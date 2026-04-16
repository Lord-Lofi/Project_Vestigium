package com.vestigium.lib.api;

import com.vestigium.lib.event.FactionCollapseEvent;
import com.vestigium.lib.model.FactionState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * All active faction states and health scores.
 *
 * Health range: 0-100.
 * Collapse threshold: < 20 triggers FactionCollapseEvent.
 * Expansion threshold: > 80 + 7-day cooldown (gates checked by FactionState.canExpand()).
 *
 * Persisted to plugins/VestigiumLib/factions.yml on every health change.
 */
public class FactionRegistry {

    private static final int COLLAPSE_THRESHOLD = 20;
    private static final int EXPANSION_THRESHOLD = 80;
    private static final int DEFAULT_HEALTH = 50;

    private final Plugin plugin;
    private final EventBus eventBus;
    private final Map<String, FactionState> factions = new HashMap<>();
    private File dataFile;

    public FactionRegistry(Plugin plugin, EventBus eventBus) {
        this.plugin = plugin;
        this.eventBus = eventBus;
    }

    /** Loads faction data from disk. Creates defaults if the file doesn't exist. */
    public void load() {
        dataFile = new File(plugin.getDataFolder(), "factions.yml");
        if (!dataFile.exists()) {
            createDefaults();
            save();
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
        for (String factionId : config.getKeys(false)) {
            int health = config.getInt(factionId + ".health", DEFAULT_HEALTH);
            long lastExpansion = config.getLong(factionId + ".last_expansion", 0L);
            factions.put(factionId, new FactionState(factionId, health, lastExpansion));
        }
        plugin.getLogger().info("[FactionRegistry] Loaded " + factions.size() + " faction(s).");
    }

    /** Saves current faction data to disk. */
    public void save() {
        if (dataFile == null) return;
        YamlConfiguration config = new YamlConfiguration();
        for (FactionState state : factions.values()) {
            config.set(state.getFactionId() + ".health", state.getHealth());
            config.set(state.getFactionId() + ".last_expansion", state.getLastExpansionMillis());
        }
        try {
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[FactionRegistry] Failed to save factions.yml: " + e.getMessage());
        }
    }

    /**
     * Returns the FactionState for a faction, or null if the faction is unknown.
     *
     * @param factionId the faction identifier (e.g. "villagers", "cultists")
     */
    public FactionState getFactionState(String factionId) {
        return factions.get(factionId.toLowerCase());
    }

    /**
     * Returns the health (0-100) of a faction. Returns -1 if the faction is unknown.
     *
     * @param factionId the faction identifier
     */
    public int getFactionHealth(String factionId) {
        FactionState state = getFactionState(factionId);
        return state != null ? state.getHealth() : -1;
    }

    /**
     * Modifies a faction's health by delta. Clamps to 0-100.
     * Fires FactionCollapseEvent if health drops below the collapse threshold.
     * Saves to disk after every change.
     *
     * @param factionId the faction identifier
     * @param delta     amount to add (negative to subtract)
     */
    public void modifyFactionHealth(String factionId, int delta) {
        FactionState state = getOrCreate(factionId);
        int previous = state.getHealth();
        state.setHealth(previous + delta);
        int updated = state.getHealth();

        // Fire collapse event when crossing below threshold
        if (previous >= COLLAPSE_THRESHOLD && updated < COLLAPSE_THRESHOLD) {
            eventBus.fire(new FactionCollapseEvent(factionId, updated));
            plugin.getLogger().info("[FactionRegistry] Faction collapsed: " + factionId
                    + " (health: " + updated + ")");
        }

        save();
    }

    /**
     * Marks that a faction has just expanded and stamps the cooldown timestamp.
     * Callers must verify canExpand() before calling this.
     *
     * @param factionId the faction identifier
     */
    public void recordExpansion(String factionId) {
        FactionState state = getOrCreate(factionId);
        state.setLastExpansionMillis(System.currentTimeMillis());
        save();
    }

    /** Returns all registered faction states. */
    public Collection<FactionState> getAllFactions() {
        return factions.values();
    }

    // -------------------------------------------------------------------------

    private FactionState getOrCreate(String factionId) {
        return factions.computeIfAbsent(factionId.toLowerCase(),
                id -> new FactionState(id, DEFAULT_HEALTH, 0L));
    }

    private void createDefaults() {
        String[] defaultFactions = {
                "villagers", "bandits", "mercenaries", "cultists",
                "drowned", "end_remnants", "conclave"
        };
        for (String id : defaultFactions) {
            factions.put(id, new FactionState(id, DEFAULT_HEALTH, 0L));
        }
        plugin.getLogger().info("[FactionRegistry] Created default faction entries.");
    }
}
