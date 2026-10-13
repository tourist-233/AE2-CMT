package com.wcmt.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import appeng.api.config.Settings;
import appeng.api.config.TerminalStyle;
import appeng.api.upgrades.Upgrades;
import appeng.api.config.CopyMode;
import appeng.api.config.FuzzyMode;
import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.Icon;
import appeng.client.gui.style.Blitter;
import appeng.client.gui.widgets.IconButton;
import appeng.client.gui.widgets.Scrollbar;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.UpgradesPanel;
import appeng.core.AEConfig;
import appeng.core.localization.Tooltips;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import com.wcmt.init.ModItems;
import com.wcmt.menu.WcmtMenu;
import com.wcmt.network.DriveSnapshotPayload;
import com.wcmt.network.WcmtActionPayload;

import de.mari_023.ae2wtlib.api.gui.ScrollingUpgradesPanel;
import de.mari_023.ae2wtlib.api.gui.UpgradeBackground;
import de.mari_023.ae2wtlib.api.terminal.IUniversalTerminalCapable;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 驱动器管理 GUI。
 *
 * <p>面板由 {@code cmt_interface.png} 的多个切片拼成（256x256；有效区域为
 * x 0..208、y 90..252）：17 px 的标题栏、每个驱动器行一条 18 px 的内容行、
 * 该区域自身的 7 px 收尾边、74 px 的玩家背包块以及 11 px 的收尾边。面板高度因此为
 * {@code header + rows * 18 + edge + inventory + bottom}，
 * 随行数增减，正如 AE2 终端通过 {@code TerminalStyle.getScreenHeight(rows)} 调整尺寸那样。
 */
public class WcmtScreen extends AEBaseScreen<WcmtMenu> implements IUniversalTerminalCapable {

    // ------------------------------------------------------------------
    // cmt_interface.png（256x256；有效区域为 x 0..208、y 90..252）内的切片
    // ------------------------------------------------------------------

    private static final ResourceLocation CELL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/cmt_interface.png");
    /**
     * 终端自己的小图集：元件槽位井、排序图标和四种状态颜色。其排列方式与 AE2 自己的
     * {@code guis/states.png} 一致，因此子矩形通过源坐标寻址，而不必把每个元素切成单独文件。
     */
    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/icon.png");
    private static final int ICON_SIZE = 128;

    /**
     * 每个内容槽位周围绘制的 18x20 槽位井。其高度与 {@link #ROW_H} 一致，因此一个完整的
     * 槽位井刚好占一行，相邻槽位井不会重叠。
     */
    private static final int SLOT_U = 0;
    private static final int SLOT_V = 0;
    private static final int SLOT_W = 18;
    private static final int SLOT_H = 20;

    /** “按位置”排序图标，居中绘制在按钮 16x16 的图标区域内。 */
    private static final int SORT_ICON_U = 22;
    private static final int SORT_ICON_V = 3;
    private static final int SORT_ICON_W = 10;
    private static final int SORT_ICON_H = 13;

    /**
     * 驱动器名称行右端的优先级按钮：底图是并排的三个 16x16 方块（空闲、悬停、按下），
     * 图案另叠在中央。
     */
    private static final int PRIORITY_BUTTON_V = 0;
    private static final int PRIORITY_BUTTON_SIZE = 16;
    private static final int PRIORITY_BUTTON_U_IDLE = 37;
    private static final int PRIORITY_BUTTON_U_HOVER = 53;
    private static final int PRIORITY_BUTTON_U_DOWN = 69;
    /** 按钮为 16x16，而它的槽位保持 18x18；此值用于把按钮居中其中。 */
    private static final int PRIORITY_INSET = 1;
    /** 优先级图案：普通驱动器取第二排第三个，NeoECO 的存储矩阵取第三排第三个。 */
    private static final int PRIORITY_ICON_U = 40;
    private static final int PRIORITY_ICON_V = 18;
    private static final int PRIORITY_ICON_W = 10;
    private static final int PRIORITY_ICON_H = 11;
    private static final int ECO_PRIORITY_ICON_U = 40;
    private static final int ECO_PRIORITY_ICON_V = 35;
    private static final int ECO_PRIORITY_ICON_W = 10;
    private static final int ECO_PRIORITY_ICON_H = 10;

    /** 槽位井的底部两行：一条灰色容量凹槽叠在一条黑色边框线上。 */
    private static final int GROOVE_GRAY = 0xFF8E8F96;
    private static final int GROOVE_BLACK = 0xFF000000;
    /** 内容区域自身的 1px 边缘线，否则会被槽位井覆盖。 */
    private static final int CONTENT_EDGE = 0xFFF2F2F2;

    /** 从图集绘制的面板宽度，含其两侧边框列。 */
    private static final int PANEL_WIDTH = 209;
    /**
     * 面板右侧的额外宽度：升级面板自身的 29 px 减去它重叠的两侧边框列，使其左边框线落在
     * 图集自身的右边框线上，两者共用同一条边，而不是并排画出两条白线。
     */
    private static final int UPGRADE_COLUMN_WIDTH = 27;
    /** 屏幕总宽度，即可点击区域的宽度。 */
    private static final int SCREEN_WIDTH = PANEL_WIDTH + UPGRADE_COLUMN_WIDTH;

    /** 标签使用的纯色，与驱动器名称一致。 */
    private static final int LABEL_COLOR = 0xFF3B3B4D;
    private static final Style LABEL_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(LABEL_COLOR));

    /** 玩家背包块：三行加快捷栏，槽位井宽 16 px、间距 18 px。 */
    private static final int INV_V = 150;
    private static final int INV_W = 209;
    private static final int INV_H = 74;

    /** 背包块下方的收尾边（在图集中为 224..234）。 */
    private static final int BOTTOM_V = 224;
    private static final int BOTTOM_H = 11;

    /** 标题栏：深色边、高光、12 px 面、高光、两行深色。 */
    private static final int HEADER_V = 87;
    private static final int HEADER_H = 16;

    /** 一条内容行：驱动器列表所在的实心面周围是浅色边缘列。 */
    private static final int ROW_V = 103;
    private static final int ROW_W = PANEL_WIDTH;
    private static final int ROW_H = 20;
    /**
     * 驱动器名称行使用自己的切片，因此其高度可与元件行分开调节；该切片上下各带一条阴影线。
     */
    private static final int NAME_ROW_V = 66;
    private static final int NAME_ROW_H = 20;
    /** 驱动器图标在其名称行中相对原始大小的绘制缩放比例。 */
    private static final float NAME_ICON_SCALE = 0.75f;

    /** 内容区域自身的收尾边，紧接在最后一条内容行下方。 */
    private static final int ROW_EDGE_V = 143;
    private static final int ROW_EDGE_H = 7;
    /**
     * 当元件工作卡位于槽位中时，用它自己的工作栏取代收尾边。其尺寸固定，只有内容行会调整。
     */
    private static final ResourceLocation WORK_BAND_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/cmt_work_band.png");
    private static final int WORK_BAND_W = 208;
    private static final int WORK_BAND_H = 64;
    /** 工作栏图集自身的像素尺寸，用于让源矩形无拉伸地映射。 */
    private static final int WORK_BAND_TEX_W = 256;
    private static final int WORK_BAND_TEX_H = 256;
    /** 工作栏图集内工作栏的滚动条凹槽；滑块在其两端之间滑动。 */
    private static final int WORK_BAND_TRACK_Y = 5;
    private static final int WORK_BAND_TRACK_H = 54;
    /** 工作台的分区行一次可见的数量。 */
    private static final int CONFIG_ROWS_VISIBLE = WcmtMenu.CONFIG_ROWS_VISIBLE;
    /** 遍历分区网格的滚动步数，供工作栏自己的滚动条滑块使用。 */
    private static final int CONFIG_SCROLL_STEPS = WcmtMenu.CONFIG_SCROLL_MAX + 1;
    /** 工作栏的滚动条滑块：AE2 的小滚动条精灵图，宽度与凹槽一致。 */
    private static final ResourceLocation BAND_HANDLE_SPRITE = ResourceLocation
            .fromNamespaceAndPath("ae2", "small_scroller");

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    private static final int SLOT_X0 = 8;
    private static final int SLOT_STEP = 18;
    private static final int SLOTS_PER_ROW = WcmtMenu.SLOTS_PER_ROW;

    /** 滚动驱动器标签与其下一份副本之间的间隔，以便看清回绕。 */
    private static final int LABEL_SCROLL_GAP = 24;
    /** 滚动时标签每客户端 tick 前进的像素数。 */
    private static final float LABEL_SCROLL_SPEED = 0.5f;
    /** 一轮滚动结束后标签保持静止、再次滚动前等待的 tick 数。 */
    private static final int LABEL_SCROLL_PAUSE_TICKS = 30;

    /**
     * 背包块内的玩家槽位。槽位井从图集 x=8 开始，间距 18 px；快捷栏位于第三行背包下方
     * 22 px 处（而非 18 px）。每个偏移都指向该槽位井框的顶部。
     */
    private static final int INV_COLS = 9;
    private static final int INV_SLOT_X = 8;
    private static final int INV_ROW0 = 0;
    private static final int INV_HOTBAR = 58;

    /**
     * 背包块右侧的两条凹槽，各自从下往上显示一项进度：左侧为网络中所有元件的类型数，
     * 右侧为字节使用量。
     */
    private static final int TYPE_BAR_X = 177;
    private static final int STORAGE_BAR_X = 194;
    /**
     * 凹槽贯穿背包块的全部高度（图集 y150..223），因此填满的凹槽能无间隙地到达两端。
     */
    private static final int BAR_V = 150;
    private static final int BAR_W = 8;
    private static final int BAR_H = 74;
    /**
     * 图集中四条宽 8 px 的竖直条带（y 13..88），每种状态一条。进度凹槽直接显示它们而非纯色，
     * 因此其明暗图案来自美术资源。
     */
    private static final int BAR_STRIP_BOTTOM = 89;
    private static final int BAR_U_UNLIMITED = 93;
    private static final int BAR_U_FULL = 102;
    private static final int BAR_U_HALF = 111;
    private static final int BAR_U_LOW = 120;

    /**
     * 绘制在槽位右上角的微型类型/字节用量徽标。每个在图集中为 3x3 的方块：两列
     * （x104/x108）乘三行（y0/y4/y8），彩色圆点外有一圈黑色边框。
     */
    private static final int BADGE_SIZE = 3;
    private static final int BADGE_U_LEFT = 104;
    private static final int BADGE_U_RIGHT = 108;
    private static final int BADGE_V_TOP = 0;
    private static final int BADGE_V_MID = 4;
    private static final int BADGE_V_BOTTOM = 8;

    /** AE2 的滚动条滑块沿图集右侧的凹槽（x 194..201）滑动。 */
    private static final int SCROLLBAR_X = 191;

    /** 面板可收缩到的最小内容行预算；实际绘制的行数可能更少。 */
    private static final int MIN_ROWS = 4;
    private static final int MAX_ROWS = WcmtMenu.MAX_ROWS;
    private static final int HIDDEN = -9999;

    /** 每个可见驱动器预构建的名称+说明行；每次收到快照时重建。 */
    private Component[] driveHeaders = new Component[0];

    /**
     * 填充色，取自图集自身的色板。每个槽位的容量凹槽与整网的进度凹槽都使用同一套刻度：
     * 无限、接近满、一半、充足。
     */
    private static final int STORAGE_COLOR_EMPTY = 0x7A7F92;
    private static final int STORAGE_COLOR_LOW = 0x46FA3D;
    private static final int STORAGE_COLOR_MEDIUM = 0xFA7C3D;
    private static final int STORAGE_COLOR_HIGH = 0xFF2427;
    /** 第五个色板的颜色：提供无限「资源」而非容量的元件。 */
    private static final int STORAGE_COLOR_UNLIMITED_RESOURCE = 0xE1369C;

    /** 无限容量（报告无限字节的元件，或 NeoECO 的无限模式）。 */
    private static final int INFINITE_COLOR = 0xFFD73DFA;

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private final List<AppEngSlot> driveSlots = new ArrayList<>();
    private final Scrollbar scrollbar;
    /** 无线终端的可滚动升级面板；线缆部件形态下为 null。 */
    @Nullable
    private final ScrollingUpgradesPanel upgradesPanel;
    /** 工作台升级条带的 AE2 组件滚动条。 */
    private final Scrollbar upgradeScrollbar;
    /** 线缆部件形态的普通升级面板；无线终端下为 null。 */
    @Nullable
    private final UpgradesPanel partUpgradesPanel;
    /** 工作卡自己的升级槽，由 AE2 面板绘制，使其外观与内容区一致。 */
    @Nullable
    private final UpgradesPanel workbenchUpgradesPanel;

    private int rows = MIN_ROWS;
    /** 每个可见驱动器的第一条内容行。 */
    private int[] driveRowStart = new int[0];
    /** 每个可见驱动器的元件数量。 */
    private int[] driveCells = new int[0];
    /** 每个可见驱动器在槽位池里的起始下标。 */
    private int[] driveFirstSlot = new int[0];
    /** 槽位池下标 → 可见驱动器下标 / 驱动器内位置；随 {@link #applyLayout} 重建。 */
    private int[] slotDriveIndex = new int[0];
    private int[] slotLocalIndex = new int[0];

    private DriveSnapshotPayload lastApplied;
    private int scrollOffset;
    /** 上次向服务器请求的行，避免重复的滚轮步进重发相同请求。 */
    private int lastRequestedScroll = -1;
    /** 当前按下的优先级按钮所属的可见驱动器，无则为 -1。 */
    private int pressedPriority = -1;
    /** 当前按下的工作台按钮，无则为 -1。 */
    private int pressedBandButton = -1;
    /**
     * 下次创建此屏幕时要恢复的滚动偏移。打开驱动器的优先级屏幕会替换终端菜单，返回时由服务器
     * 重新打开；没有它，新屏幕会从第一个驱动器开始，而不是玩家离开时的位置。
     */
    private static int pendingScrollOffset = -1;
    /** 上次布局时的工作卡状态，以便变化时触发重新布局。 */
    private boolean lastHadWorkCard;
    /** 左侧工具栏的模糊模式按钮。 */
    @Nullable
    private SettingToggleButton<FuzzyMode> fuzzyButton;
    /**
     * 当前布局所基于的工作卡状态。绘制使用它而非实时值：从工作卡移除到下一次重新布局之间，
     * 图集仍按工作栏调整尺寸，若不用它绘制，会在工作栏位置短暂露出背景。
     */
    private boolean laidOutWorkCard;

    /** 用于布局的条带高度。 */
    private int shownBandHeight() {
        return bandHeight();
    }
    /**
     * 终端自身所存排序方式的镜像。每次快照都会重建，因此关闭再打开屏幕后依然保留。
     */
    private WcmtMenu.SortMode clientSortMode = WcmtMenu.SortMode.POSITION;
    private boolean clientSortDescending;

    /** 整网按类别汇总的总量，供背包旁的两条凹槽使用。 */
    private long netTypes;
    private long netTypeCapacity;
    private long netUsedBytes;
    private long netTotalBytes;
    /** 网络中存在不限字节容量的元件时为真；字节凹槽据此显示为无限。 */
    private boolean netInfiniteBytes;
    /** 网络中存在不限类型数的元件时为真（例如 omni 的通用元件）；类型凹槽据此显示为无限。 */
    private boolean netInfiniteTypes;

    public WcmtScreen(WcmtMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        this.scrollbar = widgets.addScrollBar("scrollbar", Scrollbar.BIG);
        this.upgradeScrollbar = widgets.addScrollBar("workbenchUpgradeScrollbar", Scrollbar.SMALL);
        // 该条带在 drawBG 中由 AE2WTlib 的可滚动升级条带纹理绘制（槽位与凹槽一起），
        // 因此不使用 AE2 自己的 UpgradesPanel——它的边框会叠在纹理之上。
        this.workbenchUpgradesPanel = null;
        // 无线终端使用 AE2WTlib 的可滚动面板，与通用终端相同：它要求菜单的槽位列表以量子桥
        // 奇点槽开头（WcmtMenu 已提供），并在槽位多于可容纳数量时保持该列可滚动。线缆部件形态
        // 完全没有升级库存，因此使用 AE2 的普通面板（保持为空），以占住图集的升级列。
        if (menu.getWcmtHost() != null) {
            this.upgradesPanel = addUpgradePanel(widgets, menu);
            this.partUpgradesPanel = null;
        } else {
            this.upgradesPanel = null;
            var panel = new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE));
            widgets.add("upgrades", panel);
            this.partUpgradesPanel = panel;
        }
        // 只有通用终端才有可循环切换的其他终端。
        if (menu.isWUT()) {
            addToLeftToolbar(cycleTerminalButton());
        }
        addToLeftToolbar(new SettingToggleButton<>(Settings.TERMINAL_STYLE,
                AEConfig.instance().getTerminalStyle(), this::toggleTerminalStyle));
        addToLeftToolbar(new SortButton());
        addToLeftToolbar(new SortDirectionButton());
        // 模糊模式按钮常驻工具栏，可见性由所选元件是否装了模糊卡决定（见 updateBeforeRender）。
        fuzzyButton = new SettingToggleButton<>(Settings.FUZZY_MODE, FuzzyMode.IGNORE_ALL,
                this::toggleFuzzyMode);
        addToLeftToolbar(fuzzyButton);
    }

    /**
     * 循环切换排序键。本地镜像以便图标立即响应；服务器保持权威，其下一次快照会带回相同的值。
     */
    private void cycleSort() {
        var modes = WcmtMenu.SortMode.values();
        clientSortMode = modes[(clientSortMode.ordinal() + 1) % modes.length];
        PacketDistributor.sendToServer(new WcmtActionPayload(
                WcmtActionPayload.ACTION_SORT, clientSortMode.ordinal(), 0, ""));
    }

    /** 在正序与倒序之间翻转排序方向。 */
    private void cycleSortDirection() {
        clientSortDescending = !clientSortDescending;
        PacketDistributor.sendToServer(new WcmtActionPayload(
                WcmtActionPayload.ACTION_SORT_DIRECTION, clientSortDescending ? 1 : 0, 0, ""));
    }

    /** 左侧工具栏按钮，用于循环切换排序键，使用 AE2 自己的排序精灵图。 */
    private final class SortButton extends IconButton {
        SortButton() {
            super(pressed -> cycleSort());
        }

        @Override
        protected Icon getIcon() {
            return switch (clientSortMode) {
                case POSITION -> null;
                case NAME -> Icon.SORT_BY_NAME;
                case USAGE -> Icon.SORT_BY_AMOUNT;
            };
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            // “按位置”图标没有对应的 AE2 精灵图，因此用我们自己的纹理绘制，偏移与 z 值与基类
            // 绘制图标时保持一致。
            if (clientSortMode == WcmtMenu.SortMode.POSITION) {
                Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                        .src(SORT_ICON_U, SORT_ICON_V, SORT_ICON_W, SORT_ICON_H)
                        .dest(getX() + (16 - SORT_ICON_W) / 2, getY() + 3 + (isHovered() ? 1 : 0))
                        .zOffset(3)
                        .blit(guiGraphics);
            }
        }

        @Override
        public List<Component> getTooltipMessage() {
            return List.of(
                    Component.translatable("gui.tooltips.ae2.SortBy"),
                    switch (clientSortMode) {
                        case POSITION -> Component.translatable("gui.cmt.sort.position");
                        case NAME -> Component.translatable("gui.tooltips.ae2.ItemName");
                        case USAGE -> Component.translatable("gui.tooltips.ae2.NumberOfItems");
                    });
        }
    }

    /** 左侧工具栏按钮，用于翻转排序方向。 */
    private final class SortDirectionButton extends IconButton {
        SortDirectionButton() {
            super(pressed -> cycleSortDirection());
        }

        @Override
        protected Icon getIcon() {
            return clientSortDescending ? Icon.ARROW_UP : Icon.ARROW_DOWN;
        }

        @Override
        public List<Component> getTooltipMessage() {
            return List.of(
                    Component.translatable("gui.tooltips.ae2.SortOrder"),
                    Component.translatable(clientSortDescending
                            ? "gui.tooltips.ae2.Descending" : "gui.tooltips.ae2.Ascending"));
        }
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        // 必须在 computeSize() 之前设置：图集高度取决于布局是否含工作栏，此处若用过期的值，
        // 会按上一次的工作卡状态确定图集尺寸。
        laidOutWorkCard = hasWorkCard();
        lastHadWorkCard = laidOutWorkCard;
        computeSize();
        super.init();
        collectDriveSlots();
        applyLayout(ClientDriveData.get());
        if (pendingScrollOffset >= 0) {
            scrollOffset = pendingScrollOffset;
            pendingScrollOffset = -1;
        }
        lastApplied = ClientDriveData.get();
        // 玩家背包槽位由 AE2 依据样式中的 "bottom" 偏移定位；
        // 不去动它们才能与绘制的图集保持对齐。
        positionPlayerInventory();
        // 把升级面板挂在图集右缘，与 AE2 挂在面板左缘的终端高度按钮相呼应。
        if (upgradesPanel != null) {
            upgradesPanel.setPosition(new Point(imageWidth - 29, 0));
            upgradesPanel.setMaxRows(WcmtMenu.UPGRADE_SLOTS);
        } else if (partUpgradesPanel != null) {
            partUpgradesPanel.setPosition(new Point(imageWidth - 29, 0));
        }
        if (workbenchUpgradesPanel != null) {
            // 微调以对齐工作栏自身的行：相对面板默认位置右移 2px、下移 3px。
            workbenchUpgradesPanel.setPosition(new Point(BAND_UPGRADE_X - 5 + 2, bandTop() - 3 + 3));
        }
        // 工作台升级条带的 AE2 组件滚动条，位于其右侧。
        // 工作台自己的升级滚动条复用 AE2 的组件；其可见性在 positionWorkbenchSlots 中刷新，
        // 以便选中元件后立即出现。
        updateScrollbar();
        sendLayout(scrollOffset, rows);
    }

    /** 把滚动条放到预烘焙的轨道上，并按内容行调整其尺寸。 */
    private void updateScrollbar() {
        // 内容行旁的凹槽从第一行上方的两条阴影行延伸到底部收尾边，
        // 因此轨道起点高 2 px，高度保持 rows*ROW_H + 2。
        scrollbar.setPosition(new Point(SCROLLBAR_X, contentTop() - 2));
        scrollbar.setHeight(rows * ROW_H + 2);
        // 使用服务器实际渲染所用的行数；我们自己的估算可能不同，
        // 那样会让滚动条越过末尾（或停在中途）。
        int windowRows = Math.max(1, ClientDriveData.windowRows());
        scrollbar.setRange(0, Math.max(0, ClientDriveData.totalRows() - windowRows), 1);
        // 仅在滑块尚未被触碰时设置初值；之后它归玩家所有，
        // 在此覆盖会撤销玩家刚滚动到的位置。
        if (lastRequestedScroll < 0) {
            scrollbar.setCurrentScroll(scrollOffset);
        }
        scrollbar.setVisible(true);
    }

    /** header + rows * rowHeight + bottom；行数随窗口与终端样式变化。 */
    private void computeSize() {
        int availableHeight = this.height - 2 * AEConfig.instance().getTerminalMargin();
        // 行数固定，因此图集的内容部分不会重排。图集居中，故更高的条带会把内容行上推、
        // 把玩家背包下移，各占一半。
        int availableRows = (availableHeight - HEADER_H - WORK_BAND_RESERVE - INV_H - BOTTOM_H) / ROW_H;
        int cap = Math.max(1, Math.min(MAX_ROWS, availableRows));
        // AE2 的 getRows() 会截断（cap * multiplier / 4），每个样式最多损失半行。
        // 这里改为向上取整，避免样式被克扣半行；两种最高样式保留一行余量，
        // 而不是把窗口填得满满当当。
        int multiplier = AEConfig.instance().getTerminalStyle().ordinal() + 1;
        rows = Math.max(1, (int) Math.ceil(cap * multiplier / 4.0));
        if (multiplier >= 3) {
            rows -= 1;
        }
        // 该下限仅在窗口本身过矮、无法容纳时才生效，因此在正常屏幕上不会抬高
        // 低样式（否则会让样式按钮看起来失效）。
        rows = cap >= MIN_ROWS ? Math.min(rows, cap) : MIN_ROWS;
        this.imageWidth = SCREEN_WIDTH;
        this.imageHeight = HEADER_H + rows * ROW_H + shownBandHeight() + INV_H + BOTTOM_H;    }

    private int contentTop() {
        return HEADER_H;
    }

    /**
     * 元件工作卡位于升级槽中时为真。直接来自菜单自己的升级库存，因此插入或移除工作卡会立即
     * 生效（快照中的副本最多会滞后一个扫描间隔）。
     */
    private boolean hasWorkCard() {
        return menu.hasWorkCard();
    }

    /** 当前布局所基于的工作栏高度；与 {@link #laidOutWorkCard} 一致。 */
    private int bandHeight() {
        return laidOutWorkCard ? WORK_BAND_H : ROW_EDGE_H;
    }

    /** 工作栏中预烘焙的滚动条凹槽位置（x .. x+3），便于把滑块居中其上。 */
    private static final int WORK_BAND_TRACK_X = 177;
    private static final int WORK_BAND_TRACK_W = 4;
    /** AE2 小滚动条精灵图（滑块）的尺寸，用于把它居中在凹槽上。 */
    private static final int SCROLLER_SPRITE_W = 7;
    private static final int SCROLLER_SPRITE_H = 15;
    /** 工作栏预烘焙元件网格的起点与步进，单位为工作栏本地像素。 */
    private static final int WORK_BAND_GRID_X = 8;
    private static final int WORK_BAND_GRID_Y = 7;
    private static final int WORK_BAND_GRID_STEP = 18;

    /** 分区列数，与工作栏图集的网格一致（不是驱动器网格的 10 列）。 */
    private static final int CONFIG_COLS = 9;

    /**
     * 工作台的三个按钮：16x16 的方块叠放在工作栏滚动条右侧，每个分区行一个，
     * 居中于滚动条让出的空间。第 0 列是空闲方块，第 16 列是悬停/按下的方块；
     * 图案另取，并居中叠在方块上。
     */
    private static final int BAND_BUTTON_X = 187;
    private static final int BAND_BUTTON_SIZE = 16;
    private static final int BAND_BUTTON_Y = WORK_BAND_GRID_Y;
    private static final int BAND_BUTTON_STEP = 18;
    private static final int BAND_BUTTON_U_IDLE = 0;
    private static final int BAND_BUTTON_U_ACTIVE = 16;
    /** 底图：分区与清除按钮取第二排的方块，复制按钮取第三排的。 */
    private static final int BAND_BUTTON_V_ROW2 = 21;
    private static final int BAND_BUTTON_V_ROW3 = 37;
    /** 分区按钮的图案（第二排第四个）。 */
    private static final int BAND_ICON_PARTITION_U = 56;
    private static final int BAND_ICON_PARTITION_V = 18;
    private static final int BAND_ICON_PARTITION_W = 10;
    private static final int BAND_ICON_PARTITION_H = 11;
    /** 清除按钮的图案（第二排第五个）。 */
    private static final int BAND_ICON_CLEAR_U = 73;
    private static final int BAND_ICON_CLEAR_V = 19;
    private static final int BAND_ICON_CLEAR_W = 8;
    private static final int BAND_ICON_CLEAR_H = 9;
    /** 复制按钮的图案：清空配置状态取第三排第四个，否则第五个。 */
    private static final int BAND_ICON_COPY_ON_U = 54;
    private static final int BAND_ICON_COPY_ON_V = 33;
    private static final int BAND_ICON_COPY_ON_W = 11;
    private static final int BAND_ICON_COPY_ON_H = 12;
    private static final int BAND_ICON_COPY_OFF_U = 70;
    private static final int BAND_ICON_COPY_OFF_V = 33;
    private static final int BAND_ICON_COPY_OFF_W = 11;
    private static final int BAND_ICON_COPY_OFF_H = 12;

    /** 覆盖整个槽位井的半透明绿色，用于标记选中的元件。 */
    private static final int SELECTED_CELL_HIGHLIGHT = 0x804CC919;

    /** 工作台升级槽之间的垂直间隔。 */
    private static final int BAND_UPGRADE_STEP = 18;
    /** 升级条带面板的高度：其内边距加上每个升级槽一行。 */
    private static final int BAND_UPGRADE_PANEL_HEIGHT =
            10 + WcmtMenu.WORKBENCH_UPGRADE_SLOTS * BAND_UPGRADE_STEP;
    /** 在需要小滚动条之前可容纳的工作台升级槽数量。 */
    private static final int BAND_UPGRADE_VISIBLE = 3;
    /** 工作台自身升级槽所在的列，紧邻工作栏右缘之外。 */
    private static final int BAND_UPGRADE_X = PANEL_WIDTH + 1;
    /**
     * 工作栏升级滚动条所在的列：AE2WTlib 烘焙在其可滚动升级面板中的凹槽，就位于该面板内这么深处。
     */
    /** AE2WTlib 可滚动升级条带的左缘：槽位列之前三像素。 */
    private static final int BAND_UPGRADE_PANEL_X = BAND_UPGRADE_X - 3;
    /**
     * 条带的凹槽列（宽三像素）从纹理内 21 px 处开始；滑块宽七像素，因此此值把滑块居中其上。
     */
    private static final int BAND_UPGRADE_TRACK_X = BAND_UPGRADE_PANEL_X + 19;
    /** 图集的面板颜色，用于给条带的滚动条列打底。 */
    private static final int BAND_UPGRADE_PANEL = 0xFFCBCAD4;
    /** AE2 在面板周围绘制的深色轮廓，用于封闭滚动条列。 */
    private static final int BAND_UPGRADE_BORDER = 0xFF3C3C4E;
    /** AE2 的升级槽图集；与元件工作台自身面板所用的精灵图相同。 */
    private static final ResourceLocation UPGRADE_PANEL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/extra_panels.png");
    private static final int UPGRADE_SLOT_U = 5;
    private static final int UPGRADE_SLOT_V = 5;
    private static final int UPGRADE_SLOT_SIZE = 18;
    /** 面板图集中槽位周围的内边距；同时也是单条边框的宽度。 */
    private static final int UPGRADE_PADDING = 5;
    /** AE2 在整个升级面板周围绘制的白色轮廓。 */
    private static final int UPGRADE_PANEL_OUTLINE = 0xFFF2F2F2;

    /** 工作台升级滚动步：显示的第一个升级槽。 */
    private int upgradeScroll;

    private int bottomTop() {
        return HEADER_H + rows * ROW_H + shownBandHeight();    }

    /**
     * 玩家背包的顶部。锚定在图集的<em>底部</em>，这也是绘制背包图集时的放置方式：
     * 这样图集高度变化时两者会一起移动。
     */
    private int inventoryTop() {
        return imageHeight - INV_BOTTOM;
    }

    /** 从图集下缘到玩家背包顶部的距离。正好是背包块加上其收尾边，
     * 因此背包与上方的条带之间没有缝隙。
     */
    private static final int INV_BOTTOM = INV_H + BOTTOM_H;

    /**
     * 计算内容行时为工作栏预留的垂直空间。小于工作栏自身高度，以免条带吞掉内容行，
     * 但足够避免放入工作卡时图集远超窗口。
     */
    private static final int WORK_BAND_RESERVE = 24;

    private void collectDriveSlots() {
        driveSlots.clear();
        for (Slot slot : menu.getSlots(SlotSemantics.STORAGE_CELL)) {
            if (slot instanceof AppEngSlot appEngSlot) {
                driveSlots.add(appEngSlot);
            }
        }
        for (int i = 0; i < driveSlots.size(); i++) {
            final int index = i;
            driveSlots.get(i).setEmptyTooltip(() -> emptySlotTooltip(index));
        }
        // 工作栏的升级槽：悬停空槽时说明选中的元件可接受哪些升级卡，使用 AE2 为该物品提供的列表。
        for (Slot slot : menu.getSlots(WcmtMenu.WORKBENCH_UPGRADE)) {
            if (slot instanceof AppEngSlot appEngSlot) {
                appEngSlot.setEmptyTooltip(this::workbenchUpgradeTooltip);
            }
        }
    }

    /** 空的工作栏升级槽的悬停文本：选中的元件可被哪些升级卡升级。 */
    private List<Component> workbenchUpgradeTooltip() {
        ItemStack cell = menu.getSelectedCell();
        if (cell.isEmpty()) {
            return List.of(Component.translatable("gui.cmt.upgrade.select_cell")
                    .withStyle(ChatFormatting.GRAY));
        }
        // 与 AE2 自身的升级提示同形：一行标题，然后每张兼容卡一行。
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.ae2.CompatibleUpgrades"));
        lines.addAll(Upgrades.getTooltipLinesForMachine(cell.getItem()));
        return lines;
    }

    /**
     * 把玩家背包固定到绘制图集所放的位置。AE2 依据样式中的 {@code bottom} 偏移定位这些槽位，
     * 该偏移跟随图集高度；此处完全照此镜像。
     */
    private void positionPlayerInventory() {
        List<Slot> main = menu.getSlots(SlotSemantics.PLAYER_INVENTORY);
        for (int i = 0; i < main.size(); i++) {
            main.get(i).x = INV_SLOT_X + (i % INV_COLS) * SLOT_STEP;
            main.get(i).y = inventoryTop() + INV_ROW0 + (i / INV_COLS) * SLOT_STEP;
        }
        List<Slot> hotbar = menu.getSlots(SlotSemantics.PLAYER_HOTBAR);
        for (int i = 0; i < hotbar.size(); i++) {
            hotbar.get(i).x = INV_SLOT_X + i * SLOT_STEP;
            hotbar.get(i).y = inventoryTop() + INV_HOTBAR;
        }
    }

    // ------------------------------------------------------------------
    // 每个驱动器的布局（来自最新快照）
    // ------------------------------------------------------------------

    private void applyLayout(DriveSnapshotPayload data) {
        List<DriveSnapshotPayload.DriveInfo> drives = data == null ? List.of() : data.drives();
        int count = drives.size();
        updateNetTotals();

        driveRowStart = new int[count];
        driveCells = new int[count];

        // 无法连接网络的终端（或没有能放进去的东西）只显示原因，因此隐藏所有槽位与所有行背景。
        boolean blocked = data != null && data.blocked();

        driveHeaders = new Component[count];
        for (int k = 0; k < count; k++) {
            DriveSnapshotPayload.DriveInfo info = drives.get(k);
            long used = 0;
            long total = 0;
            boolean unlimited = false;
            boolean measured = false;
            for (DriveSnapshotPayload.SlotStat stat : info.slots()) {
                if (stat.infinite()) {
                    unlimited = true;
                    continue;
                }
                if (stat.total() <= 0) {
                    // 计数异常的元件（巨大的哨兵值可能为负）与无限元件一样不计入驱动器总量。
                    continue;
                }
                measured = true;
                used += stat.used();
                total += stat.total();
            }
            driveHeaders[k] = Component.empty()
                    .append(info.name().copy().withStyle(LABEL_STYLE))
                    .append(Component.literal("  "))
                    .append(!measured && unlimited ? infiniteText() : storageText(used, total));
        }

        int rowCursor = -(data == null ? 0 : data.skippedRows());
        int cellCursor = 0;
        int slotCursor = 0;
        driveFirstSlot = new int[count];
        if (slotDriveIndex.length < driveSlots.size()) {
            slotDriveIndex = new int[driveSlots.size()];
            slotLocalIndex = new int[driveSlots.size()];
        }
        Arrays.fill(slotDriveIndex, 0, driveSlots.size(), -1);
        for (int k = 0; k < count; k++) {
            int cells = drives.get(k).slots().size();
            int driveRows = WcmtMenu.rowsForCells(cells);
            driveRowStart[k] = rowCursor;
            driveCells[k] = cells;
            driveFirstSlot[k] = slotCursor;
            // 槽位到驱动器的映射在这里一次算好：槽位循环里再逐个线性查找会随驱动器数量放大。
            for (int local = 0; local < cells && slotCursor < driveSlots.size(); local++, slotCursor++) {
                slotDriveIndex[slotCursor] = k;
                slotLocalIndex[slotCursor] = local;
            }
            rowCursor += driveRows;
            cellCursor += cells;
        }

        // 名称行使用自己的切片绘制；其下方的一切都是元件行。
        nameRows = new boolean[Math.max(0, rowCursor)];
        for (int k = 0; k < count; k++) {
            int start = driveRowStart[k];
            if (start >= 0 && start < nameRows.length) {
                nameRows[start] = true;
            }
        }

        for (int i = 0; i < driveSlots.size(); i++) {
            AppEngSlot slot = driveSlots.get(i);
            int local = slotLocalIndex[i];
            int rowIndex = -1;
            if (slotDriveIndex[i] >= 0) {
                rowIndex = driveRowStart[slotDriveIndex[i]] + 1 + local / SLOTS_PER_ROW;
                if (rowIndex < 0 || rowIndex >= rows) {
                    rowIndex = -1;
                }
            }
            if (blocked || i >= cellCursor || rowIndex < 0) {
                hide(slot);
                continue;
            }
            slot.x = SLOT_X0 + (local % SLOTS_PER_ROW) * SLOT_STEP;
            slot.y = contentTop() + rowIndex * ROW_H;
        }

        positionWorkbenchSlots();
    }

    /** 内容行下方条带的上缘（工作栏，或普通收尾边）。 */
    private int bandTop() {
        return contentTop() + rows * ROW_H;
    }

    /**
     * 在工作栏内布置工作卡的小工作台：一个 9 列分区网格，显示当前滚动步的三行、它的三个按钮，
     * 以及图集右缘的升级槽。没有工作卡时一切隐藏，工作栏保持普通收尾边。
     */
    private void positionWorkbenchSlots() {
        boolean active = laidOutWorkCard;
        var configs = menu.getSlots(SlotSemantics.CONFIG);
        int firstRow = ClientDriveData.configScroll();
        for (int i = 0; i < configs.size(); i++) {
            Slot slot = configs.get(i);
            int row = i / CONFIG_COLS;
            int col = i % CONFIG_COLS;
            if (active && row >= firstRow && row < firstRow + WcmtMenu.CONFIG_ROWS_VISIBLE) {
                // 与工作栏图集自身的网格对齐：9 列，间距 18 px。
                slot.x = WORK_BAND_GRID_X + col * WORK_BAND_GRID_STEP;
                slot.y = bandTop() + WORK_BAND_GRID_Y + (row - firstRow) * WORK_BAND_GRID_STEP;
            } else {
                hide(slot);
            }
        }

        // 图集右缘的升级条带：只启用当前滚动步可见的三个槽，让 AE2 自己的面板绘制它们
        // （且仅绘制它们）。
        // 该条带仅在配置元件时出现，与 AE2 的元件工作台一致：其升级槽跟随槽位中的元件，
        // 提供的槽位跟随选中的元件。
        // 滚动条既由滚轮驱动也支持点击；槽位跟随它的取值，
        // 否则点击轨道会移动滑块却不移动槽位。
        int scrolled = upgradeScrollbar.getCurrentScroll();
        if (scrolled != upgradeScroll) {
            upgradeScroll = scrolled;
        }
        // 条带纹理已包含槽位边框，因此把槽位放置其上：每行一个，从条带内五像素处开始——
        // 与 AE2WTlib 布置自己槽位的方式相同。
        // 元件不接受升级卡时连条带贴图都不画（例如创造元件：能标过滤但不能装卡）。
        boolean showUpgrades = active && menu.getWorkbenchUpgradeCount() > 0;
        int count = menu.getWorkbenchUpgradeCount();
        var upgradeSlots = menu.getSlots(WcmtMenu.WORKBENCH_UPGRADE);
        for (int i = 0; i < upgradeSlots.size(); i++) {
            var slot = upgradeSlots.get(i);
            boolean show = showUpgrades && i >= upgradeScroll
                    && i < upgradeScroll + BAND_UPGRADE_VISIBLE && i < count;
            if (slot instanceof AppEngSlot appEngSlot) {
                appEngSlot.setSlotEnabled(show);
            }
            if (show) {
                // 把槽位与条带中绘制的 16x16 元件格对齐：位于其左缘内一像素，
                // 使槽位自己的 18x18 边框覆盖该格而不是悬在其外。
                slot.x = BAND_UPGRADE_PANEL_X + 1;
                // +1 使槽位自己的 18x18 边框正好落在条带的 18 px 元件格上。
                slot.y = bandTop() + UPGRADE_PADDING + 1
                        + (i - upgradeScroll) * BAND_UPGRADE_STEP;
            } else {
                hide(slot);
            }
        }

        // 在此同步（而非仅在 init 时），以便选中元件后滚动条立即出现。
        // 条带自行处理滚轮，因此滚动条不能吞掉滚轮事件（与 WCWT 相同）。
        upgradeScrollbar.setCaptureMouseWheel(false);
        upgradeScrollbar.setPosition(new Point(BAND_UPGRADE_TRACK_X, bandTop() + UPGRADE_PADDING));
        upgradeScrollbar.setHeight(BAND_UPGRADE_VISIBLE * BAND_UPGRADE_STEP - 2);
        upgradeScrollbar.setRange(0, maxUpgradeScroll(), 1);
        upgradeScrollbar.setVisible(maxUpgradeScroll() > 0 && showUpgrades);
    }

    /** 工作栏升级条带可滚动到的最大首槽位索引。 */
    private int maxUpgradeScroll() {
        return Math.max(0, menu.getWorkbenchUpgradeCount() - BAND_UPGRADE_VISIBLE);
    }

    private int driveIndexForSlot(int index, int count) {
        int acc = 0;
        for (int k = 0; k < count; k++) {
            if (index < acc + driveCells[k]) {
                return k;
            }
            acc += driveCells[k];
        }
        return -1;
    }

    private static void hide(Slot slot) {
        slot.x = HIDDEN;
        slot.y = HIDDEN;
    }

    private List<Component> emptySlotTooltip(int slotIndex) {
        int k = driveIndexForSlot(slotIndex, ClientDriveData.drives().size());
        if (k < 0) {
            return List.of();
        }
        return List.of(Component.translatable("gui.cmt.empty_slot", ClientDriveData.drives().get(k).name()));
    }

    // ------------------------------------------------------------------
    // 滚动 / 终端样式
    // ------------------------------------------------------------------

    private void sendLayout(int offset, int windowRows) {
        PacketDistributor.sendToServer(
                new WcmtActionPayload(WcmtActionPayload.ACTION_LAYOUT, Math.max(0, offset), windowRows, ""));
    }

    @Override
    public WTMenuHost getHost() {
        return menu.getWcmtHost();
    }

    @Override
    public boolean isHandlingRightClick() {
        return false;
    }

    @Override
    public void storeState() {
        // 终端把状态保存在菜单中，因此 AE2WTlib 切换离开此终端时，客户端没有需要保留的内容。
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        var data = ClientDriveData.get();
        if (data != lastApplied) {
            lastApplied = data;
            applyLayout(data);
            // 快照是唯一能移动视图的东西：滚轮只是向服务器请求新的偏移，
            // 因此内容与滚动偏移始终描述同一个窗口。
            scrollOffset = data == null ? 0 : data.offset();
            updateScrollbar();
            // 排序保存在终端上而非此屏幕中，因此采用服务器上报的值：
            // 重新打开终端时会显示离开时的排序键与方向。
            clientSortMode = ClientDriveData.sortMode();
            clientSortDescending = ClientDriveData.sortDescending();
        }

        // 与原版元件工作台一致：按钮常驻工具栏，只在所选元件装了模糊卡时可见。
        fuzzyButton.set(menu.getFuzzyMode());
        fuzzyButton.setVisibility(menu.hasFuzzyCard());

        // 滚轮/轨道只选取新的行；待服务器确认后视图才跟随。
        int scrolled = scrollbar.getCurrentScroll();
        if (scrolled != lastRequestedScroll) {
            lastRequestedScroll = scrolled;
            sendLayout(scrolled, rows);
        }
    }

    private void toggleTerminalStyle(SettingToggleButton<TerminalStyle> button, boolean backwards) {
        TerminalStyle next = button.getNextValue(backwards);
        AEConfig.instance().setTerminalStyle(next);
        button.set(next);
        reinitialize();
    }

    /** 移除所有组件后重新执行 {@link #init()}（与 AE2 终端相同的模式）。 */
    private void reinitialize() {
        new ArrayList<>(this.children()).forEach(this::removeWidget);
        this.init();
    }

    /** 模糊模式按钮：本地先切换，服务端写进所选元件，下一次渲染会从元件读回真实值。 */
    private void toggleFuzzyMode(SettingToggleButton<FuzzyMode> button, boolean backwards) {
        FuzzyMode next = button.getNextValue(backwards);
        button.set(next);
        PacketDistributor.sendToServer(new WcmtActionPayload(
                WcmtActionPayload.ACTION_FUZZY_MODE, next.ordinal(), 0, ""));
    }

    /**
     * 工作卡出现或消失时重建屏幕，以便布置条带的内容。
     */
    @Override
    public void containerTick() {
        super.containerTick();
        if (hasWorkCard() != lastHadWorkCard) {
            lastHadWorkCard = hasWorkCard();
            reinitialize();
        }
        // 升级条带跟随选中的元件，而选中变化不会重新布局屏幕，
        // 因此每个 tick 都同步其槽位与滚动条。
        positionWorkbenchSlots();
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);

        blit(guiGraphics, offsetX, offsetY, 0, HEADER_V, PANEL_WIDTH, HEADER_H);

        int y = offsetY + contentTop();
        for (int row = 0; row < rows; row++) {
            boolean nameRow = row < nameRows.length && nameRows[row];
            // 名称行切片上下各带一条阴影线；将其上移一像素，使这些线与其上下的元件槽位井相接
            // （槽位井也位于 slot.y - 1）。
            blit(guiGraphics, offsetX, nameRow ? y - 1 : y, 0, nameRow ? NAME_ROW_V : ROW_V, ROW_W,
                    nameRow ? NAME_ROW_H : ROW_H);
            if (nameRow) {
                // 上移切片会使其最后一条线在元件槽位井之外露出来，
                // 表现为两侧边缘的黑线；因此在其下铺垫行背景。
                blit(guiGraphics, offsetX, y + ROW_H - 1, 0, ROW_V, ROW_W, 1);
            }
            y += ROW_H;
        }

        // 元件槽位井。18x20 的槽位井正好一行高，因此槽位井不会重叠；随后其底部两行会被
        // 容量凹槽（灰色底、黑色边框）覆盖，填充色就画在其中。
        // 两者都只对真正装有元件的槽位显示。
        for (AppEngSlot slot : driveSlots) {
            if (!slot.isActive() || slot.x < 0 || slot.y < 0) {
                continue;
            }
            int sx = offsetX + slot.x;
            int sy = offsetY + slot.y;
            Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                    .src(SLOT_U, SLOT_V, SLOT_W, SLOT_H)
                    .dest(sx - 1, sy - 1)
                    .blit(guiGraphics);
            if (!slot.getItem().isEmpty()) {
                guiGraphics.fill(sx, sy + SLOT_H - 4, sx + SLOT_W - 2, sy + SLOT_H - 3, GROOVE_GRAY);
                guiGraphics.fill(sx, sy + SLOT_H - 3, sx + SLOT_W - 2, sy + SLOT_H - 2, GROOVE_BLACK);
            }
        }

        // 这些槽位井正好横跨槽位列，因此会盖住内容区域自身的左右边缘线；
        // 把两条线重新画在上面。
        int edgeTop = offsetY + contentTop();
        int edgeBottom = offsetY + bottomTop();
        guiGraphics.fill(offsetX + SLOT_X0 - 1, edgeTop, offsetX + SLOT_X0, edgeBottom, CONTENT_EDGE);
        int edgeRight = SLOT_X0 + (SLOTS_PER_ROW - 1) * SLOT_STEP + 16;
        guiGraphics.fill(offsetX + edgeRight, edgeTop, offsetX + edgeRight + 1, edgeBottom, CONTENT_EDGE);

        // 内容区域自身的收尾边，然后是背包块（图集把两者紧挨着排布，
        // 因此不能让它们相互错位）。
        if (laidOutWorkCard) {
            guiGraphics.blit(WORK_BAND_TEXTURE, offsetX, y, 0, 0, WORK_BAND_W, WORK_BAND_H,
                    WORK_BAND_TEX_W, WORK_BAND_TEX_H);
            // AE2 的 Scrollbar 只绘制滑块——其轨道必须烘焙进背景，而条带的滚动条列位于升级面板
            // 之外（升级面板止于 x 234），落在裸露的世界背景上。先在其下铺垫图集的面板颜色，
            // 再画取自主图集的凹槽（一条平整的竖直凹槽，因此平铺无缝），最后用白色边缘线封闭该列，
            // 使其看起来是面板的一部分。
            // 仅在所选元件接受升级卡时绘制，与滚动条滑块自身的可见性一致。该列是带白色轮廓的浅色面板，
            // 图集的凹槽——由白线镶边的窄阴影——嵌入其中，与 AE2 自己的图集给滚动条轨道的观感相同。
            // AE2WTlib 的可滚动升级条带，完全按 WCWT 的绘制方式：顶部片段承载一个元件格，
            // 底部片段承载最后一个，中间放 (visible - 2) 个片段——因此三个可见格即一个中间片段，
            // 第四个格通过滚动到达。
            if (menu.getWorkbenchUpgradeCount() > 0) {
                var bg = UpgradeBackground.get(true);
                int px = offsetX + BAND_UPGRADE_PANEL_X;
                int py = offsetY + bandTop() + UPGRADE_PADDING;
                bg.top().getBlitter().dest(px, py - UPGRADE_PADDING).blit(guiGraphics);
                for (int i = 1; i < BAND_UPGRADE_VISIBLE - 1; i++) {
                    bg.middle().getBlitter().dest(px, py + i * BAND_UPGRADE_STEP).blit(guiGraphics);
                }
                bg.bottom().getBlitter().dest(px, py + (BAND_UPGRADE_VISIBLE - 1) * BAND_UPGRADE_STEP)
                        .blit(guiGraphics);
            }
            // 工作栏自己的滚动条：凹槽已烘焙进图集，因此这里只绘制其滑块，
            // 与 AE2 自己的滚动条一样使用原版滚动条精灵图。
            int travel = WORK_BAND_TRACK_H - SCROLLER_SPRITE_H;
            int step = CONFIG_SCROLL_STEPS <= 1 ? 0 : travel / (CONFIG_SCROLL_STEPS - 1);
            int handleY = y + WORK_BAND_TRACK_Y
                    + Math.min(travel, Math.max(0, ClientDriveData.configScroll()) * step);
            // 把滑块居中在凹槽上，并向中心左侧微调一像素以匹配图集。
            int handleX = offsetX + WORK_BAND_TRACK_X
                    + (WORK_BAND_TRACK_W - SCROLLER_SPRITE_W) / 2 - 1;
            Blitter.guiSprite(BAND_HANDLE_SPRITE).dest(handleX, handleY).blit(guiGraphics);

            // 分区格，使用 AE2 自己的元件工作台精灵图绘制：工作台槽中有元件时实心，为空时变暗。
            // 每个格一个精灵图，已烘焙进图集。
            var configs = menu.getSlots(SlotSemantics.CONFIG);
            for (int i = 0; i < configs.size(); i++) {
                Slot slot = configs.get(i);
                if (slot.x < 0 || slot.y < 0) {
                    continue;
                }
                // 仍是 AE2 自己的槽位精灵图。槽位不可编辑时不是变透明而是调暗：
                // AE2 的 0.2 alpha 依赖图集下方露出深色网格，而此工作栏图集没有，
                // 因此透明会显得“完全没有网格”。
                //
                // 判据统一问菜单：它能同时反映选中元件、元件自身能配置多少格，以及复制模式。
                boolean editable = menu.isPartitionEditable(i);
                // 与 AE2 的 AEBaseScreen.drawOptionalSlotBackground 完全一致：槽位可编辑时以
                // 全不透明度绘制图集自身的槽位精灵图，不可编辑时用 AE2 自己的 0.2 明暗。
                // 由于此时槽位也报告自身为非活动，Minecraft 不再在其上绘制原版槽位背景，
                // 因此明暗能真正显示出来。
                float alpha = editable ? 1.0f : 0.2f;
                Blitter.texture(Icon.TEXTURE, Icon.TEXTURE_WIDTH, Icon.TEXTURE_HEIGHT)
                        .src(Icon.SLOT_BACKGROUND.x, Icon.SLOT_BACKGROUND.y,
                                Icon.SLOT_BACKGROUND.width, Icon.SLOT_BACKGROUND.height)
                        .dest(offsetX + slot.x - 1, offsetY + slot.y - 1)
                        .color(1f, 1f, 1f, alpha)
                        .blit(guiGraphics);
            }
        } else {
            blit(guiGraphics, offsetX, y, 0, ROW_EDGE_V, PANEL_WIDTH, ROW_EDGE_H);
        }
        // 背包图集及其收尾边锚定在图集底部，与玩家背包槽位一致
        // （三者必须共用同一锚点，否则会明显错开）。
        int invTop = offsetY + inventoryTop();
        blit(guiGraphics, offsetX, invTop, 0, INV_V, INV_W, INV_H);
        blit(guiGraphics, offsetX, invTop + INV_H, 0, BOTTOM_V, PANEL_WIDTH, BOTTOM_H);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        List<DriveSnapshotPayload.DriveInfo> drives = ClientDriveData.drives();
        boolean blocked = ClientDriveData.blocked();

        for (int k = 0; k < drives.size() && !blocked && k < driveRowStart.length; k++) {
            DriveSnapshotPayload.DriveInfo info = drives.get(k);
            int headerRow = driveRowStart[k];
            int headerY = contentTop() + headerRow * ROW_H;

            // 滚动到窗口一半外的驱动器行索引为负或过小；其名称行不得绘制在面板之外。
            if (headerRow >= 0 && headerRow < rows && k < driveHeaders.length) {
                // 图标永不移动；只有标签滚动，且仅在放不下时才滚动。
                guiGraphics.pose().pushPose();
                // 更小的驱动器图标，居中在它原本占满的 16x16 格内。图标自身的等距形状向右倾斜，
                // 因此内缩量略小于余量的一半。
                float iconInset = (16 - 16 * NAME_ICON_SCALE) / 2f - 0.5f;
                guiGraphics.pose().translate(SLOT_X0 + iconInset, headerY + 1 + iconInset + 0.5f, 0f);
                guiGraphics.pose().scale(NAME_ICON_SCALE, NAME_ICON_SCALE, 1f);
                guiGraphics.renderItem(info.icon(), 0, 0);
                guiGraphics.pose().popPose();
                drawDriveLabel(guiGraphics, driveHeaders[k], offsetX, offsetY, headerY);
                drawPriorityButton(guiGraphics, k, mouseX, mouseY);
            }

            for (int local = 0; local < driveCells[k] && local < info.slots().size(); local++) {
                int slotIndex = driveFirstSlot[k] + local;
                if (slotIndex < 0 || slotIndex >= driveSlots.size()) {
                    continue;
                }
                AppEngSlot slot = driveSlots.get(slotIndex);
                if (slot.x == HIDDEN) {
                    continue;
                }
                if (!slot.getItem().isEmpty()) {
                    drawCapacityBar(guiGraphics, slot.x, slot.y + ROW_H - 4, info.slots().get(local));
                    drawCellBadge(guiGraphics, slot.x, slot.y, info.slots().get(local));
                }
            }
        }

        drawWorkBandButtons(guiGraphics, mouseX, mouseY);
        drawSelectedCellBox(guiGraphics);
        drawProgressBars(guiGraphics);
        drawLinkStatus(guiGraphics);
    }

    /** 三个工作台按钮：分区、清除和复制模式。 */
    private static final int BAND_BUTTON_COUNT = 3;

    private void drawWorkBandButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (!laidOutWorkCard) {
            return;
        }
        for (int i = 0; i < BAND_BUTTON_COUNT; i++) {
            boolean active = bandButtonActive(i, mouseX, mouseY);
            int x = BAND_BUTTON_X;
            int y = bandButtonTop(i);
            Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                    .src(BAND_BUTTON_U_IDLE + (active ? BAND_BUTTON_U_ACTIVE : 0),
                            i < 2 ? BAND_BUTTON_V_ROW2 : BAND_BUTTON_V_ROW3,
                            BAND_BUTTON_SIZE, BAND_BUTTON_SIZE)
                    .dest(x, y)
                    .blit(guiGraphics);
            // 图案居中叠在方块上；各图案的尺寸不同，按自己的尺寸对齐。
            SheetIcon icon = bandButtonIcon(i);
            Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                    .src(icon.u(), icon.v(), icon.w(), icon.h())
                    .dest(x + (BAND_BUTTON_SIZE - icon.w()) / 2,
                            y + (BAND_BUTTON_SIZE - icon.h()) / 2)
                    .zOffset(3)
                    .blit(guiGraphics);
        }
    }

    /** 工作台按钮 {@code i} 在 GUI 空间中的上缘。 */
    private int bandButtonTop(int i) {
        return bandTop() + BAND_BUTTON_Y + i * BAND_BUTTON_STEP;
    }

    /** 鼠标位于按钮 {@code i} 上（或该按钮被按住）时为真，此时使用点亮精灵图。 */
    private boolean bandButtonActive(int i, int mouseX, int mouseY) {
        return pressedBandButton == i
                || isHovering(BAND_BUTTON_X, bandButtonTop(i), BAND_BUTTON_SIZE, BAND_BUTTON_SIZE, mouseX, mouseY);
    }

    /**
     * 按钮在图集中的行：分区、清除，然后复制（其两行对应复制模式状态）。
     * 图集的第二列比第一列低一行，因此点亮变体偏移一行。
     */
    /** 图集里的一块图案；各图案的尺寸不同，绘制时按自身尺寸居中。 */
    private record SheetIcon(int u, int v, int w, int h) {
    }

    /** 按钮 {@code i} 的图案：分区、清除，以及复制按钮的两种状态。 */
    private SheetIcon bandButtonIcon(int i) {
        return switch (i) {
            case 0 -> new SheetIcon(BAND_ICON_PARTITION_U, BAND_ICON_PARTITION_V,
                    BAND_ICON_PARTITION_W, BAND_ICON_PARTITION_H);
            case 1 -> new SheetIcon(BAND_ICON_CLEAR_U, BAND_ICON_CLEAR_V,
                    BAND_ICON_CLEAR_W, BAND_ICON_CLEAR_H);
            default -> menu.getCopyMode() == CopyMode.CLEAR_ON_REMOVE.ordinal()
                    ? new SheetIcon(BAND_ICON_COPY_ON_U, BAND_ICON_COPY_ON_V,
                            BAND_ICON_COPY_ON_W, BAND_ICON_COPY_ON_H)
                    : new SheetIcon(BAND_ICON_COPY_OFF_U, BAND_ICON_COPY_OFF_V,
                            BAND_ICON_COPY_OFF_W, BAND_ICON_COPY_OFF_H);
        };
    }

    /** 播放原版按钮点击音，与 AE2 自己（绘制的）按钮一致。 */
    private static void playClickSound() {
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** 鼠标下的工作台按钮，无则为 -1。 */
    private int bandButtonAt(double mouseX, double mouseY) {
        if (!laidOutWorkCard) {
            return -1;
        }
        for (int i = 0; i < BAND_BUTTON_COUNT; i++) {
            if (isHovering(BAND_BUTTON_X, bandButtonTop(i), BAND_BUTTON_SIZE, BAND_BUTTON_SIZE, mouseX, mouseY)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 用半透明绿色覆盖槽位井，标记玩家上次右键点击的元件。由 {@code drawFG} 绘制，
     * 即与标签一起；容器会先绘制它们再绘制槽位，因此绿色显示在槽位井本身之上，
     * 而元件物品保持不受影响地位于顶层。
     */
    private void drawSelectedCellBox(GuiGraphics guiGraphics) {
        // 取菜单里的选中值：右键时它已被本地预测更新，不必等下一次快照。
        int selected = menu.getSelectedSlot();
        if (selected < 0 || !laidOutWorkCard) {
            return;
        }
        for (var slot : menu.getSlots(SlotSemantics.STORAGE_CELL)) {
            if (slot.index == selected && slot.x != HIDDEN) {
                guiGraphics.fill(slot.x - 1, slot.y - 1, slot.x - 1 + SLOT_W, slot.y - 1 + SLOT_H,
                        SELECTED_CELL_HIGHLIGHT);
                return;
            }
        }
    }

    /** 鼠标下元件所在的驱动器槽索引，无则为 -1；右键应用时使用。 */
    private int cellSlotAt(double mouseX, double mouseY) {
        for (var slot : menu.getSlots(SlotSemantics.STORAGE_CELL)) {
            if (slot.x == HIDDEN || slot.getItem().isEmpty()) {
                continue;
            }
            if (isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                return slot.index;
            }
        }
        return -1;
    }

    /**
     * 在工作栏上滚动滚轮会步进其分区网格；在工作台升级槽上滚动则步进这些槽。
     * 在其他任何地方则滚动驱动器列表。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (laidOutWorkCard && scrollY != 0) {
            int top = getGuiTop() + bandTop();
            int left = getGuiLeft();
            // 图集右缘的升级条带，但仅在选中元件时。
            if (menu.getSelectedSlot() >= 0 && maxUpgradeScroll() > 0
                    && mouseX >= left + BAND_UPGRADE_X - 2
                    && mouseX < left + BAND_UPGRADE_TRACK_X + SCROLLER_SPRITE_W
                    && mouseY >= top && mouseY < top + WORK_BAND_H) {
                int next = Math.max(0, Math.min(maxUpgradeScroll(),
                        upgradeScroll + (scrollY > 0 ? -1 : 1)));
                if (next != upgradeScroll) {
                    upgradeScroll = next;
                    upgradeScrollbar.setCurrentScroll(upgradeScroll);
                    positionWorkbenchSlots();
                }
                return true;
            }
            if (mouseX >= left && mouseX < left + PANEL_WIDTH
                    && mouseY >= top && mouseY < top + WORK_BAND_H) {
                int next = Math.max(0, Math.min(WcmtMenu.CONFIG_SCROLL_MAX,
                        ClientDriveData.configScroll() + (scrollY > 0 ? -1 : 1)));
                if (next != ClientDriveData.configScroll()) {
                    PacketDistributor.sendToServer(
                            new WcmtActionPayload(WcmtActionPayload.ACTION_CONFIG_SCROLL, next, 0, ""));
                }
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 在驱动器图标旁绘制其名称（及容量读数）。宽度超出该行的标签会循环向左滚动——
     * 完整走一轮后短暂停顿——直到优先级按钮；较短的标签则原样绘制。
     */
    private void drawDriveLabel(GuiGraphics guiGraphics, Component label, int offsetX, int offsetY, int rowY) {
        int left = SLOT_X0 + 18;
        int right = priorityButtonX();
        int textY = rowY + 5;
        int width = font.width(label);
        if (width <= right - left) {
            guiGraphics.drawString(font, label.getVisualOrderText(), left, textY, LABEL_COLOR, false);
            return;
        }
        guiGraphics.enableScissor(offsetX + left, offsetY + rowY, offsetX + right, offsetY + rowY + ROW_H);
        int span = width + LABEL_SCROLL_GAP;
        // 滚动一轮，然后停顿，如此循环往复。
        int runTicks = Math.round(span / LABEL_SCROLL_SPEED);
        int tick = Minecraft.getInstance().gui.getGuiTicks() % (runTicks + LABEL_SCROLL_PAUSE_TICKS);
        int shift = tick < runTicks ? (int) (tick * LABEL_SCROLL_SPEED) : 0;
        FormattedCharSequence text = label.getVisualOrderText();
        guiGraphics.drawString(font, text, left - shift, textY, LABEL_COLOR, false);
        guiGraphics.drawString(font, text, left - shift + span, textY, LABEL_COLOR, false);
        guiGraphics.disableScissor();
    }

    /** 某行优先级按钮的左缘（GUI 坐标），位于最后一个内容槽位正上方。 */
    private int priorityButtonX() {
        return SLOT_X0 + (SLOTS_PER_ROW - 1) * SLOT_STEP - 2;
    }

    /** 可见驱动器 {@code k} 名称行的上缘（GUI 坐标）；在屏幕外时为 -1。 */
    private int priorityButtonY(int k) {
        int headerRow = k < driveRowStart.length ? driveRowStart[k] : -1;
        if (headerRow < 0 || headerRow >= rows) {
            return -1;
        }
        return contentTop() + headerRow * ROW_H;
    }

    /** 哪些内容行是驱动器名称行；它们使用 {@link #NAME_ROW_V}。 */
    private boolean[] nameRows = new boolean[0];

    /** 可见驱动器 {@code k} 优先级按钮的上缘（GUI 坐标）；在屏幕外时为 -1。 */
    private int priorityButtonTop(int k) {
        return priorityButtonY(k);
    }

    /** 每个驱动器用于打开其自身优先级屏幕的按钮；其图标显示状态。 */
    private void drawPriorityButton(GuiGraphics guiGraphics, int drive, int mouseX, int mouseY) {
        int by = priorityButtonTop(drive);
        if (by < 0) {
            return;
        }
        // 16x16 的按钮居中在它 18x18 的槽位内。
        int bx = priorityButtonX() + PRIORITY_INSET;
        by += PRIORITY_INSET;
        int u = PRIORITY_BUTTON_U_IDLE;
        if (pressedPriority == drive) {
            u = PRIORITY_BUTTON_U_DOWN;
        } else if (isHovering(bx, by, PRIORITY_BUTTON_SIZE, PRIORITY_BUTTON_SIZE, mouseX, mouseY)) {
            u = PRIORITY_BUTTON_U_HOVER;
        }
        // drawFG 使用屏幕相对坐标，与其旁的图标和标签一样。
        Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                .src(u, PRIORITY_BUTTON_V, PRIORITY_BUTTON_SIZE, PRIORITY_BUTTON_SIZE)
                .dest(bx, by)
                .blit(guiGraphics);

        // 图案居中叠在底图上：NeoECO 的存储矩阵用自己那一枚。
        boolean eco = isEcoDrive(drive);
        int iconU = eco ? ECO_PRIORITY_ICON_U : PRIORITY_ICON_U;
        int iconV = eco ? ECO_PRIORITY_ICON_V : PRIORITY_ICON_V;
        int iconW = eco ? ECO_PRIORITY_ICON_W : PRIORITY_ICON_W;
        int iconH = eco ? ECO_PRIORITY_ICON_H : PRIORITY_ICON_H;
        Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                .src(iconU, iconV, iconW, iconH)
                .dest(bx + (PRIORITY_BUTTON_SIZE - iconW) / 2,
                        by + (PRIORITY_BUTTON_SIZE - iconH) / 2)
                .zOffset(3)
                .blit(guiGraphics);
    }

    /** 该驱动器是否是 NeoECO 的存储矩阵；优先级图案随之不同。 */
    private boolean isEcoDrive(int drive) {
        var drives = ClientDriveData.drives();
        return drive >= 0 && drive < drives.size() && drives.get(drive).eco();
    }

    /** 优先级按钮是绘制出来的而非组件（每个可见驱动器一个）。 */
    private int priorityButtonAt(double mouseX, double mouseY) {
        for (int k = 0; k < ClientDriveData.drives().size(); k++) {
            int by = priorityButtonTop(k);
            if (by >= 0 && isHovering(priorityButtonX() + PRIORITY_INSET, by + PRIORITY_INSET,
                    PRIORITY_BUTTON_SIZE, PRIORITY_BUTTON_SIZE, mouseX, mouseY)) {
                return k;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int hit = priorityButtonAt(mouseX, mouseY);
            if (hit >= 0) {
                pressedPriority = hit;
                return true;
            }
            int band = bandButtonAt(mouseX, mouseY);
            if (band >= 0) {
                pressedBandButton = band;
                return true;
            }
        } else if (button == 1) {
            int cell = menu.hasWorkCard() ? cellSlotAt(mouseX, mouseY) : -1;
            if (cell >= 0) {
                // 本地采用新选中项，使网格与升级条带立即响应；
                // 右键点击已选中的元件会取消选中。
                menu.predictSelection(cell == menu.getSelectedSlot() ? -1 : cell);
                PacketDistributor.sendToServer(new WcmtActionPayload(
                        WcmtActionPayload.ACTION_APPLY_CELL, cell, 0, ""));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pressedPriority >= 0) {
            int hit = pressedPriority;
            pressedPriority = -1;
            if (priorityButtonAt(mouseX, mouseY) == hit) {
                pendingScrollOffset = scrollOffset;
                playClickSound();
                PacketDistributor.sendToServer(new WcmtActionPayload(
                        WcmtActionPayload.ACTION_PRIORITY, hit, 0, ""));
                return true;
            }
        }
        if (button == 0 && pressedBandButton >= 0) {
            int hit = pressedBandButton;
            pressedBandButton = -1;
            if (bandButtonAt(mouseX, mouseY) == hit) {
                int action = switch (hit) {
                    case 0 -> WcmtActionPayload.ACTION_PARTITION;
                    case 1 -> WcmtActionPayload.ACTION_CLEAR;
                    default -> WcmtActionPayload.ACTION_COPY_MODE;
                };
                if (hit == 2) {
                    // 本地翻转复制模式，使网格明暗立即跟随点击变化。
                    menu.predictCopyMode(menu.getCopyMode() == CopyMode.CLEAR_ON_REMOVE.ordinal()
                            ? CopyMode.KEEP_ON_REMOVE.ordinal()
                            : CopyMode.CLEAR_ON_REMOVE.ordinal());
                }
                playClickSound();
                PacketDistributor.sendToServer(new WcmtActionPayload(action, 0, 0, ""));
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 拷贝服务器计算的整网总量；背包旁的两条凹槽使用它们。 */
    private void updateNetTotals() {
        netTypes = ClientDriveData.typeUsed();
        netTypeCapacity = ClientDriveData.typeTotal();
        netUsedBytes = ClientDriveData.byteUsed();
        netTotalBytes = ClientDriveData.byteTotal();
        netInfiniteBytes = ClientDriveData.infinite();
        netInfiniteTypes = ClientDriveData.infiniteTypes();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawBandButtonTooltip(guiGraphics, mouseX, mouseY);
        addProgressTooltips(guiGraphics, mouseX, mouseY);
    }

    /** 工作台按钮的悬停文本，措辞与 AE2 的元件工作台相同。 */
    private void drawBandButtonTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int hit = bandButtonAt(mouseX, mouseY);
        if (hit < 0) {
            return;
        }
        List<Component> lines = switch (hit) {
            case 0 -> List.of(
                    Component.translatable("gui.tooltips.ae2.PartitionStorage"),
                    Component.translatable("gui.tooltips.ae2.PartitionStorageHint")
                            .withStyle(ChatFormatting.GRAY));
            case 1 -> List.of(
                    Component.translatable("gui.tooltips.ae2.Clear"),
                    Component.translatable("gui.cmt.band.clear_hint").withStyle(ChatFormatting.GRAY));
            default -> List.of(
                    Component.translatable("gui.ae2.CopyMode"),
                    Component.translatable("gui.ae2.CopyModeDesc").withStyle(ChatFormatting.GRAY),
                    Component.translatable("gui.cmt.band.copy_state",
                            Component.translatable(menu.getCopyMode() == CopyMode.CLEAR_ON_REMOVE.ordinal()
                                    ? "gui.cmt.band.copy_on"
                                    : "gui.cmt.band.copy_off")).withStyle(ChatFormatting.GRAY));
        };
        guiGraphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }

    /**
     * 填充玩家背包旁图集的两条凹槽：左侧为网络中已使用的存储类型数，右侧为字节使用量。
     * 两者都自下而上填充，颜色与驱动器表头相同。
     */
    private void drawProgressBars(GuiGraphics guiGraphics) {
        drawProgressBar(guiGraphics, TYPE_BAR_X, netTypes, netTypeCapacity, netInfiniteTypes);
        drawProgressBar(guiGraphics, STORAGE_BAR_X, netUsedBytes, netTotalBytes, netInfiniteBytes);
    }

    private void drawProgressBar(GuiGraphics guiGraphics, int x, long used, long total, boolean infinite) {
        int top = inventoryTop() + BAR_V - INV_V;
        if (infinite) {
            // 无限的量没有可显示的比率：整条凹槽都用无限状态的条带。
            drawBarStrip(guiGraphics, x, top, BAR_U_UNLIMITED, BAR_H);
            return;
        }
        if (total <= 0) {
            return;
        }
        // 至少留一小条，使空网络也能看出凹槽的位置。
        int filled = (int) Math.max(1, Math.min(BAR_H, used * BAR_H / total));
        drawBarStrip(guiGraphics, x, top + (BAR_H - filled), barStripU(used, total), filled);
    }

    /** 绘制图集中某条状态条带的 {@code height} 行，自下而上增长。 */
    private static void drawBarStrip(GuiGraphics guiGraphics, int x, int y, int u, int height) {
        Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                .src(u, BAR_STRIP_BOTTOM - height, BAR_W, height)
                .dest(x, y)
                .blit(guiGraphics);
    }

    /** 选取状态与网络充满程度匹配的条带。 */
    private static int barStripU(long used, long total) {
        if (total <= 0) {
            return BAR_U_LOW;
        }
        double ratio = (double) used / total;
        if (ratio < 0.5) {
            return BAR_U_LOW;
        }
        return ratio < 0.9 ? BAR_U_HALF : BAR_U_FULL;
    }

    /**
     * 当光标位于某条凹槽上时，说明它当前显示的内容。渲染时的鼠标坐标是绝对值，
     * 因此命中检测必须经由 {@link #isHovering}。
     */
    private void addProgressTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int top = inventoryTop() + BAR_V - INV_V;
        boolean overTypes = isHovering(TYPE_BAR_X, top, BAR_W, BAR_H, mouseX, mouseY);
        boolean overStorage = isHovering(STORAGE_BAR_X, top, BAR_W, BAR_H, mouseX, mouseY);
        // “类型数不受限”与“字节容量不受限”是两件事，各自只影响自己的凹槽。
        if (overTypes && netInfiniteTypes) {
            guiGraphics.renderTooltip(font,
                    Component.translatable("gui.cmt.groove_types_infinite"), mouseX, mouseY);
            return;
        }
        if (overStorage && netInfiniteBytes) {
            guiGraphics.renderTooltip(font,
                    Component.translatable("gui.cmt.groove_storage_infinite"), mouseX, mouseY);
            return;
        }
        if (overTypes) {
            guiGraphics.renderTooltip(font, tooltipLines(
                    Component.translatable("gui.cmt.groove_types", formatCount(netTypes),
                            formatCount(netTypeCapacity)),
                    usedLine(netTypes, netTypeCapacity)), mouseX, mouseY);
        } else if (overStorage) {
            guiGraphics.renderTooltip(font, tooltipLines(
                    Component.translatable("gui.cmt.groove_storage", formatBytes(netUsedBytes),
                            formatBytes(netTotalBytes)),
                    usedLine(netUsedBytes, netTotalBytes)), mouseX, mouseY);
        }
    }

    /**
     * 提示框的换行来自传入的列表；向组件追加 "\n" 会在同一行渲染。
     * 此处 GuiGraphics 需要 {@code FormattedCharSequence} 条目。
     */
    private static List<FormattedCharSequence> tooltipLines(Component first, Component second) {
        List<FormattedCharSequence> lines = new ArrayList<>(2);
        lines.add(first.getVisualOrderText());
        lines.add(second.getVisualOrderText());
        return lines;
    }

    private static Component usedLine(long used, long total) {
        int percent = total > 0 ? (int) Math.round(used * 100.0 / total) : 0;
        // 百分号放在参数里：翻译中的字面 "%%" 并非在所有路径上都会转义，会显示为占位符。
        return Component.translatable("gui.cmt.groove_used", percent + "%");
    }

    /**
     * AE2 对不可用终端的处理方式：调暗内容区域并在其中居中显示原因
     * （未连接 / 超出范围 / 电力不足）。
     */
    private void drawLinkStatus(GuiGraphics guiGraphics) {
        if (!ClientDriveData.blocked()) {
            return;
        }
        Component status = ClientDriveData.status();
        if (status.getString().isEmpty()) {
            return;
        }
        // 仅调暗内容行；收尾边/工作栏与背包保持清晰。
        int contentLeft = SLOT_X0;
        int contentRight = SLOT_X0 + SLOTS_PER_ROW * SLOT_STEP - 1;
        int shadeBottom = contentTop() + rows * ROW_H;
        guiGraphics.fill(contentLeft, contentTop(), contentRight, shadeBottom, 0x3F000000);
        guiGraphics.drawCenteredString(font, status, (contentLeft + contentRight) / 2,
                (contentTop() + shadeBottom) / 2 - 4, 0xFFFF5555);
    }

    /** 用于所有元件都是无限的驱动器，它没有可用的总量可报告。 */
    private static Component infiniteText() {
        return Component.translatable("gui.cmt.infinite")
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(INFINITE_COLOR & 0xFFFFFF)));
    }

    /** 与驱动器表头相同的配色，由字节预算的充满程度决定。 */
    private static int storageColor(long used, long total) {
        if (total <= 0) {
            return STORAGE_COLOR_EMPTY;
        }
        double ratio = (double) used / total;
        if (ratio < 0.5) {
            return STORAGE_COLOR_LOW;
        }
        return ratio < 0.9 ? STORAGE_COLOR_MEDIUM : STORAGE_COLOR_HIGH;
    }

    private static Component storageText(long used, long total) {
        return Component.literal(formatBytes(used) + "/" + formatBytes(total))
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(storageColor(used, total))));
    }

    /** 绘制在槽位井自身的凹槽内，因此绝不会覆盖元件图标。 */
    private void drawCapacityBar(GuiGraphics guiGraphics, int x, int y, DriveSnapshotPayload.SlotStat stat) {
        int width = SLOT_STEP - 2;
        if (stat.infinite()) {
            // 无限容量：用其专属颜色画满整条；无限「资源」使用其专属颜色。
            guiGraphics.fill(x, y, x + width, y + 1,
                    (stat.capacityInfinite() ? INFINITE_COLOR : STORAGE_COLOR_UNLIMITED_RESOURCE) | 0xFF000000);
            return;
        }
        int filled = stat.total() > 0
                ? (int) Math.round(width * (double) stat.used() / stat.total())
                : 0;
        filled = Math.max(0, Math.min(width, filled));
        if (filled > 0) {
            guiGraphics.fill(x, y, x + filled, y + 1, storageColor(stat.used(), stat.total()) | 0xFF000000);
        }
    }

    /**
     * 槽位右上角的类型/字节用量徽标。绘制图集中六个徽标中的哪一个，取决于元件的类型预算
     * 与字节预算的充满程度；无限容量或无限资源的元件有各自的两个。
     */
    private void drawCellBadge(GuiGraphics guiGraphics, int slotX, int slotY, DriveSnapshotPayload.SlotStat stat) {
        int u;
        int v;
        if (stat.capacityInfinite()) {
            u = BADGE_U_LEFT;
            v = BADGE_V_BOTTOM;
        } else if (stat.infinite()) {
            u = BADGE_U_RIGHT;
            v = BADGE_V_BOTTOM;
        } else {
            boolean typesFull = stat.typeCapacity() > 0 && stat.typeUsed() >= stat.typeCapacity();
            boolean bytesFull = stat.total() > 0 && stat.used() >= stat.total();
            if (typesFull && bytesFull) {
                u = BADGE_U_RIGHT;
                v = BADGE_V_MID;
            } else if (typesFull) {
                u = BADGE_U_LEFT;
                v = BADGE_V_MID;
            } else if (stat.typeUsed() > 0 || stat.used() > 0) {
                u = BADGE_U_RIGHT;
                v = BADGE_V_TOP;
            } else {
                u = BADGE_U_LEFT;
                v = BADGE_V_TOP;
            }
        }
        Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                .src(u, v, BADGE_SIZE, BADGE_SIZE)
                .dest(slotX + 16 - BADGE_SIZE, slotY)
                .zOffset(3)
                .blit(guiGraphics);
    }

    /** 字节容量按 AE2 的写法格式化：1k = 1024，逐级 1024 进位。 */
    private static String formatBytes(long value) {
        return readable(Tooltips.getByteAmount(value));
    }

    /** 数量（例如物品种类数）按 AE2 的写法格式化：1k = 1000。 */
    private static String formatCount(long value) {
        return readable(Tooltips.getAmount(value));
    }

    private static String readable(Tooltips.Amount amount) {
        return amount.unit().isEmpty() ? amount.digit() : amount.digit() + amount.unit();
    }

    private void blit(GuiGraphics guiGraphics, int x, int y, int u, int v, int width, int height) {
        guiGraphics.blit(CELL_TEXTURE, x, y, (float) u, (float) v, width, height, 256, 256);
    }

    @Override
    public void removed() {
        super.removed();
        ClientDriveData.clear();
    }
}
