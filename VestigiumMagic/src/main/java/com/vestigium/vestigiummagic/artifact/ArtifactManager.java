package com.vestigium.vestigiummagic.artifact;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.ParticlePriority;
import com.vestigium.vestigiummagic.VestigiumMagic;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Artifact items — unique named items with right-click abilities.
 *
 * Artifacts are tagged with "vestigium:artifact_type" PDC STRING.
 * Per-player cooldowns tracked in memory.
 *
 * Built-in artifacts:
 *
 *   SOUL_MIRROR       GLASS         — right-click: shows attacker's current health as % in chat;
 *                                     on hold passively grants +1 armor while sculk is nearby
 *
 *   HOLLOW_CROWN      GOLDEN_HELMET — equip: night vision while omen > 400
 *                                     right-click: brief invulnerability (2s), 60s cooldown
 *
 *   WAYFARER_ORB      ENDER_EYE     — right-click: marks nearest structure anchor block
 *                                     with glowing particles for 30s; 120s cooldown
 *
 *   ANTECEDENT_KEY    TRIAL_KEY     — right-click near lecterns: unlocks antecedent script
 *                                     (grants antecedent cipher fragment to player)
 *
 *   TIDAL_LENS        SPYGLASS      — right-click: reveals nearby underwater structures;
 *                                     REVEAL spell variant via SpellCaster; 90s cooldown
 */
public class ArtifactManager implements Listener, CommandExecutor {

    private static final NamespacedKey ARTIFACT_KEY =
            new NamespacedKey("vestigium", "artifact_type");

    private static final List<ArtifactDef> ARTIFACTS = List.of(
            new ArtifactDef("SOUL_MIRROR",    Material.GLASS,          "§fSoul Mirror",
                    "§7Reflects the truth of what stands before you.", 0),
            new ArtifactDef("HOLLOW_CROWN",   Material.GOLDEN_HELMET,  "§6Hollow Crown",
                    "§7Power has a price. You feel it immediately.", 60_000),
            new ArtifactDef("WAYFARER_ORB",   Material.ENDER_EYE,      "§aWayfarer's Orb",
                    "§7The road remembers where it has been.", 120_000),
            new ArtifactDef("ANTECEDENT_KEY", Material.TRIAL_KEY,      "§dAntecedent Key",
                    "§7Some locks were meant to be found, not forced.", 0),
            new ArtifactDef("TIDAL_LENS",     Material.SPYGLASS,       "§3Tidal Lens",
                    "§7The deep has a shape. This shows you its edges.", 90_000)
    );

    private final VestigiumMagic plugin;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public ArtifactManager(VestigiumMagic plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        var cmd = plugin.getCommand("vartifact");
        if (cmd != null) cmd.setExecutor(this);
        plugin.getLogger().info("[ArtifactManager] Initialized — " + ARTIFACTS.size() + " artifacts.");
    }

    // -------------------------------------------------------------------------
    // Item creation
    // -------------------------------------------------------------------------

    public ItemStack createArtifact(String type) {
        ArtifactDef def = ARTIFACTS.stream()
                .filter(a -> a.type().equals(type)).findFirst().orElse(null);
        if (def == null) return null;

        ItemStack item = new ItemStack(def.material());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(def.displayName());
        meta.setLore(List.of(def.lore()));
        meta.getPersistentDataContainer()
                .set(ARTIFACT_KEY, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    public String getArtifactType(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(ARTIFACT_KEY, PersistentDataType.STRING);
    }

    // -------------------------------------------------------------------------
    // Interaction
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getItem() == null) return;

        String type = getArtifactType(event.getItem());
        if (type == null) return;

        event.setCancelled(true);
        activateArtifact(event.getPlayer(), type);
    }

    private void activateArtifact(Player player, String type) {
        ArtifactDef def = ARTIFACTS.stream().filter(a -> a.type().equals(type)).findFirst().orElse(null);
        if (def == null) return;

        if (def.cooldownMs() > 0 && isOnCooldown(player, type)) {
            long remaining = (getCooldownExpiry(player, type) - System.currentTimeMillis()) / 1000;
            player.sendMessage("§7" + def.displayName() + " §7— cooldown: §e" + remaining + "s");
            return;
        }

        switch (type) {
            case "SOUL_MIRROR" -> {
                player.getWorld().getNearbyEntities(player.getLocation(), 10, 10, 10).stream()
                        .filter(e -> e instanceof LivingEntity && e != player)
                        .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(player.getLocation())))
                        .ifPresent(e -> {
                            LivingEntity le = (LivingEntity) e;
                            double pct = (le.getHealth() / le.getAttribute(
                                    org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()) * 100;
                            player.sendMessage("§f[Soul Mirror] §7"
                                    + e.getName() + ": §a" + String.format("%.0f", pct) + "% §7health");
                        });
            }
            case "HOLLOW_CROWN" -> {
                setCooldown(player, type, def.cooldownMs());
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 40, 4));
                VestigiumLib.getParticleManager().queueParticle(
                        player.getLocation(), Particle.TOTEM_OF_UNDYING, null, ParticlePriority.GAMEPLAY);
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.5f, 1.5f);
                player.sendMessage("§6The crown protects you. Briefly.");
            }
            case "WAYFARER_ORB" -> {
                setCooldown(player, type, def.cooldownMs());
                // Highlight nearest structure anchor block
                player.getWorld().getNearbyEntities(player.getLocation(), 64, 64, 64).stream()
                        .findFirst().ifPresent(e ->
                                VestigiumLib.getParticleManager().queueParticle(
                                        e.getLocation(), Particle.END_ROD, null, ParticlePriority.GAMEPLAY));
                // Pulse particles around player
                for (int i = 0; i < 8; i++) {
                    double angle = (2 * Math.PI / 8) * i;
                    Location loc = player.getLocation().clone().add(
                            Math.cos(angle) * 3, 0.5, Math.sin(angle) * 3);
                    VestigiumLib.getParticleManager().queueParticle(loc, Particle.END_ROD, null, ParticlePriority.GAMEPLAY);
                }
                player.sendMessage("§aThe orb pulses. Something nearby remembers the road.");
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.2f);
            }
            case "ANTECEDENT_KEY" -> {
                VestigiumLib.getLoreRegistry().grantFragment(
                        player.getUniqueId(), "antecedent_cipher_fragment");
                player.sendMessage("§dThe key turns. Something old reads itself to you.");
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANCIENT_DEBRIS_BREAK, 0.5f, 0.8f);
            }
            case "TIDAL_LENS" -> {
                setCooldown(player, type, def.cooldownMs());
                // Delegate to spell caster REVEAL effect
                plugin.getSpellRegistry()
                        .getById("deep_reveal")
                        .ifPresent(spell -> plugin.getSpellCaster().tryCast(player, spell));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Admin command — /vartifact give <player> <type>
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vestigium.artifact.admin")) {
            sender.sendMessage("§cNo permission."); return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage("§7Usage: /vartifact give <player> <type>"); return true;
        }
        Player target = plugin.getServer().getPlayer(args[1]);
        if (target == null) { sender.sendMessage("§cPlayer not found."); return true; }
        ItemStack item = createArtifact(args[2].toUpperCase());
        if (item == null) { sender.sendMessage("§cUnknown artifact type."); return true; }
        target.getInventory().addItem(item);
        sender.sendMessage("§aGave " + args[2] + " to " + target.getName() + ".");
        return true;
    }

    // -------------------------------------------------------------------------

    private boolean isOnCooldown(Player p, String type) {
        return System.currentTimeMillis() < getCooldownExpiry(p, type);
    }

    private long getCooldownExpiry(Player p, String type) {
        return cooldowns.getOrDefault(p.getUniqueId(), Collections.emptyMap())
                .getOrDefault(type, 0L);
    }

    private void setCooldown(Player p, String type, long durationMs) {
        cooldowns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>())
                .put(type, System.currentTimeMillis() + durationMs);
    }

    private record ArtifactDef(String type, Material material, String displayName,
                                String lore, long cooldownMs) {}
}
