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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;

/**
 * Pure type-to-search matcher behind the 2D start menu's search field.
 *
 * <p>Given the flat list of every configured application
 * ({@link Desktop2DMenuConfig.MenuModel#getItems()}) and a query, it returns the
 * matching entries best-first. Ranking is deliberately simple and predictable:
 * an exact name match beats a name prefix, which beats a match on a word
 * boundary ("man" in "File Manager"), which beats a plain name substring, which
 * beats a match only in the description or command. Ties keep descriptor order
 * (the sort is stable). Results are capped at {@link #MAX_RESULTS} so the popup
 * stays a sane height.</p>
 *
 * <p>This class does no I/O and touches no Swing, so it is unit-testable
 * headless; the popup wiring lives in {@link StartMenuSearch}.</p>
 */
final class AppSearch {

    /** Cap on the number of results returned, so the popup stays short. */
    static final int MAX_RESULTS = 12;

    // Rank tiers; a higher score wins. Ties preserve the descriptor order
    // because List.sort is stable and the comparator returns 0 for equal ranks.
    private static final int EXACT = 5;
    private static final int PREFIX = 4;
    private static final int WORD = 3;
    private static final int NAME = 2;
    private static final int DETAIL = 1;

    private AppSearch() {
        // no instances
    }

    /** One candidate plus its rank, so scoring happens exactly once per item. */
    private record Hit(ItemSpec item, int score) {
    }

    /**
     * Returns the entries in {@code all} matching {@code query}, best-first and
     * capped at {@link #MAX_RESULTS}. A {@code null}/blank query (or a
     * {@code null} list) yields an empty result, which the menu renders as its
     * normal category tree rather than a search.
     */
    static List<ItemSpec> match(List<ItemSpec> all, String query) {
        if (all == null) {
            return List.of();
        }
        String q = (query == null) ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (ItemSpec item : all) {
            int score = score(item, q);
            if (score > 0) {
                hits.add(new Hit(item, score));
            }
        }
        hits.sort((a, b) -> Integer.compare(b.score(), a.score()));
        int n = Math.min(hits.size(), MAX_RESULTS);
        List<ItemSpec> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(hits.get(i).item());
        }
        return out;
    }

    /** Rank of {@code item} against the already-normalised query {@code q}. */
    private static int score(ItemSpec item, String q) {
        if (item == null) {
            return 0;
        }
        String name = lower(item.getName());
        if (name.equals(q)) {
            return EXACT;
        }
        if (name.startsWith(q)) {
            return PREFIX;
        }
        int idx = name.indexOf(q);
        if (idx > 0 && isWordBoundary(name, idx)) {
            return WORD;
        }
        if (idx >= 0) {
            return NAME;
        }
        if (lower(item.getDesc()).contains(q) || lower(item.getCommand()).contains(q)) {
            return DETAIL;
        }
        return 0;
    }

    private static String lower(String s) {
        return (s == null) ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** True when the character before {@code idx} is not part of a word. */
    private static boolean isWordBoundary(String s, int idx) {
        return !Character.isLetterOrDigit(s.charAt(idx - 1));
    }
}
