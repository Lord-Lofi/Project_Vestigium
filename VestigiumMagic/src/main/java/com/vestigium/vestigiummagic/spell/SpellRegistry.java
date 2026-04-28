package com.vestigium.vestigiummagic.spell;

import com.vestigium.vestigiummagic.VestigiumMagic;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Loads spell definitions from plugins/VestigiumMagic/spells/*.yml.
 *
 * Spell YAML:
 *   id:           string
 *   name:         string (display)
 *   cast_item:    string (Material name — item held to cast)
 *   mana_cost:    int
 *   cooldown_ms:  long
 *   effect_type:  BOLT | BURST | WARD | PULL | BLINK | REVEAL
 *   power:        double (scales effect magnitude)
 *   range:        double (blocks)
 *   omen_required: int (min omen, 0 = no gate)
 *
 * Default spells seeded:
 *   sculk_bolt       ECHO_SHARD     20 mana  BOLT    range 20
 *   void_blink       ENDER_PEARL    15 mana  BLINK   range 15
 *   resonant_burst   AMETHYST_SHARD 30 mana  BURST   range 8
 *   ward_of_calm     NAUTILUS_SHELL 25 mana  WARD    duration-based
 *   tidal_pull       HEART_OF_SEA   35 mana  PULL    range 12
 *   deep_reveal      SPYGLASS       10 mana  REVEAL  range 30
 */
public class SpellRegistry {

    private final VestigiumMagic plugin;
    private final Map<String, SpellDefinition> spells = new LinkedHashMap<>();

    public SpellRegistry(VestigiumMagic plugin) {
        this.plugin = plugin;
    }

    public void load() {
        spells.clear();
        File dir = new File(plugin.getDataFolder(), "spells");
        if (!dir.exists()) { dir.mkdirs(); saveDefaults(dir); }

        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;

        for (File f : files) {
            try {
                SpellDefinition def = SpellDefinition.fromConfig(
                        YamlConfiguration.loadConfiguration(f));
                spells.put(def.id(), def);
            } catch (Exception e) {
                plugin.getLogger().warning("[SpellRegistry] Failed to load " + f.getName());
            }
        }
        plugin.getLogger().info("[SpellRegistry] Loaded " + spells.size() + " spells.");
    }

    public Optional<SpellDefinition> getById(String id) {
        return Optional.ofNullable(spells.get(id));
    }

    public Collection<SpellDefinition> getAll() {
        return Collections.unmodifiableCollection(spells.values());
    }

    /** Returns the spell whose cast_item matches the given material name, if any. */
    public Optional<SpellDefinition> getByItemMaterial(String materialName) {
        return spells.values().stream()
                .filter(s -> s.castItem().equalsIgnoreCase(materialName))
                .findFirst();
    }

    // -------------------------------------------------------------------------

    private void saveDefaults(File dir) {
        record DS(String id, String name, String castItem, int mana, long cooldownMs,
                  String effect, double power, double range, int omenRequired) {}

        List<DS> defaults = List.of(
            new DS("sculk_bolt",      "Sculk Bolt",      "ECHO_SHARD",     20, 3000, "BOLT",   2.0, 20, 0),
            new DS("void_blink",      "Void Blink",      "ENDER_PEARL",    15, 5000, "BLINK",  1.0, 15, 0),
            new DS("resonant_burst",  "Resonant Burst",  "AMETHYST_SHARD", 30, 8000, "BURST",  1.5,  8, 200),
            new DS("ward_of_calm",    "Ward of Calm",    "NAUTILUS_SHELL", 25, 12000,"WARD",   1.0,  5, 0),
            new DS("tidal_pull",      "Tidal Pull",      "HEART_OF_THE_SEA", 35, 10000, "PULL", 1.0, 12, 0),
            new DS("deep_reveal",     "Deep Reveal",     "SPYGLASS",       10, 6000, "REVEAL", 1.0, 30, 0)
        );

        for (DS d : defaults) {
            File f = new File(dir, d.id() + ".yml");
            if (f.exists()) continue;
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("id", d.id()); cfg.set("name", d.name());
            cfg.set("cast_item", d.castItem()); cfg.set("mana_cost", d.mana());
            cfg.set("cooldown_ms", d.cooldownMs()); cfg.set("effect_type", d.effect());
            cfg.set("power", d.power()); cfg.set("range", d.range());
            cfg.set("omen_required", d.omenRequired());
            try { cfg.save(f); } catch (Exception e) { /* best-effort */ }
        }
    }
}
