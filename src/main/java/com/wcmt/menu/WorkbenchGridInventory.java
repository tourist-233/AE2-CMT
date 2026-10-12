package com.wcmt.menu;

import appeng.api.inventories.BaseInternalInventory;
import appeng.util.ConfigMenuInventory;
import net.minecraft.world.item.ItemStack;

/**
 * 分区网格作为菜单使用的 inventory。
 *
 * <p>网格本身是工作卡自身的配置（见 {@link WcmtMenu#workbenchGrid()}）；这个包装类只添加了
 * 「该槽位当前是否可编辑」的规则，规则与 AE2 的元件工作台一致：选中元件时网格始终可编辑，
 * 未选中时仅在复制模式为 {@code KEEP_ON_REMOVE} 时可编辑。存下的网格不受该规则影响。
 */
public class WorkbenchGridInventory extends BaseInternalInventory {

    private final WcmtMenu menu;

    public WorkbenchGridInventory(WcmtMenu menu) {
        this.menu = menu;
    }

    @Override
    public int size() {
        return WcmtMenu.CONFIG_SLOTS;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return menu.workbenchGrid().getStackInSlot(slot);
    }

    @Override
    public void setItemDirect(int slot, ItemStack stack) {
        menu.workbenchGrid().setItemDirect(slot, stack);
        // 网格变了，下一次广播要把它写回终端。
        menu.markWorkbenchDirty();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return menu.isPartitionEditable(slot) && menu.workbenchGrid().isItemValid(slot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return menu.workbenchGrid().getSlotLimit(slot);
    }
}
