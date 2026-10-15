/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Wonderland P5.1: the behaviour bridge is pure over a cell and the real Mandela
 * engine, so it runs headless -- no display, no scene graph, no server. What is
 * proven here is the decision (what a script is handed and what it may return)
 * and the safety (a broken or absent behaviour never reaches the render path);
 * what an annotation looks like floating over a live cell stays the walk-in
 * probe's job, exactly as the lighting PR left its on-screen shading.
 */
class WorldBehaviorTest {

    /** A model cell with every fact a script might read filled in. */
    private static WorldCatalog.Cell modelCell() {
        return new WorldCatalog.Cell("id-1", null,
                "org.jdesktop.wonderland.server.world.ModelCell", "Reception",
                1.0, 2.0, 4.0, 3.0, 0.5, "wla://m/models/room.dae",
                WorldCatalog.Cell.Pose.IDENTITY, null, null, null);
    }

    /** A cell placed nowhere, so every numeric fact must bind as 0.0. */
    private static WorldCatalog.Cell bareCell() {
        return new WorldCatalog.Cell("id-2", null, null, null);
    }

    @Nested
    @DisplayName("a behaviour script reads the cell and returns an annotation")
    class AppliesScript {

        @Test
        @DisplayName("the cell's own label is bound and returned verbatim")
        void labelRoundTrips() {
            assertEquals("Reception",
                    WorldBehavior.annotation("label", modelCell(), "fallback"));
        }

        @Test
        @DisplayName("a script can build a string from a bound name")
        void scriptConcatenatesBoundText() {
            assertEquals("[Reception]",
                    WorldBehavior.annotation("\"[\" + label + \"]\"", modelCell(), "x"));
        }

        @Test
        @DisplayName("a boolean fact is bound so a script can branch on it")
        void booleanFactIsBound() {
            assertEquals("true",
                    WorldBehavior.annotation("hasModel", modelCell(), "x"));
            assertEquals("false",
                    WorldBehavior.annotation("isAvatar", modelCell(), "x"));
        }

        @Test
        @DisplayName("an empty result is no annotation, so the fallback stays")
        void emptyResultFallsBack() {
            assertEquals("Reception",
                    WorldBehavior.annotation("\"\"", modelCell(), "Reception"));
        }
    }

    @Nested
    @DisplayName("a broken or absent behaviour is never a failed render")
    class DegradesSafely {

        @Test
        @DisplayName("no script and a blank script change nothing")
        void absentScriptIsNoChange() {
            assertEquals("Reception", WorldBehavior.annotation(null, modelCell(), "Reception"));
            assertEquals("Reception", WorldBehavior.annotation("   ", modelCell(), "Reception"));
        }

        @Test
        @DisplayName("a script that throws during evaluation falls back")
        void throwingScriptFallsBack() {
            assertEquals("Reception",
                    WorldBehavior.annotation("1 / 0", modelCell(), "Reception"));
        }

        @Test
        @DisplayName("a script that reads an unbound name falls back")
        void unknownNameFallsBack() {
            assertEquals("Reception",
                    WorldBehavior.annotation("noSuchFact + 1", modelCell(), "Reception"));
        }
    }

    @Nested
    @DisplayName("the bound vocabulary is exactly the plain facts of a cell")
    class Bindings {

        @Test
        @DisplayName("every documented name is present for a placed cell")
        void everyFactIsBound() {
            Map<String, Object> bound = WorldBehavior.bindingsOf(modelCell());
            assertTrue(bound.keySet().containsAll(java.util.List.of(
                    "label", "name", "cellType", "x", "y", "z", "width", "depth",
                    "hasModel", "isAvatar", "isGui")), "missing a bound fact");
            assertEquals("Reception", bound.get("label"));
            assertEquals(Boolean.TRUE, bound.get("hasModel"));
            assertEquals(Boolean.FALSE, bound.get("isAvatar"));
        }

        @Test
        @DisplayName("a missing number binds as 0.0, not null, so arithmetic is safe")
        void nullNumbersBecomeZero() {
            Map<String, Object> bound = WorldBehavior.bindingsOf(bareCell());
            assertEquals(0.0, bound.get("x"));
            assertEquals(0.0, bound.get("y"));
            assertEquals(0.0, bound.get("depth"));
            assertEquals(Boolean.FALSE, bound.get("hasModel"));
        }

        @Test
        @DisplayName("a null cell binds nothing rather than throwing")
        void nullCellBindsEmpty() {
            assertTrue(WorldBehavior.bindingsOf(null).isEmpty());
        }
    }

    @Nested
    @DisplayName("the behaviour asset is read through the world's own store")
    class ReadsAsset {

        @Test
        @DisplayName("a shipped behaviour.mnd is read as its source text")
        void presentAssetReads() {
            HttpWorldStore store = store("label".getBytes(StandardCharsets.UTF_8));
            assertEquals("label", WorldBehavior.scriptOf(store, "m"));
        }

        @Test
        @DisplayName("a world with no behaviour asset yields null")
        void absentAssetIsNull() {
            HttpWorldStore store = new HttpWorldStore("http://s", url -> Optional.empty());
            assertNull(WorldBehavior.scriptOf(store, "m"));
        }

        @Test
        @DisplayName("a blank behaviour file is treated as no behaviour")
        void blankAssetIsNull() {
            HttpWorldStore store = store("   \n  ".getBytes(StandardCharsets.UTF_8));
            assertNull(WorldBehavior.scriptOf(store, "m"));
        }

        @Test
        @DisplayName("a null store or module asks for nothing")
        void nullArgumentsAreSafe() {
            assertNull(WorldBehavior.scriptOf(null, "m"));
            assertNull(WorldBehavior.scriptOf(store("label".getBytes(StandardCharsets.UTF_8)), null));
        }

        private HttpWorldStore store(byte[] scriptBytes) {
            return new HttpWorldStore("http://s", url -> url.contains(
                    WorldBehavior.SCRIPT_PATH)
                    ? Optional.of(scriptBytes) : Optional.empty());
        }
    }
}
