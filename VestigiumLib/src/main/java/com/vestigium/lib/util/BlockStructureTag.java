package com.vestigium.lib.util;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;

/**
 * Stores per-block structure_id tags on the owning chunk's PersistentDataContainer,
 * since Block does not implement PersistentDataHolder in Paper 1.21.4+.
 *
 * Format: chunk PDC key "vestigium:structure_anchors" holds a comma-delimited
 * string of "x:y:z=structureId" entries, e.g. ",100:64:-32=wandering_ruin,".
 */
public final class BlockStructureTag {

    public static final NamespacedKey KEY =
            new NamespacedKey("vestigium", "structure_anchors");

    private BlockStructureTag() {}

    public static String get(Block block) {
        String tag = block.getChunk().getPersistentDataContainer()
                .getOrDefault(KEY, PersistentDataType.STRING, ",");
        String prefix = "," + pos(block) + "=";
        int start = tag.indexOf(prefix);
        if (start == -1) return null;
        int end = tag.indexOf(",", start + prefix.length());
        return end == -1 ? null : tag.substring(start + prefix.length(), end);
    }

    public static void set(Block block, String structureId) {
        var pdc = block.getChunk().getPersistentDataContainer();
        String p = pos(block);
        String tag = removeEntry(pdc.getOrDefault(KEY, PersistentDataType.STRING, ","), p);
        pdc.set(KEY, PersistentDataType.STRING, tag + p + "=" + structureId + ",");
    }

    public static void remove(Block block) {
        var pdc = block.getChunk().getPersistentDataContainer();
        String tag = removeEntry(pdc.getOrDefault(KEY, PersistentDataType.STRING, ","), pos(block));
        pdc.set(KEY, PersistentDataType.STRING, tag);
    }

    private static String pos(Block b) {
        return b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private static String removeEntry(String tag, String p) {
        String prefix = "," + p + "=";
        int start = tag.indexOf(prefix);
        if (start == -1) return tag;
        int end = tag.indexOf(",", start + prefix.length());
        return end == -1 ? tag : tag.substring(0, start) + tag.substring(end);
    }
}
