package com.vestigium.vestigiumworld.cataclysm;

import org.bukkit.Location;

/**
 * Represents a currently-active cataclysm on the server.
 * Holds the type, epicenter, start time, and optional duration.
 * durationMillis <= 0 means the cataclysm runs until manually resolved.
 */
public class ActiveCataclysm {

    private final String type;
    private final Location epicenter;
    private final long startMillis;
    private final long durationMillis; // <=0 = indefinite

    public ActiveCataclysm(String type, Location epicenter, long durationMillis) {
        this.type           = type;
        this.epicenter      = epicenter;
        this.startMillis    = System.currentTimeMillis();
        this.durationMillis = durationMillis;
    }

    public String getType()           { return type; }
    public Location getEpicenter()    { return epicenter; }
    public long getStartMillis()      { return startMillis; }
    public long getDurationMillis()   { return durationMillis; }

    public boolean isExpired() {
        if (durationMillis <= 0) return false;
        return System.currentTimeMillis() - startMillis >= durationMillis;
    }

    public long getElapsedMillis() {
        return System.currentTimeMillis() - startMillis;
    }
}
