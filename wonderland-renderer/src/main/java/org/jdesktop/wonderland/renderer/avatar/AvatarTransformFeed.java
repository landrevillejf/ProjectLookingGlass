/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: fold a cell's base transform + trigger state into
 * the row-major double[16] the Java 3D Transform3D is built from.
 *
 * M6a's ModelRenderer used WorldTransformMath.fromCellTransform() directly --
 * a cell's pose came from its CellTransform and nothing else. M6b's avatar
 * renderer needs three extra inputs on top: a goto override that
 * short-circuits the base transform entirely (the plan's "teleport to a
 * server-specified position/look" case), and a collision Y offset added on
 * top of whichever pose won.
 *
 * The output shape is exactly M6a's -- a flat row-major 16-array -- so the
 * probe-side Java 3D code can hand it to Transform3D(double[]) with no
 * format change. vecmath keeps the translation in the last COLUMN, i.e.
 * indices 3, 7, 11 in the flat layout; the Y offset therefore lands on
 * index 7 (m13). This is the same convention WorldTransformMath.translationOf
 * documents, so a reviewer who knows M6a already knows this.
 */
package org.jdesktop.wonderland.renderer.avatar;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.renderer.transform.WorldTransformMath;
import org.jogamp.vecmath.Matrix4d;
import org.jogamp.vecmath.Quat4d;
import org.jogamp.vecmath.Vector3d;

/** Compose the M6b avatar pose from a base transform plus trigger state. */
public final class AvatarTransformFeed {

    /** Translation column indices in the flat row-major layout. */
    private static final int TX = 3;
    private static final int TY = 7;
    private static final int TZ = 11;

    private AvatarTransformFeed() {
    }

    /**
     * Compose the row-major 16-array.
     *
     * <p>Rules, in order:
     * <ol>
     *   <li>Start from the base: {@link WorldTransformMath#fromCellTransform}
     *       applied to {@code base} (identity when base is null).</li>
     *   <li>If {@code state.hasGoto()}, REPLACE the base with a fresh
     *       Matrix4d built from the goto look + goto position (scale 1). The
     *       goto override is the plan's teleport path -- the cell's live
     *       transform stops driving the pose until the state clears it.</li>
     *   <li>Add {@code state.chooseYOffset()} to the translation column's Y
     *       entry. This applies to both branches (base pose and goto pose)
     *       so a peer pressed against a ceiling during a goto still lifts.</li>
     * </ol>
     *
     * @param base  the cell's live CellTransform, or null
     * @param state the trigger state; may be null (base pose only, no offset)
     * @return a fresh row-major 16-array; never null
     */
    public static double[] compose(CellTransform base, AvatarTriggerState state) {
        double[] row = state != null && state.hasGoto()
                ? fromGoto(state)
                : WorldTransformMath.fromCellTransform(base);
        if (state != null) {
            row[TY] += state.chooseYOffset();
        }
        return row;
    }

    /**
     * The goto pose: rotation = gotoLook (identity when null), translation =
     * gotoPosition, scale = 1.0. Built through the same vecmath Matrix4d
     * Quaternion/Vector3d/scale constructor M6a's WorldTransformMath uses so
     * the layout stays row-major-consistent across both branches.
     */
    private static double[] fromGoto(AvatarTriggerState state) {
        Vector3f pos = state.getGotoPosition();
        Quaternion look = state.getGotoLook();
        Quat4d q = look == null
                ? new Quat4d(0, 0, 0, 1)
                : new Quat4d(look.x, look.y, look.z, look.w);
        Vector3d t = new Vector3d(pos.x, pos.y, pos.z);
        Matrix4d m = new Matrix4d(q, t, 1.0d);
        return WorldTransformMath.toRowMajor(m);
    }
}
