package com.vestigium.vestigiumlore.bottle;

import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Message in a Bottle — when a player walks on wet sand in a beach biome,
 * there is a small chance they find a sealed bottle containing a procedurally
 * generated expedition note from the Antecedent.
 *
 * Trigger:  PlayerMoveEvent; player on sand/gravel in beach biome, feet in water level (Y < 64)
 * Chance:   0.4% per qualifying step
 * Cooldown: 45 minutes per player
 * Item:     Glass Bottle with custom lore, PDC tag vestigium:message_bottle
 */
public class MessageInABottleManager implements Listener {

    private static final NamespacedKey BOTTLE_KEY =
            new NamespacedKey("vestigium", "message_bottle");

    private static final double SPAWN_CHANCE  = 0.004;
    private static final long   COOLDOWN_MS   = 45 * 60 * 1000L;

    private static final Set<String> BEACH_BIOMES = Set.of(
            "beach", "snowy_beach", "stony_shore");

    // -------------------------------------------------------------------------
    // Author names — Antecedent expedition members
    // -------------------------------------------------------------------------

    private static final List<String> AUTHORS = List.of(
            "Survey-Lead Aethon",  "Field Archivist Maren",  "Cartographer Sela",
            "Waystone Engineer Torvak", "Deep Survey Coord. Ilen", "Research Lead Cassiel",
            "Pathfinder Renn", "Cipher Analyst Voss", "Expedition Lead Dara",
            "Route-Marker Keln"
    );

    // -------------------------------------------------------------------------
    // Message templates — %author% and %day% are replaced procedurally
    // -------------------------------------------------------------------------

    private static final List<String> TEMPLATES = List.of(
            "Field note, day %day%. %author% reporting. The coastal path is accessible at low tide only. Do not attempt transit after phase 6. I am leaving this in case I do not return before the surge.",
            "Personal record. %author%. Day %day% at sea. The depth markers are wrong below 80 fathoms. Something has shifted the substrate. Adjusting charts accordingly. If you find this — trust the stars, not the maps.",
            "Survey log, %author%. Day %day%. We found the marker from the first expedition. It is older than our records say the first expedition was. Not reporting this upward.",
            "Expedition memo, day %day%. Sealed and released by %author%. The structure on the seabed is not on any chart. It should not be there. It is lit from inside. I am going to look.",
            "To whoever reads this — I am %author%, day %day% of the coastal survey. The Drowned Civilization is not a myth. Layer 2 is accessible from the reef at low neap. Do not go alone.",
            "Field dispatch, %author%, approx. day %day%. The sea route to the Breach is viable but not safe. The currents near the submerged arch are intentional. Someone designed them.",
            "Personal note. Day %day%. %author%. If the archive at Layer 3 is still intact, the census data there contradicts everything we were told about the Antecedent population. I cannot get back out. The door only opens from the inside.",
            "Survey lead %author%. Day %day%. The coastal ruins predate the shoreline. The sea moved. They did not.",
            "Encrypted memo, %author%, day %day% since submersion. The resonance frequencies at depth are not natural. They are a recording. We have been listening. I think it knows.",
            "Day %day%. %author%. We found the shipwreck from the third expedition. The hull is intact. The crew are intact. They are still at their posts. Something is wrong with their eyes.",
            "Route record, day %day%. %author% to all survey teams. The ocean floor is a map. I have been reading it wrong. It is not terrain. It is text. I need more time.",
            "Final log, %author%, day %day%. The tidal engine in Layer 4 is still running. We do not know who is running it. We have not found a crew. We have not found a reason to stop it. Leaving this here in case we do."
    );

    private final VestigiumLore plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public MessageInABottleManager(VestigiumLore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("[MessageInABottleManager] Initialized.");
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Only fire when block changes to reduce frequency
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player p = event.getPlayer();
        if (!isBeachContext(p)) return;

        long now = System.currentTimeMillis();
        Long cooldownUntil = cooldowns.get(p.getUniqueId());
        if (cooldownUntil != null && now < cooldownUntil) return;

        if (ThreadLocalRandom.current().nextDouble() > SPAWN_CHANCE) return;

        cooldowns.put(p.getUniqueId(), now + COOLDOWN_MS);
        spawnBottle(p);
    }

    // -------------------------------------------------------------------------

    private void spawnBottle(Player player) {
        ItemStack bottle = new ItemStack(Material.GLASS_BOTTLE);
        ItemMeta meta = bottle.getItemMeta();
        if (meta == null) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String author   = AUTHORS.get(rng.nextInt(AUTHORS.size()));
        int    day      = rng.nextInt(1, 180);
        String template = TEMPLATES.get(rng.nextInt(TEMPLATES.size()));
        String text     = template.replace("%author%", author).replace("%day%", String.valueOf(day));

        meta.setDisplayName("§bSealed Bottle");
        meta.setLore(List.of(
                "§7" + text,
                "",
                "§8A message, sealed and cast adrift."
        ));
        meta.getPersistentDataContainer()
                .set(BOTTLE_KEY, PersistentDataType.BOOLEAN, true);
        bottle.setItemMeta(meta);

        player.getWorld().dropItemNaturally(player.getLocation(), bottle);
        player.sendMessage("§7Something glints in the wet sand at your feet.");
        player.getWorld().playSound(
                player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.3f, 1.8f);
    }

    private static boolean isBeachContext(Player player) {
        String biome = player.getLocation().getBlock().getBiome().getKey().getKey();
        if (!BEACH_BIOMES.contains(biome)) return false;
        // Must be on or near the waterline
        int y = player.getLocation().getBlockY();
        return y >= 60 && y <= 66;
    }
}
