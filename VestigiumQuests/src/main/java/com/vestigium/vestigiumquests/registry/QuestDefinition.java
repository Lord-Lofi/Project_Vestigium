package com.vestigium.vestigiumquests.registry;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

public record QuestDefinition(
        String id,
        String title,
        String description,
        String faction,
        QuestType type,
        String target,
        int count,
        int minOmen,
        int maxOmen,
        String season,
        boolean repeatable,
        String prerequisite,
        QuestRewards rewards
) {
    public static QuestDefinition fromConfig(YamlConfiguration cfg) {
        QuestType type;
        try {
            type = QuestType.valueOf(cfg.getString("type", "KILL").toUpperCase());
        } catch (IllegalArgumentException e) {
            type = QuestType.KILL;
        }

        QuestRewards rewards = new QuestRewards(
                cfg.getInt("rewards.reputation", 0),
                cfg.getInt("rewards.omen_delta", 0),
                cfg.getString("rewards.lore_fragment", ""),
                cfg.getStringList("rewards.items")
        );

        return new QuestDefinition(
                cfg.getString("id", "unknown"),
                cfg.getString("title", "Untitled Quest"),
                cfg.getString("description", ""),
                cfg.getString("faction", "none"),
                type,
                cfg.getString("target", ""),
                cfg.getInt("count", 1),
                cfg.getInt("min_omen", 0),
                cfg.getInt("max_omen", 9999),
                cfg.getString("season", ""),
                cfg.getBoolean("repeatable", false),
                cfg.getString("prerequisite", ""),
                rewards
        );
    }

    public record QuestRewards(int reputation, int omenDelta, String loreFragment, List<String> items) {}
}
