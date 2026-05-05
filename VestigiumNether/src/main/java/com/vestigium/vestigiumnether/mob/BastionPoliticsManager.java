package com.vestigium.vestigiumnether.mob;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.Faction;
import com.vestigium.vestigiumnether.VestigiumNether;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.StructureSearchResult;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Bastion Politics.
 *
 * One Piglin Lord spawns near each active bastion remnant. Players who barter
 * with the Lord five times receive a Safe Passage Token for that bastion — while
 * the token is in their inventory, all Piglins and Piglin Brutes in the bastion
 * area will not target them.
 *
 * Lords spawn on demand when a player comes within 80 blocks of a bastion
 * remnant (checked every 30 seconds per player). They are persistent (no
 * despawn), immune to zombification, and have 40 HP.
 *
 * Barter counts are persisted to plugins/VestigiumNether/bastion_politics.yml.
 * Safe Passage Tokens (CMD 20008, gold nugget) carry the bastion key in their
 * item PDC; the EntityTargetLivingEntityEvent handler checks this at targeting
 * time against registered lord locations.
 *
 * First token earned: vestigium:piglin_historian_count player PDC incremented.
 * Achievement stub for Piglin Historian pending full achievement tree.
 */
public class BastionPoliticsManager implements Listener {

    private static final NamespacedKey LORD_KEY       = new NamespacedKey("vestigium", "piglin_lord");
    private static final NamespacedKey SAFE_PASS_KEY  = new NamespacedKey("vestigium", "safe_passage_bastion");
    private static final NamespacedKey HISTORIAN_KEY  = new NamespacedKey("vestigium", "piglin_historian_count");

    private static final int  BARTERS_REQUIRED   = 5;
    private static final int  BASTION_SCAN_BLOCKS = 80;
    private static final int  IGNORE_RADIUS       = 80;     // blocks — matches scan radius
    private static final long SCAN_THROTTLE_MS    = 30_000L;

    private static final String[] LORD_NAMES = {
        "§6Overlord Grun'zal", "§6Overlord Vak'sha", "§6Overlord Thrak",
        "§6Overlord Mog'rul",  "§6Overlord Zhargun"
    };

    private final VestigiumNether plugin;
    // bastionKey → active lord entity UUID
    private final Map<String, UUID>     activeLords   = new HashMap<>();
    // bastionKey → lord spawn location (kept across restarts for token validation)
    private final Map<String, Location> lordLocations = new HashMap<>();
    // "uuid~bastionKey" → barter count
    private final Map<String, Integer>  barterCounts  = new HashMap<>();
    // per-player bastion scan throttle
    private final Map<UUID, Long>       lastScanMs    = new HashMap<>();

    private File saveFile;

    public BastionPoliticsManager(VestigiumNether plugin) {
        this.plugin = plugin;
    }

    public void init() {
        saveFile = new File(plugin.getDataFolder(), "bastion_politics.yml");
        loadData();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[BastionPoliticsManager] Initialized.");
    }

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        barterCounts.forEach((k, v) -> cfg.set("barters." + k, v));
        lordLocations.forEach((key, loc) -> {
            cfg.set("lords." + key + ".world", loc.getWorld().getName());
            cfg.set("lords." + key + ".x",     loc.getX());
            cfg.set("lords." + key + ".y",     loc.getY());
            cfg.set("lords." + key + ".z",     loc.getZ());
        });
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(saveFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[BastionPoliticsManager] Save failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Events
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getWorld().getEnvironment() != World.Environment.NETHER) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        UUID id = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        if (now - lastScanMs.getOrDefault(id, 0L) < SCAN_THROTTLE_MS) return;
        lastScanMs.put(id, now);

        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> checkNearBastion(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBarter(PiglinBarterEvent event) {
        String bastionKey = event.getEntity().getPersistentDataContainer()
                .get(LORD_KEY, PersistentDataType.STRING);
        if (bastionKey == null) return;

        Player player = nearestPlayer(event.getEntity().getLocation(), 10);
        if (player == null) return;

        String countKey = player.getUniqueId() + "~" + bastionKey;
        int count = barterCounts.getOrDefault(countKey, 0) + 1;
        barterCounts.put(countKey, count);

        VestigiumLib.getReputationAPI().modifyReputation(
                player.getUniqueId(), Faction.PIGLINS, 30);

        if (count < BARTERS_REQUIRED) {
            player.sendActionBar(Component.text("§6" + event.getEntity().getCustomName()
                    + " §7regards you. §8(" + count + "/" + BARTERS_REQUIRED + " trades)"));
        } else if (count == BARTERS_REQUIRED) {
            grantSafePassage(player, bastionKey, event.getEntity().getCustomName());
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Piglin || event.getEntity() instanceof PiglinBrute)) return;
        if (!(event.getTarget() instanceof Player player)) return;

        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (!isSafePassToken(item)) continue;
            String tokenBastion = item.getItemMeta().getPersistentDataContainer()
                    .get(SAFE_PASS_KEY, PersistentDataType.STRING);
            Location lordLoc = lordLocations.get(tokenBastion);
            if (lordLoc == null || !lordLoc.getWorld().equals(event.getEntity().getWorld())) continue;
            if (lordLoc.distanceSquared(event.getEntity().getLocation())
                    <= (long) IGNORE_RADIUS * IGNORE_RADIUS) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLordDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Piglin)) return;
        String bastionKey = event.getEntity().getPersistentDataContainer()
                .get(LORD_KEY, PersistentDataType.STRING);
        if (bastionKey == null) return;
        activeLords.remove(bastionKey);
        plugin.getLogger().info("[BastionPoliticsManager] Lord at " + bastionKey + " died — will respawn on next player visit.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastScanMs.remove(event.getPlayer().getUniqueId());
    }

    // -------------------------------------------------------------------------
    // Bastion scan + Lord spawn
    // -------------------------------------------------------------------------

    private void checkNearBastion(Player player) {
        Structure bastionStruct = Registry.STRUCTURE.get(NamespacedKey.minecraft("bastion_remnant"));
        if (bastionStruct == null) return;

        StructureSearchResult result = player.getWorld()
                .locateNearestStructure(player.getLocation(), bastionStruct, BASTION_SCAN_BLOCKS, false);
        if (result == null) return;

        String bastionKey = toBastionKey(result.getLocation());

        // Verify existing lord is still alive
        UUID existingId = activeLords.get(bastionKey);
        if (existingId != null) {
            Entity e = plugin.getServer().getEntity(existingId);
            if (e != null && e.isValid()) return;
            activeLords.remove(bastionKey);
        }

        spawnLord(bastionKey, result.getLocation());
    }

    private void spawnLord(String bastionKey, Location bastionCenter) {
        World world = bastionCenter.getWorld();
        int x = bastionCenter.getBlockX();
        int z = bastionCenter.getBlockZ();
        int y = world.getHighestBlockYAt(x, z) + 1;
        Location spawnLoc = new Location(world, x, y, z);

        Piglin lord = world.spawn(spawnLoc, Piglin.class, pig -> {
            pig.getPersistentDataContainer().set(LORD_KEY, PersistentDataType.STRING, bastionKey);
            pig.setPersistent(true);
            pig.setRemoveWhenFarAway(false);
            pig.setImmuneToZombification(true);
            var hp = pig.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(40.0);
            String name = LORD_NAMES[Math.abs(bastionKey.hashCode()) % LORD_NAMES.length];
            pig.setCustomName(name);
            pig.setCustomNameVisible(true);
        });
        lord.setHealth(40.0);

        activeLords.put(bastionKey, lord.getUniqueId());
        lordLocations.put(bastionKey, spawnLoc);
        plugin.getLogger().info("[BastionPoliticsManager] Spawned lord at " + bastionKey);
    }

    // -------------------------------------------------------------------------
    // Safe Passage Token
    // -------------------------------------------------------------------------

    private void grantSafePassage(Player player, String bastionKey, String lordName) {
        ItemStack token = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta meta = token.getItemMeta();
        meta.setDisplayName("§6Safe Passage Token");
        meta.setLore(List.of(
                "§7Granted by " + (lordName != null ? lordName : "a Piglin Lord") + "§r.",
                "§7Carry this to move freely through the bastion.",
                "§8Token bound to: §7" + bastionKey));
        meta.setCustomModelData(20008);
        meta.getPersistentDataContainer().set(SAFE_PASS_KEY, PersistentDataType.STRING, bastionKey);
        token.setItemMeta(meta);

        player.getInventory().addItem(token);
        player.sendMessage("§6" + (lordName != null ? lordName : "The Piglin Lord")
                + " §7grants you safe passage through this bastion.");

        int current = player.getPersistentDataContainer()
                .getOrDefault(HISTORIAN_KEY, PersistentDataType.INTEGER, 0);
        player.getPersistentDataContainer()
                .set(HISTORIAN_KEY, PersistentDataType.INTEGER, current + 1);

        // First token: grant expedition log lore fragment + achievement stub
        if (current == 0) {
            VestigiumLib.getLoreRegistry().grantFragment(
                    player.getUniqueId(), "piglin_expedition_log_main");
            player.sendMessage("§8[§6Piglin Historian§8] §7You have earned the trust of the ancient traders.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isSafePassToken(ItemStack item) {
        if (item == null || item.getType() != Material.GOLD_NUGGET) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(SAFE_PASS_KEY, PersistentDataType.STRING);
    }

    private Player nearestPlayer(Location loc, double radius) {
        Player nearest = null;
        double best = radius * radius;
        for (Entity e : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (!(e instanceof Player p)) continue;
            double d = loc.distanceSquared(p.getLocation());
            if (d < best) { best = d; nearest = p; }
        }
        return nearest;
    }

    // Uses ~ as separator — safe since world names and integers never contain ~
    private String toBastionKey(Location loc) {
        return loc.getWorld().getName() + "~" + (loc.getBlockX() >> 6) + "~" + (loc.getBlockZ() >> 6);
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadData() {
        if (saveFile == null || !saveFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(saveFile);

        ConfigurationSection barterSection = cfg.getConfigurationSection("barters");
        if (barterSection != null) {
            barterSection.getKeys(false).forEach(k -> barterCounts.put(k, barterSection.getInt(k)));
        }

        ConfigurationSection lordSection = cfg.getConfigurationSection("lords");
        if (lordSection != null) {
            for (String key : lordSection.getKeys(false)) {
                String worldName = lordSection.getString(key + ".world");
                World world = Bukkit.getWorld(worldName != null ? worldName : "");
                if (world == null) continue;
                lordLocations.put(key, new Location(world,
                        lordSection.getDouble(key + ".x"),
                        lordSection.getDouble(key + ".y"),
                        lordSection.getDouble(key + ".z")));
            }
        }
    }
}
