package com.vestigium.vestigiummobs.boss;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Biome;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * World boss: The Leviathan.
 *
 * Spawns naturally when a player is in a deep-ocean biome below Y=30 during
 * a thunderstorm, subject to a 30-minute cooldown and a single-instance cap.
 * Three ability phases — Tidal Surge, Abyssal Ink, and Vortex Pull — escalate
 * as HP drops through 50% and 25% thresholds.
 *
 * Admin spawn: /vcboss spawn leviathan
 */
public class LeviathanManager implements Listener {

    private static final NamespacedKey LEVIATHAN_KEY =
            new NamespacedKey("vestigium", "leviathan");

    private static final double MAX_HP         = 500.0;
    private static final long   COOLDOWN_MS    = 30 * 60 * 1000L;
    private static final int    DETECT_RANGE   = 80;
    private static final int    DETECT_RANGE_SQ = DETECT_RANGE * DETECT_RANGE;

    private final VestigiumMobs plugin;
    private final Set<UUID>               activeBossIds = new HashSet<>();
    private final Map<UUID, BukkitRunnable> bossTasks   = new HashMap<>();
    private final Map<UUID, Boolean>       phase2Done   = new HashMap<>();
    private final Map<UUID, Boolean>       phase3Done   = new HashMap<>();
    private long lastSpawnMs = 0L;

    public LeviathanManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startSpawnCheck();
        plugin.getLogger().info("[LeviathanManager] Initialized.");
    }

    public void shutdown() {
        bossTasks.values().forEach(BukkitRunnable::cancel);
        bossTasks.clear();
    }

    // -------------------------------------------------------------------------
    // Natural spawn detection
    // -------------------------------------------------------------------------

    private void startSpawnCheck() {
        new BukkitRunnable() {
            @Override public void run() {
                if (!activeBossIds.isEmpty()) return;
                if (System.currentTimeMillis() - lastSpawnMs < COOLDOWN_MS) return;
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (canSpawnNear(p)) { spawn(p.getLocation()); return; }
                }
            }
        }.runTaskTimer(plugin, 200L, 200L);
    }

    private boolean canSpawnNear(Player player) {
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return false;
        if (!world.isThundering()) return false;
        if (player.getLocation().getY() > 30) return false;
        Biome b = world.getBiome(player.getLocation());
        return b == Biome.DEEP_OCEAN || b == Biome.DEEP_COLD_OCEAN
                || b == Biome.DEEP_FROZEN_OCEAN || b == Biome.DEEP_LUKEWARM_OCEAN
                || b == Biome.WARM_OCEAN || b == Biome.OCEAN;
    }

    // -------------------------------------------------------------------------
    // Spawn
    // -------------------------------------------------------------------------

    public ElderGuardian spawn(Location near) {
        double angle = Math.random() * 2 * Math.PI;
        double dist  = 20 + Math.random() * 10;
        Location loc = near.clone().add(Math.cos(angle) * dist, 0, Math.sin(angle) * dist);

        ElderGuardian boss = loc.getWorld().spawn(loc, ElderGuardian.class, eg -> {
            eg.setCustomName("§3§lThe Leviathan");
            eg.setCustomNameVisible(true);
            eg.setPersistent(true);
            eg.setRemoveWhenFarAway(false);
            var attr = eg.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) { attr.setBaseValue(MAX_HP); eg.setHealth(MAX_HP); }
            eg.getPersistentDataContainer()
                    .set(LEVIATHAN_KEY, PersistentDataType.BYTE, (byte) 1);
        });

        activeBossIds.add(boss.getUniqueId());
        phase2Done.put(boss.getUniqueId(), false);
        phase3Done.put(boss.getUniqueId(), false);
        lastSpawnMs = System.currentTimeMillis();

        VestigiumLib.getOmenAPI().addOmen(40);

        String coords = loc.getBlockX() + ", " + loc.getBlockZ();
        plugin.getServer().broadcastMessage(
                "§3§l⚡ THE LEVIATHAN RISES §r§3in "
                        + loc.getWorld().getName() + " [" + coords + "]!");

        startBossTask(boss);
        plugin.getLogger().info("[LeviathanManager] Leviathan spawned at " + coords);
        return boss;
    }

    // -------------------------------------------------------------------------
    // Boss task
    // -------------------------------------------------------------------------

    private void startBossTask(ElderGuardian boss) {
        BukkitRunnable task = new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!boss.isValid() || boss.isDead()) {
                    cancel();
                    bossTasks.remove(boss.getUniqueId());
                    return;
                }
                UUID id = boss.getUniqueId();
                var maxAttr = boss.getAttribute(Attribute.MAX_HEALTH);
                double maxHp = maxAttr != null ? maxAttr.getValue() : MAX_HP;
                double pct = boss.getHealth() / maxHp;

                if (pct <= 0.5 && !phase2Done.getOrDefault(id, true)) {
                    phase2Done.put(id, true);
                    onPhase2(boss);
                }
                if (pct <= 0.25 && !phase3Done.getOrDefault(id, true)) {
                    phase3Done.put(id, true);
                    onPhase3(boss);
                }

                if (t % 100 == 0)                            tidalSurge(boss);  // every 5 s
                if (t % 300 == 0 && t > 0)                  abyssalInk(boss);  // every 15 s
                if (phase3Done.getOrDefault(id, false)
                        && t % 400 == 0 && t > 0)            vortexPull(boss);  // every 20 s in p3
                t++;
            }
        };
        task.runTaskTimer(plugin, 20L, 20L);
        bossTasks.put(boss.getUniqueId(), task);
    }

    // -------------------------------------------------------------------------
    // Abilities
    // -------------------------------------------------------------------------

    private void tidalSurge(ElderGuardian boss) {
        Location loc = boss.getLocation();
        nearbyPlayers(boss, DETECT_RANGE_SQ).forEach(p -> {
            Vector dir = p.getLocation().toVector().subtract(loc.toVector());
            if (dir.lengthSquared() > 0) dir.normalize().multiply(2.0).setY(0.4);
            p.setVelocity(dir);
            p.sendActionBar("§3The Leviathan's tidal surge hurls you back!");
        });
        loc.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, loc, 80, 5, 2, 5, 0.3);
        loc.getWorld().playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1.5f, 0.6f);
    }

    private void abyssalInk(ElderGuardian boss) {
        Location loc = boss.getLocation();
        nearbyPlayers(boss, 400).forEach(p -> { // 20-block radius
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1, false, true));
            p.sendMessage("§8The abyss swallows the light around you.");
        });
        loc.getWorld().spawnParticle(Particle.SQUID_INK, loc, 60, 6, 3, 6, 0.1);
        loc.getWorld().playSound(loc, Sound.ENTITY_SQUID_SQUIRT, 2f, 0.5f);
    }

    private void vortexPull(ElderGuardian boss) {
        Location loc = boss.getLocation();
        nearbyPlayers(boss, DETECT_RANGE_SQ).forEach(p -> {
            Vector pull = loc.toVector().subtract(p.getLocation().toVector());
            if (pull.lengthSquared() > 0) pull.normalize().multiply(2.5);
            pull.setY(0.1);
            p.setVelocity(pull);
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 1, false, true));
            p.sendActionBar("§4The vortex pulls you into the deep!");
        });
        loc.getWorld().playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_FLOP, 2f, 0.4f);
    }

    private void onPhase2(ElderGuardian boss) {
        Location loc = boss.getLocation();
        loc.getWorld().playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 2f, 0.7f);
        for (int i = 0; i < 3; i++) {
            double a = (i / 3.0) * 2 * Math.PI;
            Location ml = loc.clone().add(Math.cos(a) * 8, 0, Math.sin(a) * 8);
            loc.getWorld().spawn(ml, Drowned.class, d -> {
                d.setCustomName("§3Tide Servant");
                d.setCustomNameVisible(true);
            });
        }
        nearbyPlayers(boss, DETECT_RANGE_SQ).forEach(p ->
                p.sendMessage("§3The Leviathan calls its tide servants from the deep!"));
    }

    private void onPhase3(ElderGuardian boss) {
        Location loc = boss.getLocation();
        loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_ROAR, 2f, 0.5f);
        loc.getWorld().spawnParticle(Particle.SOUL, loc, 100, 10, 5, 10, 0.2);
        nearbyPlayers(boss, DETECT_RANGE_SQ).forEach(p ->
                p.sendMessage("§4§lTHE LEVIATHAN IS ENRAGED. THE DEEP DEMANDS TRIBUTE."));
    }

    // -------------------------------------------------------------------------
    // Death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof ElderGuardian eg)) return;
        if (!eg.getPersistentDataContainer().has(LEVIATHAN_KEY, PersistentDataType.BYTE)) return;

        UUID id = eg.getUniqueId();
        activeBossIds.remove(id);
        phase2Done.remove(id);
        phase3Done.remove(id);
        BukkitRunnable task = bossTasks.remove(id);
        if (task != null && !task.isCancelled()) task.cancel();

        event.getDrops().clear();
        event.getDrops().add(createLeviathanEye());
        event.getDrops().add(new ItemStack(Material.NAUTILUS_SHELL, 8));
        event.getDrops().add(new ItemStack(Material.PRISMARINE_SHARD, 16));
        event.getDrops().add(new ItemStack(Material.PRISMARINE_CRYSTALS, 8));

        VestigiumLib.getOmenAPI().subtractOmen(20);

        Player killer = eg.getKiller();
        String by = killer != null ? "§f" + killer.getName() : "§8the deep";
        plugin.getServer().broadcastMessage(
                "§3⚡ The Leviathan has been slain by " + by + "§3.");
    }

    private ItemStack createLeviathanEye() {
        ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§3§lLeviathan's Eye");
        meta.setLore(List.of(
                "§7An eye torn from the Leviathan,",
                "§7still pulsing with tidal fury.",
                "§3§oThe deep remembers."
        ));
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------

    private List<Player> nearbyPlayers(Entity entity, double distSq) {
        return entity.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(entity.getLocation()) <= distSq)
                .toList();
    }
}
