package com.vestigium.vestigiumnpc.traveling;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.SeasonChangeEvent;
import com.vestigium.vestigiumnpc.VestigiumNPC;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages all traveling NPC types — spawning, relocation, and despawning.
 *
 * Relocation intervals (real-world):
 *   Traveling Scholar, Archivist, Delver → every 3 days
 *   Nomadic Clans                        → on SeasonChangeEvent
 *   All others                           → stationary until quest resolved or killed
 *
 * Each spawned NPC carries a PDC type tag so it can be identified on reload.
 * Max one of each type active on the server at a time (except Refugees/Stray Children
 * which can have multiple).
 *
 * Spawn logic is intentionally simple — a random loaded overworld chunk within
 * a reasonable distance from any online player. More sophisticated placement
 * (biome-specific, structure-adjacent) can be layered on per type later.
 */
public class TravelingNPCManager {

    private static final long THREE_DAYS_MILLIS  = 3L * 24 * 60 * 60 * 1000;
    // Relocation check every 10 minutes
    private static final long RELOC_CHECK_TICKS  = 12_000L;
    // Spawn attempt every 20 minutes if a type is missing
    private static final long SPAWN_CHECK_TICKS  = 24_000L;

    private static final NamespacedKey NPC_TYPE_KEY =
            new NamespacedKey("vestigium", "npc_type");

    // Types with relocation intervals; all others are stationary
    private static final Map<String, Long> RELOCATING_TYPES = Map.of(
            TravelingNPCType.TRAVELING_SCHOLAR, THREE_DAYS_MILLIS,
            TravelingNPCType.ARCHIVIST,         THREE_DAYS_MILLIS,
            TravelingNPCType.DELVER,            THREE_DAYS_MILLIS
    );

    // Max simultaneous instances per type (0 = unlimited within pool)
    private static final Map<String, Integer> MAX_INSTANCES = Map.of(
            TravelingNPCType.TRAVELING_SCHOLAR, 1,
            TravelingNPCType.ARCHIVIST,         1,
            TravelingNPCType.ORACLE,            1,
            TravelingNPCType.RIVAL_ADVENTURER,  3,
            TravelingNPCType.CHRONICLER,        1,
            TravelingNPCType.REFUGEE,           5,
            TravelingNPCType.STRAY_CHILD,       3,
            TravelingNPCType.DELVER,            1,
            TravelingNPCType.PEARL_DIVER,       2
    );

    private final VestigiumNPC plugin;
    private final List<TravelingNPC> active = new ArrayList<>();
    private BukkitRunnable relocTask;
    private BukkitRunnable spawnTask;

    public TravelingNPCManager(VestigiumNPC plugin) {
        this.plugin = plugin;
    }

    public void init() {
        VestigiumLib.getEventBus().subscribe(SeasonChangeEvent.class, this::onSeasonChange);
        startRelocationTask();
        startSpawnTask();
        plugin.getLogger().info("[TravelingNPCManager] Initialized.");
    }

    public void shutdown() {
        if (relocTask != null) relocTask.cancel();
        if (spawnTask != null) spawnTask.cancel();
        // Despawn all active traveling NPCs on shutdown
        active.stream().filter(TravelingNPC::isValid)
                .forEach(npc -> npc.getEntity().remove());
        active.clear();
    }

    // -------------------------------------------------------------------------

    public List<TravelingNPC> getActive() {
        return Collections.unmodifiableList(active);
    }

    public int countActive(String type) {
        return (int) active.stream()
                .filter(n -> n.getType().equals(type) && n.isValid())
                .count();
    }

    // -------------------------------------------------------------------------

    private void onSeasonChange(SeasonChangeEvent event) {
        // Relocate all nomadic clans on season change
        active.stream()
                .filter(n -> TravelingNPCType.NOMADIC_CLAN.equals(n.getType()) && n.isValid())
                .forEach(this::relocate);
    }

    private void startRelocationTask() {
        relocTask = new BukkitRunnable() {
            @Override
            public void run() {
                active.removeIf(n -> !n.isValid());
                active.stream()
                        .filter(n -> RELOCATING_TYPES.containsKey(n.getType())
                                && n.isDueRelocation())
                        .forEach(TravelingNPCManager.this::relocate);
            }
        };
        relocTask.runTaskTimer(plugin, RELOC_CHECK_TICKS, RELOC_CHECK_TICKS);
    }

    private void startSpawnTask() {
        spawnTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.getServer().getOnlinePlayers().isEmpty()) return;
                active.removeIf(n -> !n.isValid());
                trySpawnMissing();
            }
        };
        spawnTask.runTaskTimer(plugin, SPAWN_CHECK_TICKS, SPAWN_CHECK_TICKS);
    }

    private void trySpawnMissing() {
        for (Map.Entry<String, Integer> entry : MAX_INSTANCES.entrySet()) {
            String type = entry.getKey();
            int max = entry.getValue();
            if (countActive(type) < max) {
                Location loc = randomOverworldLocation();
                if (loc != null) spawn(type, loc);
            }
        }
    }

    private void relocate(TravelingNPC npc) {
        Location newLoc = randomOverworldLocation();
        if (newLoc == null) return;
        npc.getEntity().teleport(newLoc);
    }

    private TravelingNPC spawn(String type, Location location) {
        Villager villager = (Villager) location.getWorld()
                .spawnEntity(location, org.bukkit.entity.EntityType.VILLAGER);
        configureNPC(villager, type);

        long interval = RELOCATING_TYPES.getOrDefault(type, Long.MAX_VALUE);
        TravelingNPC npc = new TravelingNPC(type, villager, interval);
        active.add(npc);
        return npc;
    }

    private void configureNPC(Villager villager, String type) {
        villager.setAI(true);
        villager.setCustomName(displayNameFor(type));
        villager.setCustomNameVisible(true);
        villager.setPersistent(true);
        villager.getPersistentDataContainer()
                .set(NPC_TYPE_KEY, PersistentDataType.STRING, type);
        // Traveling NPCs don't breed or pick up items
        villager.setBreed(false);
    }

    private String displayNameFor(String type) {
        return switch (type) {
            case TravelingNPCType.TRAVELING_SCHOLAR -> "Traveling Scholar";
            case TravelingNPCType.ARCHIVIST         -> "The Archivist";
            case TravelingNPCType.ORACLE            -> "Oracle";
            case TravelingNPCType.RIVAL_ADVENTURER  -> "Rival Adventurer";
            case TravelingNPCType.CHRONICLER        -> "The Chronicler";
            case TravelingNPCType.HERMIT            -> "Hermit";
            case TravelingNPCType.NOMADIC_CLAN      -> "Nomad";
            case TravelingNPCType.MERCENARY_POST    -> "Mercenary";
            case TravelingNPCType.EXILED_MAGE       -> "Exiled Mage";
            case TravelingNPCType.REFUGEE           -> "Refugee";
            case TravelingNPCType.STRAY_CHILD       -> "Lost Child";
            case TravelingNPCType.PEARL_DIVER       -> "Pearl Diver";
            case TravelingNPCType.DELVER            -> "The Delver";
            default -> type;
        };
    }

    private Location randomOverworldLocation() {
        List<org.bukkit.entity.Player> players = new ArrayList<>(
                plugin.getServer().getOnlinePlayers());
        if (players.isEmpty()) return null;

        org.bukkit.entity.Player anchor = players.get(
                ThreadLocalRandom.current().nextInt(players.size()));
        World world = anchor.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) return null;

        // Spawn 100-500 blocks from the anchor player in a random direction
        double angle  = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
        double dist   = 100 + ThreadLocalRandom.current().nextDouble() * 400;
        int x = anchor.getLocation().getBlockX() + (int) (Math.cos(angle) * dist);
        int z = anchor.getLocation().getBlockZ() + (int) (Math.sin(angle) * dist);
        int y = world.getHighestBlockYAt(x, z) + 1;
        return new Location(world, x, y, z);
    }
}
