package com.vestigium.vestigiumend.structure;

import com.vestigium.lib.VestigiumLib;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Manages the 8 End structure types and The Regent NPC for the Hollow Throne.
 *
 * Structure types:
 *   WITNESS_TOWER         — fragment grant + Enderman aggression suppression
 *   THE_CRADLE            — once-per-player origin lore sequence
 *   SUNDERED_SANCTUM      — 3-part completion system, server broadcast on full clear
 *   CARTOGRAPHERS_OBSERVATORY — cartographer chain lore + END_ROD particles
 *   VOID_ANCHOR           — permanent player PDC coordinate binding
 *   HOLLOW_THRONE         — The Regent NPC, approach unarmed + 30s still for lore
 *   FRAGMENT_DUNGEON      — bearing compass (actionbar) within 64 blocks, fragment on arrival
 *   MIRROR_POOL           — atmospheric only, DRIPPING_WATER particle effect
 *
 * Admin: /vestructure add <type> <id> [name...]
 *         /vestructure remove <id>
 *         /vestructure list
 *         /vestructure npc spawn <regent> <siteId>
 *         /vestructure npc remove
 *         /vestructure sanctumpart <id> <1|2|3>
 */
public class EndStructureManager implements Listener {

    public enum StructureType {
        WITNESS_TOWER, THE_CRADLE, SUNDERED_SANCTUM,
        CARTOGRAPHERS_OBSERVATORY, VOID_ANCHOR, HOLLOW_THRONE,
        FRAGMENT_DUNGEON, MIRROR_POOL
    }

    // ── PDC keys ──────────────────────────────────────────────────────────────
    private static final NamespacedKey SITE_VISITED_KEY  = new NamespacedKey("vestigium", "end_sites_visited");
    private static final NamespacedKey CRADLE_KEY        = new NamespacedKey("vestigium", "cradle_witnessed");
    private static final NamespacedKey SANCTUM_KEY       = new NamespacedKey("vestigium", "sanctum_parts");
    private static final NamespacedKey ANCHOR_KEY        = new NamespacedKey("vestigium", "void_anchor_bound");
    private static final NamespacedKey THRONE_AUD_KEY    = new NamespacedKey("vestigium", "hollow_throne_audience");
    private static final NamespacedKey NPC_TYPE_KEY      = new NamespacedKey("vestigium", "end_structure_npc");
    private static final NamespacedKey NPC_SITE_KEY      = new NamespacedKey("vestigium", "end_site_id");
    private static final String[] COMPASS_ARROWS         = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    // ── Cradle lore sequence ──────────────────────────────────────────────────
    private static final String[] CRADLE_SEQUENCE = {
        "§5The Cradle is where the dragon was placed. Not born. Placed.",
        "§5The Antecedents did not create it. They relocated it from somewhere the records do not name.",
        "§5The reason given was: safekeeping. For whom, or from what, is not recorded.",
        "§5The dragon has been circling since before any of this was mapped.",
        "§5It does not know it was moved. Or it does not care. The distinction may not exist for it."
    };

    // ── Mirror pool ambient ───────────────────────────────────────────────────
    private static final String[] POOL_AMBIENT = {
        "§8The surface does not reflect. This is intentional.",
        "§8The depth of the Pool has not been measured.",
        "§8Whatever the Antecedents placed in it, they intended it to remain there.",
        "§8The Pool absorbs. It does not return."
    };

    private final JavaPlugin plugin;
    private final Map<String, EndSite> sites = new LinkedHashMap<>();

    // Per-player state
    private final Map<UUID, Long>   regentApproachMs  = new HashMap<>();
    private final Map<UUID, Long>   lastMoveSinceMs   = new HashMap<>();
    private final Map<UUID, Long>   lastMirrorMs      = new HashMap<>();
    private final Set<UUID>         cradleActive      = new HashSet<>();

    private BukkitTask proximityTask;
    private File dataFile;

    public EndStructureManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        dataFile = new File(plugin.getDataFolder(), "end_structures.yml");
        plugin.getDataFolder().mkdirs();
        loadSites();
        proximityTask = new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin, 20L, 20L);
        plugin.getLogger().info("[EndStructureManager] Initialized.");
    }

    public void shutdown() {
        if (proximityTask != null) proximityTask.cancel();
        saveSites();
    }

    // ── Proximity tick ────────────────────────────────────────────────────────

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() != World.Environment.THE_END) continue;

            boolean insideThrone = false;
            for (EndSite site : sites.values()) {
                if (site.center() == null) continue;
                if (!Objects.equals(player.getWorld().getName(), site.center().getWorld().getName())) continue;

                double dist = player.getLocation().distance(site.center());
                boolean inRange = dist <= site.radius();
                boolean nearby64 = dist <= 64;

                if (inRange) {
                    if (!hasVisited(player, site.id())) onFirstVisit(player, site);
                    applyPassives(player, site, now, dist);
                    if (site.type() == StructureType.HOLLOW_THRONE) insideThrone = true;
                } else if (nearby64 && site.type() == StructureType.FRAGMENT_DUNGEON) {
                    showDungeonCompass(player, site);
                }
            }

            // Clear approach timer the moment the player leaves any Hollow Throne site,
            // so elapsed time only counts continuous within-area presence.
            if (!insideThrone) regentApproachMs.remove(player.getUniqueId());
        }
    }

    private void onFirstVisit(Player player, EndSite site) {
        markVisited(player, site.id());

        switch (site.type()) {
            case WITNESS_TOWER -> {
                String fragId = "witness_tower_" + site.id();
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragId);
                String lore = VestigiumLib.getLoreRegistry().getLoreContent("end_structures", "witness_tower");
                player.sendMessage("§5[Witness Tower] §7" + (lore.isEmpty() ? site.displayName() : lore));
                player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_AMBIENT, 0.7f, 0.8f);
            }
            case THE_CRADLE -> {
                if (player.getPersistentDataContainer().has(CRADLE_KEY, PersistentDataType.BYTE)) return;
                if (cradleActive.contains(player.getUniqueId())) return;
                cradleActive.add(player.getUniqueId());
                player.getPersistentDataContainer().set(CRADLE_KEY, PersistentDataType.BYTE, (byte) 1);
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), "cradle_origin_witnessed");
                spawnCradleParticles(site.center());
                for (int i = 0; i < CRADLE_SEQUENCE.length; i++) {
                    final String msg = CRADLE_SEQUENCE[i];
                    new BukkitRunnable() {
                        @Override public void run() {
                            if (player.isOnline()) player.sendMessage(msg);
                        }
                    }.runTaskLater(plugin, i * 60L);
                }
                new BukkitRunnable() {
                    @Override public void run() { cradleActive.remove(player.getUniqueId()); }
                }.runTaskLater(plugin, CRADLE_SEQUENCE.length * 60L + 20L);
            }
            case SUNDERED_SANCTUM -> {
                String part = site.sanctumPart();
                addSanctumPart(player, part);
                String fragId = "sundered_sanctum_part" + part;
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragId);
                String lore = VestigiumLib.getLoreRegistry()
                        .getLoreContent("end_structures", "sundered_sanctum_part" + part);
                player.sendMessage("§5[Sundered Sanctum — Part " + part + "] §7" + (lore.isEmpty() ? site.displayName() : lore));
                player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 0.8f, 0.7f);
                checkSanctumComplete(player);
            }
            case CARTOGRAPHERS_OBSERVATORY -> {
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), "cartographers_observatory_reached");
                String lore = VestigiumLib.getLoreRegistry()
                        .getLoreContent("end_structures", "cartographers_observatory");
                player.sendMessage("§6[Cartographer's Observatory] §7" + (lore.isEmpty() ? site.displayName() : lore));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 0.6f);
            }
            case VOID_ANCHOR -> {
                player.getPersistentDataContainer()
                        .set(ANCHOR_KEY, PersistentDataType.STRING, site.id());
                String lore = VestigiumLib.getLoreRegistry()
                        .getLoreContent("end_structures", "void_anchor");
                player.sendMessage("§8[Void Anchor] §7" + (lore.isEmpty()
                        ? "The anchor recognises your presence. You are indexed to this coordinate." : lore));
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), "void_anchor_" + site.id());
                player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.6f, 0.8f);
            }
            case FRAGMENT_DUNGEON -> {
                String fragId = "fragment_dungeon_" + site.id();
                VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), fragId);
                String lore = VestigiumLib.getLoreRegistry()
                        .getLoreContent("end_structures", "fragment_dungeon");
                player.sendMessage("§8[Fragment Dungeon] §7" + (lore.isEmpty() ? site.displayName() : lore));
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ROTATE_ITEM, 0.8f, 1.0f);
            }
            case HOLLOW_THRONE, MIRROR_POOL -> { /* handled in applyPassives */ }
        }
    }

    private void applyPassives(Player player, EndSite site, long now, double dist) {
        switch (site.type()) {
            case WITNESS_TOWER -> {
                // suppress Enderman aggression in the tower
                player.getWorld().getNearbyEntities(site.center(), 12, 12, 12).stream()
                        .filter(e -> e instanceof Enderman)
                        .map(e -> (Enderman) e)
                        .filter(e -> player.equals(e.getTarget()))
                        .forEach(e -> e.setTarget(null));
                // END_ROD particles above
                if (ThreadLocalRandom.current().nextBoolean()) {
                    player.getWorld().spawnParticle(Particle.END_ROD,
                            site.center().clone().add(0, 20, 0), 1, 2, 0, 2, 0.02);
                }
            }
            case HOLLOW_THRONE -> tickHollowThrone(player, site, now);
            case MIRROR_POOL   -> tickMirrorPool(player, site, now);
            case CARTOGRAPHERS_OBSERVATORY -> {
                // Spinning END_ROD particles overhead
                if (ThreadLocalRandom.current().nextInt(4) == 0) {
                    double angle = (now / 500.0) % (2 * Math.PI);
                    Location ring = site.center().clone().add(
                            Math.cos(angle) * 5, 15, Math.sin(angle) * 5);
                    player.getWorld().spawnParticle(Particle.END_ROD, ring, 1, 0, 0, 0, 0.02);
                }
            }
            default -> { /* no passives */ }
        }
    }

    // ── Hollow Throne ─────────────────────────────────────────────────────────

    private void tickHollowThrone(Player player, EndSite site, long now) {
        if (player.getPersistentDataContainer().has(THRONE_AUD_KEY, PersistentDataType.BYTE)) return;

        boolean armed = isArmed(player);
        boolean moved = (now - lastMoveSinceMs.getOrDefault(player.getUniqueId(), 0L)) < 3_000L;

        if (armed || moved) {
            regentApproachMs.remove(player.getUniqueId());
            return;
        }

        long start = regentApproachMs.computeIfAbsent(player.getUniqueId(), k -> now);
        long elapsed = now - start;

        if (elapsed < 30_000L) {
            // progress hint
            if (elapsed > 5_000L && elapsed % 10_000L < 2_000L) {
                player.sendActionBar(Component.text("§8Stand still. Unarmed. The record proceeds."));
            }
            return;
        }

        // Audience granted
        regentApproachMs.remove(player.getUniqueId());
        player.getPersistentDataContainer().set(THRONE_AUD_KEY, PersistentDataType.BYTE, (byte) 1);
        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), "hollow_throne_audience");

        String[] lines = {
            VestigiumLib.getLoreRegistry().getLoreContent("end_structures", "hollow_throne_approach"),
            VestigiumLib.getLoreRegistry().getLoreContent("end_structures", "hollow_throne_audience"),
            VestigiumLib.getLoreRegistry().getLoreContent("end_structures", "hollow_throne_lore2"),
            VestigiumLib.getLoreRegistry().getLoreContent("end_structures", "hollow_throne_lore3")
        };
        String[] fallback = {
            "§5The Regent does not require a weapon.",
            "§5You have been recognised. Category: inheritor.",
            "§5The throne was not built for sitting. It was built for witnessing.",
            "§5Your presence has been logged."
        };

        for (int i = 0; i < lines.length; i++) {
            final String msg = lines[i].isEmpty() ? fallback[i] : "§5" + lines[i];
            new BukkitRunnable() {
                @Override public void run() { if (player.isOnline()) player.sendMessage(msg); }
            }.runTaskLater(plugin, i * 60L);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 0.8f, 0.6f);
    }

    private boolean isArmed(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main == null || main.getType().isAir()) return false;
        String name = main.getType().name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.equals("BOW")
                || name.equals("CROSSBOW") || name.equals("TRIDENT");
    }

    // ── Mirror Pool ───────────────────────────────────────────────────────────

    private void tickMirrorPool(Player player, EndSite site, long now) {
        if (site.center() == null) return;
        // Circle of DRIPPING_WATER particles
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double angle = (now / 800.0) % (2 * Math.PI);
        for (int i = 0; i < 3; i++) {
            double a = angle + i * (2 * Math.PI / 3);
            Location ring = site.center().clone().add(Math.cos(a) * 3, 0.1, Math.sin(a) * 3);
            player.getWorld().spawnParticle(Particle.DRIPPING_WATER, ring, 1, 0.1, 0, 0.1, 0);
        }
        // END_ROD underneath
        if (rng.nextBoolean()) {
            player.getWorld().spawnParticle(Particle.END_ROD,
                    site.center().clone().add(rng.nextDouble(-3, 3), -1, rng.nextDouble(-3, 3)),
                    1, 0, 0, 0, 0.01);
        }
        // Ambient message
        long next = lastMirrorMs.getOrDefault(player.getUniqueId(), 0L);
        if (now >= next) {
            lastMirrorMs.put(player.getUniqueId(),
                    now + rng.nextLong(60_000L, 120_000L));
            player.sendMessage(POOL_AMBIENT[rng.nextInt(POOL_AMBIENT.length)]);
        }
    }

    // ── Fragment Dungeon compass ──────────────────────────────────────────────

    private void showDungeonCompass(Player player, EndSite site) {
        if (hasVisited(player, site.id())) return;
        Location dest = site.center();
        Location from = player.getLocation();
        double dx = dest.getX() - from.getX();
        double dz = dest.getZ() - from.getZ();
        double bearingToDest = Math.toDegrees(Math.atan2(dx, -dz));
        if (bearingToDest < 0) bearingToDest += 360;
        double playerBearing = ((player.getLocation().getYaw() + 180) % 360 + 360) % 360;
        double rel = (bearingToDest - playerBearing + 360) % 360;
        int sector = (int) Math.round(rel / 45) % 8;
        int dist = (int) player.getLocation().distance(dest);
        player.sendActionBar(Component.text(
                "§8Dungeon §7" + COMPASS_ARROWS[sector] + " §f" + site.displayName() + " §8(" + dist + "m)"));
    }

    // ── Sundered Sanctum helpers ──────────────────────────────────────────────

    private void addSanctumPart(Player player, String part) {
        String data = player.getPersistentDataContainer()
                .getOrDefault(SANCTUM_KEY, PersistentDataType.STRING, "");
        Set<String> parts = new HashSet<>(Arrays.asList(data.split(",")));
        parts.remove("");
        parts.add(part);
        player.getPersistentDataContainer()
                .set(SANCTUM_KEY, PersistentDataType.STRING, String.join(",", parts));
    }

    private void checkSanctumComplete(Player player) {
        String data = player.getPersistentDataContainer()
                .getOrDefault(SANCTUM_KEY, PersistentDataType.STRING, "");
        Set<String> parts = new HashSet<>(Arrays.asList(data.split(",")));
        parts.remove("");
        if (!parts.containsAll(Set.of("1", "2", "3"))) return;

        VestigiumLib.getLoreRegistry().grantFragment(player.getUniqueId(), "sundered_sanctum_complete");
        String msg = VestigiumLib.getLoreRegistry()
                .getLoreContent("end_structures", "sundered_sanctum_complete");
        String final_msg = msg.isEmpty()
                ? "§5The Sanctum is complete. Three chambers, three islands, one record."
                : "§5" + msg;
        player.sendMessage(final_msg);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 0.8f);
        plugin.getServer().broadcast(Component.text(
                "§5[Sundered Sanctum] §7" + player.getName() + " has completed all three chambers."));
    }

    // ── Cradle particles ──────────────────────────────────────────────────────

    private void spawnCradleParticles(Location center) {
        if (center == null || center.getWorld() == null) return;
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (tick++ > 40) { cancel(); return; }
                double angle = tick * 0.3;
                double r = tick * 0.2;
                Location p = center.clone().add(Math.cos(angle) * r, tick * 0.3, Math.sin(angle) * r);
                center.getWorld().spawnParticle(Particle.DRAGON_BREATH, p, 3, 0.2, 0.2, 0.2, 0.03);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ── Visited helpers ───────────────────────────────────────────────────────

    private boolean hasVisited(Player player, String siteId) {
        String data = player.getPersistentDataContainer()
                .getOrDefault(SITE_VISITED_KEY, PersistentDataType.STRING, "");
        return Arrays.asList(data.split(",")).contains(siteId);
    }

    private void markVisited(Player player, String siteId) {
        String data = player.getPersistentDataContainer()
                .getOrDefault(SITE_VISITED_KEY, PersistentDataType.STRING, "");
        Set<String> set = new HashSet<>(Arrays.asList(data.split(",")));
        set.remove("");
        set.add(siteId);
        player.getPersistentDataContainer()
                .set(SITE_VISITED_KEY, PersistentDataType.STRING, String.join(",", set));
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getPlayer().getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()) return;
        lastMoveSinceMs.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        regentApproachMs.remove(id);
        lastMoveSinceMs.remove(id);
        lastMirrorMs.remove(id);
        cradleActive.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        String npcType = event.getRightClicked().getPersistentDataContainer()
                .get(NPC_TYPE_KEY, PersistentDataType.STRING);
        if (npcType == null) return;
        event.setCancelled(true);
        if ("regent".equals(npcType)) {
            Player p = event.getPlayer();
            p.sendMessage("§8The Regent does not respond to direct interaction.");
            p.sendMessage("§8Approach unarmed. Stand still. Wait.");
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_AMBIENT, 0.6f, 0.7f);
        }
    }

    // ── Admin command handler ─────────────────────────────────────────────────

    public boolean handleCommand(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("vestigium.end.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 2) { printHelp(sender); return true; }

        switch (args[1].toLowerCase()) {
            case "add" -> handleAdd(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender);
            case "npc" -> handleNpc(sender, args);
            case "sanctumpart" -> handleSanctumPart(sender, args);
            default -> printHelp(sender);
        }
        return true;
    }

    private void handleAdd(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("§cMust be a player."); return; }
        if (args.length < 4) { sender.sendMessage("§cUsage: /vestructure add <type> <id> [name...]"); return; }
        StructureType type;
        try { type = StructureType.valueOf(args[2].toUpperCase()); }
        catch (IllegalArgumentException e) {
            sender.sendMessage("§cUnknown type. Valid: " + Arrays.stream(StructureType.values())
                    .map(Enum::name).collect(Collectors.joining(", "))); return;
        }
        String id = args[3].toLowerCase();
        String name = args.length > 4
                ? String.join(" ", Arrays.copyOfRange(args, 4, args.length))
                : id;
        EndSite site = new EndSite(id, name, type, p.getLocation().clone(), defaultRadius(type), "1");
        sites.put(id, site);
        saveSites();
        sender.sendMessage("§aSite '" + id + "' (" + type.name() + ") registered.");
    }

    private void handleRemove(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§cUsage: /vestructure remove <id>"); return; }
        String id = args[2].toLowerCase();
        if (sites.remove(id) != null) { saveSites(); sender.sendMessage("§aSite removed."); }
        else sender.sendMessage("§cSite not found: " + id);
    }

    private void handleList(org.bukkit.command.CommandSender sender) {
        if (sites.isEmpty()) { sender.sendMessage("§7No End structure sites registered."); return; }
        sender.sendMessage("§8=== End Structure Sites ===");
        sites.values().forEach(s -> sender.sendMessage(
                "§5" + s.id() + " §8(" + s.type().name() + ") — §7" + s.displayName()
                + (s.type() == StructureType.SUNDERED_SANCTUM ? " §8[part " + s.sanctumPart() + "]" : "")));
    }

    private void handleNpc(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage("§cUsage: /vestructure npc <spawn|remove> [siteId]"); return; }
        switch (args[2].toLowerCase()) {
            case "spawn" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cMust be a player."); return; }
                if (args.length < 4) { sender.sendMessage("§cUsage: /vestructure npc spawn <siteId>"); return; }
                String siteId = args[3].toLowerCase();
                spawnRegent(p.getLocation(), siteId);
                sender.sendMessage("§aThe Regent spawned at your location (site: " + siteId + ").");
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cMust be a player."); return; }
                p.getNearbyEntities(5, 5, 5).stream()
                        .filter(e -> e.getPersistentDataContainer().has(NPC_TYPE_KEY, PersistentDataType.STRING))
                        .findFirst()
                        .ifPresentOrElse(e -> { e.remove(); sender.sendMessage("§aNPC removed."); },
                                () -> sender.sendMessage("§cNo structure NPC within 5 blocks."));
            }
            default -> sender.sendMessage("§cUnknown npc sub-command.");
        }
    }

    private void handleSanctumPart(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 4) { sender.sendMessage("§cUsage: /vestructure sanctumpart <id> <1|2|3>"); return; }
        String id = args[2].toLowerCase();
        String part = args[3];
        if (!Set.of("1", "2", "3").contains(part)) { sender.sendMessage("§cPart must be 1, 2, or 3."); return; }
        EndSite existing = sites.get(id);
        if (existing == null) { sender.sendMessage("§cSite not found."); return; }
        sites.put(id, new EndSite(existing.id(), existing.displayName(), existing.type(),
                existing.center(), existing.radius(), part));
        saveSites();
        sender.sendMessage("§aSite '" + id + "' assigned as Sanctum Part " + part + ".");
    }

    private void printHelp(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("§8=== /vestructure ===");
        sender.sendMessage("§7add <type> <id> [name...] §8— register site at player location");
        sender.sendMessage("§7remove <id> §8— remove site");
        sender.sendMessage("§7list §8— list all sites");
        sender.sendMessage("§7npc spawn <siteId> §8— spawn The Regent at player location");
        sender.sendMessage("§7npc remove §8— remove nearest structure NPC");
        sender.sendMessage("§7sanctumpart <id> <1|2|3> §8— assign sanctum part number");
    }

    // ── NPC Spawn ─────────────────────────────────────────────────────────────

    private void spawnRegent(Location loc, String siteId) {
        World world = loc.getWorld();
        if (world == null) return;
        world.spawn(loc, Enderman.class, e -> {
            e.setAI(false);
            e.setInvulnerable(true);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setSilent(false);
            e.customName(Component.text("§5The Regent"));
            e.setCustomNameVisible(true);
            e.getPersistentDataContainer().set(NPC_TYPE_KEY, PersistentDataType.STRING, "regent");
            e.getPersistentDataContainer().set(NPC_SITE_KEY, PersistentDataType.STRING, siteId);
        });
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void loadSites() {
        if (!dataFile.exists()) return;
        org.bukkit.configuration.file.YamlConfiguration cfg =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);
        for (String id : cfg.getKeys(false)) {
            String display     = cfg.getString(id + ".display", id);
            String typeName    = cfg.getString(id + ".type", "WITNESS_TOWER");
            int radius         = cfg.getInt(id + ".radius", 32);
            String sanctumPart = cfg.getString(id + ".sanctum_part", "1");
            String worldName   = cfg.getString(id + ".world", "world_the_end");
            double x = cfg.getDouble(id + ".x");
            double y = cfg.getDouble(id + ".y");
            double z = cfg.getDouble(id + ".z");
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) continue;
            StructureType type;
            try { type = StructureType.valueOf(typeName); } catch (IllegalArgumentException e) { continue; }
            sites.put(id, new EndSite(id, display, type, new Location(world, x, y, z), radius, sanctumPart));
        }
        plugin.getLogger().info("[EndStructureManager] Loaded " + sites.size() + " site(s).");
    }

    private void saveSites() {
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        for (EndSite site : sites.values()) {
            cfg.set(site.id() + ".display", site.displayName());
            cfg.set(site.id() + ".type", site.type().name());
            cfg.set(site.id() + ".radius", site.radius());
            cfg.set(site.id() + ".sanctum_part", site.sanctumPart());
            if (site.center() != null && site.center().getWorld() != null) {
                cfg.set(site.id() + ".world", site.center().getWorld().getName());
                cfg.set(site.id() + ".x", site.center().getX());
                cfg.set(site.id() + ".y", site.center().getY());
                cfg.set(site.id() + ".z", site.center().getZ());
            }
        }
        try { cfg.save(dataFile); } catch (IOException e) {
            plugin.getLogger().warning("[EndStructureManager] Could not save end_structures.yml: " + e.getMessage());
        }
    }

    private int defaultRadius(StructureType type) {
        return switch (type) {
            case WITNESS_TOWER, MIRROR_POOL, THE_CRADLE -> 24;
            case HOLLOW_THRONE, FRAGMENT_DUNGEON -> 20;
            case SUNDERED_SANCTUM, CARTOGRAPHERS_OBSERVATORY -> 32;
            case VOID_ANCHOR -> 12;
        };
    }

    public List<String> getSiteIds() { return new ArrayList<>(sites.keySet()); }
}
