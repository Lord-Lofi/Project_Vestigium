package com.vestigium.vestigiumquests.dispatch;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.PlayerReputationChangeEvent;
import com.vestigium.vestigiumquests.VestigiumQuests;
import com.vestigium.vestigiumquests.registry.QuestDefinition;
import com.vestigium.vestigiumquests.registry.QuestRegistry;
import com.vestigium.vestigiumquests.tracker.QuestTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Faction quest dispensing — two pathways:
 *
 * 1. Villager NPC interaction: right-clicking an NPC tagged with vestigium:npc_type
 *    dispatches a random available quest for the NPC's faction.
 *
 * 2. Reputation threshold dispatch: when a player's faction rep crosses a multiple
 *    of 100 (upward), a random quest for that faction is offered automatically.
 *    Listens to PlayerReputationChangeEvent via EventBus.
 *
 * Quest assignment is gated by QuestTracker.assignQuest() — omen, completion, etc.
 */
public class FactionQuestDispenser implements Listener {

    private static final org.bukkit.NamespacedKey NPC_TYPE_KEY =
            new org.bukkit.NamespacedKey("vestigium", "npc_type");

    private final VestigiumQuests plugin;
    private final QuestRegistry questRegistry;
    private final QuestTracker questTracker;

    public FactionQuestDispenser(VestigiumQuests plugin, QuestRegistry questRegistry,
                                  QuestTracker questTracker) {
        this.plugin = plugin;
        this.questRegistry = questRegistry;
        this.questTracker = questTracker;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Reputation threshold dispatch via EventBus
        VestigiumLib.getEventBus().subscribe(PlayerReputationChangeEvent.class, event -> {
            int prev = event.getPreviousReputation();
            int next = event.getNewReputation();
            // Crossed upward through a multiple of 100
            if (next > prev && (next / 100) > (prev / 100)) {
                Player player = plugin.getServer().getPlayer(event.getPlayerUUID());
                if (player != null) {
                    offerFactionQuest(player, event.getFaction().getKey());
                }
            }
        });

        plugin.getLogger().info("[FactionQuestDispenser] Initialized.");
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onNPCInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof org.bukkit.entity.Villager villager)) return;
        Player player = event.getPlayer();

        String npcType = villager.getPersistentDataContainer()
                .get(NPC_TYPE_KEY, PersistentDataType.STRING);
        if (npcType == null) return;

        String factionKey = npcTypeToFaction(npcType);
        if (factionKey == null) return;

        boolean assigned = offerFactionQuest(player, factionKey);
        if (!assigned) {
            player.sendMessage("§7The " + formatNpcType(npcType)
                    + " has no new work for you right now.");
        }
    }

    // -------------------------------------------------------------------------

    private boolean offerFactionQuest(Player player, String factionKey) {
        List<QuestDefinition> available = questRegistry.getByFaction(factionKey).stream()
                .filter(q -> !questTracker.isActive(player, q.id()))
                .filter(q -> q.repeatable() || !questTracker.isComplete(player, q.id()))
                .toList();

        if (available.isEmpty()) return false;

        QuestDefinition chosen = available.get(
                ThreadLocalRandom.current().nextInt(available.size()));
        return questTracker.assignQuest(player, chosen.id());
    }

    private static String npcTypeToFaction(String npcType) {
        if (npcType == null) return null;
        return switch (npcType.toLowerCase()) {
            case "merchant", "guard", "elder", "farmer" -> "villagers";
            case "bandit_chief", "raider"               -> "bandits";
            case "mercenary_captain", "sellsword"       -> "mercenaries";
            case "cult_acolyte", "dark_priest"          -> "cultists";
            case "drowned_speaker"                      -> "drowned";
            case "archivist", "scholar", "delver"       -> null; // generic/no faction
            default -> null;
        };
    }

    private static String formatNpcType(String npcType) {
        return npcType.replace("_", " ");
    }
}
