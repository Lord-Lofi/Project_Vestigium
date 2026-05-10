package com.vestigium.vestigiumplayer.epitaph;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import org.bukkit.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Player Epitaphs — spawns an ArmorStand gravestone at each player's death location.
 * Echo of the Dead — on death, replays a SOUL-particle silhouette of the player's
 * last 10 seconds of movement, visible to any nearby player.
 *
 * Right-click the gravestone to remove it (owner only, or op with vestigium.epitaph.admin).
 */
public class PlayerEpitaphManager implements Listener {

    private static final NamespacedKey EPITAPH_KEY  = new NamespacedKey("vestigium", "epitaph_owner");

    private static final int  SAMPLE_TICKS = 4;   // sample location every 4 ticks (0.2 s)
    private static final int  MAX_SAMPLES  = 50;  // 50 × 4t = 10 seconds of history

    private final VestigiumPlayer plugin;
    private final Map<UUID, ArrayDeque<Location>> locationBuffer = new HashMap<>();
    private final Map<UUID, UUID>                 playerToGrave  = new HashMap<>();
    private BukkitRunnable samplerTask;

    public PlayerEpitaphManager(VestigiumPlayer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        samplerTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    ArrayDeque<Location> buf = locationBuffer.computeIfAbsent(
                            p.getUniqueId(), id -> new ArrayDeque<>());
                    buf.addLast(p.getLocation().clone());
                    while (buf.size() > MAX_SAMPLES) buf.pollFirst();
                }
            }
        };
        samplerTask.runTaskTimer(plugin, SAMPLE_TICKS, SAMPLE_TICKS);
        plugin.getLogger().info("[PlayerEpitaphManager] Initialized.");
    }

    public void shutdown() {
        if (samplerTask != null) samplerTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        Location loc = dead.getLocation();

        removeGrave(dead);

        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setInvulnerable(true);
        stand.setGravity(false);
        stand.setVisible(false);
        stand.setSmall(false);
        stand.setPersistent(true);
        stand.setGlowing(true);
        stand.setCustomName("§8✦ §7" + dead.getName() + " §8fell here");
        stand.setCustomNameVisible(true);

        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) skull.getItemMeta();
        if (sm != null) {
            sm.setOwningPlayer(dead);
            skull.setItemMeta(sm);
        }
        stand.getEquipment().setHelmet(skull);
        stand.getPersistentDataContainer()
                .set(EPITAPH_KEY, PersistentDataType.STRING, dead.getUniqueId().toString());
        playerToGrave.put(dead.getUniqueId(), stand.getUniqueId());

        List<Location> snapshot = new ArrayList<>(
                locationBuffer.getOrDefault(dead.getUniqueId(), new ArrayDeque<>()));
        if (!snapshot.isEmpty()) {
            startEcho(loc.getWorld(), snapshot);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        locationBuffer.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInteract(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof ArmorStand stand)) return;
        String ownerStr = stand.getPersistentDataContainer()
                .get(EPITAPH_KEY, PersistentDataType.STRING);
        if (ownerStr == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        boolean isOwner = player.getUniqueId().toString().equals(ownerStr);
        boolean isAdmin = player.hasPermission("vestigium.epitaph.admin");

        if (!isOwner && !isAdmin) {
            player.sendMessage("§7This marker belongs to another wanderer.");
            return;
        }

        UUID ownerUUID = UUID.fromString(ownerStr);
        playerToGrave.remove(ownerUUID);
        stand.remove();
        player.sendMessage("§7The resting marker dissolves.");
    }

    // -------------------------------------------------------------------------

    private void removeGrave(Player player) {
        UUID graveId = playerToGrave.remove(player.getUniqueId());
        if (graveId == null) return;
        for (World world : plugin.getServer().getWorlds()) {
            var entity = world.getEntity(graveId);
            if (entity != null) { entity.remove(); break; }
        }
    }

    private void startEcho(World world, List<Location> path) {
        new BukkitRunnable() {
            int step = 0;
            @Override public void run() {
                if (step >= path.size()) { cancel(); return; }
                Location loc = path.get(step++).clone().add(0, 1, 0);
                world.spawnParticle(Particle.SOUL, loc, 5, 0.15, 0.3, 0.15, 0.01);
            }
        }.runTaskTimer(plugin, 0L, SAMPLE_TICKS);
    }
}
