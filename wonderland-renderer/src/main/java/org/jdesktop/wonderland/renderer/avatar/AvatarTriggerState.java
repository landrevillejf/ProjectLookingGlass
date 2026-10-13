/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the trigger-driven mutable state an avatar renderer
 * keeps between cell updates.
 *
 * The 2006 AvatarJME owned a jME Animator and drove skeletal clips from
 * triggerAction's animationName string. This fork's plan (see
 * documentation/wonderland/docs/RENDERER.md M7 descope note) keeps a much
 * smaller state model -- three orthogonal signals that map to a visible change
 * without an animator subsystem:
 *
 *   - pressed / lastAnimationName  : the last trigger(pressed) event; a
 *     pressed avatar tints yellow, an unpressed one stays neutral.
 *   - collisionHeight / collisionOn: the last triggerCollision(h, on) event;
 *     an active collision lifts the avatar by the height along world Y.
 *   - gotoPosition / gotoLook      : the last triggerGoto(pos, q) event;
 *     when present it short-circuits the base CellTransform for the pose and
 *     takes precedence over any live movement until it is cleared.
 *
 * The class is pure vecmath/jme math. No Jogamp class is constructed, so the
 * whole thing is unit-testable in a headless JVM -- which is what the plan
 * asks for ("state-driven visual decisions are here"). The AvatarRenderer
 * owns one instance and reads chooseDiffuseColour() / chooseYOffset() / the
 * goto pair each time it rebuilds the appearance or the Transform3D feed.
 */
package org.jdesktop.wonderland.renderer.avatar;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;

/** Mutable per-avatar trigger state the M6b renderer reads every frame. */
public final class AvatarTriggerState {

    /** RGBA in [0..1], matching the Java 3D Color4f the appearance factory wants. */
    public static final float[] COLOUR_NEUTRAL = {0.55f, 0.55f, 0.55f, 1.0f};
    public static final float[] COLOUR_PRESSED = {0.95f, 0.85f, 0.20f, 1.0f};

    private boolean pressed;
    private float collisionHeight;
    private boolean collisionOn;
    private String lastAnimationName;
    private Vector3f gotoPosition;
    private Quaternion gotoLook;

    /**
     * Apply a trigger(pressed, animationName) event. The animation name is
     * stored for M7 (real skeletal playback) but the M6b renderer only
     * consumes the pressed boolean for its colour decision.
     */
    public void applyTrigger(int triggerIndex, boolean pressedNow, String animationName) {
        this.pressed = pressedNow;
        this.lastAnimationName = animationName;
        // triggerIndex is intentionally not stored -- the plan's minimal
        // subset does not distinguish trigger 0 from trigger 1 visually.
    }

    /** Apply a triggerCollision(height, on) event. */
    public void applyCollision(float height, boolean on) {
        this.collisionHeight = height;
        this.collisionOn = on;
    }

    /** Apply a triggerGoto(worldPosition, look) event. Null position clears. */
    public void applyGoto(Vector3f worldPosition, Quaternion look) {
        if (worldPosition == null) {
            clearGoto();
            return;
        }
        this.gotoPosition = new Vector3f(worldPosition);
        this.gotoLook = look == null ? null : new Quaternion(look);
    }

    /** Remove a goto override so the base CellTransform applies again. */
    public void clearGoto() {
        this.gotoPosition = null;
        this.gotoLook = null;
    }

    /** Whether a goto override is currently in effect. */
    public boolean hasGoto() {
        return gotoPosition != null;
    }

    /**
     * The diffuse colour the appearance should show. Yellow when pressed,
     * neutral grey otherwise. Copied so a caller mutating the array (Java 3D
     * hands back its internal Color4f in some APIs) cannot corrupt state.
     */
    public float[] chooseDiffuseColour() {
        float[] src = pressed ? COLOUR_PRESSED : COLOUR_NEUTRAL;
        return new float[] {src[0], src[1], src[2], src[3]};
    }

    /**
     * The world-Y offset to add to the pose translation. Zero unless the
     * collision flag is on; when on, the last reported collision height --
     * which can be negative if a peer walks under a low lintel.
     */
    public double chooseYOffset() {
        return collisionOn ? (double) collisionHeight : 0.0d;
    }

    public boolean isPressed() {
        return pressed;
    }

    public float getCollisionHeight() {
        return collisionHeight;
    }

    public boolean isCollisionOn() {
        return collisionOn;
    }

    public String getLastAnimationName() {
        return lastAnimationName;
    }

    /** A defensive copy; null when no goto override is active. */
    public Vector3f getGotoPosition() {
        return gotoPosition == null ? null : new Vector3f(gotoPosition);
    }

    /** A defensive copy; null when no goto override is active. */
    public Quaternion getGotoLook() {
        return gotoLook == null ? null : new Quaternion(gotoLook);
    }
}
