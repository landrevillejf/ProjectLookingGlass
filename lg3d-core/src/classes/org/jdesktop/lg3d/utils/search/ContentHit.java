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
package org.jdesktop.lg3d.utils.search;

/**
 * One line inside a file that matched a content search: the 1-based
 * {@code line} number and a short, single-line {@code snippet} of the matching
 * text (already trimmed and length-capped so it is safe to show in a results
 * table). A record so matches carry their evidence immutably.
 *
 * @param line    the 1-based line number of the hit
 * @param snippet a trimmed, capped preview of the matching line
 */
public record ContentHit(int line, String snippet) {

    /** Builds a hit, coercing a null snippet to the empty string. */
    public ContentHit {
        snippet = (snippet == null) ? "" : snippet;
    }
}
