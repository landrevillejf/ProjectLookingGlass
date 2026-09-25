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

import java.util.ArrayList;
import java.util.List;

/**
 * The desktop's Do Not Disturb state: whether transient notification toasts are
 * being suppressed, optionally only until a deadline.
 *
 * <p>Suppressing DND never drops a notification from the log — the
 * {@link NotificationModel} still records it so the tray/history stays complete;
 * only the popup toast is gated (see {@link Desktop2D#raiseNotification}).
 * {@link Notification.Kind#ERROR} is always allowed through, so a genuine
 * failure surfaces even while DND is on.</p>
 *
 * <p>Time is never read here — every time-sensitive method takes the current
 * epoch milliseconds — so expiry is deterministic and unit-testable headless.
 * Pure state and listeners (no Swing, no Java 3D).</p>
 */
final class DoNotDisturb {

    /** A common "on for a while" duration offered by the tray popup. */
    static final long ONE_HOUR_MILLIS = 60L * 60L * 1000L;

    private final List<Runnable> listeners = new ArrayList<>();

    private boolean enabled;
    /** Absolute epoch millis the timed suppression ends, or 0 for indefinite. */
    private long untilMillis;

    /** A DND that starts off. */
    DoNotDisturb() {
        this(false, 0L);
    }

    /**
     * Restores a persisted state. A deadline already in the past is treated as
     * expired, so DND comes back off rather than stuck on.
     *
     * @param enabled      whether DND was on
     * @param untilMillis  the absolute deadline (0 = indefinite); only meaningful
     *                     when {@code enabled}
     */
    DoNotDisturb(boolean enabled, long untilMillis) {
        this.enabled = enabled;
        this.untilMillis = enabled ? Math.max(0L, untilMillis) : 0L;
    }

    /**
     * Whether DND is actually suppressing right now: on, and either indefinite or
     * not yet past its deadline.
     */
    boolean active(long nowMillis) {
        return enabled && (untilMillis == 0L || nowMillis < untilMillis);
    }

    /** Turns DND on indefinitely. */
    void enable() {
        set(true, 0L);
    }

    /** Turns DND on until {@code durationMillis} after {@code nowMillis}. */
    void enableFor(long durationMillis, long nowMillis) {
        if (durationMillis <= 0L) {
            enable();
            return;
        }
        set(true, nowMillis + durationMillis);
    }

    /** Turns DND off. */
    void disable() {
        set(false, 0L);
    }

    /** Flips DND on (indefinitely) or off. */
    void toggle(long nowMillis) {
        if (active(nowMillis)) {
            disable();
        } else {
            enable();
        }
    }

    /**
     * Whether a notification of {@code kind} should be suppressed (its toast
     * hidden) at {@code nowMillis}. Errors always pass; everything else is
     * suppressed only while DND is active.
     */
    boolean shouldSuppress(Notification.Kind kind, long nowMillis) {
        return active(nowMillis) && isSuppressible(kind);
    }

    /** The raw on/off flag, ignoring any deadline (for persistence). */
    boolean isEnabled() {
        return enabled;
    }

    /** The absolute deadline in epoch millis, or 0 when indefinite (persistence). */
    long untilMillis() {
        return untilMillis;
    }

    /**
     * Whether {@code kind} is hideable by DND at all. Pure so it can be tested
     * without any state: only {@link Notification.Kind#ERROR} is exempt.
     */
    static boolean isSuppressible(Notification.Kind kind) {
        return kind != Notification.Kind.ERROR;
    }

    /** Registers a callback invoked on every state change. */
    void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Deregisters a previously added callback. */
    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void set(boolean newEnabled, long newUntil) {
        if (enabled == newEnabled && untilMillis == newUntil) {
            return;
        }
        enabled = newEnabled;
        untilMillis = newEnabled ? newUntil : 0L;
        fireChange();
    }

    private void fireChange() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }
}
