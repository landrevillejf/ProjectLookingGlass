/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: expand a COLLADA draw element into render-ready mesh data.
 *
 * This is the geometry walk the 2006 jmecolladaloader ran on its way to a Java 3D
 * GeometryArray, kept headless and pure here. A primitive's <p> token list packs,
 * for each referenced vertex, one index per input at that input's offset (the
 * stride); the POSITION / NORMAL / TEXCOORD inputs each name a <source> whose
 * accessor stride says how many components to read. We expand the primitive to an
 * unshared triangle soup -- every referenced vertex becomes its own entry -- and
 * generate triangle indices by the primitive kind.
 *
 * Note on <polylist>: the M5 reader keeps the packed indices and the facet count
 * but not the per-polygon <vcount>, so a genuine n-gon fan cannot be reconstructed
 * from the model. The shipped Google Earth / SketchUp exports are already
 * triangulated, so polylist/polygon fall back to consecutive triples (a written
 * limitation, revisit if a real quad-bearing world is loaded); lines/linestrips are
 * not triangles and contribute no indices.
 */
package org.jdesktop.wonderland.renderer.mesh;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;

/** Turns a {@link ColladaGeometry} into one {@link ColladaMeshData} per primitive. */
public final class ColladaMeshBuilder {

    /** The mesh's primitives, each expanded to its own flat render-ready data. */
    public List<ColladaMeshData> build(ColladaGeometry geometry) {
        List<ColladaMeshData> out = new ArrayList<>();
        if (geometry == null) {
            return out;
        }
        ColladaGeometry.Mesh mesh = geometry.mesh();
        for (ColladaGeometry.Primitive prim : mesh.primitives()) {
            out.add(buildPrimitive(mesh, prim));
        }
        return out;
    }

    /** Expand one primitive of one mesh. Package-private for the direct tests. */
    ColladaMeshData buildPrimitive(ColladaGeometry.Mesh mesh, ColladaGeometry.Primitive prim) {
        List<ColladaGeometry.Input> inputs = prim.inputs();
        long[] indices = prim.indices();
        int stride = prim.stride();
        int vertexCount = stride == 0 ? 0 : (int) (indices.length / stride);

        ColladaGeometry.Input position = findInput(inputs, "POSITION");
        ColladaGeometry.Input normal = findInput(inputs, "NORMAL");
        ColladaGeometry.Input texcoord = findTexcoord(inputs);
        // Remembered before the VERTEX indirection fills them in, because a
        // component bound on the primitive reads its own offset while a component
        // inherited from <vertices> reads the vertex token's.
        boolean positionBound = position != null;
        boolean normalBound = normal != null;

        // Where the vertex token sits in the packed group, for the components read
        // through a VERTEX binding (0 when a component is bound directly).
        long vertexOffset = 0;
        ColladaGeometry.Input vertex = findInput(inputs, "VERTEX");
        if (vertex != null) {
            /*
             * COLLADA's vertex indirection: a primitive may bind semantic VERTEX,
             * whose token indexes the <vertices> array, and let that <vertices>
             * element name the POSITION (and NORMAL) float sources. The 2006-era
             * Google Earth / SketchUp exports the shipped Wonderland art came from
             * bind nothing else -- VERTEX plus TEXCOORD -- so a builder that looks
             * only for a POSITION input finds none, expands all 941 draw elements
             * of the Japanese pavilion to an empty mesh, and hands the scene graph
             * zero Shape3D: a world that renders as nothing at all.
             */
            ColladaGeometry.Vertices verts = mesh == null ? null : mesh.vertices();
            vertexOffset = vertex.offset();
            if (position == null && verts != null) {
                position = findInput(verts.inputs(), "POSITION");
            }
            if (normal == null && verts != null) {
                normal = findInput(verts.inputs(), "NORMAL");
            }
        }

        if (position == null || vertexCount < 1) {
            // no POSITION binding: the 2006 loader likewise produced nothing here
            return new ColladaMeshData(new float[0], new float[0], new float[0],
                    new int[0], prim.material());
        }

        double[] posData = sourceData(mesh, position);
        int posStride = sourceStride(mesh, position);
        float[] coordinates = new float[vertexCount * 3];

        double[] nrmData = normal == null ? null : sourceData(mesh, normal);
        int nrmStride = normal == null ? 0 : sourceStride(mesh, normal);
        float[] normals = normal == null ? new float[0] : new float[vertexCount * 3];

        double[] tcData = texcoord == null ? null : sourceData(mesh, texcoord);
        int tcStride = texcoord == null ? 0 : sourceStride(mesh, texcoord);
        float[] texCoords = texcoord == null ? new float[0] : new float[vertexCount * 2];

        // A component bound directly keeps its own offset; one reached through the
        // <vertices> element is indexed by the VERTEX token, since the position and
        // normal arrays are parallel to the vertex array.
        long posOffset = positionBound ? position.offset() : vertexOffset;
        long nrmOffset = normalBound ? normal.offset() : vertexOffset;

        for (int v = 0; v < vertexCount; v++) {
            readComponents(posData, posStride, token(indices, stride, v, posOffset),
                    3, coordinates, v * 3);
            if (normal != null) {
                readComponents(nrmData, nrmStride, token(indices, stride, v, nrmOffset),
                        3, normals, v * 3);
            }
            if (texcoord != null) {
                readComponents(tcData, tcStride, token(indices, stride, v, texcoord.offset()),
                        2, texCoords, v * 2);
            }
        }

        int[] triangleIndices = triangulate(prim.kind(), vertexCount);
        return new ColladaMeshData(coordinates, normals, texCoords, triangleIndices,
                prim.material());
    }

    /**
     * The triangle index list for {@code vertexCount} consecutive referenced
     * vertices, by COLLADA primitive kind. Triangles and (triangulated) polylist /
     * polygon are consecutive triples; trifans fan from vertex 0; tristrips share
     * two vertices per new triangle with alternating winding; lines emit none.
     */
    static int[] triangulate(String kind, int vertexCount) {
        List<Integer> idx = new ArrayList<>();
        String k = kind == null ? "" : kind;
        switch (k) {
            case "lines":
            case "linestrips":
                break; // not triangles
            case "trifans":
                for (int i = 1; i + 1 < vertexCount; i++) {
                    idx.add(0);
                    idx.add(i);
                    idx.add(i + 1);
                }
                break;
            case "tristrips":
                for (int i = 0; i + 2 < vertexCount; i++) {
                    if ((i & 1) == 0) {
                        idx.add(i);
                        idx.add(i + 1);
                        idx.add(i + 2);
                    } else {
                        idx.add(i);
                        idx.add(i + 2);
                        idx.add(i + 1);
                    }
                }
                break;
            default:
                // triangles, polylist, polygons, tristrips-without-vcount fallbacks:
                // consume complete triples, drop a dangling 1- or 2-vertex tail
                for (int i = 0; i + 2 < vertexCount; i += 3) {
                    idx.add(i);
                    idx.add(i + 1);
                    idx.add(i + 2);
                }
                break;
        }
        int[] out = new int[idx.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = idx.get(i);
        }
        return out;
    }

    /** The packed <p> token for vertex {@code v} at {@code offset} within its stride group. */
    private static int token(long[] indices, int stride, int v, long offset) {
        int flat = v * stride + (int) offset;
        return flat < indices.length ? (int) indices[flat] : 0;
    }

    /** Read {@code compCount} components from {@code data} at source index {@code src}, into {@code out}. */
    private static void readComponents(double[] data, int dataStride, int src,
                                       int compCount, float[] out, int outOffset) {
        int stride = Math.max(1, dataStride);
        for (int c = 0; c < compCount; c++) {
            int flat = src * stride + c;
            out[outOffset + c] = (data != null && flat >= 0 && flat < data.length)
                    ? (float) data[flat]
                    : 0f;
        }
    }

    private static double[] sourceData(ColladaGeometry.Mesh mesh, ColladaGeometry.Input in) {
        ColladaGeometry.Source src = resolveSource(mesh, in);
        return src == null ? new double[0] : src.data();
    }

    private static int sourceStride(ColladaGeometry.Mesh mesh, ColladaGeometry.Input in) {
        ColladaGeometry.Source src = resolveSource(mesh, in);
        return src == null ? 1 : src.stride();
    }

    private static ColladaGeometry.Source resolveSource(
            ColladaGeometry.Mesh mesh, ColladaGeometry.Input in) {
        if (mesh == null || in == null) {
            return null;
        }
        return mesh.sourceById(stripRef(in.source()));
    }

    /** The first input whose semantic matches, or null. */
    private static ColladaGeometry.Input findInput(List<ColladaGeometry.Input> inputs,
                                                   String semantic) {
        for (ColladaGeometry.Input in : inputs) {
            if (semantic.equals(in.semantic())) {
                return in;
            }
        }
        return null;
    }

    /**
     * The texture-coordinate input: COLLADA 1.4.1 uses the TEXCOORD semantic with a
     * {@code set} naming the UV channel; we prefer set "0" (or an unset) and fall
     * back to the first TEXCOORD-like semantic the authoring tool emitted.
     */
    private static ColladaGeometry.Input findTexcoord(List<ColladaGeometry.Input> inputs) {
        ColladaGeometry.Input first = null;
        for (ColladaGeometry.Input in : inputs) {
            String sem = in.semantic();
            if (sem == null || !(sem.equals("TEXCOORD") || sem.startsWith("TEXCOORD"))) {
                continue;
            }
            if (first == null) {
                first = in;
            }
            String set = in.set();
            if (set == null || set.isEmpty() || "0".equals(set)) {
                return in; // the primary UV set
            }
        }
        return first;
    }

    private static String stripRef(String ref) {
        if (ref == null) {
            return null;
        }
        return ref.startsWith("#") ? ref.substring(1) : ref;
    }
}
