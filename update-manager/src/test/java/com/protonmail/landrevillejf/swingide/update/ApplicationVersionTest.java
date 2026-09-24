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
package com.protonmail.landrevillejf.swingide.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ApplicationVersionTest {

    /** The forced version the test JVM started with (the test task sets 0.0.0). */
    private String originalVersionProperty;

    @BeforeEach
    void setUp() {
        originalVersionProperty = System.getProperty(ApplicationVersion.VERSION_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        // Restore, never clear: the whole suite shares one JVM and the test task
        // forces lg3d.version=0.0.0. Clearing it here would leak a cleared
        // property to later classes, making ApplicationVersion.current() fall
        // back to the packaged 1.9.0-dev and breaking version-comparison tests
        // (e.g. UpdateServiceIntegrationTest) that run after this one.
        if (originalVersionProperty == null) {
            System.clearProperty(ApplicationVersion.VERSION_PROPERTY);
        } else {
            System.setProperty(ApplicationVersion.VERSION_PROPERTY, originalVersionProperty);
        }
    }

    @Test
    void testForcedVersionWins() {
        System.setProperty(ApplicationVersion.VERSION_PROPERTY, "0.0.1");

        assertThat(ApplicationVersion.current()).isEqualTo("0.0.1");
    }

    @Test
    void testForcedVersionIsTrimmed() {
        System.setProperty(ApplicationVersion.VERSION_PROPERTY, "  0.0.2  ");

        assertThat(ApplicationVersion.current()).isEqualTo("0.0.2");
    }

    @Test
    void testBlankForcedVersionIsIgnored() {
        System.setProperty(ApplicationVersion.VERSION_PROPERTY, "   ");

        assertThat(ApplicationVersion.current()).isNotBlank();
    }

    @Test
    void testCurrentVersionIsAlwaysUsable() {
        assertThat(ApplicationVersion.current()).isNotNull().isNotBlank();
        assertThatCode(() -> Version.parse(ApplicationVersion.current())).doesNotThrowAnyException();
    }

    @Test
    void testBuildTimestampIsOptional() {
        assertThatCode(() -> ApplicationVersion.buildTimestamp()).doesNotThrowAnyException();
    }

    @Test
    void testConstants() {
        assertThat(ApplicationVersion.VERSION_PROPERTY).isEqualTo("lg3d.version");
        assertThat(ApplicationVersion.VERSION_RESOURCE).isEqualTo("/version.properties");
        assertThat(ApplicationVersion.UNKNOWN_VERSION).isEqualTo("0.0.0");
    }
}
