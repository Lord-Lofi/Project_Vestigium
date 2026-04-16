package com.vestigium.lib.event;

import com.vestigium.lib.model.Faction;

import java.util.UUID;

/** Fired when a player's reputation with any faction changes. Consumed by VestigiumPlayer for title recalculation. */
public class PlayerReputationChangeEvent extends VestigiumEvent {

    private final UUID playerUUID;
    private final Faction faction;
    private final int previousReputation;
    private final int newReputation;

    public PlayerReputationChangeEvent(UUID playerUUID, Faction faction, int previousReputation, int newReputation) {
        this.playerUUID = playerUUID;
        this.faction = faction;
        this.previousReputation = previousReputation;
        this.newReputation = newReputation;
    }

    public UUID getPlayerUUID() { return playerUUID; }
    public Faction getFaction() { return faction; }
    public int getPreviousReputation() { return previousReputation; }
    public int getNewReputation() { return newReputation; }
    public int getDelta() { return newReputation - previousReputation; }
}
