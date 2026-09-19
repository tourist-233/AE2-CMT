package com.wcmt.init;

import com.wcmt.menu.WcmtMenu;
import com.wcmt.menu.WcmtMenuHost;
import com.wcmt.part.WcmtTerminalPart;

import appeng.menu.implementations.MenuTypeBuilder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {
    /**
     * Built with AE2's builder, which registers it in the AE2 namespace ({@code ae2:cmt}) and
     * therefore loads its style from {@code assets/ae2/screens/cmt.json}.
     */
    public static final MenuType<WcmtMenu> WCMT_MENU_TYPE = MenuTypeBuilder
            .create((int id, Inventory playerInventory, WcmtMenuHost host) -> new WcmtMenu(id, playerInventory, host),
                    WcmtMenuHost.class)
            .build("cmt");

    /**
     * Same menu class, but hosted by a cable-mounted part: AE2 hands the part itself to the menu, so
     * the host class here is the part.
     */
    public static final MenuType<WcmtMenu> WCMT_PART_MENU_TYPE = MenuTypeBuilder
            .create((int id, Inventory playerInventory, WcmtTerminalPart part) -> new WcmtMenu(id, playerInventory, part),
                    WcmtTerminalPart.class)
            .build("cmt_part");

    /** Forces class initialization so the menu type is queued before AE2's registry event. */
    public static void init() {
    }

    private ModMenus() {
    }
}
