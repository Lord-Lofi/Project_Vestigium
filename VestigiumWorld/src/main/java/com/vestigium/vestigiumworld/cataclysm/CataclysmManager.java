package com.vestigium.vestigiumworld.cataclysm;

import com.vestigium.lib.VestigiumLib;
import com.vestigium.lib.event.CataclysmEndEvent;
import com.vestigium.lib.event.CataclysmStartEvent;
import com.vestigium.lib.event.OmenThresholdEvent;
import com.vestigium.vestigiumworld.VestigiumWorld;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Manages active cataclysms, escalation chain logic, and omen-driven triggers.
 *
 * Escalation chains (design doc):
 *   Primary:     Meteor -> Celestial Shower -> Volcanic -> Corruption/Astral -> Herobrine
 *   Underground: Sinkhole -> Planar Rift -> The Fracture -> Void Bloom
 *   Forest:      Canopy Fire -> The Withering
 *   Recovery:    The Long Exhale (auto after any end) -> Amber Hour -> Starfall Vigil
 *
 * Omen-driven auto-triggers:
 *   200+  -> small events eligible (sinkhole, flash flood, iron rain)
 *   400+  -> earthquake, the silence, bloodmoon
 *   600+  -> planar rift, the fracture, the sundering
 *   800+  -> astral convergence, the great unraveling
 *   1000  -> Herobrine's Return (guaranteed)
 */
public class CataclysmManager {

    private final VestigiumWorld plugin;
    private final Map<String, ActiveCataclysm> active = new LinkedHashMap<>();

    private static final Map<String, List<String>> ESCALATION_CHAINS = new LinkedHashMap<>();
    static {
        ESCALATION_CHAINS.put(CataclysmType.METEOR_STRIKE,
                List.of(CataclysmType.CELESTIAL_IMPACT_SHOWER));
        ESCALATION_CHAINS.put(CataclysmType.CELESTIAL_IMPACT_SHOWER,
                List.of(CataclysmType.VOLCANIC_ERUPTION));
        ESCALATION_CHAINS.put(CataclysmType.VOLCANIC_ERUPTION,
                List.of(CataclysmType.CORRUPTION_CASCADE, CataclysmType.ASTRAL_CONVERGENCE));
        ESCALATION_CHAINS.put(CataclysmType.ASTRAL_CONVERGENCE,
                List.of(CataclysmType.HEROBRINES_RETURN));
        ESCALATION_CHAINS.put(CataclysmType.SINKHOLE,
                List.of(CataclysmType.PLANAR_RIFT));
        ESCALATION_CHAINS.put(CataclysmType.PLANAR_RIFT,
                List.of(CataclysmType.THE_FRACTURE));
        ESCALATION_CHAINS.put(CataclysmType.THE_FRACTURE,
                List.of(CataclysmType.VOID_BLOOM));
        ESCALATION_CHAINS.put(CataclysmType.CANOPY_FIRE,
                List.of(CataclysmType.THE_WITHERING));
    }

    // Check escalations every 10 real minutes; roll random events every 30 real minutes
    private static final long ESCALATION_CHECK_TICKS = 12_000L;
    private static final long OMEN_TRIGGER_TICKS     = 36_000L;

    private BukkitRunnable escalationTask;
    private BukkitRunnable omenTriggerTask;

    public CataclysmManager(VestigiumWorld plugin) {
        this.plugin = plugin;
    }

    public void init() {
        VestigiumLib.getEventBus().subscribe(OmenThresholdEvent.class, this::onOmenThreshold);
        startEscalationTask();
        startOmenTriggerTask();
        plugin.getLogger().info("[CataclysmManager] Initialized.");
    }

    public void shutdown() {
        if (escalationTask  != null) escalationTask.cancel();
        if (omenTriggerTask != null) omenTriggerTask.cancel();
        active.clear();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts a cataclysm. Fires CataclysmStartEvent — VestigiumAtmosphere and
     * other subscribers react via EventBus. Only one instance per type at a time.
     *
     * @param type           cataclysm type key (see CataclysmType)
     * @param epicenter      world location origin; null for server-wide events
     * @param durationMillis positive = timed; -1 = player-resolved (indefinite)
     */
    public void start(String type, Location epicenter, long durationMillis) {
        if (active.containsKey(type)) return;
        active.put(type, new ActiveCataclysm(type, epicenter, durationMillis));
        VestigiumLib.getEventBus().fire(new CataclysmStartEvent(type, epicenter));
        VestigiumLib.getOmenAPI().addOmen(omenCostFor(type));
        plugin.getLogger().info("[CataclysmManager] Started: " + type);
    }

    /**
     * Ends a cataclysm. Fires CataclysmEndEvent.
     * Automatically schedules The Long Exhale recovery 30 seconds later.
     */
    public void end(String type, boolean playerResolved) {
        ActiveCataclysm cataclysm = active.remove(type);
        if (cataclysm == null) return;
        VestigiumLib.getEventBus().fire(
                new CataclysmEndEvent(type, cataclysm.getEpicenter(), playerResolved));
        // Auto-trigger recovery sequence after 30 seconds
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> start(CataclysmType.THE_LONG_EXHALE, cataclysm.getEpicenter(),
                        3L * 24 * 60 * 60 * 1000),
                20L * 30);
        plugin.getLogger().info("[CataclysmManager] Ended: " + type
                + " (player-resolved=" + playerResolved + ")");
    }

    public boolean isActive(String type) {
        return active.containsKey(type);
    }

    public Map<String, ActiveCataclysm> getActive() {
        return Collections.unmodifiableMap(active);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void onOmenThreshold(OmenThresholdEvent event) {
        if (!event.isAscending()) return;
        if (event.getThreshold() == 1000 && !isActive(CataclysmType.HEROBRINES_RETURN)) {
            start(CataclysmType.HEROBRINES_RETURN, null, -1);
        }
        if (event.getThreshold() == 800 && !isActive(CataclysmType.ASTRAL_CONVERGENCE)) {
            start(CataclysmType.ASTRAL_CONVERGENCE, null, 2L * 60 * 60 * 1000);
        }
    }

    private void startEscalationTask() {
        escalationTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkEscalations();
            }
        };
        escalationTask.runTaskTimer(plugin, ESCALATION_CHECK_TICKS, ESCALATION_CHECK_TICKS);
    }

    private void startOmenTriggerTask() {
        omenTriggerTask = new BukkitRunnable() {
            @Override
            public void run() {
                maybeRollRandomCataclysm();
            }
        };
        omenTriggerTask.runTaskTimer(plugin, OMEN_TRIGGER_TICKS, OMEN_TRIGGER_TICKS);
    }

    private void checkEscalations() {
        // Expire timed cataclysms
        new ArrayList<>(active.keySet()).forEach(type -> {
            ActiveCataclysm c = active.get(type);
            if (c != null && c.isExpired()) end(type, false);
        });

        // Escalation chance scales with effective omen (max 25% per check at omen 1000)
        float omen = VestigiumLib.getOmenAPI().getEffectiveOmenScore();
        for (String activeType : new ArrayList<>(active.keySet())) {
            List<String> nextOptions = ESCALATION_CHAINS.get(activeType);
            if (nextOptions == null) continue;
            double chance = (omen / 1000.0) * 0.25;
            if (Math.random() < chance) {
                String candidate = nextOptions.get(new Random().nextInt(nextOptions.size()));
                if (!isActive(candidate)) {
                    ActiveCataclysm src = active.get(activeType);
                    start(candidate, src != null ? src.getEpicenter() : null,
                            defaultDurationFor(candidate));
                }
            }
        }
    }

    private void maybeRollRandomCataclysm() {
        int omen = VestigiumLib.getOmenAPI().getOmenScore();
        if (omen < 200 || plugin.getServer().getOnlinePlayers().isEmpty()) return;

        List<String> pool = new ArrayList<>();
        if (omen >= 200) pool.addAll(List.of(CataclysmType.SINKHOLE, CataclysmType.FLASH_FLOOD,
                CataclysmType.IRON_RAIN, CataclysmType.NETHER_TREMORS));
        if (omen >= 400) pool.addAll(List.of(CataclysmType.EARTHQUAKE,
                CataclysmType.THE_SILENCE, CataclysmType.BLOODMOON));
        if (omen >= 600) pool.addAll(List.of(CataclysmType.PLANAR_RIFT,
                CataclysmType.THE_FRACTURE, CataclysmType.THE_SUNDERING));
        if (omen >= 800) pool.add(CataclysmType.THE_GREAT_UNRAVELING);

        pool.removeIf(this::isActive);
        if (pool.isEmpty()) return;

        // Low base chance — rare, not constant
        double chance = (omen / 1000.0) * 0.08;
        if (Math.random() < chance) {
            String chosen = pool.get(new Random().nextInt(pool.size()));
            List<org.bukkit.entity.Player> players = new ArrayList<>(
                    plugin.getServer().getOnlinePlayers());
            Location epicenter = players.get(new Random().nextInt(players.size())).getLocation();
            start(chosen, epicenter, defaultDurationFor(chosen));
        }
    }

    private int omenCostFor(String type) {
        if (CataclysmType.HEROBRINES_RETURN.equals(type))  return 0;
        if (CataclysmType.ASTRAL_CONVERGENCE.equals(type)) return 50;
        if (CataclysmType.THE_LONG_NIGHT.equals(type))     return 100;
        if (CataclysmType.VOLCANIC_ERUPTION.equals(type))  return 80;
        if (CataclysmType.THE_WITHERING.equals(type))      return 60;
        if (CataclysmType.CORRUPTION_CASCADE.equals(type)) return 40;
        if (CataclysmType.PLANAR_RIFT.equals(type))        return 50;
        if (CataclysmType.THE_FRACTURE.equals(type))       return 70;
        if (CataclysmType.SINKHOLE.equals(type))           return 10;
        if (CataclysmType.EARTHQUAKE.equals(type))         return 20;
        return 5;
    }

    private long defaultDurationFor(String type) {
        if (CataclysmType.THE_SILENCE.equals(type))    return 60 * 60 * 1000L;
        if (CataclysmType.BLOODMOON.equals(type))      return 20 * 60 * 1000L;
        if (CataclysmType.THE_FRACTURE.equals(type))   return 2L * 60 * 60 * 1000;
        if (CataclysmType.THE_SUNDERING.equals(type))  return 48L * 60 * 60 * 1000;
        if (CataclysmType.IRON_RAIN.equals(type))      return 10 * 60 * 1000L;
        if (CataclysmType.FLASH_FLOOD.equals(type))    return 30 * 60 * 1000L;
        if (CataclysmType.NETHER_TREMORS.equals(type)) return 15 * 60 * 1000L;
        return -1;
    }
}
