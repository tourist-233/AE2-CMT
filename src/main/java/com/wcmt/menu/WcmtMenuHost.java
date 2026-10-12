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
 * 终端的菜单宿主。继承 AE2WTlib 的 {@link WTMenuHost}，使终端可成为
 * AE2WTlib 通用终端的一部分（量子桥接卡、终端切换等）。
 */
public class WcmtMenuHost extends WTMenuHost {

    public WcmtMenuHost(ItemWT item, Player player, ItemMenuHostLocator locator,
            BiConsumer<Player, ISubMenu> returnToMainMenu) {
        super(item, player, locator, returnToMainMenu);
    }

    /**
     * 通过当前连接的无线访问点可达的网络；终端未连接 / 超出范围 /
     * 电量耗尽时为 null。
     */
    @Nullable
    public IGrid getConnectedGrid() {
        IGridNode node = getActionableNode();
        return node != null ? node.getGrid() : null;
    }
}
