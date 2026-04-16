package com.vestigium.lib.event;

@FunctionalInterface
public interface VestigiumEventListener<T extends VestigiumEvent> {
    void onEvent(T event);
}
