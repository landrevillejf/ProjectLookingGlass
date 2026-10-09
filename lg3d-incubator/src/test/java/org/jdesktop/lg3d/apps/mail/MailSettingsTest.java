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

import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link MailSettings}: the first-run defaults, the persistence
 * round trip through {@code /mail/settings}, and the value clamping that keeps the
 * UI sane (font sizes, auto-check interval) and secure (HTML off by default).
 */
class MailSettingsTest {

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

    @Test
    void firstRunUsesSecureDefaults() {
        MailSettings s = MailSettings.load();
        assertFalse(s.isRenderHtml(), "HTML rendering must default off");
        assertTrue(s.isConfirmOnDelete());
        assertTrue(s.isSortDescending());
        assertTrue(s.isNotifyOnNewMail(), "new-mail toasts default on");
        assertEquals(MailSettings.Theme.LIGHT, s.getTheme());
        assertEquals(MailSettings.ReadingPanePosition.RIGHT,
                s.getReadingPanePosition());
        assertEquals(MailSettings.SortColumn.DATE, s.getSortColumn());
    }

    @Test
    void clampsOutOfRangeValues() {
        MailSettings s = MailSettings.load();
        s.setListFontSize(999);
        assertEquals(32, s.getListFontSize());
        s.setListFontSize(1);
        assertEquals(8, s.getListFontSize());
        s.setCheckIntervalMinutes(-5);
        assertEquals(0, s.getCheckIntervalMinutes());
        s.setReaderFontSize(1000);
        assertEquals(40, s.getReaderFontSize());
    }

    @Test
    void rowHeightFollowsDensity() {
        MailSettings s = MailSettings.load();
        s.setDensity(MailSettings.Density.COMPACT);
        assertEquals(22, s.rowHeight());
        s.setDensity(MailSettings.Density.COMFORTABLE);
        assertEquals(34, s.rowHeight());
    }

    @Test
    void savesAndReloadsEveryField() {
        MailSettings s = MailSettings.load();
        s.setListFontFamily("Monospaced");
        s.setListFontSize(16);
        s.setReaderFontFamily("Serif");
        s.setReaderFontSize(18);
        s.setTheme(MailSettings.Theme.DARK);
        s.setAccentColor(0x123456);
        s.setDensity(MailSettings.Density.COMPACT);
        s.setReadingPanePosition(MailSettings.ReadingPanePosition.BOTTOM);
        s.setSortColumn(MailSettings.SortColumn.SUBJECT);
        s.setSortDescending(false);
        s.setCheckIntervalMinutes(30);
        s.setConfirmOnDelete(false);
        s.setRenderHtml(true);
        s.setNotifyOnNewMail(false);
        s.save();

        MailSettings r = MailSettings.load();
        assertEquals("Monospaced", r.getListFontFamily());
        assertEquals(16, r.getListFontSize());
        assertEquals("Serif", r.getReaderFontFamily());
        assertEquals(18, r.getReaderFontSize());
        assertEquals(MailSettings.Theme.DARK, r.getTheme());
        assertEquals(0x123456, r.getAccentColor());
        assertEquals(MailSettings.Density.COMPACT, r.getDensity());
        assertEquals(MailSettings.ReadingPanePosition.BOTTOM,
                r.getReadingPanePosition());
        assertEquals(MailSettings.SortColumn.SUBJECT, r.getSortColumn());
        assertFalse(r.isSortDescending());
        assertEquals(30, r.getCheckIntervalMinutes());
        assertFalse(r.isConfirmOnDelete());
        assertTrue(r.isRenderHtml());
        assertFalse(r.isNotifyOnNewMail());
    }
}
