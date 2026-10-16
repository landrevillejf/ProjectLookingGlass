/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps wonderland bridge (M6c/M6c2; moved out of wonderland-desktop): the
 * top-level Component3D that hosts all wonderland cell scene-graphs inside the
 * lg3d desktop's virtual universe.
 *
 * This class extends lg3d-core's Component3D, which means its constructor
 * reaches through AppConnectorPrivate + Jogamp BranchGroup instantiation and
 * CANNOT run in a headless JVM. It implements FrameMounter, providing the live
 * scene-graph operations that CellFrameBridge delegates to. The desktop's
 * probe verifies this class; CI asserts nothing about it.
 *
 * M6c2 is what makes the mount real. lg3d's own scene graph (org.jdesktop.lg3d.sg)
 * is a parallel hierarchy to Java 3D -- its nodes are NOT Jogamp nodes -- so a
 * cell's BranchGroup cannot be added to a Component3D directly. The desktop ships
 * the bridge for exactly this case: org.jdesktop.lg3d.wg.Java3DGraph, whose
 * addJ3dChild() attaches a Java 3D branch under the component AND sets the
 * picking capabilities (PickTool.INTERSECT_COORD on every Shape3D,
 * ALLOW_PARENT_READ on every node) that the desktop's PickEngine needs to see it.
 * That is the 2006 lg3d-in-Wonderland path, kept here rather than reinvented.
 *
 * Per cell the mounted shape is
 *   cellRoot (BranchGroup, ALLOW_DETACH)
 *     -> pose       (TransformGroup: where the world puts the cell)
 *       -> visibility (Switch: the desktop's hide/show, independent of the
 *                       renderer's own status Switch, so hiding a cell never
 *                       fights the client's VISIBLE/DISK bookkeeping)
 *         -> the renderer's own BranchGroup
 * The pose and the Switch belong to the container, not to the renderer: the
 * bridge feeds a row-major matrix and a boolean, and neither is a CellRenderer
 * concept.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.jogamp.java3d.BranchGroup;
import org.jogamp.java3d.Node;
import org.jogamp.java3d.Switch;
import org.jogamp.java3d.Transform3D;
import org.jogamp.java3d.TransformGroup;
import org.jdesktop.j3d.utils.scenegraph.traverser.NodeChangeProcessor;
import org.jdesktop.j3d.utils.scenegraph.traverser.TreeScan;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Java3DGraph;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.MouseEvent3D;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.desktop.FrameMounter;
import org.jdesktop.wonderland.renderer.avatar.AvatarRenderer;
import org.jdesktop.wonderland.renderer.j3d.ModelRenderer;

/**
 * A {@link Component3D} that hosts the scene graphs of all loaded wonderland
 * world cells. Implements {@link FrameMounter} so the pure
 * {@link org.jdesktop.wonderland.desktop.CellFrameBridge} drives the actual
 * mount/unmount/transform ops.
 *
 * <p>Probe-only: constructing this class requires a live lg3d desktop session
 * (AppConnector + Jogamp VirtualUniverse). It is verified by the in-JVM
 * screencapture probe, never by CI.</p>
 */
public final class WonderlandWorldContainer extends Component3D implements FrameMounter {

    /** Everything the container owns in Java 3D, per mounted cell. */
    private static final class CellEntry {
        final BranchGroup root;
        final TransformGroup pose;
        final Switch visibility;
        final BranchGroup scene;
        /** The nodes registered for pick attribution, so unmount can drop them. */
        final List<Node> owned;
        /** M7c: non-null for avatar cells, so unmount can stop its ticker. */
        final AvatarRenderer avatar;

        CellEntry(BranchGroup root, TransformGroup pose, Switch visibility,
                BranchGroup scene, List<Node> owned, AvatarRenderer avatar) {
            this.root = root;
            this.pose = pose;
            this.visibility = visibility;
            this.scene = scene;
            this.owned = owned;
            this.avatar = avatar;
        }
    }

    /**
     * The single Java 3D bridge node. Every mounted cell hangs off it, so the
     * world shares one transform space with the frame that hosts it.
     */
    private final Java3DGraph j3dBridge = new Java3DGraph();

    private final Map<String, CellEntry> entries = new HashMap<>();

    /**
     * node identity -> the cell that owns it. Identity-keyed because two
     * structurally identical nodes are still different nodes, and because the
     * pick engine hands back the very object that is in the scene graph.
     */
    private final Map<Node, String> cellOwners = new IdentityHashMap<>();

    public WonderlandWorldContainer() {
        // The bridge is an ordinary lg3d component child, so it is added while
        // still off-screen; its own addJ3dChild then carries the Java 3D world
        // in.
        addChild(j3dBridge);
    }

    @Override
    public void mount(String cellId, CellRenderer renderer) {
        BranchGroup scene = extractScene(renderer);
        if (scene == null) {
            return;
        }

        BranchGroup root = new BranchGroup();
        // detach() on unmount, and addJ3dChild's capability walk, both need it.
        root.setCapability(BranchGroup.ALLOW_DETACH);

        TransformGroup pose = new TransformGroup();
        pose.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
        pose.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);

        Switch visibility = new Switch(Switch.CHILD_ALL);
        visibility.setCapability(Switch.ALLOW_SWITCH_WRITE);

        scene.setCapability(BranchGroup.ALLOW_DETACH);
        visibility.addChild(scene);
        pose.addChild(visibility);
        root.addChild(pose);

        // Register pick attribution BEFORE the branch goes live: the traversal
        // needs no capabilities on a detached branch, and the ids must be in
        // place before the first mouse event can reach the new geometry.
        List<Node> owned = registerOwners(root, cellId);

        j3dBridge.addJ3dChild(root);

        // M7c: a mounted avatar animates while it is in the universe; the
        // daemon ticker writes its clip poses. start is a no-op for a body
        // that failed to load (no director, no thread).
        AvatarRenderer avatar = renderer instanceof AvatarRenderer ar ? ar : null;
        if (avatar != null) {
            avatar.startAnimationClock();
        }
        entries.put(cellId, new CellEntry(root, pose, visibility, scene, owned, avatar));
    }

    @Override
    public void unmount(String cellId) {
        CellEntry entry = entries.remove(cellId);
        if (entry == null) {
            return;
        }
        if (entry.avatar != null) {
            entry.avatar.stopAnimationClock(); // M7c: no ticker outlives its cell
        }
        for (Node node : entry.owned) {
            cellOwners.remove(node);
        }
        // BranchGroup.detach() pulls the whole cell branch out of the live
        // graph in one move; the ALLOW_DETACH set at mount is what allows it.
        entry.root.detach();
    }

    @Override
    public void applyTransform(String cellId, double[] rowMajorFeed) {
        CellEntry entry = entries.get(cellId);
        if (entry == null) {
            return;
        }
        Transform3D t = new Transform3D();
        t.set(rowMajorFeed);
        entry.pose.setTransform(t);
    }

    @Override
    public void setVisible(String cellId, boolean visible) {
        CellEntry entry = entries.get(cellId);
        if (entry == null) {
            return;
        }
        // M6c2: the per-cell Switch. Java 3D has no setEnable on a BranchGroup,
        // so a Switch is the canonical gate -- and gating here (not in the
        // renderer) is what lets the desktop hide a cell without touching the
        // client's own VISIBLE / DISK status.
        entry.visibility.setWhichChild(visible
                ? Switch.CHILD_ALL : Switch.CHILD_NONE);
    }

    /**
     * M6c2: the cell that owns {@code node}, or null when the node is not part
     * of a mounted cell (the desktop's own decorations, or a cell already
     * unmounted). Lookup is by node identity, and every node of a cell branch
     * is registered at mount, so no parent-walk -- and no capability the port
     * cannot guarantee -- is needed.
     */
    public String cellIdForNode(Node node) {
        return node == null ? null : cellOwners.get(node);
    }

    /**
     * M6c2: the mounted cells this pick hit, nearest first, with duplicates
     * collapsed -- a cell whose geometry is a stack of Shape3Ds is one hit, not
     * eight. Feeds {@link org.jdesktop.wonderland.desktop.PickRouter#routeHits}.
     *
     * @param event the desktop's picked mouse event; may be null
     * @return the cell ids under the pointer, nearest first
     */
    public List<String> cellIdsFor(MouseEvent3D event) {
        List<String> hitCells = new ArrayList<>();
        if (event == null) {
            return hitCells;
        }
        int count = event.numIntersectedNodes();
        for (int i = 0; i < count; i++) {
            String cellId = cellIdForNode(event.getIntersectedNode(i));
            if (cellId != null && !hitCells.contains(cellId)) {
                hitCells.add(cellId);
            }
        }
        return hitCells;
    }

    /**
     * Register {@code listener} on the world layer. The lg3d event connector only
     * takes a listener once the component is live (and it flips the source mask
     * for every class the listener declares), so this runs after the desktop has
     * shown the world frame.
     */
    public void addWorldListener(LgEventListener listener) {
        if (listener != null) {
            j3dBridge.addListener(listener);
        }
    }

    /**
     * The number of cells currently mounted. Exposed for the probe and future
     * HUD / debug overlay.
     */
    public int mountedCount() {
        return entries.size();
    }

    /**
     * Walks the freshly built (still detached) cell branch and records every
     * node against {@code cellId}.
     *
     * @return the nodes registered, so unmount can unregister exactly them
     */
    private List<Node> registerOwners(BranchGroup root, String cellId) {
        List<Node> owned = new ArrayList<>();
        TreeScan.findNode(root, new Class[] {Node.class}, new NodeChangeProcessor() {
            @Override
            public boolean changeNode(Node node) {
                cellOwners.put(node, cellId);
                owned.add(node);
                return true;
            }
        }, false, true);
        return owned;
    }

    /**
     * Extract the Jogamp BranchGroup from a CellRenderer, handling both
     * ModelRenderer (M6a) and AvatarRenderer (M6b) paths. Package-private so
     * the pure negative branch (a renderer that carries no extractable scene)
     * is asserted in CI without constructing a Jogamp object.
     */
    static BranchGroup extractScene(CellRenderer renderer) {
        if (renderer instanceof ModelRenderer mr) {
            return mr.getScene();
        }
        if (renderer instanceof AvatarRenderer ar) {
            return ar.getScene();
        }
        return null;
    }
}
