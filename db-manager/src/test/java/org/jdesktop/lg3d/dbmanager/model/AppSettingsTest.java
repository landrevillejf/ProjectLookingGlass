/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.dbmanager.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link AppSettings} defaults (a fresh install must work with no
 * settings file), the null-safe {@code nullText} setter and the
 * {@link AppSettings#effectiveRowLimit(int)} combination rules.
 */
class AppSettingsTest {

    @Test
    @DisplayName("a fresh settings object carries documented defaults")
    void defaults() {
        AppSettings s = new AppSettings();
        assertThat(s.getMaxRows()).isEqualTo(1000);
        assertThat(s.getPageSize()).isEqualTo(200);
        assertThat(s.getFetchSize()).isEqualTo(200);
        assertThat(s.getNullText()).isEqualTo("(null)");
        assertThat(s.getQueryTimeoutSeconds()).isEqualTo(60);
        assertThat(s.getConnectTimeoutSeconds()).isEqualTo(15);
        assertThat(s.isAutoCommitDefault()).isTrue();
        assertThat(s.isAllowSavePasswords()).isFalse();
        assertThat(s.isConfirmOnWrite()).isTrue();
        assertThat(s.getEditorFontSize()).isEqualTo(13);
        assertThat(s.getHistoryLimit()).isEqualTo(50);
    }

    @Test
    @DisplayName("setters store their values; null nullText becomes empty")
    void settersRoundTrip() {
        AppSettings s = new AppSettings();
        s.setMaxRows(500);
        s.setPageSize(50);
        s.setFetchSize(75);
        s.setQueryTimeoutSeconds(30);
        s.setConnectTimeoutSeconds(5);
        s.setAutoCommitDefault(false);
        s.setAllowSavePasswords(true);
        s.setConfirmOnWrite(false);
        s.setEditorFontSize(16);
        s.setHistoryLimit(99);
        assertThat(s.getMaxRows()).isEqualTo(500);
        assertThat(s.getPageSize()).isEqualTo(50);
        assertThat(s.getFetchSize()).isEqualTo(75);
        assertThat(s.getQueryTimeoutSeconds()).isEqualTo(30);
        assertThat(s.getConnectTimeoutSeconds()).isEqualTo(5);
        assertThat(s.isAutoCommitDefault()).isFalse();
        assertThat(s.isAllowSavePasswords()).isTrue();
        assertThat(s.isConfirmOnWrite()).isFalse();
        assertThat(s.getEditorFontSize()).isEqualTo(16);
        assertThat(s.getHistoryLimit()).isEqualTo(99);

        s.setNullText(null);
        assertThat(s.getNullText()).isEmpty();
        s.setNullText("<none>");
        assertThat(s.getNullText()).isEqualTo("<none>");
    }

    @Test
    @DisplayName("effectiveRowLimit takes the tighter of global and per-profile caps")
    void effectiveRowLimitCombination() {
        AppSettings s = new AppSettings();
        s.setMaxRows(1000);
        // both positive -> the smaller wins
        assertThat(s.effectiveRowLimit(250)).isEqualTo(250);
        assertThat(s.effectiveRowLimit(5000)).isEqualTo(1000);
        // profile uncapped -> global cap applies
        assertThat(s.effectiveRowLimit(0)).isEqualTo(1000);
        assertThat(s.effectiveRowLimit(-1)).isEqualTo(1000);

        // global disabled -> the profile limit governs (negatives clamp to 0)
        s.setMaxRows(0);
        assertThat(s.effectiveRowLimit(300)).isEqualTo(300);
        assertThat(s.effectiveRowLimit(0)).isZero();
        assertThat(s.effectiveRowLimit(-5)).isZero();
    }
}
