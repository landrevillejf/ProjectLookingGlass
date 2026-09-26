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
package org.jdesktop.lg3d.ftpclient.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers {@link AppSettings} defaults, clamping setters and the retry backoff. */
class AppSettingsTest {

    @Test
    @DisplayName("defaults are secure and sane out of the box")
    void defaults() {
        AppSettings s = new AppSettings();
        assertThat(s.getConnectTimeoutSeconds()).isEqualTo(15);
        assertThat(s.getDataTimeoutSeconds()).isEqualTo(30);
        assertThat(s.getRetryCount()).isEqualTo(3);
        assertThat(s.getRetryBackoffMillis()).isEqualTo(500L);
        assertThat(s.getBufferSize()).isEqualTo(64 * 1024);
        assertThat(s.isPassiveDefault()).isTrue();
        assertThat(s.isConfirmOverwrite()).isTrue();
        assertThat(s.isAllowSavePasswords()).isFalse();
        assertThat(s.isShowHiddenFiles()).isFalse();
    }

    @Test
    @DisplayName("numeric setters clamp to their valid ranges")
    void clamping() {
        AppSettings s = new AppSettings();
        s.setConnectTimeoutSeconds(-1);
        assertThat(s.getConnectTimeoutSeconds()).isZero();
        s.setDataTimeoutSeconds(-9);
        assertThat(s.getDataTimeoutSeconds()).isZero();
        s.setRetryCount(-3);
        assertThat(s.getRetryCount()).isZero();
        s.setRetryBackoffMillis(-100);
        assertThat(s.getRetryBackoffMillis()).isZero();

        s.setBufferSize(1);
        assertThat(s.getBufferSize()).isEqualTo(AppSettings.MIN_BUFFER_SIZE);
        s.setBufferSize(Integer.MAX_VALUE);
        assertThat(s.getBufferSize()).isEqualTo(AppSettings.MAX_BUFFER_SIZE);
        s.setBufferSize(8192);
        assertThat(s.getBufferSize()).isEqualTo(8192);
    }

    @Test
    @DisplayName("backoffFor doubles per attempt, never goes negative, and floors at zero")
    void backoff() {
        AppSettings s = new AppSettings();
        s.setRetryBackoffMillis(100);
        assertThat(s.backoffFor(0)).isEqualTo(100);
        assertThat(s.backoffFor(1)).isEqualTo(200);
        assertThat(s.backoffFor(2)).isEqualTo(400);
        // A negative attempt is treated as the first.
        assertThat(s.backoffFor(-5)).isEqualTo(100);
        // A large attempt is capped so the shift cannot overflow negative.
        assertThat(s.backoffFor(64)).isPositive();
    }

    @Test
    @DisplayName("boolean setters round-trip")
    void booleans() {
        AppSettings s = new AppSettings();
        s.setPassiveDefault(false);
        s.setConfirmOverwrite(false);
        s.setAllowSavePasswords(true);
        s.setShowHiddenFiles(true);
        assertThat(s.isPassiveDefault()).isFalse();
        assertThat(s.isConfirmOverwrite()).isFalse();
        assertThat(s.isAllowSavePasswords()).isTrue();
        assertThat(s.isShowHiddenFiles()).isTrue();
    }
}
