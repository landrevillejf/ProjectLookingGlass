/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.scenemanager.utils.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.jdesktop.lg3d.displayserver.desktop2d.DoNotDisturb;
import org.jdesktop.lg3d.displayserver.desktop2d.Notification;
import org.jdesktop.lg3d.displayserver.desktop2d.NotificationModel;
import org.jdesktop.lg3d.displayserver.desktop2d.ToastQueue;

/**
 * The native 3D desktop's notification entry point.
 *
 * <p>This is the desktop-agnostic seam that reuses the pure notification models
 * the 2D/Swing desktop already ships ({@link NotificationModel} log,
 * {@link ToastQueue} transient popups and {@link DoNotDisturb} gate — all now
 * public so both shells share one implementation) and exposes them to the 3D
 * desktop. Anything running in the desktop JVM posts through
 * {@link #get()}{@code .post(...)}; the HUD toast overlay
 * ({@code ToastOverlayPlugin} in {@code lg3d-widgets}) subscribes with
 * {@link #addListener(Runnable)} and renders {@link #visibleToasts()} on the
 * front-most {@link DesktopHudLayer}.</p>
 *
 * <p>Posting always records the notification in the log (so history/unread stay
 * complete) but only raises a toast when Do Not Disturb does not suppress its
 * {@link Notification.Kind}; {@link Notification.Kind#ERROR} always surfaces. All
 * time is read through an injectable {@link LongSupplier} clock, so the whole
 * service is deterministic and unit-testable headless.</p>
 */
public final class NotificationService {

    private static volatile NotificationService instance;

    private final NotificationModel model;
    private final ToastQueue toasts;
    private final DoNotDisturb dnd;
    private final LongSupplier clock;
    private final List<Runnable> listeners = new ArrayList<>();

    /** A service on the wall clock with default log/toast capacities. */
    public NotificationService() {
        this(System::currentTimeMillis);
    }

    /**
     * @param clock supplies "now" in epoch milliseconds for expiry, DND deadlines
     *              and notification timestamps; null falls back to the wall clock
     */
    public NotificationService(LongSupplier clock) {
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
        this.model = new NotificationModel();
        this.toasts = new ToastQueue();
        this.dnd = new DoNotDisturb();
        this.dnd.addListener(this::fire);
    }

    /** The process-wide service used by the running desktop. */
    public static NotificationService get() {
        NotificationService s = instance;
        if (s == null) {
            synchronized (NotificationService.class) {
                s = instance;
                if (s == null) {
                    s = new NotificationService();
                    instance = s;
                }
            }
        }
        return s;
    }

    private long now() {
        return clock.getAsLong();
    }

    /**
     * Records a notification and, unless Do Not Disturb suppresses its kind,
     * raises it as a transient toast. Notifies listeners either way (the log
     * changed).
     *
     * @param title   the short heading; must be non-blank
     * @param message the optional body (may be null/blank)
     * @param kind    the severity; null is treated as {@link Notification.Kind#INFO}
     * @return the logged notification
     * @throws IllegalArgumentException if {@code title} is null or blank
     */
    public synchronized Notification post(String title, String message, Notification.Kind kind) {
        Notification n = model.add(title, message, kind);
        if (!dnd.shouldSuppress(n.kind(), now())) {
            toasts.push(n, now());
        }
        fire();
        return n;
    }

    /** The toasts currently showing, newest first (expired ones are evicted). */
    public synchronized List<Notification> visibleToasts() {
        return toasts.visible(now());
    }

    /** How many toasts are currently showing. */
    public synchronized int visibleToastCount() {
        return visibleToasts().size();
    }

    /**
     * Dismisses the toast for {@code id} ahead of its expiry.
     *
     * @return true if a toast was dismissed
     */
    public synchronized boolean dismissToast(long id) {
        boolean dismissed = toasts.dismiss(id);
        if (dismissed) {
            fire();
        }
        return dismissed;
    }

    /** Dismisses every toast immediately. */
    public synchronized void clearToasts() {
        toasts.clear();
        fire();
    }

    /** The full notification log, newest first. Never null. */
    public synchronized List<Notification> notifications() {
        return model.notifications();
    }

    /** How many logged notifications are unread (tray badge). */
    public synchronized int unreadCount() {
        return model.unreadCount();
    }

    /** Marks the whole log read (clears the tray badge). */
    public synchronized void markAllRead() {
        model.markAllRead();
        fire();
    }

    /** Empties the log and dismisses every toast. */
    public synchronized void clear() {
        model.clear();
        toasts.clear();
        fire();
    }

    /** Whether Do Not Disturb is on (ignoring any deadline). */
    public synchronized boolean isDoNotDisturb() {
        return dnd.isEnabled();
    }

    /** Whether DND is suppressing right now (on and not past its deadline). */
    public synchronized boolean isDoNotDisturbActive() {
        return dnd.active(now());
    }

    /** Turns Do Not Disturb on indefinitely or off. */
    public synchronized void setDoNotDisturb(boolean enabled) {
        if (enabled) {
            dnd.enable();
        } else {
            dnd.disable();
        }
    }

    /** Turns Do Not Disturb on for {@code durationMillis} from now. */
    public synchronized void doNotDisturbFor(long durationMillis) {
        dnd.enableFor(durationMillis, now());
    }

    /** Flips Do Not Disturb on (indefinitely) or off. */
    public synchronized void toggleDoNotDisturb() {
        dnd.toggle(now());
    }

    /** Registers a callback invoked on every change (post, dismiss, DND, read). */
    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Deregisters a previously added callback. */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void fire() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }
}
