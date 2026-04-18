package com.vestigium.vestigiumplayer.title;

import com.vestigium.vestigiumplayer.VestigiumPlayer;
import com.vestigium.vestigiumplayer.data.PlayerDataStore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Unlockable titles displayed in chat and on the scoreboard name tag.
 *
 * Title unlock conditions are evaluated whenever a relevant stat changes.
 * Once unlocked a title is permanently stored in PlayerDataStore.
 *
 * Built-in titles (key → display → condition):
 *   wanderer         §7[Wanderer]       default; always granted on first join
 *   cartographer     §e[Cartographer]   10+ structures discovered
 *   survivor         §a[Survivor]       1+ cataclysm survived
 *   veteran          §6[Veteran]        5+ cataclysms survived
 *   warden_slayer    §4[Warden Slayer]  1+ named warden killed
 *   warden_bane      §c[Warden's Bane]  5+ named wardens killed
 *   lore_seeker      §b[Lore Seeker]    25+ lore fragments
 *   archivist        §d[Archivist]      100+ lore fragments
 *   deep_walker      §8[Deep Walker]    25+ structures discovered
 *   the_terminus     §5[The Terminus]   cartographer chain complete (lore_frags PDC check bypassed — uses fragment key)
 *
 * Players set their active title with /vptitle set <key>.
 * Display is prefixed in chat via PlayerChatEvent (lowest priority).
 */
public class TitleManager implements CommandExecutor {

    private static final List<TitleDefinition> ALL_TITLES = List.of(
            new TitleDefinition("wanderer",      "§7[Wanderer]",      0, 0, 0, 0),
            new TitleDefinition("cartographer",  "§e[Cartographer]",  10, 0, 0, 0),
            new TitleDefinition("survivor",      "§a[Survivor]",      0, 1, 0, 0),
            new TitleDefinition("veteran",       "§6[Veteran]",       0, 5, 0, 0),
            new TitleDefinition("warden_slayer", "§4[Warden Slayer]", 0, 0, 1, 0),
            new TitleDefinition("warden_bane",   "§c[Warden's Bane]", 0, 0, 5, 0),
            new TitleDefinition("lore_seeker",   "§b[Lore Seeker]",   0, 0, 0, 25),
            new TitleDefinition("archivist",     "§d[Archivist]",     0, 0, 0, 100),
            new TitleDefinition("deep_walker",   "§8[Deep Walker]",   25, 0, 0, 0)
    );

    private final VestigiumPlayer plugin;
    private final PlayerDataStore dataStore;

    public TitleManager(VestigiumPlayer plugin, PlayerDataStore dataStore) {
        this.plugin = plugin;
        this.dataStore = dataStore;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(new TitleChatListener(plugin, dataStore), plugin);

        var cmd = plugin.getCommand("vptitle");
        if (cmd != null) cmd.setExecutor(this);

        // Grant wanderer to any online player missing it
        plugin.getServer().getOnlinePlayers().forEach(this::checkTitles);
        plugin.getLogger().info("[TitleManager] Initialized — " + ALL_TITLES.size() + " titles.");
    }

    // -------------------------------------------------------------------------
    // Title checking
    // -------------------------------------------------------------------------

    public void checkTitles(Player player) {
        int structures = dataStore.getInt(player, PlayerDataStore.KEY_STRUCTURES);
        int cataclysms = dataStore.getInt(player, PlayerDataStore.KEY_CATACLYSMS);
        int bossKills  = dataStore.getInt(player, PlayerDataStore.KEY_BOSS_KILLS);
        int loreFrags  = dataStore.getInt(player, PlayerDataStore.KEY_LORE_FRAGS);

        for (TitleDefinition title : ALL_TITLES) {
            if (structures >= title.reqStructures()
                    && cataclysms >= title.reqCataclysms()
                    && bossKills  >= title.reqBossKills()
                    && loreFrags  >= title.reqLoreFrags()) {
                boolean wasNew = !dataStore.getUnlockedTitles(player).contains(title.key());
                dataStore.unlockTitle(player, title.key());
                if (wasNew) {
                    player.sendMessage("§6[Title Unlocked] " + title.display() + " §6— §7/vptitle set " + title.key());
                    // Auto-equip if player has no active title
                    if (dataStore.getActiveTitle(player).isBlank()) {
                        dataStore.setActiveTitle(player, title.key());
                    }
                }
            }
        }

        // Always grant wanderer
        if (!dataStore.getUnlockedTitles(player).contains("wanderer")) {
            dataStore.unlockTitle(player, "wanderer");
        }
    }

    public void checkAllOnline() {
        plugin.getServer().getOnlinePlayers().forEach(this::checkTitles);
    }

    // -------------------------------------------------------------------------
    // /vptitle command
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cPlayer only.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            List<String> unlocked = dataStore.getUnlockedTitles(player);
            player.sendMessage("§6Your titles:");
            ALL_TITLES.stream()
                    .filter(t -> unlocked.contains(t.key()))
                    .forEach(t -> player.sendMessage("  " + t.display() + " §8(" + t.key() + ")"));
            return true;
        }

        if (args[0].equalsIgnoreCase("set") && args.length >= 2) {
            String key = args[1].toLowerCase();
            if (!dataStore.getUnlockedTitles(player).contains(key)) {
                player.sendMessage("§cYou have not unlocked that title.");
                return true;
            }
            dataStore.setActiveTitle(player, key);
            String display = ALL_TITLES.stream()
                    .filter(t -> t.key().equals(key))
                    .map(TitleDefinition::display)
                    .findFirst().orElse(key);
            player.sendMessage("§7Active title set to " + display + "§7.");
            return true;
        }

        player.sendMessage("§7Usage: /vptitle [list | set <title>]");
        return true;
    }

    public static List<TitleDefinition> getAllTitles() { return ALL_TITLES; }
}
