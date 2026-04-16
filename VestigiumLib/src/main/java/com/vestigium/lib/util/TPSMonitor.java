package com.vestigium.lib.util;

import org.bukkit.Bukkit;

/**
 * Thin wrapper around Paper's server TPS API.
 * Provides the current 1-minute TPS average and scaling thresholds used by ParticleManager.
 *
 * TPS scaling thresholds:
 *  >= 18 TPS → full particle density
 *  15-17 TPS → reduced density (0.5x)
 *  12-14 TPS → minimal density (0.25x)
 *  < 12 TPS  → non-critical atmospheric particles suspended entirely
 */
public class TPSMonitor {

    public static final double THRESHOLD_FULL      = 18.0;
    public static final double THRESHOLD_REDUCED   = 15.0;
    public static final double THRESHOLD_MINIMAL   = 12.0;

    /**
     * Returns the current 1-minute TPS average.
     * Paper exposes Bukkit.getServer().getTPS() → double[3] (1m, 5m, 15m).
     */
    public double getCurrentTPS() {
        double[] tps = Bukkit.getServer().getTPS();
        return tps.length > 0 ? tps[0] : 20.0;
    }

    /**
     * Returns the atmospheric particle density multiplier based on current TPS.
     *
     * @return 1.0 (full), 0.5 (reduced), 0.25 (minimal), or 0.0 (suspended)
     */
    public double getDensityMultiplier() {
        double tps = getCurrentTPS();
        if (tps >= THRESHOLD_FULL)    return 1.0;
        if (tps >= THRESHOLD_REDUCED) return 0.5;
        if (tps >= THRESHOLD_MINIMAL) return 0.25;
        return 0.0; // suspended
    }

    /** Returns true if TPS is critically low and atmospheric particles should be fully suspended. */
    public boolean isCriticallyLow() {
        return getCurrentTPS() < THRESHOLD_MINIMAL;
    }
}
