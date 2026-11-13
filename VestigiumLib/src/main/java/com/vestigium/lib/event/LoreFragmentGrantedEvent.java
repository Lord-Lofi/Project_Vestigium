package com.vestigium.lib.event;

import java.util.UUID;

/**
 * Fired by LoreRegistry.grantFragment() whenever a player receives a lore fragment.
 * Subscribe via EventBus to update lore-fragment counters or trigger quest progress.
 */
public class LoreFragmentGrantedEvent extends VestigiumEvent {

    private final UUID playerUUID;
    private final String fragmentId;

    public LoreFragmentGrantedEvent(UUID playerUUID, String fragmentId) {
        this.playerUUID = playerUUID;
        this.fragmentId = fragmentId;
    }

    public UUID getPlayerUUID() { return playerUUID; }
    public String getFragmentId() { return fragmentId; }
}
