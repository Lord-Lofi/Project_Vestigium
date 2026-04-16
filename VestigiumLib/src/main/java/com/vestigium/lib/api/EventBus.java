package com.vestigium.lib.api;

import com.vestigium.lib.event.VestigiumEvent;
import com.vestigium.lib.event.VestigiumEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Internal event bus for all cross-plugin communication within the Vestigium suite.
 *
 * Rules:
 * - No plugin may import or call another plugin's classes directly.
 * - All cross-plugin communication fires and subscribes through this bus.
 * - Listeners are called synchronously on the thread that fired the event.
 */
public class EventBus {

    private final Map<Class<?>, List<VestigiumEventListener<?>>> listeners = new ConcurrentHashMap<>();
    private final Logger logger;

    public EventBus(Logger logger) {
        this.logger = logger;
    }

    /**
     * Subscribes a listener to a specific event type.
     *
     * @param eventClass the event class to listen for
     * @param listener   the listener to invoke when the event fires
     */
    public <T extends VestigiumEvent> void subscribe(Class<T> eventClass, VestigiumEventListener<T> listener) {
        listeners.computeIfAbsent(eventClass, k -> new ArrayList<>()).add(listener);
    }

    /**
     * Fires an event, invoking all registered listeners for its type.
     * Exceptions in individual listeners are caught and logged — one bad listener
     * cannot prevent others from receiving the event.
     *
     * @param event the event to fire
     */
    @SuppressWarnings("unchecked")
    public <T extends VestigiumEvent> void fire(T event) {
        List<VestigiumEventListener<?>> eventListeners = listeners.get(event.getClass());
        if (eventListeners == null || eventListeners.isEmpty()) return;

        for (VestigiumEventListener<?> listener : eventListeners) {
            try {
                ((VestigiumEventListener<T>) listener).onEvent(event);
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "[EventBus] Listener threw exception handling " + event.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Removes all listeners for a given event type.
     * Typically used during plugin disable to avoid stale listener references.
     */
    public void unsubscribeAll(Class<? extends VestigiumEvent> eventClass) {
        listeners.remove(eventClass);
    }

    /** Removes all listeners for all event types. Call during VestigiumLib disable. */
    public void clear() {
        listeners.clear();
    }
}
