package com.vestigium.vestigiumstructures;

import com.vestigium.vestigiumstructures.registry.StructureRegistry;
import com.vestigium.vestigiumstructures.schematic.SchematicManager;
import com.vestigium.vestigiumstructures.schematic.StructurePlacer;
import com.vestigium.vestigiumstructures.spawner.StructureNPCSpawner;
import com.vestigium.vestigiumstructures.wandering.WanderingDungeonManager;
import com.vestigium.vestigiumstructures.waystone.WaystoneManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumStructures — custom structure injection, dungeon management,
 * wandering dungeons, and structure-specific NPC spawning.
 * Depends only on VestigiumLib. WorldEdit/FAWE are soft dependencies used
 * only by SchematicManager and StructurePlacer.
 */
public class VestigiumStructures extends JavaPlugin {

    private static VestigiumStructures instance;

    private StructureRegistry       structureRegistry;
    private WanderingDungeonManager wanderingDungeonManager;
    private StructureNPCSpawner     structureNPCSpawner;
    private WaystoneManager         waystoneManager;
    private SchematicManager        schematicManager;
    private StructurePlacer         structurePlacer;

    @Override
    public void onEnable() {
        instance = this;

        structureRegistry       = new StructureRegistry(this);
        wanderingDungeonManager = new WanderingDungeonManager(this);
        structureNPCSpawner     = new StructureNPCSpawner(this);
        waystoneManager         = new WaystoneManager(this);

        structureRegistry.load();
        wanderingDungeonManager.init();
        structureNPCSpawner.init();
        waystoneManager.init();

        if (isWorldEditAvailable()) {
            schematicManager = new SchematicManager(this);
            structurePlacer  = new StructurePlacer(this);
            getLogger().info("WorldEdit detected — schematic loading enabled.");
        } else {
            getLogger().info("WorldEdit not found — schematic loading disabled.");
        }

        getLogger().info("VestigiumStructures enabled.");
    }

    @Override
    public void onDisable() {
        if (wanderingDungeonManager != null) {
            wanderingDungeonManager.shutdown();
            wanderingDungeonManager.save();
        }
        if (waystoneManager != null) waystoneManager.shutdown();
        getLogger().info("VestigiumStructures disabled.");
    }

    private boolean isWorldEditAvailable() {
        return getServer().getPluginManager().getPlugin("WorldEdit") != null
                || getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null;
    }

    public static VestigiumStructures getInstance()              { return instance; }
    public StructureRegistry getStructureRegistry()              { return structureRegistry; }
    public WanderingDungeonManager getWanderingDungeonManager()  { return wanderingDungeonManager; }
    public StructureNPCSpawner getStructureNPCSpawner()          { return structureNPCSpawner; }
    public WaystoneManager getWaystoneManager()                  { return waystoneManager; }

    /** Null if WorldEdit/FAWE is not installed. */
    public SchematicManager getSchematicManager()                { return schematicManager; }
    /** Null if WorldEdit/FAWE is not installed. */
    public StructurePlacer getStructurePlacer()                  { return structurePlacer; }
}
