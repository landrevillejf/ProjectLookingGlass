/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the autosave policy -- the judgement CellSnapshot
 * never made on its own. The clock is injected everywhere it matters, so the
 * throttle ("has it settled?"), the retry-on-failure ("did the write land?") and
 * the revision short-circuit ("is there anything new?") are all decided at an
 * arbitrary instant a test can name. A real clock only ever reaches these through
 * the thin WorldSnapshotService bean, which has no judgement left to test.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.math.Vector3f;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldAutosaverTest {

    private static CellTransform at(float x, float y, float z) {
        CellTransform t = new CellTransform();
        t.setTranslation(new Vector3f(x, y, z));
        return t;
    }

    /** A world hosting one movable content cell at a fixed, reproducible id. */
    private static WorldModel oneCellWorld(long cellIdValue, float x, float y, float z) {
        WorldModel model = new WorldModel();
        CellID id = new CellID(cellIdValue);
        model.addCell(id, "some.client.Cell", "chair", at(x, y, z), null);
        return model;
    }

    @Test
    void anUntouchedWorldIsNotDirtyAndWritesNothing(@TempDir Path dir) {
        WorldModel model = oneCellWorld(100L, 0f, 0f, 0f);
        long seeded = model.revision(); // the addCell bumped it once
        WorldAutosaver saver = new WorldAutosaver(model, dir.resolve("world.snapshot"), 1000);
        // mark the seeded state saved so a bare world is not "dirty" against nothing
        saver.flush(0L);

        saver.tick(999_999L);

        assertThat(model.revision()).isEqualTo(seeded);
        assertThat(dir.resolve("world.snapshot")).exists();
    }

    @Test
    void aChangeWaitsForTheQuietIntervalBeforeWriting(@TempDir Path dir) throws IOException {
        WorldModel model = oneCellWorld(200L, 1f, 2f, 3f);
        Path file = dir.resolve("world.snapshot");
        WorldAutosaver saver = new WorldAutosaver(model, file, 1000);
        saver.flush(0L);          // baseline: current state saved, not dirty

        model.applyMove(new CellID(200L), at(9f, 9f, 9f));
        assertThat(saver.isDirty()).isTrue();

        saver.tick(500L);         // only 500ms of the 1000ms interval has passed
        assertThat(saver.isDirty()).isTrue();
        assertThat(file).exists();
        // the baseline (chair at 1,2,3) is still what is on disk: the move to 9
        // (Float.toHexString(9f) == "0x1.2p3") has not landed yet
        assertThat(Files.readAllLines(file)).noneMatch(l -> l.contains("0x1.2p3"));

        saver.tick(1500L);        // past the interval -- now it settles
        assertThat(saver.isDirty()).isFalse();
    }

    @Test
    void aMoveRoundTripsThroughTheSnapshotExactly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("world.snapshot");
        WorldModel origin = oneCellWorld(300L, 0f, 0f, 0f);
        WorldAutosaver saver = new WorldAutosaver(origin, file, 0);
        origin.applyMove(new CellID(300L), at(0.1f, 0.2f, 0.3f));
        saver.flush(0L);
        assertThat(saver.isDirty()).isFalse();

        // a fresh world re-seeded from art (chair back at the origin), then restored
        WorldModel reboot = oneCellWorld(300L, 0f, 0f, 0f);
        int applied = CellSnapshot.restore(reboot, file);

        assertThat(applied).isEqualTo(1);
        Vector3f back = new Vector3f();
        reboot.find(new CellID(300L)).getTransform().getTranslation(back);
        assertThat(back).isEqualTo(new Vector3f(0.1f, 0.2f, 0.3f)); // exact, via toHexString
    }

    @Test
    void aFailedWriteLeavesTheWorldDirtyForRetry(@TempDir Path dir) throws IOException {
        // make the snapshot path an existing DIRECTORY: Files.write to it throws
        Path file = dir.resolve("world.snapshot");
        Files.createDirectories(file);
        WorldModel model = oneCellWorld(400L, 1f, 1f, 1f);
        WorldAutosaver saver = new WorldAutosaver(model, file, 0);

        model.applyMove(new CellID(400L), at(2f, 2f, 2f));
        saver.tick(0L);   // attempts and fails -- must not throw, must stay dirty

        assertThat(saver.isDirty()).isTrue();
    }

    @Test
    void flushWritesImmediatelyIgnoringTheThrottle(@TempDir Path dir) {
        WorldModel model = oneCellWorld(500L, 0f, 0f, 0f);
        Path file = dir.resolve("world.snapshot");
        WorldAutosaver saver = new WorldAutosaver(model, file, 10_000); // long interval

        model.applyMove(new CellID(500L), at(7f, 0f, 0f));
        saver.tick(1L);      // well inside the interval: no write yet
        assertThat(saver.isDirty()).isTrue();

        saver.flush(2L);     // shutdown flush: writes regardless of the interval
        assertThat(saver.isDirty()).isFalse();
    }

    @Test
    void revisionCountsOnlyMutationsNotReads() {
        WorldModel model = new WorldModel();
        CellID id = new CellID(600L);
        long base = model.revision();

        model.addCell(id, "some.client.Cell", "box", at(0f, 0f, 0f), null);
        assertThat(model.revision()).isEqualTo(base + 1);

        model.find(id);                 // a read bumps nothing
        model.contains(id);
        model.hierarchy();
        assertThat(model.revision()).isEqualTo(base + 1);

        assertThat(model.applyMove(id, at(1f, 0f, 0f))).isTrue();
        assertThat(model.revision()).isEqualTo(base + 2);

        assertThat(model.applyMove(new CellID(9999L), at(0f, 0f, 0f))).isFalse(); // unknown: no bump
        assertThat(model.revision()).isEqualTo(base + 2);

        assertThat(model.removeCell(model.getEnvironmentCellID())).isNull(); // root: no bump
        assertThat(model.revision()).isEqualTo(base + 2);

        model.removeCell(id);
        assertThat(model.revision()).isEqualTo(base + 3);
    }

    @Test
    void aNegativeIntervalIsRefusedAtConstruction(@TempDir Path dir) {
        // A negative throttle is a caller's slip, not a runtime condition: fail
        // at the door rather than let tick()'s "nowMs - lastSaveMs < intervalMs"
        // comparison silently never settle.
        WorldModel model = oneCellWorld(700L, 0f, 0f, 0f);

        assertThatThrownBy(() -> new WorldAutosaver(model, dir.resolve("world.snapshot"), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }
}
