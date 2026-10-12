/*
 * The shim's only non-trivial behaviour vs java.lang.Math is clamping the
 * acos/asin arguments so a rounding-overshoot (|v| > 1 by 1 ULP, which happens
 * routinely in angleBetween / toAngleAxis) returns the boundary angle rather
 * than NaN. Everything else is a thin float-narrowing wrapper.
 */
package com.jme.math;

import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FastMathTest {

    @Test
    void constantsMatchMathPi() {
        assertThat(FastMath.PI).isCloseTo((float) Math.PI, within(1e-7f));
        assertThat(FastMath.HALF_PI).isCloseTo((float) (Math.PI / 2), within(1e-7f));
        assertThat(FastMath.TWO_PI).isCloseTo((float) (2 * Math.PI), within(1e-6f));
    }

    @Test
    void absHandlesNegativeZeroAndPositiveValues() {
        assertThat(FastMath.abs(-3.5f)).isEqualTo(3.5f);
        assertThat(FastMath.abs(3.5f)).isEqualTo(3.5f);
        assertThat(FastMath.abs(0f)).isEqualTo(0f);
    }

    @Test
    void sqrtSinCosTanDelegateToMath() {
        assertThat(FastMath.sqrt(9f)).isEqualTo(3f);
        assertThat(FastMath.sin(0f)).isEqualTo(0f);
        assertThat(FastMath.cos(0f)).isEqualTo(1f);
        assertThat(FastMath.tan(0f)).isEqualTo(0f);
        assertThat(FastMath.atan2(1f, 1f)).isCloseTo((float) (Math.PI / 4), within(1e-6f));
    }

    @Test
    void acosClampsArgumentsOutsideUnitInterval() {
        // No NaN: a value overshooting 1 by any amount returns 0 (acos(1)).
        assertThat(FastMath.acos(1.0001f)).isCloseTo(0f, within(1e-6f));
        assertThat(FastMath.acos(2f)).isCloseTo(0f, within(1e-6f));
        // And undershooting -1 returns PI.
        assertThat(FastMath.acos(-1.0001f)).isCloseTo((float) Math.PI, within(1e-6f));
        assertThat(FastMath.acos(-2f)).isCloseTo((float) Math.PI, within(1e-6f));
        // In-range passthrough.
        assertThat(FastMath.acos(0f)).isCloseTo((float) (Math.PI / 2), within(1e-6f));
    }

    @Test
    void asinClampsArgumentsOutsideUnitInterval() {
        assertThat(FastMath.asin(1.5f)).isCloseTo((float) (Math.PI / 2), within(1e-6f));
        assertThat(FastMath.asin(-1.5f)).isCloseTo((float) (-Math.PI / 2), within(1e-6f));
        assertThat(FastMath.asin(0f)).isEqualTo(0f);
    }

    @Test
    void signumMinAndMax() {
        assertThat(FastMath.signum(1f)).isEqualTo(1f);
        assertThat(FastMath.signum(-1f)).isEqualTo(-1f);
        assertThat(FastMath.signum(0f)).isEqualTo(1f);
        assertThat(FastMath.min(1f, 2f)).isEqualTo(1f);
        assertThat(FastMath.max(1f, 2f)).isEqualTo(2f);
    }
}
