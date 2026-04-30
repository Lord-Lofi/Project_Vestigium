package com.vestigium.lib.api;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.model.Faction;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Registers the %vestigium_*% PlaceholderAPI expansion.
 *
 * Built-in placeholders (all player-context unless noted):
 *   %vestigium_omen%            raw omen score (0-1000, global)
 *   %vestigium_omen_effective%  omen with night multiplier applied
 *   %vestigium_omen_tier%       human label: Safe / Elevated / Dangerous / Critical / Catastrophic / Herobrine
 *   %vestigium_season%          current season name (SPRING / SUMMER / AUTUMN / WINTER)
 *   %vestigium_rep_<faction>%   player's reputation with that faction (e.g. rep_villagers)
 *
 * Extended placeholders registered by dependent plugins via register():
 *   %vestigium_balance%         Vault balance (registered by VestigiumEconomy)
 *   %vestigium_vestige_shards%  physical Vestige Shard count (registered by VestigiumEconomy)
 */
public class PlaceholderAPIHook extends PlaceholderExpansion {

    private static final Map<String, Function<OfflinePlayer, String>> registry = new HashMap<>();

    /**
     * Registers an additional placeholder handler.
     * {@code key} is the part after "vestigium_" — e.g. "balance" for %vestigium_balance%.
     * Call this from any dependent plugin's onEnable() after VestigiumLib has loaded.
     */
    public static void register(String key, Function<OfflinePlayer, String> handler) {
        registry.put(key, handler);
    }

    @Override public @NotNull String getIdentifier() { return "vestigium"; }
    @Override public @NotNull String getAuthor()     { return "Lord-Lofi"; }
    @Override public @NotNull String getVersion()    { return VestigiumLib.getInstance().getDescription().getVersion(); }
    @Override public boolean persist()               { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {

        // --- Omen (global, no player required) ---
        switch (params) {
            case "omen"            -> { return String.valueOf(VestigiumLib.getOmenAPI().getOmenScore()); }
            case "omen_effective"  -> { return String.valueOf((int) VestigiumLib.getOmenAPI().getEffectiveOmenScore()); }
            case "omen_tier"       -> { return omenTier(VestigiumLib.getOmenAPI().getOmenScore()); }
            case "season"          -> {
                var season = VestigiumLib.getSeasonAPI().getCurrentSeason();
                return season == null ? "Unknown" : season.name();
            }
        }

        // --- Per-player reputation: %vestigium_rep_villagers% etc ---
        if (params.startsWith("rep_") && player != null) {
            var online = player.getPlayer();
            if (online == null) return "0";
            try {
                Faction faction = Faction.valueOf(params.substring(4).toUpperCase());
                return String.valueOf(VestigiumLib.getReputationAPI()
                        .getReputation(online.getUniqueId(), faction));
            } catch (IllegalArgumentException e) {
                return "0";
            }
        }

        // --- Registry: balance, vestige_shards, etc (registered by other modules) ---
        var handler = registry.get(params);
        if (handler != null) return handler.apply(player);

        return null;
    }

    private static String omenTier(int omen) {
        if (omen >= 1000) return "Herobrine";
        if (omen >= 800)  return "Catastrophic";
        if (omen >= 600)  return "Critical";
        if (omen >= 400)  return "Dangerous";
        if (omen >= 200)  return "Elevated";
        return "Safe";
    }
}
