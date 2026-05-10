package com.vestigium.vestigiummobs.passive;

import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shadow Fauna — nocturnal monochrome variants of passive mobs.
 *
 * At night (game time 13 000–23 000) there is an 8% chance that a naturally
 * spawning Cow, Pig, Sheep, Horse, Rabbit, or Llama becomes a Shadow Fauna.
 * Tagged entities are named "§8Shadow [Type]", stored in a tracked set, and
 * checked every second:
 *
 *   - Any player within 16 blocks whose surrounding block light level > 7
 *     causes the fauna to flee (velocity push away from player).
 *   - At sunrise the entity despawns with a SMOKE particle burst.
 *
 * Shadow Fauna have no drops and do not count toward kill achievements.
 */
public class ShadowFaunaManager implements Listener {

    private static final NamespacedKey SHADOW_KEY =
            new NamespacedKey("vestigium", "shadow_fauna");

    private static final int    SPAWN_CHANCE  = 8;   // 8%
    private static final int    FLEE_RANGE_SQ = 16 * 16;
    private static final int    LIGHT_THRESHOLD = 7;
    private static final long   NIGHT_START   = 13_000L;
    private static final long   NIGHT_END     = 23_000L;

    private static final Set<EntityType> ELIGIBLE = Set.of(
            EntityType.COW, EntityType.PIG, EntityType.SHEEP,
            EntityType.HORSE, EntityType.RABBIT, EntityType.LLAMA
    );

    private final VestigiumMobs plugin;
    private final Set<UUID> trackedIds = new HashSet<>();
    private BukkitRunnable task;

    public ShadowFaunaManager(VestigiumMobs plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startBehaviourTask();
        plugin.getLogger().info("[ShadowFaunaManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------
    // Spawn hook
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (!ELIGIBLE.contains(event.getEntityType())) return;

        World world = event.getEntity().getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return;
        long time = world.getTime();
        if (time < NIGHT_START || time > NIGHT_END) return;

        if (ThreadLocalRandom.current().nextInt(100) >= SPAWN_CHANCE) return;

        Mob mob = (Mob) event.getEntity();
        mob.getPersistentDataContainer()
                .set(SHADOW_KEY, PersistentDataType.BYTE, (byte) 1);
        mob.setCustomName("§8Shadow " + formatType(event.getEntityType()));
        mob.setCustomNameVisible(false); // name only visible close up
        trackedIds.add(mob.getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Behaviour task
    // -------------------------------------------------------------------------

    private void startBehaviourTask() {
        task = new BukkitRunnable() {
            @Override public void run() {
                Set<UUID> dead = new HashSet<>();
                for (UUID id : trackedIds) {
                    Entity e = plugin.getServer().getEntity(id);
                    if (e == null || !e.isValid() || e.isDead()) { dead.add(id); continue; }
                    if (!(e instanceof Mob mob)) { dead.add(id); continue; }

                    long time = mob.getWorld().getTime();
                    boolean isNight = time >= NIGHT_START && time <= NIGHT_END;
                    if (!isNight) {
                        despawn(mob);
                        dead.add(id);
                        continue;
                    }

                    // Flee any nearby player with high block-light
                    Player threat = nearestLitPlayer(mob);
                    if (threat != null) flee(mob, threat);
                }
                trackedIds.removeAll(dead);
            }
        };
        task.runTaskTimer(plugin, 20L, 20L);
    }

    private Player nearestLitPlayer(Mob mob) {
        for (Entity e : mob.getNearbyEntities(16, 8, 16)) {
            if (!(e instanceof Player player)) continue;
            if (player.getLocation().distanceSquared(mob.getLocation()) > FLEE_RANGE_SQ) continue;
            int light = player.getLocation().getBlock().getLightFromBlocks();
            if (light > LIGHT_THRESHOLD) return player;
        }
        return null;
    }

    private void flee(Mob mob, Player from) {
        Vector dir = mob.getLocation().toVector()
                .subtract(from.getLocation().toVector());
        if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
        dir.normalize().multiply(0.5).setY(0.1);
        mob.setVelocity(dir);
    }

    private void despawn(Mob mob) {
        mob.getWorld().spawnParticle(
                Particle.SMOKE, mob.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
        mob.getWorld().playSound(mob.getLocation(),
                Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.8f);
        mob.remove();
    }

    private static String formatType(EntityType type) {
        String name = type.name().replace('_', ' ').toLowerCase();
        String[] parts = name.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
