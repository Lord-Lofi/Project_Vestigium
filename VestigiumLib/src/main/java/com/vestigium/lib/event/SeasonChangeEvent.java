package com.vestigium.lib.event;

import com.vestigium.lib.model.Season;

/** Fired when the server season transitions. */
public class SeasonChangeEvent extends VestigiumEvent {

    private final Season previousSeason;
    private final Season newSeason;

    public SeasonChangeEvent(Season previousSeason, Season newSeason) {
        this.previousSeason = previousSeason;
        this.newSeason = newSeason;
    }

    public Season getPreviousSeason() { return previousSeason; }
    public Season getNewSeason() { return newSeason; }
}
