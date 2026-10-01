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
package org.jdesktop.lg3d.apps.passwordmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the in-memory model: {@link PasswordVault} (add / remove / replace /
 * filter / categories and its JSON round-trip, which is what {@link VaultEnvelope}
 * seals) and {@link PasswordEntry} (bean normalisation, the secret-blind
 * {@code matches} filter and the display {@code toString}). Pure and headless.
 */
class PasswordVaultTest {

    private static PasswordEntry entry(String title, String user, String category) {
        PasswordEntry e = new PasswordEntry(title, user, "pw");
        e.setCategory(category);
        return e;
    }

    @Test
    @DisplayName("a fresh vault is empty")
    void emptyVault() {
        PasswordVault v = new PasswordVault();
        assertTrue(v.isEmpty());
        assertEquals(0, v.size());
        assertTrue(v.entries().isEmpty());
        assertTrue(v.categories().isEmpty());
        assertTrue(v.find("anything").isEmpty());
    }

    @Test
    @DisplayName("add / get / replace / remove honour bounds and nulls")
    void addRemoveReplaceGet() {
        PasswordVault v = new PasswordVault();
        PasswordEntry a = entry("A", "ua", "Work");
        v.add(a);
        v.add(null);
        assertEquals(1, v.size(), "a null entry is ignored");
        assertSame(a, v.get(0));
        assertNull(v.get(5));
        assertNull(v.get(-1));

        PasswordEntry b = entry("B", "ub", "Home");
        assertTrue(v.replace(0, b));
        assertSame(b, v.get(0));
        assertFalse(v.replace(9, a), "out of range");
        assertFalse(v.replace(0, null), "a null replacement is refused");

        assertSame(b, v.remove(0));
        assertNull(v.remove(0), "removing from an empty vault is null");
        assertTrue(v.isEmpty());
    }

    @Test
    @DisplayName("the list constructor copies and skips nulls")
    void constructorCopies() {
        List<PasswordEntry> init = new ArrayList<>();
        init.add(entry("X", "ux", "Cat"));
        init.add(null);
        assertEquals(1, new PasswordVault(init).size());
        assertTrue(new PasswordVault(null).isEmpty());
    }

    @Test
    @DisplayName("entries() is an immutable snapshot")
    void entriesIsImmutable() {
        PasswordVault v = new PasswordVault();
        v.add(entry("A", "u", "C"));
        List<PasswordEntry> snapshot = v.entries();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(entry("B", "u", "C")));
    }

    @Test
    @DisplayName("find filters by title / username / url / category")
    void findFilters() {
        PasswordVault v = new PasswordVault();
        v.add(entry("GitHub", "octocat", "Dev"));
        v.add(entry("Bank", "me", "Finance"));
        assertEquals(2, v.find("").size());
        assertEquals(2, v.find(null).size());
        assertEquals(1, v.find("git").size());
        assertEquals(1, v.find("Finance").size());
        assertEquals(0, v.find("zzz").size());
    }

    @Test
    @DisplayName("categories are distinct, non-blank and first-seen ordered")
    void categoriesAreDistinct() {
        PasswordVault v = new PasswordVault();
        v.add(entry("A", "u", "Work"));
        v.add(entry("B", "u", "Home"));
        v.add(entry("C", "u", "Work"));
        v.add(entry("D", "u", ""));
        assertEquals(List.of("Work", "Home"), v.categories());
    }

    @Test
    @DisplayName("a vault survives a JSON round-trip with every field intact")
    void jsonRoundTrip() throws IOException {
        PasswordVault v = new PasswordVault();
        PasswordEntry e = entry("GitHub", "octocat", "Dev");
        e.setUrl("https://github.com");
        e.setNotes("2FA on");
        v.add(e);

        PasswordVault back = PasswordVault.fromJsonBytes(v.toJsonBytes());
        assertEquals(1, back.size());
        PasswordEntry b = back.get(0);
        assertEquals("GitHub", b.getTitle());
        assertEquals("octocat", b.getUsername());
        assertEquals("pw", b.getPassword());
        assertEquals("https://github.com", b.getUrl());
        assertEquals("2FA on", b.getNotes());
        assertEquals("Dev", b.getCategory());
    }

    @Test
    @DisplayName("null / empty JSON yields an empty vault, never a throw")
    void fromJsonHandlesEmpty() throws IOException {
        assertTrue(PasswordVault.fromJsonBytes(null).isEmpty());
        assertTrue(PasswordVault.fromJsonBytes(new byte[0]).isEmpty());
        assertTrue(PasswordVault.fromJson(null).isEmpty());
        assertTrue(PasswordVault.fromJson("").isEmpty());
    }

    @Test
    @DisplayName("entry setters normalise null to empty and trim (except secrets)")
    void entryNormalization() {
        PasswordEntry e = new PasswordEntry();
        e.setTitle(null);
        assertEquals("", e.getTitle());
        e.setTitle("  Padded  ");
        assertEquals("Padded", e.getTitle());
        e.setUsername(null);
        assertEquals("", e.getUsername());
        e.setUrl(null);
        assertEquals("", e.getUrl());
        e.setCategory(null);
        assertEquals("", e.getCategory());
        e.setNotes(null);
        assertEquals("", e.getNotes());
        e.setPassword(null);
        assertEquals("", e.getPassword());
    }

    @Test
    @DisplayName("matches searches everything but the secret")
    void entryMatchesIgnoresSecret() {
        PasswordEntry e = new PasswordEntry("My Bank", "alice", "s3cr3t");
        e.setUrl("https://bank.example.com");
        e.setCategory("Finance");
        assertTrue(e.matches(null));
        assertTrue(e.matches("   "));
        assertTrue(e.matches("bank"));
        assertTrue(e.matches("ALICE"));
        assertTrue(e.matches("example.com"));
        assertTrue(e.matches("finance"));
        assertFalse(e.matches("s3cr3t"), "the password is never searched");
    }

    @Test
    @DisplayName("toString shows the title (or a placeholder) and touch restamps")
    void entryToStringAndTouch() throws InterruptedException {
        assertEquals("(untitled)", new PasswordEntry().toString());
        assertEquals("Title", new PasswordEntry("Title", "u", "p").toString());
        PasswordEntry e = new PasswordEntry("T", "u", "p");
        assertTrue(e.getCreatedMillis() > 0);
        long before = e.getModifiedMillis();
        Thread.sleep(2);
        e.touch();
        assertTrue(e.getModifiedMillis() >= before);
    }
}
