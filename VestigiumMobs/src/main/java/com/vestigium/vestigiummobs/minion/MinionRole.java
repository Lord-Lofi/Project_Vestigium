package com.vestigium.vestigiummobs.minion;

import org.bukkit.entity.EntityType;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The six player-summonable minion roles.
 */
public enum MinionRole {
    SHADE    ("§8Shade",          EntityType.ZOMBIE),
    GOLEM    ("§7Sentinel Golem", EntityType.IRON_GOLEM),
    WISP     ("§eGuiding Wisp",   EntityType.ALLAY),
    HEXBLADE ("§dHexblade",       EntityType.STRAY),
    HARVESTER("§6Harvester",      EntityType.DROWNED),
    SCOUT    ("§bScout",          EntityType.ZOMBIE);

    private final String displayName;
    private final EntityType entityType;

    MinionRole(String displayName, EntityType entityType) {
        this.displayName = displayName;
        this.entityType  = entityType;
    }

    public String     displayName() { return displayName; }
    public EntityType entityType()  { return entityType;  }

    public static MinionRole fromString(String s) {
        for (MinionRole r : values()) {
            if (r.name().equalsIgnoreCase(s)) return r;
        }
        return null;
    }

    public static List<String> names() {
        return Arrays.stream(values())
                .map(r -> r.name().toLowerCase())
                .collect(Collectors.toList());
    }
}
