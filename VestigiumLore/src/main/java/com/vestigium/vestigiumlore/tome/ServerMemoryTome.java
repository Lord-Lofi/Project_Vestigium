package com.vestigium.vestigiumlore.tome;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.api.OmenAPI;
import com.vestigium.lib.model.Season;
import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The Server Memory Tome — a written book players can obtain that records the current
 * "world state": omen score, season, active cataclysm, faction health summaries,
 * and up to 20 recent world events drawn from a persisted log.
 *
 * The event log is written by all other VestigiumLore systems via {@link #logEvent(String)}.
 * Persisted to plugins/VestigiumLore/memory_log.yml (max 100 entries; oldest purged).
 */
public class ServerMemoryTome implements CommandExecutor {

    private static final int MAX_LOG_ENTRIES = 100;
    private static final int ENTRIES_PER_TOME  = 20;
    private static final NamespacedKey TOME_KEY =
            new NamespacedKey("vestigium", "server_memory_tome");

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final VestigiumLore plugin;
    private final List<String> eventLog = new ArrayList<>();
    private File logFile;

    public ServerMemoryTome(VestigiumLore plugin) {
        this.plugin = plugin;
    }

    public void init() {
        logFile = new File(plugin.getDataFolder(), "memory_log.yml");
        loadLog();

        var cmd = plugin.getCommand("vctome");
        if (cmd != null) cmd.setExecutor(this);

        plugin.getLogger().info("[ServerMemoryTome] Initialized — " + eventLog.size() + " entries loaded.");
    }

    public void save() {
        saveLog();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Appends a world event to the rolling log. Thread-safe (synchronized). */
    public synchronized void logEvent(String message) {
        String ts = TIMESTAMP_FMT.format(Instant.now());
        eventLog.add("[" + ts + "] " + message);
        while (eventLog.size() > MAX_LOG_ENTRIES) {
            eventLog.remove(0);
        }
    }

    /** Creates a Server Memory Tome item reflecting current world state. */
    public ItemStack createTome() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        if (meta == null) return item;

        meta.setTitle("Server Memory Tome");
        meta.setAuthor("The Vestigium");

        meta.addPage(buildStatePage());
        meta.addPage(buildFactionPage());
        meta.addPage(buildEventPage());

        meta.getPersistentDataContainer()
                .set(TOME_KEY, PersistentDataType.BOOLEAN, true);
        item.setItemMeta(meta);
        return item;
    }

    // -------------------------------------------------------------------------
    // Command — /vctome give [player]
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player target;
        if (args.length >= 2 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("vestigium.tome.admin")) {
                sender.sendMessage("§cNo permission.");
                return true;
            }
            target = plugin.getServer().getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("§7Usage: /vctome give <player>");
            return true;
        }

        target.getInventory().addItem(createTome());
        target.sendMessage("§7The tome assembles itself in your hands.");
        return true;
    }

    // -------------------------------------------------------------------------
    // Page builders
    // -------------------------------------------------------------------------

    private String buildStatePage() {
        OomenData omen = getOmenData();
        Season season = VestigiumLib.getSeasonAPI().getCurrentSeason();
        int tidalPhase = VestigiumLib.getSeasonAPI().getTidalPhase();

        return "§0=== World State ===\n\n"
                + "§0Season: §1" + capitalize(season.name()) + "\n"
                + "§0Omen: §1" + omen.score + " §0(" + omen.tier + ")\n"
                + "§0Tide: Phase §1" + tidalPhase + "\n"
                + (omen.ascending ? "§0Omen is §4rising.\n" : "§0Omen is §2stable.\n");
    }

    private String buildFactionPage() {
        StringBuilder sb = new StringBuilder("§0=== Faction Pulse ===\n\n");
        for (com.vestigium.lib.model.Faction faction : com.vestigium.lib.model.Faction.values()) {
            var state = VestigiumLib.getFactionRegistry().getFactionState(faction.getKey());
            String status = state.isCollapsed() ? "§4COLLAPSED" :
                            state.canExpand()   ? "§2EXPANDING" : "§1stable";
            sb.append("§0").append(capitalize(faction.getKey())).append(": ")
              .append(status).append(" (").append(state.getHealth()).append(")\n");
        }
        return sb.toString();
    }

    private String buildEventPage() {
        StringBuilder sb = new StringBuilder("§0=== Recent Events ===\n\n");
        List<String> recent;
        synchronized (this) {
            int from = Math.max(0, eventLog.size() - ENTRIES_PER_TOME);
            recent = new ArrayList<>(eventLog.subList(from, eventLog.size()));
        }
        if (recent.isEmpty()) {
            sb.append("§0No recorded events.");
        } else {
            for (int i = recent.size() - 1; i >= 0; i--) {
                sb.append("§0").append(recent.get(i)).append("\n");
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadLog() {
        if (!logFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(logFile);
        List<String> loaded = cfg.getStringList("events");
        eventLog.addAll(loaded);
    }

    private void saveLog() {
        YamlConfiguration cfg = new YamlConfiguration();
        synchronized (this) {
            cfg.set("events", new ArrayList<>(eventLog));
        }
        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(logFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[ServerMemoryTome] Failed to save log: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private record OomenData(int score, String tier, boolean ascending) {}

    private OomenData getOmenData() {
        int score = (int) VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        String tier = score >= 800 ? "CRITICAL" :
                      score >= 600 ? "SEVERE"   :
                      score >= 400 ? "HIGH"      :
                      score >= 200 ? "ELEVATED"  : "LOW";
        return new OomenData(score, tier, false);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
