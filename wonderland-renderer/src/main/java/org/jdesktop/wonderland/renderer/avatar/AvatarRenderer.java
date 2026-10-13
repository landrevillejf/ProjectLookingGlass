/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b + M7c: the scene-graph avatar renderer.
 *
 * The 2006 AvatarJME was a jME-1.x scene-graph node owning an Animator and a
 * jme-binary body from an unrecoverable pool. This fork rebuilds it on the
 * Jogamp Java 3D stack, reusing M6a's SceneGraph pattern exactly: one
 * BranchGroup, one Switch (driven by CellStatus), under that one
 * TransformGroup carrying the composed M6b pose (base CellTransform, or the
 * goto override, plus the collision Y offset), and under that the Collada
 * node tree mirrored 1:1 as nested TransformGroups -- every node (joint or
 * not) gets one, hosting one Shape3D per primitive through J3dMeshFactory.
 *
 * What M6b added: the class also implements AvatarCell.AvatarActionTrigger,
 * so triggerAction / triggerCollision / triggerGoto land here; every such
 * dispatch mutates a local AvatarTriggerState, then writes through to the
 * scene (pose re-composition, re-tint) -- cellTransformUpdate feeds through
 * the same pose path so a collision offset survives every fresh move.
 *
 * What M7c adds: the renderer is now the keyframe animator's write target.
 * The node tree is nested with LOCAL matrices (not M6b's flat world-matrix
 * soup) precisely so an animated joint moves its whole subtree the way the
 * 2006 Animator moved the skeleton. An AvatarAnimationDirector (pure, see
 * the animation package) consumes the trigger's animationName; a daemon
 * ticker started by the desktop's mount path calls tickAnimation, samples
 * the active clip and rewrites only the animated nodes' TransformGroups --
 * everything else keeps its authored local matrix. Unloaded / unmounted
 * bodies stop the ticker and hold the static pose.
 *
 * Everything above the seam -- pose composition, colour decision, clip
 * selection, track sampling -- is pure and unit-tested headlessly. Everything
 * here touches Jogamp scene-graph classes: the whole file is compiled under
 * check but exercised only by the in-JVM probe, same treatment lg3d-core
 * gives its own scene graph.
 */
package org.jdesktop.wonderland.renderer.avatar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.client.cell.view.AvatarCell;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.jdesktop.wonderland.modules.collada.ColladaVisualScene;
import org.jdesktop.wonderland.renderer.animation.AnimationClip;
import org.jdesktop.wonderland.renderer.animation.AnimationLibrary;
import org.jdesktop.wonderland.renderer.animation.AvatarAnimationDirector;
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
import org.jogamp.vecmath.Matrix4d;

/** The Java 3D scene-graph renderer for one loaded avatar body. */
public final class AvatarRenderer implements CellRenderer, AvatarCell.AvatarActionTrigger {

    private static final int SWITCH_HIDE = Switch.CHILD_NONE;
    private static final int SWITCH_SHOW = 0; // one child

    private final BranchGroup branch;
    private final Switch visibilitySwitch;
    private final TransformGroup poseTransformGroup;
    private final List<Shape3D> shapes = new ArrayList<>();
    private final AvatarTriggerState state = new AvatarTriggerState();

    /** node id/name -> its TransformGroup plus authored local matrix (M7c). */
    private final Map<String, AnimatedNode> animatedNodes = new LinkedHashMap<>();
    /** the node keys the previous tick overrode, so a clip switch can restore. */
    private final Set<String> lastOverridden = new HashSet<>();

    /** M7c animator; null until a body loads (and stays null for a failed load). */
    private AvatarAnimationDirector director;
    private volatile boolean clockRunning;

    private CellStatus status = CellStatus.DISK;
    private CellTransform lastBase = null;

    /** One scene node the animator can address: its TG plus its static pose. */
    private record AnimatedNode(TransformGroup tg, Matrix4d staticLocal) { }

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
        // M7c: the name string M6b only stored is now consumed. A press
        // requests the named clip; a release returns to the stance clip --
        // the 2006 client's own pressed/release pairing of animationName.
        if (director != null) {
            director.requestAnimation(pressed ? animationName : null);
            tickAnimation(0.0); // immediate visual response, no 33 ms wait
        }
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

    // --- M7c animation clock ---------------------------------------------------

    /**
     * Start the daemon ticker (probe-side lifecycle: the desktop's mount path
     * calls this once the scene is live). Idempotent; without a loaded body
     * there is nothing to animate and no thread is started.
     */
    public void startAnimationClock() {
        if (clockRunning || director == null) {
            return;
        }
        clockRunning = true;
        Thread ticker = new Thread(() -> {
            long last = System.nanoTime();
            while (clockRunning) {
                try {
                    Thread.sleep(33); // ~30 fps; the eye cannot tell, the CPU can
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long now = System.nanoTime();
                double dt = (now - last) / 1_000_000_000.0;
                last = now;
                tickAnimation(dt);
            }
        }, "avatar-animation");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** Stop the ticker; the body freezes at its last written pose (2006 parity:
     * an unmounted avatar is not being rendered, so nobody sees the freeze). */
    public void stopAnimationClock() {
        clockRunning = false;
    }

    /**
     * Advance the director by {@code dtSeconds} and write the sampled pose.
     * Public for the probe and for the pure director's contract; safe to call
     * from any thread because Java 3D accepts setTransform on the
     * ALLOW_TRANSFORM_WRITE groups J3dMeshFactory-era code already sets.
     */
    public void tickAnimation(double dtSeconds) {
        if (director == null) {
            return;
        }
        director.advance(dtSeconds);
        AnimationClip clip = director.activeClip();
        Set<String> applied = new HashSet<>();
        if (clip != null) {
            for (Map.Entry<String, Matrix4d> e : clip.sampleAt(director.time()).entrySet()) {
                AnimatedNode node = animatedNodes.get(e.getKey());
                if (node == null) {
                    continue; // a clip naming a node the body lacks: skip, 2006 parity
                }
                node.tg().setTransform(new Transform3D(WorldTransformMath.toRowMajor(e.getValue())));
                applied.add(e.getKey());
            }
        }
        for (String key : lastOverridden) {
            if (!applied.contains(key)) {
                AnimatedNode node = animatedNodes.get(key);
                if (node != null) {
                    node.tg().setTransform(
                            new Transform3D(WorldTransformMath.toRowMajor(node.staticLocal())));
                }
            }
        }
        lastOverridden.clear();
        lastOverridden.addAll(applied);
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

        ColladaVisualScene scene = document.rootVisualScene();
        if (scene == null) {
            return;
        }
        String bodyKey = null;
        for (ColladaNode node : scene.nodes()) {
            String key = nodeKey(node);
            if (bodyKey == null && key != null) {
                bodyKey = key;
            }
            buildNode(node, poseTransformGroup, factory, meshBuilder, document, plan, appearances);
        }
        // M7c: parsed clips win; an un-rigged document falls back to the
        // synthetic trio keyed to the body's root node so it still moves.
        AnimationLibrary library =
                AnimationLibrary.forDocument(document, bodyKey == null ? "body" : bodyKey);
        director = new AvatarAnimationDirector(library);
    }

    /**
     * Mirror one Collada node as a nested TransformGroup carrying its LOCAL
     * matrix (M7c changed this from M6b's flat world-matrix soup so an
     * animated joint drags its subtree, exactly like the 2006 skeleton).
     */
    private void buildNode(ColladaNode node, Group parent, J3dMeshFactory factory,
                           ColladaMeshBuilder meshBuilder, ColladaDocument document,
                           TextureBindingPlan plan, Map<String, MaterialAppearance> appearances) {
        Matrix4d local = node.localMatrix();
        TransformGroup nodeTg = new TransformGroup();
        nodeTg.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
        nodeTg.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
        nodeTg.setTransform(new Transform3D(WorldTransformMath.toRowMajor(local)));
        parent.addChild(nodeTg);
        String key = nodeKey(node);
        if (key != null) {
            animatedNodes.put(key, new AnimatedNode(nodeTg, local));
        }

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
        for (ColladaNode child : node.children()) {
            buildNode(child, nodeTg, factory, meshBuilder, document, plan, appearances);
        }
    }

    /** The animator's address for a node: its id, or its name when id is absent. */
    private static String nodeKey(ColladaNode node) {
        if (node.id() != null && !node.id().isEmpty()) {
            return node.id();
        }
        return node.name() == null || node.name().isEmpty() ? null : node.name();
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
