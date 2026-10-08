/**
 * Project Looking Glass
 *
 * $RCSfile: GlassyTaskbar.java,v $
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
 *
 * $Revision: 1.30 $
 * $Date: 2007-01-29 18:18:44 $A
 * $State: Exp $
 */
package org.jdesktop.lg3d.scenemanager.utils.taskbar;

import org.jogamp.vecmath.Point3f;
import org.jogamp.vecmath.Vector3f;

import org.jdesktop.lg3d.scenemanager.utils.SceneControl;
import org.jdesktop.lg3d.scenemanager.utils.appcontainer.NaturalMotionF3DAnimationFactory;
import org.jdesktop.lg3d.scenemanager.utils.background.Background;
import org.jdesktop.lg3d.scenemanager.utils.background.LayeredImageBackground;
import org.jdesktop.lg3d.scenemanager.utils.background.ModelBackground;
import org.jdesktop.lg3d.scenemanager.utils.background.PanoImageBackground;
import org.jdesktop.lg3d.scenemanager.utils.event.BackgroundChangeRequestEvent;
import org.jdesktop.lg3d.scenemanager.utils.event.DesktopConfigChangeEvent;
import org.jdesktop.lg3d.scenemanager.utils.event.ScreenResolutionChangedEvent;
import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.Node;
import org.jdesktop.lg3d.sg.Switch;
import org.jdesktop.lg3d.sg.utils.transparency.TransparencyOrderedGroup;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.c3danimation.NaturalMotionAnimation;
import org.jdesktop.lg3d.utils.c3danimation.NaturalMotionAnimationFactory;
import org.jdesktop.lg3d.utils.component.Pseudo3DIcon;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.utils.shape.FrostedGlassPanel;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Container3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEvent;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.wg.event.MouseButtonEvent3D;
import org.jdesktop.lg3d.wg.event.MouseEnteredEvent3D;
import org.jdesktop.lg3d.wg.event.MouseEvent3D.ButtonId;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class GlassyTaskbar extends Taskbar {
    private static float barHeight = 0.025f;
    private static final float BASE_BAR_HEIGHT = 0.025f;
    /** Whole-bar tilt in degrees about X; mirrored negative when docked top. */
    private static final float BAR_TILT_DEG = 5.0f;
    /** Glass-shelf lay-flat rotation in degrees about X; mirrored when top. */
    private static final float SHELF_ROT_DEG = -90.0f;
    /** Glass-shelf Y offset as a fraction of barHeight; mirrored when top. */
    private static final float SHELF_Y_OFFSET = -0.52f;
    /** Glass-shelf Z offset as a fraction of barHeight (edge-independent). */
    private static final float SHELF_Z_OFFSET = -0.3f;
    private static float barDepth = 0.0025f;
    private static float barZ = -0.04f;
    private static float thumbnailZ = -0.01f;
    private static float iconSpacing = 0.0025f;
    /** Right-edge inset for the right-aligned (themes) icon group, in bar
     *  heights. {@code HorizontalLayout.RIGHT} packs the group flush to the
     *  container's right edge; without an inset the rightmost icon (Exit) lands
     *  past the tapered tip of the tilted glass shelf and reads as hanging off
     *  the end of the bar. */
    private static final float RIGHT_GROUP_INSET_BAR_HEIGHTS = 0.25f;
    /** Frosted-edge band width as a fraction of the quad height. The bar keeps
     *  square corners (radius 0) and the panel's default band is derived from
     *  the radius, so the frost would vanish without an explicit band. */
    private static final float BAR_FROST_BAND_RATIO = 0.25f;
    private static Appearance barApp
	= new SimpleAppearance(
	    0.6f, 1.0f, 0.6f, 1.0f,
	    SimpleAppearance.DISABLE_CULLING);
    
    private GlassyPanel bottomBar;
    /** Frosted slab top face; built whenever the shader program assembles and
     *  selected live by {@link #barSwitch}. Resizes in place via
     *  {@code setSize}, so {@code applyConfig} never rebuilds it (removing a
     *  live Shape3D child is forbidden). */
    private FrostedGlassPanel bottomBarFrosted;
    /** Frosted front-edge strip that gives the slab the classic shelf's
     *  visible thickness; child of {@link #bottomBarEdgeComp}, {@code null}
     *  when the shader program cannot assemble. */
    private FrostedGlassPanel bottomBarFrostedEdge;
    private Component3D bottomBarEdgeComp;
    /** Live shelf-style selector: child 0 = {@link #bottomBar} (classic box),
     *  child 1 = the frosted slab; flipped in place on Apply. */
    private Switch barSwitch;
    private Component3D bottomBarComp;
    private Container3D appThumbnails;
    private Container3D shortcuts;
    private Container3D themes;

    /** Requested taskbar index of each right-side (themes) item, so a negative
     *  index -n reliably means "n-th from the right" regardless of the order in
     *  which plugins post their items. */
    private final Map<Component3D, Integer> themesIndex = new HashMap<Component3D, Integer>();
    
    public GlassyTaskbar() {
    }
    
    public void initialize(SceneControl sceneControl) {
        super.initialize();
        setName("GlassyTaskBar");
        setMouseEventSource(MouseButtonEvent3D.class, true);

        // Honour the persisted desktop configuration (thickness, icon size,
        // docking edge) so this legacy bar stays consistent with the active one.
        DesktopConfig cfg = DesktopConfig.get();
        barHeight = BASE_BAR_HEIGHT * cfg.getBarScale();
        Pseudo3DIcon.setIconScale(cfg.getIconScale());
        
        Toolkit3D toolkit3d = Toolkit3D.getToolkit3D();
        // If the Canvas is not visible yet screensize might be 0,0, which causes NonAffine transforms
        final float width = (toolkit3d.getScreenWidth()!=0f) ? toolkit3d.getScreenWidth() : 100f;
        final float height = (toolkit3d.getScreenHeight()!=0f) ? toolkit3d.getScreenHeight() : 100f;
                
	setPreferredSize(new Vector3f(width, barHeight, barHeight));
	// Both shelf styles are built up front and parked in a Switch so a live
	// Window-glass Apply flips the bar in place: child 0 = the classic glass
	// box (thickness, bevel, transparency ramp), child 1 = the GPU frosted
	// slab - a square frosted quad for the top face (the bar's pickable hover
	// surface) plus a narrow frosted quad rotated into the viewer-facing
	// front edge for the classic shelf's visible thickness, frost
	// concentrated at the edges via an explicit band, corners always square
	// (the rounded-corner preference applies to window decorations only).
	// FrostedGlassPanel.create returns null when the shader program cannot
	// assemble, then the Switch stays on the classic box.
	bottomBar = new GlassyPanel(
	    width,
	    barHeight,
	    barDepth,
	    barDepth * 0.1f,
	    barApp);
	bottomBarFrosted = FrostedGlassPanel.create(width, barHeight, 0.0f, 0.0f,
	    barHeight * BAR_FROST_BAND_RATIO);
        
	bottomBarComp = new Component3D();
	barSwitch = new Switch();
	barSwitch.setCapability(Switch.ALLOW_SWITCH_WRITE);
	barSwitch.addChild(bottomBar);
	if (bottomBarFrosted != null) {
	    // The slab top face is the bar's pickable hover surface.
	    bottomBarFrosted.setPickable(true);
	    bottomBarFrostedEdge = FrostedGlassPanel.create(
		width, barDepth, 0.0f, 0.0f, barDepth * BAR_FROST_BAND_RATIO);
	    bottomBarEdgeComp = new Component3D();
	    if (bottomBarFrostedEdge != null) {
		bottomBarEdgeComp.addChild(bottomBarFrostedEdge);
		bottomBarEdgeComp.setRotationAxis(1.0f, 0.0f, 0.0f);
		bottomBarEdgeComp.setRotationAngle((float) Math.toRadians(90));
		positionFrostedEdge();
	    }
	    // Deterministic back-to-front blending of the two transparent quads.
	    TransparencyOrderedGroup barGlass = new TransparencyOrderedGroup();
	    barGlass.addChild(bottomBarFrosted);
	    barGlass.addChild(bottomBarEdgeComp);
	    barSwitch.addChild(barGlass);
	}
	barSwitch.setWhichChild(frostedBarChild());
	bottomBarComp.addChild(barSwitch);
	bottomBarComp.setRotationAxis(1.0f, 0.0f, 0.0f);
	bottomBarComp.setRotationAngle(shelfRotRadians());
	bottomBarComp.setTranslation(0.0f, shelfYOffset(), barHeight * SHELF_Z_OFFSET);
        bottomBarComp.setName("BottomBarComp");
	Container3D deco = new Container3D();
	deco.addChild(bottomBarComp);
        deco.setName("TaskbarDeco");
        
	shortcuts = new Container3D();
        shortcuts.setPreferredSize(new Vector3f(width, barHeight, barHeight));//FIXME
	shortcuts.setLayout(
            new HorizontalReorderableLayout(
                HorizontalLayout.AlignmentType.LEFT, iconSpacing, 
                new NaturalMotionF3DAnimationFactory(150)));
        
        // Listen for Tapps and add them to the toolbar
        LgEventConnector.getLgEventConnector().addListener(
            LgEventSource.ALL_SOURCES,
            new LgEventListener() {
                public void processEvent(final LgEvent evt) {
                    TaskbarItemConfig config = (TaskbarItemConfig)evt;
                    addTaskbarItem(config.createItem(), config.getItemIndex());
                }
                public Class<LgEvent>[] getTargetEventClasses() {
                    return new Class[] {TaskbarItemConfig.class};
                }
            });
        
	themes = new Container3D();
        themes.setPreferredSize(new Vector3f(rightGroupWidth(width), barHeight, barHeight));//FIXME
	themes.setLayout(
            new HorizontalReorderableLayout(
                HorizontalLayout.AlignmentType.RIGHT, iconSpacing,
                new NaturalMotionF3DAnimationFactory(150)));
        
        appThumbnails = new Container3D();
        appThumbnails.setPreferredSize(new Vector3f(width, barHeight, barHeight));//FIXME
	appThumbnails.setLayout(
            new ThumbnailLayout(ThumbnailLayout.AlignmentType.CENTER, 0.002f,
                (float)Math.toRadians(-45), this));
        appThumbnails.setTranslation(0.0f, 0.0f, thumbnailZ);
        
        Component3D c3d = new Component3D();
        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
        tog.addChild(deco);
	tog.addChild(shortcuts);
        tog.addChild(themes);
        tog.addChild(appThumbnails);
        c3d.addChild(tog);
        addChild(c3d);
        
	setRotationAxis(1.0f, 0.0f, 0.0f);
        setRotationAngle((float)Math.toRadians(-360));
        changeRotationAngle(tiltRadians());
        boolean top = cfg.getPosition() == DesktopConfig.Position.TOP;
        setTranslation(0.0f, top ? height * 0.6f : height * -0.6f, 0.0f);
        float dockY = top ? (height * 0.5f - barHeight * 0.5f)
                          : (height * -0.5f + barHeight * 0.5f);
        changeTranslation(0.0f, dockY, barZ, 2000);
        // Publish the reserved strip for the docking edge in use so maximized
        // windows fill the usable area and never cover the bar.
        if (top) {
            setReservedTopHeight(barHeight);
            setReservedBottomHeight(0.0f);
        } else {
            setReservedBottomHeight(barHeight);
            setReservedTopHeight(0.0f);
        }
        
        // Listen for handling the window size change
        LgEventConnector.getLgEventConnector().addListener(
            LgEventSource.ALL_SOURCES,
            new LgEventListener() {
                public void processEvent(final LgEvent event) {
                    ScreenResolutionChangedEvent csce = (ScreenResolutionChangedEvent)event;
                    changeSize(csce.getWidth(), csce.getHeight());
                }
                public Class<LgEvent>[] getTargetEventClasses() {
                    return new Class[] {ScreenResolutionChangedEvent.class};
                }
            });
            
        initHideEventHandler(height);

        // Live re-layout when the user applies new desktop settings in the
        // Control Center (thickness, docking edge, icon size, auto-hide).
        LgEventConnector.getLgEventConnector().addListener(
            LgEventSource.ALL_SOURCES,
            new LgEventListener() {
                public void processEvent(final LgEvent evt) {
                    Toolkit3D tk = Toolkit3D.getToolkit3D();
                    applyConfig(tk.getScreenWidth(), tk.getScreenHeight(), 300);
                }
                public Class<LgEvent>[] getTargetEventClasses() {
                    return new Class[] {DesktopConfigChangeEvent.class};
                }
            });

        installAutoHide();
    }
    
    private abstract class BackgroundIcon extends Pseudo3DIcon {
        protected Background background = null;
        private BackgroundIcon(URL filename) {
            super(filename);
            addListener(
                new MouseClickedEventAdapter(
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        select();
                    }
            }));
        }
        public void select() {
            if (background == null) {
                // All this complications are for performing background
                // initialization lazily when selected...
                background = initBackground();
            }
            GlassyTaskbar.this.postEvent(new BackgroundChangeRequestEvent(background));
        }
        protected abstract Background initBackground();
    }
    
    @Override
    public void addThumbnail(Component3D thumbnail) {
        if (thumbnail == null) {
            throw new IllegalArgumentException("argument cannot be null");
        }
        appThumbnails.addChild(thumbnail);
    }
    
    @Override
    public void removeThumbnail(Component3D thumbnail) {
        appThumbnails.removeChild(thumbnail);
    }
    
    @Override
    public void addTaskbarItem(Component3D item, int index) {
        if(item == null) {
            throw new IllegalArgumentException("Taskbar item cannot be null");
        }
        item.addListener(new MouseClickedEventAdapter(ButtonId.BUTTON3, 
                true, null, new RemoveTaskbarItemAction(item)));
        
        Container3D targetContainer;
        int insertAt;
        if (index < 0) {
            // Right-aligned group (themes). A negative index -n means "n-th from
            // the right": -1 is rightmost, -2 sits to its left, and so on. Order
            // the new item by its index among the existing right-side items so
            // the result does not depend on the order plugins post them.
            targetContainer = themes;
            insertAt = 0;
            for (Integer existing : themesIndex.values()) {
                if (existing.intValue() < index) {
                    insertAt++;
                }
            }
            themesIndex.put(item, Integer.valueOf(index));
        } else {
            targetContainer = shortcuts;
            insertAt = index;
        }
        if (insertAt > targetContainer.numChildren()) {
            insertAt = targetContainer.numChildren();
        } else if (insertAt < 0) {
            insertAt = 0;
        }
        targetContainer.addChild(item, insertAt);
        // Normalise any icon nested in the item (e.g. a Pseudo3DIcon wrapped in a
        // Tapp) to the configured icon size. A plugin may build its icon before
        // the taskbar publishes DesktopConfig's icon scale, so it would otherwise
        // keep a stale baked scale that the periodic rescaleIcons() pass cannot
        // see through the wrapper.
        rescaleDeep(item, DesktopConfig.get().getIconScale());
    }
    
    @Override
    public void removeTaskbarItem(Component3D item) {
        // Right-side items live in themes; everything else in shortcuts.
        if (themesIndex.remove(item) != null) {
            themes.removeChild(item);
        } else {
            shortcuts.removeChild(item);
        }
    }
    
    private class RemoveTaskbarItemAction implements ActionNoArg {
        private Component3D item;
        public RemoveTaskbarItemAction(Component3D item) {
            this.item= item;
        }
        public void performAction(LgEventSource source) {
            removeTaskbarItem(item);
        }
    }
    
    private void changeSize(float width, float height) {
        applyConfig(width, height, 200);
    }

    /** Pins the frosted front-edge strip under the front lip of the slab top
     *  face (bottomBarComp local space; the comp's shelf rotation mirrors it
     *  automatically for top docking). */
    private void positionFrostedEdge() {
        bottomBarEdgeComp.setTranslation(
            0.0f, -barHeight * 0.5f, -barDepth * 0.5f);
    }

    /** The Switch index of the shelf style the persisted preference selects. */
    private int frostedBarChild() {
        return DesktopConfig.get().isFrostedGlass() && bottomBarFrosted != null
            ? 1 : 0;
    }

    /**
     * Re-lays-out the bar from the current {@link DesktopConfig}: thickness
     * (barScale), docking edge (position), icon size (iconScale) and the
     * reserved strip published for maximized-window clearance. Runs at startup,
     * on screen-resolution change, and live when the user applies new settings
     * in the Control Center (via {@link DesktopConfigChangeEvent}).
     */
    private void applyConfig(float width, float height, int animMs) {
        DesktopConfig cfg = DesktopConfig.get();
        barHeight = BASE_BAR_HEIGHT * cfg.getBarScale();
        Pseudo3DIcon.setIconScale(cfg.getIconScale());

        setPreferredSize(new Vector3f(width, barHeight, barHeight));
        if (bottomBar != null) {
            bottomBar.setSize(width, barHeight);
        }
        if (bottomBarFrosted != null) {
            bottomBarFrosted.setSize(width, barHeight);
        }
        if (bottomBarFrostedEdge != null) {
            bottomBarFrostedEdge.setSize(width, barDepth);
            positionFrostedEdge();
        }
        if (barSwitch != null) {
            barSwitch.setWhichChild(frostedBarChild());
        }
        // Mirror the bar tilt and the glass-shelf orientation for the docking
        // edge in use, so a top-docked bar is the vertical reflection of the
        // bottom one instead of leaning the same (wrong-looking) way.
        changeRotationAngle(tiltRadians(), animMs);
        bottomBarComp.changeRotationAngle(shelfRotRadians(), animMs);
        bottomBarComp.setTranslation(0.0f, shelfYOffset(), barHeight * SHELF_Z_OFFSET);
        shortcuts.setPreferredSize(new Vector3f(width, barHeight, barHeight));
        themes.setPreferredSize(new Vector3f(rightGroupWidth(width), barHeight, barHeight));
        appThumbnails.setPreferredSize(new Vector3f(width, barHeight, barHeight));

        changeTranslation(0.0f, dockedY(height), barZ, animMs);

        // Publish the reserved strip for the docking edge in use so maximized
        // windows fill the usable area and never cover the bar.
        if (cfg.getPosition() == DesktopConfig.Position.TOP) {
            setReservedTopHeight(barHeight);
            setReservedBottomHeight(0.0f);
        } else {
            setReservedBottomHeight(barHeight);
            setReservedTopHeight(0.0f);
        }

        rescaleIcons();
    }

    private boolean isTop() {
        return DesktopConfig.get().getPosition() == DesktopConfig.Position.TOP;
    }

    /** Whole-bar tilt about X: positive at the bottom edge, mirrored negative
     *  at the top so the bar face leans toward the viewer either way. */
    private float tiltRadians() {
        return (float) Math.toRadians(isTop() ? -BAR_TILT_DEG : BAR_TILT_DEG);
    }

    /** Glass-shelf lay-flat rotation about X, mirrored for top docking. */
    private float shelfRotRadians() {
        return (float) Math.toRadians(isTop() ? -SHELF_ROT_DEG : SHELF_ROT_DEG);
    }

    /** Glass-shelf Y offset (fraction of barHeight), mirrored for top docking. */
    private float shelfYOffset() {
        return barHeight * (isTop() ? -SHELF_Y_OFFSET : SHELF_Y_OFFSET);
    }

    private float dockedY(float height) {
        return isTop() ? (height * 0.5f - barHeight * 0.5f)
                       : (height * -0.5f + barHeight * 0.5f);
    }

    private float hiddenY(float height) {
        // Slide mostly off the docking edge but leave a thin sliver on screen so
        // the pointer can re-enter the bar and bring it back.
        return isTop() ? (height * 0.5f + barHeight * 0.3f)
                       : (height * -0.5f - barHeight * 0.3f);
    }

    /** Preferred width for the right-aligned icon group: the full screen width
     *  less a symmetric inset, so {@code HorizontalLayout.RIGHT} packs the icons
     *  {@link #RIGHT_GROUP_INSET_BAR_HEIGHTS} bar-heights in from the edge and
     *  the rightmost one stays on the bar instead of off its tapered tip. */
    private float rightGroupWidth(float screenWidth) {
        return screenWidth - 2.0f * barHeight * RIGHT_GROUP_INSET_BAR_HEIGHTS;
    }

    private void rescaleIcons() {
        float scale = DesktopConfig.get().getIconScale();
        rescaleChildren(shortcuts, scale);
        rescaleChildren(themes, scale);
    }

    private void rescaleChildren(Container3D container, float scale) {
        for (int i = 0; i < container.numChildren(); i++) {
            Node child = container.getChild(i);
            if (child instanceof Component3D) {
                rescaleDeep((Component3D) child, scale);
            }
        }
    }

    /**
     * Rescales {@code comp} and every {@link Pseudo3DIcon} nested beneath it, so
     * icons wrapped in a container (a {@code Tapp} taskbar item) follow the
     * configured icon size instead of keeping their construction-time scale.
     */
    private void rescaleDeep(Component3D comp, float scale) {
        if (comp instanceof Pseudo3DIcon) {
            ((Pseudo3DIcon) comp).rescale(scale);
        }
        if (comp instanceof Container3D) {
            Container3D inner = (Container3D) comp;
            for (int i = 0; i < inner.numChildren(); i++) {
                Node child = inner.getChild(i);
                if (child instanceof Component3D) {
                    rescaleDeep((Component3D) child, scale);
                }
            }
        }
    }

    /**
     * Self-contained auto-hide: when enabled in {@link DesktopConfig} the bar
     * slides off its docking edge on pointer-exit and back on pointer-enter.
     * The legacy {@link HideEvent} path is dormant (nothing posts it), so the
     * toggle is driven directly by {@link MouseEnteredEvent3D} on the bar.
     */
    private void installAutoHide() {
        setMouseEventSource(MouseEnteredEvent3D.class, true);
        addListener(new LgEventListener() {
            public void processEvent(final LgEvent evt) {
                if (!DesktopConfig.get().isAutoHide()) {
                    return;
                }
                MouseEnteredEvent3D me = (MouseEnteredEvent3D) evt;
                float h = Toolkit3D.getToolkit3D().getScreenHeight();
                float y = me.isEntered() ? dockedY(h) : hiddenY(h);
                changeTranslation(0.0f, y, barZ, 300);
            }
            public Class<LgEvent>[] getTargetEventClasses() {
                return new Class[] {MouseEnteredEvent3D.class};
            }
        });
    }
    
    private void initHideEventHandler(final float height) {
        // Listen for handling the hide event
        setAnimation(new NaturalMotionAnimation(3000));
        LgEventConnector.getLgEventConnector().addListener(
            LgEventSource.ALL_SOURCES,
            new LgEventListener() {
                public void processEvent(final LgEvent evt) {
                    changeTranslation(0.0f, height * -0.5f - barHeight * 5.0f, barZ);
                }
                public Class<LgEvent>[] getTargetEventClasses() {
                    return new Class[] {HideEvent.class};
                }
            });
    }
            
    public static class HideEvent extends LgEvent {
        // just a tag class
    }
}

