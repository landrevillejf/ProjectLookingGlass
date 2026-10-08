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
package org.jdesktop.lg3d.apps.securitycenter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One entry in the Security Center's append-only activity log: when it happened,
 * a short {@code category} and a human {@code message}. The hub records what it
 * observed or did - a scan finished, the host posture was read, private (Tor)
 * mode changed state, the network was cut or restored, a VPN tunnel came up or
 * went down, definitions were updated - so there is an auditable trail beside
 * the scan history.
 *
 * <p>A plain Jackson bean persisted by {@link SecurityCenterStore} (no-arg
 * constructor plus getters/setters, no process or file handle), so the log
 * round-trips as JSON and a corrupt file degrades to empty rather than throwing.
 * It holds no secrets and no scanned content - only the category and a short
 * description of the event.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuditEvent {

    /** Category: an antivirus scan started or finished. */
    public static final String CATEGORY_SCAN = "scan";
    /** Category: virus definitions were updated. */
    public static final String CATEGORY_DEFINITIONS = "definitions";
    /** Category: the host security posture was probed. */
    public static final String CATEGORY_POSTURE = "posture";
    /** Category: private (Tor) mode changed state. */
    public static final String CATEGORY_PRIVACY = "privacy";
    /** Category: a VPN tunnel was observed connecting or disconnecting. */
    public static final String CATEGORY_VPN = "vpn";
    /** Category: the desktop-wide network cut was raised or lifted. */
    public static final String CATEGORY_CUT = "cut";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private long epochMillis = System.currentTimeMillis();
    private String category = "";
    private String message = "";

    /** No-arg constructor for Jackson. */
    public AuditEvent() {
    }

    /**
     * Convenience constructor stamping the event at the current time.
     *
     * @param category the event category (see the {@code CATEGORY_*} constants)
     * @param message  the human-readable description
     */
    public AuditEvent(String category, String message) {
        this(System.currentTimeMillis(), category, message);
    }

    /**
     * Full constructor with an explicit timestamp (used by tests and any caller
     * that needs a deterministic clock).
     *
     * @param epochMillis the event time in epoch milliseconds
     * @param category    the event category
     * @param message     the human-readable description
     */
    public AuditEvent(long epochMillis, String category, String message) {
        this.epochMillis = epochMillis;
        setCategory(category);
        setMessage(message);
    }

    public long getEpochMillis() {
        return epochMillis;
    }

    public void setEpochMillis(long epochMillis) {
        this.epochMillis = epochMillis;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = (category == null) ? "" : category.trim();
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = (message == null) ? "" : message.trim();
    }

    /** The wall-clock stamp for the Activity tab, in the system zone. */
    public String timestamp() {
        return STAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    @Override
    public String toString() {
        String cat = category.isBlank() ? "event" : category;
        return timestamp() + "  [" + cat + "]  " + message;
    }
}
