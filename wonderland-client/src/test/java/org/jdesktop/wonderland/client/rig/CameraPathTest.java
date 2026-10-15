/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P3.1: the spline tour, headless.
 *
 * Catmull-Rom's whole contract is that the curve passes through its points --
 * a tour that does not visit the room it keys is a bug with nicer tangents --
 * so the endpoints and every interior waypoint are asserted exactly, the
 * straight case is asserted to stay straight (a spline that bulges on a line
 * is mis-implemented, full stop), and the clock's rough edges -- overshooting
 * the end, a negative second -- are asserted to clamp rather than throw. That
 * a live fly-through LOOKS good is the probe's, as always.
 */
package org.jdesktop.wonderland.client.rig;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CameraPathTest {

    private static CameraPath.Waypoint p(float x, float y, float z) {
        return new CameraPath.Waypoint(x, y, z);
    }

    private static final CameraPath STRAIGHT = CameraPath.of(p(0, 0, 0), p(0, 0, 10));

    @Test
    void theTourStartsAndEndsExactlyOnItsKeys() {
        assertThat(STRAIGHT.sample(0f)).isEqualTo(p(0, 0, 0));
        assertThat(STRAIGHT.sample(1f)).isEqualTo(p(0, 0, 10));

        CameraPath tour = CameraPath.of(p(0, 0, 0), p(5, 1, 8), p(12, 0, 3));
        assertThat(tour.sample(0f)).isEqualTo(p(0, 0, 0));
        assertThat(tour.sample(1f)).isEqualTo(p(12, 0, 3));
    }

    @Test
    void everyInteriorWaypointIsVisitedAtItsSegmentBoundary() {
        // Two segments: the interior key sits at t = 1/2 exactly, from both
        // sides -- the C0 promise, asserted as the arrival AND departure value.
        CameraPath tour = CameraPath.of(p(0, 0, 0), p(4, 2, 6), p(10, 0, 10));

        assertThat(tour.sample(0.5f)).isEqualTo(p(4, 2, 6));
        assertThat(tour.sample(0.5f - 1e-6f)).isNotEqualTo(p(4, 2, 6));
        assertThat(tour.sample(0.5f).x()).isCloseTo(4f, within(1e-6f));
    }

    @Test
    void aStraightLineStaysStraight() {
        // The classic cubic-implementation trap: a wrong basis bulges off the
        // line. Uniform Catmull-Rom does NOT promise z = 12t between evenly
        // spaced keys (the phantom end tangent lags the first segment -- that
        // is parameterisation, not curvature), so the honest assertions are
        // the geometric ones: every sample lies ON the keyed line, and the
        // keys themselves are hit at their segment boundaries.
        CameraPath line = CameraPath.of(p(0, 0, 0), p(0, 0, 4), p(0, 0, 8), p(0, 0, 12));

        for (float t = 0f; t <= 1f; t += 0.05f) {
            CameraPath.Waypoint w = line.sample(t);
            assertThat(w.x()).as("no bulge off the line").isCloseTo(0f, within(1e-5f));
            assertThat(w.y()).isCloseTo(0f, within(1e-5f));
            assertThat(w.z()).as("and never outside the tour's span")
                    .isBetween(-1e-5f, 12f + 1e-5f);
        }
        assertThat(line.sample(1f / 3f).z()).as("keys hit at their boundaries, to float rounding")
                .isCloseTo(4f, within(1e-5f));
        assertThat(line.sample(2f / 3f).z()).isCloseTo(8f, within(1e-5f));
    }

    @Test
    void theClockClampsRatherThanThrowingWhenTheTourOvershoots() {
        assertThat(STRAIGHT.sample(-2f)).isEqualTo(p(0, 0, 0));
        assertThat(STRAIGHT.sample(99f)).isEqualTo(p(0, 0, 10));
    }

    @Test
    void theLookPointsWhereTheRouteIsGoing() {
        float[] ahead = STRAIGHT.lookDirection(0.5f);

        assertThat(ahead[2]).as("+Z run seen from mid-path").isCloseTo(1f, within(1e-4f));
        assertThat(ahead[0]).isCloseTo(0f, within(1e-6f));
        assertThat(Math.sqrt(ahead[0] * ahead[0] + ahead[1] * ahead[1] + ahead[2] * ahead[2]))
                .as("a unit vector, always").isCloseTo(1.0, within(1e-5));
    }

    @Test
    void aStalledRouteAnswersARealDirectionRatherThanNaN() {
        // Two identical waypoints: no tangent exists; the documented hold is +Z.
        CameraPath stuck = CameraPath.of(p(1, 2, 3), p(1, 2, 3));

        assertThat(stuck.lookDirection(0.25f)).containsExactly(0f, 0f, 1f);
        assertThat(stuck.sample(0.5f)).isEqualTo(p(1, 2, 3));
    }

    @Test
    void aPathIsBetweenRealPlacesAndAtLeastTwoOfThem() {
        assertThatThrownBy(() -> new CameraPath(List.of(p(0, 0, 0))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CameraPath(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p(0, Float.NaN, 0))
                .as("a waypoint that is not a place")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
