package com.vestigium.lib.model;

public enum Faction {
    VILLAGERS,
    BANDITS,
    MERCENARIES,
    CULTISTS,
    DROWNED,
    END_REMNANTS,
    CONCLAVE;

    public String getKey() {
        return name().toLowerCase();
    }
}
