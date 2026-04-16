package com.vestigium.lib.util;

import com.vestigium.lib.model.Faction;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Central registry for all PDC NamespacedKeys used across the Vestigium suite.
 * Always use these constants — never construct raw NamespacedKey strings in plugin code.
 */
public final class Keys {

    // Block / Chunk keys
    public static NamespacedKey PLAYER_PLACED;
    public static NamespacedKey ROAD_WALK_COUNT;
    public static NamespacedKey CHUNK_HEX_ZONE;
    public static NamespacedKey CHUNK_PRESSURE;
    public static NamespacedKey HAUNTING_SCORE;
    public static NamespacedKey POLLUTION_SCORE;
    public static NamespacedKey BIOME_HEALTH;
    public static NamespacedKey STRUCTURE_ID;

    // World keys
    public static NamespacedKey OMEN_SCORE;
    public static NamespacedKey SEASON_EPOCH; // millis when time-tracking began

    // Player keys
    public static NamespacedKey NOTORIETY;
    public static NamespacedKey DEATH_LOCATION;
    public static NamespacedKey VILLAGE_LAST_EXPANSION;
    public static NamespacedKey TOOL_USE_COUNT;
    public static NamespacedKey RELIC_OWNER;
    public static NamespacedKey FACTION_REP_SNAPSHOT;
    public static NamespacedKey PARTICLE_DENSITY;
    public static NamespacedKey ANTECEDENT_NOTATION_PROGRESS;
    public static NamespacedKey SOUL_ROAD_PROGRESS;
    public static NamespacedKey SKY_ISLAND_ID;

    // Entity keys
    public static NamespacedKey MINION_DATA;
    public static NamespacedKey WARDEN_CITY_ID;

    // ItemStack keys
    public static NamespacedKey TITAN_BONE_TYPE;

    // Per-faction reputation keys — faction name is appended at runtime via reputationKey()
    private static final Map<Faction, NamespacedKey> REPUTATION_KEYS = new EnumMap<>(Faction.class);

    // Per-player chunk exploration keys — built at runtime via chunkExploredKey()
    // (not pre-allocated; UUID suffix makes pre-allocation impractical)

    private Keys() {}

    /**
     * Must be called once during VestigiumLib#onEnable before any other API is used.
     */
    public static void init(Plugin plugin) {
        PLAYER_PLACED            = new NamespacedKey(plugin, "player_placed");
        ROAD_WALK_COUNT          = new NamespacedKey(plugin, "road_walk_count");
        CHUNK_HEX_ZONE           = new NamespacedKey(plugin, "chunk_hex_zone");
        CHUNK_PRESSURE           = new NamespacedKey(plugin, "chunk_pressure");
        HAUNTING_SCORE           = new NamespacedKey(plugin, "haunting_score");
        POLLUTION_SCORE          = new NamespacedKey(plugin, "pollution_score");
        BIOME_HEALTH             = new NamespacedKey(plugin, "biome_health");
        STRUCTURE_ID             = new NamespacedKey(plugin, "structure_id");

        OMEN_SCORE               = new NamespacedKey(plugin, "omen_score");
        SEASON_EPOCH             = new NamespacedKey(plugin, "season_epoch");

        NOTORIETY                = new NamespacedKey(plugin, "notoriety");
        DEATH_LOCATION           = new NamespacedKey(plugin, "death_location");
        VILLAGE_LAST_EXPANSION   = new NamespacedKey(plugin, "village_last_expansion");
        TOOL_USE_COUNT           = new NamespacedKey(plugin, "tool_use_count");
        RELIC_OWNER              = new NamespacedKey(plugin, "relic_owner");
        FACTION_REP_SNAPSHOT     = new NamespacedKey(plugin, "faction_rep_snapshot");
        PARTICLE_DENSITY         = new NamespacedKey(plugin, "particle_density");
        ANTECEDENT_NOTATION_PROGRESS = new NamespacedKey(plugin, "antecedent_notation_progress");
        SOUL_ROAD_PROGRESS       = new NamespacedKey(plugin, "soul_road_progress");
        SKY_ISLAND_ID            = new NamespacedKey(plugin, "sky_island_id");

        MINION_DATA              = new NamespacedKey(plugin, "minion_data");
        WARDEN_CITY_ID           = new NamespacedKey(plugin, "warden_city_id");

        TITAN_BONE_TYPE          = new NamespacedKey(plugin, "titan_bone_type");

        for (Faction faction : Faction.values()) {
            REPUTATION_KEYS.put(faction, new NamespacedKey(plugin, "reputation_" + faction.getKey()));
        }
    }

    /** Returns the PDC key for a player's reputation with a given faction. */
    public static NamespacedKey reputationKey(Faction faction) {
        return REPUTATION_KEYS.get(faction);
    }

    /** Returns a per-player chunk exploration key. Built on demand. */
    public static NamespacedKey chunkExploredKey(Plugin plugin, UUID playerUUID) {
        return new NamespacedKey(plugin, "chunk_explored_" + playerUUID);
    }

    /** Returns the per-city warden kill count key for a player. */
    public static NamespacedKey wardenKillsKey(Plugin plugin, String cityId) {
        return new NamespacedKey(plugin, "warden_kills_" + cityId);
    }

    /** Returns the lore fragment key for a given fragment ID. */
    public static NamespacedKey loreFragmentKey(Plugin plugin, String fragmentId) {
        return new NamespacedKey(plugin, "lore_fragment_" + fragmentId);
    }

    /** Returns the jungle marker read key for a given marker ID. */
    public static NamespacedKey jungleMarkerReadKey(Plugin plugin, String markerId) {
        return new NamespacedKey(plugin, "jungle_marker_read_" + markerId);
    }
}
