package com.vestigium.vestigiumstructures.waystone;

import org.bukkit.Location;

public record WaystoneRecord(String id, String displayName, Location location, int energy) {
    public WaystoneRecord withEnergy(int newEnergy) {
        return new WaystoneRecord(id, displayName, location, newEnergy);
    }
}
