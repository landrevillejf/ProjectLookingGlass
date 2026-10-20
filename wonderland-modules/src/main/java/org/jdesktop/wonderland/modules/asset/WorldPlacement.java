/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (assetmgr): a world's cells placed where the world put them.
 *
 * A stock world is a tree, and its files say so by where they sit. The meeting
 * complex is one cell, {@code OrientationWorld-18-wlc.xml}, and everything in it
 * -- the landing pads, the portal, the whiteboard, the PDF viewer -- lives in
 * {@code OrientationWorld-18-wld/}, the directory named after its parent. The
 * child's own position component is measured <em>from its parent</em>: the field
 * reads {@code x=32, z=75} and the complex it belongs to sits at
 * {@code x=-38, z=-53}, so the field is at {@code x=-6, z=22} in the world.
 *
 * Until now the loader handed every cell to the server flat and the server seeded
 * it as though those local numbers were world coordinates. The result is the
 * meeting complex the world browser showed: two posts and a plane in an empty
 * blue field, because the entire contents of the world had been shifted ninety
 * metres away from the thing that contains them, and the camera -- framed on what
 * it could see -- was looking at where the ground should have been. It is also
 * why a world with forty-one objects in it can look like it has four.
 *
 * This class walks the tree and multiplies the matrices out. A cell's absolute
 * placement is its parent's rotation applied to its own stretch and position,
 * added to its parent's position, composed all the way up -- the same order the
 * renderer already uses for one cell and its art (scale, then turn, then move).
 *
 * It is deliberately tolerant of a broken tree, because a world file is authored
 * by a person and shipped in a jar someone else dropped in a directory:
 *   - a cell whose parent is missing is placed by its own numbers, which is what
 *     flat seeding did, so a half-read world is no worse than before;
 *   - a cycle is broken at the repeat rather than hanging the loader, since a
 *     cycle in a shipped world is a bug in the world and not a reason to lose the
 *     twenty cells around it that are fine;
 *   - a cell with no position component contributes the identity, so its children
 *     still land where their author put them.
 */
package org.jdesktop.wonderland.modules.asset;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;

/** Resolves a world's parent-relative cell placements to world coordinates. */
public final class WorldPlacement {

    /**
     * One cell's placement in the world rather than in its parent: where it is,
     * which way it faces, how far its art is stretched.
     */
    public record Absolute(Vector3f translation, Quaternion rotation, Vector3f scale) {

        /** The identity: authored nowhere, unturned, unstretched. */
        public static Absolute identity() {
            return new Absolute(new Vector3f(), new Quaternion(),
                    new Vector3f(1f, 1f, 1f));
        }
    }

    /**
     * The authored stretch, or the identity when the file records none. A missing
     * scale is not a zero scale: reading an absent {@code <scale>} as
     * {@code (0,0,0)} collapses the cell to a point, and a degenerate matrix is
     * the one thing that makes Java 3D refuse the whole branch at attach time --
     * which costs a world every object in it to punish one empty element.
     */
    public static Vector3f scalingOf(PositionComponentServerState position) {
        Vector3f scale = (position == null) ? null : position.getScaling();
        return (scale == null) ? new Vector3f(1f, 1f, 1f) : scale;
    }

    /**
     * Resolve every cell's world placement.
     *
     * @param cells the world's cells, each keyed by its own asset path and by its
     *              parent's path (null for a root)
     * @return every input path's absolute placement, in the input's order
     */
    public static Map<String, Absolute> resolve(List<Cell> cells) {
        Map<String, Cell> byPath = new LinkedHashMap<>();
        for (Cell cell : cells) {
            if (cell != null && cell.path() != null) {
                byPath.put(cell.path(), cell);
            }
        }
        Map<String, Absolute> resolved = new LinkedHashMap<>();
        for (String path : byPath.keySet()) {
            resolved.put(path, absoluteOf(path, byPath, new HashSet<>()));
        }
        return resolved;
    }

    /**
     * One cell's placement, with its ancestors folded in first. The guard set is
     * the cycle breaker: a path already on the way down is a parent that names its
     * own child, and the walk stops there. Each cell has exactly one parent, so the
     * recursion is a single upward chain that visits every node at most once --
     * there is no shared sub-result to memoise, and the walk is already linear in
     * the depth of the world.
     */
    private static Absolute absoluteOf(String path, Map<String, Cell> byPath,
            Set<String> walking) {
        Cell cell = byPath.get(path);
        if (cell == null || !walking.add(path)) {
            return Absolute.identity();
        }
        Absolute local = localOf(cell.state());
        String parent = cell.parentPath();
        Absolute result = (parent == null || parent.equals(path))
                ? local
                : compose(absoluteOf(parent, byPath, walking), local);
        walking.remove(path);
        return result;
    }

    /**
     * Compose a child onto its parent: {@code T_parent · R_parent · S_parent ·
     * T_child · R_child · S_child}, the order the renderer uses for one cell and
     * its art. The child's translation is stretched and turned by the parent
     * before it is added, because a child authored half a parent's scale away is
     * half as far from the parent's origin as its numbers say.
     */
    static Absolute compose(Absolute parent, Absolute child) {
        Vector3f stretched = new Vector3f(child.translation());
        stretched.multLocal(parent.scale());
        Vector3f turned = new Vector3f(stretched);
        parent.rotation().multLocal(turned);
        // addLocal rather than add: this shim's Vector3f.add mutates its receiver,
        // which is not what the name promises to anyone remembering jMonkeyEngine.
        Vector3f translation = turned.addLocal(parent.translation());
        Quaternion rotation = parent.rotation().mult(child.rotation(), new Quaternion());
        Vector3f scale = new Vector3f(parent.scale());
        scale.multLocal(child.scale());
        return new Absolute(translation, rotation, scale);
    }

    /** One cell's own placement, as its file records it. */
    static Absolute localOf(CellServerState state) {
        Object component = (state == null) ? null
                : state.getComponentServerState(PositionComponentServerState.class);
        if (!(component instanceof PositionComponentServerState position)) {
            return Absolute.identity();
        }
        Vector3f translation = position.getTranslation();
        Quaternion rotation = position.getRotation();
        return new Absolute(
                (translation == null) ? new Vector3f() : new Vector3f(translation),
                (rotation == null) ? new Quaternion() : new Quaternion(rotation),
                new Vector3f(scalingOf(position)));
    }

    /**
     * The parent-relative placement of one cell, flattened to the numbers a
     * descriptor carries: translation, heading about the vertical axis, and
     * stretch. Heading is read off the composed quaternion, so a child turned
     * inside a parent that is itself turned arrives facing the way its author
     * meant -- which is the fact a top-down plan of a town needs to get right.
     */
    public static double yawOf(Absolute absolute) {
        if (absolute == null) {
            return 0.0;
        }
        Quaternion q = absolute.rotation();
        float x = q.x;
        float y = q.y;
        float z = q.z;
        float w = q.w;
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (length < 1e-9) {
            return 0.0;
        }
        x /= length;
        y /= length;
        z /= length;
        w /= length;
        return Math.atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + x * x));
    }

    /** The cells to place, in any order; see {@link Cell}. */
    public static List<Cell> cellsOf(List<WorldContents.LoadedCell> loaded) {
        List<Cell> out = new ArrayList<>();
        for (WorldContents.LoadedCell cell : loaded) {
            out.add(new Cell(cell.path(), cell.parentPath(), cell.state()));
        }
        return out;
    }

    /** One cell as the tree needs it: its path, its parent's path, its state. */
    public record Cell(String path, String parentPath, CellServerState state) {
    }

    private WorldPlacement() {
    }
}
