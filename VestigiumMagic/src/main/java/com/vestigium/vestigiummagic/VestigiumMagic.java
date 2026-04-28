package com.vestigium.vestigiummagic;

import com.vestigium.vestigiummagic.artifact.ArtifactManager;
import com.vestigium.vestigiummagic.spell.SpellCaster;
import com.vestigium.vestigiummagic.spell.SpellRegistry;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VestigiumMagic — spell casting system, mana management, and artifact items
 * with unique right-click abilities.
 * Depends only on VestigiumLib.
 */
public class VestigiumMagic extends JavaPlugin {

    private static VestigiumMagic instance;

    private SpellRegistry spellRegistry;
    private SpellCaster   spellCaster;
    private ArtifactManager artifactManager;

    @Override
    public void onEnable() {
        instance = this;

        spellRegistry   = new SpellRegistry(this);
        spellCaster     = new SpellCaster(this, spellRegistry);
        artifactManager = new ArtifactManager(this);

        spellRegistry.load();
        spellCaster.init();
        artifactManager.init();

        getLogger().info("VestigiumMagic enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("VestigiumMagic disabled.");
    }

    public static VestigiumMagic getInstance()    { return instance; }
    public SpellRegistry getSpellRegistry()       { return spellRegistry; }
    public SpellCaster getSpellCaster()           { return spellCaster; }
    public ArtifactManager getArtifactManager()   { return artifactManager; }
}
