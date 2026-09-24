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

/**
 * The set of notifications currently shown as transient toasts, and when each
 * expires. Distinct from the {@link NotificationModel} log: the log keeps every
 * notification for the tray to list, while this queue holds only the handful
 * popping up right now and drops them once their time-to-live elapses.
 *
 * <p>Time is never read here — every method takes the current time in
 * milliseconds — so expiry and the visible cap are deterministic and
 * unit-testable headless, and the {@link ToastLayer} drives them from its
 * repaint timer.</p>
 */
final class ToastQueue {

    /** How long a toast stays up before it fades on its own. */
    static final long DEFAULT_TTL_MILLIS = 4500L;

    /** The most toasts stacked at once; the oldest is dropped past this. */
    static final int DEFAULT_MAX_VISIBLE = 4;

    /** One queued toast and the instant it expires. */
    private static final class Entry {
        private final Notification notification;
        private final long expiresAt;

        Entry(Notification notification, long expiresAt) {
            this.notification = notification;
            this.expiresAt = expiresAt;
        }
    }

    private final long ttlMillis;
    private final int maxVisible;

    /** Oldest at the head, newest at the tail. */
    private final Deque<Entry> entries = new ArrayDeque<>();

    ToastQueue() {
        this(DEFAULT_TTL_MILLIS, DEFAULT_MAX_VISIBLE);
    }

    /**
     * @param ttlMillis  how long each toast stays up; must be positive
     * @param maxVisible the most toasts stacked at once; must be at least 1
     */
    ToastQueue(long ttlMillis, int maxVisible) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive: " + ttlMillis);
        }
        if (maxVisible < 1) {
            throw new IllegalArgumentException("maxVisible must be >= 1: " + maxVisible);
        }
        this.ttlMillis = ttlMillis;
        this.maxVisible = maxVisible;
    }

    /**
     * Queues {@code notification} as a toast expiring {@code ttlMillis} after
     * {@code nowMillis}, dropping the oldest if that exceeds the visible cap. A
     * null notification is ignored.
     */
    void push(Notification notification, long nowMillis) {
        if (notification == null) {
            return;
        }
        entries.addLast(new Entry(notification, nowMillis + ttlMillis));
        while (entries.size() > maxVisible) {
            entries.removeFirst();
        }
    }

    /**
     * Drops every expired toast and returns the ones still showing, newest
     * first (so index 0 is the most recent, which the layer stacks at the
     * bottom).
     */
    List<Notification> visible(long nowMillis) {
        entries.removeIf(entry -> entry.expiresAt <= nowMillis);
        List<Notification> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            result.add(entry.notification);
        }
        Collections.reverse(result);   // tail (newest) first
        return result;
    }

    /**
     * Dismisses the toast for the notification with {@code id} ahead of its
     * expiry.
     *
     * @return true if a toast was dismissed
     */
    boolean dismiss(long id) {
        return entries.removeIf(entry -> entry.notification.id() == id);
    }

    /** Dismisses every toast immediately. */
    void clear() {
        entries.clear();
    }

    /** The number of toasts queued, ignoring expiry (peek; does not evict). */
    int size() {
        return entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    long ttlMillis() {
        return ttlMillis;
    }

    int maxVisible() {
        return maxVisible;
    }
}
