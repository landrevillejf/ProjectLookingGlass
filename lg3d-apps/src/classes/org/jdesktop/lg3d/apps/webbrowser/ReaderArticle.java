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
package org.jdesktop.lg3d.apps.webbrowser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An article extracted for reader mode: a title, an optional byline and the body
 * paragraphs, all as plain text. Immutable and free of AWT/JavaFX so the
 * extraction post-processing and rendering are unit-tested headlessly.
 */
public final class ReaderArticle {

    private final String title;
    private final String byline;
    private final List<String> paragraphs;

    /**
     * @param title      the article title (may be null/blank)
     * @param byline     the author / dateline (may be null/blank)
     * @param paragraphs the body paragraphs (may be null; blanks are dropped)
     */
    public ReaderArticle(String title, String byline, List<String> paragraphs) {
        this.title = (title == null) ? "" : title.trim();
        this.byline = (byline == null) ? "" : byline.trim();
        List<String> kept = new ArrayList<>();
        if (paragraphs != null) {
            for (String p : paragraphs) {
                if (p != null && !p.isBlank()) {
                    kept.add(p.trim());
                }
            }
        }
        this.paragraphs = Collections.unmodifiableList(kept);
    }

    /** @return the title, never null (may be empty). */
    public String getTitle() {
        return title;
    }

    /** @return the byline, never null (may be empty). */
    public String getByline() {
        return byline;
    }

    /** @return the non-blank body paragraphs, never null, unmodifiable. */
    public List<String> getParagraphs() {
        return paragraphs;
    }

    /** @return true when there is no readable body text. */
    public boolean isEmpty() {
        return paragraphs.isEmpty();
    }
}
