package com.wcmt.menu;

import appeng.api.storage.cells.IBasicCellItem;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.me.cells.BasicCellInventory;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.PriorityMenu;
import appeng.menu.locator.MenuLocators;
import com.wcmt.network.DriveSnapshotPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.minecraft.world.item.ItemStack;

/** AE2 驱动器（或其附属子类，例如扩展驱动器）的 {@link ManagedDrive} 视图。 */
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
    public boolean openPriority(ServerPlayer player) {
        // AE2 的驱动器自身就是优先级宿主，因此可直接打开它自己的界面。
        return MenuOpener.open(PriorityMenu.TYPE, player, MenuLocators.forBlockEntity(drive));
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
        // 无论调用方传入什么，驱动器元件槽位都只保存一个元件。
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
        long typeUsed = 0;
        if (cell instanceof BasicCellInventory basic) {
            used = basic.getUsedBytes();
            total = basic.getTotalBytes();
            typeCapacity = basic.getTotalItemTypes();
            typeUsed = basic.getStoredItemTypes();
        } else {
            // 非基础元件：先试各模组自己的读数，读不到再退到 AE2 的公开接口。
            var omni = OmniCellSupport.read(cell(slot));
            if (omni != null) {
                // AE2 Omni Cells 的通用元件没有公开 API，只能反射读它的用量。
                used = omni.used();
                total = omni.total();
                typeCapacity = omni.typeCapacity();
                typeUsed = omni.typeUsed();
            } else {
                // 通用兜底，只用 AE2 的公开接口：存了多少种 key 由 inventory 直接给出，
                // 类型上限则在元件物品实现了 IBasicCellItem 时可读。
                // 字节用量 AE2 没有公开接口可读，因此不填——宁可空着，也不要摆出
                // 「已用 0 / 总量 64k」这种误导读数。
                typeUsed = cell.getAvailableStacks().size();
                ItemStack stack = cell(slot);
                if (stack.getItem() instanceof IBasicCellItem basic) {
                    typeCapacity = basic.getTotalTypes(stack);
                }
            }
        }
        return new DriveSnapshotPayload.SlotStat(used, total,
                ManagedDrive.isInfinite(total) || ManagedDrive.isInfiniteItem(cell(slot)),
                ManagedDrive.isInfinite(total), typeCapacity, typeUsed,
                (byte) drive.getCellStatus(slot).ordinal());
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
