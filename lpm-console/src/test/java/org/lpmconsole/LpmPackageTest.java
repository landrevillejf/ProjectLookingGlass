package org.lpmconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link LpmPackage}, the read-only value object describing one LPM
 * package: field normalisation (nulls become empty strings), the derived
 * human status string, the {@code asUpgradable} copy and {@code toString}.
 */
class LpmPackageTest {

    @Test
    @DisplayName("the constructor keeps every supplied field")
    void storesFields() {
        LpmPackage p = new LpmPackage("firefox", "1.2.3", "A browser",
                "gtk,dbus", "abc123", true, false);
        assertEquals("firefox", p.getName());
        assertEquals("1.2.3", p.getVersion());
        assertEquals("A browser", p.getDescription());
        assertEquals("gtk,dbus", p.getDeps());
        assertEquals("abc123", p.getChecksum());
        assertTrue(p.isInstalled());
        assertFalse(p.isHeld());
    }

    @Test
    @DisplayName("null string fields are normalised to empty strings")
    void normalisesNulls() {
        LpmPackage p = new LpmPackage(null, null, null, null, null, false, false);
        assertEquals("", p.getName());
        assertEquals("", p.getVersion());
        assertEquals("", p.getDescription());
        assertEquals("", p.getDeps());
        assertEquals("", p.getChecksum());
    }

    @Test
    @DisplayName("getStatus reflects the installed / held combination")
    void statusMatrix() {
        assertEquals("installed",
                new LpmPackage("a", "", "", "", "", true, false).getStatus());
        assertEquals("available",
                new LpmPackage("a", "", "", "", "", false, false).getStatus());
        assertEquals("held",
                new LpmPackage("a", "", "", "", "", true, true).getStatus());
        assertEquals("held (not installed)",
                new LpmPackage("a", "", "", "", "", false, true).getStatus());
    }

    @Test
    @DisplayName("asUpgradable returns an equal but distinct copy")
    void asUpgradableCopiesFields() {
        LpmPackage p = new LpmPackage("vim", "9.1", "Editor", "ncurses",
                "deadbeef", true, false);
        LpmPackage copy = p.asUpgradable();
        assertNotSame(p, copy);
        assertEquals(p.getName(), copy.getName());
        assertEquals(p.getVersion(), copy.getVersion());
        assertEquals(p.getDescription(), copy.getDescription());
        assertEquals(p.getDeps(), copy.getDeps());
        assertEquals(p.getChecksum(), copy.getChecksum());
        assertEquals(p.isInstalled(), copy.isInstalled());
        assertEquals(p.isHeld(), copy.isHeld());
    }

    @Test
    @DisplayName("toString shows name and version, or name alone when empty")
    void toStringFormat() {
        assertEquals("firefox 1.2.3",
                new LpmPackage("firefox", "1.2.3", "", "", "", false, false)
                        .toString());
        assertEquals("firefox",
                new LpmPackage("firefox", "", "", "", "", false, false)
                        .toString());
    }
}
