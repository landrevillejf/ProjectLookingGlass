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
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdesktop.lg3d.apps.wonderland.WonderlandWorldPanel.Loaded;
import org.jdesktop.lg3d.apps.wonderland.WonderlandWorldPanel.Row;
import org.jdesktop.lg3d.apps.wonderland.WonderlandWorldPanel.WorldsOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the face of the Wonderland World app: what the panel shows
 * after a read that worked, after one that did not, and how a published flat
 * list becomes indented rows.
 *
 * <p>The panel is constructed but never shown, which is legal headless, and the
 * read is driven through the package-private seams ({@code read} /
 * {@code render}) rather than the button, so no background thread and no EDT
 * timing is involved. The transport is scripted, so no server and no network is
 * touched &mdash; the same discipline {@code OpenMeteoTest} applies to its own
 * app. How the rows actually paint inside a {@code SwingNode} or an MDI frame is
 * verified with the desktop probe, not here.</p>
 */
class WonderlandWorldPanelTest {

    /** The same two-level world {@code WorldCatalogTest} parses. */
    private static final String SAMPLE = "["
            + "{\"id\":\"1\",\"parent\":null,\"className\":\"org.jdesktop.wonderland.modules.cell.EnvironmentCell\",\"name\":\"Environment\"},"
            + "{\"id\":\"2\",\"parent\":\"1\",\"className\":\"org.jdesktop.wonderland.modules.cell.TestCell\",\"name\":\"Room\"},"
            + "{\"id\":\"3\",\"parent\":\"2\",\"className\":\"org.jdesktop.wonderland.modules.meshmodel.MeshModelCell\",\"name\":null}"
            + "]";

    /** A panel that does not reach out on its own, reading a fixed document. */
    private static WonderlandWorldPanel panelReturning(String body) {
        return new WonderlandWorldPanel(url -> body, false);
    }

    @Test
    @DisplayName("the address field opens on the configured server")
    void opensOnConfiguredServer() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        // The panel and the test read the same property, so this states the
        // wiring rather than a hard-coded localhost.
        assertEquals(WorldCatalog.defaultServer(), panel.serverText());
        assertTrue(WonderlandWorldPanel.isReadableAddress(panel.serverText()));
        panel.close();
    }

    @Test
    @DisplayName("a blank address is refused before any request is made")
    void blankAddressIsNotReadable() {
        assertFalse(WonderlandWorldPanel.isReadableAddress(null));
        assertFalse(WonderlandWorldPanel.isReadableAddress(""));
        assertFalse(WonderlandWorldPanel.isReadableAddress("   "));

        WonderlandWorldPanel panel = panelReturning(SAMPLE);
        // And a panel that has not rendered anything lists nothing, which is
        // what the guard leaves behind when it refuses an empty field.
        assertTrue(panel.rows().isEmpty());
        panel.close();
    }

    @Test
    @DisplayName("a loaded world lists its cells with depth and path")
    void rendersPublishedWorld() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        panel.render(panel.read("localhost:8080"));

        List<Row> rows = panel.rows();
        assertEquals(3, rows.size());
        assertEquals(List.of(0, 1, 2),
                List.of(rows.get(0).depth, rows.get(1).depth, rows.get(2).depth));
        assertEquals("Environment", rows.get(0).toString());
        assertEquals("MeshModelCell", rows.get(2).toString());
        assertEquals("Environment / Room", rows.get(1).path);
        assertNull(rows.get(0).cell.parent);
        assertEquals("1", rows.get(1).cell.parent);
        panel.close();
    }

    @Test
    @DisplayName("the status line is the census of what was actually read")
    void statusStatesTheCensus() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        panel.render(panel.read("localhost:8080"));

        assertEquals("3 cells \u00B7 3 levels \u00B7 root: Environment"
                + " \u00B7 http://localhost:8080/api/world/cells", panel.statusText());
        panel.close();
    }

    @Test
    @DisplayName("the first row is selected so the detail column is never blank")
    void firstRowIsSelectedForTheDetailColumn() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        panel.render(panel.read("localhost:8080"));

        assertEquals("1", panel.selectedRow().cell.id);
        // The breadcrumb follows the selection through the list listener.
        assertEquals("Environment", panel.detailPathText());
        panel.close();
    }

    @Test
    @DisplayName("an empty world is listed as empty, not as an error")
    void emptyWorldIsReportedHonestly() {
        WonderlandWorldPanel panel = panelReturning("[]");

        panel.render(panel.read("localhost:8080"));

        assertTrue(panel.rows().isEmpty());
        assertEquals("the world has no cells"
                + " \u00B7 http://localhost:8080/api/world/cells", panel.statusText());
        assertNull(panel.selectedRow());
        panel.close();
    }

    @Test
    @DisplayName("an unreachable server says so and clears the stale rows")
    void failureNamesTheServer() throws IOException {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(url -> {
            throw new IOException("Connection refused");
        }, false);
        // A previous successful read must not keep showing once the server is gone.
        panel.render(new Loaded("localhost:8080", WorldCatalog.parse(SAMPLE), null));
        assertEquals(3, panel.rows().size());

        panel.render(panel.read("localhost:8080"));

        assertTrue(panel.rows().isEmpty());
        // The strip names the server that refused AND the command that starts
        // one, because the usual reason for the refusal is that none is running.
        assertTrue(panel.statusText().startsWith("Cannot reach "
                        + "http://localhost:8080/api/world/cells (Connection refused)"),
                panel.statusText());
        assertTrue(panel.statusText().contains(WorldCatalog.startCommand()),
                panel.statusText());
        assertNull(panel.selectedRow());
        panel.close();
    }

    @Test
    @DisplayName("a read never throws, whatever the transport did")
    void readNeverThrows() {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(url -> {
            throw new IllegalStateException("the transport blew up");
        }, false);

        Loaded loaded = panel.read("localhost:8080");

        assertNull(loaded.world);
        // A non-IO failure is still reportable on the status strip.
        assertTrue(loaded.failure instanceof IOException);
        panel.render(loaded);
        assertTrue(panel.statusText().startsWith("Cannot reach "));
        panel.close();
    }

    @Test
    @DisplayName("close stops the fetch thread and runs the host's hook")
    void closeRunsTheHook() {
        boolean[] closed = new boolean[1];
        WonderlandWorldPanel panel = panelReturning(SAMPLE);
        panel.setOnClose(() -> closed[0] = true);

        panel.close();

        assertTrue(closed[0]);
        // A second close must not throw: a host may dispose the frame twice.
        panel.close();
    }

    @Test
    @DisplayName("a panel without a hook still closes")
    void closeWithoutHookIsSafe() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);
        panel.close();
        assertNull(panel.selectedRow());
    }

    // ------------------------------------------------------------------
    // The world browser: list every world, pick one, and (auto) start a server
    // ------------------------------------------------------------------

    /** Two worlds in the shape {@code /api/world/worlds} writes. */
    private static final String WORLDS_BODY = "["
            + "{\"module\":\"clinic\",\"title\":\"Clinic\",\"description\":\"a clinic\","
            + "\"source\":\"classpath\",\"cellCount\":9},"
            + "{\"module\":\"town\",\"title\":\"Town\",\"description\":\"\","
            + "\"source\":\"jar\",\"cellCount\":2}]";

    /** A launcher whose server is already answering, and one that never spawns. */
    private static WonderlandServerLauncher launcher(String server, boolean ready) {
        WonderlandServerLauncher.Spawner never = argv -> {
            throw new IOException("a test launcher never spawns");
        };
        WonderlandServerLauncher.Probe probe = candidate -> ready;
        return new WonderlandServerLauncher(
                Path.of("/nonexistent-repo"), never, probe, Duration.ofMillis(1));
    }

    @Test
    @DisplayName("a catalog read fills the picker and holds the first world")
    void renderWorldsFillsPicker() {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(url -> WORLDS_BODY, false);

        WorldsOutcome outcome = panel.readWorlds("localhost:8080");
        assertNull(outcome.failure);
        assertEquals(2, outcome.worlds.size());

        panel.renderWorlds(outcome);
        assertEquals(2, panel.worldCount());
        assertEquals("clinic", panel.selectedWorldModule());
        panel.close();
    }

    @Test
    @DisplayName("an unreachable server with no launcher names the start command")
    void worldsFailureNamesTheStartCommand() {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(url -> {
            throw new IOException("Connection refused");
        }, false);

        panel.renderWorlds(panel.readWorlds("localhost:8080"));

        assertEquals(0, panel.worldCount());
        assertTrue(panel.rows().isEmpty());
        assertTrue(panel.statusText().startsWith(
                "Cannot reach http://localhost:8080/api/world/cells (Connection refused)"),
                panel.statusText());
        assertTrue(panel.statusText().contains(WorldCatalog.startCommand()),
                panel.statusText());
        panel.close();
    }

    @Test
    @DisplayName("a failed auto-start explains itself instead of the manual hint")
    void worldsFailureWithFailedAutoStartNamesTheNote() {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(
                url -> {
                    throw new IOException("Connection refused");
                },
                launcher("localhost:8080", false), // probe never ready, repo has no jar
                false);

        panel.renderWorlds(panel.readWorlds("localhost:8080"));

        assertEquals(0, panel.worldCount());
        assertTrue(panel.statusText().contains("auto-start"), panel.statusText());
        // no jar under the fake repo root -> the launcher says where to fall back
        assertTrue(panel.statusText().contains("no wonderland-server jar found"),
                panel.statusText());
        panel.close();
    }

    @Test
    @DisplayName("auto-start retries the catalog once the server is up")
    void autoStartRetriesAfterTheServerComesUp() {
        AtomicInteger calls = new AtomicInteger();
        WorldCatalog.Fetcher flaky = url -> {
            if (calls.getAndIncrement() == 0) {
                throw new IOException("Connection refused");
            }
            return WORLDS_BODY;
        };
        WonderlandWorldPanel panel = new WonderlandWorldPanel(
                flaky, launcher("localhost:8080", true), false);

        WorldsOutcome outcome = panel.readWorlds("localhost:8080");
        assertNull(outcome.failure);
        assertNotNull(outcome.attemptNote);
        assertEquals(2, outcome.worlds.size());

        panel.renderWorlds(outcome);
        assertEquals(2, panel.worldCount());
        assertEquals("clinic", panel.selectedWorldModule());
        panel.close();
    }

    @Test
    @DisplayName("a remote address is never auto-started")
    void aRemoteServerIsNotLaunchedLocally() {
        WonderlandServerLauncher remote = launcher("example.org:8080", false);
        WonderlandServerLauncher.Result result =
                remote.ensureRunning("http://world.example.org:8080");

        assertFalse(result.ready());
        assertFalse(result.launched());
        assertTrue(result.note().contains("remote server"), result.note());
    }

    @Test
    @DisplayName("close stops a server the launcher started, but only its own")
    void closeDoesNotStopAnUnownedServer() {
        // a panel built with the no-auto-start constructor owns no launcher, so
        // closing it is safe and stops nothing that it did not start
        WonderlandWorldPanel panel = new WonderlandWorldPanel(url -> WORLDS_BODY, false);
        panel.renderWorlds(panel.readWorlds("localhost:8080"));
        panel.close();
        assertEquals(2, panel.worldCount());
    }

    // ------------------------------------------------------------------
    // The explicit Start / Stop Server buttons
    // ------------------------------------------------------------------

    @Test
    @DisplayName("without a launcher the server buttons are dead")
    void noLauncherDisablesTheServerButtons() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        assertFalse(panel.serverStartEnabled());
        assertFalse(panel.serverStopEnabled());
        assertEquals("no launcher", panel.stopServer());
        panel.close();
    }

    @Test
    @DisplayName("a failed Start press shows the launcher's reason")
    void startFailureShowsTheReason() {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(
                url -> WORLDS_BODY, launcher("localhost:8080", false), false);
        panel.renderWorlds(panel.readWorlds("localhost:8080"));

        panel.renderStart(new WonderlandServerLauncher.Result(false, false,
                "no wonderland-server jar found"), null);

        assertTrue(panel.statusText().startsWith(
                "cannot start wonderland-server \u2014 no wonderland-server jar found"),
                panel.statusText());
        assertEquals(0, panel.worldCount());
        assertTrue(panel.rows().isEmpty());
        assertTrue(panel.serverStartEnabled());   // pressable again
        assertFalse(panel.serverStopEnabled());   // nothing was started
        panel.close();
    }

    @Test
    @DisplayName("a Start press that landed reads the world catalog")
    void startSuccessReadsTheWorlds() {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(
                url -> WORLDS_BODY, launcher("localhost:8080", true), false);

        panel.renderStart(new WonderlandServerLauncher.Result(
                        true, false, "a server already answers on http://localhost:8080"),
                panel.readWorlds("localhost:8080"));

        assertEquals(2, panel.worldCount());
        assertEquals("clinic", panel.selectedWorldModule());
        panel.close();
    }

    @Test
    @DisplayName("Stop refuses a server this panel does not own")
    void stopRefusesAnUnownedServer() {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(
                url -> WORLDS_BODY, launcher("localhost:8080", true), false);
        panel.renderWorlds(panel.readWorlds("localhost:8080"));

        String note = panel.stopServer();

        assertTrue(note.contains("only stops a server it started"), note);
        assertEquals(2, panel.worldCount());      // the read is left standing
        assertFalse(panel.serverStopEnabled());
        panel.close();
    }

    @Test
    @DisplayName("Stop reaps the child Start brought up")
    void stopReapsTheManagedChild(@TempDir Path repo) throws IOException {
        Path jar = repo.resolve("wonderland-server-app.jar");
        Files.createFile(jar);
        String before = System.getProperty(WonderlandServerLauncher.JAR_PROPERTY);
        try {
            System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, jar.toString());
            AtomicInteger polls = new AtomicInteger();
            WonderlandServerLauncher.Probe laterReady = server -> polls.getAndIncrement() > 0;
            WonderlandServerLauncher.Spawner alive = argv ->
                    new WonderlandServerLauncher.Launched() {
                        @Override
                        public boolean alive() {
                            return true;
                        }

                        @Override
                        public void stop() {
                            // fake child: nothing to reap
                        }
                    };
            WonderlandServerLauncher owned = new WonderlandServerLauncher(
                    repo, alive, laterReady, Duration.ofMillis(1));
            WonderlandWorldPanel panel = new WonderlandWorldPanel(
                    url -> WORLDS_BODY, owned, false);
            // the Start flow: ensureRunning spawns (pre-check false, poll true)
            assertTrue(owned.ensureRunning("localhost:8080").ready());

            panel.renderStart(new WonderlandServerLauncher.Result(
                            true, true, "started wonderland-server"),
                    panel.readWorlds("localhost:8080"));
            assertTrue(panel.serverStopEnabled());

            String note = panel.stopServer();

            assertTrue(note.contains("stopped"), note);
            assertFalse(owned.isManaging());
            assertFalse(panel.serverStopEnabled());
            assertEquals(0, panel.worldCount());
            panel.close();
        } finally {
            if (before == null) {
                System.clearProperty(WonderlandServerLauncher.JAR_PROPERTY);
            } else {
                System.setProperty(WonderlandServerLauncher.JAR_PROPERTY, before);
            }
        }
    }
}
