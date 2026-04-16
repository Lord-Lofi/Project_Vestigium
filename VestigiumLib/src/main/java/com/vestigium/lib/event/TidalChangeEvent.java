package com.vestigium.lib.event;

/** Fired when the tidal clock advances to a new phase (0-11). Consumed by VestigiumOcean and VestigiumMobs. */
public class TidalChangeEvent extends VestigiumEvent {

    private final int previousPhase;
    private final int newPhase;
    private final boolean highTide;
    private final boolean lowTide;

    public TidalChangeEvent(int previousPhase, int newPhase, boolean highTide, boolean lowTide) {
        this.previousPhase = previousPhase;
        this.newPhase = newPhase;
        this.highTide = highTide;
        this.lowTide = lowTide;
    }

    /** Phase 0-11. Phases 0-5 are rising, 6-11 are falling. */
    public int getPreviousPhase() { return previousPhase; }
    public int getNewPhase() { return newPhase; }
    public boolean isHighTide() { return highTide; }
    public boolean isLowTide() { return lowTide; }
}
