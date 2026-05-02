package com.vestigium.vestigiumplayer.stats;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.CataclysmEndEvent;
import com.vestigium.lib.event.LoreFragmentGrantedEvent;
import com.vestigium.lib.event.WorldBossSpawnEvent;
import com.vestigium.lib.util.BlockStructureTag;
import com.vestigium.vestigiumplayer.VestigiumPlayer;
import com.vestigium.vestigiumplayer.data.PlayerDataStore;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Listens for world events and increments player stat counters in PlayerDataStore.
 *
 * Tracked events:
 *   Cataclysm survived   — CataclysmEndEvent via EventBus; all online players +1 vp_cataclysms
 *   Structure discovered — PlayerMoveEvent; vestigium:structure_id block underfoot, first time only
 *   Named Warden killed  — EntityDeathEvent; Warden with named_warden_type PDC
 *   Lore fragment gained — WorldBossSpawnEvent NOT used; QuestTracker's fragment reward path
 *                          We hook via LoreRegistry method (polled on join against PDC count)
 */
public class PlayerStatTracker implements Listener {

    private static final NamespacedKey STRUCTURE_ID_KEY =
            new NamespacedKey("vestigium", "structure_id");
    private static final NamespacedKey NAMED_WARDEN_KEY =
            new NamespacedKey("vestigium", "named_warden_type");

    private final VestigiumPlayer plugin;
    private final PlayerDataStore dataStore;
    // Players currently inside cataclysm (alive during one) — set on start, credited on end
    private final Set<UUID> cataclysmSurvivors = new HashSet<>();

    public PlayerStatTracker(VestigiumPlayer plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        VestigiumLib.getEventBus().subscribe(
                com.vestigium.lib.event.CataclysmStartEvent.class, event -> {
                    // Record all currently online players as potential survivors
                    plugin.getServer().getOnlinePlayers()
                            .forEach(p -> cataclysmSurvivors.add(p.getUniqueId()));
                });

        VestigiumLib.getEventBus().subscribe(CataclysmEndEvent.class, event -> {
            cataclysmSurvivors.forEach(uuid -> {
                Player p = plugin.getServer().getPlayer(uuid);
                if (p != null) {
                    dataStore.addInt(p, PlayerDataStore.KEY_CATACLYSMS, 1);
                }
            });
            cataclysmSurvivors.clear();
            plugin.getTitleManager().checkAllOnline();
        });

        VestigiumLib.getEventBus().subscribe(LoreFragmentGrantedEvent.class, event -> {
            Player p = plugin.getServer().getPlayer(event.getPlayerUUID());
            if (p == null) return;
            dataStore.addInt(p, PlayerDataStore.KEY_LORE_FRAGS, 1);
            plugin.getTitleManager().checkTitles(p);
        });


        plugin.getLogger().info("[PlayerStatTracker] Initialized.");
    }

    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        String structureId = BlockStructureTag.get(event.getTo().getBlock().getRelative(0, -1, 0));
        if (structureId == null) return;

        NamespacedKey seen = new NamespacedKey("vestigium", "vp_seen_" + structureId);
        if (player.getPersistentDataContainer().has(seen, PersistentDataType.BOOLEAN)) return;
        player.getPersistentDataContainer().set(seen, PersistentDataType.BOOLEAN, true);

        dataStore.addInt(player, PlayerDataStore.KEY_STRUCTURES, 1);
        plugin.getTitleManager().checkTitles(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWardenDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Warden warden)) return;
        if (warden.getKiller() == null) return;

        String wardenType = warden.getPersistentDataContainer()
                .get(NAMED_WARDEN_KEY, PersistentDataType.STRING);
        if (wardenType == null) return;

        dataStore.addInt(warden.getKiller(), PlayerDataStore.KEY_BOSS_KILLS, 1);
        plugin.getTitleManager().checkTitles(warden.getKiller());
    }
}
