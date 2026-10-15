/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: show a world in its own window and walk through it.
 *
 * The desktop's own 3D path ({@code WonderlandWorld3D} over a {@code Frame3D})
 * needs the lg3d virtual universe, which the 2D shell does not run -- so a world
 * had no way onto the screen at all. This is the way: an AWT {@code Frame} with a
 * {@code Canvas3D} and a {@code SimpleUniverse}, exactly the shape the 2006
 * Wonderland client used, and exactly what the capability probe proved renders on
 * this stack (Jogamp Java 3D 1.7.2 draws and reads back a raster).
 *
 * Navigation is the walk-the-world minimum, not a full avatar controller: the
 * arrow keys and W/S glide, A/D and Q/E turn, PageUp/PageDown rise and sink, and
 * Home returns to the framing view. That is enough to stand in a room and look
 * around, which is the claim this class has to keep. It is deliberately driven
 * from an AWT KeyListener writing the view-platform transform rather than a
 * {@code ViewPlatformBehavior}: the behaviors package needs a WakeupOnSWInput and
 * a focus proxy, and a plain write to a capability-enabled TransformGroup is both
 * less machinery and easier to reason about from the EDT.
 *
 * <p>The framing view and the walking view are two poses, and which one a window
 * opens in is the difference between a picture of a world and a place to be.
 * {@link #frameHome()} is the vignette: outside, looking down, everything in
 * frame. {@link #walkInside()} is entering: the eye at a person's height on the
 * near edge of the subject, level, so the first thing seen is the world
 * standing in front of you the way the 2009 client showed it -- which is what
 * the browser's Enter World button is for, and what Home returns to.
 *
 * {@link #capture} is the same read the desktop's own {@code ScreenCaptureBehavior}
 * performs -- a {@code GraphicsContext3D.readRaster} posted to a Behavior, because
 * a raster read belongs on the scene-graph thread -- which is what lets the world
 * browser show a real view of a world instead of a diagram of one.
 *
 * Not constructed in CI: a Canvas3D needs a display and the native GL stack.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.jogamp.java3d.Behavior;
import org.jogamp.java3d.BoundingSphere;
import org.jogamp.java3d.BranchGroup;
import org.jogamp.java3d.Canvas3D;
import org.jogamp.java3d.GraphicsContext3D;
import org.jogamp.java3d.ImageComponent2D;
import org.jogamp.java3d.Raster;
import org.jogamp.java3d.Transform3D;
import org.jogamp.java3d.TransformGroup;
import org.jogamp.java3d.View;
import org.jogamp.java3d.WakeupCriterion;
import org.jogamp.java3d.WakeupOnBehaviorPost;
import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Point3f;
import org.jogamp.vecmath.Vector3d;
import org.jogamp.java3d.utils.universe.SimpleUniverse;
import org.jogamp.java3d.utils.universe.ViewingPlatform;

/** A live, navigable Java 3D window onto one {@link WorldScene}. */
public final class WorldViewer implements AutoCloseable {

    /** Metres moved per key press: a room is a few metres, so this is a step. */
    private static final float STEP_M = 1.2f;

    /** Radians turned per key press, about 8 degrees. */
    private static final float TURN_RAD = (float) Math.toRadians(8);

    /**
     * Back-clip reach as a multiple of the world's total extent. The sky box is
     * drawn at three extents ({@code WorldScene.SKY_FACTOR}) and the framing eye
     * sits a further {@link #CLIP_STANDOFF} subject-radii out, so the clip has to
     * clear three extents plus the camera's own standoff whatever the world is.
     *
     * <p>Java 3D's default far plane is 1000 m and {@code SimpleUniverse} never
     * widens it, which was fine until the framing was corrected: at the pavilion's
     * measured extent of 169.7 m the dome wall is 509 m out, beyond the default
     * clip, and the whole frame came back a single black value
     * (build-gradle/scratch/WlProbe8, {@code distinct=1 middle=#0}) with 886 live
     * shapes in the graph. A world clipped to nothing is indistinguishable from a
     * world that failed to build, so the clip is derived from the scene instead —
     * and it is sized off the extent, not the subject radius, because a large
     * world with a small building has a dome far bigger than the thing framed
     * (the same probe's first coloured frame had a black upper half for exactly
     * that reason).</p>
     */
    private static final double SKY_REACH = 6.0;

    /**
     * The view's vertical field of view, set explicitly rather than inherited.
     *
     * <p>{@link #frameHome()} derives the home distance from it, so the two have
     * to name the same number; and a preview that silently inherited whatever
     * default the universe picked would frame a different world on a different
     * stack.</p>
     */
    private static final float FIELD_OF_VIEW_RAD = (float) Math.toRadians(45);

    /**
     * How far back the sky dome has to be visible behind any home view, as a
     * multiple of the subject's corner radius. {@link #frameHome()} no longer
     * sits at a fixed multiple -- it fits the subject's box to the frame -- but
     * a sphere of radius r needs at least r/sin(fov/2) = 2.61 r to fit whole,
     * and this rounds that up: the clip is an upper bound over whatever pose the
     * framing chooses, not the pose itself.
     */
    private static final double CLIP_STANDOFF = 2.75;

    /**
     * How far the home view backs off from the distance that just fits the
     * subject, so a world does not press against the edge of the vignette.
     */
    private static final double HOME_MARGIN = 1.18;

    /**
     * How far below horizontal the home view looks at a world that is all ground
     * -- a clinic floor, a lawn, a plaza. Seen from near the horizon such a world
     * is a thin band at the bottom of the frame under four fifths of sky, which
     * is what the outpatient clinic read back as (a captured frame of eight
     * distinct colours, 78 % of them sky, and the preview refusing a picture it
     * had in fact drawn). Looking down shows the layout instead.
     */
    private static final double FLAT_TILT_RAD = Math.toRadians(52);

    /** How far below horizontal the home view looks at a world as tall as it is wide. */
    private static final double TALL_TILT_RAD = Math.toRadians(18);

    /**
     * How many times the home framing re-measures the corners. The fit is not
     * linear in distance (moving back also flattens the perspective), but it
     * converges in two or three passes for any box that is not wrapping the
     * camera, and the loop is a bound rather than a claim of convergence.
     */
    private static final int CORNER_FIT_PASSES = 4;

    /**
     * A standing person's eye height in metres. The walking view puts the eye
     * this far above the subject's floor, which is what makes a doorway read as
     * a doorway and a room as a room rather than as a diagram of one.
     */
    private static final double EYE_HEIGHT_M = 1.6;

    /**
     * How far outside the subject's near edge the walker starts, in metres.
     * Inside the box is where the furniture and the residents are, and entering
     * a world with your nose against one of them (which is what starting at 90 %
     * of the half-depth did: the clinic's receptionist filled the window) is not
     * entering it. One and a half metres back puts the whole world ahead of you,
     * still at walking height, on the ground the world's own floor extends to.
     */
    private static final double WALK_APPROACH_M = 1.5;

    /**
     * How far outside the subject's volume a walking eye may still stand, in
     * metres. It has to be at least {@link #WALK_APPROACH_M}, or the entering pose
     * is snapped inside the door the moment it is written; the extra half metre is
     * the doorstep itself.
     */
    private static final double WALK_BOUND_MARGIN_M = 2.0;

    /** How close to the floor a walking eye may go: crouching, not tunnelling. */
    private static final double WALK_MIN_EYE_M = 0.35;

    /** How far above the tallest thing in the world a walking eye may rise. */
    private static final double WALK_CEILING_M = 3.0;

    private final String title;
    private final WorldScene scene;
    private final Frame frame;
    private final Canvas3D canvas;
    private SimpleUniverse universe;
    private TransformGroup viewTransform;
    private CaptureBehavior capture;

    /** The eye's position in world space; a vector because Transform3D wants one. */
    private Vector3d eye = new Vector3d();
    private double yaw;
    private double pitch;

    /**
     * Whether this window was opened to walk in rather than to look at. Set
     * before {@link #show()} and read by it, because the pose has to be written
     * once a view platform exists to receive it.
     */
    private boolean walkMode;

    /**
     * Whether the eye is currently at a walking pose, as opposed to standing where
     * {@link #frameHome()} put it. {@link #keepInside()} reads this rather than
     * {@link #walkMode}: the two differ the moment a walking window is reframed to
     * the map view, and clamping that pose back into the world's box is what made
     * one probe's "map view" a picture of the back of a receptionist's head.
     */
    private boolean atWalkPose;

    /**
     * The panel docked on the window's right, or {@code null}. Kept for two
     * reasons: {@link #setSidebar} replaces one panel with another instead of
     * stacking them, and {@link #keyboardIsForTheWorld()} has to know that the
     * window now holds a text field -- which is full of the very keys the walker
     * uses, since W A S D Q E are letters before they are commands. AWT hands a
     * frame its children's unconsumed {@code KEY_PRESSED} events, so without
     * that test typing "water" into a chat box walked the eye west five times.
     */
    private volatile Component sidebar;

    /**
     * Builds the window but does not show it; {@link #show()} attaches the scene.
     *
     * @param title  the window title, normally the world's name
     * @param scene  the world to show
     * @param width  the canvas width in pixels
     * @param height the canvas height in pixels
     */
    public WorldViewer(String title, WorldScene scene, int width, int height) {
        this.title = title;
        this.scene = scene;
        GraphicsConfiguration config = SimpleUniverse.getPreferredConfiguration();
        this.canvas = new Canvas3D(config);
        this.frame = new Frame(title == null ? "Wonderland" : title);
        frame.setLayout(new BorderLayout());
        frame.add(canvas, BorderLayout.CENTER);
        frame.setSize(width, height);
        frame.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (keyboardIsForTheWorld()) {
                    handleKey(event.getKeyCode());
                }
            }
        });
        // Drag to look: the mouse is the natural "turn my head" on this platform,
        // and a viewer nobody can aim is a screensaver.
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                frame.requestFocus();
            }
        });
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                close();
            }
        });
    }

    /**
     * Shows the window and puts the world in it. Idempotent: a second call only
     * raises the existing window, so the browser can point at another world by
     * closing this one and opening the next.
     */
    public void show() {
        frame.setVisible(true);
        if (universe != null) {
            frame.toFront();
            return;
        }
        universe = new SimpleUniverse(canvas);
        ViewingPlatform platform = universe.getViewingPlatform();
        platform.setNominalViewingTransform();
        // See SKY_REACH: the default far plane clips a world its own size.
        View view = universe.getViewer().getView();
        view.setFieldOfView(FIELD_OF_VIEW_RAD);
        view.setBackClipDistance(Math.max(1000.0,
                scene.reach() * SKY_REACH + scene.radius() * CLIP_STANDOFF));
        view.setFrontClipDistance(0.05);
        viewTransform = platform.getViewPlatformTransform();
        BranchGroup root = scene.root();
        capture = new CaptureBehavior(canvas);
        root.addChild(capture);
        universe.addBranchGraph(root);
        if (walkMode) {
            frameWalk();
        } else {
            frameHome();
        }
    }

    /** Whether the world is on screen. */
    public boolean isShowing() {
        return universe != null && frame.isShowing();
    }

    /** The window's canvas, for a caller that wants to composite it elsewhere. */
    public Canvas3D getCanvas() {
        return canvas;
    }

    /**
     * Read the current frame back as an image, on the scene-graph thread.
     *
     * @param timeoutMillis how long to wait for the render thread
     * @return the pixels, or {@code null} when the window is not live or the read
     *         did not answer in time -- a missing snapshot is never a failure
     */
    public BufferedImage capture(long timeoutMillis) {
        CaptureBehavior poster = capture;
        if (poster == null || !frame.isShowing()) {
            return null;
        }
        return poster.grab(timeoutMillis);
    }

    /** The world this viewer shows. */
    public WorldScene getScene() {
        return scene;
    }

    /**
     * Dock a panel on the window's right -- the roster, the transcript, the
     * inventory, whatever the walk needs beside the view. Pass {@code null} to
     * take it back off.
     *
     * <p>Callable before or after {@link #show()}: the frame's layout is
     * revalidated either way, so a visitor who joins a world mid-session gets
     * the panel and the canvas gives back the width. The panel is a plain AWT
     * {@code Component} rather than this module's type because the walk-in's
     * window belongs here while what it shows belongs to whoever opened it --
     * the same seam {@code TitledSwingWindow} is for the 3D desktop.</p>
     *
     * @param panel the component to dock, or {@code null} to undock
     */
    public void setSidebar(Component panel) {
        Component previous = sidebar;
        if (previous != null) {
            frame.remove(previous);
        }
        sidebar = panel;
        if (panel != null) {
            frame.add(panel, BorderLayout.EAST);
        }
        frame.validate();
    }

    /** The docked panel, or {@code null}. */
    public Component getSidebar() {
        return sidebar;
    }

    /**
     * Whether the keystroke belongs to the walker rather than to a widget.
     *
     * <p>The frame itself and its canvas are the world's own keyboard; anything
     * else that holds focus -- a chat field, a roster row being selected -- is
     * not, and its keys must reach it alone. A null focus owner (a window that
     * has not been clicked yet) reads as the world's, which is what the visitor
     * expects of arrow keys on a freshly opened window.</p>
     */
    private boolean keyboardIsForTheWorld() {
        Component owner = frame.getFocusOwner();
        return owner == null || owner == frame || owner == canvas
                || owner == sidebar;
    }

    /**
     * Raise the window and hand it the keyboard. A viewer the desktop already
     * opened is not a reason to open a second one, and a window that cannot see
     * its own key events is a window nobody can walk.
     */
    public void focus() {
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    /**
     * Register a hook that runs when this window goes away, whoever closed it.
     * The panel needs it because its Enter World button must come back when the
     * visitor shuts the window from the title bar, which is the usual way.
     *
     * <p>Hooks accumulate rather than replace: the window is one object but it
     * has more than one owner -- the browser that opened it, and whatever that
     * opening started beside it (a live session, a transcript) -- and a second
     * registration used to switch the first one off, which left a visitor who
     * closed the window holding sockets nobody would ever close.</p>
     */
    public void setOnClose(Runnable hook) {
        if (hook != null) {
            onClose.add(hook);
        }
    }

    private final java.util.List<Runnable> onClose =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Tear the window down: detach the scene, then dispose the frame. Safe to
     * call twice, and safe from any thread (the AWT calls hop to the EDT).
     */
    @Override
    public void close() {
        final SimpleUniverse dead = universe;
        universe = null;
        capture = null;
        java.util.List<Runnable> hooks = new java.util.ArrayList<>(onClose);
        onClose.clear();
        java.awt.EventQueue.invokeLater(() -> {
            if (dead != null) {
                try {
                    dead.cleanup();
                } catch (RuntimeException | Error ignored) {
                    // A universe that will not clean up is a leaked context, not a
                    // reason to leave a window on the screen.
                }
            }
            frame.dispose();
            for (Runnable hook : hooks) {
                try {
                    hook.run();
                } catch (RuntimeException | Error listenerThrew) {
                    // The window is gone; a host that failed to notice is its own
                    // bug, and one listener throwing must not silence the rest.
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /**
     * Frame the world from outside its centre, looking at it.
     *
     * <p>The subject is fitted as the box it is rather than the sphere around it.
     * Fitting the sphere -- which is what this did -- is correct for a building
     * and wrong for everything laid out: the clinic's 48x36 m floor puts its
     * corners 17 m from the middle, so the eye backed off to 46 m to hold a
     * sphere that is 3 m tall, and the picture was a strip of carpet under a
     * field of sky. World X is the frame's horizontal; the subject's depth and
     * height trade off against its vertical through the tilt.</p>
     *
     * <p>The distance is then refined by projecting the box's eight corners
     * through the camera. Fitting the half-extents against the field of view at
     * the box's own centre distance -- the first attempt here -- still cropped
     * the clinic's outer rooms, because the near edge of a wide subject is
     * nearer than its centre and so projects wider than the centre does.</p>
     */
    public void frameHome() {
        atWalkPose = false; // the whole world is the subject; the box is not a cage
        Point3d center = scene.center();
        Vector3d half = scene.halfExtent();
        double spread = Math.max(half.x, half.z);
        // Flatness is what chooses the angle: a subject no taller than it is
        // wide is a floor plan and wants looking down at; a subject as tall as
        // it is wide is a building and wants looking at.
        double flatness = (spread <= 1e-9) ? 0.0
                : Math.min(1.0, half.y / spread);
        double tilt = FLAT_TILT_RAD - (FLAT_TILT_RAD - TALL_TILT_RAD) * flatness;
        double tanVertical = Math.tan(FIELD_OF_VIEW_RAD / 2.0);
        double tanHorizontal = tanVertical * canvasAspect();
        // A first guess from the extents, then corner by corner until the whole
        // box is inside the frustum. half.y alone is the floor under it, so a
        // degenerate box still puts the eye outside itself.
        double distance = Math.max(Math.max(
                half.x / tanHorizontal,
                (half.z * Math.sin(tilt) + half.y * Math.cos(tilt)) / tanVertical),
                half.y + 1.0) * HOME_MARGIN;
        for (int pass = 0; pass < CORNER_FIT_PASSES; pass++) {
            double needed = fitScale(half, tilt, distance,
                    tanHorizontal, tanVertical);
            if (needed <= 1.0) {
                break;
            }
            distance *= needed;
        }
        eye = new Vector3d(center.x,
                center.y + distance * Math.sin(tilt),
                center.z + distance * Math.cos(tilt));
        // Look back at the centre: yaw about Y, then a slight downward pitch.
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        yaw = Math.atan2(-dx, -dz);
        pitch = Math.atan2(dy, Math.sqrt(dx * dx + dz * dz));
        apply();
    }

    /**
     * How much further back the eye has to move for the subject's eight corners
     * to fall inside the frustum: 1.0 when they already do, otherwise the factor
     * by which the widest corner overflows. Measured in the camera's own frame
     * (right, up, forward) because perspective divides by depth, and the depth
     * of a corner is the whole reason the extents alone under-estimate it.
     */
    private static double fitScale(Vector3d half, double tilt,
            double distance, double tanHorizontal, double tanVertical) {
        // Forward = (0, -sin t, -cos t), right = (1, 0, 0), up = (0, cos t, -sin t).
        double sin = Math.sin(tilt);
        double cos = Math.cos(tilt);
        double worst = 1.0;
        for (int corner = 0; corner < 8; corner++) {
            double px = ((corner & 1) == 0) ? -half.x : half.x;
            double py = ((corner & 2) == 0) ? -half.y : half.y;
            double pz = ((corner & 4) == 0) ? -half.z : half.z;
            // Vector from the eye to the corner, in world axes centred on the target.
            double vx = px;
            double vy = py - distance * sin;
            double vz = pz - distance * cos;
            double depth = vx * 0 + vy * -sin + vz * -cos;
            if (depth <= 1e-6) {
                continue; // behind the eye: this corner cannot be framed, ignore it
            }
            double screenX = Math.abs(vx);
            double screenY = Math.abs(vy * cos + vz * -sin);
            worst = Math.max(worst, screenX / (depth * tanHorizontal));
            worst = Math.max(worst, screenY / (depth * tanVertical));
        }
        return worst;
    }

    /**
     * Enter the world: stand at a person's eye height on its near edge and look
     * across it, level.
     *
     * <p>Call before {@link #show()}. The pose cannot be written any earlier than
     * the view platform exists, and must be written before the window appears,
     * so this records the intent and {@code show()} applies it -- which is also
     * why it does not throw when the scene is empty: an empty world is the
     * caller's problem to report, not a reason to fail a pose.</p>
     */
    public void walkInside() {
        walkMode = true;
        frameWalk();
    }

    /**
     * The walking pose itself: floor plus eye height, a few metres in front of the
     * world's residents, looking along -Z at them with no pitch.
     *
     * <p>The crowd rather than the property line, because entering a world means
     * being in a place with someone in it: the first pose stood 1.5 m outside the
     * near edge of the subject's bounding box, which for a clinic spread over a
     * 20 m ground plane is a courtyard, and the visitor's first frame was sky and
     * one wall. Standing in front of the group is the 2009 screenshot's own point of
     * view.</p>
     *
     * <p>Yaw 0 is -Z by the {@link #direction} convention. A world with nobody
     * standing in it falls back to the old approach from outside, because there is
     * no one to arrive to.</p>
     */
    private void frameWalk() {
        Point3d center = scene.center();
        Vector3d half = scene.halfExtent();
        Point3d crowd = scene.entryPoint();
        if (crowd != null) {
            eye = new Vector3d(crowd.x, crowd.y + EYE_HEIGHT_M, crowd.z);
        } else {
            eye = new Vector3d(center.x,
                    center.y - half.y + EYE_HEIGHT_M,
                    center.z + half.z + WALK_APPROACH_M);
        }
        atWalkPose = true;
        yaw = 0.0;
        pitch = 0.0;
        apply();
    }

    /**
     * The canvas's width over its height, or 16:9 before the peer has a size.
     * The framing needs it because a square vignette and a wide window fit the
     * same world at different distances.
     */
    private double canvasAspect() {
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        return (width > 0 && height > 0) ? (double) width / height : 16.0 / 9.0;
    }

    /** One navigation step for a key code; unknown keys do nothing. */
    private void handleKey(int code) {
        Vector3d forward = direction(0, 0, -1);
        Vector3d right = direction(1, 0, 0);
        switch (code) {
            case KeyEvent.VK_UP, KeyEvent.VK_W -> move(forward, STEP_M);
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> move(forward, -STEP_M);
            case KeyEvent.VK_A -> move(right, -STEP_M);
            case KeyEvent.VK_D -> move(right, STEP_M);
            case KeyEvent.VK_PAGE_UP -> eye.y += STEP_M;
            case KeyEvent.VK_PAGE_DOWN -> eye.y -= STEP_M;
            case KeyEvent.VK_LEFT, KeyEvent.VK_Q -> yaw += TURN_RAD;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_E -> yaw -= TURN_RAD;
            case KeyEvent.VK_HOME -> {
                if (walkMode) {
                    frameWalk();
                } else {
                    frameHome();
                }
                return;
            }
            default -> {
                return;
            }
        }
        apply();
    }

    /** The world direction of a local vector under the current yaw/pitch. */
    private Vector3d direction(double x, double y, double z) {
        Transform3D rotation = rotated();
        Vector3d out = new Vector3d(x, y, z);
        rotation.transform(out);
        return out;
    }

    /**
     * The look rotation {@code Ry(yaw)·Rx(pitch)}. Built through a temporary on
     * purpose: {@code Transform3D.mul(a, b)} reads both operands while it writes
     * the receiver, so asking it to multiply itself is a corrupt matrix, and a
     * corrupt view matrix is a world that renders from the wrong angle.
     */
    private Transform3D rotated() {
        Transform3D turn = new Transform3D();
        turn.rotY(yaw);
        Transform3D out = new Transform3D();
        out.mul(turn, pitchOf(pitch));
        return out;
    }

    private static Transform3D pitchOf(double pitch) {
        Transform3D t = new Transform3D();
        t.rotX(pitch);
        return t;
    }

    private void move(Vector3d unit, double amount) {
        eye.x += unit.x * amount;
        eye.y += unit.y * amount;
        eye.z += unit.z * amount;
    }

    /**
     * Keep a walking eye inside the world it was given.
     *
     * <p>There is no collision here -- walls are not honoured, and neither are the
     * people standing in them. What is honoured is the world's own volume, inflated
     * by a metre. That is not a cosmetic guard: the first walk-through of the clinic
     * stepped forward twice and came out over open ground with nothing in the frame
     * but sky, which reads to the visitor as a broken renderer rather than as a
     * world they have walked off the edge of. Being unable to leave is the
     * difference between a place and a void.</p>
     *
     * <p>The vertical clamp lets the eye sink to just above the floor and rise a
     * storey above the roof, so PageUp still works as a look-around control while
     * "fly straight up and out of the world" does not.</p>
     */
    private void keepInside() {
        if (!atWalkPose) {
            return;
        }
        Point3d center = scene.center();
        Vector3d half = scene.halfExtent();
        double margin = WALK_BOUND_MARGIN_M;
        eye.x = Math.max(center.x - half.x - margin,
                Math.min(center.x + half.x + margin, eye.x));
        eye.z = Math.max(center.z - half.z - margin,
                Math.min(center.z + half.z + margin, eye.z));
        double floor = center.y - half.y;
        eye.y = Math.max(floor + WALK_MIN_EYE_M,
                Math.min(floor + half.y * 2.0 + WALK_CEILING_M, eye.y));
    }

    /** Write the eye position and orientation into the view platform. */
    private void apply() {
        TransformGroup tg = viewTransform;
        if (tg == null) {
            return;
        }
        keepInside();
        Transform3D view = rotated();
        view.setTranslation(eye);
        tg.setTransform(view);
        // The name tags face whoever reads them, and this is the only place the
        // eye is ever written -- so this is the only place they need to be aimed.
        scene.aimTags(new Point3d(eye.x, eye.y, eye.z));
    }

    // ------------------------------------------------------------------
    // Raster read-back
    // ------------------------------------------------------------------

    /**
     * Posts itself, reads the canvas, hands the pixels back. The passive
     * {@code WakeupOnBehaviorPost} is the desktop's own capture pattern: nothing
     * runs while nobody asks for a frame.
     */
    private static final class CaptureBehavior extends Behavior {

        private final Canvas3D canvas;
        private final GraphicsContext3D context;
        private ImageComponent2D image;
        private Raster raster;
        private volatile BufferedImage grabbed;
        private volatile CountDownLatch latch;

        CaptureBehavior(Canvas3D canvas) {
            this.canvas = canvas;
            this.context = canvas.getGraphicsContext3D();
        }

        @Override
        public void initialize() {
            setSchedulingBounds(
                    new BoundingSphere(new Point3d(), Double.POSITIVE_INFINITY));
            wakeupOn(new WakeupOnBehaviorPost(this, 0));
        }

        /** Ask for one frame and wait for it; returns null when nothing answers. */
        BufferedImage grab(long timeoutMillis) {
            CountDownLatch wait = new CountDownLatch(1);
            latch = wait;
            grabbed = null;
            postId(0);
            try {
                if (!wait.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    return null;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                latch = null;
            }
            return grabbed;
        }

        @Override
        public void processStimulus(Iterator<WakeupCriterion> criteria) {
            int w = canvas.getWidth();
            int h = canvas.getHeight();
            try {
                if (w > 0 && h > 0) {
                    if (image == null || image.getWidth() != w || image.getHeight() != h) {
                        image = new ImageComponent2D(ImageComponent2D.FORMAT_RGB, w, h);
                        raster = new Raster(new Point3f(0f, 0f, 0f), Raster.RASTER_COLOR,
                                0, 0, w, h, image, null);
                    }
                    context.readRaster(raster);
                    grabbed = toBuffered(image.getRenderedImage());
                }
            } catch (RuntimeException | Error | IOException failed) {
                grabbed = null; // a snapshot is a courtesy; the window still shows
            } finally {
                CountDownLatch waiter = latch;
                if (waiter != null) {
                    waiter.countDown();
                }
                wakeupOn(new WakeupOnBehaviorPost(this, 0));
            }
        }
    }

    /**
     * A java3d raster's image is not a {@code BufferedImage} and cannot be blitted
     * with drawImage, so it round-trips through PNG in memory.
     */
    static BufferedImage toBuffered(RenderedImage rendered)
            throws IOException {
        if (rendered instanceof BufferedImage image) {
            return image;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(rendered, "png", bytes)) {
            throw new IOException("no PNG writer for " + rendered.getClass().getName());
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        if (image == null) {
            throw new IOException("the PNG round trip read back empty");
        }
        return image;
    }
}
