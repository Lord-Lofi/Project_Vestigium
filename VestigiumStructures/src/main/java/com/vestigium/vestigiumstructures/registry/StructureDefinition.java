package com.vestigium.vestigiumstructures.registry;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/**
 * Immutable data class for a loaded structure definition.
 */
public record StructureDefinition(
        String id,
        StructureType type,
        List<String> biomes,
        int minY,
        int maxY,
        String loreId,
        List<String> npcTypes,
        String wardenType,
        int rarity,
        boolean wandering
) {
    public static StructureDefinition fromConfig(YamlConfiguration cfg, String filename) {
        String id = cfg.getString("id", filename.replace(".yml", ""));
        StructureType type;
        try {
            type = StructureType.valueOf(cfg.getString("type", "RUIN").toUpperCase());
        } catch (IllegalArgumentException e) {
            type = StructureType.RUIN;
        }
        return new StructureDefinition(
                id,
                type,
                cfg.getStringList("biomes"),
                cfg.getInt("min_y", -64),
                cfg.getInt("max_y", 320),
                cfg.getString("lore_id", id),
                cfg.getStringList("npc_types"),
                cfg.getString("warden_type", ""),
                cfg.getInt("rarity", 50),
                cfg.getBoolean("wandering", false)
        );
    }
}
