package com.vestigium.vestigiumstructures.waystone;

import com.vestigium.lib.VestigiumLib;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Waystone Network — Antecedent transit infrastructure at ley line intersections.
 *
 * Physical block: LODESTONE tagged with vestigium:waystone STRING (value = waystone id).
 * Right-clicking a waystone shows the list of available destinations and their energy cost.
 * /vwaystone go <id> performs the teleport — checks cost, deducts energy, teleports.
 *
 * Energy:
 *   Each waystone has a stored energy pool (max 1000).
 *   Cost to travel = max(5, distance / 100) energy drawn from the SOURCE waystone.
 *   Energy regenerates at 1 unit per minute per waystone (60-tick task).
 *
 * Lore: First use of any waystone grants the cartographer_waystone_1_arrive fragment.
 *
 * Admin: /vwaystone add <id> [name...] — register waystone at player location (LODESTONE block)
 *         /vwaystone remove <id>
 *         /vwaystone list
 *         /vwaystone energy set <id> <amount>
 *         /vwaystone energy refill <id>
 *         /vwaystone go <id>  (player travel command, requires standing at a waystone)
 */
public class WaystoneManager implements Listener, CommandExecutor, TabCompleter {

    private static final NamespacedKey WAYSTONE_KEY      = new NamespacedKey("vestigium", "waystone");
    private static final NamespacedKey WAYSTONE_USED_KEY = new NamespacedKey("vestigium", "waystone_used");

    private static final int    ENERGY_MAX      = 1000;
    private static final int    ENERGY_REGEN    = 1;
    private static final int    PROXIMITY_RANGE = 6;

    private final JavaPlugin plugin;
    private final Map<String, WaystoneRecord> waystones = new LinkedHashMap<>();
    private final Map<UUID, String> pendingTravel = new HashMap<>();

    private BukkitTask regenTask;
    private File dataFile;

    public WaystoneManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("vwaystone").setExecutor(this);
        plugin.getCommand("vwaystone").setTabCompleter(this);

        dataFile = new File(plugin.getDataFolder(), "waystones.yml");
        plugin.getDataFolder().mkdirs();
        loadWaystones();

        regenTask = new BukkitRunnable() {
            @Override public void run() { regenEnergy(); }
        }.runTaskTimer(plugin, 1200L, 1200L);

        plugin.getLogger().info("[WaystoneManager] Loaded " + waystones.size() + " waystone(s).");
    }

    public void shutdown() {
        if (regenTask != null) regenTask.cancel();
        saveWaystones();
    }

    // ── Block interaction ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LODESTONE) return;

        String wsId = block.getChunk().getPersistentDataContainer()
                .get(WAYSTONE_KEY, PersistentDataType.STRING);
        if (wsId == null) {
            // Try block PDC via tile entity — Lodestone is a block entity
            // Fallback: scan waystones by location
            wsId = findWaystoneAt(block.getLocation());
        }
        if (wsId == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        WaystoneRecord ws = waystones.get(wsId);
        if (ws == null) return;

        pendingTravel.put(player.getUniqueId(), wsId);
        showDestinations(player, ws);
    }

    private String findWaystoneAt(Location loc) {
        for (WaystoneRecord ws : waystones.values()) {
            Location wl = ws.location();
            if (wl == null) continue;
            if (Objects.equals(wl.getWorld(), loc.getWorld())
                    && wl.getBlockX() == loc.getBlockX()
                    && wl.getBlockY() == loc.getBlockY()
                    && wl.getBlockZ() == loc.getBlockZ()) {
                return ws.id();
            }
        }
        return null;
    }

    private void showDestinations(Player player, WaystoneRecord source) {
        player.sendMessage("§6§l[ Waystone Network ]");
        player.sendMessage("§8" + source.displayName() + " — stored energy: §e" + source.energy() + "§8/" + ENERGY_MAX);
        player.sendMessage("§7Available destinations:");

        List<WaystoneRecord> dests = waystones.values().stream()
                .filter(w -> !w.id().equals(source.id()) && w.location() != null)
                .collect(Collectors.toList());

        if (dests.isEmpty()) {
            player.sendMessage("§8  No other waystones registered.");
            return;
        }

        for (WaystoneRecord dest : dests) {
            int cost = travelCost(source, dest);
            String affordable = source.energy() >= cost ? "§a" : "§c";
            player.sendMessage("  " + affordable + dest.id()
                    + " §8— §7" + dest.displayName()
                    + " §8(" + dist(source, dest) + "m, costs §e" + cost + " §8energy)");
        }
        player.sendMessage("§8Use §7/vwaystone go <id> §8to travel.");
    }

    // ── Command ───────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { printHelp(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "add" -> handleAdd(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            case "energy" -> handleEnergy(sender, args);
            case "go" -> handleGo(sender, args);
            default -> printHelp(sender);
        }
        return true;
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vestigium.structures.admin")) { sender.sendMessage("§cNo permission."); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage("§cMust be a player."); return; }
        if (args.length < 2) { sender.sendMessage("§cUsage: /vwaystone add <id> [name...]"); return; }
        String id = args[1].toLowerCase();
        String name = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : id;

        Block target = p.getTargetBlockExact(5);
        if (target == null || target.getType() != Material.LODESTONE) {
            sender.sendMessage("§cLook at a LODESTONE block to register it as a waystone."); return;
        }

        waystones.put(id, new WaystoneRecord(id, name, target.getLocation(), ENERGY_MAX));
        target.getChunk().getPersistentDataContainer()
                .set(WAYSTONE_KEY, PersistentDataType.STRING, id);
        saveWaystones();
        sender.sendMessage("§aWaystone '" + id + "' registered at " + formatLoc(target.getLocation()) + ".");
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vestigium.structures.admin")) { sender.sendMessage("§cNo permission."); return; }
        if (args.length < 2) { sender.sendMessage("§cUsage: /vwaystone remove <id>"); return; }
        String id = args[1].toLowerCase();
        WaystoneRecord ws = waystones.remove(id);
        if (ws != null) {
            saveWaystones();
            sender.sendMessage("§aWaystone removed.");
        } else sender.sendMessage("§cWaystone not found: " + id);
    }

    private void handleList(CommandSender sender) {
        if (!sender.hasPermission("vestigium.structures.admin")) { sender.sendMessage("§cNo permission."); return; }
        if (waystones.isEmpty()) { sender.sendMessage("§7No waystones registered."); return; }
        sender.sendMessage("§8=== Waystone Network ===");
        waystones.values().forEach(w -> sender.sendMessage(
                "§6" + w.id() + " §8— §7" + w.displayName()
                + " §8energy: §e" + w.energy() + "§8/" + ENERGY_MAX
                + " " + (w.location() != null ? formatLoc(w.location()) : "§cno location")));
    }

    private void handleEnergy(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vestigium.structures.admin")) { sender.sendMessage("§cNo permission."); return; }
        if (args.length < 3) { sender.sendMessage("§cUsage: /vwaystone energy <set|refill> <id> [amount]"); return; }
        switch (args[1].toLowerCase()) {
            case "set" -> {
                if (args.length < 4) { sender.sendMessage("§cUsage: /vwaystone energy set <id> <amount>"); return; }
                String id = args[2].toLowerCase();
                int amount;
                try { amount = Integer.parseInt(args[3]); } catch (NumberFormatException e) {
                    sender.sendMessage("§cAmount must be a number."); return;
                }
                WaystoneRecord ws = waystones.get(id);
                if (ws == null) { sender.sendMessage("§cWaystone not found."); return; }
                waystones.put(id, ws.withEnergy(Math.max(0, Math.min(ENERGY_MAX, amount))));
                saveWaystones();
                sender.sendMessage("§aEnergy set to " + amount + " for '" + id + "'.");
            }
            case "refill" -> {
                if (args.length < 3) { sender.sendMessage("§cUsage: /vwaystone energy refill <id>"); return; }
                String id = args[2].toLowerCase();
                WaystoneRecord ws = waystones.get(id);
                if (ws == null) { sender.sendMessage("§cWaystone not found."); return; }
                waystones.put(id, ws.withEnergy(ENERGY_MAX));
                saveWaystones();
                sender.sendMessage("§aWaystone '" + id + "' refilled to " + ENERGY_MAX + ".");
            }
            default -> sender.sendMessage("§cUnknown energy sub-command.");
        }
    }

    private void handleGo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("§cMust be a player."); return; }
        if (args.length < 2) { sender.sendMessage("§cUsage: /vwaystone go <destinationId>"); return; }

        String sourceId = pendingTravel.get(player.getUniqueId());
        if (sourceId == null) {
            sender.sendMessage("§cInteract with a waystone first to open the network."); return;
        }

        String destId = args[1].toLowerCase();
        WaystoneRecord source = waystones.get(sourceId);
        WaystoneRecord dest   = waystones.get(destId);

        if (source == null) { sender.sendMessage("§cSource waystone not found."); return; }
        if (dest == null)   { sender.sendMessage("§cDestination not found: " + destId); return; }
        if (source.id().equals(dest.id())) { sender.sendMessage("§cAlready here."); return; }

        // Confirm player is still near source waystone
        if (!isNearWaystone(player, source)) {
            pendingTravel.remove(player.getUniqueId());
            sender.sendMessage("§cYou have moved too far from the waystone.");
            return;
        }

        int cost = travelCost(source, dest);
        if (source.energy() < cost) {
            sender.sendMessage("§cInsufficient waystone energy (" + source.energy() + "/" + cost + " required).");
            return;
        }

        // Deduct and teleport
        waystones.put(sourceId, source.withEnergy(source.energy() - cost));
        pendingTravel.remove(player.getUniqueId());
        saveWaystones();

        Location arrival = dest.location().clone().add(0.5, 1, 0.5);
        player.teleport(arrival);
        player.sendMessage("§6[Waystone] §7Arrived at §f" + dest.displayName() + "§7. Energy expended: §e" + cost + "§7.");
        player.getWorld().playSound(arrival, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.7f);
        player.getWorld().spawnParticle(Particle.END_ROD, arrival.clone().add(0, 1, 0), 20, 0.5, 1, 0.5, 0.05);

        // First use fragment
        if (!player.getPersistentDataContainer().has(WAYSTONE_USED_KEY, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().set(WAYSTONE_USED_KEY, PersistentDataType.BYTE, (byte) 1);
            VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), "cartographer_waystone_1_arrive");
            player.sendMessage("§6[Waystone] §7First transit logged to your record.");
        }
    }

    private void printHelp(CommandSender sender) {
        sender.sendMessage("§8=== /vwaystone ===");
        if (sender.hasPermission("vestigium.structures.admin")) {
            sender.sendMessage("§7add <id> [name...] §8— register looked-at LODESTONE");
            sender.sendMessage("§7remove <id> §8— remove waystone");
            sender.sendMessage("§7list §8— list all waystones");
            sender.sendMessage("§7energy set <id> <n> §8— set energy");
            sender.sendMessage("§7energy refill <id> §8— set to max");
        }
        sender.sendMessage("§7go <id> §8— travel to destination (interact with waystone first)");
    }

    // ── Energy regen ──────────────────────────────────────────────────────────

    private void regenEnergy() {
        boolean changed = false;
        for (Map.Entry<String, WaystoneRecord> entry : waystones.entrySet()) {
            WaystoneRecord ws = entry.getValue();
            if (ws.energy() < ENERGY_MAX) {
                waystones.put(entry.getKey(), ws.withEnergy(Math.min(ENERGY_MAX, ws.energy() + ENERGY_REGEN)));
                changed = true;
            }
        }
        if (changed) saveWaystones();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int travelCost(WaystoneRecord from, WaystoneRecord to) {
        return Math.max(5, dist(from, to) / 100);
    }

    private int dist(WaystoneRecord from, WaystoneRecord to) {
        if (from.location() == null || to.location() == null) return 1000;
        return (int) from.location().distance(to.location());
    }

    private boolean isNearWaystone(Player player, WaystoneRecord ws) {
        if (ws.location() == null) return false;
        if (!Objects.equals(player.getWorld(), ws.location().getWorld())) return false;
        return player.getLocation().distance(ws.location()) <= PROXIMITY_RANGE + 2;
    }

    private String formatLoc(Location loc) {
        if (loc == null) return "unknown";
        return "§8(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")";
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void loadWaystones() {
        if (!dataFile.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        for (String id : cfg.getKeys(false)) {
            String display   = cfg.getString(id + ".display", id);
            int energy       = cfg.getInt(id + ".energy", ENERGY_MAX);
            String worldName = cfg.getString(id + ".world", "world");
            double x = cfg.getDouble(id + ".x");
            double y = cfg.getDouble(id + ".y");
            double z = cfg.getDouble(id + ".z");
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                waystones.put(id, new WaystoneRecord(id, display, null, energy));
                continue;
            }
            waystones.put(id, new WaystoneRecord(id, display, new Location(world, x, y, z), energy));
        }
    }

    private void saveWaystones() {
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        for (WaystoneRecord ws : waystones.values()) {
            cfg.set(ws.id() + ".display", ws.displayName());
            cfg.set(ws.id() + ".energy", ws.energy());
            if (ws.location() != null && ws.location().getWorld() != null) {
                cfg.set(ws.id() + ".world", ws.location().getWorld().getName());
                cfg.set(ws.id() + ".x", ws.location().getX());
                cfg.set(ws.id() + ".y", ws.location().getY());
                cfg.set(ws.id() + ".z", ws.location().getZ());
            }
        }
        try { cfg.save(dataFile); } catch (IOException e) {
            plugin.getLogger().warning("[WaystoneManager] Could not save waystones.yml: " + e.getMessage());
        }
    }

    // ── TabCompleter ──────────────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> base = new ArrayList<>(List.of("go"));
            if (sender.hasPermission("vestigium.structures.admin"))
                base.addAll(List.of("add", "remove", "list", "energy"));
            return filter(base, args[0]);
        }
        if (args[0].equalsIgnoreCase("go") && args.length == 2)
            return filter(new ArrayList<>(waystones.keySet()), args[1]);
        if (args[0].equalsIgnoreCase("remove") && args.length == 2)
            return filter(new ArrayList<>(waystones.keySet()), args[1]);
        if (args[0].equalsIgnoreCase("energy")) {
            if (args.length == 2) return filter(List.of("set", "refill"), args[1]);
            if (args.length == 3) return filter(new ArrayList<>(waystones.keySet()), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String partial) {
        String p = partial.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(p)).collect(Collectors.toList());
    }
}
