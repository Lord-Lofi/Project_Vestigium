package com.vestigium.vestigiumworld.village;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.PlayerReputationChangeEvent;
import com.vestigium.lib.model.Faction;
import com.vestigium.lib.util.Keys;
import com.vestigium.vestigiumworld.VestigiumWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Manages village expansion events.
 *
 * Expansion gates (all must pass):
 *   1. FactionRegistry health for "villagers" > 80
 *   2. At least one player has VILLAGERS rep >= +300
 *   3. 7-day cooldown since last expansion (vestigium:village_last_expansion PDC on entity)
 *   4. This village has expanded fewer than 2 times total
 *
 * When triggered: fires a VillageExpansionEvent via EventBus so VestigiumStructures
 * can add new building modules. This plugin decides WHEN; Structures decides WHAT.
 *
 * Subscribes to PlayerReputationChangeEvent to re-evaluate gates on rep changes.
 */
public class VillageExpansionManager {

    private static final int  HEALTH_GATE        = 80;
    private static final int  REP_GATE           = 300;
    private static final int  MAX_EXPANSIONS      = 2;
    private static final long COOLDOWN_MILLIS     = 7L * 24 * 60 * 60 * 1000;

    // PDC key for expansion count — stored on the anchor villager entity
    // We reuse VILLAGE_LAST_EXPANSION for timestamp; expansion count is a separate integer
    // stored under a runtime NamespacedKey built from the plugin reference.
    private final VestigiumWorld plugin;

    public VillageExpansionManager(VestigiumWorld plugin) {
        this.plugin = plugin;
    }

    public void init() {
        VestigiumLib.getEventBus().subscribe(PlayerReputationChangeEvent.class,
                this::onReputationChange);
        plugin.getLogger().info("[VillageExpansionManager] Initialized.");
    }

    public void shutdown() {}

    // -------------------------------------------------------------------------

    private void onReputationChange(PlayerReputationChangeEvent event) {
        if (event.getFaction() != Faction.VILLAGERS) return;
        if (event.getNewReputation() < REP_GATE) return;

        // Check if faction health gate is met
        int factionHealth = VestigiumLib.getFactionRegistry().getFactionHealth("villagers");
        if (factionHealth <= HEALTH_GATE) return;

        // Scan for eligible villages (villager entities with expansion PDC)
        UUID playerUUID = event.getPlayerUUID();
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL) continue;
            for (Entity entity : world.getEntitiesByType(EntityType.VILLAGER)) {
                Villager villager = (Villager) entity;
                if (isEligibleForExpansion(villager)) {
                    triggerExpansion(villager, playerUUID);
                    return; // one expansion at a time
                }
            }
        }
    }

    private boolean isEligibleForExpansion(Villager villager) {
        // Must be a village anchor (has our last_expansion PDC key or is a new candidate)
        long lastExpansion = villager.getPersistentDataContainer()
                .getOrDefault(Keys.VILLAGE_LAST_EXPANSION, PersistentDataType.LONG, 0L);

        // Cooldown gate
        if (System.currentTimeMillis() - lastExpansion < COOLDOWN_MILLIS) return false;

        // Max expansion count gate
        org.bukkit.NamespacedKey countKey =
                new org.bukkit.NamespacedKey(plugin, "village_expansion_count");
        int expansionCount = villager.getPersistentDataContainer()
                .getOrDefault(countKey, PersistentDataType.INTEGER, 0);
        return expansionCount < MAX_EXPANSIONS;
    }

    private void triggerExpansion(Villager villager, UUID triggeringPlayer) {
        org.bukkit.NamespacedKey countKey =
                new org.bukkit.NamespacedKey(plugin, "village_expansion_count");

        // Stamp the timestamp and increment expansion count
        villager.getPersistentDataContainer()
                .set(Keys.VILLAGE_LAST_EXPANSION, PersistentDataType.LONG,
                        System.currentTimeMillis());
        int count = villager.getPersistentDataContainer()
                .getOrDefault(countKey, PersistentDataType.INTEGER, 0);
        villager.getPersistentDataContainer()
                .set(countKey, PersistentDataType.INTEGER, count + 1);

        // Fire VillageExpansionEvent so VestigiumStructures can handle the build
        VestigiumLib.getEventBus().fire(
                new VillageExpansionEvent(villager.getLocation(), triggeringPlayer));

        plugin.getLogger().info("[VillageExpansionManager] Village expansion triggered at "
                + villager.getLocation() + " (count=" + (count + 1) + ")");
    }
}
