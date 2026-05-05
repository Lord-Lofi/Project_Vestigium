package com.vestigium.vestigiumstructures;

import com.vestigium.vestigiumstructures.registry.StructureRegistry;
import com.vestigium.vestigiumstructures.spawner.StructureNPCSpawner;
import com.vestigium.vestigiumstructures.wandering.WanderingDungeonManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumStructures — custom structure injection, dungeon management,
 * wandering dungeons, and structure-specific NPC spawning.
 * Depends only on VestigiumLib.
 */
public class VestigiumStructures extends JavaPlugin {

    private static VestigiumStructures instance;

    private StructureRegistry      structureRegistry;
    private WanderingDungeonManager wanderingDungeonManager;
    private StructureNPCSpawner    structureNPCSpawner;

    @Override
    public void onEnable() {
        instance = this;

        structureRegistry       = new StructureRegistry(this);
        wanderingDungeonManager = new WanderingDungeonManager(this);
        structureNPCSpawner     = new StructureNPCSpawner(this);

        structureRegistry.load();
        wanderingDungeonManager.init();
        structureNPCSpawner.init();

        getLogger().info("VestigiumStructures enabled.");
    }

    @Override
    public void onDisable() {
        if (wanderingDungeonManager != null) {
            wanderingDungeonManager.shutdown();
            wanderingDungeonManager.save();
        }
        getLogger().info("VestigiumStructures disabled.");
    }

    public static VestigiumStructures getInstance()              { return instance; }
    public StructureRegistry getStructureRegistry()              { return structureRegistry; }
    public WanderingDungeonManager getWanderingDungeonManager()  { return wanderingDungeonManager; }
    public StructureNPCSpawner getStructureNPCSpawner()          { return structureNPCSpawner; }
}
