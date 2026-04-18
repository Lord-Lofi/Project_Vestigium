package com.vestigium.vestigiumlore.cipher;

import com.vestigium.vestigiumlore.VestigiumLore;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Rare cipher drops from specific mobs:
 *   Warden      → 5%  Resonant Cipher
 *   Elder Guardian → 3% Tidal Cipher
 *   Wither Skeleton → 2% Antecedent Cipher
 */
public class CipherDropListener implements Listener {

    private final VestigiumLore plugin;
    private final CipherManager cipherManager;

    public CipherDropListener(VestigiumLore plugin, CipherManager cipherManager) {
        this.plugin = plugin;
        this.cipherManager = cipherManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;

        EntityType type = event.getEntityType();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (type == EntityType.WARDEN && rng.nextInt(100) < 5) {
            event.getDrops().add(cipherManager.createCipher(CipherManager.CipherType.RESONANT));
        } else if (type == EntityType.ELDER_GUARDIAN && rng.nextInt(100) < 3) {
            event.getDrops().add(cipherManager.createCipher(CipherManager.CipherType.TIDAL));
        } else if (type == EntityType.WITHER_SKELETON && rng.nextInt(100) < 2) {
            event.getDrops().add(cipherManager.createCipher(CipherManager.CipherType.ANTECEDENT));
        }
    }
}
