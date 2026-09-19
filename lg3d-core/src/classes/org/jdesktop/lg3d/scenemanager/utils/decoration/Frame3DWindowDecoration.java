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
package org.jdesktop.lg3d.scenemanager.utils.decoration;

import java.util.Timer;
import java.util.TimerTask;

import org.jogamp.vecmath.Vector3f;
import org.jdesktop.lg3d.scenemanager.utils.taskbar.Taskbar;
import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.utils.transparency.TransparencyOrderedGroup;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.action.AppearanceChangeAction;
import org.jdesktop.lg3d.utils.action.ScaleActionBoolean;
import org.jdesktop.lg3d.utils.eventaction.Component3DRotator;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.ImagePanel;
import org.jdesktop.lg3d.utils.shape.RectShadow;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.Component3DToFrontEvent;
import org.jdesktop.lg3d.wg.event.InputEvent3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.wg.event.MouseEvent3D;


/**
 * A reusable window-decoration layer for the pure-3D {@link Frame3D} path.
 *
 * <p>In dev mode the desktop hosts applications as {@code Frame3D}s managed by
 * {@code StandardAppContainer}; the min/max/close chrome and the right-click
 * "flip to sticky note" gesture otherwise live only in
 * {@link GlassyNativeWindowLookAndFeel}, which decorates native X11 windows
 * (an excluded code path). This class generalizes the self-contained
 * decoration pattern proven by the {@code Lg3dHelp} demo app so that any bare
 * {@code Frame3D} gets standard window buttons and 3D rotation.</p>
 *
 * <p>It is attached automatically by {@code StandardAppContainer.addFrame3D}.
 * Frames that build their own chrome can opt out by setting the
 * {@link #OPT_OUT_PROPERTY} property to {@link Boolean#TRUE} before they are
 * enabled.</p>
 *
 * <p>Behaviors:</p>
 * <ul>
 *   <li><b>close</b> - {@code frame.changeEnabled(false)}</li>
 *   <li><b>minimize</b> - {@code frame.changeVisible(false)}; the shelf
 *       thumbnail (already wired by {@code StandardAppContainer}) restores
 *       it</li>
 *   <li><b>maximize</b> - scale the frame to fill the viewport and bring it to
 *       front; clicking again restores the previous scale</li>
 *   <li><b>right-click (BUTTON3)</b> - flip the window over to reveal a
 *       {@link StickyNote} back side; right-click again flips back</li>
 *   <li><b>middle-drag (BUTTON2)</b> - free spin about an arbitrary axis</li>
 * </ul>
 */
public class Frame3DWindowDecoration extends Component3D {
    /** Key under which the decoration instance is stored on the frame. */
    public static final String PROPERTY_KEY = "lg3d.frame3d.decoration";
    /**
     * Frames that supply their own window chrome set this property to
     * {@link Boolean#TRUE} to suppress the automatic decoration.
     */
    public static final String OPT_OUT_PROPERTY = "lg3d.frame3d.decoration.optOut";

    /** Thickness (z) of the green backdrop slab, centred at z = -BODY_DEPTH. */
    public static final float BODY_DEPTH = 0.005f;
    /** Extra border the backdrop adds on every side of the frame content. */
    public static final float DECO_WIDTH = 0.005f;
    private static final float buttonSize = 0.005f;
    private static final float buttonOnSize = buttonSize * 1.15f;
    private static final float shadowN = 0.001f;
    private static final float shadowE = 0.0015f;
    private static final float shadowS = 0.002f;
    private static final float shadowW = 0.001f;
    private static final float shadowI = 0.001f;

    private static final int flipDuration = 500;
    private static final int flipResetDelay = 1000;
    private static final int maximizeDuration = 500;
    private static final float maximizeMargin = 0.95f;

    private static final Timer flipperTimer = new Timer("Frame3DDecoration:FlipperTimer", true);

    private final Frame3D frame;
    private final float frameWidth;
    private final float frameHeight;

    private final SimpleAppearance bodyApp
        = new SimpleAppearance(
            0.6f, 1.0f, 0.6f, 1.0f, SimpleAppearance.DISABLE_CULLING);

    private SimpleAppearance closeButtonOffAppearance;
    private SimpleAppearance closeButtonOnAppearance;
    private SimpleAppearance maximizeButtonOffAppearance;
    private SimpleAppearance maximizeButtonOnAppearance;
    private SimpleAppearance minimizeButtonOffAppearance;
    private SimpleAppearance minimizeButtonOnAppearance;

    private boolean maximized = false;
    private float normalScale = 1.0f;
    private Vector3f normalTranslation = null;

    private volatile boolean beingFlipped = false;
    private StickyNote stickyNote = null;

    public Frame3DWindowDecoration(Frame3D frame) {
        this.frame = frame;

        Vector3f size = frame.getPreferredSize(new Vector3f());
        this.frameWidth = size.x;
        this.frameHeight = size.y;

        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();

        // Body backdrop: a glassy panel + drop shadow slightly larger than the
        // content, parked behind it (z = -bodyDepth) so it reads as a thin
        // framed border and never occludes or intercepts clicks on the app.
        Component3D backdrop = new Component3D();
        GlassyPanel bodyDeco
            = new GlassyPanel(
                frameWidth + DECO_WIDTH * 2,
                frameHeight + DECO_WIDTH * 2,
                BODY_DEPTH,
                bodyApp);
        Shape3D bodyShadow
            = new RectShadow(
                frameWidth + DECO_WIDTH * 2,
                frameHeight + DECO_WIDTH * 2,
                shadowN, shadowE, shadowS, shadowW, shadowI,
                -BODY_DEPTH,
                0.2f);
        backdrop.addChild(bodyDeco);
        backdrop.addChild(bodyShadow);
        backdrop.setTranslation(0.0f, 0.0f, -BODY_DEPTH);
        backdrop.setPickable(false);
        tog.addChild(backdrop);

        initButtonAppearances();

        float inset = buttonSize * 0.6f;
        float z = BODY_DEPTH + 0.001f;
        float yPos = frameHeight * 0.5f - inset;

        Component3D minimizeButton
            = new Button(buttonSize, minimizeButtonOffAppearance,
                buttonOnSize, minimizeButtonOnAppearance);
        minimizeButton.setCursor(Cursor3D.SMALL_CURSOR);
        minimizeButton.setTranslation(
            frameWidth * 0.5f - inset - buttonSize * 3.3f, yPos, z);
        minimizeButton.addListener(
            new MouseClickedEventAdapter(
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        frame.changeVisible(false);
                    }
                }));
        tog.addChild(minimizeButton);

        Component3D maximizeButton
            = new Button(buttonSize, maximizeButtonOffAppearance,
                buttonOnSize, maximizeButtonOnAppearance);
        maximizeButton.setCursor(Cursor3D.SMALL_CURSOR);
        maximizeButton.setTranslation(
            frameWidth * 0.5f - inset - buttonSize * 1.9f, yPos, z);
        maximizeButton.addListener(
            new MouseClickedEventAdapter(
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        toggleMaximized();
                    }
                }));
        tog.addChild(maximizeButton);

        Component3D closeButton
            = new Button(buttonSize, closeButtonOffAppearance,
                buttonOnSize, closeButtonOnAppearance);
        closeButton.setCursor(Cursor3D.SMALL_CURSOR);
        closeButton.setTranslation(
            frameWidth * 0.5f - inset - buttonSize * 0.5f, yPos, z);
        closeButton.addListener(
            new MouseClickedEventAdapter(
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        frame.changeEnabled(false);
                    }
                }));
        tog.addChild(closeButton);

        addChild(tog);

        // Rotation model 1: right-click flips the window over to a sticky note.
        frame.addListener(
            new MouseClickedEventAdapter(
                MouseEvent3D.ButtonId.BUTTON3,
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        processFlipRequest();
                    }
                }));

        // Rotation model 2: middle-drag spins the window freely. BUTTON2 is
        // used so this never clashes with click-to-front, plain drag, or the
        // CTRL-modifier rotator that ZLayeredMovableLayout already installs.
        frame.addListener(
            new Component3DRotator(frame, InputEvent3D.ModifierId.BUTTON2));
    }

    private void toggleMaximized() {
        if (maximized) {
            // Restore the pre-maximize scale and position.
            frame.changeScale(normalScale, maximizeDuration);
            if (normalTranslation != null) {
                frame.changeTranslation(normalTranslation, maximizeDuration);
            }
            maximized = false;
        } else {
            Toolkit3D tk = Toolkit3D.getToolkit3D();
            normalScale = frame.getFinalScale();
            normalTranslation = frame.getFinalTranslation(new Vector3f());

            // The taskbar reserves a strip at one edge of the screen; a real
            // maximize must fill only the usable area between the reserved
            // strips and never cover the bar (whether it is docked top/bottom).
            float bottom = Taskbar.getReservedBottomHeight();
            float top = Taskbar.getReservedTopHeight();
            float usableHeight = tk.getScreenHeight() - bottom - top;
            // World-space y of the centre of the usable area (screen centre is
            // y == 0). A bottom reserve shifts the band up by bottom/2, a top
            // reserve shifts it down by top/2.
            float centerY = (bottom - top) * 0.5f;

            // Uniform (aspect-preserving) scale that fits the frame within the
            // usable area. min() picks the constraining axis so the aspect
            // ratio is never distorted.
            float fill = Math.min(
                tk.getScreenWidth() / frameWidth,
                usableHeight / frameHeight) * maximizeMargin;

            // A true maximize must also re-center the window: scaling alone
            // just grows the frame about its current origin, leaving an
            // off-center window enlarged in place rather than filling the
            // screen. Center it in the usable area (the layout keeps z at the
            // front plane via Component3DToFrontEvent below).
            frame.changeTranslation(
                new Vector3f(0.0f, centerY, normalTranslation.z), maximizeDuration);
            frame.changeScale(fill, maximizeDuration);
            frame.postEvent(new Component3DToFrontEvent());
            maximized = true;
        }
    }

    private void processFlipRequest() {
        if (beingFlipped) {
            return;
        }
        beingFlipped = true;
        if (stickyNote != null) {
            // Flip back to the front side.
            StickyNote sn = stickyNote;
            stickyNote = null;
            frame.setRotationAngle((float)-Math.PI);
            frame.changeRotationAngle(0f, flipDuration);
            frame.postEvent(new Component3DToFrontEvent());
            frame.removeChild(sn);
            sn.setEnabled(false);
            // Release the offscreen Swing resources (hidden JFrame, repaint
            // and resize hooks). Every flip creates a fresh StickyNote, so
            // without this each flip cycle would leak a whole offscreen frame.
            sn.dispose();
        } else {
            // Flip over to reveal the sticky note back side.
            stickyNote = createStickyNote();
            frame.addChild(stickyNote);
            frame.changeRotationAngle((float)Math.PI, flipDuration);
            frame.postEvent(new Component3DToFrontEvent());
        }
        flipperTimer.schedule(new TimerTask() {
            public void run() {
                beingFlipped = false;
            }
        }, flipResetDelay);
    }

    private StickyNote createStickyNote() {
        StickyNote sn = new StickyNote();
        // Place the note just outside the *back* face of the decoration slab.
        // GlassyPanel grows backwards from its local z=0 and the backdrop sits
        // at z = -BODY_DEPTH, so the opaque green glass spans
        // [-2*BODY_DEPTH, -BODY_DEPTH]. At the old -1.1*BODY_DEPTH the note
        // was buried inside that slab and, after the PI flip, the slab's
        // opaque back face (then closest to the viewer) hid it completely -
        // the window flipped to a plain green back on every app.
        sn.setTranslation(0.0f, 0.0f, BODY_DEPTH * -2.0f - 0.0002f);
        sn.setRotationAxis(0.0f, 1.0f, 0.0f);
        sn.setRotationAngle((float)Math.PI);
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        int wpx = tk.widthPhysicalToNative(frameWidth);
        int hpx = tk.heightPhysicalToNative(frameHeight);
        sn.initialize(frame.getName(), wpx, hpx);
        sn.setEnabled(true);
        sn.addListener(
            new MouseClickedEventAdapter(
                MouseEvent3D.ButtonId.BUTTON3,
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        processFlipRequest();
                    }
                }));
        return sn;
    }

    private void initButtonAppearances() {
        closeButtonOffAppearance
            = new ButtonAppearance(
                "resources/images/button/window-close.png", false);
        closeButtonOnAppearance
            = new ButtonAppearance(
                "resources/images/button/window-close.png", true);
        maximizeButtonOffAppearance
            = new ButtonAppearance(
                "resources/images/button/window-maximize.png", false);
        maximizeButtonOnAppearance
            = new ButtonAppearance(
                "resources/images/button/window-maximize.png", true);
        minimizeButtonOffAppearance
            = new ButtonAppearance(
                "resources/images/button/window-minimize.png", false);
        minimizeButtonOnAppearance
            = new ButtonAppearance(
                "resources/images/button/window-minimize.png", true);
    }

    private static class ButtonAppearance extends SimpleAppearance {
        private ButtonAppearance(String filename, boolean on) {
            super(0.0f, 0.0f, 0.0f, 0.0f,
                SimpleAppearance.DISABLE_CULLING
                    | SimpleAppearance.ENABLE_TEXTURE);

            if (on) {
                setColor(1.0f, 0.6f, 0.6f, 0.8f);
            } else {
                setColor(0.6f, 1.0f, 0.6f, 0.6f);
            }
            try {
                setTexture(this.getClass().getClassLoader().getResource(filename));
            } catch (Exception e) {
                throw new RuntimeException(
                    "failed to initialize window button: " + e);
            }
        }
    }

    private class Button extends Component3D {
        private Button(float size, Appearance app) {
            this(size, app, size, app);
        }

        private Button(float sizeOff, Appearance appOff,
            float sizeOn, Appearance appOn)
        {
            Shape3D shape = new ImagePanel(sizeOff, sizeOff);
            shape.setAppearance(appOff);
            addChild(shape);
            if (appOff != appOn) {
                addListener(
                    new MouseEnteredEventAdapter(
                        new AppearanceChangeAction(shape, appOn)));
            }
            if (sizeOff != sizeOn) {
                addListener(
                    new MouseEnteredEventAdapter(
                        new ScaleActionBoolean(this, sizeOn / sizeOff, 100)));
            }
        }
    }
}
