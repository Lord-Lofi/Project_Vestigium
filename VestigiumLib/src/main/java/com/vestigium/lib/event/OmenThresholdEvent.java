package com.vestigium.lib.event;

/**
 * Fired when the OmenAPI score crosses a registered threshold.
 * Thresholds: 200 (elevated), 400 (dangerous), 600 (critical), 800 (catastrophic), 1000 (Herobrine).
 */
public class OmenThresholdEvent extends VestigiumEvent {

    private final int threshold;
    private final int previousScore;
    private final int newScore;
    private final boolean ascending; // true = crossed upward, false = crossed downward

    public OmenThresholdEvent(int threshold, int previousScore, int newScore) {
        this.threshold = threshold;
        this.previousScore = previousScore;
        this.newScore = newScore;
        this.ascending = newScore >= previousScore;
    }

    public int getThreshold() { return threshold; }
    public int getPreviousScore() { return previousScore; }
    public int getNewScore() { return newScore; }
    public boolean isAscending() { return ascending; }
}
