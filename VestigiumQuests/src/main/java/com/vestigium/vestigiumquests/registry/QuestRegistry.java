package com.vestigium.vestigiumquests.registry;

import com.vestigium.vestigiumquests.VestigiumQuests;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Loads quest definitions from plugins/VestigiumQuests/quests/*.yml.
 *
 * Quest YAML structure:
 *   id:          string
 *   title:       string
 *   description: string
 *   faction:     string (faction key or "none")
 *   type:        KILL | COLLECT | EXPLORE | DELIVER | SURVIVE | LORE
 *   target:      string (mob type, item type, structure id, fragment id, etc.)
 *   count:       int
 *   min_omen:    int (0 = no gate)
 *   max_omen:    int (9999 = no gate)
 *   season:      string (null = any)
 *   repeatable:  boolean
 *   rewards:
 *     reputation: int (faction rep change)
 *     omen_delta: int (positive = add, negative = subtract)
 *     lore_fragment: string
 *     items: list<string>  (Material names)
 */
public class QuestRegistry {

    private final VestigiumQuests plugin;
    private final Map<String, QuestDefinition> quests = new LinkedHashMap<>();

    public QuestRegistry(VestigiumQuests plugin) {
        this.plugin = plugin;
    }

    public void load() {
        quests.clear();
        File dir = new File(plugin.getDataFolder(), "quests");
        if (!dir.exists()) {
            dir.mkdirs();
            saveDefaults(dir);
        }

        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;

        for (File f : files) {
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                QuestDefinition def = QuestDefinition.fromConfig(cfg);
                quests.put(def.id(), def);
            } catch (Exception e) {
                plugin.getLogger().warning("[QuestRegistry] Failed to load " + f.getName() + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("[QuestRegistry] Loaded " + quests.size() + " quests.");
    }

    public Optional<QuestDefinition> getById(String id) {
        return Optional.ofNullable(quests.get(id));
    }

    public List<QuestDefinition> getByFaction(String factionKey) {
        return quests.values().stream()
                .filter(q -> factionKey.equalsIgnoreCase(q.faction()))
                .toList();
    }

    public Collection<QuestDefinition> getAll() {
        return Collections.unmodifiableCollection(quests.values());
    }

    // -------------------------------------------------------------------------

    private void saveDefaults(File dir) {
        record DefaultQuest(String id, String title, String faction, String type,
                             String target, int count, String description) {}

        List<DefaultQuest> defaults = List.of(
            new DefaultQuest("bandit_bounty_1", "Clear the Crossroads", "bandits",
                    "KILL", "ZOMBIE", 10, "Drive back the bandits who have been raiding the crossroads."),
            new DefaultQuest("villager_supply_1", "The Missing Shipment", "villagers",
                    "COLLECT", "WHEAT", 32, "Recover lost grain from the abandoned farmstead."),
            new DefaultQuest("explorer_ruin_1", "First Foray", "none",
                    "EXPLORE", "cartographer_waystone_1", 1, "Find the first waystone."),
            new DefaultQuest("cultist_trial_1", "The First Rite", "cultists",
                    "SURVIVE", "omen_400", 1, "Survive a night when the omen exceeds 400."),
            new DefaultQuest("lore_fragment_hunt", "The Archivist's Request", "none",
                    "LORE", "cartographer_act1_complete", 1,
                    "Collect the first act lore fragments for the archivist.")
        );

        for (DefaultQuest dq : defaults) {
            File f = new File(dir, dq.id() + ".yml");
            if (f.exists()) continue;
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("id", dq.id());
            cfg.set("title", dq.title());
            cfg.set("description", dq.description());
            cfg.set("faction", dq.faction());
            cfg.set("type", dq.type());
            cfg.set("target", dq.target());
            cfg.set("count", dq.count());
            cfg.set("min_omen", 0);
            cfg.set("max_omen", 9999);
            cfg.set("season", "");
            cfg.set("repeatable", false);
            cfg.set("rewards.reputation", 50);
            cfg.set("rewards.omen_delta", -5);
            cfg.set("rewards.lore_fragment", "");
            cfg.set("rewards.items", List.of());
            try { cfg.save(f); } catch (Exception e) { /* best-effort */ }
        }
    }
}
