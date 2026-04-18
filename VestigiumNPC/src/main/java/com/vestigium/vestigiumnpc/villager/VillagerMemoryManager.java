package com.vestigium.vestigiumnpc.villager;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumnpc.VestigiumNPC;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Tracks per-player villager memory using PDC on the Villager entity.
 *
 * Memory keys (namespaced to this plugin, stored on the Villager entity):
 *   vm_trades_{playerUUID}   → Integer  trade count with this player
 *   vm_defended_{playerUUID} → Boolean  player has defended this villager
 *   vm_abandoned_{playerUUID}→ Boolean  player has abandoned this villager during a raid
 *   vm_dialogue_{playerUUID} → Integer  dialogue tier (0=stranger, 1=acquaintance, 2=trusted, 3=ally)
 *
 * Dialogue tiers affect pricing and unlock additional dialogue options:
 *   0  stranger     (default)
 *   1  acquaintance (5+ trades)
 *   2  trusted      (20+ trades OR defended once)
 *   3  ally         (50+ trades AND defended AND never abandoned)
 *
 * Price modifier: stranger=1.0x, acquaintance=0.9x, trusted=0.8x, ally=0.7x
 */
public class VillagerMemoryManager implements Listener {

    private static final int TIER_1_TRADES = 5;
    private static final int TIER_2_TRADES = 20;
    private static final int TIER_3_TRADES = 50;

    private final VestigiumNPC plugin;

    public VillagerMemoryManager(VestigiumNPC plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[VillagerMemoryManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the dialogue tier (0-3) this villager has with the given player.
     */
    public int getDialogueTier(Villager villager, UUID playerUUID) {
        return villager.getPersistentDataContainer()
                .getOrDefault(dialogueKey(playerUUID), PersistentDataType.INTEGER, 0);
    }

    /**
     * Returns the price multiplier for trades between this villager and player.
     * Lower is cheaper.
     */
    public double getPriceMultiplier(Villager villager, UUID playerUUID) {
        return switch (getDialogueTier(villager, playerUUID)) {
            case 1  -> 0.9;
            case 2  -> 0.8;
            case 3  -> 0.7;
            default -> 1.0;
        };
    }

    /**
     * Records that a player has defended this villager (e.g. killed an attacker nearby).
     * Recalculates dialogue tier.
     */
    public void recordDefense(Villager villager, UUID playerUUID) {
        villager.getPersistentDataContainer()
                .set(defendedKey(playerUUID), PersistentDataType.BOOLEAN, true);
        recalculateTier(villager, playerUUID);
    }

    /**
     * Records that a player abandoned this villager during a threat event.
     * Caps tier at 1 and removes ally status permanently.
     */
    public void recordAbandonment(Villager villager, UUID playerUUID) {
        villager.getPersistentDataContainer()
                .set(abandonedKey(playerUUID), PersistentDataType.BOOLEAN, true);
        int tier = getDialogueTier(villager, playerUUID);
        if (tier > 1) {
            villager.getPersistentDataContainer()
                    .set(dialogueKey(playerUUID), PersistentDataType.INTEGER, 1);
        }
    }

    // -------------------------------------------------------------------------
    // Event listeners
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) return;
        Player player = event.getPlayer();

        int trades = villager.getPersistentDataContainer()
                .getOrDefault(tradeKey(player.getUniqueId()), PersistentDataType.INTEGER, 0);
        villager.getPersistentDataContainer()
                .set(tradeKey(player.getUniqueId()), PersistentDataType.INTEGER, trades + 1);

        recalculateTier(villager, player.getUniqueId());
        // Notify OmenAPI of player activity
        VestigiumLib.getOmenAPI().markPlayerActivity();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerAttacked(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (!(event.getDamager() instanceof Player)) return;
        // Player attacked a villager — permanent hostility flag
        Player attacker = (Player) event.getDamager();
        villager.getPersistentDataContainer()
                .set(abandonedKey(attacker.getUniqueId()), PersistentDataType.BOOLEAN, true);
        int tier = getDialogueTier(villager, attacker.getUniqueId());
        if (tier > 0) {
            villager.getPersistentDataContainer()
                    .set(dialogueKey(attacker.getUniqueId()), PersistentDataType.INTEGER, 0);
        }
        VestigiumLib.getReputationAPI().modifyReputation(
                attacker.getUniqueId(),
                com.vestigium.lib.model.Faction.VILLAGERS, -25);
    }

    // -------------------------------------------------------------------------

    private void recalculateTier(Villager villager, UUID playerUUID) {
        int trades = villager.getPersistentDataContainer()
                .getOrDefault(tradeKey(playerUUID), PersistentDataType.INTEGER, 0);
        boolean defended = villager.getPersistentDataContainer()
                .getOrDefault(defendedKey(playerUUID), PersistentDataType.BOOLEAN, false);
        boolean abandoned = villager.getPersistentDataContainer()
                .getOrDefault(abandonedKey(playerUUID), PersistentDataType.BOOLEAN, false);

        int tier;
        if (!abandoned && trades >= TIER_3_TRADES && defended) {
            tier = 3;
        } else if (trades >= TIER_2_TRADES || defended) {
            tier = 2;
        } else if (trades >= TIER_1_TRADES) {
            tier = 1;
        } else {
            tier = 0;
        }

        // Never upgrade past 1 if player has abandoned this villager
        if (abandoned) tier = Math.min(tier, 1);

        villager.getPersistentDataContainer()
                .set(dialogueKey(playerUUID), PersistentDataType.INTEGER, tier);
    }

    private NamespacedKey tradeKey(UUID playerUUID) {
        return new NamespacedKey(plugin, "vm_trades_" + playerUUID);
    }

    private NamespacedKey defendedKey(UUID playerUUID) {
        return new NamespacedKey(plugin, "vm_defended_" + playerUUID);
    }

    private NamespacedKey abandonedKey(UUID playerUUID) {
        return new NamespacedKey(plugin, "vm_abandoned_" + playerUUID);
    }

    private NamespacedKey dialogueKey(UUID playerUUID) {
        return new NamespacedKey(plugin, "vm_dialogue_" + playerUUID);
    }
}
