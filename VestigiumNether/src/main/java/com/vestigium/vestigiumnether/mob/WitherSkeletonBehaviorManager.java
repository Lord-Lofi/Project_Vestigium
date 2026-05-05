package com.vestigium.vestigiumnether.mob;

import com.vestigium.vestigiumnether.VestigiumNether;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Wither Skeleton Behavioral Response.
 *
 * When a player stands still within 20 blocks of a registered Nether terminal for
 * 15 seconds without taking any damage, all WitherSkeletons within 25 blocks that
 * are targeting them lose their target (passive ignore).
 *
 * The ignore state is evaluated and re-applied every second while conditions hold.
 * Any X/Z movement or incoming damage immediately breaks the stillness window.
 *
 * Nether terminals are admin-placed anchor points persisted to
 * plugins/VestigiumNether/terminals.yml. Manage via /vnnether terminal set|remove|list.
 *
 * First time the condition is met, vestigium:fortress_protocol_achieved is set in the
 * player's PDC — achievement stub pending the full achievement tree.
 */
public class WitherSkeletonBehaviorManager implements Listener, CommandExecutor, TabCompleter {

    private static final NamespacedKey FORTRESS_KEY =
            new NamespacedKey("vestigium", "fortress_protocol_achieved");

    private static final long STILL_THRESHOLD_MS  = 15_000L; // 15 seconds without movement
    private static final long COMBAT_COOLDOWN_MS  =  5_000L; // 5 seconds after last hit
    private static final int  TERMINAL_RADIUS     = 20;      // blocks from terminal
    private static final int  SKELETON_RADIUS     = 25;      // blocks scanned for skeletons

    private final VestigiumNether     plugin;
    private final Map<UUID, Long>     lastMoveMs   = new HashMap<>();
    private final Map<UUID, Long>     lastCombatMs = new HashMap<>();
    private final List<Location>      terminals    = new ArrayList<>();
    private File terminalsFile;
    private BukkitRunnable checkTask;

    public WitherSkeletonBehaviorManager(VestigiumNether plugin) {
        this.plugin = plugin;
    }

    public void init() {
        terminalsFile = new File(plugin.getDataFolder(), "terminals.yml");
        loadTerminals();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("vnnether").setExecutor(this);
        plugin.getCommand("vnnether").setTabCompleter(this);
        startCheckTask();
        plugin.getLogger().info("[WitherSkeletonBehaviorManager] Initialized — "
                + terminals.size() + " terminal(s) loaded.");
    }

    public void shutdown() {
        if (checkTask != null) checkTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getWorld().getEnvironment() != World.Environment.NETHER) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        lastMoveMs.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        lastCombatMs.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastMoveMs.remove(id);
        lastCombatMs.remove(id);
    }

    // -------------------------------------------------------------------------
    // Check task (every 20 ticks / 1 second)
    // -------------------------------------------------------------------------

    private void startCheckTask() {
        checkTask = new BukkitRunnable() {
            @Override public void run() {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (p.getWorld().getEnvironment() != World.Environment.NETHER) continue;
                    tickPlayer(p);
                }
            }
        };
        checkTask.runTaskTimer(plugin, 20L, 20L);
    }

    private void tickPlayer(Player player) {
        long now = System.currentTimeMillis();

        if (now - lastMoveMs.getOrDefault(player.getUniqueId(), 0L) < STILL_THRESHOLD_MS) return;
        if (now - lastCombatMs.getOrDefault(player.getUniqueId(), 0L) < COMBAT_COOLDOWN_MS) return;
        if (!isNearTerminal(player.getLocation())) return;

        boolean anyIgnored = false;
        for (Entity e : player.getWorld().getNearbyEntities(
                player.getLocation(), SKELETON_RADIUS, SKELETON_RADIUS, SKELETON_RADIUS)) {
            if (!(e instanceof WitherSkeleton ws)) continue;
            if (player.equals(ws.getTarget())) {
                ws.setTarget(null);
                anyIgnored = true;
            }
        }

        if (anyIgnored) {
            player.sendActionBar(Component.text("§7The wither skeletons overlook you."));
        }

        // Fortress Protocol achievement — fires once per player lifetime
        if (!player.getPersistentDataContainer().has(FORTRESS_KEY, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().set(FORTRESS_KEY, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage("§8[§6Fortress Protocol§8] §7You have learned to stand unseen among the ancient dead.");
        }
    }

    private boolean isNearTerminal(Location loc) {
        int rSq = TERMINAL_RADIUS * TERMINAL_RADIUS;
        for (Location t : terminals) {
            if (!t.getWorld().equals(loc.getWorld())) continue;
            if (t.distanceSquared(loc) <= rSq) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Terminal persistence
    // -------------------------------------------------------------------------

    private void loadTerminals() {
        if (!terminalsFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(terminalsFile);
        for (String key : cfg.getKeys(false)) {
            World world = Bukkit.getWorld(cfg.getString(key + ".world", ""));
            if (world == null) continue;
            terminals.add(new Location(world,
                    cfg.getDouble(key + ".x"),
                    cfg.getDouble(key + ".y"),
                    cfg.getDouble(key + ".z")));
        }
    }

    private void saveTerminals() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (int i = 0; i < terminals.size(); i++) {
            Location t = terminals.get(i);
            cfg.set(i + ".world", t.getWorld().getName());
            cfg.set(i + ".x", t.getX());
            cfg.set(i + ".y", t.getY());
            cfg.set(i + ".z", t.getZ());
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(terminalsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[WitherSkeletonBehaviorManager] Save failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Command: /vnnether terminal <set|remove|list>
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("terminal")) {
            sender.sendMessage("§cUsage: /vnnether terminal <set|remove|list>");
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "set" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cIn-game only."); return true; }
                Location loc = p.getLocation().getBlock().getLocation();
                terminals.add(loc);
                saveTerminals();
                sender.sendMessage("§aTerminal registered at §e"
                        + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + "§a.");
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cIn-game only."); return true; }
                boolean removed = terminals.removeIf(t -> t.distanceSquared(p.getLocation()) < 9);
                if (removed) { saveTerminals(); sender.sendMessage("§aNearest terminal removed."); }
                else sender.sendMessage("§cNo terminal within 3 blocks.");
            }
            case "list" -> {
                if (terminals.isEmpty()) { sender.sendMessage("§7No terminals registered."); return true; }
                sender.sendMessage("§eNether terminals (" + terminals.size() + "):");
                terminals.forEach(t -> sender.sendMessage("§7  " + t.getWorld().getName()
                        + " " + t.getBlockX() + ", " + t.getBlockY() + ", " + t.getBlockZ()));
            }
            default -> sender.sendMessage("§cUsage: /vnnether terminal <set|remove|list>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("terminal");
        if (args.length == 2 && args[0].equalsIgnoreCase("terminal"))
            return List.of("set", "remove", "list");
        return List.of();
    }
}
