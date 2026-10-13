/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the trigger state's pure visual decisions.
 *
 * Everything the AvatarRenderer reads off this class -- the colour it writes
 * into every Shape3D's Material, the Y offset it folds into the pose matrix,
 * the goto override it treats as a short-circuit -- is decided here and
 * nowhere else. Pinning those decisions in a headless JVM is what makes the
 * probe half (Jogamp class construction) the only uncovered surface.
 */
package org.jdesktop.wonderland.renderer.avatar;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class AvatarTriggerStateTest {

    @Test
    void aFreshStateIsUnpressedNeutralGreyWithNoOffsetAndNoGoto() {
        AvatarTriggerState s = new AvatarTriggerState();

        assertThat(s.isPressed()).isFalse();
        assertThat(s.isCollisionOn()).isFalse();
        assertThat(s.hasGoto()).isFalse();
        assertThat(s.getLastAnimationName()).isNull();
        assertThat(s.chooseDiffuseColour())
                .containsExactly(AvatarTriggerState.COLOUR_NEUTRAL[0],
                        AvatarTriggerState.COLOUR_NEUTRAL[1],
                        AvatarTriggerState.COLOUR_NEUTRAL[2],
                        AvatarTriggerState.COLOUR_NEUTRAL[3])
                .hasSize(4);
        assertThat(s.chooseYOffset()).isZero();
    }

    @Test
    void pressingTheTriggerFlipsTheColourToThePressedConstant() {
        AvatarTriggerState s = new AvatarTriggerState();

        s.applyTrigger(0, true, "wave");

        assertThat(s.isPressed()).isTrue();
        assertThat(s.getLastAnimationName()).isEqualTo("wave");
        assertThat(s.chooseDiffuseColour())
                .containsExactly(AvatarTriggerState.COLOUR_PRESSED[0],
                        AvatarTriggerState.COLOUR_PRESSED[1],
                        AvatarTriggerState.COLOUR_PRESSED[2],
                        AvatarTriggerState.COLOUR_PRESSED[3]);
    }

    @Test
    void unpressingTheTriggerRestoresTheNeutralColour() {
        AvatarTriggerState s = new AvatarTriggerState();
        s.applyTrigger(0, true, "wave");
        assertThat(s.isPressed()).isTrue();

        s.applyTrigger(0, false, "wave");

        assertThat(s.isPressed()).isFalse();
        assertThat(s.chooseDiffuseColour()).containsExactly(
                AvatarTriggerState.COLOUR_NEUTRAL[0],
                AvatarTriggerState.COLOUR_NEUTRAL[1],
                AvatarTriggerState.COLOUR_NEUTRAL[2],
                AvatarTriggerState.COLOUR_NEUTRAL[3]);
    }

    @Test
    void anActiveCollisionReportsItsHeightAsTheYOffset() {
        AvatarTriggerState s = new AvatarTriggerState();

        s.applyCollision(0.25f, true);

        assertThat(s.isCollisionOn()).isTrue();
        assertThat(s.getCollisionHeight()).isCloseTo(0.25f, offset(1e-6f));
        assertThat(s.chooseYOffset()).isCloseTo(0.25d, offset(1e-9));
    }

    @Test
    void turningTheCollisionOffRemovesTheOffsetWithoutErasingTheHeight() {
        // The plan asks explicitly for this case: the height field survives
        // so a subsequent on-event with the same height is stable; only the
        // flag decides whether the offset applies.
        AvatarTriggerState s = new AvatarTriggerState();
        s.applyCollision(0.5f, true);
        assertThat(s.chooseYOffset()).isCloseTo(0.5d, offset(1e-9));

        s.applyCollision(0.5f, false);

        assertThat(s.isCollisionOn()).isFalse();
        assertThat(s.getCollisionHeight()).isCloseTo(0.5f, offset(1e-6f));
        assertThat(s.chooseYOffset()).isZero();
    }

    @Test
    void aNegativeCollisionHeightIsCarriedThroughAsANegativeOffset() {
        // A peer walking under a low lintel: the server sends a negative
        // height to squash the body down; the M6b renderer must not clamp it.
        AvatarTriggerState s = new AvatarTriggerState();
        s.applyCollision(-0.3f, true);

        assertThat(s.chooseYOffset()).isCloseTo(-0.3d, offset(1e-6));
    }

    @Test
    void anApplyGotoOverridesThePoseAndSurvivesUntilCleared() {
        AvatarTriggerState s = new AvatarTriggerState();
        Vector3f pos = new Vector3f(1f, 2f, 3f);
        Quaternion look = new Quaternion();
        look.fromAngleAxis((float) Math.PI / 2f, new Vector3f(0f, 1f, 0f));

        s.applyGoto(pos, look);

        assertThat(s.hasGoto()).isTrue();
        assertThat(s.getGotoPosition()).isNotNull();
        assertThat(s.getGotoPosition().x).isCloseTo(1f, offset(1e-6f));
        assertThat(s.getGotoLook()).isNotNull();
    }

    @Test
    void aNullGotoPositionClearsAnyPriorOverride() {
        AvatarTriggerState s = new AvatarTriggerState();
        s.applyGoto(new Vector3f(1f, 2f, 3f), null);
        assertThat(s.hasGoto()).isTrue();

        s.applyGoto(null, null);

        assertThat(s.hasGoto()).isFalse();
        assertThat(s.getGotoPosition()).isNull();
        assertThat(s.getGotoLook()).isNull();
    }

    @Test
    void aGotoWithNullLookClearsTheLookButKeepsThePosition() {
        // The plan's goto case where the server only specifies a destination:
        // the pose override still fires (hasGoto() is true, position wins),
        // and the look stays identity so the caller falls back to the base
        // orientation. hasGoto() only depends on position, not look.
        AvatarTriggerState s = new AvatarTriggerState();
        s.applyGoto(new Vector3f(4f, 5f, 6f), null);

        assertThat(s.hasGoto()).isTrue();
        assertThat(s.getGotoPosition().x).isCloseTo(4f, offset(1e-6f));
        assertThat(s.getGotoLook()).isNull();
    }

    @Test
    void theGotoOverridesIndependentlyOfTheCollisionState() {
        // Both signals can be live at once -- a peer teleports via goto while
        // still inside a collider. The state model keeps them orthogonal; the
        // decision about how they compose lives in AvatarTransformFeed (and
        // is tested there).
        AvatarTriggerState s = new AvatarTriggerState();
        s.applyCollision(0.4f, true);
        s.applyGoto(new Vector3f(0f, 10f, 0f), null);

        assertThat(s.hasGoto()).isTrue();
        assertThat(s.chooseYOffset()).isCloseTo(0.4d, offset(1e-6));
    }

    @Test
    void getGotoPositionAndLookReturnDefensiveCopiesNotTheInternals() {
        // Callers who mutate the returned Vector3f (jme's are mutable) must
        // not be able to corrupt the state; the plan asks for the "state is
        // owned by the renderer" contract explicitly.
        AvatarTriggerState s = new AvatarTriggerState();
        s.applyGoto(new Vector3f(7f, 8f, 9f), null);

        Vector3f read = s.getGotoPosition();
        read.x = 999f;

        assertThat(s.getGotoPosition().x).isCloseTo(7f, offset(1e-6f));
    }

    @Test
    void chooseDiffuseColourReturnsAFreshArraySoTheCallerCannotAliasTheConstants() {
        // The constants are public for documentation; handing the same array
        // back twice would let a caller mutate the shared constant.
        AvatarTriggerState s = new AvatarTriggerState();
        float[] a = s.chooseDiffuseColour();
        a[0] = -1f;

        assertThat(s.chooseDiffuseColour()[0]).isNotEqualTo(-1f);
    }
}
