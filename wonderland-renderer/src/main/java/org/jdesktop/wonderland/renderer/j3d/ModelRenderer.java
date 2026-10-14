/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the scene-graph cell renderer the cell seam hands back.
 *
 * The 2006 com.jme renderer built a whole scene per cell and reparented it under
 * the desktop's BranchGroup. Rebuilt here on the Jogamp fork: one BranchGroup,
 * one Switch (the show / hide toggle the CellStatus walk drives), under that a
 * TransformGroup carrying the cell's live CellTransform, under that one
 * TransformGroup per Collada scene node so the visual hierarchy is preserved,
 * and under each node's group one Shape3D per primitive (via the
 * J3dMeshFactory). The CellRenderer contract the desktop calls
 * (cellTransformUpdate / setStatus / getStatus) is exactly what this class
 * implements; nothing here is safe to run under a headless JVM because the
 * Jogamp scene-graph classes need the native rendering stack. Verified by the
 * in-JVM probe, not by the suite -- same treatment lg3d-core gives its own
 * scene-graph code.
 */
package org.jdesktop.wonderland.renderer.j3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshBuilder;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.jdesktop.wonderland.renderer.mesh.MaterialAppearance;
import org.jdesktop.wonderland.renderer.mesh.TextureBindingPlan;
import org.jdesktop.wonderland.renderer.mesh.TextureSource;
import org.jdesktop.wonderland.renderer.transform.WorldTransformMath;
import org.jogamp.java3d.BranchGroup;
import org.jogamp.java3d.Group;
import org.jogamp.java3d.Shape3D;
import org.jogamp.java3d.Switch;
import org.jogamp.java3d.Transform3D;
import org.jogamp.java3d.TransformGroup;

/** A Java 3D scene-graph renderer for one loaded model, hooked to the cell seam. */
public final class ModelRenderer implements CellRenderer {

    private static final int SWITCH_HIDE = Switch.CHILD_NONE;
    private static final int SWITCH_SHOW = 0; // our Switch has exactly one child

    private final BranchGroup branch;
    private final Switch visibilitySwitch;
    private final TransformGroup cellTransformGroup;
    private CellStatus status = CellStatus.DISK;

    /**
     * Where this model's texture bytes come from; null keeps the classpath /
     * file-system resolution the M6a probe used.
     */
    private final TextureSource textures;

    /**
     * Walk the Collada document, expand every geometry through the pure mesh
     * builder, resolve materials through the pure binding plan, and build the
     * scene. Requires the Java 3D native stack; call only from the probe.
     */
    public ModelRenderer(ColladaDocument document, List<String> presentTextures) {
        this(document, presentTextures, null);
    }

    /**
     * As {@link #ModelRenderer(ColladaDocument, List)}, reading the textures the
     * binding plan selects through {@code textures} (null: the classpath).
     */
    public ModelRenderer(ColladaDocument document, List<String> presentTextures,
            TextureSource textures) {
        this.textures = textures;
        this.branch = new BranchGroup();
        branch.setCapability(BranchGroup.ALLOW_DETACH);
        branch.setCapability(Group.ALLOW_CHILDREN_READ);
        branch.setCapability(Group.ALLOW_CHILDREN_WRITE);
        branch.setCapability(Group.ALLOW_CHILDREN_EXTEND);

        this.visibilitySwitch = new Switch(SWITCH_HIDE);
        visibilitySwitch.setCapability(Switch.ALLOW_SWITCH_WRITE);

        this.cellTransformGroup = new TransformGroup();
        cellTransformGroup.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
        cellTransformGroup.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);

        visibilitySwitch.addChild(cellTransformGroup);
        branch.addChild(visibilitySwitch);

        if (document != null) {
            buildScene(document, presentTextures);
        }
    }

    /** The root of this renderer's scene, ready to be added under the desktop. */
    public BranchGroup getScene() {
        return branch;
    }

    @Override
    public void cellTransformUpdate(CellTransform transform) {
        double[] row = WorldTransformMath.fromCellTransform(transform);
        Transform3D t3d = new Transform3D(row);
        cellTransformGroup.setTransform(t3d);
    }

    @Override
    public void setStatus(CellStatus newStatus, boolean increasing) {
        // VISIBLE / RENDERING show the scene; DISK / INACTIVE / ACTIVE hide it,
        // matching the 2006 pattern where a cell off the render queue kept the
        // BranchGroup attached but its leaves un-drawn. Java 3D in this fork has
        // no Node.setEnable(); a Switch is the canonical way to gate a subtree.
        this.status = newStatus;
        boolean show = newStatus == CellStatus.VISIBLE || newStatus == CellStatus.RENDERING;
        visibilitySwitch.setWhichChild(show ? SWITCH_SHOW : SWITCH_HIDE);
    }

    @Override
    public CellStatus getStatus() {
        return status;
    }

    /**
     * Enumerate every world node's geometry instances, expand each, bind each
     * primitive to its material appearance and attach under the node's world
     * transform. A document with no root visual scene contributes nothing, and
     * a geometry referenced by a node but missing from the library is skipped
     * (both match the 2006 loader's tolerance for partial exports).
     */
    private void buildScene(ColladaDocument document, List<String> presentTextures) {
        J3dMeshFactory factory = new J3dMeshFactory(textures);
        ColladaMeshBuilder meshBuilder = new ColladaMeshBuilder();
        TextureBindingPlan plan = new TextureBindingPlan(document,
                presentTextures == null ? List.of() : presentTextures);

        // Appearances are cached by material id, one entry per material actually
        // painted -- the pavilion's 112 textures become 112 Texture2D, shared by
        // every primitive that names them.
        Map<String, MaterialAppearance> appearances = new LinkedHashMap<>();

        for (ColladaNode.WorldMatrix wm : document.worldMatrices()) {
            ColladaNode node = wm.node();
            if (node.geometries().isEmpty()) {
                continue;
            }
            double[] placed = WorldTransformMath.toRowMajor(wm.matrix());
            // A node the raster cannot hold is dropped, not fatal: setTransform
            // throws BadTransformException out of this loop, and the throw used to
            // take the whole model -- and in the world browser, the whole world --
            // down with the one degenerate node (see WorldTransformMath.isAffine).
            if (!WorldTransformMath.isAffine(placed)) {
                continue;
            }
            TransformGroup nodeTg = new TransformGroup();
            nodeTg.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
            nodeTg.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
            nodeTg.setTransform(new Transform3D(placed));
            cellTransformGroup.addChild(nodeTg);

            for (ColladaNode.InstanceGeometry ig : node.geometries()) {
                Map<String, String> bindings = bindingsOf(ig);
                ColladaGeometry geom = document.geometry(stripHash(ig.url()));
                if (geom == null) {
                    continue;
                }
                for (ColladaMeshData data : meshBuilder.build(geom)) {
                    String materialId = bindings.getOrDefault(
                            data.material(), data.material());
                    MaterialAppearance ma = appearances.computeIfAbsent(
                            materialId, plan::forMaterial);
                    Shape3D shape = factory.createShape(data, ma);
                    if (shape != null) {
                        nodeTg.addChild(shape);
                    }
                }
            }
        }
    }

    /**
     * One {@code <instance_geometry>}'s material bindings: the symbol a primitive
     * names, to the library id that symbol points at.
     *
     * <p>The scope is the instance, never the document. A primitive's
     * {@code material} attribute is a binding symbol, and the shipped exports reuse
     * the same handful of symbols everywhere: the Japanese pavilion binds
     * {@code Material2} 145 times, each to a different material, so a document-wide
     * symbol table would paint all 759 geometry instances with one texture. The
     * symbol passes through unchanged when an instance binds nothing (an export that
     * names the material id directly), which is what the older fixtures do.</p>
     */
    private static Map<String, String> bindingsOf(ColladaNode.InstanceGeometry ig) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ColladaNode.InstanceMaterial im : ig.materials()) {
            if (im.symbol() != null && im.target() != null) {
                out.putIfAbsent(im.symbol(), im.target());
            }
        }
        return out;
    }

    private static String stripHash(String url) {
        if (url == null) {
            return null;
        }
        int hash = url.lastIndexOf('#');
        return hash < 0 ? url : url.substring(hash + 1);
    }
}
