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

import java.util.ArrayList;
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
     * Walk the Collada document, expand every geometry through the pure mesh
     * builder, resolve materials through the pure binding plan, and build the
     * scene. Requires the Java 3D native stack; call only from the probe.
     */
    public ModelRenderer(ColladaDocument document, List<String> presentTextures) {
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
        J3dMeshFactory factory = new J3dMeshFactory();
        ColladaMeshBuilder meshBuilder = new ColladaMeshBuilder();
        TextureBindingPlan plan = new TextureBindingPlan(document,
                presentTextures == null ? List.of() : presentTextures);

        Map<String, MaterialAppearance> appearances = plan.forAll(allMaterialIds(document));

        for (ColladaNode.WorldMatrix wm : document.worldMatrices()) {
            ColladaNode node = wm.node();
            if (node.geometries().isEmpty()) {
                continue;
            }
            TransformGroup nodeTg = new TransformGroup();
            nodeTg.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
            nodeTg.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
            nodeTg.setTransform(new Transform3D(WorldTransformMath.toRowMajor(wm.matrix())));
            cellTransformGroup.addChild(nodeTg);

            for (ColladaNode.InstanceGeometry ig : node.geometries()) {
                ColladaGeometry geom = document.geometry(stripHash(ig.url()));
                if (geom == null) {
                    continue;
                }
                for (ColladaMeshData data : meshBuilder.build(geom)) {
                    MaterialAppearance ma = appearances.getOrDefault(
                            data.material(), plan.forMaterial(data.material()));
                    Shape3D shape = factory.createShape(data, ma);
                    if (shape != null) {
                        nodeTg.addChild(shape);
                    }
                }
            }
        }
    }

    private static List<String> allMaterialIds(ColladaDocument document) {
        List<String> ids = new ArrayList<>();
        for (ColladaNode.WorldMatrix wm : document.worldMatrices()) {
            for (ColladaNode.InstanceGeometry ig : wm.node().geometries()) {
                for (ColladaNode.InstanceMaterial im : ig.materials()) {
                    if (im.symbol() != null && !ids.contains(im.symbol())) {
                        ids.add(im.symbol());
                    }
                }
            }
        }
        return ids;
    }

    private static String stripHash(String url) {
        if (url == null) {
            return null;
        }
        int hash = url.lastIndexOf('#');
        return hash < 0 ? url : url.substring(hash + 1);
    }
}
