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
package org.jdesktop.lg3d.ftpclient.model;

/**
 * The FTP data-connection mode. Passive mode (the default, and what almost
 * every client behind NAT uses) has the client connect out to a server-opened
 * data port; active mode has the server connect back to the client, which rarely
 * works through a firewall. SFTP has a single multiplexed channel and ignores
 * this setting.
 */
public enum TransferMode {

    /** Client-initiated data connection (PASV). Works through NAT/firewalls. */
    PASSIVE("Passive"),

    /** Server-initiated data connection (PORT). Rarely usable behind a firewall. */
    ACTIVE("Active");

    private final String displayName;

    TransferMode(String displayName) {
        this.displayName = displayName;
    }

    /** @return the human-readable label shown in the site dialog. */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Resolves a mode from a stored or user-typed name, tolerating case.
     *
     * @param name a mode name ({@code passive}, {@code ACTIVE}, ...)
     * @return the matching {@link TransferMode}, or {@code null} when unrecognized
     */
    public static TransferMode fromName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        for (TransferMode m : values()) {
            if (m.name().equalsIgnoreCase(trimmed)
                    || m.displayName.equalsIgnoreCase(trimmed)) {
                return m;
            }
        }
        return null;
    }
}
