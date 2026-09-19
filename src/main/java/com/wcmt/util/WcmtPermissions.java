package com.wcmt.util;

import java.util.UUID;

import com.wcmt.config.WcmtConfig;
import com.wcmt.init.ModComponents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;

public final class WcmtPermissions {

    /** Enforces the configured permission. An unclaimed terminal may be claimed by anyone. */
    public static boolean canUse(Player player, ItemStack terminal) {
        UUID owner = terminal.get(ModComponents.OWNER.get());
        if (owner == null || owner.equals(player.getUUID())) {
            return true;
        }
        return switch (WcmtConfig.PERMISSION.get()) {
            case ALL -> true;
            case TEAM -> sameTeam(player, owner);
            case SELF -> false;
        };
    }

    /** Binds the terminal to the player if it has no owner yet. */
    public static void assignOwnerIfAbsent(Player player, ItemStack terminal) {
        if (terminal.get(ModComponents.OWNER.get()) == null) {
            terminal.set(ModComponents.OWNER.get(), player.getUUID());
        }
    }

    private static boolean sameTeam(Player player, UUID owner) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        var scoreboard = server.getScoreboard();
        PlayerTeam ownerTeam = scoreboard.getPlayersTeam(owner.toString());
        PlayerTeam playerTeam = scoreboard.getPlayersTeam(player.getUUID().toString());
        return ownerTeam != null && ownerTeam == playerTeam;
    }

    private WcmtPermissions() {
    }
}
