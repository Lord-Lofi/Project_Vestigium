package com.vestigium.vestigiumlore.delivery;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles all in-world lore delivery mechanisms:
 *
 *  Lore Tablets      — written books in structures, non-duplicable, pulled from LoreRegistry
 *                      by vestigium:structure_id PDC on the container block.
 *
 *  Resonant Terminals — sculk-covered lecterns in ancient cities; lore on right-click;
 *                       requires the player to have the Resonant Cipher item to read.
 *
 *  Campfire Stories  — multiple players near a campfire at night auto-display a random
 *                      lore fragment in chat.
 *
 *  Message in a Bottle — procedurally generated; washes up on random shores periodically.
 */
public class LoreDeliveryManager implements Listener {

    private static final NamespacedKey TERMINAL_STRUCTURE_ID =
            new NamespacedKey("vestigium", "structure_id");
    private static final NamespacedKey TABLET_READ_KEY =
            new NamespacedKey("vestigium", "tablet_read");

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

    // -------------------------------------------------------------------------
    // Lore Tablets and Resonant Terminals — right-click on lectern/chest
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        if (block.getType() == Material.LECTERN) {
            handleTerminalInteraction(player, block);
        }
    }

    private void handleTerminalInteraction(Player player, Block lectern) {
        String structureId = lectern.getPersistentDataContainer()
                .get(TERMINAL_STRUCTURE_ID, PersistentDataType.STRING);
        if (structureId == null) return;

        // Determine if Resonant Terminal (requires cipher) or plain tablet
        boolean isSculk = lectern.getRelative(0, -1, 0).getType() == Material.SCULK;
        if (isSculk && !playerHasCipher(player, "resonant")) {
            player.sendMessage("§8The symbols on the terminal mean nothing to you. "
                    + "§7You need the Resonant Cipher to read this.");
            return;
        }

        String content = VestigiumLib.getLoreRegistry().getLoreContent(structureId, "main");
        if (content.isEmpty()) return;

        deliverLoreBook(player, structureId, content);
        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), structureId + "_main");
    }

    private void deliverLoreBook(Player player, String tabletId, String content) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return;

        meta.setTitle("Fragment");
        meta.setAuthor("Unknown");
        meta.addPage(content);
        book.setItemMeta(meta);

        // Non-duplicable: tag the book with the tablet ID
        book.getItemMeta(); // refresh
        BookMeta finalMeta = (BookMeta) book.getItemMeta();
        if (finalMeta != null) {
            finalMeta.getPersistentDataContainer()
                    .set(TABLET_READ_KEY, PersistentDataType.STRING, tabletId);
            book.setItemMeta(finalMeta);
        }

        player.getInventory().addItem(book);
        player.sendMessage("§7You carefully read the tablet and transcribe its contents.");
    }

    private boolean playerHasCipher(Player player, String cipherType) {
        return Arrays.stream(player.getInventory().getContents())
                .filter(Objects::nonNull)
                .anyMatch(item -> {
                    if (item.getItemMeta() == null) return false;
                    String stored = item.getItemMeta().getPersistentDataContainer()
                            .get(new NamespacedKey("vestigium", "cipher_type"),
                                    PersistentDataType.STRING);
                    return cipherType.equals(stored);
                });
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
