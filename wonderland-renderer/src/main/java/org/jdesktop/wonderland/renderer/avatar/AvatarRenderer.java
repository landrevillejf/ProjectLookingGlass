/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the scene-graph avatar renderer.
 *
 * The 2006 AvatarJME was a jME-1.x scene-graph node owning an Animator and a
 * jme-binary body from an unrecoverable pool. This fork rebuilds it on the
 * Jogamp Java 3D stack, reusing M6a's SceneGraph pattern exactly: one
 * BranchGroup, one Switch (driven by CellStatus), under that one
 * TransformGroup carrying the composed M6b pose (base CellTransform, or the
 * goto override, plus the collision Y offset), and under that one
 * TransformGroup per Collada node hosting one Shape3D per primitive built
 * through J3dMeshFactory.
 *
 * What M6b adds on top of M6a's ModelRenderer:
 *   - the class also implements AvatarCell.AvatarActionTrigger, so the
 *     AvatarCell.triggerAction / triggerCollision / triggerGoto dispatches
 *     land here instead of being inert;
 *   - every such dispatch mutates a local AvatarTriggerState, then writes
 *     through to the scene: the collision / goto paths replace the pose
 *     Transform3D through AvatarTransformFeed, and the pressed path rewrites
 *     every Shape3D's Material.diffuseColor to the state-chosen colour. Both
 *     Material and Appearance carry ALLOW_COMPONENT_WRITE /
 *     ALLOW_MATERIAL_WRITE from J3dMeshFactory, so the live universe accepts
 *     the mutation without a rebuild.
 *   - cellTransformUpdate also feeds through AvatarTransformFeed so a
 *     collision offset survives every fresh move message, matching the 2006
 *     behaviour where a peer sliding along a floor keeps the collider lift
 *     until the collision-off event clears it.
 *
 * Everything above the seam -- the pose composition, the colour decision, the
 * state mutation -- is pure (see AvatarTriggerState and AvatarTransformFeed)
 * and unit-tested headlessly. Everything here touches Jogamp scene-graph
 * classes: the whole file is compiled under check but exercised only by the
 * in-JVM probe, same treatment lg3d-core gives its own scene graph.
 */
package org.jdesktop.wonderland.renderer.avatar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.client.cell.view.AvatarCell;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.jdesktop.wonderland.renderer.j3d.J3dMeshFactory;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshBuilder;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.jdesktop.wonderland.renderer.mesh.MaterialAppearance;
import org.jdesktop.wonderland.renderer.mesh.TextureBindingPlan;
import org.jdesktop.wonderland.renderer.transform.WorldTransformMath;
import org.jogamp.java3d.Appearance;
import org.jogamp.java3d.BranchGroup;
import org.jogamp.java3d.Group;
import org.jogamp.java3d.Material;
import org.jogamp.java3d.Shape3D;
import org.jogamp.java3d.Switch;
import org.jogamp.java3d.Transform3D;
import org.jogamp.java3d.TransformGroup;
import org.jogamp.vecmath.Color4f;

/** The Java 3D scene-graph renderer for one loaded avatar body. */
public final class AvatarRenderer implements CellRenderer, AvatarCell.AvatarActionTrigger {

    private static final int SWITCH_HIDE = Switch.CHILD_NONE;
    private static final int SWITCH_SHOW = 0; // one child

    private final BranchGroup branch;
    private final Switch visibilitySwitch;
    private final TransformGroup poseTransformGroup;
    private final List<Shape3D> shapes = new ArrayList<>();
    private final AvatarTriggerState state = new AvatarTriggerState();

    private CellStatus status = CellStatus.DISK;
    private CellTransform lastBase = null;

    /**
     * Build the scene from an already-resolved avatar load. The renderer
     * keeps a reference to every Shape3D so a later trigger/collision event
     * can re-tint them without rebuilding the geometry. Requires the Java 3D
     * native stack; call only from the probe.
     */
    public AvatarRenderer(AvatarLoadResult result) {
        this.branch = new BranchGroup();
        branch.setCapability(BranchGroup.ALLOW_DETACH);
        branch.setCapability(Group.ALLOW_CHILDREN_READ);
        branch.setCapability(Group.ALLOW_CHILDREN_WRITE);
        branch.setCapability(Group.ALLOW_CHILDREN_EXTEND);

        this.visibilitySwitch = new Switch(SWITCH_HIDE);
        visibilitySwitch.setCapability(Switch.ALLOW_SWITCH_WRITE);

        this.poseTransformGroup = new TransformGroup();
        poseTransformGroup.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
        poseTransformGroup.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
        poseTransformGroup.setTransform(new Transform3D(
                AvatarTransformFeed.compose(null, state)));

        visibilitySwitch.addChild(poseTransformGroup);
        branch.addChild(visibilitySwitch);

        if (result != null && result.isLoaded() && result.document() != null) {
            buildScene(result.document());
            applyColourToAllShapes();
        }
    }

    /** The renderer's root, ready to attach under the desktop's scene. */
    public BranchGroup getScene() {
        return branch;
    }

    /** Exposed for the probe -- tests never call this because state is pure. */
    public AvatarTriggerState getState() {
        return state;
    }

    // --- CellRenderer --------------------------------------------------------

    @Override
    public void cellTransformUpdate(CellTransform transform) {
        this.lastBase = transform;
        poseTransformGroup.setTransform(new Transform3D(
                AvatarTransformFeed.compose(transform, state)));
    }

    @Override
    public void setStatus(CellStatus newStatus, boolean increasing) {
        this.status = newStatus;
        boolean show = newStatus == CellStatus.VISIBLE || newStatus == CellStatus.RENDERING;
        visibilitySwitch.setWhichChild(show ? SWITCH_SHOW : SWITCH_HIDE);
    }

    @Override
    public CellStatus getStatus() {
        return status;
    }

    // --- AvatarCell.AvatarActionTrigger --------------------------------------

    @Override
    public void trigger(int triggerIndex, boolean pressed, String animationName) {
        state.applyTrigger(triggerIndex, pressed, animationName);
        applyColourToAllShapes();
    }

    @Override
    public void triggerCollision(float height, boolean collision) {
        state.applyCollision(height, collision);
        poseTransformGroup.setTransform(new Transform3D(
                AvatarTransformFeed.compose(lastBase, state)));
    }

    @Override
    public void triggerGoto(com.jme.math.Vector3f worldPosition,
                            com.jme.math.Quaternion look) {
        state.applyGoto(worldPosition, look);
        poseTransformGroup.setTransform(new Transform3D(
                AvatarTransformFeed.compose(lastBase, state)));
    }

    // --- scene assembly ------------------------------------------------------

    private void buildScene(ColladaDocument document) {
        J3dMeshFactory factory = new J3dMeshFactory();
        ColladaMeshBuilder meshBuilder = new ColladaMeshBuilder();
        // The pool ships no textures and no material library, so the plan is
        // created empty; that is exactly what makes the J3dMeshFactory use
        // its neutral-grey fallback appearance -- the colour the trigger
        // path then overrides. A real rigged body added later with a material
        // library will resolve through this same plan unchanged.
        TextureBindingPlan plan = new TextureBindingPlan(document, List.of());
        Map<String, MaterialAppearance> appearances = plan.forAll(List.of());

        for (ColladaNode.WorldMatrix wm : document.worldMatrices()) {
            ColladaNode node = wm.node();
            if (node.geometries().isEmpty()) {
                continue;
            }
            TransformGroup nodeTg = new TransformGroup();
            nodeTg.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
            nodeTg.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
            nodeTg.setTransform(new Transform3D(
                    WorldTransformMath.toRowMajor(wm.matrix())));
            poseTransformGroup.addChild(nodeTg);

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
                        shapes.add(shape);
                    }
                }
            }
        }
    }

    /**
     * Write the current trigger state's colour into every Shape3D's Material.
     * The Material was constructed by J3dMeshFactory with the write
     * capability already set, so this is legal on a live universe; on a
     * headless one the whole class is never invoked.
     */
    private void applyColourToAllShapes() {
        float[] rgba = state.chooseDiffuseColour();
        Color4f c = new Color4f(rgba[0], rgba[1], rgba[2], rgba[3]);
        for (Shape3D s : shapes) {
            Appearance app = s.getAppearance();
            if (app == null) {
                continue;
            }
            Material mat = app.getMaterial();
            if (mat == null) {
                continue;
            }
            // Color3f and Color4f share RGB; Java 3D's setDiffuseColor
            // overload takes Color3f. The alpha component is handled by the
            // Appearance's TransparencyAttributes upstream; for the pool's
            // opaque bodies alpha is 1.0 and does not matter here.
            mat.setDiffuseColor(new org.jogamp.vecmath.Color3f(c.x, c.y, c.z));
            mat.setAmbientColor(new org.jogamp.vecmath.Color3f(c.x, c.y, c.z));
        }
    }

    private static String stripHash(String url) {
        if (url == null) {
            return null;
        }
        int hash = url.lastIndexOf('#');
        return hash < 0 ? url : url.substring(hash + 1);
    }
}
