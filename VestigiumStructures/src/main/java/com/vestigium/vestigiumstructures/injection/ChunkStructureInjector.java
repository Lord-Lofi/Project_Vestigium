package com.vestigium.vestigiumstructures.injection;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.WorldBossSpawnEvent;
import com.vestigium.lib.util.BlockStructureTag;
import com.vestigium.vestigiumstructures.VestigiumStructures;
import com.vestigium.vestigiumstructures.registry.StructureDefinition;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Injects schematics into newly generated chunks based on biome and rarity rules.
 *
 * On first generation only (isNewChunk=true), iterates all structure definitions,
 * filters by biome compatibility and schematic existence, rolls rarity for each
 * candidate, then pastes one winner at the clamped surface Y.
 *
 * Requires WorldEdit/FAWE to be present; silently skips when SchematicManager
 * is null (i.e., WorldEdit not installed).
 */
public class ChunkStructureInjector implements Listener {

    private final VestigiumStructures plugin;
    private final Logger log;

    public ChunkStructureInjector(VestigiumStructures plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        log.info("[ChunkStructureInjector] Initialized.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        if (plugin.getSchematicManager() == null) return;

        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();

        // Gather candidates that pass biome filter and schematic existence check.
        List<StructureDefinition> candidates = new ArrayList<>();
        for (StructureDefinition def : plugin.getStructureRegistry().getAll()) {
            if (!matchesBiome(def, chunk, world)) continue;
            if (!plugin.getSchematicManager().exists(def.id())) continue;
            if (ThreadLocalRandom.current().nextInt(100) < def.rarity()) {
                candidates.add(def);
            }
        }

        if (candidates.isEmpty()) return;

        StructureDefinition chosen = candidates.get(
                ThreadLocalRandom.current().nextInt(candidates.size()));

        // Determine placement Y: highest non-air block at chunk centre, clamped to def range.
        int cx = chunk.getX() * 16 + 8;
        int cz = chunk.getZ() * 16 + 8;
        int y = Math.max(chosen.minY(), Math.min(chosen.maxY(),
                world.getHighestBlockYAt(cx, cz)));

        Location loc = new Location(world, cx, y, cz);

        Optional<Clipboard> clip = plugin.getSchematicManager().load(chosen.id());
        clip.ifPresent(clipboard -> {
            plugin.getStructurePlacer().paste(clipboard, loc, false);

            Block anchor = world.getBlockAt(cx, y, cz);
            BlockStructureTag.set(anchor, chosen.id());

            log.info("[ChunkStructureInjector] Placed " + chosen.id()
                    + " at " + world.getName()
                    + " " + cx + "," + y + "," + cz);

            // Notify ResonantArchiveManager about underground archive anchors
            if (chosen.id().startsWith("resonant_archive")) {
                plugin.getResonantArchiveManager().registerAnchor(anchor, chosen.id());
            }

            // Fire world boss spawn event so VestigiumMobs can handle the warden
            if (chosen.wardenType() != null && !chosen.wardenType().isBlank()) {
                VestigiumLib.getEventBus().fire(
                        new WorldBossSpawnEvent(chosen.wardenType(), null, loc));
            }
        });
    }

    private boolean matchesBiome(StructureDefinition def, Chunk chunk, World world) {
        List<String> allowed = def.biomes();
        if (allowed.isEmpty()) return true;

        int cx = chunk.getX() * 16 + 8;
        int cz = chunk.getZ() * 16 + 8;
        // Sample biome at mid-altitude for both overworld and nether
        Biome biome = world.getBiome(cx, 64, cz);
        String biomeName = biome.getKey().getKey().toUpperCase();

        return allowed.stream().anyMatch(b -> b.equalsIgnoreCase(biomeName));
    }
}
