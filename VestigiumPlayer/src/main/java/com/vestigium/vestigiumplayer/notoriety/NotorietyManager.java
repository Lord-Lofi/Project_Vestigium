package com.vestigium.vestigiumplayer.notoriety;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import com.vestigium.vestigiumplayer.data.PlayerDataStore;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Notoriety — a destructive-action accumulator, separate from reputation.
 *
 * Thresholds:
 *   0–24   CLEAR        — no effects
 *   25–49  WANTED       — merchant surcharge +10%
 *   50–74  INFAMOUS     — surcharge +25%; wandering traders refuse business
 *   75–99  NOTORIOUS    — surcharge +50%; iron golems aggro on sight; bounty hunters spawn
 *   100+   MOST_WANTED  — surcharge +75%; faster bounty hunters; server-wide announcement
 *
 * Triggers:
 *   Villager kill    +10
 *   Player kill (overworld PvP)  +15
 *   Village bell broken  +20
 *
 * Decay: −2 every 5 minutes (online only).
 *
 * PDC key on player: vestigium:notoriety (INTEGER)
 * PDC key on bounty hunter: vestigium:bounty_target (STRING, player UUID)
 */
public class NotorietyManager implements Listener {

    // -------------------------------------------------------------------------
    // Levels
    // -------------------------------------------------------------------------

    public enum Level {
        CLEAR      (0,   "§aClean",      1.00),
        WANTED     (25,  "§eWanted",     1.10),
        INFAMOUS   (50,  "§6Infamous",   1.25),
        NOTORIOUS  (75,  "§cNotorious",  1.50),
        MOST_WANTED(100, "§4Most Wanted",1.75);

        public final int    threshold;
        public final String display;
        public final double priceMultiplier;

        Level(int threshold, String display, double priceMultiplier) {
            this.threshold       = threshold;
            this.display         = display;
            this.priceMultiplier = priceMultiplier;
        }

        public static Level of(int notoriety) {
            Level result = CLEAR;
            for (Level l : values()) {
                if (notoriety >= l.threshold) result = l;
            }
            return result;
        }
    }

    // -------------------------------------------------------------------------

    private static final NamespacedKey BOUNTY_TARGET_KEY =
            new NamespacedKey("vestigium", "bounty_target");
    private static final long BOUNTY_COOLDOWN_MS = 10 * 60 * 1000L; // 10 minutes

    private final VestigiumPlayer plugin;
    private final PlayerDataStore  dataStore;
    private final Map<UUID, Long>  lastBountySpawn = new HashMap<>();
    private BukkitRunnable         effectTask;
    private BukkitRunnable         decayTask;

    public NotorietyManager(VestigiumPlayer plugin, PlayerDataStore dataStore) {
        this.plugin    = plugin;
        this.dataStore = dataStore;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startEffectTask();
        startDecayTask();
        registerCommand();
        plugin.getLogger().info("[NotorietyManager] Initialized.");
    }

    public void shutdown() {
        if (effectTask != null) effectTask.cancel();
        if (decayTask  != null) decayTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public int getNotoriety(Player player) {
        return dataStore.getInt(player, PlayerDataStore.KEY_NOTORIETY);
    }

    public Level getLevel(Player player) {
        return Level.of(getNotoriety(player));
    }

    public void addNotoriety(Player player, int amount) {
        int current = getNotoriety(player);
        int next    = Math.min(current + amount, 200);
        if (next == current) return;

        Level before = Level.of(current);
        Level after  = Level.of(next);

        dataStore.addInt(player, PlayerDataStore.KEY_NOTORIETY, next - current);

        if (after.threshold > before.threshold) {
            player.sendMessage("§c[Notoriety] Your status has risen to " + after.display + "§c.");
            onThresholdCrossed(player, after);
        }
    }

    public void setNotoriety(Player player, int amount) {
        int clamped = Math.max(0, Math.min(amount, 200));
        player.getPersistentDataContainer()
                .set(PlayerDataStore.KEY_NOTORIETY, PersistentDataType.INTEGER, clamped);
    }

    // -------------------------------------------------------------------------
    // Triggers
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        if (event.getEntity() instanceof Villager) {
            addNotoriety(killer, 10);
            killer.sendMessage("§7[Notoriety] Killing a villager weighs on your reputation. §c(+10)");
        } else if (event.getEntity() instanceof Player victim && !victim.equals(killer)
                && killer.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL) {
            addNotoriety(killer, 15);
            killer.sendMessage("§7[Notoriety] This death follows you. §c(+15)");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBellBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.BELL) return;
        addNotoriety(event.getPlayer(), 20);
        event.getPlayer().sendMessage("§7[Notoriety] Destroying a village bell marks you as an enemy. §c(+20)");
    }

    // -------------------------------------------------------------------------
    // Merchant surcharge — InventoryClickEvent on result slot (slot 2)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTradeComplete(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory() instanceof MerchantInventory merchantInv)) return;
        if (event.getRawSlot() != 2) return;

        Level level = getLevel(player);
        if (level.priceMultiplier <= 1.0) return;

        // Block shift-click bulk trading to prevent surcharge stacking
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
            player.sendMessage("§6[Notoriety] §7Bulk trading is restricted due to your reputation.");
            return;
        }

        MerchantRecipe recipe = merchantInv.getSelectedRecipe();
        if (recipe == null) return;

        List<ItemStack> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return;

        ItemStack firstIngredient = ingredients.get(0);
        int baseAmount = firstIngredient.getAmount();
        int extraCost  = (int) Math.ceil(baseAmount * level.priceMultiplier) - baseAmount;
        if (extraCost <= 0) return;

        Material currency  = firstIngredient.getType();
        int available = countInInventory(player, currency);

        if (available < extraCost) {
            event.setCancelled(true);
            player.sendMessage("§6[Notoriety] §7This merchant demands §e" + extraCost
                    + " extra " + formatMaterial(currency)
                    + " §7as a surcharge. Your reputation precedes you.");
            return;
        }

        // Remove surcharge on the next tick, after the trade resolves
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Map<Integer, ItemStack> leftover =
                    player.getInventory().removeItem(new ItemStack(currency, extraCost));
            if (leftover.isEmpty()) {
                player.sendActionBar(Component.text(
                        "§6[Notoriety] Surcharge paid: " + extraCost + " " + formatMaterial(currency)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Bounty hunter death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBountyHunterDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Pillager pillager)) return;

        String targetUUIDStr = pillager.getPersistentDataContainer()
                .get(BOUNTY_TARGET_KEY, PersistentDataType.STRING);
        if (targetUUIDStr == null) return;

        // Drop Bounty Coin
        ItemStack coin = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta  meta = coin.getItemMeta();
        meta.setDisplayName("§6Bounty Coin");
        meta.setLore(List.of("§8Proof of a bounty fulfilled."));
        coin.setItemMeta(meta);
        event.getDrops().add(coin);

        // Reward: reduce the target player's notoriety
        try {
            Player target = plugin.getServer().getPlayer(UUID.fromString(targetUUIDStr));
            if (target != null) {
                setNotoriety(target, Math.max(0, getNotoriety(target) - 5));
                target.sendMessage("§a[Notoriety] §7A bounty hunter has been slain. §a(−5)");
            }
        } catch (IllegalArgumentException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Effect task — golem aggro + bounty spawns (every 2 s)
    // -------------------------------------------------------------------------

    private void startEffectTask() {
        effectTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    Level level = getLevel(player);
                    if (level.threshold >= Level.NOTORIOUS.threshold) {
                        aggroNearbyGolems(player);
                        trySpawnBountyHunter(player, level);
                    }
                }
            }
        };
        effectTask.runTaskTimer(plugin, 40L, 40L);
    }

    private void aggroNearbyGolems(Player player) {
        player.getWorld()
                .getNearbyEntities(player.getLocation(), 20, 20, 20).stream()
                .filter(e -> e instanceof IronGolem g && g.getTarget() == null)
                .forEach(e -> ((IronGolem) e).setTarget(player));
    }

    private void trySpawnBountyHunter(Player player, Level level) {
        long now  = System.currentTimeMillis();
        long last = lastBountySpawn.getOrDefault(player.getUniqueId(), 0L);
        long cooldown = level == Level.MOST_WANTED ? BOUNTY_COOLDOWN_MS / 2 : BOUNTY_COOLDOWN_MS;
        if (now - last < cooldown) return;

        lastBountySpawn.put(player.getUniqueId(), now);

        // Spawn 10 blocks away from the player at surface level
        double angle = ThreadLocalRandom.current().nextDouble() * 2 * Math.PI;
        org.bukkit.Location spawnLoc = player.getLocation().add(
                Math.cos(angle) * 10, 0, Math.sin(angle) * 10);
        spawnLoc = spawnLoc.getWorld()
                .getHighestBlockAt(spawnLoc).getLocation().add(0, 1, 0);

        Pillager bounty = (Pillager) spawnLoc.getWorld()
                .spawnEntity(spawnLoc, EntityType.PILLAGER);
        bounty.setCustomName("§c[Bounty Hunter]");
        bounty.setCustomNameVisible(true);
        bounty.getPersistentDataContainer()
                .set(BOUNTY_TARGET_KEY, PersistentDataType.STRING, player.getUniqueId().toString());
        bounty.setTarget(player);

        player.sendMessage("§c[Notoriety] A bounty hunter is tracking you.");

        if (level == Level.MOST_WANTED) {
            plugin.getServer().broadcastMessage(
                    "§4[WANTED] §7A bounty has been placed on §c" + player.getName() + "§7.");
        }
    }

    // -------------------------------------------------------------------------
    // Decay task — −2 notoriety every 5 minutes (online only)
    // -------------------------------------------------------------------------

    private void startDecayTask() {
        decayTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    int current = getNotoriety(player);
                    if (current <= 0) continue;

                    Level before = Level.of(current);
                    int   next   = Math.max(0, current - 2);
                    Level after  = Level.of(next);
                    setNotoriety(player, next);

                    if (next == 0) {
                        player.sendMessage("§a[Notoriety] Your slate is clean.");
                    } else if (after.threshold < before.threshold) {
                        player.sendMessage("§a[Notoriety] Your status has improved to " + after.display + "§a.");
                    }
                }
            }
        };
        decayTask.runTaskTimer(plugin, 6000L, 6000L);
    }

    // -------------------------------------------------------------------------
    // Threshold crossed — first-time notifications
    // -------------------------------------------------------------------------

    private void onThresholdCrossed(Player player, Level level) {
        switch (level) {
            case WANTED ->
                player.sendMessage("§e[Notoriety] §7Merchants will now charge more for their goods.");
            case INFAMOUS -> {
                player.sendMessage("§6[Notoriety] §7Wandering traders will refuse your business.");
                player.sendMessage("§6[Notoriety] §7Some guild doors are now closed to you.");
            }
            case NOTORIOUS -> {
                player.sendMessage("§c[Notoriety] §7Iron golems in villages will attack you on sight.");
                player.sendMessage("§c[Notoriety] §7Bounty hunters will now track you down.");
            }
            case MOST_WANTED ->
                plugin.getServer().broadcastMessage(
                        "§4[WANTED] §7" + player.getName() + " §7has become §4Most Wanted§7.");
            default -> {}
        }
    }

    // -------------------------------------------------------------------------
    // Command — /vpnotoriety [check|clear|add] [player] [amount]
    // -------------------------------------------------------------------------

    private void registerCommand() {
        var cmd = plugin.getCommand("vpnotoriety");
        if (cmd == null) return;

        cmd.setExecutor((sender, command, label, args) -> {
            if (args.length == 0) {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§7Usage: /vpnotoriety check|clear|add <player> [amount]");
                    return true;
                }
                printNotoriety(p, p);
                return true;
            }
            switch (args[0].toLowerCase()) {
                case "check" -> {
                    Player target = resolveTarget(sender, args, 1);
                    if (target == null) return true;
                    printNotoriety(sender, target);
                }
                case "clear" -> {
                    if (!sender.hasPermission("vestigium.notoriety.admin")) {
                        sender.sendMessage("§cNo permission.");
                        return true;
                    }
                    Player target = resolveTarget(sender, args, 1);
                    if (target == null) return true;
                    setNotoriety(target, 0);
                    sender.sendMessage("§aCleared notoriety for " + target.getName() + ".");
                    target.sendMessage("§a[Notoriety] Your record has been cleared by an admin.");
                }
                case "add" -> {
                    if (!sender.hasPermission("vestigium.notoriety.admin")) {
                        sender.sendMessage("§cNo permission.");
                        return true;
                    }
                    if (args.length < 3) {
                        sender.sendMessage("§7Usage: /vpnotoriety add <player> <amount>");
                        return true;
                    }
                    Player target = plugin.getServer().getPlayer(args[1]);
                    if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
                    try {
                        addNotoriety(target, Integer.parseInt(args[2]));
                        sender.sendMessage("§7Added " + args[2] + " notoriety to "
                                + target.getName() + " (now " + getNotoriety(target) + ").");
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cInvalid amount.");
                    }
                }
                default -> sender.sendMessage("§7Usage: /vpnotoriety [check|clear|add] [player] [amount]");
            }
            return true;
        });

        cmd.setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) return List.of("check", "clear", "add");
            if (args.length == 2) return plugin.getServer().getOnlinePlayers()
                    .stream().map(Player::getName).toList();
            return List.of();
        });
    }

    private void printNotoriety(org.bukkit.command.CommandSender sender, Player player) {
        int   n     = getNotoriety(player);
        Level level = Level.of(n);
        sender.sendMessage("§7" + player.getName() + " — Notoriety: §c" + n + "/200 §7(" + level.display + "§7)");
        int surcharge = (int) ((level.priceMultiplier - 1.0) * 100);
        if (surcharge > 0)
            sender.sendMessage("  §7Merchant surcharge: §e+" + surcharge + "%");
        if (level.threshold >= Level.NOTORIOUS.threshold)
            sender.sendMessage("  §cIron golems aggro on sight. Bounty hunters active.");
    }

    private Player resolveTarget(org.bukkit.command.CommandSender sender,
                                  String[] args, int index) {
        if (args.length > index) {
            Player t = plugin.getServer().getPlayer(args[index]);
            if (t == null) { sender.sendMessage("§cPlayer not found."); return null; }
            return t;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage("§cSpecify a player.");
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int countInInventory(Player player, Material material) {
        return Arrays.stream(player.getInventory().getStorageContents())
                .filter(is -> is != null && is.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    private String formatMaterial(Material m) {
        return m.name().toLowerCase().replace("_", " ");
    }
}
