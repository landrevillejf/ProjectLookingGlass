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
package com.protonmail.landrevillejf.swingide.update.rollback;

import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the failed-update rollback trigger (roadmap §10.2).
 */
class RollbackTriggerTest {

    private static final String PREVIOUS = "0.5.0";

    @TempDir
    Path tempDir;

    private DowngradeManager downgradeManager;
    private Path markerFile;
    private Path currentJar;
    private Path previousJar;
    private AtomicInteger restarts;
    private RollbackTrigger trigger;

    @BeforeEach
    void setUp() throws Exception {
        downgradeManager = new DowngradeManager(tempDir.resolve("versions"));
        markerFile = tempDir.resolve(RollbackTrigger.PENDING_FILE_NAME);
        currentJar = tempDir.resolve("swing-ide.jar");
        previousJar = tempDir.resolve("previous.jar");

        Files.writeString(currentJar, "current-version");
        Files.writeString(previousJar, "previous-version");

        downgradeManager.registerBackup(PREVIOUS, previousJar, "build-1");

        restarts = new AtomicInteger();
        trigger = new RollbackTrigger(
            downgradeManager,
            markerFile,
            () -> currentJar,
            restarts::incrementAndGet
        );
    }

    @Test
    void noMarkerMeansNothingToDo() {
        assertThat(trigger.evaluateOnStartup(() -> false))
            .isEqualTo(RollbackTrigger.Outcome.NO_PENDING_UPDATE);
        assertThat(restarts).hasValue(0);
    }

    @Test
    void healthyStartClearsTheMarkerAndKeepsTheJar() throws IOException {
        trigger.markPendingUpdate(PREVIOUS);
        assertThat(trigger.readPendingVersion()).contains(PREVIOUS);

        RollbackTrigger.Outcome outcome = trigger.evaluateOnStartup(() -> true);

        assertThat(outcome).isEqualTo(RollbackTrigger.Outcome.HEALTHY);
        assertThat(trigger.readPendingVersion()).isEmpty();
        assertThat(Files.readString(currentJar)).isEqualTo("current-version");
        assertThat(restarts).hasValue(0);
    }

    @Test
    void unhealthyStartRestoresThePreviousVersion() throws IOException {
        trigger.markPendingUpdate(PREVIOUS);

        RollbackTrigger.Outcome outcome = trigger.evaluateOnStartup(() -> false);

        assertThat(outcome).isEqualTo(RollbackTrigger.Outcome.ROLLED_BACK);
        assertThat(Files.readString(currentJar)).isEqualTo("previous-version");
        assertThat(trigger.readPendingVersion()).isEmpty();
        assertThat(restarts).hasValue(1);
    }

    @Test
    void healthCheckThrowingIsTreatedAsUnhealthy() throws IOException {
        trigger.markPendingUpdate(PREVIOUS);

        RollbackTrigger.Outcome outcome = trigger.evaluateOnStartup(() -> {
            throw new IllegalStateException("boom");
        });

        assertThat(outcome).isEqualTo(RollbackTrigger.Outcome.ROLLED_BACK);
        assertThat(Files.readString(currentJar)).isEqualTo("previous-version");
    }

    @Test
    void unknownRollbackTargetFailsWithoutLosingTheMarker() {
        trigger.markPendingUpdate("9.9.9");

        RollbackTrigger.Outcome outcome = trigger.evaluateOnStartup(() -> false);

        assertThat(outcome).isEqualTo(RollbackTrigger.Outcome.ROLLBACK_FAILED);
        assertThat(trigger.readPendingVersion()).contains("9.9.9");
        assertThat(restarts).hasValue(0);
    }

    @Test
    void blankVersionIsNotRecorded() {
        assertThat(trigger.markPendingUpdate("  ")).isFalse();
        assertThat(trigger.readPendingVersion()).isEmpty();
    }

    @Test
    void defaultMarkerFileHonoursTheConfigDirProperty() {
        System.setProperty(RollbackTrigger.CONFIG_DIR_PROPERTY, tempDir.toString());
        try {
            assertThat(RollbackTrigger.defaultMarkerFile())
                .isEqualTo(tempDir.resolve(RollbackTrigger.PENDING_FILE_NAME));
        } finally {
            System.clearProperty(RollbackTrigger.CONFIG_DIR_PROPERTY);
        }
    }
}
