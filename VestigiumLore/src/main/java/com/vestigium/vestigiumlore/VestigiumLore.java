package com.vestigium.vestigiumlore;

import com.vestigium.vestigiumlore.bottle.MessageInABottleManager;
import com.vestigium.vestigiumlore.campfire.CampfireStoriesManager;
import com.vestigium.vestigiumlore.chain.FinalCartographerChain;
import com.vestigium.vestigiumlore.cipher.CipherManager;
import com.vestigium.vestigiumlore.delivery.LoreDeliveryManager;
import com.vestigium.vestigiumlore.terminal.TerminalManager;
import com.vestigium.vestigiumlore.tome.ServerMemoryTome;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumLore — all lore delivery systems, cipher items, the Final Cartographer
 * quest chain, and the server memory tome.
 * Depends only on VestigiumLib.
 */
public class VestigiumLore extends JavaPlugin {

    private static VestigiumLore instance;

    private LoreDeliveryManager     loreDeliveryManager;
    private FinalCartographerChain  finalCartographerChain;
    private CipherManager           cipherManager;
    private TerminalManager         terminalManager;
    private ServerMemoryTome        serverMemoryTome;
    private CampfireStoriesManager  campfireStoriesManager;
    private MessageInABottleManager messageInABottleManager;

    @Override
    public void onEnable() {
        instance = this;

        loreDeliveryManager     = new LoreDeliveryManager(this);
        finalCartographerChain  = new FinalCartographerChain(this);
        cipherManager           = new CipherManager(this);
        terminalManager         = new TerminalManager(this);
        serverMemoryTome        = new ServerMemoryTome(this);
        campfireStoriesManager  = new CampfireStoriesManager(this);
        messageInABottleManager = new MessageInABottleManager(this);

        loreDeliveryManager.init();
        finalCartographerChain.init();
        cipherManager.init();
        terminalManager.init();
        serverMemoryTome.init();
        campfireStoriesManager.init();
        messageInABottleManager.init();

        getLogger().info("VestigiumLore enabled.");
    }

    @Override
    public void onDisable() {
        if (loreDeliveryManager     != null) loreDeliveryManager.shutdown();
        if (campfireStoriesManager  != null) campfireStoriesManager.shutdown();
        if (serverMemoryTome        != null) serverMemoryTome.save();
        getLogger().info("VestigiumLore disabled.");
    }

    public static VestigiumLore getInstance()                      { return instance; }
    public LoreDeliveryManager getLoreDeliveryManager()            { return loreDeliveryManager; }
    public FinalCartographerChain getFinalCartographerChain()      { return finalCartographerChain; }
    public CipherManager getCipherManager()                        { return cipherManager; }
    public TerminalManager getTerminalManager()                    { return terminalManager; }
    public ServerMemoryTome getServerMemoryTome()                   { return serverMemoryTome; }
    public CampfireStoriesManager getCampfireStoriesManager()       { return campfireStoriesManager; }
    public MessageInABottleManager getMessageInABottleManager()     { return messageInABottleManager; }
}
