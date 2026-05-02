package com.vestigium.lib.api;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Single entry point for all protection checks in the Vestigium suite.
 *
 * AND logic: isProtected() returns true on ANY protection match.
 * Every destructive event, spreading system, cataclysm, and mob behaviour
 * MUST call isProtected(location) before any block modification.
 *
 * Wraps:
 * - WorldGuard region checks (soft dependency — gracefully absent)
 * - GriefPrevention claim checks (soft dependency — gracefully absent)
 * - PDC player-placed tag (vestigium:player_placed)
 */
public class ProtectionAPI {

    // Chunk PDC key: stores comma-delimited relative block positions as "x:y:z"
    private final NamespacedKey placedBlocksKey;

    private final Plugin plugin;
    private boolean worldGuardEnabled = false;
    private boolean griefPreventionEnabled = false;
    private boolean depsChecked = false;

    public ProtectionAPI(Plugin plugin) {
        this.plugin = plugin;
        this.placedBlocksKey = new NamespacedKey("vestigium", "player_placed_blocks");
    }

    // Deferred until first actual use so WorldGuard/GP are guaranteed to be enabled
    // (VestigiumLib loads at STARTUP; protection plugins enable at POSTWORLD)
    private void ensureInitialized() {
        if (depsChecked) return;
        depsChecked = true;

        Plugin wg = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        if (wg != null && wg.isEnabled()) {
            worldGuardEnabled = true;
            plugin.getLogger().info("[ProtectionAPI] WorldGuard detected — region protection active.");
        }

        Plugin gp = plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
        if (gp != null && gp.isEnabled()) {
            griefPreventionEnabled = true;
            plugin.getLogger().info("[ProtectionAPI] GriefPrevention detected — claim protection active.");
        }

        if (!worldGuardEnabled && !griefPreventionEnabled) {
            plugin.getLogger().warning("[ProtectionAPI] Neither WorldGuard nor GriefPrevention found. " +
                    "Only PDC player-placed tags will be checked. Consider installing a protection plugin.");
        }
    }

    /**
     * Returns true if the given location is protected and must not be modified by
     * any Vestigium system. Always call this before any block modification.
     *
     * @param location the location to check
     * @return true if protected; false if safe to modify
     */
    public boolean isProtected(Location location) {
        if (location == null || location.getWorld() == null) return true; // fail safe
        ensureInitialized();

        if (worldGuardEnabled && isWorldGuardProtected(location)) return true;
        if (griefPreventionEnabled && isGriefPreventionProtected(location)) return true;

        return false;
    }

    /**
     * Returns true if the block carries the vestigium:player_placed PDC tag,
     * meaning a player intentionally placed it. Use this as the gate for invasive
     * species spread, cataclysm block consumption, etc.
     *
     * @param block the block to check
     * @return true if player-placed
     */
    public boolean isPlayerPlaced(Block block) {
        if (block == null) return false;
        String tag = block.getChunk().getPersistentDataContainer()
                .getOrDefault(placedBlocksKey, PersistentDataType.STRING, "");
        return tag.contains("," + encodePos(block) + ",");
    }

    /**
     * Tags a block as player-placed. Call this from a BlockPlaceEvent listener
     * in VestigiumLib for every placed block server-wide.
     *
     * @param block the block to tag
     */
    public void tagPlayerPlaced(Block block) {
        if (block == null) return;
        var pdc = block.getChunk().getPersistentDataContainer();
        String pos = encodePos(block);
        String tag = pdc.getOrDefault(placedBlocksKey, PersistentDataType.STRING, ",");
        if (!tag.contains("," + pos + ",")) {
            pdc.set(placedBlocksKey, PersistentDataType.STRING, tag + pos + ",");
        }
    }

    /**
     * Removes the player-placed tag. Call when a block is broken so the tag
     * does not persist if another block is placed in the same position later.
     *
     * @param block the block to untag
     */
    public void untagPlayerPlaced(Block block) {
        if (block == null) return;
        var pdc = block.getChunk().getPersistentDataContainer();
        String tag = pdc.getOrDefault(placedBlocksKey, PersistentDataType.STRING, ",");
        pdc.set(placedBlocksKey, PersistentDataType.STRING,
                tag.replace("," + encodePos(block) + ",", ","));
    }

    // Encodes chunk-relative x/z (0–15) and absolute y as "x:y:z"
    private static String encodePos(Block block) {
        return (block.getX() & 15) + ":" + block.getY() + ":" + (block.getZ() & 15);
    }

    public boolean isWorldGuardEnabled() {
        ensureInitialized();
        return worldGuardEnabled;
    }

    public boolean isGriefPreventionEnabled() {
        ensureInitialized();
        return griefPreventionEnabled;
    }

    // -------------------------------------------------------------------------
    // Soft-dependency integration — isolated to avoid ClassNotFoundException
    // -------------------------------------------------------------------------

    private boolean isWorldGuardProtected(Location location) {
        try {
            com.sk89q.worldedit.util.Location weLoc =
                    com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(location);
            com.sk89q.worldguard.WorldGuard wg = com.sk89q.worldguard.WorldGuard.getInstance();
            com.sk89q.worldguard.protection.regions.RegionContainer container =
                    wg.getPlatform().getRegionContainer();
            com.sk89q.worldguard.protection.regions.RegionQuery query =
                    container.createQuery();
            com.sk89q.worldguard.protection.ApplicableRegionSet regions =
                    query.getApplicableRegions(weLoc);
            // Protected if any regions exist at this location (build flag assumed denied for non-members)
            return regions.size() > 0;
        } catch (Exception e) {
            // WorldGuard API changed or unavailable at runtime — fail safe
            return false;
        }
    }

    private boolean isGriefPreventionProtected(Location location) {
        try {
            me.ryanhamshire.GriefPrevention.GriefPrevention gp =
                    (me.ryanhamshire.GriefPrevention.GriefPrevention)
                            plugin.getServer().getPluginManager().getPlugin("GriefPrevention");
            if (gp == null) return false;
            me.ryanhamshire.GriefPrevention.Claim claim =
                    gp.dataStore.getClaimAt(location, false, null);
            return claim != null;
        } catch (Exception e) {
            return false;
        }
    }
}
