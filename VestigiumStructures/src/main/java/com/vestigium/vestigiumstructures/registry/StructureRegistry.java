package com.vestigium.vestigiumstructures.registry;

import com.vestigium.vestigiumstructures.VestigiumStructures;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Loads and provides access to structure definitions from
 * plugins/VestigiumStructures/structures/*.yml.
 *
 * Each YAML file defines one structure:
 *   id:           string (matches filename without extension)
 *   type:         RUIN | DUNGEON | CITY | OUTPOST | TEMPLE | WAYSTONE | ANCIENT_CITY_VARIANT
 *   biomes:       list<string> (allowed biome names, empty = any)
 *   min_y:        int
 *   max_y:        int
 *   lore_id:      string (structure_id used by LoreRegistry)
 *   npc_types:    list<string> (NPC types that may spawn here)
 *   warden_type:  string (optional named warden type)
 *   rarity:       int 1-100 (lower = rarer; used by spawner as weight)
 *   wandering:    boolean (if true, WanderingDungeonManager may migrate it)
 */
public class StructureRegistry {

    private final VestigiumStructures plugin;
    private final Map<String, StructureDefinition> definitions = new LinkedHashMap<>();

    public StructureRegistry(VestigiumStructures plugin) {
        this.plugin = plugin;
    }

    public void load() {
        definitions.clear();
        File dir = new File(plugin.getDataFolder(), "structures");
        if (!dir.exists()) dir.mkdirs();
        saveDefaults(dir); // always seeds any missing default files

        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File f : files) {
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                StructureDefinition def = StructureDefinition.fromConfig(cfg, f.getName());
                definitions.put(def.id(), def);
            } catch (Exception e) {
                plugin.getLogger().warning("[StructureRegistry] Failed to load " + f.getName() + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("[StructureRegistry] Loaded " + definitions.size() + " structure definitions.");
    }

    public Optional<StructureDefinition> getById(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public Collection<StructureDefinition> getAll() {
        return Collections.unmodifiableCollection(definitions.values());
    }

    public List<StructureDefinition> getWandering() {
        return definitions.values().stream()
                .filter(StructureDefinition::wandering)
                .toList();
    }

    public List<StructureDefinition> getByType(StructureType type) {
        return definitions.values().stream()
                .filter(d -> d.type() == type)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Default structure seed files
    // -------------------------------------------------------------------------

    private void saveDefaults(File dir) {
        // Each entry: id, type, biomes (csv or empty), min_y, max_y, npc_types (csv), warden, rarity, wandering
        Object[][] defs = {
            // ── Cartographer chain (original 5) ──────────────────────────────
            { "cartographer_waystone_1",  "WAYSTONE",          "",                                     60,  180, "",                          "",                  10, false },
            { "cartographer_terminus",    "WAYSTONE",          "",                                     60,  200, "cartographer",              "",                  5,  false },
            { "ancient_guardian_chamber", "DUNGEON",           "",                                    -64,   40, "",                          "ancient_guardian",  8,  false },
            { "antecedent_vault",         "ANCIENT_CITY_VARIANT","",                                  -64,  -10, "antecedent_archivist",      "",                  6,  false },
            { "deep_archive_alpha",       "DUNGEON",           "",                                    -64,  -30, "resonant_archivist",        "",                  5,  false },
            // ── Overworld surface structures ──────────────────────────────────
            { "sunken_library",           "RUIN",              "SWAMP,MANGROVE_SWAMP,RIVER",          -30,   40, "",                          "",                  8,  false },
            { "collapsed_watchtower",     "RUIN",              "",                                     60,  200, "",                          "",                  30, false },
            { "plagued_village",          "RUIN",              "PLAINS,SUNFLOWER_PLAINS,MEADOW",       60,  120, "",                          "",                  15, false },
            { "sorcerers_tower",          "TEMPLE",            "FOREST,BIRCH_FOREST,DARK_FOREST",      60,  200, "exiled_mage",               "",                  12, false },
            { "titan_graveyard",          "ANCIENT_CITY_VARIANT","",                                   55,  160, "",                          "golem_titan",       5,  false },
            { "bandit_camp",              "OUTPOST",           "PLAINS,FOREST,SAVANNA",                60,  150, "bandit,bandit",             "",                  25, false },
            { "mercenary_post",           "OUTPOST",           "PLAINS,FOREST",                        60,  150, "mercenary_recruiter,mercenary","",               15, false },
            { "cult_outpost",             "TEMPLE",            "FOREST,DARK_FOREST",                   60,  150, "",                          "cult_herald",       10, false },
            { "hermit_settlement",        "RUIN",              "FOREST,TAIGA,OLD_GROWTH_PINE_TAIGA",   60,  150, "hermit",                    "",                  20, false },
            { "frozen_outpost",           "OUTPOST",           "SNOWY_PLAINS,FROZEN_RIVER,ICE_SPIKES,SNOWY_SLOPES", 60, 180, "",             "",                  15, false },
            { "inverted_tower",           "TEMPLE",            "",                                     40,  140, "",                          "",                  5,  false },
            { "sky_island",               "RUIN",              "",                                    120,  220, "",                          "",                  8,  false },
            { "the_undermarket",          "DUNGEON",           "",                                    -60,   20, "black_market,ore_broker",   "",                  6,  false },
            { "probability_shrine",       "TEMPLE",            "",                                     60,  160, "",                          "",                  10, false },
            { "depth_anomaly_zone",       "ANCIENT_CITY_VARIANT","",                                  -64,    0, "",                          "",                  5,  false },
            { "cartographers_cache",      "WAYSTONE",          "",                                     60,  180, "cartographer",              "",                  12, false },
            { "ancient_golem_workshop",   "RUIN",              "PLAINS,SAVANNA,DESERT",                60,  150, "",                          "iron_colossus",     8,  false },
            { "alchemists_ruin",          "RUIN",              "SWAMP,MANGROVE_SWAMP",                 60,  120, "",                          "",                  10, false },
            // ── Jungle structures ─────────────────────────────────────────────
            { "descent_marker",           "RUIN",              "JUNGLE,BAMBOO_JUNGLE,SPARSE_JUNGLE",   60,  100, "",                          "",                  15, false },
            { "overgrown_laboratory",     "DUNGEON",           "JUNGLE,BAMBOO_JUNGLE",                 50,  110, "",                          "",                  10, false },
            { "the_unfinished_temple",    "TEMPLE",            "JUNGLE,BAMBOO_JUNGLE,SPARSE_JUNGLE",   60,  120, "",                          "jungle_guardian",   8,  false },
            // ── Underground structures ────────────────────────────────────────
            { "resonant_archive",         "DUNGEON",           "",                                    -64,  -30, "resonant_archivist",        "",                  5,  false },
            { "antecedent_chamber",       "DUNGEON",           "",                                    -64,    0, "antecedent_archivist",      "",                  8,  false },
            // ── Nether structures ─────────────────────────────────────────────
            { "the_breach_point",         "TEMPLE",            "NETHER_WASTES",                        30,  100, "breach_sentinel",           "",                  5,  true  },
            { "expedition_camp_alpha",    "OUTPOST",           "SOUL_SAND_VALLEY",                     30,  100, "hermit",                    "",                  12, false },
            { "expedition_camp_beta",     "OUTPOST",           "SOUL_SAND_VALLEY",                     30,  100, "hermit",                    "",                  12, false },
            { "translation_chamber",      "DUNGEON",           "NETHER_WASTES,SOUL_SAND_VALLEY",       30,  100, "piglin_translator",         "",                  8,  false },
            { "piglin_shrine",            "TEMPLE",            "BASALT_DELTAS,CRIMSON_FOREST",         30,  100, "",                          "piglin_warlord",    10, false },
        };

        for (Object[] d : defs) {
            String id = (String) d[0];
            File f = new File(dir, id + ".yml");
            if (f.exists()) continue;

            String biomesCsv = (String) d[2];
            List<String> biomes = biomesCsv.isBlank()
                    ? List.of()
                    : List.of(biomesCsv.split(","));

            String npcCsv = (String) d[5];
            List<String> npcs = npcCsv.isBlank()
                    ? List.of()
                    : List.of(npcCsv.split(","));

            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("id",          id);
            cfg.set("type",        d[1]);
            cfg.set("biomes",      biomes);
            cfg.set("min_y",       d[3]);
            cfg.set("max_y",       d[4]);
            cfg.set("lore_id",     id);
            cfg.set("npc_types",   npcs);
            cfg.set("warden_type", d[6]);
            cfg.set("rarity",      d[7]);
            cfg.set("wandering",   d[8]);
            try { cfg.save(f); } catch (Exception ignored) {}
        }
    }
}
