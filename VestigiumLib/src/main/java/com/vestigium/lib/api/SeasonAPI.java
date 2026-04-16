package com.vestigium.lib.api;

import com.vestigium.lib.event.SeasonChangeEvent;
import com.vestigium.lib.event.TidalChangeEvent;
import com.vestigium.lib.model.Season;
import com.vestigium.lib.util.Keys;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Server-tracked real-time season and tidal clock.
 *
 * Seasons: 4 seasons of 30 real-world days each (120 days full cycle).
 * Tidal clock: 12 phases, each 1 real-world hour (12-hour full cycle).
 *   - Phases 0-5: rising tide
 *   - Phases 6-11: falling tide
 *   - High tide: phase 5 (peak rising)
 *   - Low tide: phase 11 (peak falling)
 *
 * The epoch (start of time tracking) is stored in the overworld World PDC
 * as vestigium:season_epoch (milliseconds). This persists across restarts.
 */
public class SeasonAPI {

    private static final long MILLIS_PER_TIDAL_PHASE = 60 * 60 * 1000L;       // 1 hour
    private static final long MILLIS_PER_TIDAL_CYCLE = 12 * MILLIS_PER_TIDAL_PHASE; // 12 hours
    // Check for season/tidal changes every 5 minutes
    private static final long CHECK_INTERVAL_TICKS = 6_000L;

    private final Plugin plugin;
    private final EventBus eventBus;
    private World overworld;
    private BukkitRunnable clockTask;

    private Season lastKnownSeason = null;
    private int lastKnownTidalPhase = -1;

    public SeasonAPI(Plugin plugin, EventBus eventBus) {
        this.plugin = plugin;
        this.eventBus = eventBus;
    }

    /** Must be called after worlds load. Establishes epoch if not already stored. */
    public void init(World overworld) {
        this.overworld = overworld;

        // Write epoch if this is the first time
        if (!overworld.getPersistentDataContainer().has(Keys.SEASON_EPOCH, PersistentDataType.LONG)) {
            overworld.getPersistentDataContainer()
                    .set(Keys.SEASON_EPOCH, PersistentDataType.LONG, System.currentTimeMillis());
            plugin.getLogger().info("[SeasonAPI] Season epoch initialised.");
        }

        lastKnownSeason = getCurrentSeason();
        lastKnownTidalPhase = getTidalPhase();

        startClockTask();
        plugin.getLogger().info("[SeasonAPI] Initialized. Season: " + lastKnownSeason
                + ", Tidal phase: " + lastKnownTidalPhase);
    }

    /** Returns the current season based on real elapsed time since epoch. */
    public Season getCurrentSeason() {
        long elapsed = getElapsedMillis();
        int seasonIndex = (int) ((elapsed / Season.MILLIS_PER_SEASON) % Season.values().length);
        return Season.values()[seasonIndex];
    }

    /**
     * Returns the current tidal phase (0-11).
     * 0-5 = rising, 6-11 = falling.
     */
    public int getTidalPhase() {
        long elapsed = getElapsedMillis();
        return (int) ((elapsed / MILLIS_PER_TIDAL_PHASE) % 12);
    }

    /** True when the tidal phase is 5 (peak rising). */
    public boolean isHighTide() {
        return getTidalPhase() == 5;
    }

    /** True when the tidal phase is 11 (peak falling). */
    public boolean isLowTide() {
        return getTidalPhase() == 11;
    }

    /** Returns total Minecraft days elapsed on this server. */
    public long getDayCount() {
        if (overworld == null) return 0;
        return overworld.getFullTime() / 24_000L;
    }

    /** Returns the milliseconds elapsed since the season epoch. */
    public long getElapsedMillis() {
        if (overworld == null) return 0;
        long epoch = overworld.getPersistentDataContainer()
                .getOrDefault(Keys.SEASON_EPOCH, PersistentDataType.LONG, System.currentTimeMillis());
        return System.currentTimeMillis() - epoch;
    }

    public void shutdown() {
        if (clockTask != null) clockTask.cancel();
    }

    // -------------------------------------------------------------------------

    private void startClockTask() {
        clockTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkSeasonChange();
                checkTidalChange();
            }
        };
        clockTask.runTaskTimer(plugin, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    private void checkSeasonChange() {
        Season current = getCurrentSeason();
        if (lastKnownSeason != null && current != lastKnownSeason) {
            eventBus.fire(new SeasonChangeEvent(lastKnownSeason, current));
            plugin.getLogger().info("[SeasonAPI] Season changed: " + lastKnownSeason + " -> " + current);
            lastKnownSeason = current;
        }
    }

    private void checkTidalChange() {
        int current = getTidalPhase();
        if (current != lastKnownTidalPhase) {
            eventBus.fire(new TidalChangeEvent(lastKnownTidalPhase, current, isHighTide(), isLowTide()));
            lastKnownTidalPhase = current;
        }
    }
}
