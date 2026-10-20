/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the memory of where things were, headless.
 *
 * The promise is a round trip, and the tests are the shapes around it: a
 * first boot with no file, a re-seeded world missing a cell the snapshot
 * remembers, and a torn file that must NOT half-apply. The exactness of the
 * float text is asserted with adversarial decimals (0.1f, the one every
 * naive format betrays), because furniture that drifts a micrometre per boot
 * is a bug report six reboots from now.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CellSnapshotTest {

    @TempDir
    Path dir;

    private static CellTransform at(float x, float y, float z) {
        CellTransform t = new CellTransform();
        t.setTranslation(new Vector3f(x, y, z));
        return t;
    }

    private static Vector3f translationOf(WorldModel world, long cellID) {
        Vector3f out = new Vector3f();
        world.find(new CellID(cellID)).getTransform().getTranslation(out);
        return out;
    }

    /** A world with two content cells, one of them slid to an awkward place. */
    private static WorldModel furnished() {
        WorldModel world = new WorldModel();
        world.addCell(new CellID(1L), "some.client.Cell", "chair", at(0.1f, 0f, 3f), null);
        world.addCell(new CellID(2L), "some.client.Cell", "board", at(-7.25f, 2f, 0f), null);
        return world;
    }

    @Test
    void aFirstBootWithNoFileRestoresNothingAndDoesNotComplain(@TempDir Path empty)
            throws IOException {
        assertThat(CellSnapshot.restore(furnished(), empty.resolve("none.txt"))).isZero();
    }

    @Test
    void furnitureSurvivesTheRoundTripToTheMicrometre() throws IOException {
        Path file = dir.resolve("cells.txt");
        CellSnapshot.save(furnished(), file);

        // A fresh boot of the SAME world, everything at the origin, as the
        // seeder leaves it before any move has been replayed.
        WorldModel reboot = new WorldModel();
        reboot.addCell(new CellID(1L), "some.client.Cell", "chair", at(0f, 0f, 0f), null);
        reboot.addCell(new CellID(2L), "some.client.Cell", "board", at(0f, 0f, 0f), null);

        assertThat(CellSnapshot.restore(reboot, file)).isEqualTo(2);
        assertThat(translationOf(reboot, 1)).isEqualTo(new Vector3f(0.1f, 0f, 3f));
        assertThat(translationOf(reboot, 2)).isEqualTo(new Vector3f(-7.25f, 2f, 0f));
    }

    @Test
    void theEnvironmentRootIsNeverWritten() throws IOException {
        Path file = dir.resolve("cells.txt");

        CellSnapshot.save(furnished(), file);

        List<String> lines = Files.readAllLines(file);
        assertThat(lines.get(0)).isEqualTo(CellSnapshot.MAGIC);
        assertThat(lines).as("two content cells and the header, no environment").hasSize(3);
    }

    @Test
    void aCellTheReSeededWorldLostIsSkippedNotResurrected() throws IOException {
        Path file = dir.resolve("cells.txt");
        CellSnapshot.save(furnished(), file);

        WorldModel redesigned = new WorldModel(); // the art changed: no cells at all
        redesigned.addCell(new CellID(1L), "some.client.Cell", "chair", at(0f, 0f, 0f), null);

        assertThat(CellSnapshot.restore(redesigned, file))
                .as("cell 1 restored, cell 2 dropped with a skip, not an error")
                .isEqualTo(1);
        assertThat(redesigned.contains(new CellID(2L))).isFalse();
    }

    /** A quarter-degree tilt about Z, built the only way this shim allows. */
    private static Quaternion tilt() {
        return new Quaternion().fromAngleAxis(0.5f, new Vector3f(0f, 0f, 1f));
    }

    @Test
    void orientationSurvivesBecauseTheSnapshotOnlyClaimsToKnowWhere() throws IOException {
        WorldModel tilted = new WorldModel();
        tilted.addCell(new CellID(5L), "some.client.Cell", "ramp",
                new CellTransform(tilt(), new Vector3f(1f, 0f, 0f)), null);
        Path file = dir.resolve("cells.txt");
        CellSnapshot.save(tilted, file);

        WorldModel reboot = new WorldModel();
        reboot.addCell(new CellID(5L), "some.client.Cell", "ramp",
                new CellTransform(tilt(), new Vector3f(0f, 0f, 0f)), null);
        Quaternion before = new Quaternion();
        reboot.find(new CellID(5L)).getTransform().getRotation(before);

        CellSnapshot.restore(reboot, file);

        assertThat(translationOf(reboot, 5)).isEqualTo(new Vector3f(1f, 0f, 0f));
        Quaternion after = new Quaternion();
        reboot.find(new CellID(5L)).getTransform().getRotation(after);
        assertThat(after).as("still tilted exactly as before the restore")
                .isEqualTo(before);
    }

    @Test
    void aTornOrForeignFileIsRefusedRatherThanHalfApplied() throws IOException {
        Path foreign = dir.resolve("foreign.txt");
        Files.write(foreign, List.of("some other tool 1", "1 0 0 0"));

        assertThatThrownBy(() -> CellSnapshot.restore(furnished(), foreign))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a cell snapshot");

        Path torn = dir.resolve("torn.txt");
        Files.write(torn, List.of(CellSnapshot.MAGIC, "1 0.1f 0")); // three tokens
        assertThatThrownBy(() -> CellSnapshot.restore(furnished(), torn))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a place");

        Path junkNumber = dir.resolve("junk.txt");
        Files.write(junkNumber, List.of(CellSnapshot.MAGIC, "one 0 0 0"));
        assertThatThrownBy(() -> CellSnapshot.restore(furnished(), junkNumber))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("torn");
    }

    @Test
    void theWorstNaiveDecimalRoundTripsBitForBit() throws IOException {
        // 0.1f is the float every "%.2f"-style format betrays; toHexString +
        // parseFloat must return the identical bits, no tolerance needed.
        Path file = dir.resolve("precise.txt");
        WorldModel precise = new WorldModel();
        precise.addCell(new CellID(9L), "some.client.Cell", "needle",
                at(0.1f, 1.0E7f, -3.3333333f), null);
        CellSnapshot.save(precise, file);

        WorldModel reboot = new WorldModel();
        reboot.addCell(new CellID(9L), "some.client.Cell", "needle", at(0f, 0f, 0f), null);
        CellSnapshot.restore(reboot, file);

        assertThat(translationOf(reboot, 9)).isEqualTo(new Vector3f(0.1f, 1.0E7f, -3.3333333f));
    }

    @Test
    void anEmptyFileIsRefusedNotReadAsAnEmptyWorld(@TempDir Path empty) throws IOException {
        // A zero-byte file has no header line at all: that is not a first boot
        // (which has NO file), it is a truncated one, so it must be refused
        // rather than silently restoring nothing.
        Path zero = empty.resolve("zero.txt");
        Files.write(zero, List.of());

        assertThatThrownBy(() -> CellSnapshot.restore(furnished(), zero))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not a cell snapshot");
    }

    @Test
    void blankLinesAmongThePlacementsAreSkipped(@TempDir Path padded) throws IOException {
        // A hand-edited or CRLF-mangled file can carry blank lines; they are
        // gaps in the list, not placements, so restore steps over them and
        // still applies every real line.
        Path file = padded.resolve("cells.txt");
        Files.write(file, List.of(CellSnapshot.MAGIC, "", "1 0x0.0p0 0x0.0p0 0x1.8p1", "   "));

        WorldModel reboot = new WorldModel();
        reboot.addCell(new CellID(1L), "some.client.Cell", "chair", at(0f, 0f, 0f), null);

        assertThat(CellSnapshot.restore(reboot, file)).isEqualTo(1);
        assertThat(translationOf(reboot, 1)).isEqualTo(new Vector3f(0f, 0f, 3f));
    }
}
