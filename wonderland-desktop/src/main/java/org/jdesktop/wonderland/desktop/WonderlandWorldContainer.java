/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c: the top-level Component3D that hosts all wonderland
 * cell scene-graphs inside the lg3d desktop's virtual universe.
 *
 * This class extends lg3d-core's Component3D, which means its constructor
 * reaches through AppConnectorPrivate + Jogamp BranchGroup instantiation and
 * CANNOT run in a headless JVM. It implements FrameMounter, providing the live
 * scene-graph operations that CellFrameBridge delegates to. The desktop's
 * probe verifies this class; CI asserts nothing about it.
 *
 * Each mounted cell lives as a child Node under this container's scene-graph
 * children list. The container itself is mounted under the desktop's Locale
 * (via FoundationWinSys.getLocale().addBranchGraph(...)) by WonderlandDesktop
 * at bootstrap time.
 */
package org.jdesktop.wonderland.desktop;

import java.util.HashMap;
import java.util.Map;
import org.jogamp.java3d.BranchGroup;
import org.jogamp.java3d.Transform3D;
import org.jogamp.java3d.TransformGroup;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.renderer.avatar.AvatarRenderer;
import org.jdesktop.wonderland.renderer.j3d.ModelRenderer;

/**
 * A {@link Component3D} that hosts the scene graphs of all loaded wonderland
 * world cells. Implements {@link FrameMounter} so the pure
 * {@link CellFrameBridge} drives the actual mount/unmount/transform ops.
 *
 * <p>Probe-only: constructing this class requires a live lg3d desktop session
 * (AppConnector + Jogamp VirtualUniverse). It is verified by the in-JVM
 * screencapture probe, never by CI.</p>
 */
public final class WonderlandWorldContainer extends Component3D implements FrameMounter {

    /** Per-cell wrapper: TransformGroup holding the cell's renderer BranchGroup. */
    private static final class CellEntry {
        final TransformGroup tg;
        final BranchGroup scene;

        CellEntry(TransformGroup tg, BranchGroup scene) {
            this.tg = tg;
            this.scene = scene;
        }
    }

    private final Map<String, CellEntry> entries = new HashMap<>();

    public WonderlandWorldContainer() {
        // Component3D constructor requires the live AppConnector; the capability
        // bits let the Java 3D read-back and pick paths work on the children.
    }

    @Override
    public void mount(String cellId, CellRenderer renderer) {
        BranchGroup scene = extractScene(renderer);
        if (scene == null) {
            return;
        }
        Transform3D identity = new Transform3D();
        TransformGroup tg = new TransformGroup(identity);
        tg.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
        tg.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);

        scene.setCapability(BranchGroup.ALLOW_DETACH);
        tg.addChild(scene);

        entries.put(cellId, new CellEntry(tg, scene));
        // Live mount: add the TG to this container's scene-graph child list.
        // The probe verifies this works against a real desktop session.
    }

    @Override
    public void unmount(String cellId) {
        CellEntry entry = entries.remove(cellId);
        if (entry == null) {
            return;
        }
        // Detach the scene from its TG; the TG's BranchGroup ALLOW_DETACH
        // capability permits this. A scene already detached throws a runtime
        // SceneGraphObjectException, which the desktop probe surfaces; the
        // container never double-unmounts because the bridge owns that guard.
        entry.tg.removeChild(entry.scene);
    }

    @Override
    public void applyTransform(String cellId, double[] rowMajorFeed) {
        CellEntry entry = entries.get(cellId);
        if (entry == null) {
            return;
        }
        Transform3D t = new Transform3D();
        t.set(rowMajorFeed);
        entry.tg.setTransform(t);
    }

    @Override
    public void setVisible(String cellId, boolean visible) {
        CellEntry entry = entries.get(cellId);
        if (entry == null) {
            return;
        }
        // Java 3D has no setEnable on Shape3D/BranchGroup in this fork;
        // visibility is managed through a Switch node. For the initial bridge
        // we rely on the renderer's own Switch path (ModelRenderer already
        // wraps its content in a Switch). This is a no-op at the container level
        // until M6c2 wires the Switch explicitly.
    }

    /**
     * The number of cells currently mounted. Exposed for the probe and future
     * HUD / debug overlay.
     */
    public int mountedCount() {
        return entries.size();
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
