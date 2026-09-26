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
package org.jdesktop.lg3d.ftpclient.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jdesktop.lg3d.ftpclient.model.ProfileStore;
import org.jdesktop.lg3d.ftpclient.net.RemotePaths;
import org.jdesktop.lg3d.ftpclient.session.ConnectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link RemoteBrowser}: headless construction rooted at {@code /}, the
 * synchronous {@code clear}/selection surface, and the asynchronous
 * {@code navigate} path - exercised against an unconnected manager so the worker
 * fails fast and routes the error to the logger (verified with a bounded latch).
 */
class RemoteBrowserTest {

    private ConnectionManager disconnectedManager(Path dir) {
        return new ConnectionManager(new ProfileStore(dir));
    }

    @Test
    @DisplayName("construction is headless-safe and starts at the root path")
    void construction(@TempDir Path dir) {
        RemoteBrowser b = new RemoteBrowser(disconnectedManager(dir));
        assertThat(b).isNotNull();
        assertThat(b.getCurrentPath()).isEqualTo(RemotePaths.SEPARATOR);
        assertThat(b.getSelectedEntries()).isEmpty();
    }

    @Test
    @DisplayName("clear resets the path to root and empties the view")
    void clear(@TempDir Path dir) {
        RemoteBrowser b = new RemoteBrowser(disconnectedManager(dir));
        b.clear();
        assertThat(b.getCurrentPath()).isEqualTo(RemotePaths.SEPARATOR);
        assertThat(b.getSelectedEntries()).isEmpty();
    }

    @Test
    @DisplayName("callbacks can be attached without throwing")
    void callbacks(@TempDir Path dir) {
        RemoteBrowser b = new RemoteBrowser(disconnectedManager(dir));
        b.setLogger(msg -> {
            // no-op
        });
        b.setOnActivate(entries -> {
            // no-op
        });
        b.setLogger(null);
        b.setOnActivate(null);
        assertThat(b.getSelectedEntries()).isEmpty();
    }

    @Test
    @DisplayName("navigate lists on a worker and routes a failure to the logger, leaving the path")
    void navigateFailureIsLogged(@TempDir Path dir) throws Exception {
        RemoteBrowser b = new RemoteBrowser(disconnectedManager(dir));
        CountDownLatch logged = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>();
        b.setLogger(msg -> {
            message.set(msg);
            logged.countDown();
        });

        b.navigate("/pub");
        assertThat(logged.await(15, TimeUnit.SECONDS))
                .as("the navigate worker should report its failure to the logger")
                .isTrue();
        assertThat(message.get()).contains("Cannot list").contains("/pub");
        // A failed navigation must not move the cursor.
        assertThat(b.getCurrentPath()).isEqualTo(RemotePaths.SEPARATOR);
    }
}
