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

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * A single search result: the matched node plus the metadata the UI shows and
 * the relevance {@code score} used to rank it. Matches are emitted by
 * {@link SearchEngine} as they are discovered (streaming), so a consumer sees
 * results live rather than after the whole walk finishes.
 *
 * <p>The {@code score} is a heuristic &mdash; higher is more relevant &mdash;
 * combining name-match quality (exact / prefix / substring), path shallowness,
 * recency and the number of content hits. It is advisory only: consumers may
 * sort by it, or by name/size/date instead.</p>
 *
 * @param path               the absolute path of the match
 * @param name               the file name (last path segment)
 * @param directory          true for a folder, false for a regular file
 * @param size               size in bytes (0 for directories)
 * @param lastModifiedMillis last-modified time in epoch millis (0 if unknown)
 * @param score              the relevance score, higher is better
 * @param hits               content hits (empty when contents were not searched)
 */
public record SearchMatch(Path path,
                          String name,
                          boolean directory,
                          long size,
                          long lastModifiedMillis,
                          int score,
                          List<ContentHit> hits) {

    /** Normalises nulls so a match is always safe to read. */
    public SearchMatch {
        name = (name == null) ? "" : name;
        hits = (hits == null) ? Collections.emptyList() : List.copyOf(hits);
    }

    /** True when this match carries at least one content hit. */
    public boolean hasContentHits() {
        return !hits.isEmpty();
    }
}
