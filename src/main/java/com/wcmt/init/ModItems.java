package com.wcmt.init;

import com.wcmt.WcmtMod;
import com.wcmt.item.WcmtTerminalItem;
import com.wcmt.part.WcmtTerminalPart;

import appeng.items.parts.PartItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WcmtMod.MOD_ID);

    /** 手持无线终端；也是 AE2WTlib 注册为无线终端的那个物品。 */
    public static final DeferredItem<WcmtTerminalItem> WCMT_TERMINAL = ITEMS.registerItem(
            "wireless_component_management_terminal", props -> new WcmtTerminalItem());

    /**
     * 线缆终端：同样的管理器，装在线上而非拿在手中。AE2 的
     * {@link PartItem} 负责放置它并创建部件；无需注册方块或方块实体。
     */
    public static final DeferredItem<PartItem<WcmtTerminalPart>> COMPONENT_MANAGEMENT_TERMINAL = ITEMS
            .registerItem("component_management_terminal",
                    props -> new PartItem<>(props, WcmtTerminalPart.class, WcmtTerminalPart::new));

    /**
     * 只被无线终端升级槽位接受的卡。它刻意没有注册为 AE2 升级卡，
     * 因此其它机器的升级槽位不会接受它。
     */
    public static final DeferredItem<Item> COMPONENT_WORK_CARD = ITEMS
            .registerItem("component_work_card",
                    props -> new com.wcmt.item.ComponentWorkCardItem(props));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    private ModItems() {
    }
}
