package com.vestigium.vestigiummagic.spell;

import org.bukkit.configuration.file.YamlConfiguration;

public record SpellDefinition(
        String id,
        String name,
        String castItem,
        int manaCost,
        long cooldownMs,
        SpellEffectType effectType,
        double power,
        double range,
        int omenRequired
) {
    public static SpellDefinition fromConfig(YamlConfiguration cfg) {
        SpellEffectType type;
        try {
            type = SpellEffectType.valueOf(cfg.getString("effect_type", "BOLT").toUpperCase());
        } catch (IllegalArgumentException e) {
            type = SpellEffectType.BOLT;
        }
        return new SpellDefinition(
                cfg.getString("id", "unknown"),
                cfg.getString("name", "Unknown Spell"),
                cfg.getString("cast_item", "ECHO_SHARD"),
                cfg.getInt("mana_cost", 10),
                cfg.getLong("cooldown_ms", 3000),
                type,
                cfg.getDouble("power", 1.0),
                cfg.getDouble("range", 10.0),
                cfg.getInt("omen_required", 0)
        );
    }
}
