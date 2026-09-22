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
import org.jdesktop.lg3d.wg.HostedWindowResizer;
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
 *   <li><b>right-click (BUTTON3)</b> on the window's green border - flip the
 *       window over to reveal a {@link StickyNote} back side; right-click the
 *       note again to flip back. The pickable backdrop border is the reliable
 *       gesture handle for <em>both</em> native 3D apps and Swing-to-Node
 *       windows, whose own content stays non-propagatable so it keeps its
 *       right-click for its context menus</li>
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
    private float frameWidth;
    private float frameHeight;

    // Chrome rebuilt / repositioned by relayout() on hosted-window resize.
    private Component3D backdrop;
    private GlassyPanel bodyDeco;
    private RectShadow bodyShadow;
    private Component3D minimizeButton;
    private Component3D maximizeButton;
    private Component3D closeButton;

    // Pre-maximize hosted size in native pixels, to restore on un-maximize.
    private int normalWidthPx;
    private int normalHeightPx;

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
        // content, parked behind it (z = -BODY_DEPTH) so it reads as a thin
        // green framed border around the app.
        //
        // It is pickable and mouse-event propagatable, which makes this border
        // the window's gesture handle. Because it sits *behind* the app content
        // it never occludes or intercepts clicks on the app itself - picks there
        // still hit the (closer) content first - yet a right-click / middle-drag
        // on the exposed border propagates up to the frame-level flip and rotate
        // listeners. This is the Frame3D analogue of the native look-and-feel's
        // propagatable moveRegion. Without it a decorated Frame3D has no
        // propagatable surface at all (app content is deliberately
        // non-propagatable), so the frame-level flip gesture could never be
        // reached - which is exactly why flipping to the sticky note silently
        // did nothing for both native 3D apps and Swing-to-Node windows.
        backdrop = new Component3D();
        populateBackdrop();
        backdrop.setTranslation(0.0f, 0.0f, -BODY_DEPTH);
        backdrop.setPickable(true);
        backdrop.setMouseEventPropagatable(true);
        tog.addChild(backdrop);

        initButtonAppearances();

        minimizeButton
            = new Button(buttonSize, minimizeButtonOffAppearance,
                buttonOnSize, minimizeButtonOnAppearance);
        minimizeButton.setCursor(Cursor3D.SMALL_CURSOR);
        minimizeButton.addListener(
            new MouseClickedEventAdapter(
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        frame.changeVisible(false);
                    }
                }));
        tog.addChild(minimizeButton);

        maximizeButton
            = new Button(buttonSize, maximizeButtonOffAppearance,
                buttonOnSize, maximizeButtonOnAppearance);
        maximizeButton.setCursor(Cursor3D.SMALL_CURSOR);
        maximizeButton.addListener(
            new MouseClickedEventAdapter(
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        toggleMaximized();
                    }
                }));
        tog.addChild(maximizeButton);

        closeButton
            = new Button(buttonSize, closeButtonOffAppearance,
                buttonOnSize, closeButtonOnAppearance);
        closeButton.setCursor(Cursor3D.SMALL_CURSOR);
        closeButton.addListener(
            new MouseClickedEventAdapter(
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        frame.changeEnabled(false);
                    }
                }));
        tog.addChild(closeButton);
        positionButtons();

        addChild(tog);

        // Rotation model 1: right-click flips the window over to a sticky note.
        // This is a frame-level listener, so it fires only when the pick
        // propagates up to the frame. App content (Swing quads, native 3D
        // widgets) is deliberately non-propagatable and keeps its own right-click
        // context menus, so the reliable handle is the decoration chrome: the
        // pickable + propagatable backdrop border above and, for
        // TitledSwingWindow, its propagatable title bar. A plain right-click on
        // the green window border therefore flips any decorated Frame3D, exactly
        // matching the plain BUTTON3 idiom of the native X11 look-and-feel.
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

    /**
     * Builds the backdrop glass + shadow once, at the current frame size. Only
     * called from the constructor (before the decoration is on a live graph);
     * later size changes go through {@link #relayout()}, which resizes the
     * existing geometry in place rather than rebuilding it.
     */
    private void populateBackdrop() {
        bodyDeco = new GlassyPanel(
            frameWidth + DECO_WIDTH * 2,
            frameHeight + DECO_WIDTH * 2,
            BODY_DEPTH,
            bodyApp);
        bodyShadow = new RectShadow(
            frameWidth + DECO_WIDTH * 2,
            frameHeight + DECO_WIDTH * 2,
            shadowN, shadowE, shadowS, shadowW, shadowI,
            -BODY_DEPTH,
            0.2f);
        backdrop.addChild(bodyDeco);
        backdrop.addChild(bodyShadow);
    }

    /** Pins the min/max/close buttons to the top-right of the current size. */
    private void positionButtons() {
        float inset = buttonSize * 0.6f;
        float z = BODY_DEPTH + 0.001f;
        float yPos = frameHeight * 0.5f - inset;
        minimizeButton.setTranslation(
            frameWidth * 0.5f - inset - buttonSize * 3.3f, yPos, z);
        maximizeButton.setTranslation(
            frameWidth * 0.5f - inset - buttonSize * 1.9f, yPos, z);
        closeButton.setTranslation(
            frameWidth * 0.5f - inset - buttonSize * 0.5f, yPos, z);
    }

    /**
     * Re-reads the frame's preferred size and re-lays-out the chrome (backdrop
     * and window buttons) to match. Called after a hosted Swing window is
     * resized (e.g. maximize) so the decoration tracks the new dimensions
     * without re-creating this object (which would double the frame-level
     * flip / rotate listeners installed in the constructor).
     */
    public void relayout() {
        Vector3f size = frame.getPreferredSize(new Vector3f());
        this.frameWidth = size.x;
        this.frameHeight = size.y;
        // Resize the glass + shadow geometry IN PLACE. Both shapes were built
        // with ALLOW_COORDINATE_WRITE, so setSize rewrites their vertex buffers
        // on the live graph legally. Recreating them would require removing
        // non-BranchGroup Shape3D children from a live Group (Java 3D forbids
        // this: RestrictedAccessException), and detaching the whole backdrop to
        // rebuild it off-graph then re-inserting it trips
        // MultipleParentException. In-place setSize avoids both.
        float w = frameWidth + DECO_WIDTH * 2;
        float h = frameHeight + DECO_WIDTH * 2;
        if (bodyDeco != null) {
            bodyDeco.setSize(w, h);
        }
        if (bodyShadow != null) {
            bodyShadow.setSize(w, h);
        }
        positionButtons();
    }

    private void toggleMaximized() {
        if (maximized) {
            // Restore the pre-maximize scale and position.
            frame.changeScale(normalScale, maximizeDuration);
            if (normalTranslation != null) {
                frame.changeTranslation(normalTranslation, maximizeDuration);
            }
            // A hosted Swing window was resized (not scaled) to maximize; put
            // its content back at the original pixel size.
            if (normalWidthPx > 0 && normalHeightPx > 0) {
                HostedWindowResizer.resize(
                    frame, normalWidthPx, normalHeightPx);
                normalWidthPx = 0;
                normalHeightPx = 0;
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
            // Leave a headroom band at the top of the screen. Filling exactly
            // to the screen top put the title strip - and with it the
            // minimize/maximize/close buttons - flush against (or past) the
            // top edge, where they cannot be clicked. The headroom keeps the
            // whole title bar comfortably inside the visible area.
            float headroom = 0.012f;
            float usableHeight = tk.getScreenHeight() - bottom - top - headroom;
            // World-space y of the centre of the usable area (screen centre is
            // y == 0). A bottom reserve shifts the band up by bottom/2, a top
            // reserve shifts it down by top/2, the headroom shifts it down.
            float centerY = (bottom - top - headroom) * 0.5f;

            if (HostedWindowResizer.isResizable(frame)) {
                // JFrame-like maximize: resize the hosted Swing content to the
                // usable area in pixels (full width) so Swing re-lays-out at
                // native text size, instead of magnifying a fixed-resolution
                // texture and letterboxing a narrow window.
                Vector3f pref = frame.getPreferredSize(new Vector3f());
                normalWidthPx = tk.widthPhysicalToNative(pref.x);
                normalHeightPx = tk.heightPhysicalToNative(pref.y);
                HostedWindowResizer.resize(
                    frame,
                    tk.widthPhysicalToNative(tk.getScreenWidth()),
                    tk.heightPhysicalToNative(usableHeight));
                frame.changeTranslation(
                    new Vector3f(0.0f, centerY, normalTranslation.z),
                    maximizeDuration);
                frame.postEvent(new Component3DToFrontEvent());
                maximized = true;
                return;
            }

            // Pure-3D windows keep the uniform (aspect-preserving) fit.
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
        // enable() must run BEFORE initialize(): initialize() dereferences the
        // Swing panel / title field / text area, and those are only created by
        // enable() (invoked from setEnabled(true)). Calling initialize() first
        // threw a NullPointerException that the event loop swallowed, so the
        // window never flipped at all - on native 3D apps and Swing-to-Node
        // windows alike. This mirrors the native look-and-feel, which calls
        // setEnabled(true) in createStickyNote() and initialize() afterwards.
        sn.setEnabled(true);
        sn.initialize(frame.getName(), wpx, hpx);
        // Flip back with the same plain right-click gesture that flipped over.
        // The note is itself the picked source, so its own listener fires
        // directly regardless of the propagation flag on the Swing quad.
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
