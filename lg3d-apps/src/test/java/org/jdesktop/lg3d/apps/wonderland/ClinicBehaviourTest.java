/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wonderland P5.2: the clinic ships a behaviour, and it must actually do
 * something. {@link WorldBehaviorTest} proves the bridge (what a script is
 * handed, how it degrades); this test proves the one behaviour the repository
 * ships -- the outpatient clinic's {@code behaviour.mnd} beside its art -- is
 * valid Mandela and annotates each kind of cell the way its comment claims.
 *
 * <p>The script is read from the world module's resources, the same path the
 * walk-in's asset endpoint serves at runtime; this is the headless stand-in for
 * "the world really does re-annotate its cells." A behaviour script is NOT under
 * {@code scripts/mandela/}: those are self-contained programs and the shipped-
 * script gate rejects an undeclared name, whereas a world behaviour reads the
 * host-bound cell facts by design. This test is its gate instead.</p>
 */
class ClinicBehaviourTest {

    /** The shipped world behaviour, resolved against the repository layout. */
    private static final String BEHAVIOUR = "wonderland-server/src/main/resources/"
            + "wonderland-outpatient-clinic/behaviour.mnd";

    private static WorldCatalog.Cell cell(String className, String name, String cellType,
            String modelUrl, Double y) {
        return new WorldCatalog.Cell("id", null, className, name,
                1.0, 2.0, 4.0, 3.0, y, modelUrl,
                WorldCatalog.Cell.Pose.IDENTITY, null, cellType, null);
    }

    @Test
    @DisplayName("the shipped behaviour file is really being read")
    void behaviourExistsAndIsNotEmpty() {
        String source = readBehaviour();
        assertTrue(source.length() > 40, "the behaviour file is suspiciously short");
    }

    @Test
    @DisplayName("a model cell is annotated as the room the author named")
    void modelCellIsARoom() {
        WorldCatalog.Cell room = cell("org.jdesktop.wonderland.server.world.ModelCell",
                "Reception", null, "wla://m/models/desk.kmz", 0.0);
        assertEquals("the Reception", WorldBehavior.annotation(readBehaviour(), room, "FALLBACK"));
    }

    @Test
    @DisplayName("a raised model cell says it is raised")
    void raisedModelCellNotesItsHeight() {
        WorldCatalog.Cell shelf = cell("org.jdesktop.wonderland.server.world.ModelCell",
                "Cabinet", null, "wla://m/models/box.kmz", 1.2);
        assertEquals("the Cabinet  (raised 1.2m)",
                WorldBehavior.annotation(readBehaviour(), shelf, "FALLBACK"));
    }

    @Test
    @DisplayName("a seeded resident is annotated as a visitor")
    void avatarCellIsAVisitor() {
        WorldCatalog.Cell visitor = cell("org.jdesktop.wonderland.server.world.AvatarCell",
                "Dana", null, null, 0.0);
        assertEquals("a visitor", WorldBehavior.annotation(readBehaviour(), visitor, "FALLBACK"));
    }

    @Test
    @DisplayName("a pinned picture is annotated as a notice")
    void guiCellIsANotice() {
        WorldCatalog.Cell poster = cell("org.jdesktop.wonderland.server.world.GuiCell",
                "Poster", "image", null, 0.0);
        assertEquals("a notice on the wall",
                WorldBehavior.annotation(readBehaviour(), poster, "FALLBACK"));
    }

    @Test
    @DisplayName("an unplaced bare cell still yields its label, never a crash")
    void bareCellDegradesToItsLabel() {
        WorldCatalog.Cell bare = new WorldCatalog.Cell("id", null, null, "Hall");
        // No model, not an avatar, cellType null -> falls through to `label`.
        String note = WorldBehavior.annotation(readBehaviour(), bare, "FALLBACK");
        assertNotNull(note);
        assertEquals("Hall", note);
    }

    /** Read the shipped behaviour, trying the module and the repository layout. */
    private static String readBehaviour() {
        for (Path candidate : List.of(Path.of("..", BEHAVIOUR), Path.of(BEHAVIOUR))) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8).strip();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return fail("cannot find " + BEHAVIOUR + " from " + Path.of("").toAbsolutePath());
    }
}
