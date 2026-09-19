package com.wcmt.init;

import com.wcmt.WcmtMod;
import com.wcmt.item.WcmtTerminalItem;
import com.wcmt.part.WcmtTerminalPart;

import appeng.items.parts.PartItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WcmtMod.MOD_ID);

    /** The hand-held wireless terminal; also the item AE2WTlib registers as a wireless terminal. */
    public static final DeferredItem<WcmtTerminalItem> WCMT_TERMINAL = ITEMS.registerItem(
            "wireless_component_management_terminal", props -> new WcmtTerminalItem());

    /**
     * The cable-mounted terminal: the same manager, mounted on a cable instead of held in hand. AE2's
     * {@link PartItem} places it and creates the part; no block or block entity has to be registered.
     */
    public static final DeferredItem<PartItem<WcmtTerminalPart>> COMPONENT_MANAGEMENT_TERMINAL = ITEMS
            .registerItem("component_management_terminal",
                    props -> new PartItem<>(props, WcmtTerminalPart.class, WcmtTerminalPart::new));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    private ModItems() {
    }
}
