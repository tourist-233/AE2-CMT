package com.wcmt.util;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import appeng.menu.MenuOpener;
import appeng.menu.implementations.PriorityMenu;
import appeng.menu.locator.MenuHostLocator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * 记住谁从我们的终端打开了驱动器的优先级界面，使该界面的返回按钮回到终端，
 * 而不是回到 AE2 的驱动器界面。
 *
 * <p>AE2 的优先级界面会返回到打开它时的 {@code IPriorityHost}——也就是驱动器方块——
 * 因为界面只知道这些。那不是玩家来的地方，因此这里监听该切换并改为重新打开终端。
 *
 * <p>返回只在一次完整的往返里发生：先看到优先级界面本身，随后它被关闭并打开了别的菜单。
 * 若打开的容器不是优先级界面（NeoECO 的优先级界面走 LDLib2，或者 {@code openPriority}
 * 失败而根本没打开界面），记录当即作废，玩家之后打开的箱子、工作台不会被劫持。
 */
public final class PriorityReturn {

    /** 一次待处理的返回：回到哪个终端、何时失效、往返走到了哪一步。 */
    private static final class Pending {
        private final MenuHostLocator locator;
        private final MenuType<?> menuType;
        /** 记录的有效截止时刻，单位为 tick。 */
        private final long expiresAt;
        /** 是否已经看到过优先级界面本身。 */
        private boolean sawPriorityScreen;
        /** 优先级界面关闭的时刻；仍开着时为 -1。 */
        private long closedAt = -1;

        private Pending(MenuHostLocator locator, MenuType<?> menuType, long expiresAt) {
            this.locator = locator;
            this.menuType = menuType;
            this.expiresAt = expiresAt;
        }
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** 记录来源的有效时长，单位为 tick。 */
    private static final long TTL_TICKS = 200;
    /**
     * 优先级界面关闭后，返回流程必须在这个窗口内打开新菜单；超出则视为玩家自己关掉了它，
     * 记录作废，之后的容器不会被替换成终端。
     */
    private static final long RETURN_WINDOW_TICKS = 2;

    /** 在从终端打开驱动器优先级界面之前调用。 */
    public static void remember(ServerPlayer player, MenuType<?> menuType, MenuHostLocator locator) {
        PENDING.put(player.getUUID(), new Pending(locator, menuType,
                player.level().getGameTime() + TTL_TICKS));
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PriorityReturn::onMenuOpened);
        NeoForge.EVENT_BUS.addListener(PriorityReturn::onMenuClosed);
    }

    private static void onMenuOpened(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) {
            return;
        }
        long now = player.level().getGameTime();
        if (now > pending.expiresAt) {
            PENDING.remove(player.getUUID());
            return;
        }
        // 打开优先级界面本身就是预期步骤；等它关闭后再看下一步。
        if (event.getContainer() instanceof PriorityMenu) {
            pending.sawPriorityScreen = true;
            pending.closedAt = -1;
            return;
        }
        // 没见到优先级界面就打开了别的容器：说明打开的不是 AE2 的优先级界面，作废记录，
        // 而不是把玩家接下来打开的容器劫持成终端。
        if (!pending.sawPriorityScreen) {
            PENDING.remove(player.getUUID());
            return;
        }
        // 优先级界面刚关掉又打开了菜单：那是 AE2 把玩家送回驱动器界面，改回终端。
        boolean justClosed = pending.closedAt < 0 || now - pending.closedAt <= RETURN_WINDOW_TICKS;
        PENDING.remove(player.getUUID());
        if (justClosed) {
            player.closeContainer();
            MenuOpener.open(pending.menuType, player, pending.locator);
        }
    }

    private static void onMenuClosed(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Pending pending = PENDING.get(player.getUUID());
        if (pending != null && pending.sawPriorityScreen
                && event.getContainer() instanceof PriorityMenu) {
            pending.closedAt = player.level().getGameTime();
        }
    }

    private PriorityReturn() {
    }
}
