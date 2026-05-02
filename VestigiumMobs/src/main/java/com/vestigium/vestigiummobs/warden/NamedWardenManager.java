package com.vestigium.vestigiummobs.warden;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.util.Keys;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages the five Named Wardens — world-boss-tier encounters in ancient cities.
 *
 * Each Named Warden has:
 *   - Unique name and title
 *   - 3–5.5x standard Warden HP
 *   - A unique ability that fires periodically during combat
 *   - A kill counter stored on players (vestigium:warden_kills_{wardenType})
 *   - Rare unique loot drop
 *   - OmenAPI contribution on death
 *
 * Abilities (fire on a per-warden interval while alive):
 *   THE_HARBINGER     — 8s  — Sonic Boom: knockback + 4 damage + Slowness II to all within 20
 *   ECHO_SOVEREIGN    — 10s — Echo Bind: Glowing + Slowness I to all within 15 (5s)
 *   DEEP_ARCHITECT    — 12s — Sculk Surge: Slowness II + Mining Fatigue + sculk placed near target
 *   PALE_LISTENER     — 8s  — Sensory Silence: Blindness + Darkness to all within 12 (4s)
 *   FINAL_CHORUS      — 6s  — Resonance: 6 damage + Levitation flicker to all within 25
 */
public class NamedWardenManager implements Listener {

    private static final NamespacedKey WARDEN_TYPE_KEY =
            new NamespacedKey("vestigium", "named_warden_type");

    public static final String TYPE_HARBINGER      = "the_harbinger";
    public static final String TYPE_ECHO_SOVEREIGN = "echo_sovereign";
    public static final String TYPE_DEEP_ARCHITECT = "deep_architect";
    public static final String TYPE_PALE_LISTENER  = "pale_listener";
    public static final String TYPE_FINAL_CHORUS   = "final_chorus";

    private static final Map<String, WardenProfile> PROFILES = Map.of(
        TYPE_HARBINGER, new WardenProfile(
                "§4The Harbinger", 5.0, 30, 160,
                "§cThe ground shudders as the Harbinger approaches.",
                "§4The Harbinger has fallen. The silence is deafening."),
        TYPE_ECHO_SOVEREIGN, new WardenProfile(
                "§5Echo Sovereign", 4.0, 20, 200,
                "§5Every sound you make is its weapon.",
                "§5The Echo Sovereign is silent at last."),
        TYPE_DEEP_ARCHITECT, new WardenProfile(
                "§9The Deep Architect", 3.5, 15, 240,
                "§9Something ancient stirs in the deep city.",
                "§9The Deep Architect's design is undone."),
        TYPE_PALE_LISTENER, new WardenProfile(
                "§fThe Pale Listener", 3.0, 10, 160,
                "§fIt hears your heartbeat from three cities away.",
                "§fThe Pale Listener hears nothing now."),
        TYPE_FINAL_CHORUS, new WardenProfile(
                "§dThe Final Chorus", 5.5, 50, 120,
                "§dThe deep city sings its last song.",
                "§dThe Final Chorus is complete. You are the only note remaining.")
    );

    private final VestigiumMobs plugin;
    private final Map<UUID, BukkitRunnable> abilityTasks = new HashMap<>();

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

    public void shutdown() {
        abilityTasks.values().forEach(BukkitRunnable::cancel);
        abilityTasks.clear();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Spawns a named warden of the given type at the given location. */
    public Warden spawnNamedWarden(String type, Location loc) {
        WardenProfile profile = PROFILES.get(type);
        if (profile == null) return null;

        Warden warden = (Warden) loc.getWorld().spawnEntity(loc, EntityType.WARDEN);
        warden.setCustomName(profile.displayName());
        warden.setCustomNameVisible(true);

        var hp = warden.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            double newHp = hp.getBaseValue() * profile.hpMultiplier();
            hp.setBaseValue(newHp);
            warden.setHealth(newHp);
        }

        warden.getPersistentDataContainer()
                .set(WARDEN_TYPE_KEY, PersistentDataType.STRING, type);

        loc.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(loc) <= 4096)
                .forEach(p -> p.sendMessage(profile.spawnMessage()));

        VestigiumLib.getOmenAPI().addOmen(profile.omenOnSpawn());
        startAbilityTask(warden, type, profile.abilityIntervalTicks());
        plugin.getLogger().info("Named Warden spawned: " + type + " at " + locationString(loc));
        return warden;
    }

    // -------------------------------------------------------------------------
    // Ability scheduler
    // -------------------------------------------------------------------------

    private void startAbilityTask(Warden warden, String type, int intervalTicks) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override public void run() {
                if (!warden.isValid() || warden.isDead()) {
                    cancel();
                    abilityTasks.remove(warden.getUniqueId());
                    return;
                }
                fireAbility(warden, type);
            }
        };
        task.runTaskTimer(plugin, intervalTicks, intervalTicks);
        abilityTasks.put(warden.getUniqueId(), task);
    }

    private void fireAbility(Warden warden, String type) {
        Location loc = warden.getLocation();

        switch (type) {
            case TYPE_HARBINGER -> {
                // Sonic Boom — knockback + damage + Slowness II within 20 blocks
                loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2f, 0.9f);
                loc.getWorld().spawnParticle(Particle.SONIC_BOOM, loc, 1);
                nearbyPlayers(warden, 400).forEach(p -> {
                    var dir = p.getLocation().subtract(loc).toVector();
                    if (dir.lengthSquared() > 0) dir.normalize().multiply(2.5).setY(0.4);
                    p.setVelocity(dir);
                    p.damage(4.0, warden);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, false, true));
                    p.sendMessage("§cThe ground shudders. The air breaks.");
                });
            }
            case TYPE_ECHO_SOVEREIGN -> {
                // Echo Bind — Glowing + Slowness I within 15 blocks
                loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 0.5f);
                List<Player> targets = nearbyPlayers(warden, 225);
                targets.forEach(p -> {
                    p.setGlowing(true);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 0, false, true));
                    p.sendMessage("§5The sovereign echoes your existence back at you.");
                });
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> targets.forEach(p -> p.setGlowing(false)), 100L);
            }
            case TYPE_DEEP_ARCHITECT -> {
                // Sculk Surge — Slowness II + Mining Fatigue + place sculk near random target
                loc.getWorld().playSound(loc, Sound.BLOCK_SCULK_SPREAD, 1.0f, 0.7f);
                List<Player> targets = nearbyPlayers(warden, 400);
                targets.forEach(p -> {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 1, false, true));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 120, 0, false, true));
                    p.sendMessage("§9The deep city rearranges itself around you.");
                });
                if (!targets.isEmpty()) {
                    Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
                    placeSculkNear(target);
                }
            }
            case TYPE_PALE_LISTENER -> {
                // Sensory Silence — Blindness + Darkness within 12 blocks
                loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_LISTENING_ANGRY, 1.0f, 0.7f);
                nearbyPlayers(warden, 144).forEach(p -> {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, false, true));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0, false, true));
                    p.sendMessage("§fIt has heard you. It has heard everything.");
                });
            }
            case TYPE_FINAL_CHORUS -> {
                // Resonance — 6 damage + Levitation flicker within 25 blocks
                loc.getWorld().playSound(loc, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.0f, 0.6f);
                loc.getWorld().spawnParticle(Particle.SCULK_SOUL, loc, 25, 2.5, 1, 2.5, 0.08);
                nearbyPlayers(warden, 625).forEach(p -> {
                    p.damage(6.0, warden);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 15, 0, false, false));
                    p.sendMessage("§dThe chorus knows your name. It is singing it.");
                });
            }
        }
    }

    private List<Player> nearbyPlayers(Warden warden, double distanceSquared) {
        return warden.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(warden.getLocation()) <= distanceSquared)
                .toList();
    }

    private void placeSculkNear(Player target) {
        Location tLoc = target.getLocation();
        int placed = 0;
        for (int attempts = 0; attempts < 20 && placed < 3; attempts++) {
            int dx = ThreadLocalRandom.current().nextInt(-4, 5);
            int dz = ThreadLocalRandom.current().nextInt(-4, 5);
            Block b = tLoc.clone().add(dx, -1, dz).getBlock();
            if (b.getType().isSolid() && !b.getType().name().contains("SCULK")
                    && !VestigiumLib.getProtectionAPI().isProtected(b.getLocation())) {
                b.setType(Material.SCULK);
                placed++;
            }
        }
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

        // Cancel ability task
        BukkitRunnable task = abilityTasks.remove(warden.getUniqueId());
        if (task != null) task.cancel();

        WardenProfile profile = PROFILES.get(type);
        if (profile == null) return;

        warden.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(warden.getLocation()) <= 65536)
                .forEach(p -> p.sendMessage(profile.deathMessage()));

        Player killer = warden.getKiller();
        if (killer != null) {
            NamespacedKey killKey = Keys.wardenKillsKey(VestigiumLib.getInstance(), type);
            int kills = killer.getPersistentDataContainer()
                    .getOrDefault(killKey, PersistentDataType.INTEGER, 0);
            killer.getPersistentDataContainer()
                    .set(killKey, PersistentDataType.INTEGER, kills + 1);
            killer.sendMessage("§6[" + profile.displayName() + "§6] §7You have felled the "
                    + type + ". Kills: " + (kills + 1));
        }

        VestigiumLib.getOmenAPI().subtractOmen(profile.omenOnSpawn() / 2);

        switch (type) {
            case TYPE_HARBINGER      -> event.getDrops().add(new org.bukkit.inventory.ItemStack(Material.NETHER_STAR));
            case TYPE_ECHO_SOVEREIGN -> event.getDrops().add(new org.bukkit.inventory.ItemStack(Material.ECHO_SHARD, 3));
            case TYPE_DEEP_ARCHITECT -> event.getDrops().add(new org.bukkit.inventory.ItemStack(Material.SCULK_CATALYST));
            case TYPE_PALE_LISTENER  -> event.getDrops().add(new org.bukkit.inventory.ItemStack(Material.AMETHYST_SHARD, 5));
            case TYPE_FINAL_CHORUS   -> event.getDrops().add(new org.bukkit.inventory.ItemStack(Material.DRAGON_EGG));
        }
    }

    // -------------------------------------------------------------------------

    private static String locationString(Location loc) {
        return loc.getWorld().getName() + " "
                + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private record WardenProfile(String displayName, double hpMultiplier,
                                  int omenOnSpawn, int abilityIntervalTicks,
                                  String spawnMessage, String deathMessage) {}
}
