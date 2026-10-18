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
 * <p>Since the wiring phase this window is also where the roadmap's kernels
 * meet the frame: every write of the eye ({@link #apply()}) now culls the scene
 * through the world's own {@link WorldScene#cullTo} (P0.2), resolves walking
 * through {@link WorldScene#blockStep} so a wall stops a step and slides along
 * it (P1.1), glides the eye toward its target through
 * {@code client.rig.FollowRig}'s frame-rate-independent smoothing (P3.1), and
 * points {@code client.audio.AudioManager}'s ear at the same pose (P4.1). All
 * four consult {@code WorldFeatures} once per use, so
 * {@code -Dlg.wonderland.features=off} returns this window to exactly the
 * keyboard-and-void walk it was, and {@link #close()} stops the glide and the
 * mixer with it.</p>
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
import javax.swing.Timer;
import org.jdesktop.wonderland.client.audio.AudioManager;
import org.jdesktop.wonderland.client.audio.JavaSoundBackend;
import org.jdesktop.wonderland.client.audio.SpatialAudio;
import org.jdesktop.wonderland.client.rig.FollowRig;
import org.jdesktop.wonderland.client.trigger.TriggerZone;
import org.jdesktop.wonderland.common.features.WorldFeatures;
import org.jdesktop.wonderland.renderer.post.GpuBloomPass;
import org.jdesktop.wonderland.renderer.post.GpuDofPass;
import org.jdesktop.wonderland.renderer.post.GpuFxaaPass;
import org.jdesktop.wonderland.renderer.post.GpuHdrPass;
import org.jdesktop.wonderland.renderer.post.GpuPostEffect;
import org.jdesktop.wonderland.renderer.post.GpuSsaoPass;
import org.jdesktop.wonderland.renderer.post.PostProcessPlan;
import org.jdesktop.wonderland.renderer.post.SsaoKernel;
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
     * The front clip distance in metres {@link #show()} sets on the view, spelled once so
     * the SSAO reconstruction (which needs the same near plane to linearise depth) cannot
     * drift from the camera that rendered the frame.
     */
    private static final double FRONT_CLIP_M = 0.05;

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

    /**
     * The walker's blocking height in metres, from their feet up: the same
     * door-frame tall figure {@code WalkerCollision.WALKER_HEIGHT_M} documents.
     * Spelled here so the collision band the viewer asks for is a number this
     * window owns rather than a cross-module constant it must re-read.
     */
    private static final double WALKER_HEIGHT_M = 1.7;

    /**
     * The camera rig's lag in seconds (P3.1). Deliberately short: this is a
     * first-person window, and a rig that lags half a second feels like moving
     * through syrup, while at 0.18 s a step still arrives as a glide rather
     * than a teleport. The rig's answer is frame-rate independent either way --
     * that is the property the headless suite proves, not this constant.
     */
    private static final float RIG_LAG_SEC = 0.18f;

    /** A gap this small is "arrived": the glide timer stops instead of jittering. */
    private static final double RIG_SETTLED_M = 0.01;

    /** How often the glide ticks when the camera feature is live: about 60 fps. */
    private static final int GLIDE_PERIOD_MS = 16;

    private final String title;
    private final WorldScene scene;
    private final Frame frame;
    private final Canvas3D canvas;

    /**
     * Which bloom route this window's canvas took (P3.2), decided once at
     * construction from {@link WorldFeatures#BLOOM} and whether the patched
     * preSwap() hook is present. GPU_POSTPROCESS contributes a {@link GpuBloomPass}
     * link to the {@link PostProcessCanvas} chain; any other path contributes
     * nothing. Kept so the HUD, the console and a probe can read what was decided
     * and why, not guess from the canvas's class.
     */
    private final PostProcessPlan.Decision bloomDecision;

    /**
     * Which HDR tone-mapping route this window's canvas took (P3.3), decided once at
     * construction from {@link WorldFeatures#HDR} and the same preSwap() probe. A
     * GPU_POSTPROCESS decision contributes a {@link GpuHdrPass} link; because tone
     * mapping has no honest software route (see {@link PostProcessPlan#decideHdr})
     * the only other outcome is NONE, never a half-applied curve.
     */
    private final PostProcessPlan.Decision hdrDecision;

    /**
     * Which FXAA route this window's canvas took (P3.4), decided once at construction
     * from {@link WorldFeatures#FXAA} and the same preSwap() probe. A GPU_POSTPROCESS
     * decision contributes a {@link GpuFxaaPass} link, run LAST so it anti-aliases the
     * tone-mapped image the eye is actually shown; the only other outcome is NONE.
     */
    private final PostProcessPlan.Decision fxaaDecision;

    /**
     * Which depth-of-field route this window's canvas took (P3.5), decided once at
     * construction from {@link WorldFeatures#DOF} and the same preSwap() probe. A
     * GPU_POSTPROCESS decision contributes a {@link GpuDofPass} link, run after the tone
     * map and before FXAA (see the chain-order note in the constructor); because a
     * defocus has no honest software route (see {@link PostProcessPlan#decideDof}) the
     * only other outcome is NONE, never a half-applied blur.
     */
    private final PostProcessPlan.Decision dofDecision;

    /**
     * Which ambient-occlusion route this window's canvas took (P2.7), decided once at
     * construction from {@link WorldFeatures#SSAO} and the same preSwap() probe. A
     * GPU_POSTPROCESS decision contributes a {@link GpuSsaoPass} link, run FIRST (see the
     * chain-order note in the constructor) so the scene is occluded before the cosmetic
     * lens effects; the only other outcome is NONE.
     */
    private final PostProcessPlan.Decision ssaoDecision;

    private SimpleUniverse universe;
    private TransformGroup viewTransform;
    private CaptureBehavior capture;

    /** The eye's position in world space; a vector because Transform3D wants one. */
    private Vector3d eye = new Vector3d();
    private double yaw;
    private double pitch;

    /**
     * Where the walker asked to be (P3.1). The keyboard and the framer write
     * this; the eye follows it through {@link #rig} instead of teleporting, and
     * the two coincide the moment the rig is off or the gap has closed.
     */
    private Vector3d target = new Vector3d();

    /** The follow rig, or null when the camera feature is off for this window. */
    private FollowRig rig;

    /** Drives the glide between key presses; null until the first step asks. */
    private Timer glide;

    /**
     * Drives the ponds (roadmap P2.3): one write of the texture-coordinate
     * planes per tick, from a clock that runs whether or not anybody is walking.
     * Null when the world named no water or the feature is off, which is the
     * same window the port drew before this existed.
     */
    private Timer water;

    /** When the water clock started, so its elapsed time is the scroll's input. */
    private long waterStartNanos;

    /** The audio ear's mixer, or null when the audio feature is off. */
    private JavaSoundBackend mixer;

    /** The world's sounds and one listener; the listener is this window's eye. */
    private AudioManager audio;

    /**
     * The walk's trigger dispatcher (P1.2): a field seeded on the world's own
     * room volumes, fed every eye write, whose crossed edges go to a host
     * listener. Null until a walk window with zones and a live trigger feature
     * is shown -- a framing window or a feature-off walk never builds one.
     */
    private volatile TriggerWatcher triggers;

    /**
     * Who to tell when the walker crosses a zone -- the seam to the P5 behaviour
     * layer, set by the host that owns the world's script (the renderer cannot
     * import Mandela). Null until a host attaches one, so a bare walk dispatches
     * nothing it has no consumer for.
     */
    private volatile TriggerWatcher.Listener triggerListener;

    /** How many gates the last frame's cull shut, for the HUD and the probe. */
    private volatile int droppedLastFrame;

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
        // P3.2/P3.3: build the live post-process chain when the platform and the
        // evidence allow each effect. Each decision is made once, here, from its own
        // feature switch and whether the patched java3d-core's preSwap() hook is
        // actually present at runtime -- so a stock jar degrades to a plain canvas
        // instead of a crash, and a host that wants no glow / no tone map turns one
        // off with lg.wonderland.bloom=off or lg.wonderland.hdr=off (or the master
        // switch). See PostProcessPlan for each whole rule and the probe evidence.
        this.bloomDecision = PostProcessPlan.decide(bloomCapabilities());
        this.hdrDecision = PostProcessPlan.decideHdr(hdrCapabilities());
        this.fxaaDecision = PostProcessPlan.decideFxaa(fxaaCapabilities());
        this.dofDecision = PostProcessPlan.decideDof(dofCapabilities());
        this.ssaoDecision = PostProcessPlan.decideSsao(ssaoCapabilities());
        // The chain order is deliberate: occlude FIRST, then bloom, tone map, defocus, and
        // anti-alias LAST. SSAO is a scene-lighting correction (it darkens the ambient in a
        // crevice), so it runs on the raw scene before the cosmetic lens effects and the
        // bloom then glows an already-occluded image rather than a crevice the eye should
        // not see lit. The glow is added while the frame is still in scene-ish range, the
        // filmic curve compresses the combined highlights so a hot bloom rolls off toward
        // white instead of clipping, the defocus then gathers that tone-mapped image into
        // bokeh, and FXAA finally softens the edges of the very image the eye is shown. The
        // scene depth both the occlusion and the defocus blit survives every link (each
        // writes only colour), so each reads the true per-pixel depth wherever it sits.
        java.util.List<GpuPostEffect> chain = new java.util.ArrayList<>();
        if (ssaoDecision.path() == PostProcessPlan.Path.GPU_POSTPROCESS) {
            chain.add(new GpuSsaoPass(ssaoSettings(width, height)));
        }
        if (bloomDecision.path() == PostProcessPlan.Path.GPU_POSTPROCESS) {
            chain.add(new GpuBloomPass());
        }
        if (hdrDecision.path() == PostProcessPlan.Path.GPU_POSTPROCESS) {
            chain.add(new GpuHdrPass());
        }
        if (dofDecision.path() == PostProcessPlan.Path.GPU_POSTPROCESS) {
            chain.add(new GpuDofPass());
        }
        if (fxaaDecision.path() == PostProcessPlan.Path.GPU_POSTPROCESS) {
            chain.add(new GpuFxaaPass());
        }
        if (chain.isEmpty()) {
            this.canvas = new Canvas3D(config);
        } else {
            this.canvas = new PostProcessCanvas(config,
                    chain.toArray(new GpuPostEffect[0]));
        }
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
        view.setFrontClipDistance(FRONT_CLIP_M);
        viewTransform = platform.getViewPlatformTransform();
        BranchGroup root = scene.root();
        capture = new CaptureBehavior(canvas);
        root.addChild(capture);
        universe.addBranchGraph(root);
        // Install the camera rig and the audio ear only once the graph is live:
        // the gates the cull writes belong to a compiled scene, and a rig built
        // here cannot smear a pose the framing has not chosen yet.
        if (WorldFeatures.enabled(WorldFeatures.CAMERA)) {
            // A locked seat relative to the subject: in this first-person window
            // the subject IS the target pose, so the rig's job is purely the
            // arrival curve, with the walk's own step distance well inside the
            // teleport radius (a framing jump from 2.6 km out must SNAP, exactly
            // as FollowRig documents).
            rig = new FollowRig(0f, 0f, 0f, RIG_LAG_SEC,
                    (float) Math.max(scene.reach() * 2.0, 100.0),
                    (float) EYE_HEIGHT_M);
        }
        installAudio();
        installWater();
        if (walkMode) {
            frameWalk();
        } else {
            frameHome();
        }
        installTriggers();
    }

    /**
     * Seed the walk's trigger dispatcher (P1.2) once the first pose is chosen, so
     * the room the walker starts in is primed as "inside" rather than firing a
     * spurious enter on the first frame. Inert for a framing window (only a walk
     * triggers), for a feature-off world ({@link WorldScene#triggerZones()} is
     * empty) and for a world with no volumes -- every one of which leaves the walk
     * exactly the pre-P1.2 walk.</p>
     */
    private void installTriggers() {
        if (!walkMode || !WorldFeatures.enabled(WorldFeatures.TRIGGER)) {
            return;
        }
        java.util.List<TriggerZone> zones = scene.triggerZones();
        if (!zones.isEmpty()) {
            triggers = new TriggerWatcher(
                    (float) eye.x, (float) eye.y, (float) eye.z, zones);
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
     * Which bloom route this window decided, and why (P3.2). A caller -- the HUD,
     * the console, a probe -- reads this to know whether the live GPU bloom is on
     * without inspecting the canvas's class or re-deriving the plan.
     */
    public PostProcessPlan.Decision getBloomDecision() {
        return bloomDecision;
    }

    /**
     * Which HDR tone-mapping route this window decided, and why (P3.3). A caller --
     * the HUD, the console, a probe -- reads this to know whether the live GPU tone map
     * is on without inspecting the canvas's chain or re-deriving the plan.
     */
    public PostProcessPlan.Decision getHdrDecision() {
        return hdrDecision;
    }

    /**
     * Which FXAA route this window decided, and why (P3.4). Read by the HUD, the
     * console or a probe to know whether the live GPU anti-alias is on.
     */
    public PostProcessPlan.Decision getFxaaDecision() {
        return fxaaDecision;
    }

    /**
     * Which depth-of-field route this window decided, and why (P3.5). Read by the HUD,
     * the console or a probe to know whether the live GPU defocus is on.
     */
    public PostProcessPlan.Decision getDofDecision() {
        return dofDecision;
    }

    /**
     * Which ambient-occlusion route this window decided, and why (P2.7). Read by the HUD,
     * the console or a probe to know whether the live GPU occlusion is on.
     */
    public PostProcessPlan.Decision getSsaoDecision() {
        return ssaoDecision;
    }

    /**
     * The capability set the bloom decision is made from: bloom is wanted when the
     * roadmap feature is on (on by default, like every {@link WorldFeatures}
     * switch; {@code lg.wonderland.bloom=off} or the master switch turns it off),
     * and the GPU route is probe-usable when the patched {@code Canvas3D.preSwap()}
     * hook is actually present -- the exact artefact WlProbe53/WlProbe55 measured.
     * Against a stock java3d-core the hook is absent, the GPU capability is not in
     * evidence, and the plan falls through to the software route: honest.
     */
    private static java.util.Set<PostProcessPlan.Capability> bloomCapabilities() {
        java.util.Set<PostProcessPlan.Capability> caps =
                java.util.EnumSet.noneOf(PostProcessPlan.Capability.class);
        if (WorldFeatures.enabled(WorldFeatures.BLOOM)) {
            caps.add(PostProcessPlan.Capability.BLOOM_WANTED);
        }
        if (preSwapHookPresent()) {
            caps.add(PostProcessPlan.Capability.GPU_POSTPROCESS_PROBED_USABLE);
        }
        return caps;
    }

    /**
     * The capability set the HDR decision is made from: tone mapping is wanted when
     * {@link WorldFeatures#HDR} is on (on by default, like every switch;
     * {@code lg.wonderland.hdr=off} or the master switch turns it off), and the GPU
     * route is probe-usable when the patched {@code Canvas3D.preSwap()} hook is present
     * -- the SAME artefact the bloom rides, since the tone map is one more link on one
     * chain, not a second hook. Against a stock java3d-core the capability is not in
     * evidence and {@link PostProcessPlan#decideHdr} falls through to NONE.
     */
    private static java.util.Set<PostProcessPlan.Capability> hdrCapabilities() {
        java.util.Set<PostProcessPlan.Capability> caps =
                java.util.EnumSet.noneOf(PostProcessPlan.Capability.class);
        if (WorldFeatures.enabled(WorldFeatures.HDR)) {
            caps.add(PostProcessPlan.Capability.HDR_WANTED);
        }
        if (preSwapHookPresent()) {
            caps.add(PostProcessPlan.Capability.GPU_POSTPROCESS_PROBED_USABLE);
        }
        return caps;
    }

    /**
     * The capability set the FXAA decision is made from, the same shape as
     * {@link #hdrCapabilities()}: wanted when {@link WorldFeatures#FXAA} is on (on by
     * default; {@code lg.wonderland.fxaa=off} or the master switch turns it off), and
     * GPU-usable when the patched {@code Canvas3D.preSwap()} hook is present.
     */
    private static java.util.Set<PostProcessPlan.Capability> fxaaCapabilities() {
        java.util.Set<PostProcessPlan.Capability> caps =
                java.util.EnumSet.noneOf(PostProcessPlan.Capability.class);
        if (WorldFeatures.enabled(WorldFeatures.FXAA)) {
            caps.add(PostProcessPlan.Capability.FXAA_WANTED);
        }
        if (preSwapHookPresent()) {
            caps.add(PostProcessPlan.Capability.GPU_POSTPROCESS_PROBED_USABLE);
        }
        return caps;
    }

    /**
     * The capability set the DOF decision is made from, the same shape as
     * {@link #fxaaCapabilities()}: wanted when {@link WorldFeatures#DOF} is on (on by
     * default; {@code lg.wonderland.dof=off} or the master switch turns it off), and
     * GPU-usable when the patched {@code Canvas3D.preSwap()} hook is present. The defocus
     * rides the SAME hook the bloom, tone map and anti-alias do -- one more link on one
     * chain -- and its extra depth blit reads the depth Java 3D already rendered, so no
     * second capability gates it.
     */
    private static java.util.Set<PostProcessPlan.Capability> dofCapabilities() {
        java.util.Set<PostProcessPlan.Capability> caps =
                java.util.EnumSet.noneOf(PostProcessPlan.Capability.class);
        if (WorldFeatures.enabled(WorldFeatures.DOF)) {
            caps.add(PostProcessPlan.Capability.DOF_WANTED);
        }
        if (preSwapHookPresent()) {
            caps.add(PostProcessPlan.Capability.GPU_POSTPROCESS_PROBED_USABLE);
        }
        return caps;
    }

    /**
     * The capability set the SSAO decision is made from, the same shape as
     * {@link #dofCapabilities()}: wanted when {@link WorldFeatures#SSAO} is on (on by
     * default; {@code lg.wonderland.ssao=off} or the master switch turns it off), and
     * GPU-usable when the patched {@code Canvas3D.preSwap()} hook is present.
     */
    private static java.util.Set<PostProcessPlan.Capability> ssaoCapabilities() {
        java.util.Set<PostProcessPlan.Capability> caps =
                java.util.EnumSet.noneOf(PostProcessPlan.Capability.class);
        if (WorldFeatures.enabled(WorldFeatures.SSAO)) {
            caps.add(PostProcessPlan.Capability.SSAO_WANTED);
        }
        if (preSwapHookPresent()) {
            caps.add(PostProcessPlan.Capability.GPU_POSTPROCESS_PROBED_USABLE);
        }
        return caps;
    }

    /**
     * The occlusion parameters matched to THIS window's camera, so the pass's view
     * reconstruction agrees with the frame it darkens: the same front clip and the same
     * back clip {@link #show()} sets on the view, the same vertical field of view, and the
     * canvas's own aspect. The radius, intensity and bias are the look controls (a
     * half-metre contact reach at full strength); the near/far/fov/aspect are the geometry
     * the reconstruction must not guess. The aspect is the constructor's requested size, so
     * a later window reshape leaves it slightly stale, which shifts the AO's horizontal
     * reach by that ratio and nothing else -- a soft darkening is not sensitive to it.
     */
    private SsaoKernel.Settings ssaoSettings(int width, int height) {
        double far = Math.max(1000.0,
                scene.reach() * SKY_REACH + scene.radius() * CLIP_STANDOFF);
        float aspect = (width > 0 && height > 0) ? (float) width / height : 16f / 9f;
        return new SsaoKernel.Settings((float) FRONT_CLIP_M, (float) far,
                (float) Math.tan(FIELD_OF_VIEW_RAD / 2.0), aspect, 0.5f, 1f, 0.025f);
    }

    /**
     * Whether the running java3d-core carries the patched {@code preSwap()} hook --
     * a runtime detection of the exact artefact the probes measured, so the GPU
     * route is claimed only where the hook that makes it work is genuinely present,
     * never on the assumption that a version string implies a patch.
     */
    private static boolean preSwapHookPresent() {
        try {
            Canvas3D.class.getMethod("preSwap");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
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
     * Attach the walk's trigger listener (P1.2) -- the seam to the P5 behaviour
     * layer. The renderer reports only that the walker crossed a named zone
     * (entered or exited) and never decides what that means, because it cannot
     * import Mandela; the host that owns the world's {@code behaviour.mnd}
     * attaches a listener here and turns the edge into whatever the script says.
     * A null listener, or a walk with no trigger feature, fires nothing.</p>
     *
     * @param listener the edge consumer, or {@code null} to detach
     */
    public void setTriggerListener(TriggerWatcher.Listener listener) {
        triggerListener = listener;
    }

    /**
     * Show one line of transient status in the window's title (P1.2's visible
     * effect): the walk-in host writes it when a behaviour has something to say
     * about a zone the walker just entered, and passes {@code null} to clear it
     * back to the plain world name on exit. The title is the one surface this
     * first-person window always owns, so a trigger note is genuinely on screen
     * without a scene-graph write that a headless CI could not verify.</p>
     *
     * @param note the line to append to the title, or {@code null}/blank to reset
     */
    public void setStatus(String note) {
        String base = (title == null || title.isBlank()) ? "Wonderland" : title;
        frame.setTitle((note == null || note.isBlank()) ? base : base + " — " + note);
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
        final AudioManager deadAudio = audio;
        final JavaSoundBackend deadMixer = mixer;
        audio = null;
        mixer = null;
        // Release the trigger dispatcher and its host hook with the window: a
        // torn-down viewer must not keep firing edges into a listener that no
        // longer owns a world on screen.
        triggers = null;
        triggerListener = null;
        java.util.List<Runnable> hooks = new java.util.ArrayList<>(onClose);
        onClose.clear();
        java.awt.EventQueue.invokeLater(() -> {
            // Stop the glide before the universe goes, so no tick writes a
            // transform into a torn-down view platform.
            if (glide != null) {
                glide.stop();
            }
            // Same for the ponds: a tick that reaches a cleaned-up universe is a
            // stack trace on the EDT, and the water is gone with the world anyway.
            if (water != null) {
                water.stop();
            }
            if (dead != null) {
                try {
                    dead.cleanup();
                } catch (RuntimeException | Error ignored) {
                    // A universe that will not clean up is a leaked context, not a
                    // reason to leave a window on the screen.
                }
            }
            // Silence the world and release every clip line it held (P4.1); a
            // mixer that will not close is a leaked line, never a stuck window.
            if (deadAudio != null) {
                deadAudio.clear();
            }
            if (deadMixer != null) {
                try {
                    deadMixer.close();
                } catch (RuntimeException | Error ignored) {
                    // The window is going away regardless.
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
        seatRigAtEye();
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
            // No crowd to arrive at is not a reason to arrive outside the world.
            // The old fallback stood the visitor one approach past the subject's
            // near wall -- beyond the property line -- which is the "I appear
            // outside the world" every resident-less stock world produced. Stand
            // in the middle of the room the framing chose, on its floor, instead.
            eye = new Vector3d(center.x,
                    center.y - half.y + EYE_HEIGHT_M,
                    center.z);
        }
        atWalkPose = true;
        yaw = 0.0;
        pitch = 0.0;
        seatRigAtEye();
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
            case KeyEvent.VK_UP, KeyEvent.VK_W -> stepTowards(forward, STEP_M);
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> stepTowards(forward, -STEP_M);
            case KeyEvent.VK_A -> stepTowards(right, -STEP_M);
            case KeyEvent.VK_D -> stepTowards(right, STEP_M);
            case KeyEvent.VK_PAGE_UP -> riseAndFall(STEP_M);
            case KeyEvent.VK_PAGE_DOWN -> riseAndFall(-STEP_M);
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

    /**
     * Ask for a horizontal step and let the world answer where it lands.
     *
     * <p>The request goes through {@link WorldScene#blockStep} -- P1.1's wired
     * half -- so a step into a wall stops at its face and slides along it rather
     * than crossing it, and a world with the collision feature off (or no authored
     * volumes) hands the request straight back unchanged, which is exactly the
     * pre-P1.1 glide. The vertical part of a pitched forward step is the viewer's
     * own and is kept; the collider never touches it.</p>
     */
    private void stepTowards(Vector3d unit, double amount) {
        double rx = eye.x + unit.x * amount;
        double ry = eye.y + unit.y * amount;
        double rz = eye.z + unit.z * amount;
        float[] land = new float[2];
        // The walker's body straddles the eye: its feet are one eye-height below
        // the lens and its head just above it, so a wall blocks the person rather
        // than a point. The collider reads this band once, when it is first built.
        double floor = eye.y - EYE_HEIGHT_M;
        scene.blockStep((float) eye.x, (float) eye.z, (float) rx, (float) rz,
                (float) floor, (float) (floor + WALKER_HEIGHT_M), land);
        target = new Vector3d(land[0], ry, land[1]);
        keepInside(target);
        glideOrSnap();
    }

    /** Rise or sink: the eye's height is the look-around control, no collision. */
    private void riseAndFall(double amount) {
        target = new Vector3d(eye.x, eye.y + amount, eye.z);
        keepInside(target);
        glideOrSnap();
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

    /**
     * Keep a walking pose inside the world's own volume.
     *
     * <p>This is the outer fence, not the walls: it bounds the eye to the world's
     * box inflated by a doorstep so it can never stand over the open void past the
     * property line (the first walk-through stepped forward twice and left the
     * clinic entirely, which reads as a broken renderer rather than as walking off
     * the edge). The walls themselves are honoured one pose earlier, in
     * {@link WorldScene#blockStep}, which is why a step into a room's side is
     * refused before it ever reaches this box.</p>
     *
     * <p>The vertical clamp lets the eye sink to just above the floor and rise a
     * storey above the roof, so PageUp still works as a look-around control while
     * "fly straight up and out of the world" does not.</p>
     */
    private void keepInside(Vector3d pose) {
        if (!atWalkPose) {
            return;
        }
        Point3d center = scene.center();
        Vector3d half = scene.halfExtent();
        double margin = WALK_BOUND_MARGIN_M;
        pose.x = Math.max(center.x - half.x - margin,
                Math.min(center.x + half.x + margin, pose.x));
        pose.z = Math.max(center.z - half.z - margin,
                Math.min(center.z + half.z + margin, pose.z));
        double floor = center.y - half.y;
        pose.y = Math.max(floor + WALK_MIN_EYE_M,
                Math.min(floor + half.y * 2.0 + WALK_CEILING_M, pose.y));
    }

    /**
     * Send the current {@link #target} to the eye: a locked rig (camera feature
     * off) arrives instantly, exactly the pre-P3.1 teleport; a live rig starts a
     * glide and the eye trails the target until it settles.
     */
    private void glideOrSnap() {
        if (rig == null) {
            eye = new Vector3d(target);
            apply();
            return;
        }
        ensureGlideRunning();
    }

    /** Park the rig and both poses on the eye, so a framing view does not smear. */
    private void seatRigAtEye() {
        target = new Vector3d(eye);
        if (rig != null) {
            rig.place((float) eye.x, (float) eye.y, (float) eye.z);
        }
    }

    /** Start the glide timer if it is not already running; harmless to re-call. */
    private void ensureGlideRunning() {
        if (glide != null && glide.isRunning()) {
            return;
        }
        if (glide == null) {
            glide = new Timer(GLIDE_PERIOD_MS, event -> glideTick());
            glide.setRepeats(true);
            glide.setCoalesce(true);
        }
        lastGlideNanos = 0L;
        glide.start();
    }

    /** One glide frame: pull the eye toward the target, write it, stop when done. */
    private void glideTick() {
        long now = System.nanoTime();
        float dt = (lastGlideNanos == 0L)
                ? GLIDE_PERIOD_MS / 1000f
                : (now - lastGlideNanos) / 1_000_000_000f;
        lastGlideNanos = now;
        float[] next = rig.update((float) target.x, (float) target.y,
                (float) target.z, dt);
        eye = new Vector3d(next[0], next[1], next[2]);
        apply();
        double gap = Math.sqrt(sq(eye.x - target.x) + sq(eye.y - target.y)
                + sq(eye.z - target.z));
        if (gap <= RIG_SETTLED_M) {
            eye = new Vector3d(target); // land exactly, no residual sub-centimetre drift
            apply();
            if (glide != null) {
                glide.stop();
            }
        }
    }

    /** The clock the glide measures its steps against; 0 means "next tick is first". */
    private long lastGlideNanos;

    private static double sq(double v) {
        return v * v;
    }

    /**
     * Start the water clock (P2.3). Nothing is installed when the feature is off
     * or the world named no pond, so the ordinary world costs one skipped check
     * per frame and no timer at all -- and a world whose art does not have a
     * water record still cannot sprout one.
     */
    private void installWater() {
        if (!WorldFeatures.enabled(WorldFeatures.WATER)
                || scene.pondCount() == 0) {
            return;
        }
        waterStartNanos = System.nanoTime();
        water = new Timer(WATER_PERIOD_MS, event -> waterTick());
        water.start();
    }

    /**
     * Advance every pond to now. The scene's own write swallows a generator that
     * refuses, so a torn-down universe stops the animation rather than the EDT;
     * and the clock stops itself with the window, in {@link #close()}.
     */
    private void waterTick() {
        scene.scrollWater(
                (System.nanoTime() - waterStartNanos) / 1_000_000_000.0);
    }

    /** Whether this window is animating water; the probe's evidence line. */
    public boolean waterClockRunning() {
        Timer clock = water;
        return clock != null && clock.isRunning();
    }

    /** How many ponds the world laid, whether or not the clock is running. */
    public int pondCount() {
        return scene.pondCount();
    }

    /**
     * How many ponds the last frame's cull shut along with the ground under them
     * (roadmap P2.3's culling half), read beside {@link #pondCount()} the same way
     * {@link #poolsHiddenLastFrame()} is read beside the pools'.
     */
    public int pondsHiddenLastFrame() {
        return scene.pondsHiddenLastFrame();
    }

    /**
     * How often the ponds are rewritten, in milliseconds.
     *
     * <p>Eight writes a second is enough because the motion is not a moving
     * object: at the tile's own speed one tick advances the pattern by half a
     * percent of a tile, which is under a pixel on a pond at any distance a
     * person stands at. A faster clock costs a scene-graph write and buys
     * nothing a slower one would not have shown; a much slower one reads as a
     * slideshow of a lake rather than as one.</p>
     */
    private static final int WATER_PERIOD_MS = 120;

    /**
     * Install the world's audio ear (P4.1). Off when the feature is disabled or
     * the mixer refuses to open -- a world without sound, never a world without
     * a view, so every failure here is swallowed to silence.
     */
    private void installAudio() {
        if (!WorldFeatures.enabled(WorldFeatures.AUDIO)) {
            return;
        }
        try {
            mixer = new JavaSoundBackend();
            audio = new AudioManager(mixer);
        } catch (RuntimeException | Error refused) {
            mixer = null;
            audio = null;
        }
    }

    /** Point the ear at the eye and let the manager start, pose and stop sounds. */
    private void updateAudio() {
        AudioManager manager = audio;
        if (manager == null) {
            return;
        }
        manager.setListener(new SpatialAudio.Listener(
                (float) eye.x, (float) eye.y, (float) eye.z, (float) yaw));
        manager.update();
    }

    /**
     * Write one frame's cull answer into the scene (P0.2), from the eye this
     * frame is drawn at. Silent when the feature is off -- {@link
     * WorldScene#visibleFrom} then keeps every gate open.
     */
    private void updateCull() {
        droppedLastFrame = scene.cullTo(
                (float) eye.x, (float) eye.y, (float) eye.z);
    }

    /** How many far cells the last frame shut; the probe's evidence that cull is live. */
    public int droppedLastFrame() {
        return droppedLastFrame;
    }

    /**
     * How many shadow pools the last frame shut along with their caster (roadmap
     * P2.4's occlusion half). Read beside {@link #droppedLastFrame()}: cells that
     * dropped without their shadows dropping too is the ghost this number exists
     * to rule out -- a far building with its shadow still lying in the near ground.
     */
    public int poolsHiddenLastFrame() {
        return scene.poolsHiddenLastFrame();
    }

    /**
     * Feed this frame's eye pose to the trigger dispatcher (P1.2). A no-op when
     * no watcher was built (a framing window, a feature-off or volume-less world)
     * or no listener is attached; a crossed edge reaches the host, and a host
     * that throws is contained inside {@link TriggerWatcher} rather than here.</p>
     */
    private void updateTriggers() {
        TriggerWatcher watcher = triggers;
        if (watcher != null) {
            watcher.onEyeAt((float) eye.x, (float) eye.y, (float) eye.z,
                    triggerListener);
        }
    }

    /** Write the eye position and orientation into the view platform. */
    private void apply() {
        TransformGroup tg = viewTransform;
        if (tg == null) {
            return;
        }
        keepInside(eye);
        Transform3D view = rotated();
        view.setTranslation(eye);
        tg.setTransform(view);
        Point3d at = new Point3d(eye.x, eye.y, eye.z);
        // The name tags face whoever reads them, the cull drops what that eye
        // cannot reach and the ear turns with it -- and this is the only place
        // the eye is ever written, so this is the only place any of them update.
        scene.aimTags(at);
        updateCull();
        updateAudio();
        updateTriggers();
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
