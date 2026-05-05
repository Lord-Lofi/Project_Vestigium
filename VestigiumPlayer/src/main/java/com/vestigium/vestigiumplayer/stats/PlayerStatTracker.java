package com.vestigium.vestigiumplayer.stats;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.CataclysmEndEvent;
import com.vestigium.lib.event.LoreFragmentGrantedEvent;
import com.vestigium.lib.event.WorldBossSpawnEvent;
import com.vestigium.lib.util.BlockStructureTag;
import com.vestigium.vestigiumplayer.VestigiumPlayer;
import com.vestigium.vestigiumplayer.data.PlayerDataStore;
import com.vestigium.vestigiumplayer.notoriety.NotorietyManager;
import com.vestigium.vestigiumplayer.title.TitleManager;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
public class PlayerStatTracker implements Listener, CommandExecutor {

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


        plugin.getCommand("vpstats").setExecutor(this);
        plugin.getLogger().info("[PlayerStatTracker] Initialized.");
    }

    // -------------------------------------------------------------------------
    // /vpstats command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length >= 1 && sender.hasPermission("vestigium.stats.admin")) {
            target = plugin.getServer().getPlayer(args[0]);
            if (target == null) { sender.sendMessage("§cPlayer not found: " + args[0]); return true; }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§cUsage: /vpstats [player]"); return true;
        }

        long minutes  = target.getPersistentDataContainer()
                .getOrDefault(PlayerDataStore.KEY_PLAYTIME, PersistentDataType.LONG, 0L);
        int structures = dataStore.getInt(target, PlayerDataStore.KEY_STRUCTURES);
        int cataclysms = dataStore.getInt(target, PlayerDataStore.KEY_CATACLYSMS);
        int bossKills  = dataStore.getInt(target, PlayerDataStore.KEY_BOSS_KILLS);
        int loreFrags  = dataStore.getInt(target, PlayerDataStore.KEY_LORE_FRAGS);
        int notoriety  = dataStore.getInt(target, PlayerDataStore.KEY_NOTORIETY);

        String titleKey = dataStore.getActiveTitle(target);
        String titleDisplay = TitleManager.getAllTitles().stream()
                .filter(t -> t.key().equals(titleKey))
                .map(t -> t.display())
                .findFirst().orElse(titleKey.isBlank() ? "§7None" : titleKey);

        NotorietyManager.Level level = NotorietyManager.Level.of(notoriety);

        String header = "§8§m          §r §e" + target.getName() + "'s Stats §8§m          ";
        sender.sendMessage(header);
        sender.sendMessage("§7Playtime:      §f" + formatTime(minutes));
        sender.sendMessage("§7Title:         §f" + titleDisplay);
        sender.sendMessage("§7Structures:    §f" + structures + " §7discovered");
        sender.sendMessage("§7Cataclysms:    §f" + cataclysms + " §7survived");
        sender.sendMessage("§7Named Wardens: §f" + bossKills   + " §7slain");
        sender.sendMessage("§7Lore Fragments:§f" + loreFrags);
        sender.sendMessage("§7Notoriety:     " + level.display + " §7(" + notoriety + "/200)");
        return true;
    }

    private String formatTime(long minutes) {
        if (minutes < 60) return minutes + "m";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
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
