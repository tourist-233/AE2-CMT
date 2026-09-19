package com.wcmt.item;

import org.jetbrains.annotations.Nullable;

import com.wcmt.init.ModMenus;
import com.wcmt.menu.WcmtMenuHost;
import com.wcmt.util.WcmtPermissions;

import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.UpgradeInventories;
import appeng.api.upgrades.Upgrades;
import appeng.menu.MenuOpener;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import com.wcmt.menu.WcmtMenu;
import de.mari_023.ae2wtlib.api.terminal.ItemWT;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Wireless terminal that lists and manages the storage cells installed in every ME Drive of the ME
 * network it is linked to.
 *
 * <p>Extends AE2WTlib's {@link ItemWT} so it can be registered as one of its wireless terminals and
 * therefore also be reachable from AE2WTlib's universal terminal.
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
     * AE2's wireless terminals hard-code two upgrade slots; this one offers four. The callback mirrors
     * the parent's: it keeps the energy-buffer multiplier in sync with the installed energy cards.
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
            if (prepareOpen(player, stack) && MenuOpener.open(getMenuType(), player, locator)) {
                return InteractionResultHolder.sidedSuccess(stack, false);
            }
        }
        return new InteractionResultHolder<>(InteractionResult.FAIL, stack);
    }

    @Override
    protected boolean openFromInventory(Player player, ItemMenuHostLocator locator, boolean returningFromSubmenu) {
        ItemStack stack = locator.locateItem(player);
        if (stack.isEmpty() || !prepareOpen(player, stack)) {
            return false;
        }
        return MenuOpener.open(getMenuType(), player, locator, returningFromSubmenu);
    }

    /**
     * Gate opening on permission only. AE2's link status is not a gate: the terminal opens while
     * unlinked, out of range or out of power too, and the screen then says why it cannot be used.
     */
    private boolean prepareOpen(Player player, ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != this) {
            return false;
        }
        if (!WcmtPermissions.canUse(player, stack)) {
            player.displayClientMessage(Component.translatable("gui.cmt.no_permission"), true);
            return false;
        }
        WcmtPermissions.assignOwnerIfAbsent(player, stack);
        return true;
    }
}
