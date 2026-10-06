/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link X11WindowAssociator#matches}, the pure
 * window-association rule clause extracted from
 * {@code WindowAssociationRuleEntry.getTargetWindow}.
 *
 * <p>The associator itself cannot be built in a test JVM — its constructor
 * registers an {@code LgEventConnector} listener and reads preferences, and its
 * rules are keyed on an {@code X11Client} (a {@code gnu.x11.Window} that needs a
 * live {@code Display}). Extracting the cls/name/title match into a static seam
 * makes the actual decision logic testable with plain Strings. Both the
 * sub-window half and the focused-window half of a rule delegate here, so this
 * covers both.
 *
 * <p>A {@code null} rule criterion is a wildcard (ignored); a non-null one must
 * equal the candidate exactly. A non-null title pattern must
 * {@link java.util.regex.Matcher#matches() fully match} the candidate title (not
 * merely be found within it).
 */
class X11WindowAssociatorMatcherTest {

    private static Pattern pat(String regex) {
        return regex == null ? null : Pattern.compile(regex);
    }

    @Test
    void allNullCriteriaMatchAnything() {
        assertTrue(X11WindowAssociator.matches(null, null, null, "xterm", "XTerm", "bash"));
        assertTrue(X11WindowAssociator.matches(null, null, null, null, null, null));
    }

    @Test
    void classCriterionMustEqualExactly() {
        assertTrue(X11WindowAssociator.matches("xterm", null, null, "xterm", "XTerm", "t"));
        assertFalse(X11WindowAssociator.matches("xterm", null, null, "emacs", "XTerm", "t"));
        // Case-sensitive: WM_CLASS res_class is matched with equals(), not equalsIgnoreCase().
        assertFalse(X11WindowAssociator.matches("xterm", null, null, "XTerm", "XTerm", "t"));
    }

    @Test
    void nameCriterionMustEqualExactly() {
        assertTrue(X11WindowAssociator.matches(null, "XTerm", null, "xterm", "XTerm", "t"));
        assertFalse(X11WindowAssociator.matches(null, "XTerm", null, "xterm", "xterm", "t"));
    }

    @Test
    void nonNullCriterionDoesNotMatchNullCandidateValue() {
        // A window with no WM_CLASS resolves cls/name to null; a rule that
        // requires one must not match.
        assertFalse(X11WindowAssociator.matches("xterm", null, null, null, null, "t"));
        assertFalse(X11WindowAssociator.matches(null, "XTerm", null, null, null, "t"));
    }

    @Test
    void titlePatternMustFullyMatchNotJustBeFound() {
        // matches() anchors the whole title, so a bare substring is not enough.
        assertFalse(X11WindowAssociator.matches(null, null, pat("bash"), "xterm", "XTerm", "user@host: bash"));
        assertTrue(X11WindowAssociator.matches(null, null, pat("bash"), "xterm", "XTerm", "bash"));
        assertTrue(X11WindowAssociator.matches(null, null, pat(".*bash.*"), "xterm", "XTerm", "user@host: bash"));
    }

    @Test
    void titlePatternSupportsRegexAlternationAndAnchors() {
        Pattern p = pat("(bash|zsh) - .*");
        assertTrue(X11WindowAssociator.matches(null, null, p, "xterm", "XTerm", "bash - login"));
        assertTrue(X11WindowAssociator.matches(null, null, p, "xterm", "XTerm", "zsh - login"));
        assertFalse(X11WindowAssociator.matches(null, null, p, "xterm", "XTerm", "fish - login"));
    }

    @Test
    void allCriteriaMustMatchTogether() {
        assertTrue(X11WindowAssociator.matches(
                "xterm", "XTerm", pat("bash"), "xterm", "XTerm", "bash"));
        // One mismatched criterion vetoes the whole clause.
        assertFalse(X11WindowAssociator.matches(
                "xterm", "XTerm", pat("bash"), "xterm", "XTerm", "zsh"));
        assertFalse(X11WindowAssociator.matches(
                "xterm", "XTerm", pat("bash"), "xterm", "other", "bash"));
        assertFalse(X11WindowAssociator.matches(
                "xterm", "XTerm", pat("bash"), "other", "XTerm", "bash"));
    }

    @Test
    void emptyStringsAreMatchedLiterally() {
        // An empty rule string is a real (non-null) criterion, not a wildcard.
        assertTrue(X11WindowAssociator.matches("", null, null, "", null, null));
        assertFalse(X11WindowAssociator.matches("", null, null, "xterm", null, null));
        assertTrue(X11WindowAssociator.matches(null, null, pat(""), "x", "y", ""));
    }
}
