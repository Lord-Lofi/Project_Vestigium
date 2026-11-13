package com.vestigium.vestigiumend.structure;

import org.bukkit.Location;

public record EndSite(
        String id,
        String displayName,
        EndStructureManager.StructureType type,
        Location center,
        int radius,
        String sanctumPart
) {}
