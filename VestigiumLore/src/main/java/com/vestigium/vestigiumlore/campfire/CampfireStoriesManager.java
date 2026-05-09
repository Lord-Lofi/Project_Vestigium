package com.vestigium.vestigiumlore.campfire;

import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Campfire Stories — when 2+ players gather within 5 blocks of a lit campfire
 * during night (game-time 13 000–23 000), a lore story is shared in chat.
 *
 * Per-campfire cooldown: 10 minutes.
 * Checked every 60 ticks (3 seconds).
 */
public class CampfireStoriesManager {

    private static final int  CAMPFIRE_RADIUS   = 5;
    private static final int  MIN_PLAYERS       = 2;
    private static final long COOLDOWN_MS       = 10 * 60 * 1000L;
    private static final long CHECK_TICKS       = 60L;

    private static final long NIGHT_START = 13_000L;
    private static final long NIGHT_END   = 23_000L;

    private static final List<String> STORIES = List.of(
        "§7§oThe Antecedent did not build roads. They built memory. Every path was a sentence in a language no one has finished reading.",
        "§7§oSomeone left a map here once. The landmarks were not places. They were questions.",
        "§7§oThere is a cartographer's mark on the stone at the edge of the world. It says: 'do not go further.' Below it, in different ink: 'too late.'",
        "§7§oThe expedition logs mention a survey team that went north and did not return. The last entry says only: the compass points down.",
        "§7§oThey built the waystones before they had a destination. That tells you something about who they were.",
        "§7§oIn the deep archive, all the clocks stopped at the same moment. No one recorded which moment that was.",
        "§7§oThe warden does not hunt by sight. It hunts by understanding. The Antecedent knew this. They left anyway.",
        "§7§oOne survey note reads: 'The structure is older than our civilization. We believe we built it. We are wrong.'",
        "§7§oThe Resonant language has no word for goodbye. It has seventeen words for 'I will remember where you stood.'",
        "§7§oA child once asked an archivist what the Antecedent were afraid of. The archivist said: themselves. Then they closed the book.",
        "§7§oThe final cartographer drew every road except the one they took home. No one knows if that was an accident.",
        "§7§oThe sculk does not spread randomly. It spreads toward memory. The Antecedent understood this only at the end.",
        "§7§oSomewhere beneath this ground, a clock still counts. We do not know what it is counting toward.",
        "§7§oThere are structures in this world that predate the Antecedent. They built over them anyway. The older things are still there.",
        "§7§oThe last expedition memo says: 'Return if you can. If you cannot, leave the data. The data is what matters.' No one came back.",
        "§7§oThe Antecedent had a word — 'vestigium.' Meaning: a trace. A footprint. The smallest evidence that something was here.",
        "§7§oSome of their doors only open from the inside. This was not a mistake.",
        "§7§oThe omen rises when the world remembers something it should have forgotten. That is the theory. No one has disproven it.",
        "§7§oThere is a record of a city that vanished in three days. The record was written four days after it vanished, by someone who had been inside.",
        "§7§oThe cipher says the world has a center. The center is not a place. It is a question. We are still asking it."
    );

    private final VestigiumLore plugin;
    private final Map<String, Long> campfireCooldowns = new HashMap<>();
    private BukkitRunnable task;

    public CampfireStoriesManager(VestigiumLore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        task = new BukkitRunnable() {
            @Override public void run() {
                plugin.getServer().getWorlds().stream()
                        .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                        .forEach(CampfireStoriesManager.this::checkWorld);
            }
        };
        task.runTaskTimer(plugin, CHECK_TICKS, CHECK_TICKS);
        plugin.getLogger().info("[CampfireStoriesManager] Initialized.");
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    // -------------------------------------------------------------------------

    private void checkWorld(World world) {
        long time = world.getTime();
        if (time < NIGHT_START || time > NIGHT_END) return;

        long now = System.currentTimeMillis();

        // Group players by the nearest lit campfire within radius
        Map<String, List<Player>> groups = new HashMap<>();
        for (Player p : world.getPlayers()) {
            Block campfire = findNearestLitCampfire(p);
            if (campfire == null) continue;
            String key = locKey(campfire.getLocation());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        for (var entry : groups.entrySet()) {
            if (entry.getValue().size() < MIN_PLAYERS) continue;
            Long cooldownUntil = campfireCooldowns.get(entry.getKey());
            if (cooldownUntil != null && now < cooldownUntil) continue;

            campfireCooldowns.put(entry.getKey(), now + COOLDOWN_MS);
            deliverStory(entry.getValue());
        }
    }

    private void deliverStory(List<Player> audience) {
        String story = STORIES.get(ThreadLocalRandom.current().nextInt(STORIES.size()));
        for (Player p : audience) {
            p.sendMessage(" ");
            p.sendMessage("§6✦ §7Around the fire, someone speaks:");
            p.sendMessage(story);
            p.sendMessage(" ");
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_FIRE_AMBIENT, 0.4f, 0.9f);
        }
    }

    private Block findNearestLitCampfire(Player player) {
        Location loc = player.getLocation();
        int r = CAMPFIRE_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = loc.getBlock().getRelative(dx, dy, dz);
                    if (b.getType() == Material.CAMPFIRE || b.getType() == Material.SOUL_CAMPFIRE) {
                        var state = b.getBlockData();
                        if (state instanceof org.bukkit.block.data.type.Campfire cf && cf.isLit()) {
                            return b;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String locKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
