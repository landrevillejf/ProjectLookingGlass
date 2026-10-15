/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland roadmap P0.1, the half that is allowed to work on this platform.
 *
 * Why a CPU bake instead of the light rig the roadmap names, stated with the
 * measurement rather than as a preference: build-gradle/scratch/WlProbe32 drew
 * one authored-red quad under eight conditions on the live X display and read
 * the pixels back. A full-white ambient light over a red Material came back
 * (46,5,5); the SAME red Material with NO light in the universe came back
 * (46,5,5); the unlit DECAL texture path came back (229,25,25), exactly the
 * authored colour; and an emissive (0.9,0.9,0.9) Material under that same
 * full-white ambient read back (229,25,25). Three readings that cannot all be
 * true if a Light node reaches the GL fixed-function pipeline -- and only one
 * that is true if it does not: 0.2 is GL_GL_LIGHT_MODEL_AMBIENT's own default
 * (0.2,0.2,0.2,1), the diffuse and directional terms contribute nothing
 * (facing == yawed in all eight recipes), and the (229,25,25) readings are the
 * paths that bypass the light equation entirely. So a scene-graph Light is
 * installed anyway for honesty and for the day the platform is fixed, but the
 * shading a person can see has to be computed here and delivered as per-vertex
 * colour.
 *
 * Why per-vertex colour is the right carrier, also measured: WlProbe33 drew a
 * quad whose four vertices carry a graded red, read back (193) distinct
 * colours interpolated across the face -- so GeometryArray.COLOR_3 survives the
 * fork's unlit path -- while the same geometry under DECAL came back pure white
 * (distinct=2), proving the bake is thrown away in the mode every production
 * material uses today. MODULATE kept the ramp (193 distinct). Hence: write the
 * bake into COLOR_3, and force textured surfaces through MODULATE.
 *
 * What the default rig is: a gentle 0.72 ambient + 0.28 key, NOT the 0.45 +
 * 0.55 an offline renderer would choose. The textures a cell ships are already
 * baked by the server -- TextureBindingPlan says so in its own comment -- so
 * this factor multiplies colour that already carries its shading, and a rig
 * deep enough to look like real lighting would darken the whole world by half.
 * The brief is "the pixels show which way a face points", and 0.72..1.00 does
 * that while the average stays where the artist left it. Tuning lives here, in
 * the pure class, so the probe can retune it without touching a scene graph.
 */
package org.jdesktop.wonderland.renderer.mesh;

import org.jdesktop.wonderland.common.features.WorldFeatures;

/**
 * The lighting decision as arithmetic, with no Java 3D in it, so it is testable
 * headless (the rule the whole renderer follows: pure in tests, thin in the
 * scene graph) and so the numbers a probe measured can be asserted here rather
 * than argued from pixels.
 */
public final class BakedRig {

    /**
     * Whether the bake is in force for this JVM: the feature flag AND a rig that
     * actually changes a colour. Both halves are needed -- a test that wants the
     * identity rig should not have to know about properties, and a user who
     * writes {@code -Dlg.wonderland.lighting=off} should not have to know that
     * the rig is a separate object.
     */
    public static boolean active() {
        return WorldFeatures.enabled(WorldFeatures.LIGHTING) && !isIdentity(spec);
    }

    /**
     * The floor this build's fixed pipeline answers with when nothing is in
     * scene: GL's default global ambient, 0.2. Recorded because it is the
     * calibration of every claim in this file -- red (0.9,0.1,0.1) read back as
     * (46,5,5) is exactly 0.2 x authored -- and because a future rig that stops
     * the world sitting in the dark should say what replaced this number.
     */
    public static final float MEASURED_PLATFORM_AMBIENT_FLOOR = 0.2f;

    /** Ambient term of the default rig. See the class comment for why 0.72. */
    public static final float DEFAULT_AMBIENT = 0.72f;

    /** Key term of the default rig; ambient + key == 1 keeps the brightest face authored-exact. */
    public static final float DEFAULT_KEY = 0.28f;

    /**
     * The key light's direction, unit length, in cell-local space -- from up
     * and in front, pointing down and back, which is the same direction
     * WorldScene.Lighting's own default carries so the probe rig and this one
     * disagree about nothing but intensity.
     */
    public static final float[] DEFAULT_KEY_DIRECTION = {-0.231f, -0.370f, -0.899f};

    /**
     * The rig in force. Volatile and static for the reason the avatar installers
     * are: the scene graph is assembled by a path that has no injection point
     * (J3dMeshFactory is constructed by the model loader), and the alternative --
     * threading a spec through every constructor -- touches a ported call graph
     * for a value that is one per JVM. A test or a probe that wants a
     * deterministic rig sets it and restores what it found.
     */
    private static volatile Spec spec = new Spec(DEFAULT_AMBIENT, DEFAULT_KEY,
            DEFAULT_KEY_DIRECTION[0], DEFAULT_KEY_DIRECTION[1], DEFAULT_KEY_DIRECTION[2]);

    private BakedRig() {
        // holder: no instances
    }

    /**
     * One lighting rig as five floats. A record so the setter, a test and the
     * probe can all speak about "the rig" as one value that is restorable.
     *
     * @param ambient the flat term, the brightness of a face edge-on to the key
     * @param key     the directional term added on top for a face looking at the key
     * @param kx      the unit key direction, x
     * @param ky      the unit key direction, y
     * @param kz      the unit key direction, z
     */
    public record Spec(float ambient, float key, float kx, float ky, float kz) {

        /**
         * The rig that leaves the pixels exactly as they are today: factor 1.0
         * for every normal. An argument, not a property, because a test that
         * reaches for {@code lg.wonderland.lighting=off} would be asserting the
         * flag and not the arithmetic, and those are different regressions.
         */
        public static Spec none() {
            return new Spec(1.0f, 0.0f, 0f, 0f, -1f);
        }

        /** The unit-length key direction, normalised defensively. */
        public float[] direction() {
            float len = (float) Math.sqrt(kx * kx + ky * ky + kz * kz);
            if (len < 1e-6f) {
                // A zero direction cannot point anywhere; down-and-front is the
                // least surprising stand-in and is what the default uses for z.
                return new float[] {0f, 0f, -1f};
            }
            return new float[] {kx / len, ky / len, kz / len};
        }
    }

    /** The rig in force. Never null; {@link Spec#none()} is the identity. */
    public static Spec spec() {
        return spec;
    }

    /** Install a rig; pass what {@link #spec()} returned to restore it. */
    public static void setSpec(Spec s) {
        spec = s == null ? new Spec(DEFAULT_AMBIENT, DEFAULT_KEY,
                DEFAULT_KEY_DIRECTION[0], DEFAULT_KEY_DIRECTION[1], DEFAULT_KEY_DIRECTION[2]) : s;
    }

    /**
     * The light factor for one surface normal: {@code ambient + key * max(0,
     * -N . L)}, clamped to [0,1].
     *
     * The sign: the model's normals point OUT of the surface and the key
     * direction points AT the surface (it is the direction light travels), so a
     * face looking at the key has a negative dot and the term is negated. A
     * normal of all zeros (a model that shipped none) gets the ambient term
     * alone rather than a division by zero or a black face.
     *
     * @param nx normal x
     * @param ny normal y
     * @param nz normal z
     * @return the multiplier for this vertex, in [0,1]
     */
    public static float factor(float nx, float ny, float nz) {
        return factor(nx, ny, nz, spec);
    }

    /** The same arithmetic against an explicit rig, for tests and the probe. */
    public static float factor(float nx, float ny, float nz, Spec s) {
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) {
            return clamp(s.ambient());
        }
        float[] d = s.direction();
        float ndotl = (nx / len) * d[0] + (ny / len) * d[1] + (nz / len) * d[2];
        return clamp(s.ambient() + s.key() * Math.max(0f, -ndotl));
    }

    /**
     * The per-vertex colours for one mesh, as {@code 3 * vertexCount} floats in
     * the order GeometryArray wants them.
     *
     * @param normals  the mesh normals, 3 * vertexCount, may be null or short
     * @param authored the surface colour to shade, 3 * vertexCount; pass null for
     *                 a textured surface, whose colour lives in its texture, so
     *                 the vertices carry the light factor alone -- multiplying
     *                 the server's baked texture by a colour again would darken
     *                 it twice
     * @return the colour array, or null when the rig is the identity and the
     *         authored colour is absent, which is the signal to the factory to
     *         not ask for COLOR_3 at all
     */
    public static float[] vertexColors(float[] normals, float[] authored) {
        Spec s = spec;
        return vertexColors(normals, authored, s);
    }

    /** The same against an explicit rig. */
    public static float[] vertexColors(float[] normals, float[] authored, Spec s) {
        int vertices = authored != null ? authored.length / 3
                : (normals == null ? 0 : normals.length / 3);
        if (vertices == 0) {
            return null;
        }
        float[] out = new float[vertices * 3];
        boolean identity = isIdentity(s);
        for (int i = 0; i < vertices; i++) {
            float f = identity ? 1f : factor(at(normals, i * 3), at(normals, i * 3 + 1),
                    at(normals, i * 3 + 2), s);
            if (authored != null) {
                out[i * 3] = clamp01(at(authored, i * 3) * f);
                out[i * 3 + 1] = clamp01(at(authored, i * 3 + 1) * f);
                out[i * 3 + 2] = clamp01(at(authored, i * 3 + 2) * f);
            } else {
                out[i * 3] = f;
                out[i * 3 + 1] = f;
                out[i * 3 + 2] = f;
            }
        }
        return out;
    }

    /** Whether this rig leaves every colour untouched. */
    public static boolean isIdentity(Spec s) {
        return s.ambient() >= 1.0f && s.key() <= 0.0f;
    }

    private static float at(float[] a, int i) {
        return a == null || i >= a.length ? 0f : a[i];
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp01(float v) {
        return clamp(v);
    }
}
