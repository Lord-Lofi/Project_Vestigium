package com.vestigium.vestigiumend.echo;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumend.VestigiumEnd;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dragon echo events — periodic echoes of the Ender Dragon after its first death.
 *
 * Echo tick interval: 12 000 ticks (10 minutes).
 *
 * Base echo (dragon previously killed):
 *   Dragon roar sound, DRAGON_BREATH particle burst at world spawn, +2 omen,
 *   flavor message to all End players.
 *
 * Intense echo (omen > 400):
 *   Same as base + Blindness 3s to all End players + larger particle burst + +5 omen.
 *
 * Tracks which End worlds have seen the dragon die via world name set.
 * Also listens to EntityDeathEvent for EnderDragon to detect first kill.
 */
public class DragonEchoManager implements Listener {

    private static final long ECHO_INTERVAL_TICKS = 12_000L;

    private static final String[] ECHO_MESSAGES = {
        "§5Something vast and hollow exhales across the End.",
        "§5The sky trembles. The dragon is gone but not forgotten.",
        "§5An echo of something enormous passes through the island.",
        "§5You feel the wings in the wind even though they are not there."
    };

    private static final String[] INTENSE_MESSAGES = {
        "§4The echo is wrong. It is not remembering — it is returning.",
        "§4The omen calls to the void. The void answers with wings.",
        "§4The dragon does not die. It only waits."
    };

    private final VestigiumEnd plugin;
    private final Set<String> dragonKilledWorlds = new HashSet<>();
    private BukkitRunnable task;

    public DragonEchoManager(VestigiumEnd plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Seed worlds where the dragon was already killed before this boot
        plugin.getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.THE_END)
                .filter(w -> w.getEnderDragonBattle() != null
                          && w.getEnderDragonBattle().hasBeenPreviouslyKilled())
                .forEach(w -> dragonKilledWorlds.add(w.getName()));

        task = new BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getWorlds().stream()
                        .filter(w -> w.getEnvironment() == World.Environment.THE_END)
                        .filter(w -> dragonKilledWorlds.contains(w.getName()))
                        .forEach(DragonEchoManager.this::fireEcho);
            }
        };
        task.runTaskTimer(plugin, ECHO_INTERVAL_TICKS, ECHO_INTERVAL_TICKS);

        plugin.getLogger().info("[DragonEchoManager] Initialized. Dragon-killed worlds: "
                + dragonKilledWorlds.size());
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon)) return;
        dragonKilledWorlds.add(event.getEntity().getWorld().getName());
        event.getEntity().getWorld().getPlayers().forEach(p ->
                p.sendMessage("§5The dragon falls. The End remembers."));
    }

    // -------------------------------------------------------------------------

    private void fireEcho(World world) {
        if (world.getPlayers().isEmpty()) return;

        int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        boolean intense = omen > 400;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Sound
        world.playSound(world.getSpawnLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL,
                intense ? 1.2f : 0.7f, 0.6f);

        // Particles at world spawn
        world.spawnParticle(Particle.DRAGON_BREATH, world.getSpawnLocation(),
                intense ? 60 : 25, 3, 2, 3, 0.05);

        // Omen
        VestigiumLib.getOmenAPI().addOmen(intense ? 5 : 2);

        // Message
        String msg = intense
                ? INTENSE_MESSAGES[rng.nextInt(INTENSE_MESSAGES.length)]
                : ECHO_MESSAGES[rng.nextInt(ECHO_MESSAGES.length)];
        world.getPlayers().forEach(p -> p.sendMessage(msg));

        if (intense) {
            world.getPlayers().forEach(p ->
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0)));
        }
    }
}
