package com.wcmt.menu;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import appeng.api.networking.IManagedGridNode;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.wcmt.WcmtMod;
import com.wcmt.network.DriveSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * {@link ManagedDrive} view of one NeoECO LD storage matrix (the "ECO - LD 存储矩阵驱动器" block).
 *
 * <p>A NeoECO storage matrix is a multiblock: several drive blocks share one cluster and every block
 * holds a single cell. One instance of this class therefore represents a whole cluster and exposes
 * one cell slot per drive block. The CD computation drives ("晶阵驱动器") are not storage hosts for
 * us and are ignored.
 *
 * <p>NeoECO is an optional mod and its drives do not extend AE2's {@code DriveBlockEntity}, so
 * everything is done reflectively: the class and method lookups happen once and simply fail to
 * resolve when the mod is missing, leaving the terminal unaffected.
 */
final class NeoEcoDrive implements ManagedDrive {

    private static final String CELL_HOST_CLASS = "cn.dancingsnow.neoecoae.util.ICellHost";
    private static final String STORAGE_DRIVE_CLASS = "cn.dancingsnow.neoecoae.blocks.entity.storage.ECODriveBlockEntity";
    private static final String CELL_REGISTRY_CLASS = "cn.dancingsnow.neoecoae.api.storage.ECOStorageCells";
    private static final String CELL_INTERFACE = "cn.dancingsnow.neoecoae.api.storage.IECOStorageCell";
    private static final String BLOCK_ENTITY_BASE = "cn.dancingsnow.neoecoae.blocks.entity.NEBlockEntity";

    private static final Class<?> storageDriveClass;
    private static final Method getCellStack;
    private static final Method setCellStack;
    private static final Method isItemValid;
    private static final Method canExtractCell;
    private static final Method getCellInventory;
    private static final Method getUsedBytes;
    private static final Method getTotalBytes;
    /** How many distinct types a cell can hold; not every NeoECO version reports it. */
    private static final Method getTotalItemTypes;
    private static final Method getStatus;
    private static final Method getMainNode;
    private static final Method getCluster;
    /** {@code ECODriveBlockEntity#isLockedByInfiniteMode()}: null when that API is missing. */
    private static final Method isLockedByInfiniteMode;

    static {
        Class<?> driveClass = null;
        Method readCell = null;
        Method writeCell = null;
        Method valid = null;
        Method extract = null;
        Method status = null;
        Method cluster = null;
        try {
            driveClass = Class.forName(STORAGE_DRIVE_CLASS);
            Class<?> hostClass = Class.forName(CELL_HOST_CLASS);
            readCell = hostClass.getMethod("getCellStack");
            writeCell = hostClass.getMethod("setCellStack", ItemStack.class);
            valid = hostClass.getMethod("isItemValid", ItemStack.class);
            extract = hostClass.getMethod("canExtractCell");
            status = StorageCell.class.getMethod("getStatus");
            cluster = findMethod(driveClass, "getCluster");
        } catch (ReflectiveOperationException | LinkageError e) {
            // NeoECO is not installed (or changed its API): stay inert, but leave a trace for debugging.
            WcmtMod.LOGGER.debug("NeoECO cell-host bridge inactive: {}", e.toString());
            driveClass = null;
        }
        Method inventory = null;
        Method used = null;
        Method total = null;
        try {
            inventory = Class.forName(CELL_REGISTRY_CLASS)
                    .getMethod("getCellInventory", ItemStack.class, ISaveProvider.class);
            Class<?> cellInterface = Class.forName(CELL_INTERFACE);
            used = cellInterface.getMethod("getUsedBytes");
            total = cellInterface.getMethod("getTotalBytes");
        } catch (ReflectiveOperationException | LinkageError e) {
            // Without the cell registry we can still move cells, just not read their capacity.
            inventory = null;
            used = null;
            total = null;
        }
        Method node = null;
        try {
            node = findMethod(Class.forName(BLOCK_ENTITY_BASE), "getMainNode");
        } catch (ReflectiveOperationException | LinkageError e) {
            node = null;
        }
        Method types = null;
        try {
            types = findMethod(Class.forName(CELL_INTERFACE), "getTotalItemTypes");
        } catch (ReflectiveOperationException | LinkageError e) {
            types = null;
        }
        Method infiniteMode = driveClass == null ? null : findMethod(driveClass, "isLockedByInfiniteMode");
        storageDriveClass = driveClass;
        getCellStack = readCell;
        setCellStack = writeCell;
        isItemValid = valid;
        canExtractCell = extract;
        getCellInventory = inventory;
        getUsedBytes = used;
        getTotalBytes = total;
        getStatus = status;
        getMainNode = node;
        getCluster = cluster;
        isLockedByInfiniteMode = infiniteMode;
        getTotalItemTypes = types;
    }

    /** Drive blocks of one cluster, ordered by position so a cell keeps its slot while scrolling. */
    private final List<BlockEntity> parts;

    private NeoEcoDrive(List<BlockEntity> parts) {
        this.parts = parts;
    }

    /** One managed drive per cluster; owners that are not LD storage matrix blocks are ignored. */
    static List<ManagedDrive> collect(List<BlockEntity> owners) {
        if (storageDriveClass == null) {
            return List.of();
        }
        List<ManagedDrive> result = new ArrayList<>();
        List<Object> seenClusters = new ArrayList<>();
        for (BlockEntity owner : owners) {
            if (!isStorageDrive(owner)) {
                continue;
            }
            Object cluster = getCluster == null ? null : invoke(getCluster, owner);
            if (cluster == null) {
                // Not part of a formed cluster (yet): show it on its own.
                result.add(new NeoEcoDrive(List.of(owner)));
                continue;
            }
            if (seenClusters.contains(cluster)) {
                continue;
            }
            seenClusters.add(cluster);
            List<BlockEntity> parts = drivesOf(cluster);
            result.add(new NeoEcoDrive(parts.isEmpty() ? List.of(owner) : parts));
        }
        return result;
    }

    private static boolean isStorageDrive(Object candidate) {
        return storageDriveClass != null && storageDriveClass.isInstance(candidate);
    }

    private static List<BlockEntity> drivesOf(Object cluster) {
        Method getDrives = findMethod(cluster.getClass(), "getDrives");
        if (getDrives == null || !(invoke(getDrives, cluster) instanceof List<?> list)) {
            return List.of();
        }
        List<BlockEntity> parts = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof BlockEntity blockEntity && !blockEntity.isRemoved()
                    && !parts.contains(blockEntity)) {
                parts.add(blockEntity);
            }
        }
        parts.sort(Comparator.comparingLong(part -> part.getBlockPos().asLong()));
        return parts;
    }

    private static Method findMethod(Class<?> start, String name) {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            } catch (RuntimeException e) {
                // Not accessible after all (module or security manager): give up on this lookup.
                return null;
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Whatever goes wrong (changed signature, missing class, rejected argument) must degrade to
            // "no value" rather than escape into the menu's tick.
            return null;
        }
    }

    private BlockEntity part(int slot) {
        return slot >= 0 && slot < parts.size() ? parts.get(slot) : null;
    }

    @Override
    public Component name() {
        BlockEntity first = parts.get(0);
        if (first instanceof Nameable nameable && nameable.hasCustomName()) {
            return nameable.getCustomName();
        }
        return first.getBlockState().getBlock().getName();
    }

    @Override
    public ItemStack icon() {
        return parts.get(0).getBlockState().getBlock().asItem().getDefaultInstance();
    }

    @Override
    public boolean online() {
        BlockEntity first = parts.get(0);
        if (getMainNode == null) {
            return true;
        }
        return invoke(getMainNode, first) instanceof IManagedGridNode node && node.isOnline();
    }

    @Override
    public BlockPos pos() {
        return parts.get(0).getBlockPos();
    }

    @Override
    public ResourceLocation dimension() {
        var level = parts.get(0).getLevel();
        return level != null
                ? level.dimension().location()
                : ResourceLocation.withDefaultNamespace("overworld");
    }

    @Override
    public int cellCount() {
        return parts.size();
    }

    @Override
    public ItemStack cell(int slot) {
        BlockEntity part = part(slot);
        if (part == null || getCellStack == null) {
            return ItemStack.EMPTY;
        }
        return invoke(getCellStack, part) instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    @Override
    public boolean acceptsCell(int slot, ItemStack stack) {
        BlockEntity part = part(slot);
        if (part == null || stack.isEmpty() || isItemValid == null) {
            return false;
        }
        return Boolean.TRUE.equals(invoke(isItemValid, part, stack));
    }

    @Override
    public boolean canExtractCell(int slot) {
        BlockEntity part = part(slot);
        if (part == null || canExtractCell == null) {
            return false;
        }
        return Boolean.TRUE.equals(invoke(canExtractCell, part));
    }

    @Override
    public void setCell(int slot, ItemStack stack) {
        BlockEntity part = part(slot);
        if (part == null || setCellStack == null) {
            return;
        }
        try {
            // The host expects null (not an empty stack) to clear its cell.
            setCellStack.invoke(part, new Object[] { stack.isEmpty() ? null : stack.copyWithCount(1) });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            WcmtMod.LOGGER.warn("Could not update the cell of a NeoECO storage matrix", e);
        }
    }

    @Override
    public DriveSnapshotPayload.SlotStat stat(int slot) {
        Object inventory = cellInventory(slot);
        if (inventory == null) {
            return DriveSnapshotPayload.SlotStat.absent();
        }
        try {
            long used = ((Number) getUsedBytes.invoke(inventory)).longValue();
            long total = ((Number) getTotalBytes.invoke(inventory)).longValue();
            byte state = getStatus.invoke(inventory) instanceof CellState cellState
                    ? (byte) cellState.ordinal()
                    : (byte) CellState.ABSENT.ordinal();
            long typeCapacity = 0;
            if (getTotalItemTypes != null) {
                Object reported = invoke(getTotalItemTypes, inventory);
                if (reported instanceof Number number) {
                    typeCapacity = number.longValue();
                }
            }
            // Unlimited storage in NeoECO means the cluster runs in infinite mode; a cell that only
            // supplies infinite resources (the infinite material matrix) still shows a purple bar.
            // Only the former is a genuine capacity limit, which the progress grooves react to.
            boolean clusterInfinite = inInfiniteMode(part(slot));
            boolean infinite = clusterInfinite || ManagedDrive.isInfiniteItem(cell(slot));
            return new DriveSnapshotPayload.SlotStat(used, total, infinite, clusterInfinite, typeCapacity,
                    state);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return DriveSnapshotPayload.SlotStat.absent();
        }
    }

    /**
     * True when the cluster this drive belongs to currently runs in NeoECO's infinite mode. The
     * infinite "member" cell item alone does not mean unlimited storage, so it must not be used here.
     */
    private static boolean inInfiniteMode(BlockEntity part) {
        if (part == null || isLockedByInfiniteMode == null) {
            return false;
        }
        return Boolean.TRUE.equals(invoke(isLockedByInfiniteMode, part));
    }

    @Override
    public double fillRatio() {
        long used = 0;
        long total = 0;
        for (int i = 0; i < cellCount(); i++) {
            var stat = stat(i);
            used += stat.used();
            total += stat.total();
        }
        return total > 0 ? (double) used / total : 0;
    }

    /** The cell's {@code IECOStorageCell} (an AE2 {@link StorageCell}), or null. */
    private Object cellInventory(int slot) {
        BlockEntity part = part(slot);
        if (part == null || getCellInventory == null || getUsedBytes == null || getTotalBytes == null
                || getStatus == null) {
            return null;
        }
        ItemStack cell = cell(slot);
        if (cell.isEmpty()) {
            return null;
        }
        try {
            Object saveProvider = part instanceof ISaveProvider provider ? provider : null;
            return getCellInventory.invoke(null, cell, saveProvider);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }
}
