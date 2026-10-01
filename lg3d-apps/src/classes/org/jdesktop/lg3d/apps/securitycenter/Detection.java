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

/**
 * One finding from an antivirus scan: the file {@code path}, the {@code threat}
 * (signature) name ClamAV reported, and whether the file is infected or could
 * not be read. Detections are immutable values produced by
 * {@link AntivirusBackend#parseScanOutput}; a clean scan yields none.
 *
 * @param path   the scanned file path (never null; empty when unknown)
 * @param threat the signature name for an infection, or the error message
 * @param status whether this is an infection or a read/scan error
 */
public record Detection(String path, String threat, Status status) {

    /** What kind of finding this detection represents. */
    public enum Status {
        /** ClamAV matched a virus signature in the file. */
        INFECTED,
        /** The file could not be scanned (unreadable, locked, too large). */
        ERROR
    }

    /** Normalises nulls so a detection is always safe to render. */
    public Detection {
        path = (path == null) ? "" : path;
        threat = (threat == null) ? "" : threat;
        status = (status == null) ? Status.INFECTED : status;
    }

    /**
     * Builds an infection detection.
     *
     * @param path   the infected file path
     * @param threat the matched signature name
     * @return the detection
     */
    public static Detection infected(String path, String threat) {
        return new Detection(path, threat, Status.INFECTED);
    }

    /**
     * Builds a scan-error detection.
     *
     * @param path    the file that could not be scanned
     * @param message the reason ClamAV reported
     * @return the detection
     */
    public static Detection error(String path, String message) {
        return new Detection(path, message, Status.ERROR);
    }

    /** @return true when ClamAV matched a signature in this file. */
    public boolean isInfected() {
        return status == Status.INFECTED;
    }

    @Override
    public String toString() {
        String label = threat.isBlank() ? path : path + "  [" + threat + "]";
        return (status == Status.ERROR) ? label + "  (scan error)" : label;
    }
}
