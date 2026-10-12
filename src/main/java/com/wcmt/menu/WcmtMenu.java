package com.wcmt.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.Iterators;

import com.wcmt.init.ModItems;
import com.wcmt.init.ModMenus;
import com.wcmt.network.DriveSnapshotPayload;
import com.wcmt.network.WcmtActionPayload;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.CopyMode;
import appeng.api.config.Setting;
import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.ILinkStatus;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.ICellWorkbenchItem;
import appeng.api.upgrades.IUpgradeableItem;
import appeng.api.upgrades.IUpgradeableObject;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.util.IConfigManager;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.localization.GuiText;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.menu.SlotSemantics;
import appeng.api.stacks.KeyCounter;
import appeng.menu.slot.CellPartitionSlot;
import appeng.menu.slot.IPartitionSlotHost;
import appeng.menu.slot.RestrictedInputSlot;
import appeng.util.ConfigInventory;
import appeng.util.ConfigMenuInventory;
import appeng.util.inv.AppEngInternalInventory;
import de.mari_023.ae2wtlib.api.gui.AE2wtlibSlotSemantics;
import de.mari_023.ae2wtlib.api.terminal.ItemWUT;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;
import com.wcmt.part.WcmtTerminalPart;
import com.wcmt.util.PriorityReturn;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 无线元件管理终端的菜单。
 *
 * <p>客户端发送一个行预算（由窗口高度与 AE2 终端样式设置推导而来）。服务端用连续的驱动器
 * 填满这么多行，并把固定的元件槽位池连续映射到它们上面，因此元件数不同的驱动器（例如
 * ExtendedAE 的 20 槽位驱动器）也能正确处理。
 */
public class WcmtMenu extends AEBaseMenu implements IPartitionSlotHost {

    /** 每行排布的元件数（原版驱动器的 10 个元件恰好填满一行）。 */
    public static final int SLOTS_PER_ROW = 10;
    /** 内容行数的上限；实际预算由客户端控制。 */
    public static final int MAX_ROWS = 24;
    /** 同时管理的元件槽位总数上限；也是槽位池的大小。 */
    public static final int MAX_SLOTS = 128;
    /** 全网总计有自己更慢的频率：读取它们需要遍历元件内容。 */
    private static final int TOTALS_SCAN_TICKS = 20;
    /** 终端打开期间重新扫描网络中驱动器的间隔（单位：tick）。 */
    private static final int SCAN_INTERVAL_TICKS = 10;
    /** 面板右上角的升级卡槽位；四张能量卡可让能量缓存变为四倍。 */
    public static final int UPGRADE_SLOTS = 4;
    /** 工作卡暴露的分区槽位：与 AE2 元件工作台相同的 9x7 网格。 */
    public static final int CONFIG_SLOTS = 63;
    /** 区域中一次显示的分区行数；其余通过滚动到达。 */
    public static final int CONFIG_ROWS_VISIBLE = 3;
    /** 整个分区网格的行数（63 个槽位、9 列）。 */
    public static final int CONFIG_ROWS_TOTAL = 7;
    /** 网格可滚动到的最大首行索引。 */
    public static final int CONFIG_SCROLL_MAX = CONFIG_ROWS_TOTAL - CONFIG_ROWS_VISIBLE;

    /** 工作卡在其区域旁显示的升级卡槽位，对应元件工作台自身的升级卡槽。 */
    public static final SlotSemantic WORKBENCH_UPGRADE = SlotSemantics.register("CMT_WORKBENCH_UPGRADE",
            false);
    /** 工作区域预建的升级卡槽位数量；AE2 的 {@code ICellWorkbenchItem} 允许最多 8 格，按元件实际数量启用。 */
    public static final int WORKBENCH_UPGRADE_SLOTS = 8;

    public enum SortMode {
        POSITION,
        NAME,
        USAGE
    }

    /**
     * 由终端自身携带的自定义设置（无线终端为物品 NBT，线缆终端为部件 NBT），
     * 使所选排序在关闭并重新打开界面后依然保留。
     */
    public static final Setting<SortMode> SORT_MODE = new Setting<>("cmt_sort_mode", SortMode.class);

    /** 无线宿主；此菜单属于线缆部件时为 null。 */
    @Nullable
    private final WcmtMenuHost host;
    /**
     * 升级卡槽位所依托的升级卡 inventory。缓存在这里是因为宿主每次调用都会重新查询它，
     * 那样会返回与槽位写入不同的实例。
     */
    @Nullable
    private final appeng.api.upgrades.IUpgradeInventory upgradeInv;
    /** 承载此菜单的线缆部件；无线终端时为 null。 */
    @Nullable
    private final WcmtTerminalPart part;
    private final PagedDriveInventory driveInventory;
    private final boolean serverSide;

    private int tickCounter;
    private boolean needsSnapshot = true;
    private boolean needsTotals = true;
    /** 两个进度条的全网总计：typeUsed、typeTotal、byteUsed、byteTotal。 */
    private final long[] netTotals = new long[4];
    /** 网络中任一元件为无限时为 true，进度条会将其显示为满。 */
    private boolean netInfinite;
    /** 窗口中显示的首个内容行（一个驱动器占一个名称行加其元件行）。 */
    private int offset;
    /** 所有驱动器的内容行数；元件更多的驱动器占用更多行。 */
    private int totalRows;
    /** 首个可见驱动器被滚动到窗口上方之外的行数。 */
    private int skippedRows;
    private int rowsBudget = 4;
    /** 排序的存放位置：无线终端的物品堆，或线缆部件。 */
    private final IConfigManager sortConfig;
    private String search = "";
    private List<ManagedDrive> allDrives = List.of();
    private List<ManagedDrive> visibleDrives = List.of();
    /** 存在驱动器但都放不进槽位池时置位；会报告给客户端。 */
    private boolean windowOverflow;
    /** 客户端上对应驱动器的槽位数量，通过快照数据包保持同步。 */
    private int clientActiveSlots;
    /** 服务端当前窗口映射的元件槽位总数；随窗口一起重算，避免每次调用都重新归约。 */
    private int activeSlots;
    /** 区域显示的分区网格滚动步进；随网格一起存在终端上。 */
    private int configScroll;

    /**
     * 工作卡的分区网格。与 AE2 元件工作台保存的网格一样，它独立于任何元件存在：随时可编辑，
     * 存放在终端的物品堆上，并在编辑时写入所选中的那个元件。
     */
    private final ConfigInventory workbenchConfig = ConfigInventory.configTypes(CONFIG_SLOTS).build();
    private final ConfigMenuInventory workbenchConfigWrapper = workbenchConfig.createMenuWrapper();
    /** 网格作为菜单使用的 inventory，并应用了编辑规则。 */
    private final WorkbenchGridInventory partitionGrid = new WorkbenchGridInventory(this);
    /**
     * 网格自上次写回终端后是否被改过。分区格随时可编辑，但写回要重编码整份配置，
     * 因此只在真的改动过时做，而不是每 tick 比对一份 NBT。
     */
    private boolean workbenchDirty;
    /** 工作台复制按钮显示的复制模式；随网格一起持久化。 */
    private CopyMode copyMode = CopyMode.CLEAR_ON_REMOVE;
    /**
     * 所选元件自身的过滤格数；元件不能配置过滤（如只提供无限资源的元件）时为 0。
     * 缓存它是因为界面每帧都会对 63 个分区格各问一次。
     */
    private int selectedCellConfigSize;
    /** 所选元件接受的升级卡数量；元件不支持升级卡时为 0，界面据此决定是否绘制升级条带。 */
    private int selectedCellUpgradeCount;
    /**
     * 区域旁显示的升级卡槽位。它们跟随所选的元件：每种元件声明它接受多少张升级卡
     * （见 {@link ICellWorkbenchItem#getUpgrades}），因此面板只显示该元件可用的卡。
     */
    private final IUpgradeableObject workbenchUpgrades = new IUpgradeableObject() {
        @Override
        public IUpgradeInventory getUpgrades() {
            ItemStack cell = selectedCell();
            if (!cell.isEmpty() && cell.getItem() instanceof appeng.api.upgrades.IUpgradeableItem item) {
                return item.getUpgrades(cell);
            }
            return UpgradeInventories.empty();
        }
    };
    /**
     * 区域的升级卡槽位。它们跟随所选元件并直接写回驱动器，因此插入这里的卡会真正落到元件上
     * （见 {@link SelectedCellUpgradesInventory}）。
     */
    private final SelectedCellUpgradesInventory workbenchUpgradeInv =
            new SelectedCellUpgradesInventory(this);
    /** 玩家最后右键点击的元件的驱动器槽位索引，或 -1。会绘制高亮框。 */
    private int selectedSlot = -1;
    /** 按网格顺序排列的分区槽位；客户端一次显示三行。 */
    private final List<WorkbenchPartitionSlot> configSlots = new ArrayList<>();

    /** 工作台网格存放在终端上的键。 */
    private static final String WORKBENCH_CONFIG_TAG = "CMTWorkbenchConfig";
    /** 复制模式存放在终端上的键。 */
    private static final String COPY_MODE_TAG = "CMTCopyMode";
    /** 分区网格滚动步存放在终端上的键。 */
    private static final String CONFIG_SCROLL_TAG = "CMTConfigScroll";

    /** 标记网格已被修改，使下一次广播把它写回终端。 */
    void markWorkbenchDirty() {
        workbenchDirty = true;
    }

    /**
     * 重算所选元件的能力缓存：过滤格数与可用升级卡数量。选中元件、收到快照或重扫驱动器后调用。
     * 界面的遮罩与升级条带都读这两份缓存，避免每帧对 63 个格子各解析一次元件数据。
     */
    private void refreshSelectedCellCapabilities() {
        ItemStack cell = selectedCell();
        if (!cell.isEmpty() && cell.getItem() instanceof ICellWorkbenchItem cwi) {
            var config = cwi.getConfigInventory(cell);
            selectedCellConfigSize = config == null ? 0 : config.size();
            selectedCellUpgradeCount = workbenchUpgradeInv.size();
            return;
        }
        selectedCellConfigSize = 0;
        selectedCellUpgradeCount = 0;
    }

    /** 构建菜单时加载已保存的网格。 */
    private void loadWorkbench() {
        Level level = getPlayer().level();
        if (level == null || host == null) {
            return;
        }
        var tag = host.getItemStack().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        workbenchConfig.readFromChildTag(tag, WORKBENCH_CONFIG_TAG, level.registryAccess());
        copyMode = CopyMode.values()[Math.floorMod(tag.getInt(COPY_MODE_TAG),
                CopyMode.values().length)];
        configScroll = Math.max(0, Math.min(CONFIG_SCROLL_MAX, tag.getInt(CONFIG_SCROLL_TAG)));
    }

    /**
     * 网格变化时将其写回终端。网格编辑也会立即写入所选元件，因此即使未再次右键就关闭界面，
     * 元件也已带有该配置。
     */
    private void saveWorkbench() {
        Level level = getPlayer().level();
        if (level == null || host == null) {
            return;
        }
        var stack = host.getItemStack();
        var original = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        var tag = original.copy();
        var previousGrid = tag.get(WORKBENCH_CONFIG_TAG);
        tag.remove(WORKBENCH_CONFIG_TAG);
        workbenchConfig.writeToChildTag(tag, WORKBENCH_CONFIG_TAG, level.registryAccess());
        tag.putInt(COPY_MODE_TAG, copyMode.ordinal());
        tag.putInt(CONFIG_SCROLL_TAG, configScroll);
        boolean gridChanged = !Objects.equals(previousGrid, tag.get(WORKBENCH_CONFIG_TAG));
        if (tag.equals(original)) {
            return;
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (gridChanged && selectedSlot >= 0) {
            applyWorkbenchToCell(selectedSlot);
        }
        // 终端位于玩家背包中，因此客户端上的这份物品堆只有
        // 在背包广播后才会感知到变化。
        if (getPlayer() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.broadcastChanges();
        }
    }

    /** 所选元件表示为 {@code dimension|pos|localSlot}；未选中任何元件时为 null。 */
    @Nullable
    private String rememberedSelection() {
        if (selectedSlot < 0) {
            return null;
        }
        int acc = 0;
        for (ManagedDrive drive : visibleDrives) {
            if (selectedSlot < acc + drive.cellCount()) {
                return drive.dimension() + "|" + drive.pos().asLong() + "|" + (selectedSlot - acc);
            }
            acc += drive.cellCount();
        }
        return null;
    }

    public WcmtMenu(int id, Inventory playerInventory, WcmtMenuHost host) {
        this(id, playerInventory, ModMenus.WCMT_MENU_TYPE, host);
    }

    public WcmtMenu(int id, Inventory playerInventory, WcmtTerminalPart part) {
        this(id, playerInventory, ModMenus.WCMT_PART_MENU_TYPE, part);
    }

    private WcmtMenu(int id, Inventory playerInventory, MenuType<?> menuType, Object host) {
        super(menuType, id, playerInventory, host);
        this.host = host instanceof WcmtMenuHost wireless ? wireless : null;
        this.part = host instanceof WcmtTerminalPart cablePart ? cablePart : null;
        this.serverSide = isServerSide();
        // 排序保存在 AE2 的配置管理器中，它会持久化到承载此终端的对象上：
        // 无线物品堆或线缆部件。
        this.sortConfig = this.host != null
                ? IConfigManager.builder(this.host::getItemStack)
                        .registerSetting(SORT_MODE, SortMode.POSITION)
                        .registerSetting(Settings.SORT_DIRECTION, SortDir.ASCENDING)
                        .build()
                : this.part.getConfigManager();
        this.driveInventory = new PagedDriveInventory(serverSide, this);

        for (int i = 0; i < MAX_SLOTS; i++) {
            addSlot(new DriveCellSlot(driveInventory, i, this), SlotSemantics.STORAGE_CELL);
        }
        createPlayerInventorySlots(playerInventory);

        // 升级卡是物品终端的特性：AE2 的终端部件没有升级卡 inventory，
        // 因此线缆形态只获得驱动器槽位。此处字段顺序很重要——对部件
        // 不得执行宿主检查和 getUpgrades()。
        if (this.host != null) {
            // 量子桥接奇点槽位必须排在首位：AE2WTlib 的升级面板会把
            // 其列表的第一个槽位当作奇点槽位，并在它为空时隐藏它。
            addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.QE_SINGULARITY,
                    this.host.getSubInventory(WTMenuHost.INV_SINGULARITY), 0),
                    AE2wtlibSlotSemantics.SINGULARITY);

            // 工作卡的分区网格。槽位总是被创建（AE2 也是如此）；它们是否显示
            // 由下面的 isPartitionSlotEnabled 决定。
            for (int i = 0; i < CONFIG_SLOTS; i++) {
                var slot = new WorkbenchPartitionSlot(partitionGrid, this, i);
                configSlots.add(slot);
                addSlot(slot, SlotSemantics.CONFIG);
            }

            // 区域旁显示的第二组独立升级卡槽位。由所选元件自身的升级卡
            // inventory 支撑，因此只列出该元件类型接受的卡。
            for (int i = 0; i < WORKBENCH_UPGRADE_SLOTS; i++) {
                addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.UPGRADES,
                        workbenchUpgradeInv, i), WORKBENCH_UPGRADE);
            }

            var upgrades = this.host.getUpgrades();
            this.upgradeInv = upgrades;
            int slotCount = isWUT() ? upgrades.size() : Math.min(UPGRADE_SLOTS, upgrades.size());
            for (int i = 0; i < slotCount; i++) {
                addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.UPGRADES, upgrades, i) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return super.mayPlace(stack) || stack.is(ModItems.COMPONENT_WORK_CARD.get());
                    }
                }, SlotSemantics.UPGRADE);
            }
        } else {
            this.upgradeInv = null;
        }

        // 两侧都加载；网格本身来自所选元件，两侧都能看到它。
        loadWorkbench();
        if (serverSide) {
            refreshDrives();
        }
        refreshSelectedCellCapabilities();
    }

    /** 终端从 AE2WTlib 的通用终端打开时为 true。 */
    public boolean isWUT() {
        return host != null && host.getItemStack().getItem() instanceof ItemWUT;
    }

    /** 具有指定元件数的驱动器所需行数（1 个名称行 + ceil(cells / 10) 行）。 */
    public static int rowsForCells(int cells) {
        return 1 + (cells + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW;
    }

    // ------------------------------------------------------------------
    // 访问控制
    // ------------------------------------------------------------------

    public boolean canInsert() {
        if (!serverSide) {
            return true;
        }
        return linkUsable() && hasPower();
    }

    /** 终端此刻是否真的能改动它管理的驱动器（已链接、在范围内且有电）。 */
    private boolean canConfigure() {
        return linkUsable() && hasPower();
    }

    public boolean canExtract() {
        if (!serverSide) {
            return true;
        }
        return linkUsable() && hasPower();
    }

    /** 终端未连接、超出范围或电量耗尽时为 false。 */
    private boolean linkUsable() {
        ILinkStatus status = linkStatus();
        return status == null || status.connected();
    }

    /** 此菜单所属宿主的连接状态；两者都没有时为 null。 */
    @Nullable
    private ILinkStatus linkStatus() {
        if (host != null) {
            return host.getLinkStatus();
        }
        return part == null ? null : part.getLinkStatus();
    }

    /** 此菜单操作的网络；无法访问任何网络时为 null。 */
    @Nullable
    private IGrid wiredGrid() {
        if (host != null) {
            return host.getConnectedGrid();
        }
        return part == null ? null : part.getMainNode().getGrid();
    }

    /** AE2 终端自身的能量缓存耗尽后即停止工作；创造模式玩家不受影响。 */
    private boolean hasPower() {
        if (getPlayer().isCreative() || host == null) {
            // 线缆部件从网络取电，没有物品缓存需要检查。
            return true;
        }
        ItemStack terminal = host.getItemStack();
        return terminal.getItem() instanceof WirelessTerminalItem wireless
                && wireless.getAECurrentPower(terminal) > 0;
    }

    /** 终端完全无法使用（未连接、无电或没有内容放得下）时为 true。 */
    private boolean blocked() {
        return !linkUsable() || !hasPower() || windowOverflow;
    }

    // ------------------------------------------------------------------
    // 槽位可用性
    // ------------------------------------------------------------------

    /** 当前映射到真实驱动器的驱动器元件槽位总数。 */
    public int activeSlotCount() {
        return serverSide ? activeSlots : clientActiveSlots;
    }

    public boolean isSlotActive(int slot) {
        return slot >= 0 && slot < activeSlotCount();
    }

    /** 客户端收到快照时调用，使幽灵槽位停止接受物品。 */
    public void onClientSnapshot(DriveSnapshotPayload snapshot) {
        this.clientActiveSlots = snapshot.drives().stream().mapToInt(d -> d.slots().size()).sum();
        this.selectedSlot = snapshot.selectedSlot();
        this.copyMode = CopyMode.values()[Math.floorMod(snapshot.copyMode(), CopyMode.values().length)];
        refreshSelectedCellCapabilities();
    }

    // ------------------------------------------------------------------
    // 服务端滚动 / 排序 / 搜索
    // ------------------------------------------------------------------

    public void handleAction(int action, int a, int b, String text) {
        if (!serverSide) {
            return;
        }
        switch (action) {
            case WcmtActionPayload.ACTION_LAYOUT -> {
                int newRows = Math.max(1, Math.min(MAX_ROWS, b));
                // 按上次扫描的结果进行钳制，使客户端过期的滚动范围
                // 永远不会把窗口推到内容末尾之外。
                int maxOffset = Math.max(0, totalRows - newRows);
                int newOffset = Math.max(0, Math.min(a, maxOffset));
                if (newOffset == offset && newRows == rowsBudget) {
                    // 没有任何变化：不要触发另一次完整扫描和快照。
                    return;
                }
                offset = newOffset;
                rowsBudget = newRows;
            }
            case WcmtActionPayload.ACTION_SORT ->
                sortConfig.putSetting(SORT_MODE,
                        SortMode.values()[Math.floorMod(a, SortMode.values().length)]);
            case WcmtActionPayload.ACTION_SORT_DIRECTION ->
                sortConfig.putSetting(Settings.SORT_DIRECTION,
                        a != 0 ? SortDir.DESCENDING : SortDir.ASCENDING);
            case WcmtActionPayload.ACTION_SEARCH -> search = text == null ? "" : text;
            case WcmtActionPayload.ACTION_CONFIG_SCROLL -> {
                configScroll = Math.max(0, Math.min(CONFIG_SCROLL_MAX, a));
                markWorkbenchDirty();
            }
            case WcmtActionPayload.ACTION_PARTITION -> partitionWorkbench();
            case WcmtActionPayload.ACTION_CLEAR -> clearWorkbench();
            case WcmtActionPayload.ACTION_COPY_MODE -> {
                copyMode = copyMode == CopyMode.CLEAR_ON_REMOVE
                        ? CopyMode.KEEP_ON_REMOVE
                        : CopyMode.CLEAR_ON_REMOVE;
                markWorkbenchDirty();
            }
            case WcmtActionPayload.ACTION_APPLY_CELL -> handleCellRightClick(a);
            case WcmtActionPayload.ACTION_PRIORITY -> {
                // 打开驱动器自身的界面（它反正会替换此终端的菜单）；槽位索引
                // 是客户端在快照中看到的那个，即位于可见窗口内。
                if (a >= 0 && a < visibleDrives.size() && getPlayer() instanceof ServerPlayer player) {
                    // AE2 的界面会把玩家送回驱动器方块而非此处，因此在打开它之前
                    // 先记住返回路径。
                    PriorityReturn.remember(player,
                            part == null ? ModMenus.WCMT_MENU_TYPE : ModMenus.WCMT_PART_MENU_TYPE,
                            getLocator());
                    visibleDrives.get(a).openPriority(player);
                }
            }
            default -> {
                return;
            }
        }
        needsSnapshot = true;
    }

    @Override
    public void broadcastChanges() {
        // 先调用 super：它会 tick 物品菜单宿主，刷新访问点、范围与电量。
        super.broadcastChanges();
        if (!serverSide || !isValidMenu()) {
            return;
        }
        tickCounter++;
        if (workbenchDirty) {
            workbenchDirty = false;
            saveWorkbench();
        }
        int interval = SCAN_INTERVAL_TICKS;
        if (needsSnapshot || tickCounter % interval == 0) {
            needsSnapshot = false;
            refreshDrives();
            sendSnapshot();
        }
    }

    private void refreshDrives() {
        IGrid grid = wiredGrid();
        List<ManagedDrive> found = new ArrayList<>();
        if (grid != null) {
            // 注意：IGrid.getMachines(X) 按宿主节点的「确切」类来索引，因此子类以及
            // 仅实现元件宿主接口的附属宿主会被漏掉。先收集宿主：NeoECO 会把一个
            // 多块结构的方块归入单个驱动器，为此需要完整列表。
            List<BlockEntity> owners = new ArrayList<>();
            Set<BlockEntity> seenOwners = Collections.newSetFromMap(new IdentityHashMap<>());
            for (IGridNode node : grid.getNodes()) {
                if (node.getOwner() instanceof BlockEntity blockEntity
                        && !blockEntity.isRemoved()
                        && blockEntity.getLevel() != null
                        && seenOwners.add(blockEntity)) {
                    owners.add(blockEntity);
                }
            }
            for (BlockEntity owner : owners) {
                if (owner instanceof DriveBlockEntity drive && drive.getCellCount() > 0) {
                    found.add(new Ae2Drive(drive));
                }
            }
            // 可选附属模组：NeoECO 的驱动器在网络上，但不继承 AE2 的驱动器。
            found.addAll(NeoEcoDrive.collect(owners));
        }
        // 分类总计描述整个网络而非仅可见窗口，且在搜索过滤之前采集，
        // 因此不会随当前查询变化。
        if (needsTotals || tickCounter % TOTALS_SCAN_TICKS == 0) {
            needsTotals = false;
            summarizeNetwork(found);
        }
        found.sort(comparatorFor(sortConfig.getSetting(SORT_MODE), found));
        if (sortConfig.getSetting(Settings.SORT_DIRECTION) == SortDir.DESCENDING) {
            Collections.reverse(found);
        }
        if (!search.isBlank()) {
            String query = search.toLowerCase(Locale.ROOT);
            found.removeIf(drive -> !matchesSearch(drive, query));
        }

        allDrives = found;

        int budget = Math.max(1, Math.min(MAX_ROWS, rowsBudget));
        int[] driveFirstRow = new int[found.size()];
        int rows = 0;
        for (int i = 0; i < found.size(); i++) {
            driveFirstRow[i] = rows;
            rows += rowsForCells(found.get(i).cellCount());
        }
        totalRows = rows;

        // 按行滚动而非按驱动器：这样短于一个驱动器的窗口仍会显示该驱动器的
        // 下方行，而不是直接跳到下一个驱动器。
        int start = Math.max(0, Math.min(offset, Math.max(0, totalRows - budget)));
        int end = start + budget;

        List<ManagedDrive> window = new ArrayList<>();
        int usedCells = 0;
        int firstVisible = -1;
        for (int i = 0; i < found.size(); i++) {
            int first = driveFirstRow[i];
            int last = i + 1 < found.size() ? driveFirstRow[i + 1] : totalRows;
            if (first >= end) {
                break;
            }
            if (last <= start) {
                continue;
            }
            ManagedDrive drive = found.get(i);
            if (usedCells + drive.cellCount() > MAX_SLOTS) {
                // 继续查找：后面更小的宿主仍可能放得下。只有当第一个宿主也过大时
                // 窗口才保持为空，随后会报告给客户端。
                continue;
            }
            if (firstVisible < 0) {
                firstVisible = i;
            }
            window.add(drive);
            usedCells += drive.cellCount();
        }

        offset = start;
        skippedRows = firstVisible < 0 ? 0 : start - driveFirstRow[firstVisible];
        // 存在驱动器但没有一个放得进槽位池：告知玩家，而不是显示
        // 空列表。
        windowOverflow = window.isEmpty() && !found.isEmpty();
        visibleDrives = window;
        activeSlots = usedCells;
        driveInventory.setDrives(window);
        // 元件可能在两次扫描之间被换掉，能力缓存要跟着驱动器一起刷新。
        refreshSelectedCellCapabilities();
    }


    /**
     * 两个进度条的全网总计。物品种类数来自网络自身的存储——网络中所有可达的元件，
     * 包括附属元件，这也是 NeoECO 元件能出现在这里的原因，尽管它们自己的 inventory API
     * 不会回应我们。种类容量与字节数来自终端所管理的元件（只有普通 AE2 元件报告种类容量）；
     * 字节计数器为哨兵值的元件会被跳过，因为它无法参与比值计算且会使求和的数值溢出。
     */
    private void summarizeNetwork(List<ManagedDrive> drives) {
        long typeUsed = 0;
        IGrid grid = wiredGrid();
        if (grid != null) {
            KeyCounter counter = new KeyCounter();
            grid.getStorageService().getInventory().getAvailableStacks(counter);
            typeUsed = counter.size();
        }
        long typeTotal = 0;
        long byteUsed = 0;
        long byteTotal = 0;
        boolean infinite = false;
        for (ManagedDrive drive : drives) {
            for (int slot = 0; slot < drive.cellCount(); slot++) {
                DriveSnapshotPayload.SlotStat stat = drive.stat(slot);
                if (stat.capacityInfinite()) {
                    // 就这两个进度条而言，只有真正没有容量限制的元件才使网络变为无限；
                    // 仅提供无限资源的元件（无限水/圆石元件）仍使用其普通计数器。
                    infinite = true;
                }
                // 即使元件的字节计数器是哨兵值，其种类容量仍然计入；但负数表示「不限类型数」
                // （NeoECO 的 omni 元件就是这么约定的），计入求和只会把总量减掉。
                if (stat.typeCapacity() > 0) {
                    typeTotal += stat.typeCapacity();
                }
                if (stat.total() <= 0 || ManagedDrive.isInfinite(stat.total())) {
                    continue;
                }
                byteUsed += stat.used();
                byteTotal += stat.total();
            }
        }
        netTotals[0] = typeUsed;
        netTotals[1] = typeTotal;
        netTotals[2] = byteUsed;
        netTotals[3] = byteTotal;
        netInfinite = infinite;
    }

    private Comparator<ManagedDrive> comparatorFor(SortMode mode, List<ManagedDrive> drives) {
        Comparator<ManagedDrive> byPosition = Comparator
                .comparing((ManagedDrive d) -> d.dimension().toString())
                .thenComparing(d -> d.pos().asLong());
        return switch (mode) {
            case POSITION -> byPosition;
            case NAME -> Comparator.comparing((ManagedDrive d) -> d.name().getString())
                    .thenComparing(byPosition);
            case USAGE -> {
                // 填充率会遍历驱动器的全部元件，逐个驱动器只算一次，而不是每次比较都重算。
                Map<ManagedDrive, Double> ratios = new IdentityHashMap<>(drives.size());
                for (ManagedDrive drive : drives) {
                    ratios.put(drive, drive.fillRatio());
                }
                yield Comparator.comparingDouble((ManagedDrive d) -> ratios.getOrDefault(d, 0.0))
                        .reversed().thenComparing(byPosition);
            }
        };
    }

    private static boolean matchesSearch(ManagedDrive drive, String query) {
        if (drive.name().getString().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (drive.pos().toShortString().contains(query)) {
            return true;
        }
        for (int i = 0; i < drive.cellCount(); i++) {
            ItemStack stack = drive.cell(i);
            if (stack != null && !stack.isEmpty()
                    && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private void sendSnapshot() {
        if (!(getPlayer() instanceof ServerPlayer serverPlayer)) {
            return;
        }
        List<DriveSnapshotPayload.DriveInfo> infos = new ArrayList<>(visibleDrives.size());
        for (ManagedDrive drive : visibleDrives) {
            infos.add(buildInfo(drive));
        }
        int windowRows = Math.max(1, Math.min(MAX_ROWS, rowsBudget));
        PacketDistributor.sendToPlayer(serverPlayer,
                new DriveSnapshotPayload(offset, totalRows, skippedRows, windowRows, blocked(),
                        sortConfig.getSetting(SORT_MODE).name(), search, linkStatusMessage(), infos,
                        netTotals[0], netTotals[1], netTotals[2], netTotals[3], netInfinite,
                        sortConfig.getSetting(Settings.SORT_DIRECTION) == SortDir.DESCENDING,
                        hasWorkCard(), configScroll, copyMode.ordinal(), selectedSlot));
    }

    /**
     * 始终启用，使 AE2 的 {@code CellPartitionSlot} 绝不会在我们背后清空网格（只要槽位报告
     * 自身被禁用，它就会清空其底层 inventory，而此网格应当在切换元件和切换复制模式时都
     * 保持存活）。
     */
    @Override
    public boolean isPartitionSlotEnabled(int idx) {
        return isPartitionEditable(idx);
    }

    /**
     * 网格当前是否可编辑。它同时驱动槽位的激活状态，使置灰的网格
     * 不会被原版槽位背景盖住。
     */
    public boolean isPartitionEditable(int idx) {
        if (idx < 0 || idx >= CONFIG_SLOTS) {
            return false;
        }
        // 选中可配置元件时按元件自身的过滤格数收缩（AE2 的 CellWorkbenchMenu 用的是同一判据）；
        // 元件根本不能配置（过滤格为 0）时遮罩保持，只在 KEEP 模式下继续允许标记。
        if (selectedCellConfigSize > 0) {
            return idx < selectedCellConfigSize;
        }
        return copyMode == CopyMode.KEEP_ON_REMOVE;
    }

    /** 选中了已存储的元件且可配置时为 true。 */
    public boolean hasSelectedCell() {
        return selectedSlot >= 0 && !selectedCell().isEmpty();
    }

    /**
     * 让客户端立即采用所选状态，而不必等待下一次快照，使
     * 网格置灰与升级卡栏能即时响应点击。
     */
    public void predictSelection(int slot) {
        if (!serverSide) {
            selectedSlot = slot;
            refreshSelectedCellCapabilities();
        }
    }

    /** 复制模式的序号，供界面的复制按钮及其置灰使用。 */
    public int getCopyMode() {
        return copyMode.ordinal();
    }

    /** 让客户端立即切换复制模式，而不必等待下一次快照。 */
    public void predictCopyMode(int ordinal) {
        if (!serverSide) {
            copyMode = CopyMode.values()[Math.floorMod(ordinal, CopyMode.values().length)];
        }
    }

    /**
     * 升级卡槽位中存在组件工作卡时为 true。从本地升级卡 inventory 读取，它在两侧都存在
     * （并由容器保持同步），因此玩家一插入卡客户端就能看到，而无需等待下一次快照。
     */
    public boolean hasWorkCard() {
        if (upgradeInv == null) {
            return false;
        }
        for (int i = 0; i < upgradeInv.size(); i++) {
            if (upgradeInv.getStackInSlot(i).is(ModItems.COMPONENT_WORK_CARD.get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将所选元件的内容采集到其分区网格，类似 AE2 工作台的分区按钮。
     * 未选中元件时不做任何事。
     */
    private void partitionWorkbench() {
        ItemStack cell = selectedCell();
        if (cell.isEmpty()) {
            return;
        }
        var it = iterateCellStacks(cell);
        for (int i = 0; i < CONFIG_SLOTS; i++) {
            workbenchConfig.setStack(i, it.hasNext() ? new GenericStack(it.next(), 0) : null);
        }
        markWorkbenchDirty();
    }

    /** 清空分区网格，类似 AE2 工作台的清除按钮。 */
    private void clearWorkbench() {
        workbenchConfig.clear();
        markWorkbenchDirty();
    }

    /** 玩家右键点击的元件，或空。 */
    private ItemStack selectedCell() {
        if (selectedSlot < 0 || selectedSlot >= driveInventory.size()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = driveInventory.getStackInSlot(selectedSlot);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** 玩家右键点击的元件，或空。对外暴露以便界面描述其升级卡。 */
    public ItemStack getSelectedCell() {
        return selectedCell();
    }

    /** 分区网格作为物品 inventory。 */
    ConfigMenuInventory workbenchGrid() {
        return workbenchConfigWrapper;
    }

    /**
     * 所选元件自身的升级卡 inventory；未选中可配置元件时为 null。
     * 该 inventory 绑定到传给它的物品堆上，而那只是所存储元件的副本。
     */
    @Nullable
    IUpgradeInventory selectedCellUpgrades() {
        ItemStack cell = selectedCell();
        if (cell.isEmpty() || !(cell.getItem() instanceof IUpgradeableItem item)) {
            return null;
        }
        return item.getUpgrades(cell);
    }

    /** 将所选元件的工作副本推回其所在的驱动器。 */
    void writeBackSelectedCell() {
        if (selectedSlot < 0 || selectedSlot >= driveInventory.size()) {
            return;
        }
        ItemStack cell = driveInventory.getStackInSlot(selectedSlot);
        if (cell != null && !cell.isEmpty()) {
            driveInventory.setItemDirect(selectedSlot, cell);
        }
    }

    /**
     * 右键点击已存储的元件，仅在安装了工作卡时有效。第一次点击选中该元件并显示其高亮；
     * 再次点击则取消选中。
     *
     * <p>网格的处理方式由 AE2 的复制模式决定：在 {@code CLEAR_ON_REMOVE} 下，网格从被选中的
     * 元件加载（因此新元件从它自己的过滤条件开始），而在 {@code KEEP_ON_REMOVE} 下，网格
     * 保持原样——即使从一个元件切换到另一个，标记也留在原处。两种模式都不会自行清空所存储的网格。
     */
    private void handleCellRightClick(int slotIndex) {
        // 与取放元件一致：终端未链接、超距或断电时不得改动驱动器里的元件。
        if (!hasWorkCard() || !canConfigure() || slotIndex < 0 || slotIndex >= driveInventory.size()) {
            return;
        }
        if (selectedSlot >= 0) {
            applyWorkbenchToCell(selectedSlot);
            if (slotIndex == selectedSlot) {
                selectedSlot = -1;
                refreshSelectedCellCapabilities();
                return;
            }
        }
        selectedSlot = slotIndex;
        refreshSelectedCellCapabilities();
        if (copyMode == CopyMode.CLEAR_ON_REMOVE) {
            loadWorkbenchFromCell(slotIndex);
        }
    }

    /** 将给定元件自身的配置加载到网格。 */
    private void loadWorkbenchFromCell(int slotIndex) {
        ItemStack cell = driveInventory.getStackInSlot(slotIndex);
        if (cell == null || cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem cwi)) {
            workbenchConfig.clear();
            markWorkbenchDirty();
            return;
        }
        var source = cwi.getConfigInventory(cell);
        for (int i = 0; i < CONFIG_SLOTS; i++) {
            workbenchConfig.setStack(i, i < source.size() ? source.getStack(i) : null);
        }
        markWorkbenchDirty();
    }

    /** 若给定元件可配置，则将网格写入该元件。 */
    private void applyWorkbenchToCell(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= driveInventory.size()) {
            return;
        }
        ItemStack cell = driveInventory.getStackInSlot(slotIndex);
        if (cell == null || cell.isEmpty() || !(cell.getItem() instanceof ICellWorkbenchItem cwi)) {
            return;
        }
        var target = cwi.getConfigInventory(cell);
        int shared = Math.min(target.size(), CONFIG_SLOTS);
        for (int i = 0; i < shared; i++) {
            target.setStack(i, workbenchConfig.getStack(i));
        }
        for (int i = shared; i < target.size(); i++) {
            target.setStack(i, null);
        }
        // 槽位返回的是副本，因此必须把编辑后的物品堆写回，
        // 配置（存在于元件自身数据中）才会真正生效。
        driveInventory.setItemDirect(slotIndex, cell);
    }

    /** 元件当前存储的键，供分区按钮使用。 */
    private static Iterator<? extends AEKey> iterateCellStacks(ItemStack is) {
        var cellInv = StorageCells.getCellInventory(is, null);
        if (cellInv == null) {
            return Collections.emptyIterator();
        }
        return Iterators.transform(cellInv.getAvailableStacks().iterator(), Map.Entry::getKey);
    }

    /** 玩家最后右键点击的元件的驱动器槽位索引，或 -1；客户端会绘制方框。 */
    public int getSelectedSlot() {
        return selectedSlot;
    }

    /** 包装所选元件自身的升级卡 inventory，供工作台的升级面板使用。 */
    public IUpgradeableObject getWorkbenchUpgrades() {
        return workbenchUpgrades;
    }

    /** 所选元件接受的升级卡数量；未选中任何元件或元件不支持升级卡时为 0。 */
    public int getWorkbenchUpgradeCount() {
        return selectedCellUpgradeCount;
    }

    /** 终端能访问网络时为空，否则为无法访问的原因。 */
    private Component linkStatusMessage() {
        if (!hasPower()) {
            return GuiText.OutOfPower.text();
        }
        ILinkStatus status = linkStatus();
        if (status == null || !status.connected()) {
            Component reason = status != null ? status.statusDescription() : null;
            return reason != null ? reason : Component.translatable("gui.cmt.not_connected");
        }
        if (windowOverflow) {
            return Component.translatable("gui.cmt.too_large");
        }
        return Component.empty();
    }

    private DriveSnapshotPayload.DriveInfo buildInfo(ManagedDrive drive) {
        int cells = drive.cellCount();
        List<DriveSnapshotPayload.SlotStat> stats = new ArrayList<>(cells);
        for (int i = 0; i < cells; i++) {
            stats.add(drive.stat(i));
        }
        return new DriveSnapshotPayload.DriveInfo(drive.pos(), drive.name(), drive.icon(), stats);
    }

    /** 菜单宿主，对外暴露以供 AE2WTlib 的通用终端集成使用。 */
    @Nullable
    public WcmtMenuHost getWcmtHost() {
        return host;
    }
}
