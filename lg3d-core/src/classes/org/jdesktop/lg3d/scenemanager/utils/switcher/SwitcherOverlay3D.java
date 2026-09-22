/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jdesktop.lg3d.sg.Transform3D;
import org.jdesktop.lg3d.sg.utils.transparency.TransparencyOrderedGroup;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Container3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.switcher.SwitcherItem;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Point3f;
import org.jogamp.vecmath.Vector3f;

/**
 * The scene-graph view of the application switcher on the 3D desktop: a glassy
 * panel, floating in front of every window, listing the open applications
 * most-recently-used-first with the current selection highlighted.
 *
 * <p>It renders whatever a {@link org.jdesktop.lg3d.wg.switcher.SwitcherController}
 * exposes and holds no switching state of its own, so the cycling behaviour
 * stays unit-testable in the controller while this class is pure presentation.
 * The panel is lifted toward the eye with the same perspective-compensated pose
 * the start menu uses ({@code StartMenuModel.compensatedFrontPose}) so it both
 * draws over and picks in front of application windows while keeping its
 * apparent on-screen size.</p>
 *
 * <p>Labels are plain glassy text rather than live {@code Thumbnail} nodes: a
 * {@code Frame3D}'s single thumbnail is already parented in the taskbar and a
 * scene-graph node cannot have two parents, so the overlay lists window names.
 * {@link SwitcherItem} still carries an optional thumbnail image for a future
 * textured-preview overlay.</p>
 */
public class SwitcherOverlay3D extends Component3D {

    private static final Logger logger
            = Logger.getLogger("lg.scenemanager.switcher");

    /** Fraction of the eye distance the overlay is lifted toward the viewer. */
    private static final float FRONT_WORLD_Z_FRACTION = 0.4f;

    private static final float WIDTH = 0.60f;
    private static final float HEIGHT = 0.40f;
    private static final float DEPTH = 0.005f;
    private static final float PAD = 0.035f;
    private static final float ROW_H = 0.042f;
    private static final float LABEL_H = 0.028f;
    /** Selected rows are drawn slightly larger to stand out. */
    private static final float SELECTED_SCALE = 1.18f;

    private static final Color4f TEXT_COLOR = new Color4f(0.92f, 0.98f, 0.92f, 1.0f);

    private final Container3D rows = new Container3D();
    private final List<Component3D> labels = new ArrayList<>();

    public SwitcherOverlay3D() {
        setName("SwitcherOverlay3D");

        SimpleAppearance panelApp = new SimpleAppearance(
                0.55f, 0.85f, 0.60f, 0.55f, SimpleAppearance.DISABLE_CULLING);
        GlassyPanel panel = new GlassyPanel(WIDTH, HEIGHT, DEPTH, panelApp);

        rows.setTranslation(0.0f, 0.0f, DEPTH);

        Component3D content = new Component3D();
        content.addChild(panel);
        content.addChild(rows);

        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
        tog.addChild(content);
        addChild(tog);

        // Start hidden; show() lifts it into view on the first Alt+Tab.
        setVisible(false);
        setMouseEventEnabled(false);
    }

    /**
     * Renders {@code items} with {@code selectedIndex} highlighted and lifts the
     * panel in front of the windows. Called when a cycle session opens.
     */
    public void show(List<SwitcherItem> items, int selectedIndex) {
        rebuild(items);
        highlight(selectedIndex);
        liftToFront();
        setVisible(true);
    }

    /**
     * Re-highlights {@code selectedIndex} without rebuilding the labels (the
     * item list is fixed for the duration of a cycle session). Called as the
     * selection steps.
     */
    public void update(int selectedIndex) {
        highlight(selectedIndex);
    }

    /** Hides the panel; called when a session commits or cancels. */
    public void hide() {
        setVisible(false);
    }

    private void rebuild(List<SwitcherItem> items) {
        for (Component3D old : labels) {
            rows.removeChild(old);
        }
        labels.clear();
        if (items == null) {
            return;
        }
        float maxWidth = WIDTH - 2.0f * PAD;
        float x = -WIDTH / 2.0f + PAD;
        float topY = HEIGHT / 2.0f - PAD;
        int i = 0;
        for (SwitcherItem item : items) {
            GlassyText2D text = new GlassyText2D(
                    labelText(item), maxWidth, LABEL_H, TEXT_COLOR);
            // GlassyText2D is a bare Shape3D whose quad origin is its lower-left
            // corner; wrap it in a Component3D so it can be positioned and
            // scaled, stepping each row down from the top of the panel.
            Component3D holder = new Component3D();
            holder.addChild(text);
            float y = topY - (i + 1) * ROW_H;
            holder.setTranslation(x, y, 0.0f);
            rows.addChild(holder);
            labels.add(holder);
            i++;
        }
    }

    private void highlight(int selectedIndex) {
        for (int i = 0; i < labels.size(); i++) {
            labels.get(i).setScale(i == selectedIndex ? SELECTED_SCALE : 1.0f);
        }
    }

    private static String labelText(SwitcherItem item) {
        String name = item.getName();
        return (name == null || name.isEmpty()) ? "(untitled)" : name;
    }

    /**
     * Lift the panel toward the eye so it sorts in front of application windows,
     * compensating the world position and node scale so its apparent on-screen
     * position and size are unchanged. Mirrors {@code StartMenuModel}'s proven
     * perspective handling; falls back to a plain centred pose if the parent or
     * eye position is not yet available.
     */
    private void liftToFront() {
        float[] plain = { 0.0f, 0.0f, 0.0f, 1.0f };
        float[] pose = plain;
        try {
            org.jdesktop.lg3d.sg.Node parent = getParent();
            if (parent != null) {
                Transform3D parentWorld = new Transform3D();
                parent.getLocalToVworld(parentWorld);
                Point3f eye = Toolkit3D.getToolkit3D()
                        .getEyePositionInVworld(new Point3f());
                Point3f refWorld = new Point3f(0.0f, 0.0f, 0.0f);
                parentWorld.transform(refWorld);
                float frontZ = eye.z * FRONT_WORLD_Z_FRACTION;
                float denom = eye.z - refWorld.z;
                if (denom > 0.01f && frontZ < eye.z - 0.01f) {
                    float r = (eye.z - frontZ) / denom;
                    Point3f frontWorld = new Point3f(
                            refWorld.x * r, refWorld.y * r, frontZ);
                    parentWorld.invert();
                    parentWorld.transform(frontWorld);
                    pose = new float[] { frontWorld.x, frontWorld.y, frontWorld.z, r };
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "switcher overlay lift fell back to plain pose", e);
            pose = plain;
        }
        setScale(pose[3]);
        setTranslation(pose[0], pose[1], pose[2]);
    }
}
