/*
 * wonderland-modules M5b (artimport): the <deployed-model> a .dep file records.
 *
 * A deployed model is the artimport's record of where the Collada lives inside
 * the world module (the wla:// URLs), the background normalisation transform the
 * 2006 importer computed to seat the model at the origin at a sane scale (scale /
 * translation / rotation), its bounding volume and the loader class that reads
 * it. Pure data; the geometry it points at is parsed by M5a and drawn at M6.
 */
package org.jdesktop.wonderland.modules.artimport;

import org.jogamp.vecmath.Vector3f;

/** A parsed {@code .dep} deployed-model document. */
public final class DeployedModel {

    private final int version;
    private final String modelURL;
    private final String loaderDataURL;
    private final Vector3f backgroundScale;
    private final Vector3f backgroundTranslation;
    private final Vector3f backgroundRotationAxis;
    private final float backgroundRotationAngle;
    private final Bounds bounds;
    private final String modelLoaderClassname;

    public DeployedModel(int version, String modelURL, String loaderDataURL,
                         Vector3f backgroundScale, Vector3f backgroundTranslation,
                         Vector3f backgroundRotationAxis, float backgroundRotationAngle,
                         Bounds bounds, String modelLoaderClassname) {
        this.version = version;
        this.modelURL = modelURL;
        this.loaderDataURL = loaderDataURL;
        this.backgroundScale = backgroundScale;
        this.backgroundTranslation = backgroundTranslation;
        this.backgroundRotationAxis = backgroundRotationAxis;
        this.backgroundRotationAngle = backgroundRotationAngle;
        this.bounds = bounds;
        this.modelLoaderClassname = modelLoaderClassname;
    }

    public int version() {
        return version;
    }

    /** The {@code wla://} URL of the (gzipped) Collada model. */
    public String modelURL() {
        return modelURL;
    }

    /** The {@code wla://} URL of the {@code .ldr} loader-data. */
    public String loaderDataURL() {
        return loaderDataURL;
    }

    public Vector3f backgroundScale() {
        return copy(backgroundScale);
    }

    public Vector3f backgroundTranslation() {
        return copy(backgroundTranslation);
    }

    public Vector3f backgroundRotationAxis() {
        return copy(backgroundRotationAxis);
    }

    /** The background rotation angle in radians (the {@code <angle>} element). */
    public float backgroundRotationAngle() {
        return backgroundRotationAngle;
    }

    public Bounds bounds() {
        return bounds;
    }

    public String modelLoaderClassname() {
        return modelLoaderClassname;
    }

    private static Vector3f copy(Vector3f v) {
        return v == null ? null : new Vector3f(v);
    }

    /** The model's bounding volume: a shape type plus a centre and per-axis dimension. */
    public static final class Bounds {
        private final String type;
        private final Vector3f center;
        private final Vector3f dimension;

        public Bounds(String type, Vector3f center, Vector3f dimension) {
            this.type = type;
            this.center = center;
            this.dimension = dimension;
        }

        /** {@code BOX} / {@code SPHERE} / ... as the exporter wrote it. */
        public String type() {
            return type;
        }

        public Vector3f center() {
            return center == null ? null : new Vector3f(center);
        }

        public Vector3f dimension() {
            return dimension == null ? null : new Vector3f(dimension);
        }

        /** The enclosing volume's long-side length, used for a quick sanity bound. */
        public float longestDimension() {
            if (dimension == null) {
                return 0f;
            }
            return Math.max(dimension.x, Math.max(dimension.y, dimension.z));
        }
    }
}
