package com.vestigium.vestigiumeconomy.specialitems;

import com.vestigium.vestigiumeconomy.VestigiumEconomy;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.event.Listener;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Random;

/**
 * Resonant Artifacts — passive items that amplify sculk bloom while held in an
 * Ancient City (DEEP_DARK biome). While a player holds one, every 10 seconds
 * there is an additional 25% chance of a sculk bloom particle burst + sculk sound.
 *
 * This is implemented independently (does not patch AncientCityEventManager)
 * by running its own sculk-bloom equivalent directly.
 *
 * Item: ECHO_SHARD, CMD 60013.
 * PDC: vestigium:resonant_artifact BYTE on the item.
 * PDC: vestigium:resonant_artifact_held BYTE on the player (set while held, cleared when not).
 *   AncientCityEventManager may check this key in the future to boost its own bloom rate.
 */
public class ResonantArtifactManager implements Listener, CommandExecutor {

    private static final NamespacedKey ARTIFACT_KEY    = new NamespacedKey("vestigium", "resonant_artifact");
    static final NamespacedKey         HELD_FLAG_KEY   = new NamespacedKey("vestigium", "resonant_artifact_held");

    private static final double BLOOM_EXTRA_CHANCE = 0.25;
    private static final int    CHECK_TICKS        = 200; // every 10 seconds

    private final VestigiumEconomy plugin;
    private final Random rng = new Random();
    private BukkitRunnable task;

    public ResonantArtifactManager(VestigiumEconomy plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("veresonant");
        if (cmd != null) cmd.setExecutor(this);

        task = new BukkitRunnable() {
            @Override public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    tick(player);
                }
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);
        plugin.getLogger().info("[ResonantArtifactManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------
    // Periodic check
    // -------------------------------------------------------------------------

    private void tick(Player player) {
        boolean held = isHoldingArtifact(player);

        if (held) {
            player.getPersistentDataContainer().set(HELD_FLAG_KEY, PersistentDataType.BYTE, (byte) 1);
        } else {
            player.getPersistentDataContainer().remove(HELD_FLAG_KEY);
            return;
        }

        if (player.getLocation().getBlock().getBiome() != Biome.DEEP_DARK) return;
        if (rng.nextDouble() >= BLOOM_EXTRA_CHANCE) return;

        // Extra sculk bloom
        Location loc = player.getLocation();
        player.getWorld().spawnParticle(Particle.SCULK_SOUL, loc.add(0, 0.5, 0),
                8, 1.5, 0.5, 1.5, 0.01);
        player.getWorld().playSound(loc, Sound.BLOCK_SCULK_SENSOR_CLICKING, 0.3f, 0.7f);
        player.sendActionBar("§9◈ §8The sculk stirs...");
    }

    // -------------------------------------------------------------------------
    // Command — /veresonant
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sender.sendMessage("§7/veresonant give [player]"); return true; }
        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("vestigium.resonant.admin")) {
                sender.sendMessage("§cNo permission."); return true;
            }
            Player target = args.length >= 2
                    ? plugin.getServer().getPlayer(args[1])
                    : (sender instanceof Player p ? p : null);
            if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
            target.getInventory().addItem(createArtifact());
            sender.sendMessage("§aGave Resonant Artifact to §f" + target.getName() + "§a.");
        } else {
            sender.sendMessage("§7/veresonant give [player]");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Item factory
    // -------------------------------------------------------------------------

    public ItemStack createArtifact() {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§9Resonant Artifact");
        meta.setCustomModelData(60013);
        meta.setLore(List.of(
                "§7Passive: while held in an §fAncient City§7,",
                "§7amplifies sculk bloom events by §f25%§7.",
                "§8The sculk recognises this fragment."
        ));
        meta.getPersistentDataContainer().set(ARTIFACT_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isHoldingArtifact(Player player) {
        return isArtifact(player.getInventory().getItemInMainHand())
            || isArtifact(player.getInventory().getItemInOffHand());
    }

    private boolean isArtifact(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(ARTIFACT_KEY, PersistentDataType.BYTE);
    }
}
