package com.vestigium.vestigiumstructures.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import com.vestigium.vestigiumstructures.VestigiumStructures;
import org.bukkit.Location;

import java.util.logging.Logger;

/**
 * Pastes WorldEdit Clipboard objects into the world at a given Bukkit Location.
 *
 * The paste origin is the clipboard's own origin vector — callers can shift
 * the clipboard's origin before calling paste() if they need to align the
 * schematic to a specific block (e.g., the bottom-left corner vs. the center).
 *
 * Paste runs synchronously on the server thread. Keep schematics small or
 * schedule the call off peak hours for large structures.
 */
public class StructurePlacer {

    private final Logger log;

    public StructurePlacer(VestigiumStructures plugin) {
        this.log = plugin.getLogger();
    }

    /**
     * Pastes a clipboard at the given Bukkit location.
     *
     * @param clipboard  the clipboard to paste (from SchematicManager.load)
     * @param location   target origin in the Bukkit world
     * @param ignoreAir  if true, air blocks in the schematic do not overwrite existing blocks
     */
    public void paste(Clipboard clipboard, Location location, boolean ignoreAir) {
        if (location.getWorld() == null) {
            log.warning("[StructurePlacer] Cannot paste — location has null world.");
            return;
        }

        World weWorld = BukkitAdapter.adapt(location.getWorld());
        BlockVector3 target = BlockVector3.at(
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ());

        try (EditSession editSession = WorldEdit.getInstance()
                .newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {

            Operation operation = new ClipboardHolder(clipboard)
                    .createPaste(editSession)
                    .to(target)
                    .ignoreAirBlocks(ignoreAir)
                    .build();

            Operations.complete(operation);
            log.info("[StructurePlacer] Pasted schematic at "
                    + location.getWorld().getName()
                    + " " + target.x() + "," + target.y() + "," + target.z());

        } catch (Exception e) {
            log.warning("[StructurePlacer] Paste failed: " + e.getMessage());
        }
    }

    /** Convenience overload — does not ignore air blocks. */
    public void paste(Clipboard clipboard, Location location) {
        paste(clipboard, location, false);
    }
}
