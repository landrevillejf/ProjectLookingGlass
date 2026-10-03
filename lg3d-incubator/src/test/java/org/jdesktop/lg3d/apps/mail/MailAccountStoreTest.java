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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link MailAccountStore}: field round-trip through the
 * {@code /mail/accounts} node, the single-default invariant, default promotion on
 * delete, and the sealed-password accessors.
 */
class MailAccountStoreTest {

    private MailAccountStore store;

    @BeforeEach
    void setup() {
        remove("/mail");
        store = new MailAccountStore();
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

    private static MailAccount account(String id, String email) {
        MailAccount a = new MailAccount(id);
        a.setDisplayName("User " + id);
        a.setEmailAddress(email);
        a.setUsername("user-" + id);
        a.setImapHost("imap." + id);
        a.setImapPort(993);
        a.setImapSecurity(MailAccount.Security.STARTTLS);
        a.setSmtpHost("smtp." + id);
        a.setSmtpPort(587);
        a.setSmtpSecurity(MailAccount.Security.STARTTLS);
        a.setSignature("-- " + id);
        return a;
    }

    @Test
    void firstSavedAccountBecomesDefault() {
        store.save(account("a1", "a1@example.com"));
        MailAccount def = store.defaultAccount();
        assertNotNull(def);
        assertEquals("a1", def.getId());
    }

    @Test
    void roundTripsEveryField() {
        store.save(account("a1", "a1@example.com"));
        MailAccount loaded = store.findById("a1");
        assertNotNull(loaded);
        assertEquals("a1@example.com", loaded.getEmailAddress());
        assertEquals("user-a1", loaded.getUsername());
        assertEquals("imap.a1", loaded.getImapHost());
        assertEquals(993, loaded.getImapPort());
        assertEquals(MailAccount.Security.STARTTLS, loaded.getImapSecurity());
        assertEquals("smtp.a1", loaded.getSmtpHost());
        assertEquals(587, loaded.getSmtpPort());
        assertEquals("-- a1", loaded.getSignature());
    }

    @Test
    void savingANewDefaultClearsTheOldOne() {
        store.save(account("a1", "a1@example.com"));
        MailAccount second = account("a2", "a2@example.com");
        second.setDefaultAccount(true);
        store.save(second);

        List<MailAccount> all = store.load();
        int defaults = 0;
        for (MailAccount a : all) {
            if (a.isDefaultAccount()) {
                defaults++;
                assertEquals("a2", a.getId());
            }
        }
        assertEquals(1, defaults, "exactly one account must be default");
    }

    @Test
    void deletingTheDefaultPromotesAnother() {
        store.save(account("a1", "a1@example.com"));
        store.save(account("a2", "a2@example.com"));
        store.delete("a1");
        MailAccount def = store.defaultAccount();
        assertNotNull(def);
        assertEquals("a2", def.getId());
        assertNull(store.findById("a1"));
    }

    @Test
    void sealsAndRecoversPasswords() {
        store.save(account("a1", "a1@example.com"));
        assertFalse(store.hasSavedPassword("a1"));
        store.setPassword("a1", "pw-123");
        assertTrue(store.hasSavedPassword("a1"));
        assertEquals("pw-123", store.getPassword("a1"));
        // The plaintext is not stored verbatim in the node.
        assertFalse("pw-123".equals(
                Preferences.userRoot().node("/mail/accounts/a1")
                        .get("passwordSealed", "")));
    }

    @Test
    void preservesInsertionOrder() {
        store.save(account("a1", "a1@example.com"));
        store.save(account("a2", "a2@example.com"));
        store.save(account("a3", "a3@example.com"));
        List<MailAccount> all = store.load();
        assertEquals(3, all.size());
        assertEquals("a1", all.get(0).getId());
        assertEquals("a2", all.get(1).getId());
        assertEquals("a3", all.get(2).getId());
    }
}
