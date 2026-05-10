package com.vestigium.vestigiumlore.delivery;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Legacy campfire + bottle delivery stubs. Terminal interaction is handled by TerminalManager.
 * Campfire stories are handled by CampfireStoriesManager.
 * Messages in a Bottle are handled by MessageInABottleManager.
 */
public class LoreDeliveryManager implements Listener {

    // Campfire story check: every 2 minutes during server night
    private static final long CAMPFIRE_CHECK_TICKS = 2_400L;
    // Min players around campfire to trigger a story
    private static final int CAMPFIRE_PLAYER_THRESHOLD = 2;
    private static final double CAMPFIRE_RADIUS = 8.0;
    // Bottle wash-up: roughly once per real-world day per world
    private static final long BOTTLE_SPAWN_TICKS = 72_000L;

    private static final List<String> BOTTLE_FRAGMENTS = List.of(
            "The water is rising. Tell them we tried.",
            "Do not open the fourth door. The others did not know what they were sealing.",
            "It watches from the sculk. I have seen it three times now.",
            "The road still works. Follow it. Do not stop when it gets dark.",
            "We descended because the surface forgot us. We do not regret it.",
            "The dragon is not a monster. Do not treat it as one.",
            "There is a name carved at the bottom of every waystone. Same name. Every one.",
            "I found the census. The name matches. Do you understand what that means?",
            "The Antecedent did not die. They became everything else."
    );

    private final VestigiumLore plugin;
    private BukkitRunnable campfireTask;
    private BukkitRunnable bottleTask;

    public LoreDeliveryManager(VestigiumLore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startCampfireTask();
        startBottleTask();
        plugin.getLogger().info("[LoreDeliveryManager] Initialized.");
    }

    public void shutdown() {
        if (campfireTask != null) campfireTask.cancel();
        if (bottleTask   != null) bottleTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Campfire Stories
    // -------------------------------------------------------------------------

    private void startCampfireTask() {
        campfireTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getServer().getWorlds().forEach(world -> {
                    if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL) return;
                    long time = world.getTime();
                    if (time < 13_000 || time > 23_000) return; // daytime skip

                    world.getPlayers().forEach(player -> checkCampfireStory(player, world));
                });
            }
        };
        campfireTask.runTaskTimer(plugin, CAMPFIRE_CHECK_TICKS, CAMPFIRE_CHECK_TICKS);
    }

    private void checkCampfireStory(Player player, org.bukkit.World world) {
        Block below = player.getLocation().getBlock().getRelative(0, -1, 0);
        if (below.getType() != Material.CAMPFIRE && below.getType() != Material.SOUL_CAMPFIRE)
            return;

        long nearby = world.getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(player.getLocation())
                        <= CAMPFIRE_RADIUS * CAMPFIRE_RADIUS)
                .count();

        if (nearby < CAMPFIRE_PLAYER_THRESHOLD) return;
        if (ThreadLocalRandom.current().nextInt(100) > 20) return; // 20% chance per check

        String fragment = getRandomLoreFragment();
        world.getPlayers().stream()
                .filter(p -> p.getLocation().distanceSquared(player.getLocation())
                        <= CAMPFIRE_RADIUS * CAMPFIRE_RADIUS)
                .forEach(p -> p.sendMessage("§6[Campfire] §7" + fragment));
    }

    private String getRandomLoreFragment() {
        // Try to pull from LoreRegistry first; fall back to hardcoded fragments
        return BOTTLE_FRAGMENTS.get(ThreadLocalRandom.current().nextInt(BOTTLE_FRAGMENTS.size()));
    }

    // -------------------------------------------------------------------------
    // Message in a Bottle
    // -------------------------------------------------------------------------

    private void startBottleTask() {
        bottleTask = new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getServer().getWorlds().stream()
                        .filter(w -> w.getEnvironment() == org.bukkit.World.Environment.NORMAL)
                        .forEach(world -> {
                            if (ThreadLocalRandom.current().nextInt(100) < 15) {
                                spawnBottle(world);
                            }
                        });
            }
        };
        bottleTask.runTaskTimerAsynchronously(plugin, BOTTLE_SPAWN_TICKS, BOTTLE_SPAWN_TICKS);
    }

    private void spawnBottle(org.bukkit.World world) {
        // Find a random ocean/beach block to wash up on
        List<Player> players = new ArrayList<>(world.getPlayers());
        if (players.isEmpty()) return;

        Player anchor = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        int x = anchor.getLocation().getBlockX()
                + ThreadLocalRandom.current().nextInt(400) - 200;
        int z = anchor.getLocation().getBlockZ()
                + ThreadLocalRandom.current().nextInt(400) - 200;
        int y = world.getHighestBlockYAt(x, z);

        Location loc = new Location(world, x, y + 1, z);
        if (VestigiumLib.getProtectionAPI().isProtected(loc)) return;

        String message = BOTTLE_FRAGMENTS.get(
                ThreadLocalRandom.current().nextInt(BOTTLE_FRAGMENTS.size()));

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            ItemStack bottle = new ItemStack(Material.GLASS_BOTTLE);
            org.bukkit.inventory.meta.ItemMeta meta = bottle.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§bMessage in a Bottle");
                meta.setLore(List.of("§7" + message));
                meta.getPersistentDataContainer()
                        .set(new NamespacedKey("vestigium", "bottle_message"),
                                PersistentDataType.STRING, message);
                bottle.setItemMeta(meta);
            }
            world.dropItemNaturally(loc, bottle);
        });
    }
}
