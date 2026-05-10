package com.vestigium.vestigiummobs.passive;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.SeasonChangeEvent;
import com.vestigium.lib.model.Season;
import com.vestigium.vestigiummobs.VestigiumMobs;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mythic Beasts — one procedurally-named unique animal per season.
 *
 * Once per season a naturally-spawning mob has a 1-in-5000 chance of becoming
 * the Mythic Beast. Only one can exist at a time. Eligible mobs vary by season:
 *   SPRING  → Wolf, Fox, Horse
 *   SUMMER  → Ocelot, Parrot, Panda
 *   AUTUMN  → Wolf, Polar Bear
 *   WINTER  → Polar Bear, Goat
 *
 * The beast gets 3× base HP and a procedurally generated name seeded by the
 * current season + day count, so the same name recurs each year.
 *
 * On death: server-wide broadcast, unique trophy item drop, and the slot
 * reopens for the rest of the season.
 *
 * Persisted to plugins/VestigiumMobs/mythic_beast.yml across restarts.
 */
public class MythicBeastManager implements Listener {

    private static final NamespacedKey MYTHIC_KEY =
            new NamespacedKey("vestigium", "mythic_beast");

    private static final int SPAWN_ODDS = 5000;

    private static final Map<Season, Set<EntityType>> SEASON_MOBS = Map.of(
            Season.SPRING, Set.of(EntityType.WOLF, EntityType.FOX, EntityType.HORSE),
            Season.SUMMER, Set.of(EntityType.OCELOT, EntityType.PARROT, EntityType.PANDA),
            Season.AUTUMN, Set.of(EntityType.WOLF, EntityType.POLAR_BEAR),
            Season.WINTER, Set.of(EntityType.POLAR_BEAR, EntityType.GOAT)
    );

    private static final String[] ADJECTIVES = {
            "Pale", "Crimson", "Ancient", "Hollow", "Gilded",
            "Iron", "Ashen", "Boundless", "Last", "Verdant"
    };
    private static final String[] EPITHETS = {
            "Wanderer", "Stalker", "Shepherd", "Guardian",
            "Remnant", "Witness", "Sovereign", "Pilgrim"
    };

    private final VestigiumMobs plugin;
    private final File dataFile;

    private UUID   currentBeastId = null;
    private boolean beastActiveThisSeason = false;

    public MythicBeastManager(VestigiumMobs plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "mythic_beast.yml");
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        VestigiumLib.getEventBus().subscribe(SeasonChangeEvent.class, this::onSeasonChange);
        load();
        plugin.getLogger().info("[MythicBeastManager] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Season change
    // -------------------------------------------------------------------------

    private void onSeasonChange(SeasonChangeEvent event) {
        // Previous beast (if still alive) loses its status but keeps living
        currentBeastId = null;
        beastActiveThisSeason = false;
        save();
        plugin.getLogger().info("[MythicBeastManager] Season changed — Mythic Beast slot reset.");
    }

    // -------------------------------------------------------------------------
    // Spawn hook
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (beastActiveThisSeason) return;
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        if (event.getEntity().getWorld().getEnvironment() != World.Environment.NORMAL) return;

        Season season = VestigiumLib.getSeasonAPI().getCurrentSeason();
        Set<EntityType> eligible = SEASON_MOBS.getOrDefault(season, Set.of());
        if (!eligible.contains(event.getEntityType())) return;

        if (ThreadLocalRandom.current().nextInt(SPAWN_ODDS) != 0) return;

        designateAsBeast((Mob) event.getEntity(), season);
    }

    private void designateAsBeast(Mob mob, Season season) {
        String name = generateName(season);
        mob.setCustomName(name);
        mob.setCustomNameVisible(true);
        mob.setPersistent(true);
        mob.setRemoveWhenFarAway(false);
        mob.getPersistentDataContainer()
                .set(MYTHIC_KEY, PersistentDataType.STRING, name);

        var hp = mob.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * 3.0);
            mob.setHealth(hp.getValue());
        }

        currentBeastId = mob.getUniqueId();
        beastActiveThisSeason = true;
        save();

        String coords = mob.getLocation().getBlockX() + ", "
                + mob.getLocation().getBlockZ();
        plugin.getServer().broadcastMessage(
                "§6§lA MYTHIC BEAST HAS APPEARED: §e" + name
                        + " §6in " + mob.getWorld().getName()
                        + " [" + coords + "]!");
        plugin.getLogger().info("[MythicBeastManager] Designated " + name
                + " at " + coords);
    }

    // -------------------------------------------------------------------------
    // Death
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) return;
        String name = mob.getPersistentDataContainer()
                .get(MYTHIC_KEY, PersistentDataType.STRING);
        if (name == null) return;

        event.getDrops().add(createTrophy(name));

        currentBeastId = null;
        beastActiveThisSeason = false;
        save();

        Player killer = mob.getKiller();
        String by = killer != null ? "§f" + killer.getName() : "§8an unknown hand";
        plugin.getServer().broadcastMessage(
                "§6The Mythic Beast §e" + name + " §6has been slain by " + by + "§6.");
    }

    private ItemStack createTrophy(String beastName) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6§lMythic Trophy");
        meta.setLore(List.of(
                "§7Taken from " + beastName + ",",
                "§7the season's only wanderer.",
                "§6§oOnly one exists."
        ));
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Name generation — seeded so the same season+year always produces the same name
    // -------------------------------------------------------------------------

    private String generateName(Season season) {
        long year = VestigiumLib.getSeasonAPI().getDayCount() / 120;
        long seed = season.ordinal() + year * 4L;
        Random rng = new Random(seed);
        String adj     = ADJECTIVES[rng.nextInt(ADJECTIVES.length)];
        String epithet = EPITHETS[rng.nextInt(EPITHETS.length)];
        return "§6The §e" + adj + " " + epithet;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        String uuidStr = cfg.getString("beast_uuid");
        if (uuidStr != null) {
            try {
                currentBeastId = UUID.fromString(uuidStr);
                // Verify the entity still exists and is alive
                Entity e = plugin.getServer().getEntity(currentBeastId);
                beastActiveThisSeason = (e != null && e.isValid() && !e.isDead());
                if (!beastActiveThisSeason) currentBeastId = null;
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        if (currentBeastId != null) cfg.set("beast_uuid", currentBeastId.toString());
        cfg.set("active", beastActiveThisSeason);
        try { cfg.save(dataFile); } catch (IOException e) {
            plugin.getLogger().warning("[MythicBeastManager] Failed to save: " + e.getMessage());
        }
    }
}
