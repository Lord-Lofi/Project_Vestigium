package com.vestigium.lib.event;

import org.bukkit.Location;

/** Fired when a cataclysmic event begins. Consumed primarily by VestigiumAtmosphere. */
public class CataclysmStartEvent extends VestigiumEvent {

    private final String cataclysmType;
    private final Location epicenter;

    public CataclysmStartEvent(String cataclysmType, Location epicenter) {
        this.cataclysmType = cataclysmType;
        this.epicenter = epicenter;
    }

    /** Identifier matching the cataclysm type keys defined in VestigiumWorld (e.g. "METEOR", "WITHERING"). */
    public String getCataclysmType() { return cataclysmType; }

    /** The world location that is the origin of this event. May be null for server-wide cataclysms. */
    public Location getEpicenter() { return epicenter; }
}
