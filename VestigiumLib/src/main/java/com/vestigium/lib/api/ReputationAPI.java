package com.vestigium.lib.api;

import com.vestigium.lib.event.PlayerReputationChangeEvent;
import com.vestigium.lib.model.Faction;
import com.vestigium.lib.util.Keys;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Per-player, per-faction reputation. Range -1000 to +1000.
 *
 * Storage: vestigium:reputation_{faction} PDC tag on each Player entity.
 *
 * Cross-faction side effects:
 * - High CULTISTS rep (+400 or above) automatically penalises VILLAGERS rep.
 * - These are applied silently and fire their own PlayerReputationChangeEvent.
 */
public class ReputationAPI {

    public static final int MAX_REP = 1000;
    public static final int MIN_REP = -1000;

    // CULTISTS threshold that triggers VILLAGERS penalty
    private static final int CULTIST_PENALTY_THRESHOLD = 400;
    // VILLAGERS rep reduction applied per CULTISTS rep point above threshold (scaled)
    private static final double CULTIST_PENALTY_RATIO = 0.25;

    private final Plugin plugin;
    private final EventBus eventBus;

    public ReputationAPI(Plugin plugin, EventBus eventBus) {
        this.plugin = plugin;
        this.eventBus = eventBus;
    }

    /**
     * Returns a player's current reputation with a faction.
     * Returns 0 if the player has no stored reputation yet.
     *
     * @param playerUUID the player's UUID
     * @param faction    the faction to query
     * @return reputation value in range [-1000, 1000]
     */
    public int getReputation(UUID playerUUID, Faction faction) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) {
            // Player is offline — attempt offline PDC read via OfflinePlayer is not supported.
            // Callers must ensure the player is online, or use the snapshot PDC key.
            plugin.getLogger().warning("[ReputationAPI] getReputation called for offline player " + playerUUID);
            return 0;
        }
        return player.getPersistentDataContainer()
                .getOrDefault(Keys.reputationKey(faction), PersistentDataType.INTEGER, 0);
    }

    /**
     * Modifies a player's reputation with a faction by the given delta.
     * Clamps to [-1000, 1000], fires PlayerReputationChangeEvent, and applies
     * any cross-faction side effects.
     *
     * @param playerUUID the player's UUID
     * @param faction    the faction whose rep is changing
     * @param delta      amount to add (negative to subtract)
     */
    public void modifyReputation(UUID playerUUID, Faction faction, int delta) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) {
            plugin.getLogger().warning("[ReputationAPI] modifyReputation called for offline player " + playerUUID);
            return;
        }

        int previous = getReputation(playerUUID, faction);
        int updated = clamp(previous + delta);

        player.getPersistentDataContainer()
                .set(Keys.reputationKey(faction), PersistentDataType.INTEGER, updated);

        eventBus.fire(new PlayerReputationChangeEvent(playerUUID, faction, previous, updated));

        applyCrossFactionEffects(player, playerUUID, faction, updated);
    }

    /**
     * Directly sets a player's reputation with a faction.
     * Fires PlayerReputationChangeEvent.
     */
    public void setReputation(UUID playerUUID, Faction faction, int value) {
        Player player = plugin.getServer().getPlayer(playerUUID);
        if (player == null) return;

        int previous = getReputation(playerUUID, faction);
        int updated = clamp(value);

        player.getPersistentDataContainer()
                .set(Keys.reputationKey(faction), PersistentDataType.INTEGER, updated);

        eventBus.fire(new PlayerReputationChangeEvent(playerUUID, faction, previous, updated));
        applyCrossFactionEffects(player, playerUUID, faction, updated);
    }

    // -------------------------------------------------------------------------

    private void applyCrossFactionEffects(Player player, UUID playerUUID, Faction changedFaction, int newValue) {
        if (changedFaction != Faction.CULTISTS) return;

        // High Cultists rep penalises Villagers rep
        if (newValue >= CULTIST_PENALTY_THRESHOLD) {
            int excess = newValue - CULTIST_PENALTY_THRESHOLD;
            int villagerPenalty = (int) Math.round(excess * CULTIST_PENALTY_RATIO);
            if (villagerPenalty > 0) {
                int prevVillager = getReputation(playerUUID, Faction.VILLAGERS);
                int updatedVillager = clamp(prevVillager - villagerPenalty);
                player.getPersistentDataContainer()
                        .set(Keys.reputationKey(Faction.VILLAGERS), PersistentDataType.INTEGER, updatedVillager);
                eventBus.fire(new PlayerReputationChangeEvent(
                        playerUUID, Faction.VILLAGERS, prevVillager, updatedVillager));
            }
        }
    }

    private int clamp(int value) {
        return Math.max(MIN_REP, Math.min(MAX_REP, value));
    }
}
