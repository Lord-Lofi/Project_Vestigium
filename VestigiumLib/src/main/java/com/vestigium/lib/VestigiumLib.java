package com.vestigium.lib;

import com.vestigium.lib.api.*;
import com.vestigium.lib.util.Keys;
import com.vestigium.lib.util.TPSMonitor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumLib — Core library plugin.
 *
 * Initialises all APIs and exposes them via static getters.
 * Every other Vestigium plugin accesses APIs through these getters:
 *
 *   VestigiumLib.getOmenAPI().addOmen(10);
 *   VestigiumLib.getProtectionAPI().isProtected(location);
 *
 * Load order: STARTUP (before all dependent plugins).
 */
public class VestigiumLib extends JavaPlugin implements Listener {

    private static VestigiumLib instance;

    // APIs
    private EventBus eventBus;
    private ProtectionAPI protectionAPI;
    private OmenAPI omenAPI;
    private ReputationAPI reputationAPI;
    private SeasonAPI seasonAPI;
    private LoreRegistry loreRegistry;
    private FactionRegistry factionRegistry;
    private ParticleManager particleManager;
    private TPSMonitor tpsMonitor;

    @Override
    public void onEnable() {
        instance = this;

        // Step 1: Keys must be initialised before anything else touches PDC
        Keys.init(this);

        // Step 2: EventBus — no dependencies
        eventBus = new EventBus(getLogger());

        // Step 3: ProtectionAPI — only needs plugin reference for soft dep checks
        protectionAPI = new ProtectionAPI(this);

        // Step 4: Stateful APIs backed by PDC / YAML
        omenAPI = new OmenAPI(this, eventBus);
        reputationAPI = new ReputationAPI(this, eventBus);
        seasonAPI = new SeasonAPI(this, eventBus);
        factionRegistry = new FactionRegistry(this, eventBus);
        loreRegistry = new LoreRegistry(this);

        // Step 5: TPS monitor + ParticleManager
        tpsMonitor = new TPSMonitor();
        particleManager = new ParticleManager(this, tpsMonitor);

        // Step 6: Load persisted data
        saveDefaultConfig();
        factionRegistry.load();
        loreRegistry.loadAll();

        // Step 7: Initialise world-dependent APIs (worlds are available on POSTWORLD,
        // but VestigiumLib loads at STARTUP — we defer world init to first server tick)
        getServer().getScheduler().runTask(this, this::initWorldAPIs);

        // Step 8: Register block tag listener
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("VestigiumLib enabled. All core APIs ready.");
    }

    @Override
    public void onDisable() {
        if (omenAPI != null) omenAPI.shutdown();
        if (seasonAPI != null) seasonAPI.shutdown();
        if (factionRegistry != null) factionRegistry.save();
        if (eventBus != null) eventBus.clear();
        getLogger().info("VestigiumLib disabled.");
    }

    // -------------------------------------------------------------------------
    // World initialisation (deferred to first tick so worlds are loaded)
    // -------------------------------------------------------------------------

    private void initWorldAPIs() {
        World overworld = getServer().getWorlds().stream()
                .filter(w -> w.getEnvironment() == World.Environment.NORMAL)
                .findFirst()
                .orElse(null);

        if (overworld == null) {
            getLogger().severe("[VestigiumLib] No overworld found! OmenAPI and SeasonAPI will not function.");
            return;
        }

        omenAPI.init(overworld);
        seasonAPI.init(overworld);
    }

    // -------------------------------------------------------------------------
    // Block tagging — tag every player-placed block, remove tag on break
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        protectionAPI.tagPlayerPlaced(event.getBlockPlaced());
        // Also mark player as active for omen decay purposes
        omenAPI.markPlayerActivity();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        protectionAPI.untagPlayerPlaced(event.getBlock());
        omenAPI.markPlayerActivity();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only fire on meaningful block-to-block movement (not head turns)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        omenAPI.markPlayerActivity();
    }

    // -------------------------------------------------------------------------
    // Static API accessors — used by all dependent plugins
    // -------------------------------------------------------------------------

    public static VestigiumLib getInstance() {
        return instance;
    }

    public static EventBus getEventBus() {
        return instance.eventBus;
    }

    public static ProtectionAPI getProtectionAPI() {
        return instance.protectionAPI;
    }

    public static OmenAPI getOmenAPI() {
        return instance.omenAPI;
    }

    public static ReputationAPI getReputationAPI() {
        return instance.reputationAPI;
    }

    public static SeasonAPI getSeasonAPI() {
        return instance.seasonAPI;
    }

    public static LoreRegistry getLoreRegistry() {
        return instance.loreRegistry;
    }

    public static FactionRegistry getFactionRegistry() {
        return instance.factionRegistry;
    }

    public static ParticleManager getParticleManager() {
        return instance.particleManager;
    }

    public static TPSMonitor getTPSMonitor() {
        return instance.tpsMonitor;
    }
}
