/*
 * wonderland-modules M5a (colladaloader): a COLLADA 1.4.1 <geometry>.
 *
 * The mesh data the loader reads before any scene graph is built: the accessor
 * sources (float arrays with a stride), the <vertices> position binding, and the
 * draw primitives (<triangles> / <polylist> / ...) whose <p> indices index into
 * those sources through their per-input offset. Positions can be resolved to a
 * flat xyz list entirely headless -- that is the geometry half the 2006 loader
 * fed to the jME/jogamp Shape3D build, which stays deferred to M6.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A named {@code <geometry>} element wrapping a single {@code <mesh>}. */
public final class ColladaGeometry {

    private final String id;
    private final String name;
    private final Mesh mesh;

    public ColladaGeometry(String id, String name, Mesh mesh) {
        this.id = id;
        this.name = name;
        this.mesh = mesh == null ? new Mesh(List.of(), null, List.of()) : mesh;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Mesh mesh() {
        return mesh;
    }

    /** The three families a mesh is made of: its sources, its vertex binding, its primitives. */
    public static final class Mesh {
        private final List<Source> sources;
        private final Vertices vertices;
        private final List<Primitive> primitives;

        public Mesh(List<Source> sources, Vertices vertices, List<Primitive> primitives) {
            this.sources = List.copyOf(sources);
            this.vertices = vertices;
            this.primitives = List.copyOf(primitives);
        }

        public List<Source> sources() {
            return sources;
        }

        public Vertices vertices() {
            return vertices;
        }

        public List<Primitive> primitives() {
            return primitives;
        }

        /** Resolve a {@code #id} reference to its source, or null when absent. */
        public Source sourceById(String refId) {
            for (Source s : sources) {
                if (s.id().equals(refId)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** A {@code <source>} = one float/id/Name array plus the accessor's stride + count. */
    public static final class Source {
        private final String id;
        private final double[] data;
        private final int stride;
        private final long count;

        public Source(String id, double[] data, int stride, long count) {
            this.id = id;
            this.data = data == null ? new double[0] : data.clone();
            this.stride = stride;
            this.count = count;
        }

        public String id() {
            return id;
        }

        public double[] data() {
            return data.clone();
        }

        public int stride() {
            return stride;
        }

        public long count() {
            return count;
        }
    }

    /** One {@code <input>} element: the semantic, its source reference and offsets. */
    public static final class Input {
        private final String semantic;
        private final String source;
        private final long offset;
        private final String set;

        public Input(String semantic, String source, long offset, String set) {
            this.semantic = semantic;
            this.source = source;
            this.offset = offset;
            this.set = set;
        }

        public String semantic() {
            return semantic;
        }

        public String source() {
            return source;
        }

        public long offset() {
            return offset;
        }

        public String set() {
            return set;
        }
    }

    /** The {@code <vertices>} binding: an id plus the semantic inputs (usually POSITION + NORMAL). */
    public static final class Vertices {
        private final String id;
        private final List<Input> inputs;

        public Vertices(String id, List<Input> inputs) {
            this.id = id;
            this.inputs = List.copyOf(inputs);
        }

        public String id() {
            return id;
        }

        public List<Input> inputs() {
            return inputs;
        }
    }

    /** A draw element ({@code triangles} / {@code polylist} / {@code lines} / ...). */
    public static final class Primitive {
        private final String kind;
        private final String material;
        private final long count;
        private final List<Input> inputs;
        private final long[] indices;

        public Primitive(String kind, String material, long count,
                         List<Input> inputs, long[] indices) {
            this.kind = kind;
            this.material = material;
            this.count = count;
            this.inputs = List.copyOf(inputs);
            this.indices = indices == null ? new long[0] : indices.clone();
        }

        public String kind() {
            return kind;
        }

        public String material() {
            return material;
        }

        public long count() {
            return count;
        }

        public List<Input> inputs() {
            return inputs;
        }

        public long[] indices() {
            return indices.clone();
        }

        /**
         * The number of {@code <p>} tokens per vertex: the largest input offset + 1
         * (COLLADA packs each vertex's inputs contiguously). Zero inputs → zero.
         */
        public int stride() {
            int stride = 0;
            for (Input in : inputs) {
                stride = Math.max(stride, (int) in.offset() + 1);
            }
            return stride;
        }

        /**
         * Resolve the referenced vertices' positions to a flat xyz list.
         *
         * <p>The POSITION input's source is looked up in {@code mesh}; each vertex
         * reads its position index from the packed {@code <p>} array and pulls the
         * three floats out of that source. Returns empty when the mesh has no
         * POSITION binding (a pure-normal primitive) -- the 2006 loader likewise
         * skipped those.
         */
        public List<Float> positions(Mesh mesh) {
            Input position = findInput("POSITION");
            List<Float> out = new ArrayList<>();
            if (position == null || mesh == null) {
                return Collections.unmodifiableList(out);
            }
            Source src = mesh.sourceById(stripRef(position.source()));
            if (src == null) {
                return Collections.unmodifiableList(out);
            }
            int stride = stride();
            if (stride == 0) {
                return Collections.unmodifiableList(out);
            }
            int srcStride = Math.max(1, src.stride());
            double[] data = src.data();
            // The packed <p> array holds one stride-sized group per referenced
            // vertex; a <triangles>' count is the facet count, not the vertex
            // count, so walk the tokens rather than trust count.
            long entries = stride == 0 ? 0 : indices.length / stride;
            for (long vertex = 0; vertex < entries; vertex++) {
                long tokenIndex = vertex * stride + position.offset();
                if (tokenIndex >= indices.length) {
                    break; // malformed short <p>: emit what we have, do not throw on load
                }
                int posIndex = (int) indices[(int) tokenIndex];
                for (int comp = 0; comp < 3; comp++) {
                    int flat = posIndex * srcStride + comp;
                    out.add(flat < data.length ? (float) data[flat] : 0f);
                }
            }
            return Collections.unmodifiableList(out);
        }

        private Input findInput(String semantic) {
            for (Input in : inputs) {
                if (semantic.equals(in.semantic())) {
                    return in;
                }
            }
            return null;
        }

        /** strip the leading {@code #} from a local reference. */
        static String stripRef(String ref) {
            if (ref == null) {
                return null;
            }
            return ref.startsWith("#") ? ref.substring(1) : ref;
        }
    }
}
