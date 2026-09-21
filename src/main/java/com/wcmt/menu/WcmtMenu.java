package com.wcmt.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.wcmt.init.ModMenus;
import com.wcmt.network.DriveSnapshotPayload;
import com.wcmt.network.WcmtActionPayload;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.ILinkStatus;import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.localization.GuiText;
import appeng.items.tools.powered.WirelessTerminalItem;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.api.stacks.KeyCounter;
import appeng.menu.slot.RestrictedInputSlot;
import de.mari_023.ae2wtlib.api.gui.AE2wtlibSlotSemantics;
import de.mari_023.ae2wtlib.api.terminal.ItemWUT;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;
import com.wcmt.part.WcmtTerminalPart;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Menu of the Wireless Component Management Terminal.
 *
 * <p>The client sends a row budget (derived from the window height and the AE2 terminal-style
 * setting). The server fills that many rows with consecutive drives and maps a fixed pool of cell
 * slots onto them contiguously, so drives with a different number of cells (e.g. ExtendedAE's
 * 20-slot drive) are handled as well.
 */
public class WcmtMenu extends AEBaseMenu {

    /** Cells laid out per row (a vanilla drive's 10 cells fill exactly one row). */
    public static final int SLOTS_PER_ROW = 10;
    /** Upper bound on content rows; the actual budget is client-driven. */
    public static final int MAX_ROWS = 24;
    /** Upper bound on the total number of cell slots managed at once; also the slot pool size. */
    public static final int MAX_SLOTS = 128;
    /** The network-wide totals get their own, slower rate: reading them walks cell contents. */
    private static final int TOTALS_SCAN_TICKS = 20;
    /** How often, in ticks, the terminal rescans the network for drives while it is open. */
    private static final int SCAN_INTERVAL_TICKS = 10;
    /** Upgrade slots in the panel's top-right corner; four energy cards quadruple the power buffer. */
    public static final int UPGRADE_SLOTS = 4;

    public enum SortMode {
        POSITION,
        NAME,
        USAGE
    }

    /** The wireless host, or null when this menu belongs to the cable-mounted part. */
    @Nullable
    private final WcmtMenuHost host;
    /** The cable-mounted part hosting this menu, or null for the wireless terminal. */
    @Nullable
    private final WcmtTerminalPart part;
    private final PagedDriveInventory driveInventory;
    private final boolean serverSide;

    private int tickCounter;
    private boolean needsSnapshot = true;
    private boolean needsTotals = true;
    /** Network-wide totals for the two grooves: typeUsed, typeTotal, byteUsed, byteTotal. */
    private final long[] netTotals = new long[4];
    /** True while any cell in the network is unlimited, which the grooves show as full. */
    private boolean netInfinite;
    /** First content row shown in the window (a drive spans a name row plus its cell rows). */
    private int offset;
    /** Content rows of all drives; drives with more cells occupy more rows. */
    private int totalRows;
    /** Rows of the first visible drive that are scrolled off above the window. */
    private int skippedRows;
    private int rowsBudget = 4;
    private SortMode sortMode = SortMode.POSITION;
    private String search = "";
    private List<ManagedDrive> allDrives = List.of();
    private List<ManagedDrive> visibleDrives = List.of();
    /** Set when drives exist but none fits into the slot pool; reported to the client. */
    private boolean windowOverflow;
    /** Number of backing drive slots on the client, kept in sync via the snapshot payload. */
    private int clientActiveSlots;

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
        this.driveInventory = new PagedDriveInventory(serverSide, this);

        for (int i = 0; i < MAX_SLOTS; i++) {
            addSlot(new DriveCellSlot(driveInventory, i, this), SlotSemantics.STORAGE_CELL);
        }
        createPlayerInventorySlots(playerInventory);

        // Upgrade cards are an item terminal feature: AE2's terminal parts have no upgrade inventory,
        // so the cable-mounted variant only gets the drive slots. Field order matters here — the host
        // check and getUpgrades() must not run for the part.
        if (this.host != null) {
            // The quantum-bridge singularity slot must come first: AE2WTlib's upgrade panel treats the
            // first slot of its list as the singularity slot and hides it while it is empty.
            addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.QE_SINGULARITY,
                    this.host.getSubInventory(WTMenuHost.INV_SINGULARITY), 0),
                    AE2wtlibSlotSemantics.SINGULARITY);

            var upgrades = this.host.getUpgrades();
            int slotCount = isWUT() ? upgrades.size() : Math.min(UPGRADE_SLOTS, upgrades.size());
            for (int i = 0; i < slotCount; i++) {
                addSlot(new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.UPGRADES, upgrades, i),
                        SlotSemantics.UPGRADE);
            }
        }

        if (serverSide) {
            refreshDrives();
        }
    }

    /** True while the terminal was opened from AE2WTlib's universal terminal. */
    public boolean isWUT() {
        return host != null && host.getItemStack().getItem() instanceof ItemWUT;
    }

    /** Rows needed by a drive with the given number of cells (1 name row + ceil(cells / 10) rows). */
    public static int rowsForCells(int cells) {
        return 1 + (cells + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW;
    }

    // ------------------------------------------------------------------
    // Access control
    // ------------------------------------------------------------------

    public boolean canInsert() {
        if (!serverSide) {
            return true;
        }
        return linkUsable() && hasPower();
    }

    public boolean canExtract() {
        if (!serverSide) {
            return true;
        }
        return linkUsable() && hasPower();
    }

    /** False while the terminal is unlinked, out of range or out of power. */
    private boolean linkUsable() {
        ILinkStatus status = linkStatus();
        return status == null || status.connected();
    }

    /** The link status of whichever host this menu belongs to; null if there is neither. */
    @Nullable
    private ILinkStatus linkStatus() {
        if (host != null) {
            return host.getLinkStatus();
        }
        return part == null ? null : part.getLinkStatus();
    }

    /** The grid this menu works on, or null while it cannot reach one. */
    @Nullable
    private IGrid wiredGrid() {
        if (host != null) {
            return host.getConnectedGrid();
        }
        return part == null ? null : part.getMainNode().getGrid();
    }

    /** AE2 terminals stop working once their own energy buffer is empty; creative players never do. */
    private boolean hasPower() {
        if (getPlayer().isCreative() || host == null) {
            // The cable-mounted part draws from the network; there is no item buffer to check.
            return true;
        }
        ItemStack terminal = host.getItemStack();
        return terminal.getItem() instanceof WirelessTerminalItem wireless
                && wireless.getAECurrentPower(terminal) > 0;
    }

    /** True while the terminal cannot be used at all: unlinked, unpowered or nothing fits. */
    private boolean blocked() {
        return !linkUsable() || !hasPower() || windowOverflow;
    }

    // ------------------------------------------------------------------
    // Slot availability
    // ------------------------------------------------------------------

    /** Total number of drive cell slots that currently map to a real drive. */
    public int activeSlotCount() {
        return serverSide ? visibleDrives.stream().mapToInt(ManagedDrive::cellCount).sum() : clientActiveSlots;
    }

    public boolean isSlotActive(int slot) {
        return slot >= 0 && slot < activeSlotCount();
    }

    /** Called on the client when a snapshot arrives, so phantom slots stop accepting items. */
    public void onClientSnapshot(DriveSnapshotPayload snapshot) {
        this.clientActiveSlots = snapshot.drives().stream().mapToInt(d -> d.slots().size()).sum();
    }

    // ------------------------------------------------------------------
    // Server-side scrolling / sorting / searching
    // ------------------------------------------------------------------

    public void handleAction(int action, int a, int b, String text) {
        if (!serverSide) {
            return;
        }
        switch (action) {
            case WcmtActionPayload.ACTION_LAYOUT -> {
                int newOffset = a;
                int newRows = Math.max(1, Math.min(MAX_ROWS, b));
                if (newOffset == offset && newRows == rowsBudget) {
                    // Nothing changed: don't trigger another full scan and snapshot.
                    return;
                }
                offset = newOffset;
                rowsBudget = newRows;
            }
            case WcmtActionPayload.ACTION_SORT ->
                sortMode = SortMode.values()[Math.floorMod(a, SortMode.values().length)];
            case WcmtActionPayload.ACTION_SEARCH -> search = text == null ? "" : text;
            default -> {
                return;
            }
        }
        needsSnapshot = true;
    }

    @Override
    public void broadcastChanges() {
        // super first: it ticks the item menu host, which refreshes the access point, range and power.
        super.broadcastChanges();
        if (!serverSide || !isValidMenu()) {
            return;
        }
        tickCounter++;
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
            // NB: IGrid.getMachines(X) keys nodes by their owner's *exact* class, so subclasses and
            // addon hosts that merely implement a cell-host interface would be missed. Collect the
            // owners first: NeoECO groups the blocks of one multiblock into a single drive and needs
            // the whole list to do so.
            List<BlockEntity> owners = new ArrayList<>();
            for (IGridNode node : grid.getNodes()) {
                if (node.getOwner() instanceof BlockEntity blockEntity
                        && !blockEntity.isRemoved()
                        && blockEntity.getLevel() != null
                        && !owners.contains(blockEntity)) {
                    owners.add(blockEntity);
                }
            }
            for (BlockEntity owner : owners) {
                if (owner instanceof DriveBlockEntity drive && drive.getCellCount() > 0) {
                    found.add(new Ae2Drive(drive));
                }
            }
            // Optional addons: NeoECO's drives are on the grid without extending AE2's drive.
            found.addAll(NeoEcoDrive.collect(owners));
        }
        // Per-kind totals describe the whole network, not just the visible window, and are taken
        // before the search filter so they don't change with the current query.
        if (needsTotals || tickCounter % TOTALS_SCAN_TICKS == 0) {
            needsTotals = false;
            summarizeNetwork(found);
        }
        found.sort(comparatorFor(sortMode));
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

        // Scroll in rows, not drives: a window shorter than one drive then still shows that drive's
        // lower rows instead of jumping straight to the next drive.
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
                // Keep looking: a later, smaller host may still fit. Only if even the first host is
                // too large does the window stay empty, which is then reported to the client.
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
        // Nothing fit into the slot pool although drives exist: tell the player instead of showing
        // an empty list.
        windowOverflow = window.isEmpty() && !found.isEmpty();
        visibleDrives = window;
        driveInventory.setDrives(window);
    }


    /**
     * Network-wide totals for the two grooves. The type count comes from the grid's own storage — every
     * cell reachable in the network, addon cells included, which is why NeoECO cells show up here even
     * though their own inventory API does not answer us. Type capacity and the bytes come from the
     * cells the terminal manages (only plain AE2 cells report a type capacity); a cell whose byte
     * counter is a sentinel value is skipped, since it cannot take part in a ratio and would overflow
     * the sum.
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
                    // Only a cell that truly has no capacity limit makes the network unlimited for the
                    // purpose of these two grooves; a cell that merely supplies infinite resources
                    // (an infinite water/cobblestone cell) keeps its ordinary counters.
                    infinite = true;
                }
                // Type capacity counts even for cells whose byte counter is a sentinel value.
                typeTotal += stat.typeCapacity();
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

    private Comparator<ManagedDrive> comparatorFor(SortMode mode) {
        Comparator<ManagedDrive> byPosition = Comparator
                .comparing((ManagedDrive d) -> d.dimension().toString())
                .thenComparing(d -> d.pos().asLong());
        return switch (mode) {
            case POSITION -> byPosition;
            case NAME -> Comparator.comparing((ManagedDrive d) -> d.name().getString())
                    .thenComparing(byPosition);
            case USAGE -> Comparator.comparingDouble(ManagedDrive::fillRatio).reversed()
                    .thenComparing(byPosition);
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
        PacketDistributor.sendToPlayer(serverPlayer,
                new DriveSnapshotPayload(offset, totalRows, skippedRows, blocked(),
                        sortMode.name(), search, linkStatusMessage(), infos,
                        netTotals[0], netTotals[1], netTotals[2], netTotals[3], netInfinite));
    }

    /** Empty while the terminal can reach the network, otherwise the reason it cannot. */
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

    /** The menu host, exposed for AE2WTlib's universal-terminal integration. */
    @Nullable
    public WcmtMenuHost getWcmtHost() {
        return host;
    }
}
