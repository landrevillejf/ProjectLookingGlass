/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): a COLLADA 1.4.1 scene <node>.
 *
 * A node nests transforms, child nodes, geometry instances and node instances.
 * This keeps the document-order transform list and folds local / world matrices
 * headless (the world walk is what the 2006 loader baked into each Shape3D's
 * transform); the geometry instances record which geometry + material binding
 * the node draws, which is everything the asset pipeline needs to inventory a
 * model before M6 turns it into the scene graph.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.List;
import org.jogamp.vecmath.Matrix4d;

/** A {@code <node>} in a visual scene or node library. */
public final class ColladaNode {

    private final String id;
    private final String name;
    private final String type;
    private final List<ColladaTransform> transforms;
    private final List<ColladaNode> children;
    private final List<InstanceGeometry> geometries;
    private final List<String> nodeInstanceUrls;

    public ColladaNode(String id, String name, String type,
                       List<ColladaTransform> transforms,
                       List<ColladaNode> children,
                       List<InstanceGeometry> geometries,
                       List<String> nodeInstanceUrls) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.transforms = List.copyOf(transforms);
        this.children = List.copyOf(children);
        this.geometries = List.copyOf(geometries);
        this.nodeInstanceUrls = List.copyOf(nodeInstanceUrls);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** The {@code type} attribute ({@code NODE} / {@code JOINT} / ...), or null. */
    public String type() {
        return type;
    }

    public List<ColladaTransform> transforms() {
        return transforms;
    }

    public List<ColladaNode> children() {
        return children;
    }

    public List<InstanceGeometry> geometries() {
        return geometries;
    }

    /** The {@code url}s of {@code <instance_node>} children (resolved by the document later). */
    public List<String> nodeInstanceUrls() {
        return nodeInstanceUrls;
    }

    /** Concatenate this node's own transforms in document order (identity when none). */
    public Matrix4d localMatrix() {
        Matrix4d m = new Matrix4d();
        m.setIdentity();
        for (ColladaTransform t : transforms) {
            Matrix4d next = new Matrix4d();
            next.mul(m, t.toMatrix());
            m = next;
        }
        return m;
    }

    /**
     * Walk the subtree filling each node's world matrix (parent * child-local) into
     * {@code out}. A null {@code parentWorld} seeds the root with the identity.
     */
    public void collectWorldMatrices(Matrix4d parentWorld, List<WorldMatrix> out) {
        Matrix4d parent = parentWorld == null ? identity() : parentWorld;
        Matrix4d world = new Matrix4d();
        world.mul(parent, localMatrix());
        out.add(new WorldMatrix(this, world));
        for (ColladaNode child : children) {
            child.collectWorldMatrices(world, out);
        }
    }

    private static Matrix4d identity() {
        Matrix4d m = new Matrix4d();
        m.setIdentity();
        return m;
    }

    /** A node paired with the world matrix computed for it. */
    public static final class WorldMatrix {
        private final ColladaNode node;
        private final Matrix4d matrix;

        WorldMatrix(ColladaNode node, Matrix4d matrix) {
            this.node = node;
            this.matrix = matrix;
        }

        public ColladaNode node() {
            return node;
        }

        public Matrix4d matrix() {
            return matrix;
        }
    }

    /** An {@code <instance_geometry>} and the material bindings it carries. */
    public static final class InstanceGeometry {
        private final String url;
        private final List<InstanceMaterial> materials;

        public InstanceGeometry(String url, List<InstanceMaterial> materials) {
            this.url = url;
            this.materials = List.copyOf(materials);
        }

        /** The referenced geometry id (the {@code #id} stripped of its hash), or null. */
        public String url() {
            return url;
        }

        public List<InstanceMaterial> materials() {
            return materials;
        }
    }

    /** An {@code <instance_material>} binding a symbol to an effect target for a UV set. */
    public static final class InstanceMaterial {
        private final String symbol;
        private final String target;
        private final List<String> texcoords;

        public InstanceMaterial(String symbol, String target, List<String> texcoords) {
            this.symbol = symbol;
            this.target = target;
            this.texcoords = List.copyOf(texcoords);
        }

        public String symbol() {
            return symbol;
        }

        /** The referenced material id (hash stripped), or null. */
        public String target() {
            return target;
        }

        public List<String> texcoords() {
            return texcoords;
        }
    }
}
