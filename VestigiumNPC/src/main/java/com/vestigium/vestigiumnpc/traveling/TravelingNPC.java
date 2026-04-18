package com.vestigium.vestigiumnpc.traveling;

import org.bukkit.Location;
import org.bukkit.entity.Villager;

/**
 * Wrapper around a spawned traveling NPC entity.
 * Tracks its type, spawn time, and next relocation timestamp.
 */
public class TravelingNPC {

    private final String type;
    private final Villager entity;
    private final long spawnedMillis;
    private final long relocateIntervalMillis;

    public TravelingNPC(String type, Villager entity, long relocateIntervalMillis) {
        this.type = type;
        this.entity = entity;
        this.spawnedMillis = System.currentTimeMillis();
        this.relocateIntervalMillis = relocateIntervalMillis;
    }

    public String getType()       { return type; }
    public Villager getEntity()   { return entity; }
    public long getSpawnedMillis(){ return spawnedMillis; }

    public boolean isDueRelocation() {
        return System.currentTimeMillis() - spawnedMillis >= relocateIntervalMillis;
    }

    public boolean isValid() {
        return entity != null && entity.isValid() && !entity.isDead();
    }
}
