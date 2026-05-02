package com.vestigium.vestigiumend.corruption;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.FactionCollapseEvent;
import com.vestigium.vestigiumend.VestigiumEnd;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Void corruption system for End players at high omen.
 *
 * Corruption exposure accumulates while a player is in the End with omen > 700.
 * It decays when they leave the End or omen drops below 700.
 * A FactionCollapseEvent surges corruption for all active End players.
 *
 * Corruption tiers (tracked per player, evaluated every 80 ticks):
 *   1–10  : Wither I pulses + flavor messages
 *   11–25 : Wither II + Blindness pulses
 *   26+   : Wither II + Blindness + Slowness + "you are becoming part of the void" message
 *
 * Exposure resets when the player dies or leaves the End.
 */
public class VoidCorruptionManager {

    private static final long CHECK_TICKS     = 80L;
    private static final int  OMEN_THRESHOLD  = 700;

    private static final String[] TIER1_MESSAGES = {
        "§8Something in the void presses against your skin.",
        "§8The end-stone hums at a frequency you feel in your teeth.",
        "§8You are not supposed to be here this long."
    };

    private static final String[] TIER3_MESSAGES = {
        "§4You are becoming part of the void.",
        "§4The corruption remembers you. You are starting to forget yourself.",
        "§4The end does not want you to leave."
    };

    private final VestigiumEnd plugin;
    private final Map<UUID, Integer> corruptionLevel = new HashMap<>();
    private BukkitRunnable task;

    public VoidCorruptionManager(VestigiumEnd plugin) {
        this.plugin = plugin;
    }

    public void init() {
        VestigiumLib.getEventBus().subscribe(FactionCollapseEvent.class, e -> onFactionCollapse());

        var cmd = plugin.getCommand("veend");
        if (cmd != null) cmd.setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("vestigium.end.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            // /veend corruption clear <player>  OR  /veend corruption status <player>
            if (args.length >= 3 && args[0].equalsIgnoreCase("corruption")) {
                org.bukkit.entity.Player target =
                        plugin.getServer().getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found.");
                    return true;
                }
                if (args[1].equalsIgnoreCase("clear")) {
                    clearCorruption(target);
                    sender.sendMessage("§aCleared void corruption for " + target.getName() + ".");
                    target.sendMessage("§7Your void corruption has been cleared by an administrator.");
                    return true;
                }
                if (args[1].equalsIgnoreCase("status")) {
                    int level = getCorruptionLevel(target);
                    String tier = level == 0 ? "§anone" : level <= 10 ? "§eI" : level <= 25 ? "§6II" : "§4III";
                    sender.sendMessage("§7" + target.getName() + " corruption: " + tier + " §8(level " + level + ")");
                    return true;
                }
            }
            sender.sendMessage("§7Usage: /veend corruption <clear|status> <player>");
            return true;
        });

        task = new BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != World.Environment.THE_END) return;
                    int omen = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
                    world.getPlayers().forEach(p -> tick(p, omen));
                });
                // Decay corruption for players no longer in the End
                plugin.getServer().getOnlinePlayers().stream()
                        .filter(p -> p.getWorld().getEnvironment() != World.Environment.THE_END)
                        .forEach(p -> corruptionLevel.remove(p.getUniqueId()));
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);

        plugin.getLogger().info("[VoidCorruptionManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    private void tick(Player player, int omen) {
        if (omen < OMEN_THRESHOLD) {
            corruptionLevel.remove(player.getUniqueId());
            return;
        }

        int level = corruptionLevel.merge(player.getUniqueId(), 1, Integer::sum);
        applyTier(player, level);
    }

    private void applyTier(Player player, int level) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (level >= 1 && level <= 10) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 120, 0, false, false));
            if (rng.nextInt(4) == 0) {
                player.sendMessage(TIER1_MESSAGES[rng.nextInt(TIER1_MESSAGES.length)]);
            }
        } else if (level <= 25) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 120, 1, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0, false, false));
        } else {
            player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 120, 1, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 1, false, false));
            if (rng.nextInt(6) == 0) {
                player.sendMessage(TIER3_MESSAGES[rng.nextInt(TIER3_MESSAGES.length)]);
            }
        }
    }

    private void onFactionCollapse() {
        plugin.getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.THE_END)
                .flatMap(w -> w.getPlayers().stream())
                .forEach(p -> {
                    corruptionLevel.merge(p.getUniqueId(), 15, Integer::sum);
                    p.sendMessage("§4A faction has fallen. The void surges.");
                });
    }

    public void clearCorruption(Player player) {
        corruptionLevel.remove(player.getUniqueId());
    }

    public int getCorruptionLevel(Player player) {
        return corruptionLevel.getOrDefault(player.getUniqueId(), 0);
    }
}
