/*
 * In-tree replacement for jMonkeyEngine's com.jme.math.FastMath.
 *
 * Only the few helpers the ported data model touches are provided. They delegate
 * to java.lang.Math with float narrowing, and clamp acos/asin exactly as jME did
 * so a rounding-overshoot argument returns the boundary angle instead of NaN.
 */
package com.jme.math;

public final class FastMath {

    public static final float PI = (float) Math.PI;
    public static final float HALF_PI = (float) (Math.PI / 2.0);
    public static final float TWO_PI = (float) (2.0 * Math.PI);
    public static final float EPSILON = 1.0E-5F;
    public static final float DEG_TO_RAD = PI / 180.0F;
    public static final float RAD_TO_DEG = 180.0F / PI;

    private FastMath() {
    }

    public static float abs(float v) {
        return v > 0.0F ? v : -v;
    }

    public static float sqrt(float v) {
        return (float) Math.sqrt(v);
    }

    public static float sin(float v) {
        return (float) Math.sin(v);
    }

    public static float cos(float v) {
        return (float) Math.cos(v);
    }

    public static float tan(float v) {
        return (float) Math.tan(v);
    }

    public static float atan2(float y, float x) {
        return (float) Math.atan2(y, x);
    }

    public static float acos(float v) {
        if (v < -1.0F) {
            v = -1.0F;
        } else if (v > 1.0F) {
            v = 1.0F;
        }
        return (float) Math.acos(v);
    }

    public static float asin(float v) {
        if (v < -1.0F) {
            v = -1.0F;
        } else if (v > 1.0F) {
            v = 1.0F;
        }
        return (float) Math.asin(v);
    }

    public static float signum(float v) {
        return v >= 0.0F ? 1.0F : -1.0F;
    }

    public static float min(float a, float b) {
        return a < b ? a : b;
    }

    public static float max(float a, float b) {
        return a > b ? a : b;
    }
}
