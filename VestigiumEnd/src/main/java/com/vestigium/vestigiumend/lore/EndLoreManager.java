package com.vestigium.vestigiumend.lore;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumend.VestigiumEnd;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * End Lore Systems — four interlocking lore mechanics in the End dimension.
 *
 * 1. Bound Dragon Dialogue — when an active Ender Dragon flies within 40 blocks of
 *    a player, a random atmospheric actionbar fragment is shown (10-second cooldown).
 *
 * 2. Dragon Seal Fragments — one lore fragment per dragon kill (all End players
 *    within 150 blocks receive a seal), up to 5 total. Fragment IDs:
 *    dragon_seal_01 through dragon_seal_05. Tracked via vestigium:dragon_seal_count
 *    INTEGER PDC on player.
 *
 * 3. The Convergence Point — standing within 15 blocks of the End world spawn for
 *    10 seconds triggers a once-per-player 5-message lore sequence (3-second gaps).
 *    Awards convergence_point_main. Gate: vestigium:convergence_witnessed BYTE.
 *
 * 4. Enderman Witness Chain — 6-step sequential lore chain. Each step grants
 *    enderman_witness_01 through enderman_witness_06. At step 6 sets
 *    vestigium:witness_complete BYTE. Steps:
 *      1 — Enter the End for the first time
 *      2 — Kill 1 Enderman in the End
 *      3 — Kill 10 Endermen in the End (vestigium:enderman_end_kills INTEGER)
 *      4 — Stand still 30s in the End while a nearby Enderman isn't targeting you
 *      5 — 5+ Endermen within 10 blocks simultaneously
 *      6 — Witness The Convergence Point
 */
public class EndLoreManager implements Listener {

    private static final NamespacedKey SEAL_KEY       = new NamespacedKey("vestigium", "dragon_seal_count");
    private static final NamespacedKey CONVERGENCE_KEY = new NamespacedKey("vestigium", "convergence_witnessed");
    private static final NamespacedKey WITNESS_KEY    = new NamespacedKey("vestigium", "witness_step");
    private static final NamespacedKey END_KILLS_KEY  = new NamespacedKey("vestigium", "enderman_end_kills");
    private static final NamespacedKey WITNESS_COMP_KEY = new NamespacedKey("vestigium", "witness_complete");

    private static final int  DIALOGUE_RADIUS      = 40;
    private static final long DIALOGUE_COOLDOWN_MS = 10_000L;
    private static final int  SEAL_MAX             = 5;
    private static final int  SEAL_GRANT_RADIUS    = 150;
    private static final long CONVERGENCE_STILL_MS = 10_000L;
    private static final int  CONVERGENCE_RADIUS   = 15;
    private static final long WITNESS_STILL_MS     = 30_000L;
    private static final int  CLUSTER_RADIUS       = 10;
    private static final int  CLUSTER_THRESHOLD    = 5;

    private static final String[] BOUND_DIALOGUE = {
        "§5The dragon knows you are here.",
        "§5It has flown this circle ten thousand times. It has not counted.",
        "§5Something in its breath remembers fire.",
        "§5The wings do not tire. You do not know why you notice this.",
        "§5It passes through you the way grief passes through walls.",
        "§5The dragon was not the first thing to die here. It was the last thing to notice.",
        "§5It is not attacking. This is worse.",
        "§5You feel the displacement of air from something vast and close.",
        "§5The dragon circles. The End is its memory, not its home.",
        "§5Whatever holds it here, it is not hatred."
    };

    private static final String[] WITNESS_MESSAGES = {
        "§5You have set foot in the End. The Endermen have already noticed.",
        "§5An Enderman has fallen. The others know. They are not afraid — they are watching.",
        "§5Ten. The Endermen count the dead. So do you, now.",
        "§5It stood beside you and did not move. You were seen. That was enough.",
        "§5Five of them, within reach, still. They gather like memory around something lost.",
        "§5You have seen what they witness. Now they see what you understand."
    };

    private static final String[] CONVERGENCE_SEQUENCE = {
        "§5The ground beneath you is older than this island.",
        "§5The first thing that came here came willingly. This is what they have been trying to tell you.",
        "§5This is where the Shattering began — not with violence, but with a door left open.",
        "§5The dragon was not placed here. It arrived. Like you. Unlike you, it knew what this place was.",
        "§5You have witnessed the beginning. The Endermen have been waiting for someone to stand here and understand."
    };

    private final VestigiumEnd      plugin;
    private final Map<UUID, Long>   lastDialogueMs = new HashMap<>();
    private final Map<UUID, Long>   lastMoveMs     = new HashMap<>();

    private BukkitRunnable dialogueTask;
    private BukkitRunnable progressTask;

    public EndLoreManager(VestigiumEnd plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startDialogueTask();
        startProgressTask();
        plugin.getLogger().info("[EndLoreManager] Initialized.");
    }

    public void shutdown() {
        if (dialogueTask != null) dialogueTask.cancel();
        if (progressTask != null) progressTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEnterEnd(PlayerChangedWorldEvent event) {
        if (event.getPlayer().getWorld().getEnvironment() != World.Environment.THE_END) return;
        advanceWitness(event.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;
        World world = dragon.getWorld();
        long rSq = (long) SEAL_GRANT_RADIUS * SEAL_GRANT_RADIUS;
        world.getPlayers().stream()
                .filter(p -> dragon.getLocation().distanceSquared(p.getLocation()) <= rSq)
                .forEach(this::grantDragonSeal);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEndermanKill(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enderman)) return;
        if (event.getEntity().getWorld().getEnvironment() != World.Environment.THE_END) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        int kills = killer.getPersistentDataContainer()
                .getOrDefault(END_KILLS_KEY, PersistentDataType.INTEGER, 0) + 1;
        killer.getPersistentDataContainer().set(END_KILLS_KEY, PersistentDataType.INTEGER, kills);

        if (kills == 1)  advanceWitness(killer, 2);
        if (kills == 10) advanceWitness(killer, 3);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getPlayer().getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        lastMoveMs.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastDialogueMs.remove(id);
        lastMoveMs.remove(id);
    }

    // -------------------------------------------------------------------------
    // Bound Dragon Dialogue — every 5 ticks
    // -------------------------------------------------------------------------

    private void startDialogueTask() {
        dialogueTask = new BukkitRunnable() {
            @Override public void run() {
                for (World world : plugin.getServer().getWorlds()) {
                    if (world.getEnvironment() != World.Environment.THE_END) continue;
                    List<EnderDragon> dragons = world.getEntitiesByClass(EnderDragon.class)
                            .stream().toList();
                    if (dragons.isEmpty()) continue;
                    long rSq = (long) DIALOGUE_RADIUS * DIALOGUE_RADIUS;
                    for (Player p : world.getPlayers()) {
                        for (EnderDragon dragon : dragons) {
                            if (dragon.getLocation().distanceSquared(p.getLocation()) <= rSq) {
                                tryBoundDialogue(p);
                                break;
                            }
                        }
                    }
                }
            }
        };
        dialogueTask.runTaskTimer(plugin, 5L, 5L);
    }

    private void tryBoundDialogue(Player player) {
        long now = System.currentTimeMillis();
        if (now - lastDialogueMs.getOrDefault(player.getUniqueId(), 0L) < DIALOGUE_COOLDOWN_MS) return;
        lastDialogueMs.put(player.getUniqueId(), now);
        String msg = BOUND_DIALOGUE[ThreadLocalRandom.current().nextInt(BOUND_DIALOGUE.length)];
        player.sendActionBar(Component.text(msg));
    }

    // -------------------------------------------------------------------------
    // Convergence + Witness — every 20 ticks
    // -------------------------------------------------------------------------

    private void startProgressTask() {
        progressTask = new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (p.getWorld().getEnvironment() != World.Environment.THE_END) continue;
                    tryConvergence(p, now);
                    tryWitnessStep4(p, now);
                    tryWitnessStep5(p);
                }
            }
        };
        progressTask.runTaskTimer(plugin, 20L, 20L);
    }

    private void tryConvergence(Player player, long now) {
        if (player.getPersistentDataContainer().has(CONVERGENCE_KEY, PersistentDataType.BYTE)) return;
        var spawn = player.getWorld().getSpawnLocation();
        long rSq = (long) CONVERGENCE_RADIUS * CONVERGENCE_RADIUS;
        if (spawn.distanceSquared(player.getLocation()) > rSq) return;
        if (now - lastMoveMs.getOrDefault(player.getUniqueId(), 0L) < CONVERGENCE_STILL_MS) return;

        player.getPersistentDataContainer().set(CONVERGENCE_KEY, PersistentDataType.BYTE, (byte) 1);
        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), "convergence_point_main");

        for (int i = 0; i < CONVERGENCE_SEQUENCE.length; i++) {
            final String msg = CONVERGENCE_SEQUENCE[i];
            new BukkitRunnable() {
                @Override public void run() { if (player.isOnline()) player.sendMessage(msg); }
            }.runTaskLater(plugin, i * 60L);
        }
        new BukkitRunnable() {
            @Override public void run() { if (player.isOnline()) advanceWitness(player, 6); }
        }.runTaskLater(plugin, CONVERGENCE_SEQUENCE.length * 60L);
    }

    private void tryWitnessStep4(Player player, long now) {
        if (getWitnessStep(player) != 3) return;
        if (now - lastMoveMs.getOrDefault(player.getUniqueId(), 0L) < WITNESS_STILL_MS) return;

        boolean found = player.getWorld()
                .getNearbyEntities(player.getLocation(), CLUSTER_RADIUS, CLUSTER_RADIUS, CLUSTER_RADIUS)
                .stream()
                .filter(e -> {
                    if (!(e instanceof Enderman em)) return false;
                    return !player.equals(em.getTarget());
                })
                .findAny().isPresent();
        if (found) advanceWitness(player, 4);
    }

    private void tryWitnessStep5(Player player) {
        if (getWitnessStep(player) != 4) return;
        long nearby = player.getWorld()
                .getNearbyEntities(player.getLocation(), CLUSTER_RADIUS, CLUSTER_RADIUS, CLUSTER_RADIUS)
                .stream().filter(e -> e instanceof Enderman).count();
        if (nearby >= CLUSTER_THRESHOLD) advanceWitness(player, 5);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void grantDragonSeal(Player player) {
        int current = player.getPersistentDataContainer()
                .getOrDefault(SEAL_KEY, PersistentDataType.INTEGER, 0);
        if (current >= SEAL_MAX) return;

        int next = current + 1;
        player.getPersistentDataContainer().set(SEAL_KEY, PersistentDataType.INTEGER, next);
        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(),
                String.format("dragon_seal_%02d", next));

        player.sendMessage("§5[Dragon Seal] §7Seal " + next + "/" + SEAL_MAX + " added to your record.");
        if (next == SEAL_MAX) {
            player.sendMessage("§5The seal is complete. The dragon is no longer singular.");
        }
    }

    private int getWitnessStep(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(WITNESS_KEY, PersistentDataType.INTEGER, 0);
    }

    private void advanceWitness(Player player, int expectedStep) {
        if (getWitnessStep(player) != expectedStep - 1) return;

        player.getPersistentDataContainer().set(WITNESS_KEY, PersistentDataType.INTEGER, expectedStep);
        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(),
                String.format("enderman_witness_%02d", expectedStep));

        if (expectedStep >= 1 && expectedStep <= WITNESS_MESSAGES.length) {
            player.sendMessage(WITNESS_MESSAGES[expectedStep - 1]);
        }
        if (expectedStep == 6) {
            player.getPersistentDataContainer().set(WITNESS_COMP_KEY, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage("§8[§5Witness§8] §7You have completed the Witness chain.");
        }
    }
}
