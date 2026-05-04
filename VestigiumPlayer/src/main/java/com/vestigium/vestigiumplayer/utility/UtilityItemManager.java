package com.vestigium.vestigiumplayer.utility;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Egg;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.block.Biome;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.StructureSearchResult;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class UtilityItemManager implements Listener, CommandExecutor, TabCompleter {

    public enum UtilityItem {
        SOULBOUND_COMPASS("soulbound_compass", Material.COMPASS, 20001, "§6Soulbound Compass",
                List.of("§7Points to your last death location.", "§7Right-click to refresh.")),
        HEADLAMP("headlamp", Material.LEATHER_HELMET, 20002, "§eHeadlamp",
                List.of("§7Grants Night Vision while worn.")),
        SPELUNKERS_LANTERN("spelunkers_lantern", Material.TORCH, 20003, "§eSpelunker's Lantern",
                List.of("§7Night Vision + flame particles while held.")),
        CARTOGRAPHERS_LENS("cartographers_lens", Material.FILLED_MAP, 20004, "§bCartographer's Lens",
                List.of("§7Right-click to inspect your surroundings.", "§7Shows biome, depth, light, and nearby structures.")),
        DEPTH_CHARTS("depth_charts", Material.PAPER, 20005, "§aDepth Charts",
                List.of("§7Shows depth, biome, light level, and ore tier on actionbar while held.")),
        RESONANT_DOWSING_ROD("resonant_dowsing_rod", Material.BLAZE_ROD, 20006, "§dResonant Dowsing Rod",
                List.of("§7Locates Ancient Cities, Strongholds, and Trail Ruins.", "§730s cooldown.")),
        SMOKE_BOMB("smoke_bomb", Material.EGG, 20007, "§8Smoke Bomb",
                List.of("§7Right-click to throw.", "§7Blindness + Slowness on impact."));

        private final String id;
        private final Material material;
        private final int cmd;
        private final String displayName;
        private final List<String> lore;

        UtilityItem(String id, Material material, int cmd, String displayName, List<String> lore) {
            this.id = id;
            this.material = material;
            this.cmd = cmd;
            this.displayName = displayName;
            this.lore = lore;
        }

        public String id()          { return id; }
        public Material material()  { return material; }
        public int cmd()            { return cmd; }
        public String displayName() { return displayName; }
        public List<String> lore()  { return lore; }

        public static Optional<UtilityItem> fromId(String id) {
            for (UtilityItem u : values()) if (u.id.equals(id)) return Optional.of(u);
            return Optional.empty();
        }
    }

    private static final NamespacedKey UTILITY_ITEM_KEY = new NamespacedKey("vestigium", "utility_item");
    private static final NamespacedKey DEATH_LOC_KEY    = new NamespacedKey("vestigium", "last_death_loc");
    private static final NamespacedKey SMOKE_BOMB_KEY   = new NamespacedKey("vestigium", "smoke_bomb_throw");
    private static final NamespacedKey DOWSING_COOLDOWN = new NamespacedKey("vestigium", "dowsing_cooldown");

    private final VestigiumPlayer plugin;
    private BukkitRunnable effectTask;

    public UtilityItemManager(VestigiumPlayer plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getCommand("vpitem").setExecutor(this);
        plugin.getCommand("vpitem").setTabCompleter(this);
        startEffectTask();
        plugin.getLogger().info("[UtilityItemManager] Initialized.");
    }

    public void shutdown() {
        if (effectTask != null) effectTask.cancel();
    }

    // -------------------------------------------------------------------------
    // Passive effect task (40-tick tick)
    // -------------------------------------------------------------------------

    private void startEffectTask() {
        effectTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : plugin.getServer().getOnlinePlayers()) tickPassive(p);
            }
        };
        effectTask.runTaskTimer(plugin, 40L, 40L);
    }

    private void tickPassive(Player p) {
        ItemStack helmet = p.getInventory().getHelmet();
        if (isItem(helmet, UtilityItem.HEADLAMP)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 80, 0, true, false));
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isItem(hand, UtilityItem.SPELUNKERS_LANTERN)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 80, 0, true, false));
            p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.5, 0), 3, 0.15, 0.15, 0.15, 0.01);
        }

        if (isItem(hand, UtilityItem.DEPTH_CHARTS)) {
            sendDepthBar(p);
        }
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        Location loc = p.getLocation();
        String encoded = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        p.getPersistentDataContainer().set(DEATH_LOC_KEY, PersistentDataType.STRING, encoded);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) return;

        if (isItem(item, UtilityItem.SOULBOUND_COMPASS)) {
            event.setCancelled(true);
            activateSoulboundCompass(player);
        } else if (isItem(item, UtilityItem.CARTOGRAPHERS_LENS)) {
            event.setCancelled(true);
            activateCartographersLens(player);
        } else if (isItem(item, UtilityItem.RESONANT_DOWSING_ROD)) {
            event.setCancelled(true);
            activateDowsingRod(player, item);
        } else if (isItem(item, UtilityItem.SMOKE_BOMB)) {
            event.setCancelled(true);
            throwSmokeBomb(player, item);
        }
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Egg egg)) return;
        if (!egg.getPersistentDataContainer().has(SMOKE_BOMB_KEY, PersistentDataType.STRING)) return;
        Location loc = egg.getLocation();
        loc.getWorld().spawnParticle(Particle.SQUID_INK, loc, 60, 0.8, 0.8, 0.8, 0.05);
        for (LivingEntity le : loc.getNearbyLivingEntities(4)) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 1, false, false));
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 0, false, false));
        }
    }

    // -------------------------------------------------------------------------
    // Active abilities
    // -------------------------------------------------------------------------

    private void activateSoulboundCompass(Player player) {
        String encoded = player.getPersistentDataContainer().get(DEATH_LOC_KEY, PersistentDataType.STRING);
        if (encoded == null) {
            player.sendActionBar(Component.text("§cNo death location recorded yet."));
            return;
        }
        String[] parts = encoded.split(",");
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) {
            player.sendActionBar(Component.text("§cDeath world is unavailable."));
            return;
        }
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);
        Location deathLoc = new Location(world, x, y, z);
        player.setCompassTarget(deathLoc);
        int dist = (int) player.getLocation().distance(deathLoc);
        player.sendActionBar(Component.text("§6☩ Last death: §e" + x + ", " + y + ", " + z + " §7(§e" + dist + " blocks§7)"));
    }

    private void activateCartographersLens(Player player) {
        Location loc = player.getLocation();
        Biome biome = player.getWorld().getBiome(loc);
        int y = loc.getBlockY();
        int light = player.getWorld().getBlockAt(loc).getLightLevel();
        String biomeName = biome.getKey().getKey().replace("_", " ");

        String nearest = findNearestStructureDesc(player, List.of("stronghold", "ancient_city", "mineshaft", "ruined_portal"));
        player.sendMessage(
                "§bBiome: §f" + biomeName + "  §bY: §f" + y + "  §bLight: §f" + light
                + "\n§bNearest structure: §f" + nearest);
    }

    private void activateDowsingRod(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        Long cdEnd = meta.getPersistentDataContainer().get(DOWSING_COOLDOWN, PersistentDataType.LONG);
        if (cdEnd != null && System.currentTimeMillis() < cdEnd) {
            long remaining = (cdEnd - System.currentTimeMillis()) / 1000;
            player.sendActionBar(Component.text("§cRecharging: §e" + remaining + "s"));
            return;
        }

        List<String> targets = List.of("ancient_city", "stronghold", "trail_ruins");
        String desc = findNearestStructureDesc(player, targets);
        if (desc.equals("None nearby")) {
            player.sendMessage("§dThe rod hums faintly but finds nothing.");
        } else {
            Location found = findNearestStructureLoc(player, targets);
            if (found != null) player.setCompassTarget(found);
            player.sendMessage("§dThe rod pulls toward: §f" + desc);
        }

        meta.getPersistentDataContainer().set(DOWSING_COOLDOWN, PersistentDataType.LONG,
                System.currentTimeMillis() + 30_000L);
        item.setItemMeta(meta);
    }

    private void throwSmokeBomb(Player player, ItemStack item) {
        Egg egg = player.launchProjectile(Egg.class);
        egg.getPersistentDataContainer().set(SMOKE_BOMB_KEY, PersistentDataType.STRING,
                player.getUniqueId().toString());
        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else player.getInventory().setItemInMainHand(null);
    }

    // -------------------------------------------------------------------------
    // Depth Charts actionbar
    // -------------------------------------------------------------------------

    private void sendDepthBar(Player player) {
        int y = player.getLocation().getBlockY();
        int light = player.getWorld().getBlockAt(player.getLocation()).getLightLevel();
        String biome = player.getWorld().getBiome(player.getLocation()).getKey().getKey().replace("_", " ");
        player.sendActionBar(Component.text(
                "§aY: §f" + y + "  §aBiome: §f" + biome + "  §aLight: §f" + light + "  §aOre: §f" + oreTier(y)));
    }

    private String oreTier(int y) {
        if (y > 64)   return "Surface";
        if (y >= 16)  return "Coal / Iron";
        if (y >= -16) return "Gold / Lapis / Diamond";
        return "Deep (Netherite)";
    }

    // -------------------------------------------------------------------------
    // Structure helpers
    // -------------------------------------------------------------------------

    private String findNearestStructureDesc(Player player, List<String> ids) {
        StructureSearchResult best = null;
        String bestId = null;
        double bestDist = Double.MAX_VALUE;
        for (String id : ids) {
            Structure struct = Registry.STRUCTURE.get(NamespacedKey.minecraft(id));
            if (struct == null) continue;
            StructureSearchResult found = player.getWorld().locateNearestStructure(player.getLocation(), struct, 100, false);
            if (found == null) continue;
            double dist = found.getLocation().distance(player.getLocation());
            if (dist < bestDist) { bestDist = dist; best = found; bestId = id; }
        }
        if (best == null) return "None nearby";
        String dir = cardinalDir(best.getLocation().getX() - player.getLocation().getX(),
                best.getLocation().getZ() - player.getLocation().getZ());
        return bestId.replace("_", " ") + " (" + dir + ", ~" + (int) bestDist + " blocks)";
    }

    private Location findNearestStructureLoc(Player player, List<String> ids) {
        StructureSearchResult best = null;
        double bestDist = Double.MAX_VALUE;
        for (String id : ids) {
            Structure struct = Registry.STRUCTURE.get(NamespacedKey.minecraft(id));
            if (struct == null) continue;
            StructureSearchResult found = player.getWorld().locateNearestStructure(player.getLocation(), struct, 100, false);
            if (found == null) continue;
            double dist = found.getLocation().distance(player.getLocation());
            if (dist < bestDist) { bestDist = dist; best = found; }
        }
        return best == null ? null : best.getLocation();
    }

    private String cardinalDir(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360;
        if (angle < 22.5 || angle >= 337.5) return "N";
        if (angle < 67.5)  return "NE";
        if (angle < 112.5) return "E";
        if (angle < 157.5) return "SE";
        if (angle < 202.5) return "S";
        if (angle < 247.5) return "SW";
        if (angle < 292.5) return "W";
        return "NW";
    }

    // -------------------------------------------------------------------------
    // Item factory & identification
    // -------------------------------------------------------------------------

    public ItemStack createItem(UtilityItem type) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(type.displayName());
        meta.setLore(type.lore());
        meta.setCustomModelData(type.cmd());
        meta.getPersistentDataContainer().set(UTILITY_ITEM_KEY, PersistentDataType.STRING, type.id());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isItem(ItemStack item, UtilityItem type) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return type.id().equals(meta.getPersistentDataContainer().get(UTILITY_ITEM_KEY, PersistentDataType.STRING));
    }

    // -------------------------------------------------------------------------
    // Command: /vpitem give <item> [player]
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§cUsage: /vpitem give <item> [player]");
            return true;
        }
        Optional<UtilityItem> typeOpt = UtilityItem.fromId(args[1].toLowerCase());
        if (typeOpt.isEmpty()) {
            sender.sendMessage("§cUnknown item: §e" + args[1] + "§c. Options: "
                    + Arrays.stream(UtilityItem.values()).map(UtilityItem::id).reduce((a, b) -> a + ", " + b).orElse(""));
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) { sender.sendMessage("§cPlayer not found: " + args[2]); return true; }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§cSpecify a player name.");
            return true;
        }
        target.getInventory().addItem(createItem(typeOpt.get()));
        sender.sendMessage("§aGave §e" + typeOpt.get().displayName() + " §ato §e" + target.getName() + "§a.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give");
        if (args.length == 2 && args[0].equalsIgnoreCase("give"))
            return Arrays.stream(UtilityItem.values()).map(UtilityItem::id).toList();
        if (args.length == 3 && args[0].equalsIgnoreCase("give"))
            return plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        return List.of();
    }
}
