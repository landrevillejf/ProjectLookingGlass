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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Headless orchestration tests for {@link X11WindowAssociator}: the rule walk in
 * {@link X11WindowAssociator#getAssociatedWindow}, one-time rule retirement,
 * first-match-wins ordering, the focus guard, and
 * {@link X11WindowAssociator#removeAllRules}.
 *
 * <p>These drive the associator through the {@link WindowAssociationTarget}
 * seam with a lightweight {@link FakeWindow} and the no-wiring test constructor
 * {@code new X11WindowAssociator(false)} — so there is no
 * {@code LgEventConnector}, no preference load, and above all no
 * {@code X11Client} (which extends {@code gnu.x11.Window}, needs a live
 * {@code Display}, and cannot even be class-loaded headless without running its
 * own static {@code new X11WindowAssociator()}). The pure cls/name/title clause
 * the rules evaluate is covered separately by
 * {@link X11WindowAssociatorMatcherTest}; this class covers how the rules are
 * walked and retired around it.
 */
class X11WindowAssociatorOrchestrationTest {

    /** Minimal {@link WindowAssociationTarget} stand-in for an {@code X11Client}. */
    private static final class FakeWindow implements WindowAssociationTarget {
        private final String resClass;
        private final String resName;
        private final String title;

        FakeWindow(String resClass, String resName, String title) {
            this.resClass = resClass;
            this.resName = resName;
            this.title = title;
        }

        public String getName() {
            return title;
        }

        public String getResClass() {
            return resClass;
        }

        public String getResName() {
            return resName;
        }
    }

    /** An associator with no event-connector wiring and no preference rules. */
    private static X11WindowAssociator newAssociator() {
        return new X11WindowAssociator(false);
    }

    @Test
    void noFocusedWindowNeverAssociates() {
        X11WindowAssociator a = newAssociator();
        FakeWindow target = new FakeWindow("emacs", "Emacs", "doc");
        // A one-time rule that would match the candidate, but nothing is focused.
        a.addRule(target, "xterm", null, null);
        assertNull(a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void oneTimeRuleReturnsExplicitTargetAndIsRetired() {
        X11WindowAssociator a = newAssociator();
        FakeWindow target = new FakeWindow("emacs", "Emacs", "doc");
        a.setFocusedWindow(new FakeWindow("anything", "anything", "t"));
        a.addRule(target, "xterm", null, null);

        FakeWindow candidate = new FakeWindow("xterm", "XTerm", "bash");
        assertSame(target, a.getAssociatedWindow(candidate));
        // A one-time rule is removed once it fires, so a second lookup misses.
        assertNull(a.getAssociatedWindow(candidate));
    }

    @Test
    void oneTimeRuleIgnoresFocusedWindowAttributes() {
        // The explicit-target branch returns targetWindow after only the
        // sub-window clause is checked; the focused window's attributes are
        // irrelevant (it only needs to be non-null to pass the guard).
        X11WindowAssociator a = newAssociator();
        FakeWindow target = new FakeWindow("emacs", "Emacs", "doc");
        a.setFocusedWindow(new FakeWindow("zzz", "zzz", "zzz"));
        a.addRule(target, "xterm", null, null);
        assertSame(target, a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void patternRuleReturnsFocusedWindowWhenBothClausesMatch() {
        X11WindowAssociator a = newAssociator();
        FakeWindow focused = new FakeWindow("emacs", "Emacs", "edit.txt");
        a.setFocusedWindow(focused);
        // target clause = emacs, sub-window clause = xterm
        a.addRule("emacs", null, null, "xterm", null, null);
        assertSame(focused, a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void patternRulePersistsAcrossLookups() {
        // A pattern rule (no explicit target) is not one-time, so it keeps firing.
        X11WindowAssociator a = newAssociator();
        FakeWindow focused = new FakeWindow("emacs", "Emacs", "edit.txt");
        a.setFocusedWindow(focused);
        a.addRule("emacs", null, null, "xterm", null, null);
        FakeWindow candidate = new FakeWindow("xterm", "XTerm", "bash");
        assertSame(focused, a.getAssociatedWindow(candidate));
        assertSame(focused, a.getAssociatedWindow(candidate));
    }

    @Test
    void subWindowClauseGatesTheWholeRule() {
        X11WindowAssociator a = newAssociator();
        FakeWindow focused = new FakeWindow("emacs", "Emacs", "edit.txt");
        a.setFocusedWindow(focused);
        a.addRule("emacs", null, null, "xterm", null, null);
        // The candidate fails the sub-window clause, so there is no association
        // even though the focused window matches the target clause.
        assertNull(a.getAssociatedWindow(new FakeWindow("firefox", "Firefox", "web")));
    }

    @Test
    void targetClauseGatesAPatternRule() {
        X11WindowAssociator a = newAssociator();
        a.setFocusedWindow(new FakeWindow("vim", "Vim", "code"));
        a.addRule("emacs", null, null, "xterm", null, null);
        // Candidate matches the sub clause, but focus does not match the target.
        assertNull(a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void firstMatchingRuleWins() {
        X11WindowAssociator a = newAssociator();
        FakeWindow focused = new FakeWindow("emacs", "Emacs", "edit.txt");
        a.setFocusedWindow(focused);
        FakeWindow firstTarget = new FakeWindow("one", "one", "1");
        // Both rules would match candidate "xterm"; the first (one-time) wins.
        a.addRule(firstTarget, "xterm", null, null);
        a.addRule("emacs", null, null, "xterm", null, null);
        assertSame(firstTarget, a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void noRulesNeverAssociates() {
        X11WindowAssociator a = newAssociator();
        a.setFocusedWindow(new FakeWindow("emacs", "Emacs", "e"));
        assertNull(a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void removeAllRulesDropsOneTimeRulesForThatWindow() {
        X11WindowAssociator a = newAssociator();
        FakeWindow target = new FakeWindow("emacs", "Emacs", "doc");
        a.setFocusedWindow(new FakeWindow("any", "any", "t"));
        a.addRule(target, "xterm", null, null);
        a.removeAllRules(target);
        // The rule targeting `target` is gone, so the candidate no longer matches.
        assertNull(a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void removeAllRulesClearsFocusWhenThatWindowWasFocused() {
        X11WindowAssociator a = newAssociator();
        FakeWindow focused = new FakeWindow("emacs", "Emacs", "doc");
        a.setFocusedWindow(focused);
        a.addRule("emacs", null, null, "xterm", null, null);
        a.removeAllRules(focused);
        // Focus is cleared, so the guard short-circuits even though the
        // (pattern) rule itself is not a target of `focused` and remains.
        assertNull(a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void removeAllRulesKeepsOtherWindowsRules() {
        X11WindowAssociator a = newAssociator();
        FakeWindow focused = new FakeWindow("emacs", "Emacs", "doc");
        a.setFocusedWindow(focused);
        a.addRule("emacs", null, null, "xterm", null, null);
        a.removeAllRules(new FakeWindow("other", "other", "o"));
        // An unrelated removal leaves the rule and the focus intact.
        assertSame(focused, a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void removeAllRulesNullIsNoOp() {
        X11WindowAssociator a = newAssociator();
        FakeWindow focused = new FakeWindow("emacs", "Emacs", "doc");
        a.setFocusedWindow(focused);
        a.addRule("emacs", null, null, "xterm", null, null);
        a.removeAllRules(null);
        assertSame(focused, a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }

    @Test
    void focusedWindowWithoutClassHintIsHandledGracefully() {
        // Hardening: the focused-window half used to dereference classHint
        // unconditionally (an NPE when a focused window had no WM_CLASS). Routing
        // the reads through getResClass()/getResName() makes it null-safe, so a
        // focus with no class hint simply fails a class/name criterion instead of
        // throwing -- and still matches an all-wildcard target clause.
        X11WindowAssociator a = newAssociator();
        FakeWindow focusedNoHint = new FakeWindow(null, null, "mystery");
        a.setFocusedWindow(focusedNoHint);
        a.addRule(null, null, null, "xterm", null, null);
        assertSame(focusedNoHint, a.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));

        // A target clause that requires a res_class does not match the null hint.
        X11WindowAssociator b = newAssociator();
        b.setFocusedWindow(new FakeWindow(null, null, "mystery"));
        b.addRule("emacs", null, null, "xterm", null, null);
        assertNull(b.getAssociatedWindow(new FakeWindow("xterm", "XTerm", "bash")));
    }
}
