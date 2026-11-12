package com.vestigium.vestigiumeconomy.specialitems;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Titan Bone Materials — 7 biome-specific rare crafting components.
 * Each drops from specific mobs in matching biomes, and at higher rates
 * from Named Wardens (which carry vestigium:named_warden PDC).
 *
 * CMD range 60001–60007. No crafting recipes yet — they are ingredients
 * for future content (Titan Gear, Waystone fuel, etc.).
 *
 * Drop rates:
 *   Named Warden (any type): 10% chance, random bone type
 *   Standard mobs (biome-specific): 2% chance
 */
public class TitanBoneManager implements Listener, CommandExecutor {

    private static final NamespacedKey NAMED_WARDEN_KEY = new NamespacedKey("vestigium", "named_warden");
    private static final NamespacedKey BONE_TYPE_KEY    = new NamespacedKey("vestigium", "titan_bone");

    private static final double NAMED_WARDEN_DROP_CHANCE = 0.10;
    private static final double STANDARD_DROP_CHANCE      = 0.02;

    private final VestigiumEconomy plugin;
    private final Random rng = new Random();

    public TitanBoneManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vetitanbone");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[TitanBoneManager] Initialized — "
                + BoneType.values().length + " bone types.");
    }

    // -------------------------------------------------------------------------
    // Drop handler
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;

        // Named Warden: random bone type at 10%
        if (event.getEntityType() == EntityType.WARDEN
                && event.getEntity().getPersistentDataContainer()
                        .has(NAMED_WARDEN_KEY, PersistentDataType.BYTE)) {
            if (rng.nextDouble() < NAMED_WARDEN_DROP_CHANCE) {
                BoneType bone = BoneType.values()[rng.nextInt(BoneType.values().length)];
                event.getDrops().add(createBone(bone));
            }
            return;
        }

        // Standard mobs: biome-specific
        BoneType bone = getBoneForKill(event);
        if (bone != null && rng.nextDouble() < STANDARD_DROP_CHANCE) {
            event.getDrops().add(createBone(bone));
        }
    }

    // -------------------------------------------------------------------------
    // Command — /vetitanbone
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§7/vetitanbone <list | give <type> [player]>");
            return true;
        }
        switch (args[0].toLowerCase()) {

            case "list" -> {
                sender.sendMessage("§6§l── Titan Bones ──");
                for (BoneType b : BoneType.values())
                    sender.sendMessage("  §f" + b.id() + " §8— §7" + b.displayName()
                            + " §8(§7" + b.sourceBiome() + "§8)");
            }

            case "give" -> {
                if (!sender.hasPermission("vestigium.titanbone.admin")) {
                    sender.sendMessage("§cNo permission."); return true;
                }
                if (args.length < 2) { sender.sendMessage("§7/vetitanbone give <type> [player]"); return true; }
                BoneType type = BoneType.byId(args[1]);
                if (type == null) { sender.sendMessage("§cUnknown bone: §f" + args[1]); return true; }
                Player target = args.length >= 3
                        ? plugin.getServer().getPlayer(args[2])
                        : (sender instanceof Player p ? p : null);
                if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                target.getInventory().addItem(createBone(type));
                sender.sendMessage("§aGave §f" + type.displayName() + " §ato §f" + target.getName() + "§a.");
            }

            default -> sender.sendMessage("§7/vetitanbone <list | give <type> [player]>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Item factory
    // -------------------------------------------------------------------------

    public ItemStack createBone(BoneType type) {
        ItemStack item = new ItemStack(Material.BONE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(type.displayName());
        meta.setCustomModelData(type.cmd());
        meta.setLore(List.of(
                "§7Found in: §f" + type.sourceBiome(),
                "§8A fragment of something impossibly large.",
                "§8Crafting material — properties yet unknown."
        ));
        meta.getPersistentDataContainer().set(BONE_TYPE_KEY, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Biome-to-bone mapping
    // -------------------------------------------------------------------------

    private BoneType getBoneForKill(EntityDeathEvent event) {
        var entity = event.getEntity();
        Biome biome = entity.getLocation().getBlock().getBiome();
        EntityType type = entity.getType();

        if (isOceanBiome(biome) && (type == EntityType.DROWNED || type == EntityType.GUARDIAN || type == EntityType.ELDER_GUARDIAN))
            return BoneType.AQUA;
        if (isDesertBiome(biome) && (type == EntityType.HUSK || type == EntityType.ZOMBIE))
            return BoneType.PALE;
        if (isJungleBiome(biome) && (type == EntityType.OCELOT || type == EntityType.PARROT || type == EntityType.SPIDER))
            return BoneType.VERDANT;
        if (isNetherBiome(biome) && type == EntityType.WITHER_SKELETON)
            return BoneType.CRIMSON;
        if (isBasaltBiome(biome) && type == EntityType.MAGMA_CUBE)
            return BoneType.VOLCANIC;
        if (biome == Biome.THE_END && type == EntityType.ENDERMAN)
            return BoneType.CRYSTAL;
        if (isSavannaBiome(biome) && (type == EntityType.ZOMBIE || type == EntityType.PILLAGER))
            return BoneType.AMBER;

        return null;
    }

    private static boolean isOceanBiome(Biome b) {
        String n = b.name();
        return n.contains("OCEAN") || n.contains("BEACH");
    }

    private static boolean isDesertBiome(Biome b) {
        return b == Biome.DESERT || b == Biome.BADLANDS || b == Biome.ERODED_BADLANDS
                || b == Biome.WOODED_BADLANDS;
    }

    private static boolean isJungleBiome(Biome b) {
        String n = b.name();
        return n.contains("JUNGLE");
    }

    private static boolean isNetherBiome(Biome b) {
        return b == Biome.NETHER_WASTES || b == Biome.CRIMSON_FOREST || b == Biome.WARPED_FOREST;
    }

    private static boolean isBasaltBiome(Biome b) {
        return b == Biome.BASALT_DELTAS || b == Biome.SOUL_SAND_VALLEY;
    }

    private static boolean isSavannaBiome(Biome b) {
        String n = b.name();
        return n.contains("SAVANNA") || n.contains("PLAINS");
    }

    // -------------------------------------------------------------------------
    // Bone catalogue
    // -------------------------------------------------------------------------

    public enum BoneType {
        AMBER("amber",     "§6Amber Titan Bone",    60001, "Savanna / Plains"),
        AQUA("aqua",       "§3Aqua Titan Bone",     60002, "Ocean / Beach"),
        CRIMSON("crimson", "§cCrimson Titan Bone",  60003, "Nether Wastes / Crimson Forest"),
        PALE("pale",       "§fPale Titan Bone",     60004, "Desert / Badlands"),
        VERDANT("verdant", "§aVerdant Titan Bone",  60005, "Jungle"),
        CRYSTAL("crystal", "§bCrystal Titan Bone",  60006, "The End"),
        VOLCANIC("volcanic","§4Volcanic Titan Bone",60007, "Basalt Deltas / Soul Sand Valley");

        private final String id;
        private final String displayName;
        private final int    cmd;
        private final String sourceBiome;

        BoneType(String id, String displayName, int cmd, String sourceBiome) {
            this.id          = id;
            this.displayName = displayName;
            this.cmd         = cmd;
            this.sourceBiome = sourceBiome;
        }

        public String id()          { return id; }
        public String displayName() { return displayName; }
        public int    cmd()         { return cmd; }
        public String sourceBiome() { return sourceBiome; }

        public static BoneType byId(String id) {
            for (BoneType b : values()) if (b.id.equals(id)) return b;
            return null;
        }
    }
}
