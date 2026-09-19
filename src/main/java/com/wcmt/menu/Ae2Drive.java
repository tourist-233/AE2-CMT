package com.wcmt.menu;

import appeng.blockentity.storage.DriveBlockEntity;
import appeng.me.cells.BasicCellInventory;
import com.wcmt.network.DriveSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;

/** {@link ManagedDrive} view of an AE2 drive (or an addon subclass of it, e.g. an extended drive). */
final class Ae2Drive implements ManagedDrive {

    private final DriveBlockEntity drive;

    Ae2Drive(DriveBlockEntity drive) {
        this.drive = drive;
    }

    @Override
    public Component name() {
        if (drive instanceof Nameable nameable && nameable.hasCustomName()) {
            return nameable.getCustomName();
        }
        return drive.getBlockState().getBlock().getName();
    }

    @Override
    public ItemStack icon() {
        return drive.getBlockState().getBlock().asItem().getDefaultInstance();
    }

    @Override
    public boolean online() {
        return drive.isPowered();
    }

    @Override
    public BlockPos pos() {
        return drive.getBlockPos();
    }

    @Override
    public ResourceLocation dimension() {
        return drive.getLevel() != null
                ? drive.getLevel().dimension().location()
                : ResourceLocation.withDefaultNamespace("overworld");
    }

    @Override
    public int cellCount() {
        return drive.getCellCount();
    }

    @Override
    public ItemStack cell(int slot) {
        return drive.getInternalInventory().getStackInSlot(slot);
    }

    @Override
    public boolean acceptsCell(int slot, ItemStack stack) {
        return !stack.isEmpty() && drive.getInternalInventory().isItemValid(slot, stack);
    }

    @Override
    public boolean canExtractCell(int slot) {
        return true;
    }

    @Override
    public void setCell(int slot, ItemStack stack) {
        // A drive cell slot holds exactly one cell, whatever the caller hands us.
        drive.getInternalInventory().setItemDirect(slot,
                stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
    }

    @Override
    public DriveSnapshotPayload.SlotStat stat(int slot) {
        var cell = drive.getOriginalCellInventory(slot);
        if (cell == null) {
            return DriveSnapshotPayload.SlotStat.absent();
        }
        long used = 0;
        long total = 0;
        long typeCapacity = 0;
        if (cell instanceof BasicCellInventory basic) {
            used = basic.getUsedBytes();
            total = basic.getTotalBytes();
            typeCapacity = basic.getTotalItemTypes();
        }
        return new DriveSnapshotPayload.SlotStat(used, total,
                ManagedDrive.isInfinite(total) || ManagedDrive.isInfiniteItem(cell(slot)),
                ManagedDrive.isInfinite(total), typeCapacity, (byte) drive.getCellStatus(slot).ordinal());
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
}
