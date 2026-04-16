package com.vestigium.lib.model;

public enum Season {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER;

    /** Real-world milliseconds per season (30 days). */
    public static final long MILLIS_PER_SEASON = 30L * 24 * 60 * 60 * 1000;

    public Season next() {
        Season[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
