package com.vestigium.vestigiumnether.storm;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumnether.VestigiumNether;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Soul storm events for players in the soul sand valley biome.
 *
 * Evaluated every 80 ticks (4 seconds).
 *   omen 500+  — soul particle surges, whisper messages, occasional levitation flicker
 *   omen 700+  — more intense levitation + Blindness pulses + "the lost are no longer quiet"
 *
 * Soul sand valley detection uses biome key "soul_sand_valley".
 */
public class SoulStormManager {

    private static final long CHECK_TICKS = 80L;

    private static final String[] WHISPER_MESSAGES_LOW = {
        "§5§oYou hear something that was once a name.",
        "§5§oThe soul sand exhales beneath your feet.",
        "§5§oSomething reaches up through the ground.",
        "§5§oThe valley remembers everyone who crossed it."
    };

    private static final String[] WHISPER_MESSAGES_HIGH = {
        "§4§oThe lost are no longer quiet.",
        "§4§oThey know you are here. They remember being alive.",
        "§4§oThe valley is full and it wants more.",
        "§4§oYou should not have lingered."
    };

    private final VestigiumNether plugin;
    private final Set<String> stormActive = new HashSet<>();
    private BukkitRunnable task;

    public SoulStormManager(VestigiumNether plugin) {
        this.plugin = plugin;
    }

    public void init() {
        task = new BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.NETHER) return;
                    int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
                    world.getPlayers().forEach(p -> applyForPlayer(p, omen));
                });
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("[SoulStormManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    private void applyForPlayer(Player player, int omen) {
        String biome = player.getLocation().getBlock().getBiome().getKey().getKey();
        if (!biome.equals("soul_sand_valley")) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String key = player.getUniqueId() + ":soul_storm";

        if (omen >= 500) {
            // Activate storm for this player if not already
            if (stormActive.add(key) ) {
                player.sendMessage("§5The valley shifts. Something wakes beneath the blue flame.");
            }

            // Soul particle surge (via world.spawnParticle — these are local and targeted)
            player.getWorld().spawnParticle(
                    org.bukkit.Particle.SOUL, player.getLocation(), 6, 1.5, 0.5, 1.5, 0.02);

            if (rng.nextInt(6) == 0) {
                String msg = WHISPER_MESSAGES_LOW[rng.nextInt(WHISPER_MESSAGES_LOW.length)];
                player.sendMessage(msg);
                player.playSound(player.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 0.4f, 0.8f);
            }

            // Levitation flicker
            if (rng.nextInt(10) == 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 4, 0, false, false));
            }
        } else {
            if (stormActive.remove(key)) {
                player.sendMessage("§7The valley quiets. For now.");
            }
        }

        if (omen >= 700) {
            if (rng.nextInt(4) == 0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 8, 0, false, false));
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, false));
            }
            if (rng.nextInt(8) == 0) {
                String msg = WHISPER_MESSAGES_HIGH[rng.nextInt(WHISPER_MESSAGES_HIGH.length)];
                player.sendMessage(msg);
                player.playSound(player.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_ADDITIONS, 0.5f, 0.6f);
            }
        }
    }
}
