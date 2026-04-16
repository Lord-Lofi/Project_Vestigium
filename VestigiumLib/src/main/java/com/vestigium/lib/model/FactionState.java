package com.vestigium.lib.model;

public class FactionState {

    private final String factionId;
    private int health; // 0-100
    private long lastExpansionMillis;

    public FactionState(String factionId, int health, long lastExpansionMillis) {
        this.factionId = factionId;
        this.health = Math.max(0, Math.min(100, health));
        this.lastExpansionMillis = lastExpansionMillis;
    }

    public String getFactionId() {
        return factionId;
    }

    public int getHealth() {
        return health;
    }

    public void setHealth(int health) {
        this.health = Math.max(0, Math.min(100, health));
    }

    public long getLastExpansionMillis() {
        return lastExpansionMillis;
    }

    public void setLastExpansionMillis(long millis) {
        this.lastExpansionMillis = millis;
    }

    public boolean isCollapsed() {
        return health < 20;
    }

    /** True if health > 80 and the 7-day expansion cooldown has elapsed. */
    public boolean canExpand() {
        if (health <= 80) return false;
        long sevenDays = 7L * 24 * 60 * 60 * 1000;
        return (System.currentTimeMillis() - lastExpansionMillis) >= sevenDays;
    }
}
