package com.vestigium.vestigiumquests.reward;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.Faction;
import com.vestigium.vestigiumquests.VestigiumQuests;
import com.vestigium.vestigiumquests.registry.QuestDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Delivers quest completion rewards to a player.
 *
 * Rewards applied (from QuestDefinition.QuestRewards):
 *   - Faction reputation change (if faction != "none")
 *   - Omen delta (positive = add, negative = subtract)
 *   - Lore fragment grant
 *   - Item drops into inventory (overflow drops at feet)
 */
public class QuestRewardManager {

    private final VestigiumQuests plugin;

    public QuestRewardManager(VestigiumQuests plugin) {
        this.plugin = plugin;
    }

    public void grantRewards(Player player, QuestDefinition def) {
        QuestDefinition.QuestRewards rewards = def.rewards();

        // Reputation
        if (rewards.reputation() != 0 && !"none".equalsIgnoreCase(def.faction())) {
            try {
                Faction faction = Faction.valueOf(def.faction().toUpperCase());
                VestigiumLib.getReputationAPI().modifyReputation(
                        player.getUniqueId(), faction, rewards.reputation());
                String direction = rewards.reputation() > 0 ? "§a+" : "§c";
                player.sendMessage("§6[Quest] §7Reputation with §e"
                        + def.faction() + "§7: " + direction + rewards.reputation());
            } catch (IllegalArgumentException ignored) {}
        }

        // Omen delta
        if (rewards.omenDelta() > 0) {
            VestigiumLib.getOmenAPI().addOmen(rewards.omenDelta());
        } else if (rewards.omenDelta() < 0) {
            VestigiumLib.getOmenAPI().subtractOmen(Math.abs(rewards.omenDelta()));
        }

        // Lore fragment
        if (rewards.loreFragment() != null && !rewards.loreFragment().isBlank()) {
            VestigiumLib.getLoreRegistry().grantFragment(
                    player.getUniqueId(), rewards.loreFragment());
            player.sendMessage("§7A lore fragment has been added to your record.");
        }

        // Item rewards
        for (String matName : rewards.items()) {
            try {
                Material mat = Material.valueOf(matName.toUpperCase());
                Map<Integer, ItemStack> overflow =
                        player.getInventory().addItem(new ItemStack(mat));
                overflow.values().forEach(item ->
                        player.getWorld().dropItemNaturally(player.getLocation(), item));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[QuestRewardManager] Unknown material in reward: " + matName);
            }
        }

        player.sendMessage("§6[Quest] §7Rewards delivered.");
    }
}
