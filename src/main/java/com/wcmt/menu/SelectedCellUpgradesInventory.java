package com.wcmt.menu;

import org.jetbrains.annotations.Nullable;

import appeng.api.inventories.BaseInternalInventory;
import appeng.api.upgrades.IUpgradeInventory;
import net.minecraft.world.item.ItemStack;

/**
 * 工作台区域旁显示的升级卡槽位，由所选元件自身的升级卡 inventory 支撑。
 *
 * <p>AE2 返回的升级卡 inventory 绑定在创建它的物品堆上。从驱动器读取的元件只是一份副本，
 * 所以每次写入还必须推回驱动器，否则卡只会落在副本上并随即消失。
 */
public class SelectedCellUpgradesInventory extends BaseInternalInventory {

    private final WcmtMenu menu;

    public SelectedCellUpgradesInventory(WcmtMenu menu) {
        this.menu = menu;
    }

    @Nullable
    private IUpgradeInventory delegate() {
        return menu.selectedCellUpgrades();
    }

    @Override
    public int size() {
        IUpgradeInventory upgrades = delegate();
        return upgrades == null ? 0 : upgrades.size();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        IUpgradeInventory upgrades = delegate();
        if (upgrades == null || slot < 0 || slot >= upgrades.size()) {
            return ItemStack.EMPTY;
        }
        return upgrades.getStackInSlot(slot);
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        IUpgradeInventory upgrades = delegate();
        if (upgrades == null || slot < 0 || slot >= upgrades.size()) {
            return;
        }
        upgrades.setItemDirect(slot, stack);
        menu.writeBackSelectedCell();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        IUpgradeInventory upgrades = delegate();
        return upgrades != null && slot >= 0 && slot < upgrades.size()
                && upgrades.isItemValid(slot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        IUpgradeInventory upgrades = delegate();
        return upgrades == null ? 1 : upgrades.getSlotLimit(slot);
    }
}
