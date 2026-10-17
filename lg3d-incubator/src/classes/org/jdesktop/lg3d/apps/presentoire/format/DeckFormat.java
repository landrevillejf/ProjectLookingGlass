/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.apps.presentoire.format;

import java.util.Locale;

/**
 * The slide-deck file formats Presentoire can open and save.
 *
 * <p>Presentoire's own {@code .zip} ({@link #NATIVE}) is the format both
 * renderers were built for; the other three let it exchange decks with the two
 * office suites a slide deck usually lives in: Microsoft PowerPoint's modern
 * {@code .pptx} ({@link #PPTX}) and legacy binary {@code .ppt} ({@link #PPT})
 * through Apache POI, and LibreOffice Impress's OpenDocument {@code .odp}
 * ({@link #ODP}) parsed in pure Java. Every format both imports and exports, so
 * a deck round-trips.</p>
 *
 * <p>{@link DeckCodecs} sniffs the real format from a file's magic bytes rather
 * than trusting this extension, because a mis-named file is common; the extension
 * here is only the fallback and the default a Save As dialog appends.</p>
 */
public enum DeckFormat {

    /** Presentoire's own ZIP + {@code content.xml} deck. */
    NATIVE("zip", "Presentoire deck (.zip)"),

    /** Microsoft PowerPoint (Office Open XML), read and written by POI XSLF. */
    PPTX("pptx", "PowerPoint (.pptx)"),

    /** Microsoft PowerPoint 97-2003 binary, read and written by POI HSLF. */
    PPT("ppt", "PowerPoint 97-2003 (.ppt)"),

    /** LibreOffice / OpenOffice Impress (OpenDocument), pure Java. */
    ODP("odp", "OpenDocument presentation (.odp)");

    private final String extension;
    private final String description;

    DeckFormat(String extension, String description) {
        this.extension = extension;
        this.description = description;
    }

    /** The lower-case file extension, with no leading dot (e.g. {@code pptx}). */
    public String extension() {
        return extension;
    }

    /** A human-readable name for a file filter or the panel's status line. */
    public String description() {
        return description;
    }

    /** Whether Presentoire can open this format. True for every format. */
    public boolean canImport() {
        return true;
    }

    /** Whether Presentoire can save this format. True for every format. */
    public boolean canExport() {
        return true;
    }

    /**
     * Resolves a format from a file name's extension, or null when the extension
     * is unknown. This is only a fallback for {@link DeckCodecs}, which sniffs
     * the real content first.
     *
     * @param fileName a file name or path; only the suffix after the last dot is
     *        read
     * @return the matching format, or null if the extension names none
     */
    public static DeckFormat fromExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (DeckFormat format : values()) {
            if (format.extension.equals(ext)) {
                return format;
            }
        }
        return null;
    }
}
