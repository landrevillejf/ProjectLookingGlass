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

import java.util.List;

/**
 * The pure, AWT-free decision behind the inline completion popup: given the
 * document text, the caret offset and the candidate list an extension just
 * published, should the popup appear, over which identifier run, and where the
 * run starts (so the popup anchors to the caret and can auto-hide when the caret
 * leaves the run).
 *
 * <p>Factored out of {@link CompletionPopup} so the show / hide and candidate
 * routing rules are unit-testable headless, exactly like the rest of the
 * editor's engine, while the Swing layer only renders a {@link Decision}.</p>
 */
final class CompletionTrigger {

    private CompletionTrigger() {
    }

    /** Why the popup is being asked to appear this round. */
    enum Reason {
        /** The user is typing an identifier / after a dot. */
        TYPING,
        /** The user pressed the force shortcut (Ctrl+Space). */
        FORCE
    }

    /**
     * The outcome of evaluating a publish.
     *
     * @param show     whether the popup should be visible
     * @param prefix   the identifier run immediately before the caret (never null)
     * @param anchor   the 0-based offset where {@code prefix} starts (the run the
     *                 popup replaces, and the caret window it auto-hides outside)
     */
    record Decision(boolean show, String prefix, int anchor) { }

    /**
     * Decides whether to show the popup for a debounced publish.
     *
     * @param text       the full document text (may be null/empty)
     * @param caret      the 0-based caret offset
     * @param candidates the published candidates (may be null/empty)
     * @param force      true for a Ctrl+Space force-show (shows even with no prefix)
     * @return the decision; never null, {@code show} false when there is nothing
     *         to offer or the caret is not on an identifier / after a dot
     */
    static Decision evaluate(String text, int caret, List<String> candidates, boolean force) {
        if (candidates == null || candidates.isEmpty()
                || text == null || text.isEmpty() || caret < 0 || caret > text.length()) {
            return new Decision(false, "", Math.max(0, caret));
        }
        String prefix = prefixAt(text, caret);
        int anchor = caret - prefix.length();
        boolean afterDot = anchor > 0 && text.charAt(anchor - 1) == '.';
        boolean show = force || !prefix.isEmpty() || afterDot;
        return new Decision(show, prefix, anchor);
    }

    /**
     * @return true when the caret has moved off the identifier run the popup is
     *         completing (past its end or before its start), so the popup should
     *         auto-hide. A popup with an empty prefix hides on any movement.
     */
    static boolean caretLeftRun(String text, int caret, int anchor, String prefix) {
        if (text == null || caret < 0 || caret > text.length()) {
            return true;
        }
        if (prefix == null || prefix.isEmpty()) {
            return caret != anchor;
        }
        return caret < anchor || caret > anchor + prefix.length();
    }

    /** @return the identifier run immediately before {@code caret}. */
    static String prefixAt(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int start = Math.min(caret, text.length());
        int i = start;
        while (i > 0 && isWordChar(text.charAt(i - 1))) {
            i--;
        }
        return text.substring(i, start);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
