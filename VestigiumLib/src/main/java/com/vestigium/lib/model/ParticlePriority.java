package com.vestigium.lib.model;

public enum ParticlePriority {
    /** Bypasses the atmospheric particle budget. Use for gameplay-critical feedback. */
    GAMEPLAY,
    /** Draws from the shared atmospheric budget. Subject to TPS scaling and culling. */
    ATMOSPHERIC
}
