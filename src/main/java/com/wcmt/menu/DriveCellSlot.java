package com.wcmt.menu;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.AppEngSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** A single storage-cell slot of a remotely managed ME Drive. */
public class DriveCellSlot extends AppEngSlot {

    private final WcmtMenu menu;

    public DriveCellSlot(InternalInventory inventory, int slotIndex, WcmtMenu menu) {
        super(inventory, slotIndex);
        this.menu = menu;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return menu.canInsert() && super.mayPlace(stack);
    }

    @Override
    public boolean mayPickup(Player player) {
        return menu.canExtract() && super.mayPickup(player);
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }
}
