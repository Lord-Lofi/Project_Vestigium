package com.vestigium.vestigiummobs.boss;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * World boss: The Sunken God.
 *
 * Awakened through a player ritual — drop a Heart of the Sea while submerged
 * below Y=0 during a thunderstorm. A 30-second channeling countdown follows,
 * then the Warden-form god awakens with a server-wide announcement.
 *
 * Two ability phases (Abyssal Shockwave / Dark Tide) escalate at 50% and 25%
 * HP. A continuous Water Breathing task keeps it viable underwater.
 *
 * Admin spawn: /vcboss spawn sunken_god
 */
public class SunkenGodManager implements Listener {

    private static final NamespacedKey SUNKEN_GOD_KEY =
            new NamespacedKey("vestigium", "sunken_god");

    private static final double MAX_HP          = 800.0;
    private static final int    RITUAL_SECONDS  = 30;
    private static final int    RITUAL_RADIUS_SQ = 50 * 50;
    private static final int    DETECT_RANGE_SQ = 80 * 80;

    private final VestigiumMobs plugin;
    private final Set<UUID>                activeBossIds = new HashSet<>();
    private final Map<UUID, BukkitRunnable> bossTasks    = new HashMap<>();
    private final Map<UUID, BukkitRunnable> breathTasks  = new HashMap<>();
    private final Map<UUID, Boolean>        phase2Done   = new HashMap<>();
    private final Map<UUID, Boolean>        phase3Done   = new HashMap<>();
    private boolean ritualActive = false;

    public SunkenGodManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[SunkenGodManager] Initialized.");
    }

    public void shutdown() {
        bossTasks.values().forEach(BukkitRunnable::cancel);
        breathTasks.values().forEach(BukkitRunnable::cancel);
        bossTasks.clear();
        breathTasks.clear();
    }

    // -------------------------------------------------------------------------
    // Ritual trigger
    // -------------------------------------------------------------------------

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (ritualActive || !activeBossIds.isEmpty()) return;
        if (event.getItemDrop().getItemStack().getType() != Material.HEART_OF_THE_SEA) return;
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        if (!world.isThundering()) return;
        if (!player.isInWater() || player.getLocation().getY() > 0) return;

        event.getItemDrop().remove(); // consume the Heart of the Sea
        startRitual(player.getLocation().clone(), player);
    }

    private void startRitual(Location center, Player initiator) {
        ritualActive = true;
        plugin.getServer().broadcastMessage(
                "§5§l⚠ " + initiator.getName()
                        + " §r§5has begun the ritual of awakening...");

        new BukkitRunnable() {
            int seconds = 0;
            @Override public void run() {
                seconds++;
                if (seconds >= RITUAL_SECONDS) {
                    cancel();
                    ritualActive = false;
                    spawn(center);
                    return;
                }

                Particle particle = seconds < 10 ? Particle.SOUL_FIRE_FLAME
                        : seconds < 20 ? Particle.SOUL : Particle.SCULK_SOUL;
                center.getWorld().spawnParticle(particle, center, 20, 4, 4, 4, 0.05);

                if (seconds % 5 == 0) {
                    float pitch = 0.5f + (seconds / (float) RITUAL_SECONDS) * 0.8f;
                    center.getWorld().playSound(center,
                            Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.5f, pitch);
                    int remaining = RITUAL_SECONDS - seconds;
                    nearbyPlayersAt(center, RITUAL_RADIUS_SQ).forEach(p ->
                            p.sendActionBar("§5The Sunken God stirs... §d" + remaining + "s"));
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // -------------------------------------------------------------------------
    // Spawn
    // -------------------------------------------------------------------------

    public Warden spawn(Location loc) {
        Warden boss = loc.getWorld().spawn(loc, Warden.class, w -> {
            w.setCustomName("§5§lThe Sunken God");
            w.setCustomNameVisible(true);
            w.setPersistent(true);
            w.setRemoveWhenFarAway(false);
            var attr = w.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) { attr.setBaseValue(MAX_HP); w.setHealth(MAX_HP); }
            w.getPersistentDataContainer()
                    .set(SUNKEN_GOD_KEY, PersistentDataType.BYTE, (byte) 1);
        });

        activeBossIds.add(boss.getUniqueId());
        phase2Done.put(boss.getUniqueId(), false);
        phase3Done.put(boss.getUniqueId(), false);

        VestigiumLib.getOmenAPI().addOmen(60);

        String coords = loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
        plugin.getServer().broadcastMessage(
                "§4§l⚠ THE SUNKEN GOD HAS AWAKENED §r§4in "
                        + loc.getWorld().getName() + " [" + coords + "]!");

        startBreathTask(boss);
        startBossTask(boss);
        plugin.getLogger().info("[SunkenGodManager] Sunken God spawned at " + coords);
        return boss;
    }

    // -------------------------------------------------------------------------
    // Boss tasks
    // -------------------------------------------------------------------------

    // Keeps the Warden alive underwater — refreshes Water Breathing every 3s
    private void startBreathTask(Warden boss) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override public void run() {
                if (!boss.isValid() || boss.isDead()) { cancel(); return; }
                boss.addPotionEffect(
                        new PotionEffect(PotionEffectType.WATER_BREATHING, 100, 0, false, false));
            }
        };
        task.runTaskTimer(plugin, 0L, 60L);
        breathTasks.put(boss.getUniqueId(), task);
    }

    private void startBossTask(Warden boss) {
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

                if (t % 200 == 0)              abyssalShockwave(boss); // every 10 s
                if (t % 400 == 0 && t > 0)    darkTide(boss);         // every 20 s
                t++;
            }
        };
        task.runTaskTimer(plugin, 20L, 20L);
        bossTasks.put(boss.getUniqueId(), task);
    }

    // -------------------------------------------------------------------------
    // Abilities
    // -------------------------------------------------------------------------

    private void abyssalShockwave(Warden boss) {
        Location loc = boss.getLocation();
        nearbyPlayers(boss, DETECT_RANGE_SQ).forEach(p -> {
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 200, 1, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 1, false, true));
            p.sendMessage("§5The Sunken God's will presses down on you.");
        });
        loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.4f);
        loc.getWorld().spawnParticle(Particle.SCULK_SOUL, loc, 40, 8, 4, 8, 0.08);
    }

    private void darkTide(Warden boss) {
        Location loc = boss.getLocation();
        nearbyPlayers(boss, DETECT_RANGE_SQ).forEach(p -> {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 200, 0, false, true));
            p.sendMessage("§8The dark tide rises. You cannot see. You cannot flee.");
        });
        loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_ROAR, 2f, 0.3f);
    }

    private void onPhase2(Warden boss) {
        Location loc = boss.getLocation();
        loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_HEARTBEAT, 2f, 0.3f);
        for (int i = 0; i < 5; i++) {
            double a = (i / 5.0) * 2 * Math.PI;
            Location ml = loc.clone().add(Math.cos(a) * 10, 0, Math.sin(a) * 10);
            loc.getWorld().spawn(ml, Drowned.class, d -> {
                d.setCustomName("§5Sunken Acolyte");
                d.setCustomNameVisible(true);
            });
        }
        nearbyPlayers(boss, DETECT_RANGE_SQ).forEach(p ->
                p.sendMessage("§5The Sunken God summons its faithful from the ruins!"));
    }

    private void onPhase3(Warden boss) {
        Location loc = boss.getLocation();
        loc.getWorld().playSound(loc, Sound.ENTITY_WARDEN_DEATH, 2f, 0.5f);
        loc.getWorld().spawnParticle(Particle.SOUL, loc, 200, 15, 8, 15, 0.1);
        nearbyPlayers(boss, DETECT_RANGE_SQ).forEach(p -> {
            p.damage(8.0, boss);
            p.sendMessage("§4§lTHE SUNKEN GOD TEARS THE VEIL. THERE IS NO ESCAPE.");
        });
    }

    // -------------------------------------------------------------------------
    // Death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Warden w)) return;
        if (!w.getPersistentDataContainer().has(SUNKEN_GOD_KEY, PersistentDataType.BYTE)) return;

        UUID id = w.getUniqueId();
        activeBossIds.remove(id);
        phase2Done.remove(id);
        phase3Done.remove(id);
        BukkitRunnable bt = bossTasks.remove(id);
        if (bt != null && !bt.isCancelled()) bt.cancel();
        BukkitRunnable brt = breathTasks.remove(id);
        if (brt != null && !brt.isCancelled()) brt.cancel();

        event.getDrops().clear();
        event.getDrops().add(createSunkenFragment());
        event.getDrops().add(new ItemStack(Material.SPONGE, 8));
        event.getDrops().add(new ItemStack(Material.PRISMARINE, 16));
        event.getDrops().add(new ItemStack(Material.ECHO_SHARD, 3));

        VestigiumLib.getOmenAPI().subtractOmen(30);

        Player killer = w.getKiller();
        String by = killer != null ? "§f" + killer.getName() : "§8the tide";
        plugin.getServer().broadcastMessage(
                "§5The Sunken God has been vanquished by " + by + "§5. The deep grows quiet.");
    }

    private ItemStack createSunkenFragment() {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§5§lFragment of the Sunken God");
        meta.setLore(List.of(
                "§7A shard of divine ruin,",
                "§7pulled from the god that drowned.",
                "§5§oIt still hears the tide."
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

    private List<Player> nearbyPlayersAt(Location loc, double distSq) {
        return loc.getWorld().getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(loc) <= distSq)
                .toList();
    }
}
