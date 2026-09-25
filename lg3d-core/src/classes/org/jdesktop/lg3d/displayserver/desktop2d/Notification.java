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

import java.util.Objects;

/**
 * One desktop notification: a short titled message with a severity and the time
 * it was raised. Immutable, pure data (no Swing, no Java 3D), so it is trivially
 * unit-testable and safe to hand between the notification log, the taskbar tray
 * and the toast overlay.
 *
 * @see NotificationModel
 * @see ToastQueue
 */
public final class Notification {

    /** How urgent a notification is; drives the toast/tray accent colour. */
    public enum Kind {
        /** Ordinary information. */
        INFO,
        /** Something the user should notice but that is not fatal. */
        WARNING,
        /** A failure. */
        ERROR
    }

    private final long id;
    private final String title;
    private final String message;
    private final Kind kind;
    private final long timestampMillis;

    /**
     * @param id              the unique id assigned by the model
     * @param title           the short heading (non-blank)
     * @param message         the optional body; blank is stored as null
     * @param kind            the severity; null is treated as {@link Kind#INFO}
     * @param timestampMillis when it was raised, in epoch milliseconds
     * @throws IllegalArgumentException if {@code title} is null or blank
     */
    Notification(long id, String title, String message, Kind kind,
                 long timestampMillis) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must be non-blank");
        }
        this.id = id;
        this.title = title;
        this.message = (message == null || message.isBlank()) ? null : message;
        this.kind = (kind == null) ? Kind.INFO : kind;
        this.timestampMillis = timestampMillis;
    }

    public long id() {
        return id;
    }

    public String title() {
        return title;
    }

    /** The body text, or null when the notification has none. */
    public String message() {
        return message;
    }

    public Kind kind() {
        return kind;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Notification)) {
            return false;
        }
        Notification n = (Notification) other;
        return id == n.id && timestampMillis == n.timestampMillis
                && kind == n.kind && title.equals(n.title)
                && Objects.equals(message, n.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, message, kind, timestampMillis);
    }

    @Override
    public String toString() {
        return "Notification[" + id + " " + kind + " \"" + title + "\"]";
    }
}
