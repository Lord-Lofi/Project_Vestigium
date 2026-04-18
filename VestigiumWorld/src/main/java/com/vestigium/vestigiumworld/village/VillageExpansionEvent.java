package com.vestigium.vestigiumworld.village;

import com.vestigium.lib.event.VestigiumEvent;
import org.bukkit.Location;

import java.util.UUID;

/**
 * Fired when a village is eligible to expand.
 * VestigiumStructures subscribes to this event to add new building modules.
 */
public class VillageExpansionEvent extends VestigiumEvent {

    private final Location villageAnchor;
    private final UUID triggeringPlayer;

    public VillageExpansionEvent(Location villageAnchor, UUID triggeringPlayer) {
        this.villageAnchor   = villageAnchor;
        this.triggeringPlayer = triggeringPlayer;
    }

    public Location getVillageAnchor()   { return villageAnchor; }
    public UUID getTriggeringPlayer()    { return triggeringPlayer; }
}
