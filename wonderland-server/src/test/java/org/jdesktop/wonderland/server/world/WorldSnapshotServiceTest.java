/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the thin lifecycle bean, tested at its two seams.
 *
 * WorldAutosaver owns every judgement and is proven against an injected instant;
 * this class has almost none -- it reads a Spring property, decides whether to
 * exist at all, restores once on boot, hands the saver a real clock on the beat
 * and flushes on shutdown. What is worth pinning here is exactly that: that a
 * blank path keeps the whole thing inert (so an unset switch is the pre-P5.4
 * server), that a named path round-trips the world through boot and beat, and
 * that a torn snapshot is forgotten rather than failing the boot -- the three
 * branches an operator actually lands in.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.math.Vector3f;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class WorldSnapshotServiceTest {

    private static CellTransform at(float x, float y, float z) {
        CellTransform t = new CellTransform();
        t.setTranslation(new Vector3f(x, y, z));
        return t;
    }

    private static WorldModel chairWorld(long idValue, float x, float y, float z) {
        WorldModel model = new WorldModel();
        model.addCell(new CellID(idValue), "some.client.Cell", "chair", at(x, y, z), null);
        return model;
    }

    private static Vector3f translationOf(WorldModel model, long idValue) {
        Vector3f out = new Vector3f();
        model.find(new CellID(idValue)).getTransform().getTranslation(out);
        return out;
    }

    @Test
    void aBlankPathLeavesTheServiceInert(@TempDir Path dir) {
        WorldModel model = chairWorld(1L, 0f, 0f, 0f);
        // no path named: the default pre-P5.4 server -- nothing restored, no file
        WorldSnapshotService service = new WorldSnapshotService(model, "", 1000);

        service.start();
        model.applyMove(new CellID(1L), at(3f, 3f, 3f));
        service.beat();
        service.stop();

        assertThat(dir.toFile().list()).isEmpty(); // the disabled path never touches disk
    }

    @Test
    void aNamedPathRestoresOnBootAndRewritesOnTheBeat(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("world.snapshot");
        // a prior session slid the chair to (5,5,5) and saved it
        WorldModel saved = chairWorld(2L, 0f, 0f, 0f);
        saved.applyMove(new CellID(2L), at(5f, 5f, 5f));
        CellSnapshot.save(saved, file);

        // boot: a fresh world seeded from art (chair back at origin), then restored
        WorldModel boot = chairWorld(2L, 0f, 0f, 0f);
        WorldSnapshotService service = new WorldSnapshotService(boot, file.toString(), 1000);
        service.start();
        assertThat(translationOf(boot, 2L)).isEqualTo(new Vector3f(5f, 5f, 5f));

        // someone moves it this session; the next real-clock beat writes it down
        boot.applyMove(new CellID(2L), at(8f, 8f, 8f));
        service.beat();
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("0x1.0p3"); // 8.0

        service.stop(); // clean shutdown flush (nothing left dirty here)
    }

    @Test
    void aTornSnapshotIsForgottenRatherThanFatal(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("world.snapshot");
        Files.writeString(file, "this is not a cell snapshot\n", StandardCharsets.UTF_8);

        WorldModel model = chairWorld(3L, 1f, 1f, 1f);
        WorldSnapshotService service = new WorldSnapshotService(model, file.toString(), 1000);

        // start must not throw over unreadable memory; it keeps the fresh world and
        // the baseline flush rewrites the file into a real snapshot
        service.start();
        assertThat(translationOf(model, 3L)).isEqualTo(new Vector3f(1f, 1f, 1f));
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .startsWith(CellSnapshot.MAGIC);
        service.stop();
    }
}
