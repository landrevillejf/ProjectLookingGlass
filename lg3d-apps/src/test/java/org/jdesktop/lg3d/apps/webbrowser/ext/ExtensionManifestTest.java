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
package org.jdesktop.lg3d.apps.webbrowser.ext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the immutable {@link ExtensionManifest} value type. */
class ExtensionManifestTest {

    @Test
    @DisplayName("blank identity fields fall back to safe defaults")
    void normalizesBlanks() {
        ExtensionManifest m = new ExtensionManifest("  ", null, "", "desc", null, null);
        assertEquals("unknown", m.getId());
        assertEquals("unknown", m.getName(), "a blank name defaults to the id");
        assertEquals("0.0.0", m.getVersion());
        assertEquals("desc", m.getDescription());
        assertEquals("", m.getAuthor());
        assertTrue(m.getPermissions().isEmpty());
    }

    @Test
    @DisplayName("fields are trimmed and permissions are defensive-copied + immutable")
    void copiesAndFreezesPermissions() {
        Set<Permission> perms = EnumSet.of(Permission.NAVIGATE, Permission.POPUP);
        ExtensionManifest m = new ExtensionManifest(
                " id ", " Name ", " 1.2.3 ", " d ", " author ", perms);
        assertEquals("id", m.getId());
        assertEquals("Name", m.getName());
        assertEquals("1.2.3", m.getVersion());
        assertEquals("author", m.getAuthor());
        assertEquals(perms, m.getPermissions());

        perms.add(Permission.TOOLBAR);
        assertEquals(2, m.getPermissions().size(), "mutating the source must not leak in");
        assertThrows(UnsupportedOperationException.class,
                () -> m.getPermissions().add(Permission.SETTINGS));
    }

    @Test
    @DisplayName("equality is by id + version, ignoring the blurb")
    void equalsByIdAndVersion() {
        ExtensionManifest a = new ExtensionManifest("x", "A", "1.0", "one", null, null);
        ExtensionManifest b = new ExtensionManifest("x", "B", "1.0", "two", null, null);
        ExtensionManifest c = new ExtensionManifest("x", "A", "2.0", "one", null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
