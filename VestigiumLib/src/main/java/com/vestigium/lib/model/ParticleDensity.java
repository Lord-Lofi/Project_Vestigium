package com.vestigium.lib.model;

public enum ParticleDensity {
    /** Full atmospheric particle effects. */
    FULL(1.0),
    /** Roughly half density — reduced count and frequency. */
    REDUCED(0.5),
    /** Gameplay-critical particles only; all atmospheric suppressed. */
    MINIMAL(0.0);

    private final double multiplier;

    ParticleDensity(double multiplier) {
        this.multiplier = multiplier;
    }

    public double getMultiplier() {
        return multiplier;
    }
}
