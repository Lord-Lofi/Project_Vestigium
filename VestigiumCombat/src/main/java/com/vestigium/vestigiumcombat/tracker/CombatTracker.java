package com.vestigium.vestigiumcombat.tracker;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.vestigiumcombat.VestigiumCombat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks active combat state per player.
 *
 * A player is "in combat" for 6 seconds after dealing or receiving damage.
 * Combat state is used by ComboSystem and CustomStatusEffectManager.
 *
 * Stored in memory only — no PDC needed for transient combat state.
 *
 * Per player:
 *   lastHitMillis    — when the player last dealt damage
 *   lastHitByMillis  — when the player was last damaged
 *   comboCount       — consecutive hits within COMBO_WINDOW_MS
 *   lastComboMillis  — timestamp of last combo-qualifying hit
 */
public class CombatTracker implements Listener {

    public static final long COMBAT_TIMEOUT_MS = 6_000L;
    public static final long COMBO_WINDOW_MS   = 1_500L;

    private final VestigiumCombat plugin;
    private final Map<UUID, CombatState> states = new HashMap<>();

    public CombatTracker(VestigiumCombat plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        // Decay stale states every 10 seconds
        new BukkitRunnable() {
            @Override public void run() {
                long now = System.currentTimeMillis();
                states.entrySet().removeIf(e -> now - e.getValue().lastActivityMillis() > COMBAT_TIMEOUT_MS * 2);
            }
        }.runTaskTimer(plugin, 200L, 200L);

        plugin.getLogger().info("[CombatTracker] Initialized.");
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public boolean isInCombat(Player player) {
        CombatState state = states.get(player.getUniqueId());
        if (state == null) return false;
        return System.currentTimeMillis() - state.lastActivityMillis() < COMBAT_TIMEOUT_MS;
    }

    public int getComboCount(Player player) {
        CombatState state = states.get(player.getUniqueId());
        if (state == null) return 0;
        long elapsed = System.currentTimeMillis() - state.lastComboMillis();
        return elapsed < COMBO_WINDOW_MS * 3 ? state.comboCount() : 0;
    }

    public void resetCombo(Player player) {
        CombatState state = states.get(player.getUniqueId());
        if (state != null) state.resetCombo();
    }

    public long getLastHitMillis(Player player) {
        CombatState state = states.get(player.getUniqueId());
        return state == null ? 0 : state.lastHitMillis();
    }

    // -------------------------------------------------------------------------
    // Event hooks
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        long now = System.currentTimeMillis();

        if (event.getDamager() instanceof Player attacker) {
            CombatState state = states.computeIfAbsent(
                    attacker.getUniqueId(), k -> new CombatState());
            state.recordHit(now);
            VestigiumLib.getOmenAPI().markPlayerActivity();
        }

        if (event.getEntity() instanceof Player victim) {
            states.computeIfAbsent(victim.getUniqueId(), k -> new CombatState())
                    .recordHitBy(now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    // -------------------------------------------------------------------------
    // State model
    // -------------------------------------------------------------------------

    static class CombatState {
        private long lastHitMillis;
        private long lastHitByMillis;
        private int  comboCount;
        private long lastComboMillis;

        void recordHit(long now) {
            if (now - lastComboMillis < COMBO_WINDOW_MS) {
                comboCount++;
            } else {
                comboCount = 1;
            }
            lastHitMillis  = now;
            lastComboMillis = now;
        }

        void recordHitBy(long now) { lastHitByMillis = now; }
        void resetCombo() { comboCount = 0; lastComboMillis = 0; }

        long lastHitMillis()   { return lastHitMillis; }
        long lastActivityMillis() { return Math.max(lastHitMillis, lastHitByMillis); }
        int  comboCount()      { return comboCount; }
        long lastComboMillis() { return lastComboMillis; }
    }
}
