package com.vestigium.lib.event;

/**
 * Base class for all cross-plugin events fired through the Vestigium EventBus.
 * These are NOT Bukkit events — they travel through EventBus only, not through
 * Bukkit's plugin manager. This keeps cross-plugin communication fully internal.
 */
public abstract class VestigiumEvent {

    private final long timestamp;

    protected VestigiumEvent() {
        this.timestamp = System.currentTimeMillis();
    }

    public long getTimestamp() {
        return timestamp;
    }
}
