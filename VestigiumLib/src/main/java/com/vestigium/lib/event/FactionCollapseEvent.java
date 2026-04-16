package com.vestigium.lib.event;

/** Fired when a faction's health drops below the collapse threshold (< 20). */
public class FactionCollapseEvent extends VestigiumEvent {

    private final String factionId;
    private final int finalHealth;

    public FactionCollapseEvent(String factionId, int finalHealth) {
        this.factionId = factionId;
        this.finalHealth = finalHealth;
    }

    public String getFactionId() { return factionId; }
    public int getFinalHealth() { return finalHealth; }
}
