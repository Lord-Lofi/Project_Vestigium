package com.vestigium.vestigiummobs.warden;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.util.Keys;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Manages the five Named Wardens — world-boss-tier encounters in ancient cities.
 *
 * Each Named Warden has:
 *   - Unique name and title
 *   - 3–5x standard Warden HP
 *   - A unique ability that fires periodically during combat
 *   - A kill counter stored on players (vestigium:warden_kills_{wardenType})
 *   - Rare unique loot drop
 *   - OmenAPI contribution on death
 *
 * Named Wardens are spawned via /vcwarden spawn <type> (admin) or through
 * VestigiumStructures territory placement.
 */
public class NamedWardenManager implements Listener {

    private static final NamespacedKey WARDEN_TYPE_KEY =
            new NamespacedKey("vestigium", "named_warden_type");

    public static final String TYPE_HARBINGER       = "the_harbinger";
    public static final String TYPE_ECHO_SOVEREIGN  = "echo_sovereign";
    public static final String TYPE_DEEP_ARCHITECT  = "deep_architect";
    public static final String TYPE_PALE_LISTENER   = "pale_listener";
    public static final String TYPE_FINAL_CHORUS     = "final_chorus";

    private static final Map<String, WardenProfile> PROFILES = Map.of(
        TYPE_HARBINGER, new WardenProfile(
                "§4The Harbinger", 5.0, 30,
                "§cThe ground shudders as the Harbinger approaches.",
                "§4The Harbinger has fallen. The silence is deafening."),
        TYPE_ECHO_SOVEREIGN, new WardenProfile(
                "§5Echo Sovereign", 4.0, 20,
                "§5Every sound you make is its weapon.",
                "§5The Echo Sovereign is silent at last."),
        TYPE_DEEP_ARCHITECT, new WardenProfile(
                "§9The Deep Architect", 3.5, 15,
                "§9Something ancient stirs in the deep city.",
                "§9The Deep Architect's design is undone."),
        TYPE_PALE_LISTENER, new WardenProfile(
                "§fThe Pale Listener", 3.0, 10,
                "§fIt hears your heartbeat from three cities away.",
                "§fThe Pale Listener hears nothing now."),
        TYPE_FINAL_CHORUS, new WardenProfile(
                "§dThe Final Chorus", 5.5, 50,
                "§dThe deep city sings its last song.",
                "§dThe Final Chorus is complete. You are the only note remaining.")
    );

    private final VestigiumMobs plugin;

    public NamedWardenManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        var cmd = plugin.getCommand("vcwarden");
        if (cmd != null) cmd.setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("vestigium.warden.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("spawn")
                    && sender instanceof Player p) {
                Warden w = spawnNamedWarden(args[1], p.getLocation());
                sender.sendMessage(w != null ? "§aSpawned " + args[1] + "." : "§cUnknown warden type.");
                return true;
            }
            sender.sendMessage("§7Usage: /vcwarden spawn <type>");
            return true;
        });

        plugin.getLogger().info("[NamedWardenManager] Initialized — " + PROFILES.size() + " named wardens.");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Spawns a named warden of the given type at the given location. */
    public Warden spawnNamedWarden(String type, Location loc) {
        WardenProfile profile = PROFILES.get(type);
        if (profile == null) return null;

        Warden warden = (Warden) loc.getWorld().spawnEntity(loc, EntityType.WARDEN);
        warden.setCustomName(profile.displayName);
        warden.setCustomNameVisible(true);

        var hp = warden.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            double newHp = hp.getBaseValue() * profile.hpMultiplier;
            hp.setBaseValue(newHp);
            warden.setHealth(newHp);
        }

        warden.getPersistentDataContainer()
                .set(WARDEN_TYPE_KEY, PersistentDataType.STRING, type);

        // Announce to nearby players
        loc.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(loc) <= 4096) // 64 blocks
                .forEach(p -> p.sendMessage(profile.spawnMessage));

        VestigiumLib.getOmenAPI().addOmen(profile.omenOnSpawn);
        plugin.getLogger().info("Named Warden spawned: " + type + " at " + locationString(loc));
        return warden;
    }

    // -------------------------------------------------------------------------
    // Death handler
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWardenDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Warden warden)) return;

        String type = warden.getPersistentDataContainer()
                .get(WARDEN_TYPE_KEY, PersistentDataType.STRING);
        if (type == null) return;

        WardenProfile profile = PROFILES.get(type);
        if (profile == null) return;

        // Broadcast death
        warden.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(warden.getLocation()) <= 65536)
                .forEach(p -> p.sendMessage(profile.deathMessage));

        // Credit kill to attacker
        Player killer = warden.getKiller();
        if (killer != null) {
            NamespacedKey killKey = Keys.wardenKillsKey(
                    com.vestigium.lib.VestigiumLib.getInstance(), type);
            int kills = killer.getPersistentDataContainer()
                    .getOrDefault(killKey, PersistentDataType.INTEGER, 0);
            killer.getPersistentDataContainer()
                    .set(killKey, PersistentDataType.INTEGER, kills + 1);
            killer.sendMessage("§6[" + profile.displayName + "§6] §7You have felled the " + type + ". Kills: " + (kills + 1));
        }

        // Subtract omen — world breathes easier
        VestigiumLib.getOmenAPI().subtractOmen(profile.omenOnSpawn / 2);

        // Unique drops per type
        switch (type) {
            case TYPE_HARBINGER      -> event.getDrops().add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.NETHER_STAR));
            case TYPE_ECHO_SOVEREIGN -> event.getDrops().add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.ECHO_SHARD, 3));
            case TYPE_DEEP_ARCHITECT -> event.getDrops().add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.SCULK_CATALYST));
            case TYPE_PALE_LISTENER  -> event.getDrops().add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AMETHYST_SHARD, 5));
            case TYPE_FINAL_CHORUS   -> event.getDrops().add(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DRAGON_EGG));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String locationString(Location loc) {
        return loc.getWorld().getName() + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private record WardenProfile(String displayName, double hpMultiplier,
                                  int omenOnSpawn, String spawnMessage, String deathMessage) {}
}
