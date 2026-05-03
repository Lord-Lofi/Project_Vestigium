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

    private record DefaultQuest(String id, String title, String faction, String type,
                                String target, int count, String description,
                                int minOmen, int maxOmen, String season,
                                boolean repeatable, int reputation, int omenDelta,
                                String loreFragment, List<String> items, String prerequisite) {
        DefaultQuest(String id, String title, String faction, String type,
                     String target, int count, String description) {
            this(id, title, faction, type, target, count, description,
                 0, 9999, "", false, 50, -5, "", List.of(), "");
        }
    }

    private void saveDefaults(File dir) {
        List<DefaultQuest> defaults = List.of(
            // ---- VILLAGERS ----
            new DefaultQuest("villager_supply_1", "The Missing Shipment", "villagers",
                "COLLECT", "WHEAT", 32,
                "Recover lost grain from the abandoned farmstead north of the settlement."),
            new DefaultQuest("villager_defend_1", "Hold the Line", "villagers",
                "KILL", "ZOMBIE", 20,
                "The undead have been testing the village walls. Put them down before dawn."),
            new DefaultQuest("villager_trade_route", "Reopen the Road", "villagers",
                "KILL", "SKELETON", 15,
                "Skeletons have occupied the old trade road. Clear them out so commerce can resume."),

            // ---- BANDITS ----
            new DefaultQuest("bandit_bounty_1", "Clear the Crossroads", "bandits",
                "KILL", "ZOMBIE", 10,
                "Drive back the undead at the crossroads. The bandits need it clear for business."),
            new DefaultQuest("bandit_supply_run", "Powder Acquisition", "bandits",
                "COLLECT", "GUNPOWDER", 12,
                "The outfit needs explosive stock. Acquire it from whatever source you can."),
            new DefaultQuest("bandit_vault_access", "What's in the Vault", "bandits",
                "EXPLORE", "antecedent_vault", 1,
                "The Antecedent Vault has been sealed for generations. Find a way inside and report what you see."),

            // ---- MERCENARIES ----
            new DefaultQuest("merc_contract_1", "Road Clearance", "mercenaries",
                "KILL", "SKELETON", 25,
                "A merchant caravan needs the eastern road cleared. Standard contract rates."),
            new DefaultQuest("merc_warden_hunt", "The Named One", "mercenaries",
                "KILL", "WARDEN", 1,
                "A named Warden has been sighted near the deep archive. Confirmation of kill required."),
            new DefaultQuest("merc_waystone_escort", "Safe Passage", "mercenaries",
                "EXPLORE", "cartographer_waystone_1", 1,
                "Escort the surveyor to the first waystone. Return with proof of arrival."),
            // Mercenary contract chain
            new DefaultQuest("merc_chain_scout", "Advance Reconnaissance", "mercenaries",
                "EXPLORE", "cartographer_waystone_1", 1,
                "Scout the first waystone and confirm the route is clear. Client is paying hazard rates.",
                0, 9999, "", false, 60, 0, "", List.of(), ""),
            new DefaultQuest("merc_chain_clear", "Area Denial", "mercenaries",
                "KILL", "ZOMBIE", 25,
                "The area around the waystone needs clearing. Client extended the contract. Kill 25.",
                0, 9999, "", false, 100, -5, "", List.of(), "merc_chain_scout"),
            new DefaultQuest("merc_chain_final", "Hold Until Dawn", "mercenaries",
                "SURVIVE", "omen_400", 1,
                "Hold the position through a high-omen night. Standard rates, exceptional circumstances. You agreed to this.",
                0, 9999, "", false, 175, 0, "", List.of(), "merc_chain_clear"),

            // ---- CULTISTS ----
            new DefaultQuest("cultist_trial_1", "The First Rite", "cultists",
                "SURVIVE", "omen_400", 1,
                "Survive a night when the omen exceeds 400. The Cultists say the omen speaks to those who endure it."),
            new DefaultQuest("cultist_offering", "Shards of the Deep", "cultists",
                "COLLECT", "ECHO_SHARD", 4,
                "The ritual requires echo shards from the deep dark. Retrieve them without disturbing what listens."),
            new DefaultQuest("cultist_awakening", "The Second Rite", "cultists",
                "SURVIVE", "omen_600", 1,
                "Endure a night when the omen surpasses 600. Most who attempt this do not complete the rite.",
                400, 9999, "", false, 150, 10, "antecedent_vault_main", List.of(), "cultist_trial_1"),
            new DefaultQuest("cultist_third_rite", "The Third Rite", "cultists",
                "COLLECT", "ECHO_SHARD", 8,
                "The Cultists say you are ready. They have been saying this about everyone who reached this point. None of them came back.",
                600, 9999, "", false, 200, 15, "antecedent_vault_main", List.of(), "cultist_awakening"),

            // ---- DROWNED ----
            new DefaultQuest("drowned_relic_1", "From the Deep", "drowned",
                "COLLECT", "NAUTILUS_SHELL", 6,
                "The Drowned want what the sea has already claimed. Recover nautilus shells from the ocean floor."),
            new DefaultQuest("drowned_archive", "The Submerged Record", "drowned",
                "EXPLORE", "deep_archive_alpha", 1,
                "The Drowned believe a record of the second flood is held in the deep archive. Find it."),
            new DefaultQuest("drowned_tribute", "Heart of the Abyss", "drowned",
                "COLLECT", "HEART_OF_THE_SEA", 1,
                "A tribute the Drowned will not explain. They want a heart of the sea. Do not ask why."),
            // Drowned passage chain
            new DefaultQuest("drowned_passage_1", "The High Ground", "drowned",
                "EXPLORE", "cartographer_waystone_1", 1,
                "The Drowned say the waystones were built before the second flood. Find the first. Learn what was worth marking.",
                0, 9999, "", false, 60, 0, "", List.of(), "drowned_relic_1"),
            new DefaultQuest("drowned_passage_2", "Where the Water Stops", "drowned",
                "EXPLORE", "cartographer_terminus", 1,
                "The terminus waystone marks the highest ground before the next flood. The Drowned want confirmation it still stands.",
                0, 9999, "", false, 120, 5, "cartographer_terminus_main", List.of(), "drowned_passage_1"),
            new DefaultQuest("drowned_passage_3", "The Water Remembers", "drowned",
                "SURVIVE", "omen_500", 1,
                "Survive when the omen reaches 500. The Drowned say this is the water remembering. They say this is practice.",
                400, 9999, "", false, 200, 12, "", List.of(), "drowned_passage_2"),

            // ---- END REMNANTS ----
            new DefaultQuest("end_remnant_path", "Follow the Stones", "end_remnants",
                "EXPLORE", "cartographer_terminus", 1,
                "The End Remnants say the Cartographer's final waystone points toward something they recognise. Reach it."),
            new DefaultQuest("end_remnant_vigil", "The Echo Vigil", "end_remnants",
                "SURVIVE", "omen_500", 1,
                "Stand in the End when the omen exceeds 500. The Remnants say the dragon's echo can be heard at that threshold.",
                300, 9999, "", false, 100, 0, "cartographer_terminus_main", List.of(), "end_remnant_path"),

            // ---- CONCLAVE ----
            new DefaultQuest("conclave_fragment_1", "The First Cartographer Fragment", "conclave",
                "LORE", "cartographer", 1,
                "The Conclave wants the first waystone's record transcribed. Find and read the terminal at Waystone Alpha."),
            new DefaultQuest("conclave_guardian_record", "Chamber Survey", "conclave",
                "EXPLORE", "ancient_guardian_chamber", 1,
                "The Conclave's last survey of the Guardian Chamber was interrupted. Complete it and return the record."),
            new DefaultQuest("conclave_full_chain", "The Archivist's Request", "conclave",
                "LORE", "cartographer", 3,
                "Collect three fragments from the Cartographer chain. The Conclave is assembling a complete picture.",
                0, 9999, "", false, 200, -15, "deep_archive_alpha_main", List.of(), "conclave_guardian_record"),

            // ---- NEUTRAL — base quests ----
            new DefaultQuest("explorer_ruin_1", "First Foray", "none",
                "EXPLORE", "cartographer_waystone_1", 1,
                "Find the first waystone. Whoever built them wanted to be found."),
            new DefaultQuest("survivor_blood_moon", "Red Sky at Night", "none",
                "SURVIVE", "omen_400", 1,
                "Survive a blood moon. The sky turns red when the omen reaches 400 on the eighth night."),

            // ---- NEUTRAL — Cartographer's Trail chain ----
            new DefaultQuest("cartographer_trail_2", "End of the Road", "none",
                "EXPLORE", "cartographer_terminus", 1,
                "The first waystone pointed to another. Follow the Cartographer's trail to its end.",
                0, 9999, "", false, 100, 0, "cartographer_terminus_main", List.of(), "explorer_ruin_1"),
            new DefaultQuest("cartographer_trail_3", "The Complete Record", "conclave",
                "LORE", "cartographer", 3,
                "Three of the Cartographer's fragments. The Conclave has waited long enough for a complete picture.",
                0, 9999, "", false, 200, -10, "deep_archive_alpha_main", List.of(), "cartographer_trail_2"),

            // ---- DELIVER quests ----
            new DefaultQuest("villager_grain_delivery", "Grain for the Stores", "villagers",
                "DELIVER", "WHEAT", 16,
                "Bring 16 wheat to a villager. Right-click any NPC to hand it over."),
            new DefaultQuest("cultist_dust_offering", "Dust for the Altar", "cultists",
                "DELIVER", "REDSTONE", 8,
                "The Cultists require 8 redstone as tribute. Deliver it to any cult acolyte."),
            new DefaultQuest("conclave_paper_supply", "Paper for the Archive", "conclave",
                "DELIVER", "PAPER", 12,
                "The Conclave archive is running low. Bring 12 paper to any archivist.",
                0, 9999, "", false, 60, 0, "", List.of(), ""),
            new DefaultQuest("bandit_iron_tribute", "Iron for the Outfit", "bandits",
                "DELIVER", "IRON_INGOT", 6,
                "The bandits want iron for tools and blades. Six ingots. Deliver to any raider.",
                0, 9999, "", false, 50, 0, "", List.of(), ""),
            new DefaultQuest("merc_supply_delivery", "Contractor Supply Run", "mercenaries",
                "DELIVER", "ARROW", 32,
                "A mercenary unit needs arrows before the next contract. Deliver 32 to any sellsword.",
                0, 9999, "", false, 55, 0, "", List.of(), ""),

            // ---- DAILY REPEATABLES ----
            new DefaultQuest("daily_patrol", "Daily Patrol", "none",
                "KILL", "ZOMBIE", 15,
                "Clear 15 undead from the roads. Needs doing every day.",
                0, 9999, "", true, 20, -2, "", List.of(), ""),
            new DefaultQuest("daily_supply", "Daily Supply Run", "none",
                "COLLECT", "WHEAT", 24,
                "Bring 24 wheat to the settlement stores. Daily requirement.",
                0, 9999, "", true, 15, 0, "", List.of(), ""),
            new DefaultQuest("daily_hunt", "Daily Hunt", "none",
                "KILL", "SKELETON", 12,
                "Twelve skeletons, minimum. Keep the roads open.",
                0, 9999, "", true, 20, -2, "", List.of(), ""),

            // ---- SEASONAL — WINTER ----
            new DefaultQuest("seasonal_winter_frost", "The Frost Test", "none",
                "SURVIVE", "omen_300", 1,
                "Winter sharpens the omen. Survive a night when it crosses 300.",
                0, 9999, "WINTER", false, 80, 0, "", List.of(), ""),
            new DefaultQuest("seasonal_winter_archive", "Cold Records", "none",
                "EXPLORE", "deep_archive_alpha", 1,
                "The deep archive thaws slightly in winter. Something in the cold makes the lore more legible. Go in and read it.",
                0, 9999, "WINTER", false, 100, 0, "", List.of(), ""),

            // ---- SEASONAL — SPRING ----
            new DefaultQuest("seasonal_spring_waystone", "First Light", "none",
                "EXPLORE", "cartographer_waystone_1", 1,
                "The waystones wake in spring. The first one is calling. Go before the season passes.",
                0, 9999, "SPRING", false, 60, -3, "", List.of(), ""),
            new DefaultQuest("seasonal_spring_gather", "The Replanting", "none",
                "COLLECT", "OAK_SAPLING", 12,
                "Gather twelve saplings before the growing window closes. The settlement needs replanting after winter.",
                0, 9999, "SPRING", false, 40, 0, "", List.of(), ""),

            // ---- SEASONAL — SUMMER ----
            new DefaultQuest("seasonal_summer_heat", "Thin Places", "none",
                "COLLECT", "BLAZE_ROD", 5,
                "In the height of summer the barrier between here and the Nether grows thin. The blaze rods practically gather themselves.",
                0, 9999, "SUMMER", false, 80, 5, "", List.of(), ""),
            new DefaultQuest("seasonal_summer_endure", "The Long Day", "none",
                "SURVIVE", "omen_350", 1,
                "Summer amplifies everything. The omen included. Survive when it reaches 350.",
                0, 9999, "SUMMER", false, 75, 0, "", List.of(), ""),

            // ---- SEASONAL — AUTUMN ----
            new DefaultQuest("seasonal_autumn_harvest", "Strange Harvest", "none",
                "COLLECT", "PUMPKIN", 8,
                "Autumn turns the fields strange. Harvest eight pumpkins before the first frost takes them.",
                0, 9999, "AUTUMN", false, 50, 0, "", List.of(), ""),
            new DefaultQuest("seasonal_autumn_warden", "Before the Winter", "none",
                "KILL", "WARDEN", 1,
                "In autumn the ancient city grows restless. A Warden must be culled before the deep winter sets in.",
                0, 9999, "AUTUMN", false, 150, -8, "", List.of(), "")
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
            cfg.set("min_omen", dq.minOmen());
            cfg.set("max_omen", dq.maxOmen());
            cfg.set("season", dq.season());
            cfg.set("repeatable", dq.repeatable());
            cfg.set("prerequisite", dq.prerequisite());
            cfg.set("rewards.reputation", dq.reputation());
            cfg.set("rewards.omen_delta", dq.omenDelta());
            cfg.set("rewards.lore_fragment", dq.loreFragment());
            cfg.set("rewards.items", dq.items());
            try { cfg.save(f); } catch (Exception e) { /* best-effort */ }
        }
    }
}
