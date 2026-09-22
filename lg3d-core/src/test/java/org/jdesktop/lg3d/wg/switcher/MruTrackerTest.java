/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.wg.switcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link MruTracker}'s ordering rules: most-recently-used first,
 * reconciliation against the windows actually present, and pruning of closed
 * ones. Pure logic; runs headless.
 */
class MruTrackerTest {

    @Test
    @DisplayName("touch puts the most recent item first")
    void touchOrdersMostRecentFirst() {
        MruTracker<String> mru = new MruTracker<>();
        mru.touch("a");
        mru.touch("b");
        mru.touch("c");
        assertEquals(Arrays.asList("c", "b", "a"),
                mru.ordered(Arrays.asList("a", "b", "c")));
    }

    @Test
    @DisplayName("re-touching an item moves it back to the front")
    void retouchPromotes() {
        MruTracker<String> mru = new MruTracker<>();
        mru.touch("a");
        mru.touch("b");
        mru.touch("a");
        assertEquals(Arrays.asList("a", "b"),
                mru.ordered(Arrays.asList("a", "b")));
    }

    @Test
    @DisplayName("untracked present items are appended in the order given")
    void untrackedAppendedInOrder() {
        MruTracker<String> mru = new MruTracker<>();
        mru.touch("b");
        // "a" and "c" were never tracked; they follow the tracked "b".
        assertEquals(Arrays.asList("b", "a", "c"),
                mru.ordered(Arrays.asList("a", "b", "c")));
    }

    @Test
    @DisplayName("tracked items that are no longer present are dropped")
    void closedItemsPruned() {
        MruTracker<String> mru = new MruTracker<>();
        mru.touch("a");
        mru.touch("b");
        // "a" closed: only "b" is present.
        List<String> ordered = mru.ordered(Arrays.asList("b", "c"));
        assertEquals(Arrays.asList("b", "c"), ordered);
        // The pruned "a" must not resurface on the next reconciliation.
        mru.touch("c");
        assertEquals(Arrays.asList("c", "b"), mru.ordered(Arrays.asList("b", "c")));
    }

    @Test
    @DisplayName("remove and clear forget entries; touch(null) is ignored")
    void removeClearAndNull() {
        MruTracker<String> mru = new MruTracker<>();
        mru.touch(null);
        assertEquals(Arrays.asList("a"), mru.ordered(Arrays.asList("a")),
                "touch(null) is ignored and does not corrupt the order");
        mru.touch("b");
        mru.touch("a");
        assertEquals(Arrays.asList("a", "b"), mru.ordered(Arrays.asList("a", "b")),
                "a was touched last, so it leads");
        mru.remove("a");
        assertEquals(Arrays.asList("b", "a"), mru.ordered(Arrays.asList("a", "b")),
                "a removed item is no longer promoted ahead of tracked ones");
        mru.clear();
        // After clear nothing is tracked, so present order is returned as-is.
        assertEquals(Arrays.asList("a", "b"), mru.ordered(Arrays.asList("a", "b")));
    }

    @Test
    @DisplayName("a null present collection yields an empty order")
    void nullPresent() {
        MruTracker<String> mru = new MruTracker<>();
        mru.touch("a");
        assertTrue(mru.ordered(null).isEmpty());
    }

    @Test
    @DisplayName("duplicates in the present set are not repeated")
    void noDuplicates() {
        MruTracker<String> mru = new MruTracker<>();
        mru.touch("a");
        List<String> present = new ArrayList<>(Arrays.asList("a", "a", "b"));
        assertEquals(Arrays.asList("a", "b"), mru.ordered(present));
    }
}
