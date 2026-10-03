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
package org.jdesktop.lg3d.apps.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link MailRule} matching (every field / match combination,
 * the disabled and malformed-regex cases) and for {@link MailRuleStore}
 * persistence and ordering.
 */
class MailRuleTest {

    @BeforeEach
    void clear() {
        remove("/mail");
    }

    @AfterEach
    void cleanup() {
        remove("/mail");
    }

    private static void remove(String path) {
        try {
            if (Preferences.userRoot().nodeExists(path)) {
                Preferences.userRoot().node(path).removeNode();
            }
        } catch (Exception e) {
            // best effort
        }
    }

    private static MailMessage message(String from, String subject, String to) {
        MailMessage m = new MailMessage();
        m.setFrom(MailAddress.parse(from));
        m.setSubject(subject);
        m.setTo(Collections.singletonList(MailAddress.parse(to)));
        return m;
    }

    private static MailRule rule(MailRule.Field field, MailRule.Match match,
            String value) {
        MailRule r = new MailRule(null);   // unique generated id
        r.setField(field);
        r.setMatch(match);
        r.setValue(value);
        return r;
    }

    @Test
    void containsMatchesCaseInsensitively() {
        MailRule r = rule(MailRule.Field.FROM, MailRule.Match.CONTAINS, "ALICE");
        assertTrue(r.matches(message("Alice <alice@example.com>", "hi", "me@x")));
        assertFalse(r.matches(message("Bob <bob@example.com>", "hi", "me@x")));
    }

    @Test
    void equalsMatchesWholeSubject() {
        MailRule r = rule(MailRule.Field.SUBJECT, MailRule.Match.EQUALS, "Report");
        assertTrue(r.matches(message("a@b", "report", "me@x")));
        assertFalse(r.matches(message("a@b", "Weekly Report", "me@x")));
    }

    @Test
    void regexMatchesAndSwallowsBadPatterns() {
        MailRule good = rule(MailRule.Field.SUBJECT, MailRule.Match.REGEX,
                "^invoice-\\d+$");
        assertTrue(good.matches(message("a@b", "invoice-42", "me@x")));
        assertFalse(good.matches(message("a@b", "receipt-42", "me@x")));

        MailRule bad = rule(MailRule.Field.SUBJECT, MailRule.Match.REGEX, "[unclosed");
        assertFalse(bad.matches(message("a@b", "anything", "me@x")),
                "a malformed regex must never match, not throw");
    }

    @Test
    void toFieldMatchesRecipients() {
        MailRule r = rule(MailRule.Field.TO, MailRule.Match.CONTAINS, "team@x");
        assertTrue(r.matches(message("a@b", "s", "Team <team@x>")));
        assertFalse(r.matches(message("a@b", "s", "other@y")));
    }

    @Test
    void disabledOrEmptyValueNeverMatches() {
        MailRule off = rule(MailRule.Field.FROM, MailRule.Match.CONTAINS, "alice");
        off.setEnabled(false);
        assertFalse(off.matches(message("alice@x", "s", "me@x")));

        MailRule empty = rule(MailRule.Field.FROM, MailRule.Match.CONTAINS, "");
        assertFalse(empty.matches(message("alice@x", "s", "me@x")));
    }

    @Test
    void describeSummarisesTheRule() {
        MailRule r = rule(MailRule.Field.FROM, MailRule.Match.CONTAINS, "boss");
        r.setAction(MailRule.Action.MOVE);
        r.setTargetFolder("Important");
        String d = r.describe();
        assertTrue(d.contains("FROM"), d);
        assertTrue(d.contains("MOVE"), d);
        assertTrue(d.contains("Important"), d);
    }

    @Test
    void storeRoundTripsRulesInOrder() {
        MailRuleStore store = new MailRuleStore();
        MailRule a = rule(MailRule.Field.FROM, MailRule.Match.CONTAINS, "a");
        MailRule b = rule(MailRule.Field.SUBJECT, MailRule.Match.EQUALS, "b");
        MailRule c = rule(MailRule.Field.TO, MailRule.Match.REGEX, "c.*");
        store.saveAll(Arrays.asList(a, b, c));

        List<MailRule> loaded = store.load();
        assertEquals(3, loaded.size());
        assertEquals(a.getId(), loaded.get(0).getId());
        assertEquals(b.getId(), loaded.get(1).getId());
        assertEquals(c.getId(), loaded.get(2).getId());
        assertEquals(MailRule.Field.SUBJECT, loaded.get(1).getField());
        assertEquals(MailRule.Match.REGEX, loaded.get(2).getMatch());
    }

    @Test
    void saveAllRemovesDroppedRules() {
        MailRuleStore store = new MailRuleStore();
        MailRule a = rule(MailRule.Field.FROM, MailRule.Match.CONTAINS, "a");
        MailRule b = rule(MailRule.Field.SUBJECT, MailRule.Match.EQUALS, "b");
        store.saveAll(Arrays.asList(a, b));
        store.saveAll(Collections.singletonList(a));

        List<MailRule> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals(a.getId(), loaded.get(0).getId());
    }

    @Test
    void clearEmptiesTheStore() {
        MailRuleStore store = new MailRuleStore();
        store.saveAll(Collections.singletonList(
                rule(MailRule.Field.FROM, MailRule.Match.CONTAINS, "a")));
        store.clear();
        assertTrue(store.load().isEmpty());
    }
}
