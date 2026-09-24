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
package com.protonmail.landrevillejf.swingide.update.channels;

import com.protonmail.landrevillejf.swingide.update.Version;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * Enumeration of available update channels.
 * Users can opt into different channels for different update frequencies and stability levels.
 */
@Slf4j
public enum UpdateChannel {
    STABLE("Stable", "Production releases with thorough testing", 2592000000L), // 30 days
    BETA("Beta", "Pre-release versions for early testing", 604800000L), // 7 days
    NIGHTLY("Nightly", "Daily builds with latest features", 86400000L); // 1 day

    private final String displayName;
    private final String description;
    private final long checkIntervalMs;

    UpdateChannel(String displayName, String description, long checkIntervalMs) {
        this.displayName = displayName;
        this.description = description;
        this.checkIntervalMs = checkIntervalMs;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public long getCheckIntervalMs() { return checkIntervalMs; }

    /**
     * Value stored in {@code update-config.properties} for this channel.
     */
    public String getConfigValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse channel from string.
     */
    public static UpdateChannel parse(String value) {
        if (value == null || value.isBlank()) {
            return STABLE;
        }
        try {
            return UpdateChannel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown update channel '{}', falling back to STABLE", value);
            return STABLE; // Default to stable
        }
    }

    /**
     * Tells whether a released version may be proposed to a user subscribed to
     * this channel.
     * <ul>
     *   <li>{@code STABLE} only accepts final releases ({@code 1.2.3})</li>
     *   <li>{@code BETA} also accepts {@code beta} and {@code rc} qualifiers</li>
     *   <li>{@code NIGHTLY} accepts every build, including {@code alpha} and
     *       {@code snapshot}</li>
     * </ul>
     *
     * @param version the version advertised by the update server
     * @return {@code true} when the version belongs to this channel
     */
    public boolean accepts(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }

        String qualifier;
        try {
            qualifier = Version.parse(version.trim()).getQualifier().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            log.warn("Version '{}' cannot be parsed, rejecting it for channel {}", version, this);
            return false;
        }

        return switch (this) {
            case STABLE -> qualifier.isEmpty();
            case BETA -> qualifier.isEmpty() || qualifier.contains("beta") || qualifier.contains("rc");
            case NIGHTLY -> true;
        };
    }
}
