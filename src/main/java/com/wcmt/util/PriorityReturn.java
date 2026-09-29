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
 * Remembers who opened a drive's priority screen from our terminal, so the screen's back button
 * returns to the terminal rather than to AE2's drive screen.
 *
 * <p>AE2's priority screen goes back to whatever {@code IPriorityHost} it was opened for — the drive
 * block — because that is all the screen knows about. That is not where the player came from, so the
 * transition is watched for and the terminal reopened instead.
 */
public final class PriorityReturn {

    private record Pending(MenuHostLocator locator, MenuType<?> menuType, long expiresAt) {
    }

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** How long a remembered origin stays valid, in ticks. */
    private static final long TTL_TICKS = 200;

    /** Called right before a drive's priority screen is opened from the terminal. */
    public static void remember(ServerPlayer player, MenuType<?> menuType, MenuHostLocator locator) {
        PENDING.put(player.getUUID(), new Pending(locator, menuType,
                player.level().getGameTime() + TTL_TICKS));
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PriorityReturn::onMenuOpened);
    }

    private static void onMenuOpened(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Pending pending = PENDING.get(player.getUUID());
        if (pending == null) {
            return;
        }
        if (player.level().getGameTime() > pending.expiresAt()) {
            PENDING.remove(player.getUUID());
            return;
        }
        // Opening the priority screen itself is the expected step; any other menu means the player is
        // on the way back through the drive's own screen, so send them to the terminal instead.
        if (event.getContainer() instanceof PriorityMenu) {
            return;
        }
        PENDING.remove(player.getUUID());
        player.closeContainer();
        MenuOpener.open(pending.menuType(), player, pending.locator());
    }

    private PriorityReturn() {
    }
}
