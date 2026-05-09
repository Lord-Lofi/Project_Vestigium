package com.vestigium.vestigiumplayer.achievement;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.function.Predicate;

public record AchievementDefinition(
        String key,
        String displayName,
        String description,
        Material rewardMaterial,
        String rewardName,
        Predicate<Player> condition
) {}
