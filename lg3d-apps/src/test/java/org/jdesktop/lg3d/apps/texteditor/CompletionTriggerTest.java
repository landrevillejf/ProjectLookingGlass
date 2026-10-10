/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.CompletionTrigger.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure {@link CompletionTrigger} show / hide rules the
 * inline popup renders: identifier-run and after-dot detection, the Ctrl+Space
 * force path, empty / out-of-range guards and caret-left-run auto-hide. The
 * Swing {@link CompletionPopup} is separately smoke-constructed headless.
 */
class CompletionTriggerTest {

    private static final List<String> CANDS = List.of("counter", "continue");

    @Test
    @DisplayName("nothing to offer — empty candidates hide the popup")
    void emptyCandidates() {
        assertFalse(CompletionTrigger.evaluate("cou", 3, List.of(), false).show());
        assertFalse(CompletionTrigger.evaluate("cou", 3, null, false).show());
        assertFalse(CompletionTrigger.evaluate("", 0, CANDS, false).show());
    }

    @Test
    @DisplayName("an identifier run before the caret triggers a show")
    void typingTriggers() {
        Decision d = CompletionTrigger.evaluate("int cou", 7, CANDS, false);
        assertTrue(d.show());
        assertEquals("cou", d.prefix());
        assertEquals(4, d.anchor());
    }

    @Test
    @DisplayName("a bare dot after a receiver triggers a show with no prefix")
    void afterDotTriggers() {
        Decision d = CompletionTrigger.evaluate("cfg.", 4, CANDS, false);
        assertTrue(d.show(), "caret sits right after a dot");
        assertEquals("", d.prefix());
        assertEquals(4, d.anchor());
    }

    @Test
    @DisplayName("force shows with no prefix; a blank context without force does not")
    void forcePath() {
        assertTrue(CompletionTrigger.evaluate("int ", 4, CANDS, true).show());
        assertFalse(CompletionTrigger.evaluate("int ", 4, CANDS, false).show());
        // Candidates published for a number literal are empty, so the popup stays hidden.
        String s = "x = 1.5";
        assertFalse(CompletionTrigger.evaluate(s, s.indexOf('5') + 1, List.of(), false).show(),
                "nothing published -> hidden");
    }

    @Test
    @DisplayName("out-of-range caret is rejected rather than throwing")
    void rangeGuards() {
        assertFalse(CompletionTrigger.evaluate("abc", -1, CANDS, false).show());
        assertFalse(CompletionTrigger.evaluate("abc", 99, CANDS, false).show());
    }

    @Test
    @DisplayName("caretLeftRun hides only when the caret leaves the run window")
    void caretLeftRun() {
        // popup completing "cou" anchored at 4 (spans offsets 4..7)
        assertFalse(CompletionTrigger.caretLeftRun("int cou", 7, 4, "cou"));
        assertFalse(CompletionTrigger.caretLeftRun("int cou", 5, 4, "cou"), "inside run ok");
        assertTrue(CompletionTrigger.caretLeftRun("int coupled", 11, 4, "cou"), "past end");
        assertTrue(CompletionTrigger.caretLeftRun("cou", 0, 4, "cou"), "before start");
        // an empty-prefix (after-dot / force) popup hides on any movement
        assertFalse(CompletionTrigger.caretLeftRun("cfg.", 4, 4, ""));
        assertTrue(CompletionTrigger.caretLeftRun("cfg.x", 5, 4, ""));
        // out-of-range always hides
        assertTrue(CompletionTrigger.caretLeftRun("abc", 99, 0, "abc"));
    }

    @Test
    @DisplayName("prefixAt returns the trailing identifier run")
    void prefixExtraction() {
        assertEquals("cou", CompletionTrigger.prefixAt("int cou", 7));
        assertEquals("", CompletionTrigger.prefixAt("int ", 4));
        assertEquals("$x_1", CompletionTrigger.prefixAt("$x_1", 4));
        assertEquals("", CompletionTrigger.prefixAt(null, 0));
    }
}
