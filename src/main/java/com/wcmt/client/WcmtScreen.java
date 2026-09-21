package com.wcmt.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import appeng.api.config.Settings;
import appeng.api.config.TerminalStyle;
import appeng.api.storage.cells.CellState;
import appeng.client.Point;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.ScreenStyle;
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
    private static final int HEADER_V = 90;
    private static final int HEADER_H = 17;

    /** One content row: light edge columns around the solid face the drives are listed on. */
    private static final int ROW_V = 107;
    private static final int ROW_W = PANEL_WIDTH;
    private static final int ROW_H = 18;

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
    private static final int BAR_V = 152;
    private static final int BAR_W = 8;
    private static final int BAR_H = 72;

    /** AE2's scrollbar handle is centred on the sheet's right-hand groove (x 194..201). */
    private static final int SCROLLBAR_X = 192;

    /** Smallest content-row budget the panel may shrink to; the drawn row count can be lower. */
    private static final int MIN_ROWS = 4;
    private static final int MAX_ROWS = WcmtMenu.MAX_ROWS;
    private static final int HIDDEN = -9999;

    /** Cached so the per-cell capacity bar doesn't clone the enum array every frame. */
    private static final CellState[] CELL_STATES = CellState.values();

    /** Pre-built name+caption line per visible drive; rebuilt whenever a snapshot arrives. */
    private Component[] driveHeaders = new Component[0];

    private static final int STORAGE_COLOR_EMPTY = 0x7A7F92;
    private static final int STORAGE_COLOR_LOW = 0x3F8F3F;
    private static final int STORAGE_COLOR_MEDIUM = 0xBC8A2A;
    private static final int STORAGE_COLOR_HIGH = 0xB04545;

    /** Unlimited capacity (a cell that reports infinite bytes, or NeoECO's infinite mode). */
    private static final int INFINITE_COLOR = 0xFFCB61F6;

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
        scrollbar.setPosition(new Point(SCROLLBAR_X, contentTop()));
        scrollbar.setHeight(rows * ROW_H);
        scrollbar.setRange(0, Math.max(0, ClientDriveData.totalRows() - rows), 1);
        scrollbar.setCurrentScroll(scrollOffset);
        scrollbar.setVisible(true);
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
                // The same background icon vanilla AE2 puts behind its own storage cell slots;
                // AppEngSlot rendering draws it only while the slot is empty.
                appEngSlot.setIcon(Icon.BACKGROUND_STORAGE_CELL);
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
            scrollOffset = data == null ? 0 : data.offset();
            updateScrollbar();
        }

        int scrolled = scrollbar.getCurrentScroll();
        if (scrolled != scrollOffset) {
            scrollOffset = scrolled;
            sendLayout(scrolled, rows);
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
        for (int row = 0; row < rows; row++) {
            blit(guiGraphics, offsetX, y, 0, ROW_V, ROW_W, ROW_H);
            y += ROW_H;
        }

        // The content area's own closing edge, then the inventory block (the sheet lays the two out
        // back to back, so they must not be shifted against each other).
        blit(guiGraphics, offsetX, y, 0, ROW_EDGE_V, PANEL_WIDTH, ROW_EDGE_H);
        y += ROW_EDGE_H;
        blit(guiGraphics, offsetX, y, 0, INV_V, INV_W, INV_H);
        blit(guiGraphics, offsetX, y + INV_H, 0, BOTTOM_V, PANEL_WIDTH, BOTTOM_H);
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        List<DriveSnapshotPayload.DriveInfo> drives = ClientDriveData.drives();
        boolean blocked = ClientDriveData.blocked();

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
                    drawCapacityBar(guiGraphics, slot.x, slot.y + ROW_H - 2, info.slots().get(local));
                }
            }
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
        int bottom = inventoryTop() + BAR_V - INV_V + BAR_H;
        if (netInfinite) {
            // Unlimited storage has no ratio to show: a full groove in the unlimited colour.
            guiGraphics.fill(x, bottom - BAR_H, x + BAR_W, bottom, INFINITE_COLOR);
            return;
        }
        if (total <= 0) {
            return;
        }
        // At least a sliver, so an empty network still shows where the groove is.
        int filled = (int) Math.max(1, Math.min(BAR_H, used * BAR_H / total));
        // storageColor carries no alpha; the groove fill needs an opaque colour.
        guiGraphics.fill(x, bottom - filled, x + BAR_W, bottom,
                storageColor(used, total) | 0xFF000000);
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
        guiGraphics.fill(contentLeft, contentTop(), contentRight, bottomTop(), 0x3F000000);
        guiGraphics.drawCenteredString(font, status, (contentLeft + contentRight) / 2,
                (contentTop() + bottomTop()) / 2 - 4, 0xFFFF5555);
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

    /** Drawn along the bottom edge of the cell, so it never covers the cell icon. */
    private void drawCapacityBar(GuiGraphics guiGraphics, int x, int y, DriveSnapshotPayload.SlotStat stat) {
        // As wide as the slot icon, so the bar never reaches into the next column.
        int width = SLOT_STEP - 2;
        guiGraphics.fill(x, y, x + width, y + 2, 0x66202020);
        if (stat.infinite()) {
            // Unlimited capacity: always a full purple bar.
            guiGraphics.fill(x, y, x + width, y + 2, INFINITE_COLOR);
            return;
        }
        int filled = stat.total() > 0
                ? (int) Math.round(width * (double) stat.used() / stat.total())
                : 0;
        filled = Math.max(0, Math.min(width, filled));
        if (filled > 0) {
            int color = CELL_STATES[Math.floorMod(stat.state(), CELL_STATES.length)].getStateColor();
            guiGraphics.fill(x, y, x + filled, y + 2, color | 0xFF000000);
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
