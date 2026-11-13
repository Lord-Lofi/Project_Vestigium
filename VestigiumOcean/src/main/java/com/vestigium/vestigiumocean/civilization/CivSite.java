package com.vestigium.vestigiumocean.civilization;

import org.bukkit.Location;

public record CivSite(String id, String displayName, int tier, Location center, int radius) {}
