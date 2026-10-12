package com.wcmt.init;

import com.wcmt.menu.WcmtMenu;
import com.wcmt.menu.WcmtMenuHost;
import com.wcmt.part.WcmtTerminalPart;

import appeng.menu.implementations.MenuTypeBuilder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {
    /**
     * 使用 AE2 的 builder 构建，因此会注册在 AE2 命名空间（{@code ae2:cmt}）下，
     * 并从 {@code assets/ae2/screens/cmt.json} 加载样式。
     */
    public static final MenuType<WcmtMenu> WCMT_MENU_TYPE = MenuTypeBuilder
            .create((int id, Inventory playerInventory, WcmtMenuHost host) -> new WcmtMenu(id, playerInventory, host),
                    WcmtMenuHost.class)
            .build("cmt");

    /**
     * 同一个菜单类，但由线缆部件承载：AE2 会把部件本身交给菜单，
     * 所以这里的宿主类是部件。
     */
    public static final MenuType<WcmtMenu> WCMT_PART_MENU_TYPE = MenuTypeBuilder
            .create((int id, Inventory playerInventory, WcmtTerminalPart part) -> new WcmtMenu(id, playerInventory, part),
                    WcmtTerminalPart.class)
            .build("cmt_part");

    /** 强制类初始化，使菜单类型在 AE2 的注册事件之前入队。 */
    public static void init() {
    }

    private ModMenus() {
    }
}
