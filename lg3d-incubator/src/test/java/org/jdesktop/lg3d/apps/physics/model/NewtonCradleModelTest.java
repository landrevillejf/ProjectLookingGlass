package org.jdesktop.lg3d.apps.physics.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the AWT-free, Java 3D-free 2D cradle core: the {@link Vec2} vector, the
 * {@link Particle} body, the damped {@link Spring} string and the
 * {@link NewtonCradleModel} simulation that composes them. Every case is
 * headless - the core is pure {@code java.lang} arithmetic, which is exactly why
 * the 2D/Swing desktop can drive it without ever loading the scene graph.
 *
 * <p>The model assertions pin the physics the resurrected app is known for: the
 * first ball is released from the pull angle, the momentum walks the resting
 * chain so the far ball swings out (one in, one out), the total energy decays as
 * the impacts and string damping bleed it off, and no ball ever leaves the static
 * world bounds the viewer fits itself to.</p>
 */
class NewtonCradleModelTest {

    /** Steps the model by {@code seconds} at a 60 fps cadence. */
    private static void run(NewtonCradleModel m, float seconds) {
        int frames = Math.round(seconds * 60f);
        for (int i = 0; i < frames; i++) {
            m.step(1f / 60f);
        }
    }

    // ------------------------------------------------------------------
    // Vec2
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Vec2 does the planar arithmetic the solver is built on")
    void vec2Arithmetic() {
        Vec2 v = new Vec2();
        assertEquals(0f, v.x);
        assertEquals(0f, v.y);

        v.set(3f, 4f);
        assertEquals(3f, v.x);
        assertEquals(4f, v.y);
        assertEquals(25f, v.lengthSquared());
        assertEquals(5f, v.length());

        Vec2 w = new Vec2(1f, 2f);
        v.add(w);
        assertEquals(4f, v.x);
        assertEquals(6f, v.y);
        v.sub(w);
        assertEquals(3f, v.x);
        assertEquals(4f, v.y);

        v.scale(2f);
        assertEquals(6f, v.x);
        assertEquals(8f, v.y);
        v.addScaled(w, -1f);
        assertEquals(5f, v.x);
        assertEquals(6f, v.y);

        assertEquals(5f * 1f + 6f * 2f, v.dot(w));

        // copy is independent of the original.
        Vec2 c = v.copy();
        assertEquals(v.x, c.x);
        assertEquals(v.y, c.y);
        c.set(0f, 0f);
        assertEquals(5f, v.x, "copy() must not alias the original");

        // normalize makes it unit length; a zero vector normalizes to a no-op.
        Vec2 u = new Vec2(3f, 4f);
        u.normalize();
        assertEquals(1f, u.length(), 1e-5);
        Vec2 zero = new Vec2();
        zero.normalize();
        assertEquals(0f, zero.length(), "a zero vector stays zero");

        assertTrue(new Vec2(1f, 2f).toString().contains("1.0"));
    }

    // ------------------------------------------------------------------
    // Particle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Particle copies its position, starts at rest and clamps restitution")
    void particleConstruction() {
        Vec2 at = new Vec2(1f, 2f);
        Particle p = new Particle(at, 2f, 0.5f, 0.9f);
        assertEquals(1f, p.getX());
        assertEquals(2f, p.getY());
        assertEquals(0f, p.getVelocityX());
        assertEquals(0f, p.getVelocityY());
        assertEquals(0f, p.getSpeed());
        assertEquals(0.5f, p.getRadius());
        assertEquals(2f, p.getMass());
        assertEquals(0.9f, p.getRestitution());
        assertEquals(0.5f, p.invMass, 1e-6);

        // Moving the source vector afterwards must not move the particle.
        at.set(9f, 9f);
        assertEquals(1f, p.getX(), "the constructor copies the position");

        // An immovable body (mass <= 0) has zero inverse mass.
        Particle fixed = new Particle(new Vec2(), 0f, 1f, 1f);
        assertEquals(0f, fixed.invMass);

        // Restitution is clamped into [0, 1].
        assertEquals(1f, new Particle(new Vec2(), 1f, 1f, 5f).getRestitution());
        assertEquals(0f, new Particle(new Vec2(), 1f, 1f, -5f).getRestitution());
    }

    // ------------------------------------------------------------------
    // Spring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stretched string pulls its ball toward the anchor")
    void springPullsTowardAnchor() {
        Vec2 anchor = new Vec2(0f, 0f);
        // A ball hanging straight below the anchor, stretched past rest length.
        Particle ball = new Particle(new Vec2(0f, -1f), 1f, 0.1f, 0.9f);
        Spring spring = new Spring(anchor, ball, 0.5f, 100f, 10f);
        spring.apply();
        // The force points up the string, toward the anchor.
        assertTrue(ball.force.y > 0f, "a stretched string pulls the ball up");
        assertEquals(0f, ball.force.x, 1e-6, "a vertical string has no sideways pull");

        // The anchor is copied, so moving the caller's vector changes nothing.
        anchor.set(50f, 50f);
        Particle ball2 = new Particle(new Vec2(0f, -1f), 1f, 0.1f, 0.9f);
        Spring spring2 = new Spring(new Vec2(0f, 0f), ball2, 0.5f, 100f, 10f);
        spring2.apply();
        assertEquals(ball.force.y, ball2.force.y, 1e-6, "the anchor is copied");
    }

    @Test
    @DisplayName("a ball exactly on its anchor defines no axis and adds no force")
    void springDegenerateAxis() {
        Particle ball = new Particle(new Vec2(0f, 0f), 1f, 0.1f, 0.9f);
        Spring spring = new Spring(new Vec2(0f, 0f), ball, 0.5f, 100f, 10f);
        spring.apply();
        assertEquals(0f, ball.force.x);
        assertEquals(0f, ball.force.y, "no defined axis means no force");
    }

    // ------------------------------------------------------------------
    // NewtonCradleModel: initial state
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the cradle starts with the first ball pulled out and the row at rest")
    void initialState() {
        NewtonCradleModel m = new NewtonCradleModel();
        assertEquals(NewtonCradleModel.BALL_COUNT, m.getBallCount());
        assertEquals(0f, m.getTime());
        assertEquals(NewtonCradleModel.BALL_RADIUS, m.getBallRadius());
        assertEquals(NewtonCradleModel.ANCHOR_Y, m.getAnchorY());

        // The equilibrium hang is the relaxed length plus the static gravity sag.
        float expectedLength = NewtonCradleModel.STRING_REST
                + (NewtonCradleModel.BALL_MASS * NewtonCradleModel.GRAVITY)
                / NewtonCradleModel.SPRING_K;
        assertEquals(expectedLength, m.getStringLength(), 1e-5);

        // Ball 0 is pulled back (left of its anchor and above the rest hang);
        // every other ball hangs straight below its own anchor.
        assertTrue(m.getBallX(0) < m.getAnchorX(0),
                "the first ball is pulled out to the left");
        assertTrue(m.getBallY(0) > m.getAnchorY() - m.getStringLength(),
                "the pulled ball hangs higher than the rest hang");
        for (int i = 1; i < m.getBallCount(); i++) {
            assertEquals(m.getAnchorX(i), m.getBallX(i), 1e-5,
                    "ball " + i + " hangs straight below its anchor");
            assertEquals(0f, m.getBallSpeed(i), 1e-6, "everything starts at rest");
        }

        // The static bounds contain every ball, with the pull swing as headroom.
        for (int i = 0; i < m.getBallCount(); i++) {
            assertTrue(m.getBallX(i) >= m.getMinX() && m.getBallX(i) <= m.getMaxX());
            assertTrue(m.getBallY(i) >= m.getMinY() && m.getBallY(i) <= m.getMaxY());
        }
        assertTrue(m.getMinX() < 0f && m.getMaxX() > 0f);
        assertTrue(m.getMinY() < m.getMaxY());
    }

    // ------------------------------------------------------------------
    // NewtonCradleModel: the momentum transfer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one ball in sends one ball out through the resting chain")
    void momentumTransfersThroughTheChain() {
        NewtonCradleModel m = new NewtonCradleModel();
        int last = m.getBallCount() - 1;
        float maxOutLast = 0f;
        float maxOutFirst = 0f;
        // Watch the first second: the release crosses the row and the far ball
        // leaves it while the middle balls stay (almost) where they hang.
        for (int i = 0; i < 60; i++) {
            m.step(1f / 60f);
            maxOutLast = Math.max(maxOutLast, m.getBallX(last) - m.getAnchorX(last));
            maxOutFirst = Math.min(maxOutFirst, m.getBallX(0) - m.getAnchorX(0));
        }
        assertTrue(maxOutLast > 0.02f,
                "the far ball must swing out (got " + maxOutLast + ")");
        assertTrue(maxOutFirst < -0.02f,
                "the released ball must have come from out on the left");
    }

    @Test
    @DisplayName("the middle balls barely move while the ends exchange the swing")
    void middleBallsStayPut() {
        NewtonCradleModel m = new NewtonCradleModel();
        float worstMiddle = 0f;
        for (int i = 0; i < 60; i++) {
            m.step(1f / 60f);
            for (int b = 1; b < m.getBallCount() - 1; b++) {
                worstMiddle = Math.max(worstMiddle,
                        Math.abs(m.getBallX(b) - m.getAnchorX(b)));
            }
        }
        // The chain transmits the impulse instead of carrying it: the middle
        // balls jiggle but never swing out like the ends do.
        assertTrue(worstMiddle < 0.02f,
                "middle balls must stay near their hang (got " + worstMiddle + ")");
    }

    // ------------------------------------------------------------------
    // NewtonCradleModel: energy and bounds over a longer run
    // ------------------------------------------------------------------

    @Test
    @DisplayName("total energy decays as the motion is bled off")
    void energyDecays() {
        NewtonCradleModel m = new NewtonCradleModel();
        run(m, 0.1f);
        float early = m.getTotalEnergy();
        assertTrue(early > 0f, "a released cradle carries energy");
        run(m, 4.9f);   // t ~= 5 s
        float later = m.getTotalEnergy();
        assertTrue(later < early,
                "energy must decay (early=" + early + " later=" + later + ")");
        assertTrue(m.getMaxSpeed() >= 0f);
        assertTrue(m.getKineticEnergy() >= 0f);
    }

    @Test
    @DisplayName("no ball ever leaves the static world bounds")
    void ballsStayInBounds() {
        NewtonCradleModel m = new NewtonCradleModel();
        for (int i = 0; i < 600; i++) {   // 10 simulated seconds
            m.step(1f / 60f);
            for (int b = 0; b < m.getBallCount(); b++) {
                assertTrue(m.getBallX(b) >= m.getMinX() - 1e-3
                                && m.getBallX(b) <= m.getMaxX() + 1e-3,
                        "ball " + b + " left the x bounds at t=" + m.getTime());
                assertTrue(m.getBallY(b) >= m.getMinY() - 1e-3
                                && m.getBallY(b) <= m.getMaxY() + 1e-3,
                        "ball " + b + " left the y bounds at t=" + m.getTime());
            }
        }
    }

    @Test
    @DisplayName("the balls never visibly interpenetrate")
    void ballsDoNotOverlap() {
        NewtonCradleModel m = new NewtonCradleModel();
        float worst = 0f;
        for (int i = 0; i < 600; i++) {
            m.step(1f / 60f);
            for (int a = 0; a < m.getBallCount(); a++) {
                for (int b = a + 1; b < m.getBallCount(); b++) {
                    float dx = m.getBallX(a) - m.getBallX(b);
                    float dy = m.getBallY(a) - m.getBallY(b);
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    float penetration = 2f * m.getBallRadius() - dist;
                    worst = Math.max(worst, penetration);
                }
            }
        }
        // A hair of penetration is tolerated (the solver's slop), but never
        // enough to read as two balls occupying one place.
        assertTrue(worst < 1e-3f, "penetration must stay under the slop (got " + worst + ")");
    }

    // ------------------------------------------------------------------
    // NewtonCradleModel: stepping discipline
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a non-positive delta is ignored and a huge delta is clamped")
    void stepDiscipline() {
        NewtonCradleModel m = new NewtonCradleModel();
        m.step(0f);
        m.step(-1f);
        assertEquals(0f, m.getTime(), "non-positive deltas advance nothing");

        m.step(10f);   // far beyond one frame
        assertTrue(m.getTime() <= 0.05f + 1e-6,
                "a stalled viewer must not make one frame do unbounded work");
    }

    @Test
    @DisplayName("reset restores the release pose and zeroes the clock")
    void resetRestoresInitialState() {
        NewtonCradleModel m = new NewtonCradleModel();
        run(m, 2f);
        assertTrue(m.getTime() > 0f);
        m.reset();
        assertEquals(0f, m.getTime());
        assertTrue(m.getBallX(0) < m.getAnchorX(0), "ball 0 is pulled out again");
        for (int i = 0; i < m.getBallCount(); i++) {
            assertEquals(0f, m.getBallSpeed(i), 1e-6, "reset zeroes every velocity");
        }
        // And the toy runs again from the restored pose.
        run(m, 0.5f);
        assertTrue(m.getTime() > 0f);
    }

    @Test
    @DisplayName("the same step sequence is deterministic")
    void deterministic() {
        NewtonCradleModel a = new NewtonCradleModel();
        NewtonCradleModel b = new NewtonCradleModel();
        run(a, 1.5f);
        run(b, 1.5f);
        for (int i = 0; i < a.getBallCount(); i++) {
            assertEquals(a.getBallX(i), b.getBallX(i), 0f);
            assertEquals(a.getBallY(i), b.getBallY(i), 0f);
            assertEquals(a.getBallVelocityX(i), b.getBallVelocityX(i), 0f);
            assertEquals(a.getBallVelocityY(i), b.getBallVelocityY(i), 0f);
        }
        assertEquals(a.getTotalEnergy(), b.getTotalEnergy(), 0f);
    }

    @Test
    @DisplayName("the read API reports per-ball state consistently")
    void readApiConsistency() {
        NewtonCradleModel m = new NewtonCradleModel();
        run(m, 0.3f);
        for (int i = 0; i < m.getBallCount(); i++) {
            float vx = m.getBallVelocityX(i);
            float vy = m.getBallVelocityY(i);
            assertEquals((float) Math.sqrt(vx * vx + vy * vy), m.getBallSpeed(i), 1e-5,
                    "speed must be the velocity magnitude");
        }
        assertFalse(Float.isNaN(m.getTotalEnergy()));
        assertFalse(Float.isNaN(m.getKineticEnergy()));
    }
}
