package com.vestigium.vestigiumstructures.schematic;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.vestigium.vestigiumstructures.VestigiumStructures;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Loads WorldEdit-compatible schematics from plugins/VestigiumStructures/schematics/.
 *
 * Supported formats (auto-detected via ClipboardFormats.findByFile):
 *   .schem       Sponge v2/v3 NBT  (WorldEdit 7+ //schematic save)
 *   .schematic   Legacy MCEdit/WE6 NBT
 *   .nbt         Vanilla structure files
 *
 * Only active when WorldEdit or FAWE is installed. Absence of both is handled
 * gracefully — every load() call simply returns Optional.empty().
 *
 * Clipboards are NOT cached; each call reads from disk.
 */
public class SchematicManager {

    private static final String[] EXTENSIONS = {".schem", ".schematic", ".nbt"};

    private final File schematicsDir;
    private final Logger log;

    public SchematicManager(VestigiumStructures plugin) {
        this.schematicsDir = new File(plugin.getDataFolder(), "schematics");
        this.log = plugin.getLogger();
        if (!schematicsDir.exists()) {
            schematicsDir.mkdirs();
        }
    }

    /**
     * Loads the schematic for the given structure id.
     * Tries .schem, .schematic, .nbt in that order.
     *
     * @param structureId  the structure's id (matches the filename without extension)
     * @return the loaded Clipboard, or empty if no file found / load failed
     */
    public Optional<Clipboard> load(String structureId) {
        for (String ext : EXTENSIONS) {
            File file = new File(schematicsDir, structureId + ext);
            if (!file.exists()) continue;

            ClipboardFormat format = ClipboardFormats.findByFile(file);
            if (format == null) {
                log.warning("[SchematicManager] Unknown format for " + file.getName() + " — skipped.");
                continue;
            }

            try (FileInputStream fis = new FileInputStream(file);
                 ClipboardReader reader = format.getReader(fis)) {
                Clipboard clipboard = reader.read();
                log.info("[SchematicManager] Loaded " + file.getName()
                        + " (" + format.getName() + ")");
                return Optional.of(clipboard);
            } catch (IOException e) {
                log.warning("[SchematicManager] Failed to load " + file.getName()
                        + ": " + e.getMessage());
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    /**
     * Returns true if a schematic file exists for the given structure id
     * (in any supported format), without loading it.
     */
    public boolean exists(String structureId) {
        for (String ext : EXTENSIONS) {
            if (new File(schematicsDir, structureId + ext).exists()) return true;
        }
        return false;
    }

    public File getSchematicsDir() {
        return schematicsDir;
    }
}
