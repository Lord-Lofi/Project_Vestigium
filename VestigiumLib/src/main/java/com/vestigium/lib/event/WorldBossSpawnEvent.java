package com.vestigium.lib.event;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Fired when a Vestigium world boss spawns. Consumed by VestigiumQuests and VestigiumAtmosphere. */
public class WorldBossSpawnEvent extends VestigiumEvent {

    private final String bossType;
    private final Entity entity;
    private final Location spawnLocation;

    public WorldBossSpawnEvent(String bossType, Entity entity, Location spawnLocation) {
        this.bossType = bossType;
        this.entity = entity;
        this.spawnLocation = spawnLocation;
    }

    /** Identifier for the boss type (e.g. "LEVIATHAN", "SUNKEN_GOD", "HEROBRINE"). */
    public String getBossType() { return bossType; }
    public Entity getEntity() { return entity; }
    public Location getSpawnLocation() { return spawnLocation; }
}
