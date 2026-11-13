package com.vestigium.vestigiumnpc.spy;

import com.vestigium.vestigiumnpc.VestigiumNPC;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Spy Network — players pay 15 Vestige Shards to a WanderingTrader informant
 * and name a target; 60 seconds later they receive the target's chunk location
 * and biome (current if online, last-known if offline).
 *
 * Offline last-known locations are written to player PDC on quit.
 * Shard counting is done inline to avoid a cross-plugin compile dependency.
 */
public class SpyNetworkManager implements Listener, CommandExecutor {

    private static final NamespacedKey SPY_NPC_KEY    = new NamespacedKey("vestigium", "spy_contact");
    private static final NamespacedKey SPY_CD_KEY     = new NamespacedKey("vestigium", "spy_contact_cd");
    private static final NamespacedKey LAST_LOC_KEY   = new NamespacedKey("vestigium", "last_known_loc");
    private static final NamespacedKey SHARD_ITEM_KEY = new NamespacedKey("vestigium", "vestige_shard");

    private static final int  SPY_COST     = 15;
    private static final long SPY_COOLDOWN = 30L * 60 * 1000;
    private static final long REPORT_DELAY = 60L * 20;

    private final VestigiumNPC plugin;
    private final Set<UUID> awaitingTarget = new HashSet<>();

    public SpyNetworkManager(VestigiumNPC plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vcspy");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[SpyNetworkManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // NPC interaction — WanderingTrader informant
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpyInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof WanderingTrader npc)) return;
        if (!npc.getPersistentDataContainer().has(SPY_NPC_KEY, PersistentDataType.BYTE)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        long now = System.currentTimeMillis();
        long cd = player.getPersistentDataContainer()
                .getOrDefault(SPY_CD_KEY, PersistentDataType.LONG, 0L);
        if (now < cd) {
            long mins = (cd - now) / 60000;
            player.sendMessage("§8[Informant] §7My sources need time. Check back in §f" + mins + " §7min.");
            return;
        }

        int shards = countShards(player);
        if (shards < SPY_COST) {
            player.sendMessage("§8[Informant] §7That kind of information costs §d"
                    + SPY_COST + " Vestige Shards§7. You have §f" + shards + "§7.");
            return;
        }

        player.sendMessage("§8[Informant] §7Who are you looking for? §8(Type their name in chat.)");
        awaitingTarget.add(player.getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Chat interception — capture target name
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!awaitingTarget.remove(player.getUniqueId())) return;

        event.setCancelled(true);
        String targetName = event.getMessage().trim();

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            int shards = countShards(player);
            if (shards < SPY_COST) {
                player.sendMessage("§8[Informant] §cYou can't afford this anymore.");
                return;
            }

            removeShards(player, SPY_COST);
            long now = System.currentTimeMillis();
            player.getPersistentDataContainer()
                    .set(SPY_CD_KEY, PersistentDataType.LONG, now + SPY_COOLDOWN);

            player.sendMessage("§8[Informant] §7My contacts are on it. Come back in a minute.");

            new BukkitRunnable() {
                @Override public void run() {
                    if (player.isOnline()) deliverReport(player, targetName);
                }
            }.runTaskLater(plugin, REPORT_DELAY);
        });
    }

    // -------------------------------------------------------------------------
    // Save last known location on quit
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        awaitingTarget.remove(p.getUniqueId());
        Location loc = p.getLocation();
        String encoded = loc.getWorld().getName()
                + "," + loc.getBlockX()
                + "," + loc.getBlockY()
                + "," + loc.getBlockZ();
        p.getPersistentDataContainer().set(LAST_LOC_KEY, PersistentDataType.STRING, encoded);
    }

    // -------------------------------------------------------------------------
    // /vcspy
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vestigium.spy.admin")) {
            sender.sendMessage("§cNo permission."); return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§7/vcspy <spawn | remove | list>"); return true;
        }
        switch (args[0].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cIn-game only."); return true;
                }
                player.getWorld().spawn(player.getLocation(), WanderingTrader.class, wt -> {
                    wt.setCustomName("§8§lInformant");
                    wt.setCustomNameVisible(true);
                    wt.setAI(false);
                    wt.setSilent(true);
                    wt.setInvulnerable(true);
                    wt.setPersistent(true);
                    wt.setDespawnDelay(Integer.MAX_VALUE);
                    wt.getPersistentDataContainer()
                            .set(SPY_NPC_KEY, PersistentDataType.BYTE, (byte) 1);
                });
                sender.sendMessage("§aSpy contact spawned.");
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cIn-game only."); return true;
                }
                boolean removed = player.getNearbyEntities(5, 5, 5).stream()
                        .filter(e -> e instanceof WanderingTrader wt
                                && wt.getPersistentDataContainer().has(SPY_NPC_KEY, PersistentDataType.BYTE))
                        .findFirst()
                        .map(e -> { e.remove(); return true; })
                        .orElse(false);
                sender.sendMessage(removed ? "§aSpy contact removed." : "§cNo spy contact within 5 blocks.");
            }
            case "list" -> {
                var contacts = new ArrayList<String>();
                for (World w : plugin.getServer().getWorlds()) {
                    for (WanderingTrader wt : w.getEntitiesByClass(WanderingTrader.class)) {
                        if (wt.getPersistentDataContainer().has(SPY_NPC_KEY, PersistentDataType.BYTE)) {
                            Location l = wt.getLocation();
                            contacts.add(w.getName() + " " + l.getBlockX()
                                    + "," + l.getBlockY() + "," + l.getBlockZ());
                        }
                    }
                }
                sender.sendMessage("§6Spy contacts: §f" + contacts.size());
                contacts.forEach(s -> sender.sendMessage("  §7" + s));
            }
            default -> sender.sendMessage("§7/vcspy <spawn | remove | list>");
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Report delivery
    // -------------------------------------------------------------------------

    private void deliverReport(Player requester, String targetName) {
        Player target = plugin.getServer().getPlayerExact(targetName);
        if (target != null) {
            Location loc = target.getLocation();
            requester.sendMessage("§8[Informant] §7Found §f" + target.getName() + "§7.");
            requester.sendMessage("§7  Chunk: §f" + loc.getChunk().getX()
                    + ", " + loc.getChunk().getZ()
                    + " §7in §f" + loc.getWorld().getName());
            requester.sendMessage("§7  Biome: §f" + formatBiome(loc.getBlock().getBiome().name()));
        } else {
            OfflinePlayer offline = findOffline(targetName);
            if (offline == null) {
                requester.sendMessage("§8[Informant] §7I couldn't find anyone by that name.");
                return;
            }
            // Load last known location from PDC (set on quit)
            org.bukkit.entity.Player loaded = offline.getPlayer();
            String locStr = loaded == null ? null
                    : loaded.getPersistentDataContainer().get(LAST_LOC_KEY, PersistentDataType.STRING);
            if (locStr == null) {
                requester.sendMessage("§8[Informant] §7" + offline.getName()
                        + " is off the grid. No trace.");
                return;
            }
            String[] parts = locStr.split(",");
            if (parts.length < 4) {
                requester.sendMessage("§8[Informant] §7The trail went cold.");
                return;
            }
            int cx = Integer.parseInt(parts[1]) >> 4;
            int cz = Integer.parseInt(parts[3]) >> 4;
            requester.sendMessage("§8[Informant] §7Last seen: §f" + offline.getName() + "§7.");
            requester.sendMessage("§7  Last known chunk: §f" + cx + ", " + cz
                    + " §7in §f" + parts[0] + " §8(offline)");
        }
        requester.playSound(requester.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.5f, 0.8f);
    }

    private OfflinePlayer findOffline(String name) {
        for (OfflinePlayer op : plugin.getServer().getOfflinePlayers()) {
            if (name.equalsIgnoreCase(op.getName())) return op;
        }
        return null;
    }

    private String formatBiome(String rawBiome) {
        return Arrays.stream(rawBiome.toLowerCase().split("_"))
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }

    // -------------------------------------------------------------------------
    // Inline shard helpers — avoids cross-plugin compile dependency
    // -------------------------------------------------------------------------

    private int countShards(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            if (item.getItemMeta().getPersistentDataContainer()
                    .getOrDefault(SHARD_ITEM_KEY, PersistentDataType.BOOLEAN, false)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeShards(Player player, int amount) {
        int remaining = amount;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize() && remaining > 0; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || !item.hasItemMeta()) continue;
            if (!item.getItemMeta().getPersistentDataContainer()
                    .getOrDefault(SHARD_ITEM_KEY, PersistentDataType.BOOLEAN, false)) continue;
            int take = Math.min(item.getAmount(), remaining);
            remaining -= take;
            if (item.getAmount() - take == 0) inv.setItem(i, null);
            else item.setAmount(item.getAmount() - take);
        }
    }
}
