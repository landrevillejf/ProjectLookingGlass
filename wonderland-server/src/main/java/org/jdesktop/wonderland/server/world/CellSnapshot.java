/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the world remembers where things were put.
 *
 * Persistence, at honest scope: what a multiplayer desktop actually loses
 * today is the MOVABLE state -- the chair someone slid, the notice board
 * they hung -- because the WorldModel is seeded fresh from the module's art
 * every boot and every applyMove since then lives only in RAM. This class
 * snapshots each cell's TRANSLATION to one small text file and restores it
 * through the model's own applyMove, which is the same authoritative path a
 * wire MovableMessage takes -- so a restored world is a world the router
 * already knows how to broadcast, not a special case.
 *
 * Translations only, on purpose. Rotation and scale are cell-transform
 * facts this snapshot format deliberately does not claim, because no current
 * port code writes them -- persisting them would mean inventing a format for
 * state nothing produces. The header line says what a snapshot IS
 * ("lg3d-cellsnapshot 1") so a future full-transform version can refuse an
 * old file instead of misreading it, and the numbers are Float.toHexString
 * -- the only text form of a float that round-trips exactly, because a
 * restored chair at 0.1 mm off its spot drifts across sessions.
 *
 * A missing file is the normal case -- a first boot -- and restores zero
 * cells without complaint; a MALFORMED file throws, because half-applying a
 * torn snapshot moves some furniture and not the rest, and a deployment that
 * cannot tell the two apart is a deployment that trusts its storage.
 */
package org.jdesktop.wonderland.server.world;

import com.jme.math.Vector3f;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;

/** Save/restore of the WorldModel's movable translations (roadmap P5.4). */
public final class CellSnapshot {

    static final String MAGIC = "lg3d-cellsnapshot 1";

    private CellSnapshot() {
    }

    /**
     * Write every hosted cell's translation -- the environment root excepted,
     * since it is the frame the world is hung ON and never moves. One line
     * per cell: {@code <cellID> <tx> <ty> <tz>}.
     */
    public static void save(WorldModel model, Path file) throws IOException {
        Vector3f scratch = new Vector3f();
        List<String> lines = new ArrayList<>();
        lines.add(MAGIC);
        CellID environment = model.getEnvironmentCellID();
        for (WorldCell cell : model.hierarchy()) {
            if (cell.getCellID().equals(environment)) {
                continue;
            }
            cell.getTransform().getTranslation(scratch);
            lines.add(cell.getCellID() + " "
                    + Float.toHexString(scratch.x) + " "
                    + Float.toHexString(scratch.y) + " "
                    + Float.toHexString(scratch.z));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /**
     * Replay a snapshot through applyMove: a cell the re-seeded world no
     * longer hosts is SKIPPED (the art changed under the snapshot -- a
     * warning for the log, not a lost boot), and every applied move lands on
     * the cell's CURRENT rotation and scale, because the snapshot only ever
     * claims to know where, not how it stands. Returns the number applied.
     */
    public static int restore(WorldModel model, Path file) throws IOException {
        if (!Files.exists(file)) {
            return 0; // first boot is a fact of life, not an error
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !MAGIC.equals(lines.get(0).trim())) {
            throw new IOException("not a cell snapshot: " + file);
        }
        int applied = 0;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] t = line.split(" ");
            if (t.length != 4) {
                throw new IOException("snapshot line " + (i + 1) + " is not a place: " + line);
            }
            CellID cellID;
            float[] at = new float[3];
            try {
                cellID = new CellID(Long.parseLong(t[0]));
                for (int k = 0; k < 3; k++) {
                    at[k] = Float.parseFloat(t[1 + k]); // toHexString reads back exactly
                }
            } catch (NumberFormatException torn) {
                throw new IOException("snapshot line " + (i + 1) + " is torn: " + line, torn);
            }
            WorldCell existing = model.find(cellID);
            if (existing == null) {
                continue; // the world re-seeded past this cell
            }
            CellTransform moved = existing.getTransform().clone(new CellTransform());
            moved.setTranslation(new Vector3f(at[0], at[1], at[2]));
            // find() above proved the cell is hosted, and applyMove returns false
            // only for an absent cell -- so the move always lands here. Guarding it
            // would be an unreachable branch, not coverage.
            model.applyMove(cellID, moved);
            applied++;
        }
        return applied;
    }
}
