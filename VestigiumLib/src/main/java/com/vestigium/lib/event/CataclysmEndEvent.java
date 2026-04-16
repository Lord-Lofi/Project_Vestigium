package com.vestigium.lib.event;

import org.bukkit.Location;

/** Fired when a cataclysmic event concludes. Triggers The Long Exhale recovery sequence. */
public class CataclysmEndEvent extends VestigiumEvent {

    private final String cataclysmType;
    private final Location epicenter;
    private final boolean playerResolved; // true = players actively ended it, false = time-expired

    public CataclysmEndEvent(String cataclysmType, Location epicenter, boolean playerResolved) {
        this.cataclysmType = cataclysmType;
        this.epicenter = epicenter;
        this.playerResolved = playerResolved;
    }

    public String getCataclysmType() { return cataclysmType; }
    public Location getEpicenter() { return epicenter; }
    public boolean isPlayerResolved() { return playerResolved; }
}
