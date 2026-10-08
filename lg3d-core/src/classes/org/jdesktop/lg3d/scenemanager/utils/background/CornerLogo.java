/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.scenemanager.utils.background;


import java.net.URL;

import org.jogamp.vecmath.Vector3f;

import org.jdesktop.lg3d.sg.Node;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.utils.actionadapter.Float2Adder;
import org.jdesktop.lg3d.utils.actionadapter.Float2Differ;
import org.jdesktop.lg3d.utils.actionadapter.Float2Scaler;
import org.jdesktop.lg3d.utils.animation.SpringRotationAnimationFloat;
import org.jdesktop.lg3d.utils.eventadapter.MouseMovedEventAdapter;
import org.jdesktop.lg3d.utils.shape.ImagePanel;
import org.jdesktop.lg3d.utils.shape.OriginTranslation;
import org.jdesktop.lg3d.utils.shape.PickableRegion;
import org.jdesktop.lg3d.wg.AnimationGroup;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.LgEventSource;


/**
 * The decorative 3D model that floats in the corner of the image backgrounds.
 * <p>
 * This replaces the old fixed {@code JavaLogo}: the outer node is a <em>stable</em>
 * drag handle (its {@link PickableRegion} is what {@link WindowRotator} and
 * {@link SceneTempZoomer} attach their mouse listeners to). Both model subtrees
 * are built once, up front, and {@link #setModel(Model)} simply shows one and
 * hides the other. The scene-graph structure therefore never changes after
 * construction - which matters because Java 3D forbids removing a non-BranchGroup
 * child from a live node - so the model can be swapped live, on a
 * {@code DesktopConfigChangeEvent}, without re-wiring the scene, and the
 * parallax/zoom gestures keep working across the change.
 * <p>
 * Two models ship today: {@link Model#JAVA}, the classic four-panel Java/Sun logo
 * that tilts with the mouse, and {@link Model#MASCOT}, the Looking-Glass mascot
 * (the same icon the 2D splash and the About window reflect) rendered as a single
 * textured panel with the same mouse-driven tilt.
 */
public class CornerLogo extends Component3D {

    /** The selectable corner-logo models. */
    public enum Model {
        /** The classic four-panel Java/Sun logo. */
        JAVA,
        /** The Looking-Glass mascot from the 2D splash / About icon. */
        MASCOT;

        /**
         * Maps a persisted {@code DesktopConfig} corner-logo id to a model.
         * {@code null}, blank or any unknown token falls back to {@link #JAVA}
         * (the historical default). Pure and side-effect free, so it is safe to
         * call headlessly.
         */
        public static Model fromConfig(String id) {
            if (id != null && "mascot".equalsIgnoreCase(id.trim())) {
                return MASCOT;
            }
            return JAVA;
        }
    }

    private static final int origHeight = 1331;
    private static final int origWidth = 649;

    /** Classpath location of the Looking-Glass mascot (the 2D splash icon). */
    private static final String MASCOT_PATH = "resources/images/icon/lg3d-logo.png";

    /** Edge length of the mascot panel, in the same normalized space (h==1.0). */
    private static final float MASCOT_SIZE = 0.5f;

    /** Container for the classic Java/Sun logo panels; shown for {@link Model#JAVA}. */
    private final Component3D javaRoot = new Component3D();

    /** Container for the Looking-Glass mascot panel; shown for {@link Model#MASCOT}. */
    private final Component3D mascotRoot = new Component3D();

    private Model model;

    public CornerLogo(Model model) {
        setName("CornerLogo");

        float width = (float) origWidth / origHeight;
        float height = 1.0f;

        // Stable pickable drag handle: never swapped, so the WindowRotator /
        // SceneTempZoomer listeners attached to this node stay valid for the
        // lifetime of the background regardless of model changes.
        addChild(
            new OriginTranslation(
                new PickableRegion(width, height),
                new Vector3f(0.0f, 0.0f, 0.1f)));

        // Both models are built once, before this node goes live, and setModel()
        // shows/hides them in place. Java 3D cannot remove a non-BranchGroup child
        // from a live node, so a rebuild-on-swap approach would throw once the
        // background is in the scene; toggling visibility avoids that entirely.
        buildJava();
        buildMascot();
        addChild(javaRoot);
        addChild(mascotRoot);

        setPreferredSize(new Vector3f(width, height, 0.0f));

        setModel(model);
    }

    /** The model currently displayed. */
    public Model getModel() {
        return model;
    }

    /**
     * Shows the artwork for {@code model} and hides the other, in place, keeping
     * the outer drag handle (and its listeners) untouched. A no-op when the
     * requested model is already showing. {@code null} is treated as
     * {@link Model#JAVA}. Uses the immediate {@code setVisible} (duration 0), so
     * it is safe to call on a live scene graph from a
     * {@code DesktopConfigChangeEvent} listener.
     */
    public void setModel(Model model) {
        Model m = (model == null) ? Model.JAVA : model;
        if (this.model == m) {
            return;
        }
        this.model = m;
        boolean java = (m == Model.JAVA);
        javaRoot.setVisible(java);
        mascotRoot.setVisible(!java);
    }

    public void reposition(float logoSize,
            float screenWidth, float screenHeight, float logoZPos,
            float screenHalfOfTanOfFov)
    {
        float logoX = (screenWidth  - logoZPos * screenHalfOfTanOfFov - logoSize) * 0.5f;
        float logoY = (screenHeight - logoZPos * screenHalfOfTanOfFov * screenHeight / screenWidth - logoSize) * 0.5f;

        setTranslation(logoX, logoY, logoZPos);
        setScale(logoSize);
    }

    private void buildJava() {
        setupPartial(0, 649, 453,   0, 870,    45.0f, (float)Math.toRadians(30));
        setupPartial(1, 632, 400,   2, 442,    60.0f, (float)Math.toRadians(60));
        setupPartial(2, 260, 450, 178,   6,  3000.0f, (float)Math.toRadians(360 * 10));
        setupPartial(3, 268, 324, 256, 170, -5000.0f, (float)Math.toRadians(360 * 10));
    }

    private void setupPartial(int i, float w, float h,
        float x, float y, float sensitivity, float limit)
    {
        // normalize the size
        w /= origHeight;
        h /= origHeight;
        x /= origHeight;
        y /= origHeight;

        AnimationGroup ag = new AnimationGroup();
        String filename
            = "resources/images/background/Java-logo-" + i + ".png";

        try {
            Shape3D logo = new ImagePanel(
                    this.getClass().getClassLoader().getResource(filename),
                    w, h, true, 0.6f);
            ag.addChild(logo);
        } catch (Exception e) {
            logger.warning("failed to read image file: " + e);
        }
        // move (0,0) to the center of the logo
        x += (w - (float)origWidth/origHeight) * 0.5f;
        y = (1.0f - h) * 0.5f - y;
        Node otag = new OriginTranslation(ag, new Vector3f(x, y, 0.0f));

        addTilt(ag, sensitivity, limit);

        javaRoot.addChild(otag);
    }

    /**
     * Builds the Looking-Glass mascot: a single square textured panel with the
     * same mouse-driven spring tilt as the Java panels, offset toward the upper
     * right of the component footprint so it occupies the same corner as the Java
     * artwork.
     */
    private void buildMascot() {
        AnimationGroup ag = new AnimationGroup();
        URL url = this.getClass().getClassLoader().getResource(MASCOT_PATH);
        try {
            Shape3D panel = new ImagePanel(url, MASCOT_SIZE, MASCOT_SIZE, true, 0.9f);
            ag.addChild(panel);
        } catch (Exception e) {
            logger.warning("failed to read mascot image file: " + e);
        }

        // Center the square on the component origin so that, once reposition()
        // places the node in the corner, the mascot occupies the same region as
        // the Java artwork (which is likewise centred on the origin). Keeping it
        // inside the footprint avoids clipping at the screen edge.
        float x = 0.0f;
        float y = 0.0f;
        Node otag = new OriginTranslation(ag, new Vector3f(x, y, 0.0f));

        addTilt(ag, 60.0f, (float)Math.toRadians(60));

        mascotRoot.addChild(otag);
    }

    /**
     * Wires a Y-axis spring rotation on {@code ag} driven by mouse movement. The
     * listener lives for the lifetime of this corner logo (both models are built
     * once and never torn down), matching the old {@code JavaLogo} behaviour.
     */
    private void addTilt(AnimationGroup ag, float sensitivity, float limit) {
        SpringRotationAnimationFloat anim
            = new SpringRotationAnimationFloat(new Vector3f(0.0f, 1.0f, 0.0f), 4000);
        ag.setAnimation(anim);
        LgEventListener listener =
            new MouseMovedEventAdapter(
                new Float2Differ(
                    new Float2Scaler(sensitivity, 0.0f, limit,
                        new Float2Adder(anim))));
        LgEventConnector.getLgEventConnector().addListener(
            LgEventSource.ALL_SOURCES, listener);
    }
}
