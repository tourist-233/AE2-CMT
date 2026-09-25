package com.wcmt.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import appeng.api.config.Settings;
import appeng.api.config.TerminalStyle;
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
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import com.wcmt.menu.WcmtMenu;
import com.wcmt.network.DriveSnapshotPayload;
import com.wcmt.network.WcmtActionPayload;

import de.mari_023.ae2wtlib.api.gui.ScrollingUpgradesPanel;
import de.mari_023.ae2wtlib.api.terminal.IUniversalTerminalCapable;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Drive manager GUI.
 *
 * <p>The panel is stitched from slices of {@code cmt_interface.png} (256x256; the live area is
 * x 0..208, y 90..252): a 17 px title bar, one 18 px content row per row of drives, that area's own
 * 7 px closing edge, the 74 px player-inventory block and an 11 px closing edge. Panel height is
 * therefore {@code header + rows * 18 + edge + inventory + bottom}, so it grows and shrinks with the
 * row count the way AE2 terminals resize via {@code TerminalStyle.getScreenHeight(rows)}.
 */
public class WcmtScreen extends AEBaseScreen<WcmtMenu> implements IUniversalTerminalCapable {

    // ------------------------------------------------------------------
    // Slices inside cmt_interface.png (256x256; the live area is x 0..208, y 90..252)
    // ------------------------------------------------------------------

    private static final ResourceLocation CELL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/cmt_interface.png");
    /**
     * The terminal's own little art sheet: the storage-cell well, the sort icon and the four state
     * colours. Ordered like AE2's own {@code guis/states.png}, so sub-rects are addressed by source
     * coordinates rather than by slicing it into separate files per element.
     */
    private static final ResourceLocation ICON =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/icon.png");
    private static final int ICON_SIZE = 128;

    /**
     * The 18x20 well drawn around every content slot. Its height matches {@link #ROW_H}, so a whole
     * well fits one row and neighbours never overlap.
     */
    private static final int SLOT_U = 0;
    private static final int SLOT_V = 0;
    private static final int SLOT_W = 18;
    private static final int SLOT_H = 20;

    /** The "by position" sort icon, drawn centred in the button's 16x16 icon area. */
    private static final int SORT_ICON_U = 22;
    private static final int SORT_ICON_V = 3;
    private static final int SORT_ICON_W = 10;
    private static final int SORT_ICON_H = 13;

    /** The well's bottom two rows: a grey capacity groove over a black frame line. */
    private static final int GROOVE_GRAY = 0xFF8E8F96;
    private static final int GROOVE_BLACK = 0xFF000000;
    /** The content area's own 1px edge lines, which the wells would otherwise cover. */
    private static final int CONTENT_EDGE = 0xFFF2F2F2;

    /** Width of the panel drawn from the sheet, its two border columns included. */
    private static final int PANEL_WIDTH = 209;
    /**
     * Extra width on the right of the panel, exactly the upgrade panel's width: the panel hangs off
     * the sheet's right edge the same way AE2's terminal-height button hangs off the left edge.
     * 28 px for the panel itself plus 5 px for the strip its scrollbar needs.
     */
    private static final int UPGRADE_COLUMN_WIDTH = 33;
    /** Total screen width, i.e. the clickable area. */
    private static final int SCREEN_WIDTH = PANEL_WIDTH + UPGRADE_COLUMN_WIDTH;

    /** Plain text colour used for labels (matches the drive names). */
    private static final int LABEL_COLOR = 0xFF3B3B4D;
    private static final Style LABEL_STYLE = Style.EMPTY.withColor(TextColor.fromRgb(LABEL_COLOR));

    /** Player inventory block: three rows plus the hotbar, wells 16 px wide, 18 px apart. */
    private static final int INV_V = 150;
    private static final int INV_W = 209;
    private static final int INV_H = 74;

    /** Closing edge under the inventory block (224..234 on the sheet). */
    private static final int BOTTOM_V = 224;
    private static final int BOTTOM_H = 11;

    /** Title bar: dark edge, highlight, 12 px face, highlight, two dark rows. */
    private static final int HEADER_V = 87;
    private static final int HEADER_H = 16;

    /** One content row: light edge columns around the solid face the drives are listed on. */
    private static final int ROW_V = 103;
    private static final int ROW_W = PANEL_WIDTH;
    private static final int ROW_H = 20;

    /** The content area's own closing edge, right below the last content row. */
    private static final int ROW_EDGE_V = 143;
    private static final int ROW_EDGE_H = 7;

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private static final int SLOT_X0 = 8;
    private static final int SLOT_STEP = 18;
    private static final int SLOTS_PER_ROW = WcmtMenu.SLOTS_PER_ROW;

    /**
     * Player slots inside the inventory block. The wells start at sheet x=8 and are 18 px apart; the
     * hotbar sits 22 px below the third backpack row (not 18). Each offset points at the top of the
     * cell's well box.
     */
    private static final int INV_COLS = 9;
    private static final int INV_SLOT_X = 8;
    private static final int INV_ROW0 = 0;
    private static final int INV_HOTBAR = 58;

    /**
     * The two grooves on the right of the inventory block, each showing one progress from the bottom
     * up: the left one the type counts of every cell in the network, the right one the byte usage.
     */
    private static final int TYPE_BAR_X = 177;
    private static final int STORAGE_BAR_X = 194;
    /**
     * The groove runs the full height of the inventory block (sheet y150..223), so a filled groove
     * reaches both ends without a gap.
     */
    private static final int BAR_V = 150;
    private static final int BAR_W = 8;
    private static final int BAR_H = 74;
    /**
     * The sheet's four 8px-wide vertical strips (y 13..88), one per state. The progress grooves
     * show them directly instead of a flat colour, so their shading pattern comes from the art.
     */
    private static final int BAR_STRIP_BOTTOM = 89;
    private static final int BAR_U_UNLIMITED = 93;
    private static final int BAR_U_FULL = 102;
    private static final int BAR_U_HALF = 111;
    private static final int BAR_U_LOW = 120;

    /** AE2's scrollbar handle runs down the sheet's right-hand groove (x 194..201). */
    private static final int SCROLLBAR_X = 191;

    /** Smallest content-row budget the panel may shrink to; the drawn row count can be lower. */
    private static final int MIN_ROWS = 4;
    private static final int MAX_ROWS = WcmtMenu.MAX_ROWS;
    private static final int HIDDEN = -9999;

    /** Pre-built name+caption line per visible drive; rebuilt whenever a snapshot arrives. */
    private Component[] driveHeaders = new Component[0];

    /**
     * Fill colours, sampled from the art sheet's own swatches. Both the per-slot capacity groove and
     * the network-wide progress grooves use the same scale: unlimited, nearly full, half, plenty.
     */
    private static final int STORAGE_COLOR_EMPTY = 0x7A7F92;
    private static final int STORAGE_COLOR_LOW = 0x46FA3D;
    private static final int STORAGE_COLOR_MEDIUM = 0xFA7C3D;
    private static final int STORAGE_COLOR_HIGH = 0xFF2427;

    /** Unlimited capacity (a cell that reports infinite bytes, or NeoECO's infinite mode). */
    private static final int INFINITE_COLOR = 0xFFD73DFA;

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final List<AppEngSlot> driveSlots = new ArrayList<>();
    private final Scrollbar scrollbar;
    /** The wireless terminal's scrolling upgrade panel, or null for the cable-mounted part. */
    @Nullable
    private final ScrollingUpgradesPanel upgradesPanel;
    /** The cable-mounted part's plain upgrade panel, or null for the wireless terminal. */
    @Nullable
    private final UpgradesPanel partUpgradesPanel;

    private int rows = MIN_ROWS;
    /** First content row of each visible drive. */
    private int[] driveRowStart = new int[0];
    /** Cell count of each visible drive. */
    private int[] driveCells = new int[0];

    private DriveSnapshotPayload lastApplied;
    private int scrollOffset;
    /** Last row we asked the server for, so repeated wheel steps don't resend the same request. */
    private int lastRequestedScroll = -1;
    /**
     * Row offset used while drawing: eases toward {@link #scrollOffset} so a wheel step animates
     * instead of jumping a whole row at once. Slot positions themselves stay on whole rows, so
     * clicks keep landing where they should.
     */
    private float smoothOffset = -1f;
    /**
     * Mirror of the ordering stored on the terminal itself. Rebuilt from every snapshot, so it also
     * survives closing and reopening the screen.
     */
    private WcmtMenu.SortMode clientSortMode = WcmtMenu.SortMode.POSITION;
    private boolean clientSortDescending;

    /** Summed per-kind totals of the whole network, for the two grooves beside the inventory. */
    private long netTypes;
    private long netTypeCapacity;
    private long netUsedBytes;
    private long netTotalBytes;
    /** True while the network holds an unlimited cell; both grooves then read as full. */
    private boolean netInfinite;

    public WcmtScreen(WcmtMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
        this.scrollbar = widgets.addScrollBar("scrollbar", Scrollbar.BIG);
        // The wireless terminal uses AE2WTlib's scrolling panel, the same one the universal terminal
        // uses: it expects the menu's slot list to start with the quantum-bridge singularity slot
        // (which WcmtMenu provides) and keeps the column scrollable when there are more slots than
        // fit. The cable-mounted part has no upgrade inventory at all, so it gets AE2's plain panel
        // (which stays empty) to keep the sheet's upgrade column in place.
        if (menu.getWcmtHost() != null) {
            this.upgradesPanel = addUpgradePanel(widgets, menu);
            this.partUpgradesPanel = null;
        } else {
            this.upgradesPanel = null;
            var panel = new UpgradesPanel(menu.getSlots(SlotSemantics.UPGRADE));
            widgets.add("upgrades", panel);
            this.partUpgradesPanel = panel;
        }
        // Only the universal terminal has other terminals to cycle through.
        if (menu.isWUT()) {
            addToLeftToolbar(cycleTerminalButton());
        }
        addToLeftToolbar(new SettingToggleButton<>(Settings.TERMINAL_STYLE,
                AEConfig.instance().getTerminalStyle(), this::toggleTerminalStyle));
        addToLeftToolbar(new SortButton());
        addToLeftToolbar(new SortDirectionButton());
    }

    /**
     * Cycles the ordering key. Mirrored locally so the icon reacts immediately; the server stays
     * authoritative and its next snapshot carries the same value back.
     */
    private void cycleSort() {
        var modes = WcmtMenu.SortMode.values();
        clientSortMode = modes[(clientSortMode.ordinal() + 1) % modes.length];
        PacketDistributor.sendToServer(new WcmtActionPayload(
                WcmtActionPayload.ACTION_SORT, clientSortMode.ordinal(), 0, ""));
    }

    /** Flips the ordering between top-to-bottom and bottom-to-top. */
    private void cycleSortDirection() {
        clientSortDescending = !clientSortDescending;
        PacketDistributor.sendToServer(new WcmtActionPayload(
                WcmtActionPayload.ACTION_SORT_DIRECTION, clientSortDescending ? 1 : 0, 0, ""));
    }

    /** Left-toolbar button that steps through the ordering keys, using AE2's own sort sprites. */
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
            // The "by position" icon has no AE2 sprite of its own, so it is drawn from our own texture
            // at the same offset and z the base class uses for its icons.
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

    /** Left-toolbar button that flips the ordering direction. */
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
    // Setup
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        computeSize();
        super.init();
        collectDriveSlots();
        applyLayout(ClientDriveData.get());
        lastApplied = ClientDriveData.get();
        positionPlayerInventory();
        // Hang the upgrade panel off the sheet's right edge, mirroring the terminal-height button
        // that AE2 attaches to the left edge of the panel.
        if (upgradesPanel != null) {
            upgradesPanel.setPosition(new Point(PANEL_WIDTH - 2, 0));
            upgradesPanel.setMaxRows(WcmtMenu.UPGRADE_SLOTS);
        } else if (partUpgradesPanel != null) {
            partUpgradesPanel.setPosition(new Point(PANEL_WIDTH - 2, 0));
        }
        updateScrollbar();
        sendLayout(scrollOffset, rows);
    }

    /** Places the scrollbar on the pre-baked track and sizes it to the content rows. */
    private void updateScrollbar() {
        // The groove beside the content rows is exactly rows*ROW_H tall (the sheet breaks it at the
        // closing edge below), so the handle travels from the first to the last visible row and no
        // further. Running it over the inventory block would make the handle leave the groove.
        scrollbar.setPosition(new Point(SCROLLBAR_X, contentTop()));
        scrollbar.setHeight(rows * ROW_H);
        // Use the row count the server actually rendered with; our own estimate can differ and would
        // then let the scrollbar overshoot the end (or stop halfway).
        int windowRows = Math.max(1, ClientDriveData.windowRows());
        scrollbar.setRange(0, Math.max(0, ClientDriveData.totalRows() - windowRows), 1);
        // Only seed the handle while it is still untouched; afterwards it belongs to the player, and
        // overwriting it here would undo whatever they just scrolled to.
        if (lastRequestedScroll < 0) {
            scrollbar.setCurrentScroll(scrollOffset);
        }
        scrollbar.setVisible(true);
    }

    /**
     * How far the content is currently drawn from its snapped position, in pixels. The content is
     * already laid out for {@code scrollOffset}, so to make it look like it is still at
     * {@code smoothOffset} the offset has to go the other way.
     */
    private float scrollShift() {
        return (scrollOffset - smoothOffset) * ROW_H;
    }

    /** header + rows * rowHeight + bottom; the row count follows the window and terminal style. */
    private void computeSize() {
        int availableHeight = this.height - 2 * AEConfig.instance().getTerminalMargin();
        int availableRows = (availableHeight - HEADER_H - ROW_EDGE_H - INV_H - BOTTOM_H) / ROW_H;
        int maxRows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, availableRows));
        int budget = Math.max(MIN_ROWS, AEConfig.instance().getTerminalStyle().getRows(maxRows));
        // One row less than the budget, kept even: a vanilla drive occupies exactly two rows
        // (name + one 10-cell row), so the content ends flush without a trailing empty row.
        int wanted = Math.max(1, budget - 1);
        rows = Math.max(2, wanted - (wanted % 2));
        this.imageWidth = SCREEN_WIDTH;
        this.imageHeight = HEADER_H + rows * ROW_H + ROW_EDGE_H + INV_H + BOTTOM_H;
    }

    private int contentTop() {
        return HEADER_H;
    }

    private int bottomTop() {
        return HEADER_H + rows * ROW_H + ROW_EDGE_H;
    }

    /** Top of the player inventory, i.e. past the full-width band below the content rows. */
    private int inventoryTop() {
        return bottomTop();
    }

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
    }

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
    // Per-drive layout (from the latest snapshot)
    // ------------------------------------------------------------------

    private void applyLayout(DriveSnapshotPayload data) {
        List<DriveSnapshotPayload.DriveInfo> drives = data == null ? List.of() : data.drives();
        int count = drives.size();
        updateNetTotals();

        driveRowStart = new int[count];
        driveCells = new int[count];

        // A terminal that cannot reach its network (or has nothing that fits) shows nothing but the
        // reason, so hide every slot and every row background.
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
                    // Cells reporting nonsense counters (a huge sentinel can be negative) stay out of
                    // the drive's totals just like unlimited ones.
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
        for (int k = 0; k < count; k++) {
            int cells = drives.get(k).slots().size();
            int driveRows = WcmtMenu.rowsForCells(cells);
            driveRowStart[k] = rowCursor;
            driveCells[k] = cells;
            rowCursor += driveRows;
            cellCursor += cells;
        }

        for (int i = 0; i < driveSlots.size(); i++) {
            AppEngSlot slot = driveSlots.get(i);
            int rowIndex = rowIndexForSlot(i, count);
            if (blocked || i >= cellCursor || rowIndex < 0) {
                hide(slot);
                continue;
            }
            int local = localSlotFor(i, count);
            slot.x = SLOT_X0 + (local % SLOTS_PER_ROW) * SLOT_STEP;
            slot.y = contentTop() + rowIndex * ROW_H;
        }
    }

    /** Content row of the slot pool index {@code i}, or -1 when it is outside the window. */
    private int rowIndexForSlot(int index, int count) {
        int local = localSlotFor(index, count);
        if (local < 0) {
            return -1;
        }
        int k = driveIndexForSlot(index, count);
        int rowIndex = driveRowStart[k] + 1 + local / SLOTS_PER_ROW;
        return rowIndex >= 0 && rowIndex < rows ? rowIndex : -1;
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

    private int localSlotFor(int index, int count) {
        int k = driveIndexForSlot(index, count);
        if (k < 0) {
            return -1;
        }
        int acc = 0;
        for (int i = 0; i < k; i++) {
            acc += driveCells[i];
        }
        return index - acc;
    }

    private static void hide(AppEngSlot slot) {
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
    // Scrolling / terminal style
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
        // The terminal keeps its state in the menu, so there is nothing client-side to carry over
        // when AE2WTlib switches away from this terminal.
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        var data = ClientDriveData.get();
        if (data != lastApplied) {
            lastApplied = data;
            applyLayout(data);
            // The snapshot is the only thing that moves the view: the wheel merely asks the server for
            // a new offset, so the content and the easing offset always describe the same window.
            // Starting the easing here (from wherever we were) makes the jump animate.
            scrollOffset = data == null ? 0 : data.offset();
            updateScrollbar();
            // The ordering lives on the terminal, not in this screen, so adopt whatever the server
            // reports: reopening the terminal then shows the key and direction it was left in.
            clientSortMode = ClientDriveData.sortMode();
            clientSortDescending = ClientDriveData.sortDescending();
        }

        // The wheel/track only picks a new row; the view follows once the server confirms it.
        int scrolled = scrollbar.getCurrentScroll();
        if (scrolled != lastRequestedScroll) {
            lastRequestedScroll = scrolled;
            sendLayout(scrolled, rows);
        }
        if (smoothOffset < 0f) {
            // First snapshot: start where we are instead of animating in from row 0.
            smoothOffset = scrollOffset;
        }
        // Ease the drawing offset toward the target row so a step slides instead of jumping.
        smoothOffset += (scrollOffset - smoothOffset) * 0.2f;
        if (Math.abs(scrollOffset - smoothOffset) < 0.02f) {
            smoothOffset = scrollOffset;
        }
    }

    private void toggleTerminalStyle(SettingToggleButton<TerminalStyle> button, boolean backwards) {
        TerminalStyle next = button.getNextValue(backwards);
        AEConfig.instance().setTerminalStyle(next);
        button.set(next);
        reinitialize();
    }

    /** Re-runs {@link #init()} after removing all widgets (same pattern as AE2's terminals). */
    private void reinitialize() {
        new ArrayList<>(this.children()).forEach(this::removeWidget);
        this.init();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY,
            float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);

        blit(guiGraphics, offsetX, offsetY, 0, HEADER_V, PANEL_WIDTH, HEADER_H);

        int y = offsetY + contentTop();
        // Content slides by the easing offset; the panel art and the surrounding bars do not move.
        float shift = scrollShift();
        if (shift != 0f) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0f, shift, 0f);
        }
        for (int row = 0; row < rows; row++) {
            blit(guiGraphics, offsetX, y, 0, ROW_V, ROW_W, ROW_H);
            y += ROW_H;
        }

        // Storage cell wells. An 18x20 well is exactly one row tall, so wells never overlap; the
        // bottom two well rows are then overwritten with the capacity groove (grey base, black
        // frame) the fill colour is painted into. Both only show for slots that actually hold a
        // cell.
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

        if (shift != 0f) {
            guiGraphics.pose().popPose();
        }

        // Those wells span the slot columns exactly, so they cover the content area's own left and
        // right edge lines; draw the two lines back on top.
        int edgeTop = offsetY + contentTop();
        int edgeBottom = offsetY + bottomTop();
        guiGraphics.fill(offsetX + SLOT_X0 - 1, edgeTop, offsetX + SLOT_X0, edgeBottom, CONTENT_EDGE);
        int edgeRight = SLOT_X0 + (SLOTS_PER_ROW - 1) * SLOT_STEP + 16;
        guiGraphics.fill(offsetX + edgeRight, edgeTop, offsetX + edgeRight + 1, edgeBottom, CONTENT_EDGE);

        // The content area's own closing edge, then the inventory block (the sheet lays the two out
        // back to back, so they must not be shifted against each other).
        blit(guiGraphics, offsetX, y, 0, ROW_EDGE_V, PANEL_WIDTH, ROW_EDGE_H);
        y += ROW_EDGE_H;
        blit(guiGraphics, offsetX, y, 0, INV_V, INV_W, INV_H);
        blit(guiGraphics, offsetX, y + INV_H, 0, BOTTOM_V, PANEL_WIDTH, BOTTOM_H);
    }

    /**
     * Slot contents slide with the rest of the row, while the slot itself stays snapped. Only the
     * drive slots take part: the player inventory must stay put, or the whole screen looks like it
     * is jittering while scrolling.
     */
    @Override
    public void renderSlot(GuiGraphics guiGraphics, net.minecraft.world.inventory.Slot slot) {
        float shift = scrollShift();
        if (shift != 0f && slot instanceof AppEngSlot engSlot && driveSlots.contains(engSlot)) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0f, shift, 0f);
            super.renderSlot(guiGraphics, slot);
            guiGraphics.pose().popPose();
            return;
        }
        super.renderSlot(guiGraphics, slot);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        List<DriveSnapshotPayload.DriveInfo> drives = ClientDriveData.drives();
        boolean blocked = ClientDriveData.blocked();

        float shift = scrollShift();
        if (shift != 0f) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0f, shift, 0f);
        }

        for (int k = 0; k < drives.size() && !blocked && k < driveRowStart.length; k++) {
            DriveSnapshotPayload.DriveInfo info = drives.get(k);
            int headerRow = driveRowStart[k];
            int headerY = contentTop() + headerRow * ROW_H;

            // A drive scrolled halfway out of the window has a negative or too small row index;
            // its name row must not be drawn outside the panel.
            if (headerRow >= 0 && headerRow < rows && k < driveHeaders.length) {
                guiGraphics.renderItem(info.icon(), SLOT_X0, headerY + 1);
                guiGraphics.drawString(font, driveHeaders[k].getVisualOrderText(), SLOT_X0 + 18, headerY + 5,
                        LABEL_COLOR, false);
            }

            for (int local = 0; local < driveCells[k] && local < info.slots().size(); local++) {
                int slotIndex = slotIndexFor(k, local);
                if (slotIndex < 0 || slotIndex >= driveSlots.size()) {
                    continue;
                }
                AppEngSlot slot = driveSlots.get(slotIndex);
                if (slot.x == HIDDEN) {
                    continue;
                }
                if (!slot.getItem().isEmpty()) {
                    drawCapacityBar(guiGraphics, slot.x, slot.y + ROW_H - 4, info.slots().get(local));
                }
            }
        }

        if (shift != 0f) {
            guiGraphics.pose().popPose();
        }

        drawProgressBars(guiGraphics);
        drawLinkStatus(guiGraphics);
    }

    /** Copies the network-wide totals the server computed; the two grooves beside the inventory use them. */
    private void updateNetTotals() {
        netTypes = ClientDriveData.typeUsed();
        netTypeCapacity = ClientDriveData.typeTotal();
        netUsedBytes = ClientDriveData.byteUsed();
        netTotalBytes = ClientDriveData.byteTotal();
        netInfinite = ClientDriveData.infinite();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        addProgressTooltips(guiGraphics, mouseX, mouseY);
    }

    /**
     * Fills the sheet's two grooves beside the player inventory: the left one with how many of the
     * network's storage types are used, the right one with its byte usage. Both are filled from the
     * bottom up in the same colours as the drive headers.
     */
    private void drawProgressBars(GuiGraphics guiGraphics) {
        drawProgressBar(guiGraphics, TYPE_BAR_X, netTypes, netTypeCapacity);
        drawProgressBar(guiGraphics, STORAGE_BAR_X, netUsedBytes, netTotalBytes);
    }

    private void drawProgressBar(GuiGraphics guiGraphics, int x, long used, long total) {
        int top = inventoryTop() + BAR_V - INV_V;
        if (netInfinite) {
            // Unlimited storage has no ratio to show: the whole groove in the unlimited strip.
            drawBarStrip(guiGraphics, x, top, BAR_U_UNLIMITED, BAR_H);
            return;
        }
        if (total <= 0) {
            return;
        }
        // At least a sliver, so an empty network still shows where the groove is.
        int filled = (int) Math.max(1, Math.min(BAR_H, used * BAR_H / total));
        drawBarStrip(guiGraphics, x, top + (BAR_H - filled), barStripU(used, total), filled);
    }

    /** Paints {@code height} rows of one of the sheet's state strips, growing from the bottom up. */
    private static void drawBarStrip(GuiGraphics guiGraphics, int x, int y, int u, int height) {
        Blitter.texture(ICON, ICON_SIZE, ICON_SIZE)
                .src(u, BAR_STRIP_BOTTOM - height, BAR_W, height)
                .dest(x, y)
                .blit(guiGraphics);
    }

    /** Picks the strip whose state matches how full the network is. */
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
     * While the cursor is over one of the grooves, say what it currently shows. The render mouse
     * coordinates are absolute, so the hit test has to go through {@link #isHovering}.
     */
    private void addProgressTooltips(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int top = inventoryTop() + BAR_V - INV_V;
        if (netInfinite) {
            // Both grooves read "unlimited" while the network holds an unlimited cell.
            if (isHovering(TYPE_BAR_X, top, BAR_W, BAR_H, mouseX, mouseY)) {
                guiGraphics.renderTooltip(font,
                        Component.translatable("gui.cmt.groove_types_infinite"), mouseX, mouseY);
            } else if (isHovering(STORAGE_BAR_X, top, BAR_W, BAR_H, mouseX, mouseY)) {
                guiGraphics.renderTooltip(font,
                        Component.translatable("gui.cmt.groove_storage_infinite"), mouseX, mouseY);
            }
            return;
        }
        if (isHovering(TYPE_BAR_X, top, BAR_W, BAR_H, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, tooltipLines(
                    Component.translatable("gui.cmt.groove_types", formatBytes(netTypes),
                            formatBytes(netTypeCapacity)),
                    usedLine(netTypes, netTypeCapacity)), mouseX, mouseY);
        } else if (isHovering(STORAGE_BAR_X, top, BAR_W, BAR_H, mouseX, mouseY)) {
            guiGraphics.renderTooltip(font, tooltipLines(
                    Component.translatable("gui.cmt.groove_storage", formatBytes(netUsedBytes),
                            formatBytes(netTotalBytes)),
                    usedLine(netUsedBytes, netTotalBytes)), mouseX, mouseY);
        }
    }

    /**
     * A tooltip's line breaks come from the list it is handed; a "\n" appended to a component is
     * rendered on the same line. GuiGraphics wants {@code FormattedCharSequence} entries here.
     */
    private static List<FormattedCharSequence> tooltipLines(Component first, Component second) {
        List<FormattedCharSequence> lines = new ArrayList<>(2);
        lines.add(first.getVisualOrderText());
        lines.add(second.getVisualOrderText());
        return lines;
    }

    private static Component usedLine(long used, long total) {
        int percent = total > 0 ? (int) Math.round(used * 100.0 / total) : 0;
        // The percent sign goes in the argument: a literal "%%" in the translation was not being
        // unescaped on every path and showed up as a placeholder.
        return Component.translatable("gui.cmt.groove_used", percent + "%");
    }

    /**
     * AE2's own treatment for an unusable terminal: dim the content area and centre the reason
     * (unlinked / out of range / out of power) in it.
     */
    private void drawLinkStatus(GuiGraphics guiGraphics) {
        if (!ClientDriveData.blocked()) {
            return;
        }
        Component status = ClientDriveData.status();
        if (status.getString().isEmpty()) {
            return;
        }
        // Only the content rows are dimmed; the sheet's middle panel and the inventory stay clear.
        int contentLeft = SLOT_X0;
        int contentRight = SLOT_X0 + SLOTS_PER_ROW * SLOT_STEP - 1;
        int shadeBottom = bottomTop() - 7;
        guiGraphics.fill(contentLeft, contentTop(), contentRight, shadeBottom, 0x3F000000);
        guiGraphics.drawCenteredString(font, status, (contentLeft + contentRight) / 2,
                (contentTop() + shadeBottom) / 2 - 4, 0xFFFF5555);
    }

    private int slotIndexFor(int driveIndex, int local) {
        int acc = 0;
        for (int i = 0; i < driveIndex && i < driveCells.length; i++) {
            acc += driveCells[i];
        }
        return acc + local;
    }

    /** Shown for a drive whose cells are all unlimited, so it has no usable totals to report. */
    private static Component infiniteText() {
        return Component.translatable("gui.cmt.infinite")
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(INFINITE_COLOR & 0xFFFFFF)));
    }

    /** Same colour code as the drive headers, driven by how full the byte budget is. */
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

    /** Drawn inside the well's own groove, so it never covers the cell icon. */
    private void drawCapacityBar(GuiGraphics guiGraphics, int x, int y, DriveSnapshotPayload.SlotStat stat) {
        int width = SLOT_STEP - 2;
        if (stat.infinite()) {
            guiGraphics.fill(x, y, x + width, y + 1, INFINITE_COLOR);
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

    private static String formatBytes(long value) {
        if (value < 1000) {
            return Long.toString(value);
        }
        if (value < 1_000_000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000.0);
        }
        if (value < 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }
        return String.format(Locale.ROOT, "%.1fG", value / 1_000_000_000.0);
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
