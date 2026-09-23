package com.protonmail.landrevillejf.swingide.core.bus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A minimal, thread-safe publish/subscribe event bus.
 * <p>
 * The update pipeline decouples its producers (periodic and asynchronous checks,
 * download progress) from its consumers (the auto-download/install subscriber and
 * the UI presenter) through this bus. Subscribers register for an exact event
 * type; {@link #publish(Object)} delivers synchronously on the calling thread to
 * every consumer registered for that event's runtime class, so a consumer that
 * needs the event dispatch thread hops onto it itself (as {@code UpdatePresenter}
 * does).
 * </p>
 * <p>
 * This class replaces the host application's shared bus: the module owns a
 * private instance unless a host injects one, and {@link #shutdown()} releases
 * every subscription once the owner is done with it.
 * </p>
 *
 * @author landrevillejf
 * @version 1.0.0
 * @since 1.0.0
 */
public class EventBus {

    /** Subscribers keyed by the exact event type they registered for. */
    private final Map<Class<?>, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    /** Set by {@link #shutdown()}; afterwards {@link #publish(Object)} is a no-op. */
    private volatile boolean shutdown;

    /**
     * Creates an empty bus with no subscribers.
     */
    public EventBus() {
        // No initial state beyond the empty subscriber map.
    }

    /**
     * Registers a consumer for an exact event type.
     *
     * @param type     the event class to listen for, {@code null} is ignored
     * @param consumer the callback invoked on publish, {@code null} is ignored
     * @param <T>      the event type
     */
    public <T> void subscribe(Class<T> type, Consumer<T> consumer) {
        if (type == null || consumer == null) {
            return;
        }
        subscribers.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    /**
     * Removes a previously registered consumer.
     *
     * @param type     the event class the consumer was registered for
     * @param consumer the exact consumer instance to remove
     * @param <T>      the event type
     */
    public <T> void unsubscribe(Class<T> type, Consumer<T> consumer) {
        if (type == null || consumer == null) {
            return;
        }
        List<Consumer<?>> registered = subscribers.get(type);
        if (registered != null) {
            registered.remove(consumer);
        }
    }

    /**
     * Delivers an event synchronously to every consumer registered for its exact
     * runtime type. Does nothing once the bus is shut down.
     *
     * @param event the event to publish, {@code null} is ignored
     */
    @SuppressWarnings("unchecked")
    public void publish(Object event) {
        if (event == null || shutdown) {
            return;
        }
        List<Consumer<?>> registered = subscribers.get(event.getClass());
        if (registered == null) {
            return;
        }
        for (Consumer<?> consumer : registered) {
            ((Consumer<Object>) consumer).accept(event);
        }
    }

    /**
     * Releases every subscription and stops further delivery. Idempotent.
     */
    public void shutdown() {
        shutdown = true;
        subscribers.clear();
    }
}
