package com.wcmt.item;

import org.jetbrains.annotations.Nullable;

import com.wcmt.init.ModMenus;
import com.wcmt.menu.WcmtMenuHost;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.upgrades.Upgrades;
import appeng.menu.MenuOpener;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import com.wcmt.menu.WcmtMenu;
import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 无线终端，用于列出并管理它所链接的 ME 网络中每个 ME 驱动器内安装的存储元件。
 *
 * <p>继承 AE2WTlib 的 {@link ItemWT}，因此可以注册为它的无线终端之一，
 * 从而也能从 AE2WTlib 的通用终端打开。
 */
public class WcmtTerminalItem extends ItemWT {

    public WcmtTerminalItem() {
        super();
    }

    @Override
    public MenuType<?> getMenuType(ItemMenuHostLocator locator, Player player) {
        return ModMenus.WCMT_MENU_TYPE;
    }

    @Override
    public MenuType<?> getMenuType() {
        return ModMenus.WCMT_MENU_TYPE;
    }

    /**
     * AE2 的无线终端硬编码为两个升级槽位；本终端提供四个。回调与父类一致：
     * 让能量缓冲倍率与已安装的能量卡保持同步。
     */
    @Override
    public IUpgradeInventory getUpgrades(ItemStack stack) {
        return UpgradeInventories.forItem(stack, WcmtMenu.UPGRADE_SLOTS,
                (changedStack, inventory) -> setAEMaxPowerMultiplier(changedStack,
                        1 + Upgrades.getEnergyCardMultiplier(inventory)));
    }

    @Nullable
    @Override
    public WcmtMenuHost getMenuHost(Player player, ItemMenuHostLocator locator,
            @Nullable BlockHitResult hitResult) {
        return new WcmtMenuHost(this, player, locator, (p, subMenu) -> openFromInventory(p, locator, true));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide()) {
            ItemMenuHostLocator locator = MenuLocators.forHand(player, hand);
            if (prepareOpen(stack) && MenuOpener.open(getMenuType(), player, locator)) {
                return InteractionResultHolder.sidedSuccess(stack, false);
            }
        }
        return new InteractionResultHolder<>(InteractionResult.FAIL, stack);
    }

    @Override
    protected boolean openFromInventory(Player player, ItemMenuHostLocator locator, boolean returningFromSubmenu) {
        ItemStack stack = locator.locateItem(player);
        if (stack.isEmpty() || !prepareOpen(stack)) {
            return false;
        }
        return MenuOpener.open(getMenuType(), player, locator, returningFromSubmenu);
    }

    /**
     * 以物品确实是本终端作为打开的前提。AE2 的链接状态不是前提：
     * 未链接、超出范围或电力不足时终端也会打开，随后界面会说明它为何不可用。
     */
    private boolean prepareOpen(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == this;
    }
}
