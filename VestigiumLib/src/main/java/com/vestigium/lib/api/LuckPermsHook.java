package com.vestigium.lib.api;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Soft-dependency wrapper around the LuckPerms API.
 *
 * Use LuckPermsHook.isAvailable() before constructing.
 * Exposed via VestigiumLib.getLuckPermsHook() — returns null if LP is absent.
 *
 * Useful for:
 *   - Reading a player's primary group / prefix for display
 *   - Programmatic permission grants (e.g. reputation milestones)
 *   - Direct permission checks that bypass Bukkit's cache
 */
public class LuckPermsHook {

    private final LuckPerms lp;

    public LuckPermsHook(Plugin plugin) {
        this.lp = LuckPermsProvider.get();
        plugin.getLogger().info("[LuckPermsHook] LuckPerms integration active.");
    }

    public static boolean isAvailable(Plugin plugin) {
        var reg = plugin.getServer().getPluginManager().getPlugin("LuckPerms");
        return reg != null && reg.isEnabled();
    }

    // -------------------------------------------------------------------------

    /** Returns the player's primary LuckPerms group name. */
    public String getPrimaryGroup(Player player) {
        return lp.getPlayerAdapter(Player.class).getUser(player).getPrimaryGroup();
    }

    /** Returns the player's cached display prefix, or an empty string. */
    public String getPrefix(Player player) {
        String prefix = lp.getPlayerAdapter(Player.class).getUser(player)
                .getCachedData().getMetaData().getPrefix();
        return prefix != null ? prefix : "";
    }

    /**
     * Checks a permission directly via LuckPerms (bypasses Bukkit's attachment cache).
     * Prefer this over Player.hasPermission() when you need certainty.
     */
    public boolean hasPermission(Player player, String permission) {
        return lp.getPlayerAdapter(Player.class).getUser(player)
                .getCachedData().getPermissionData()
                .checkPermission(permission).asBoolean();
    }

    /**
     * Persistently grants a permission node to a player.
     * Async — change is saved to LP's storage and takes effect on next context refresh.
     *
     * Intended use: reputation milestone rewards, quest completion unlocks.
     */
    public void grantPermission(UUID uuid, String permission) {
        lp.getUserManager().modifyUser(uuid,
                user -> user.data().add(Node.builder(permission).build()));
    }

    /**
     * Persistently revokes a permission node from a player.
     */
    public void revokePermission(UUID uuid, String permission) {
        lp.getUserManager().modifyUser(uuid,
                user -> user.data().remove(Node.builder(permission).build()));
    }
}
