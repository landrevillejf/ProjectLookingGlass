/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure seams of {@link LocaleStatus}: the {@code localectl status}
 * LANG parsing, the locale-list parsing and the {@code set-locale} command
 * builder. The {@code localectl} probes themselves are host-dependent and are
 * exercised only through their graceful-degradation contract, not asserted here.
 */
class LocaleStatusTest {

    @Test
    @DisplayName("the LANG value is read from the System Locale line")
    void parsesLang() {
        assertEquals("en_US.UTF-8", LocaleStatus.parseLang(
                "   System Locale: LANG=en_US.UTF-8\n"
              + "       VC Keymap: us\n"
              + "      X11 Layout: us\n"));
    }

    @Test
    @DisplayName("extra LC_* assignments after LANG do not confuse the parse")
    void parsesLangWithExtraAssignments() {
        assertEquals("fr_FR.UTF-8", LocaleStatus.parseLang(
                "System Locale: LANG=fr_FR.UTF-8 LC_TIME=fr_FR.UTF-8\n"));
    }

    @Test
    @DisplayName("an unset (n/a) or absent system locale yields empty")
    void parsesMissingLang() {
        assertEquals("", LocaleStatus.parseLang("System Locale: n/a\n"));
        assertEquals("", LocaleStatus.parseLang("VC Keymap: us\n"), "no System Locale line");
        assertEquals("", LocaleStatus.parseLang(null));
    }

    @Test
    @DisplayName("list-locales output yields one locale per line, skipping blanks")
    void parsesLocales() {
        List<String> locales = LocaleStatus.parseLocales(
                "C.UTF-8\nen_US.UTF-8\n\n   \nfr_FR.UTF-8\n");
        assertEquals(3, locales.size());
        assertEquals("C.UTF-8", locales.get(0));
        assertEquals("fr_FR.UTF-8", locales.get(2));
        assertTrue(LocaleStatus.parseLocales(null).isEmpty());
        assertTrue(LocaleStatus.parseLocales("").isEmpty());
    }

    @Test
    @DisplayName("the set-locale command assigns LANG")
    void commandBuilder() {
        assertArrayEquals(new String[] {"localectl", "set-locale", "LANG=en_US.UTF-8"},
                LocaleStatus.setLocaleCommand("en_US.UTF-8"));
    }
}
