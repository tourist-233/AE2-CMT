package com.wcmt.menu;

import java.util.function.BiConsumer;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.menu.ISubMenu;
import appeng.menu.locator.ItemMenuHostLocator;
import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import de.mari_023.ae2wtlib.api.terminal.WTMenuHost;
import net.minecraft.world.entity.player.Player;

/**
 * Menu host of the terminal. Extends AE2WTlib's {@link WTMenuHost} so the terminal can be part of
 * AE2WTlib's universal terminal (quantum bridge cards, terminal switching, ...).
 */
public class WcmtMenuHost extends WTMenuHost {

    public WcmtMenuHost(ItemWT item, Player player, ItemMenuHostLocator locator,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(item, player, locator, returnToMainMenu);
    }

    /**
     * The grid reached through the currently connected wireless access point, or null when the
     * terminal is unlinked / out of range / out of power.
     */
    @Nullable
    public IGrid getConnectedGrid() {
        IGridNode node = getActionableNode();
        return node != null ? node.getGrid() : null;
    }
}
