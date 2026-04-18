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
        if (!dir.exists()) {
            dir.mkdirs();
            saveDefaults(dir);
        }

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
        String[] defaults = {
            "cartographer_waystone_1", "cartographer_terminus", "ancient_guardian_chamber",
            "antecedent_vault", "deep_archive_alpha"
        };
        for (String id : defaults) {
            File f = new File(dir, id + ".yml");
            if (!f.exists()) {
                YamlConfiguration cfg = new YamlConfiguration();
                cfg.set("id", id);
                cfg.set("type", "WAYSTONE");
                cfg.set("biomes", List.of());
                cfg.set("min_y", -64);
                cfg.set("max_y", 320);
                cfg.set("lore_id", id);
                cfg.set("npc_types", List.of());
                cfg.set("warden_type", "");
                cfg.set("rarity", 10);
                cfg.set("wandering", false);
                try { cfg.save(f); } catch (Exception e) { /* best-effort */ }
            }
        }
    }
}
