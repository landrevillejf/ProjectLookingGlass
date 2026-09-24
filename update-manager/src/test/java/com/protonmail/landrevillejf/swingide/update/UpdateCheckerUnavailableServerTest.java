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

import com.protonmail.landrevillejf.swingide.core.bus.EventBus;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link UpdateChecker#checkForUpdates()} treats both network
 * failures and HTTP non-2xx answers from the metadata endpoint as a quiet skip
 * rather than as a fatal error. This is the behaviour relied upon by
 * {@code UpdatePresenter.reportCheckResult} to avoid popping an error dialog
 * when the release host is misconfigured, offline, or protected by
 * authentication the client does not carry (private GitHub release assets).
 */
class UpdateCheckerUnavailableServerTest {

    @Test
    void testHttpNon2xxIsTreatedAsQuietSkip() {
        FailingRepository repository = new FailingRepository(
            new UpdateServerUnavailableException(404, "https://example.invalid/version.json")
        );

        UpdateChecker checker = new UpdateChecker(repository, 60_000L, new EventBus());

        assertThat(checker.checkForUpdates()).isNull();
    }

    @Test
    void testHttpNon2xxWrappedInUpdateExceptionIsStillQuiet() {
        // The checker walks the whole cause chain, so an UpdateException whose
        // root cause is UpdateServerUnavailableException must be recognised.
        FailingRepository repository = new FailingRepository(
            new UpdateException(
                "wrapper",
                new UpdateServerUnavailableException(401, "https://example.invalid/version.json")
            )
        );

        UpdateChecker checker = new UpdateChecker(repository, 60_000L, new EventBus());

        assertThat(checker.checkForUpdates()).isNull();
    }

    @Test
    void testUnknownHostIsStillTreatedAsQuietSkip() {
        FailingRepository repository = new FailingRepository(
            new UpdateException("network down", new UnknownHostException("releases.invalid"))
        );

        UpdateChecker checker = new UpdateChecker(repository, 60_000L, new EventBus());

        assertThat(checker.checkForUpdates()).isNull();
    }

    /**
     * Repository throwing a canned exception without touching the network.
     */
    private static final class FailingRepository extends UpdateRepository {

        private final UpdateException failure;

        FailingRepository(UpdateException failure) {
            super("http://mock.local");
            this.failure = failure;
        }

        @Override
        public UpdateInfo fetchLatestVersion() throws UpdateException {
            throw failure;
        }
    }
}
