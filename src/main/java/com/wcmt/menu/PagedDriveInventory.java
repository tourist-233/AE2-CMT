package com.wcmt.menu;

import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.inventories.BaseInternalInventory;
import net.minecraft.world.item.ItemStack;

/**
 * 终端驱动器元件槽位的底层 inventory。
 *
 * <p>槽位池是连续的：每个可见驱动器占用 {@code cellCount()} 个连续槽位，因此元件数不同的
 * 驱动器（原版 10、扩展 20、NeoECO 宿主的 1 等）都以相同方式处理。
 *
 * <p>服务端槽位池通过 {@link ManagedDrive} 委托给真实驱动器；客户端仅保存由原版容器同步的
 * 物品堆。
 */
public class PagedDriveInventory extends BaseInternalInventory {

    private final boolean server;
    private final WcmtMenu menu;
    private final ItemStack[] mirror;
    private List<ManagedDrive> drives = List.of();
    /**
     * 槽位到「驱动器下标 / 驱动器内位置」的映射，随 {@link #setDrives} 重建。
     * 容器每 tick 会问遍所有槽位，逐个线性查驱动器会在驱动器一多时被放大。
     */
    private final int[] slotDrive = new int[WcmtMenu.MAX_SLOTS];
    private final int[] slotLocal = new int[WcmtMenu.MAX_SLOTS];

    public PagedDriveInventory(boolean server, WcmtMenu menu) {
        this.server = server;
        this.menu = menu;
        this.mirror = new ItemStack[WcmtMenu.MAX_SLOTS];
        Arrays.fill(this.mirror, ItemStack.EMPTY);
        Arrays.fill(this.slotDrive, -1);
    }

    public void setDrives(List<ManagedDrive> drives) {
        this.drives = drives;
        Arrays.fill(slotDrive, -1);
        int slot = 0;
        for (int k = 0; k < drives.size() && slot < slotDrive.length; k++) {
            int cells = drives.get(k).cellCount();
            for (int local = 0; local < cells && slot < slotDrive.length; local++, slot++) {
                slotDrive[slot] = k;
                slotLocal[slot] = local;
            }
        }
    }

    @Nullable
    private ManagedDrive driveFor(int slot) {
        if (slot < 0 || slot >= slotDrive.length) {
            return null;
        }
        int k = slotDrive[slot];
        return k >= 0 && k < drives.size() ? drives.get(k) : null;
    }

    private int localSlot(int slot) {
        return slot >= 0 && slot < slotLocal.length ? slotLocal[slot] : -1;
    }

    @Override
    public int size() {
        return WcmtMenu.MAX_SLOTS;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < 0 || slot >= WcmtMenu.MAX_SLOTS) {
            return ItemStack.EMPTY;
        }
        if (!server) {
            return mirror[slot];
        }
        ManagedDrive drive = driveFor(slot);
        if (drive == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = drive.cell(localSlot(slot));
        return stack == null ? ItemStack.EMPTY : stack;
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        if (slot < 0 || slot >= WcmtMenu.MAX_SLOTS) {
            return;
        }
        if (!server) {
            mirror[slot] = stack;
            return;
        }
        ManagedDrive drive = driveFor(slot);
        if (drive != null) {
            // 元件槽位只保存一个元件，不要相信传入的数量。
            drive.setCell(localSlot(slot), stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        if (!server) {
            // 客户端预测：只有当前有对应驱动器的槽位才接受物品。
            return menu.isSlotActive(slot);
        }
        if (!menu.canInsert()) {
            return false;
        }
        // 没有对应驱动器（在当前窗口之外）的槽位绝不能接受物品，否则 setItemDirect
        // 会静默丢弃它们。
        ManagedDrive drive = driveFor(slot);
        return drive != null && drive.acceptsCell(localSlot(slot), stack);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!server || !isItemValid(slot, stack)) {
            return stack;
        }
        ManagedDrive drive = driveFor(slot);
        if (drive == null) {
            return stack;
        }
        int local = localSlot(slot);
        if (!drive.cell(local).isEmpty()) {
            return stack;
        }
        if (!simulate) {
            drive.setCell(local, stack.copyWithCount(1));
        }
        ItemStack remainder = stack.copy();
        remainder.shrink(1);
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (!server || amount <= 0 || !menu.canExtract()) {
            return ItemStack.EMPTY;
        }
        ManagedDrive drive = driveFor(slot);
        if (drive == null) {
            return ItemStack.EMPTY;
        }
        int local = localSlot(slot);
        if (!drive.canExtractCell(local)) {
            return ItemStack.EMPTY;
        }
        ItemStack current = drive.cell(local);
        if (current == null || current.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = current.copyWithCount(Math.min(1, current.getCount()));
        if (!simulate) {
            drive.setCell(local, ItemStack.EMPTY);
        }
        return taken;
    }
}
