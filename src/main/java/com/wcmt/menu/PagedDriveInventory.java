package com.wcmt.menu;

import java.util.Arrays;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.inventories.BaseInternalInventory;
import net.minecraft.world.item.ItemStack;

/**
 * Backing inventory for the terminal's drive cell slots.
 *
 * <p>The slot pool is contiguous: each visible drive occupies {@code cellCount()} consecutive slots,
 * so drives with a different number of cells (vanilla 10, extended 20, a NeoECO host's 1, ...) are
 * handled the same way.
 *
 * <p>Server side the pool delegates to the actual drives through {@link ManagedDrive}; client side it
 * simply stores the stacks synchronised by the vanilla container.
 */
public class PagedDriveInventory extends BaseInternalInventory {

    private final boolean server;
    private final WcmtMenu menu;
    private final ItemStack[] mirror;
    private List<ManagedDrive> drives = List.of();

    public PagedDriveInventory(boolean server, WcmtMenu menu) {
        this.server = server;
        this.menu = menu;
        this.mirror = new ItemStack[WcmtMenu.MAX_SLOTS];
        Arrays.fill(this.mirror, ItemStack.EMPTY);
    }

    public void setDrives(List<ManagedDrive> drives) {
        this.drives = drives;
    }

    @Nullable
    private ManagedDrive driveFor(int slot) {
        int acc = 0;
        for (ManagedDrive drive : drives) {
            int cells = drive.cellCount();
            if (slot < acc + cells) {
                return drive;
            }
            acc += cells;
        }
        return null;
    }

    private int localSlot(int slot) {
        int acc = 0;
        for (ManagedDrive drive : drives) {
            int cells = drive.cellCount();
            if (slot < acc + cells) {
                return slot - acc;
            }
            acc += cells;
        }
        return -1;
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
            // A cell slot holds exactly one cell; never trust the incoming amount.
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
            // Client-side prediction: only slots that currently have a backing drive accept items.
            return menu.isSlotActive(slot);
        }
        if (!menu.canInsert()) {
            return false;
        }
        // A slot without a backing drive (outside the current window) must never accept items,
        // otherwise setItemDirect silently drops them.
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
