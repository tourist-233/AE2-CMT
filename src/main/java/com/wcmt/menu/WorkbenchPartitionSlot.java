package com.wcmt.menu;

import appeng.api.inventories.InternalInventory;
import appeng.menu.slot.FakeSlot;

/**
 * 工作卡分区网格中的一个槽位。
 *
 * <p>沿用 AE2 元件工作台的分区槽位，但有一处刻意不同：槽位禁用时绝不清空存储的网格。
 * 网格必须挺过置灰状态，而 AE2 的槽位在禁用时会清空自身（这在那边没问题，因为元件离开
 * 工作台本来就会清空配置）。
 *
 * <p>置灰时处于非激活状态，也正是屏幕能画出置灰效果的前提：Minecraft 只给激活的槽位绘制
 * 原版槽位背景，否则置灰的网格会被它盖住。
 */
public class WorkbenchPartitionSlot extends FakeSlot {

    private final WcmtMenu menu;
    private final int slot;

    public WorkbenchPartitionSlot(InternalInventory inv, WcmtMenu menu, int invSlot) {
        super(inv, invSlot);
        this.menu = menu;
        this.slot = invSlot;
    }

    @Override
    public boolean isSlotEnabled() {
        return menu.isPartitionEditable(slot);
    }

    @Override
    public boolean isActive() {
        return isSlotEnabled();
    }
}
