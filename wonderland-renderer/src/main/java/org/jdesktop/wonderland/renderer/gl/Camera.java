/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.2: the JOGL camera -- pure view/projection maths.
 *
 * The scene core is rebuilt on JOGL (the modern Jogamp OpenGL binding) because
 * it supplies the per-frame GLEventListener seam stock java3d lacks. The camera
 * is the one piece that needs no GL context at all: it turns a pose (eye,
 * target, up) and a lens (vertical fov, aspect, near, far) into the two
 * column-major 4x4 matrices OpenGL multiplies vertices by. Keeping it a pure
 * value type means the matrix maths is asserted headless, exactly like the
 * mesh/transform data it sits beside; only the glUniformMatrix4fv upload that
 * consumes view() and projection() needs a live context.
 *
 * Matrices are column-major (OpenGL's layout), stored as float[16] so they can
 * be handed straight to glUniformMatrix4fv without transposition.
 */
package org.jdesktop.wonderland.renderer.gl;

/**
 * An immutable eye/lens and the view + projection matrices it implies.
 */
public final class Camera {

    private final float[] view;
    private final float[] projection;

    /**
     * @param eyeX    camera position x
     * @param eyeY    camera position y
     * @param eyeZ    camera position z
     * @param targetX look-at point x
     * @param targetY look-at point y
     * @param targetZ look-at point z
     * @param upX     world-up x
     * @param upY     world-up y
     * @param upZ     world-up z
     * @param fovY    vertical field of view in radians, exclusive (0, pi)
     * @param aspect  width / height, positive
     * @param near    near clip, positive and less than far
     * @param far     far clip, positive and greater than near
     */
    public Camera(float eyeX, float eyeY, float eyeZ,
                  float targetX, float targetY, float targetZ,
                  float upX, float upY, float upZ,
                  float fovY, float aspect, float near, float far) {
        if (!(aspect > 0f)) {
            throw new IllegalArgumentException("aspect must be positive: " + aspect);
        }
        if (!(near > 0f) || !(far > near)) {
            throw new IllegalArgumentException("need 0 < near < far: " + near + ", " + far);
        }
        if (!(fovY > 0f) || !(fovY < Math.PI)) {
            throw new IllegalArgumentException("fovY must be in (0, pi): " + fovY);
        }
        this.view = lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ);
        this.projection = perspective(fovY, aspect, near, far);
    }

    /** The view (world -> eye) matrix, column-major. A fresh copy each call. */
    public float[] view() {
        return view.clone();
    }

    /** The projection (eye -> clip) matrix, column-major. A fresh copy each call. */
    public float[] projection() {
        return projection.clone();
    }

    /** view * projection is not what GL wants; callers upload each separately. */
    private static float[] lookAt(float ex, float ey, float ez,
                                  float tx, float ty, float tz,
                                  float ux, float uy, float uz) {
        // f = normalize(target - eye)
        float fx = tx - ex;
        float fy = ty - ey;
        float fz = tz - ez;
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl == 0f) {
            throw new IllegalArgumentException("eye and target coincide");
        }
        fx /= fl;
        fy /= fl;
        fz /= fl;
        // s = normalize(f x up)
        float sx = fy * uz - fz * uy;
        float sy = fz * ux - fx * uz;
        float sz = fx * uy - fy * ux;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl == 0f) {
            throw new IllegalArgumentException("up is parallel to the view direction");
        }
        sx /= sl;
        sy /= sl;
        sz /= sl;
        // u = s x f
        float uux = sy * fz - sz * fy;
        float uuy = sz * fx - sx * fz;
        float uuz = sx * fy - sy * fx;
        float[] m = new float[16];
        m[0] = sx;
        m[4] = sy;
        m[8] = sz;
        m[1] = uux;
        m[5] = uuy;
        m[9] = uuz;
        m[2] = -fx;
        m[6] = -fy;
        m[10] = -fz;
        m[12] = -(sx * ex + sy * ey + sz * ez);
        m[13] = -(uux * ex + uuy * ey + uuz * ez);
        m[14] = fx * ex + fy * ey + fz * ez;
        m[15] = 1f;
        return m;
    }

    private static float[] perspective(float fovY, float aspect, float near, float far) {
        float tanHalf = (float) Math.tan(fovY / 2f);
        float[] m = new float[16];
        m[0] = 1f / (aspect * tanHalf);
        m[5] = 1f / tanHalf;
        m[10] = -(far + near) / (far - near);
        m[11] = -1f;
        m[14] = -(2f * far * near) / (far - near);
        return m;
    }
}
