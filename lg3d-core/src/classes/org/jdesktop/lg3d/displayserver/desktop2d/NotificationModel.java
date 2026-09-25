/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * The desktop's notification log: the ordered set of notifications raised so far
 * (newest first), an unread count for the taskbar tray's badge, and change
 * listeners so the tray and the toast overlay stay in step.
 *
 * <p>The log is capped: adding past {@code capacity} evicts the oldest, so a
 * long-running desktop cannot grow it without bound. The clock and the id
 * sequence are internal but the clock is injectable, so tests get deterministic
 * timestamps. Pure state and listeners (no Swing, no Java 3D); the {@code long}
 * "now" is supplied by the caller's clock rather than read here, keeping every
 * method unit-testable headless.</p>
 */
public final class NotificationModel {

    /** How many notifications the log retains before evicting the oldest. */
    public static final int DEFAULT_CAPACITY = 50;

    private final int capacity;
    private final LongSupplier clock;
    private final Deque<Notification> items = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private long nextId = 1L;
    private int unread;

    /** A log with the default capacity on the wall clock. */
    public NotificationModel() {
        this(DEFAULT_CAPACITY, System::currentTimeMillis);
    }

    /**
     * @param capacity the most notifications retained; must be at least 1
     * @param clock    supplies each notification's timestamp
     */
    public NotificationModel(int capacity, LongSupplier clock) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        this.capacity = capacity;
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
    }

    /**
     * Raises a notification, adds it to the front of the log, marks it unread
     * and notifies the listeners.
     *
     * @return the notification that was added
     */
    public Notification add(String title, String message, Notification.Kind kind) {
        Notification notification =
                new Notification(nextId++, title, message, kind, clock.getAsLong());
        items.addFirst(notification);
        while (items.size() > capacity) {
            items.removeLast();
        }
        unread++;
        fireChange();
        return notification;
    }

    /**
     * Removes the notification with {@code id}, if present.
     *
     * @return true if one was removed
     */
    public boolean remove(long id) {
        boolean removed = items.removeIf(n -> n.id() == id);
        if (removed) {
            unread = Math.max(0, unread - 1);
            fireChange();
        }
        return removed;
    }

    /** Empties the log and resets the unread count. */
    public void clear() {
        if (items.isEmpty() && unread == 0) {
            return;
        }
        items.clear();
        unread = 0;
        fireChange();
    }

    /** The logged notifications, newest first. Never null; unmodifiable. */
    public List<Notification> notifications() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * How many notifications have been raised since the last
     * {@link #markAllRead()}, never more than the number actually retained.
     */
    public int unreadCount() {
        return Math.min(unread, items.size());
    }

    /** Marks every retained notification as seen (clears the tray badge). */
    public void markAllRead() {
        if (unread == 0) {
            return;
        }
        unread = 0;
        fireChange();
    }

    /** Registers a callback invoked on every change to the log. */
    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Deregisters a previously added callback. */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void fireChange() {
        // Copy so a listener that mutates the model cannot cause a
        // ConcurrentModificationException on the listener list.
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }
}
