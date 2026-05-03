package com.vestigium.vestigiumlore;

import com.vestigium.vestigiumlore.chain.FinalCartographerChain;
import com.vestigium.vestigiumlore.cipher.CipherManager;
import com.vestigium.vestigiumlore.delivery.LoreDeliveryManager;
import com.vestigium.vestigiumlore.tome.ServerMemoryTome;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumLore — all lore delivery systems, cipher items, the Final Cartographer
 * quest chain, and the server memory tome.
 * Depends only on VestigiumLib.
 */
public class VestigiumLore extends JavaPlugin {

    private static VestigiumLore instance;

    private LoreDeliveryManager   loreDeliveryManager;
    private FinalCartographerChain finalCartographerChain;
    private CipherManager          cipherManager;
    private ServerMemoryTome       serverMemoryTome;

    @Override
    public void onEnable() {
        instance = this;

        loreDeliveryManager    = new LoreDeliveryManager(this);
        finalCartographerChain = new FinalCartographerChain(this);
        cipherManager          = new CipherManager(this);
        serverMemoryTome       = new ServerMemoryTome(this);

        loreDeliveryManager.init();
        finalCartographerChain.init();
        cipherManager.init();
        serverMemoryTome.init();

        getLogger().info("VestigiumLore enabled.");
    }

    @Override
    public void onDisable() {
        if (loreDeliveryManager != null) loreDeliveryManager.shutdown();
        if (serverMemoryTome    != null) serverMemoryTome.save();
        getLogger().info("VestigiumLore disabled.");
    }

    public static VestigiumLore getInstance()                      { return instance; }
    public LoreDeliveryManager getLoreDeliveryManager()            { return loreDeliveryManager; }
    public FinalCartographerChain getFinalCartographerChain()      { return finalCartographerChain; }
    public CipherManager getCipherManager()                        { return cipherManager; }
    public ServerMemoryTome getServerMemoryTome()                  { return serverMemoryTome; }
}
