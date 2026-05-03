package com.vestigium.lib.api;

import com.vestigium.lib.event.OmenThresholdEvent;
import com.vestigium.lib.util.Keys;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.TreeMap;

/**
 * Global server health score 0-1000. The invisible backbone driving event
 * frequency and escalation.
 *
 * Storage: vestigium:omen_score on the overworld World PDC.
 * Night multiplier: score is treated as 1.25x during server night.
 * Auto-decay: -1 per real-world hour with no player activity.
 * Thresholds: 200 (elevated), 400 (dangerous), 600 (critical), 800 (catastrophic), 1000 (Herobrine).
 */
public class OmenAPI {

    public static final int MAX_OMEN = 1000;
    public static final int MIN_OMEN = 0;
    public static final float NIGHT_MULTIPLIER = 1.25f;

    private static final int[] DEFAULT_THRESHOLDS = {200, 400, 600, 800, 1000};
    // Decay: 1 point per real-world hour = every 72000 ticks (20 ticks/s * 3600s)
    private static final long DECAY_INTERVAL_TICKS = 72_000L;

    private final Plugin plugin;
    private final EventBus eventBus;
    private World overworld;

    // threshold → whether it has been crossed (to avoid repeated firing)
    private final TreeMap<Integer, Boolean> thresholdState = new TreeMap<>();

    private long lastActivityMillis = System.currentTimeMillis();
    private boolean lastChangeWasIncrease = false;
    private BukkitRunnable decayTask;

    public OmenAPI(Plugin plugin, EventBus eventBus) {
        this.plugin = plugin;
        this.eventBus = eventBus;
        for (int t : DEFAULT_THRESHOLDS) {
            thresholdState.put(t, false);
        }
    }

    /** Must be called after the world is loaded (e.g. in onEnable after Bukkit worlds are ready). */
    public void init(World overworld) {
        this.overworld = overworld;
        startDecayTask();
        plugin.getLogger().info("[OmenAPI] Initialized. Current omen score: " + getOmenScore());
    }

    /** Returns the raw omen score (0-1000). */
    public int getOmenScore() {
        if (overworld == null) return 0;
        return overworld.getPersistentDataContainer()
                .getOrDefault(Keys.OMEN_SCORE, PersistentDataType.INTEGER, 0);
    }

    /**
     * Returns the effective omen score applying the night multiplier if it is
     * currently night on the server. Use this for event frequency decisions.
     */
    public float getEffectiveOmenScore() {
        int raw = getOmenScore();
        if (overworld != null && isNight()) {
            return Math.min(MAX_OMEN, raw * NIGHT_MULTIPLIER);
        }
        return raw;
    }

    /** Adds omen, clamps to MAX_OMEN, fires threshold events if crossed. */
    public void addOmen(int amount) {
        if (amount <= 0 || overworld == null) return;
        int previous = getOmenScore();
        int updated = Math.min(MAX_OMEN, previous + amount);
        setScore(updated);
        lastChangeWasIncrease = updated > previous;
        checkThresholds(previous, updated);
    }

    /** Subtracts omen, clamps to MIN_OMEN, fires threshold events if crossed downward. */
    public void subtractOmen(int amount) {
        if (amount <= 0 || overworld == null) return;
        int previous = getOmenScore();
        int updated = Math.max(MIN_OMEN, previous - amount);
        setScore(updated);
        lastChangeWasIncrease = false;
        checkThresholds(previous, updated);
    }

    /** Returns true if the most recent omen change was an increase. */
    public boolean isAscending() {
        return lastChangeWasIncrease;
    }

    /**
     * Registers a custom threshold. The OmenThresholdEvent fires when the score
     * crosses this value in either direction.
     *
     * @param threshold value 0-1000 to watch
     */
    public void registerThreshold(int threshold) {
        thresholdState.putIfAbsent(threshold, getOmenScore() >= threshold);
    }

    /** Notifies OmenAPI that players are active — resets the decay countdown. */
    public void markPlayerActivity() {
        lastActivityMillis = System.currentTimeMillis();
    }

    public void shutdown() {
        if (decayTask != null) {
            decayTask.cancel();
        }
    }

    // -------------------------------------------------------------------------

    private void setScore(int score) {
        overworld.getPersistentDataContainer()
                .set(Keys.OMEN_SCORE, PersistentDataType.INTEGER, score);
    }

    private void checkThresholds(int previous, int updated) {
        for (Map.Entry<Integer, Boolean> entry : thresholdState.entrySet()) {
            int threshold = entry.getKey();
            boolean wasCrossed = entry.getValue();
            boolean nowCrossed = updated >= threshold;

            if (nowCrossed != wasCrossed) {
                thresholdState.put(threshold, nowCrossed);
                eventBus.fire(new OmenThresholdEvent(threshold, previous, updated));
            }
        }
    }

    private boolean isNight() {
        long time = overworld.getTime();
        // Minecraft night is roughly ticks 13000-23000
        return time >= 13_000 && time <= 23_000;
    }

    private void startDecayTask() {
        decayTask = new BukkitRunnable() {
            @Override
            public void run() {
                // Only decay if no player activity in the last real-world hour
                long oneHourMillis = 60 * 60 * 1000L;
                if (System.currentTimeMillis() - lastActivityMillis >= oneHourMillis) {
                    subtractOmen(1);
                }
            }
        };
        decayTask.runTaskTimer(plugin, DECAY_INTERVAL_TICKS, DECAY_INTERVAL_TICKS);
    }
}
