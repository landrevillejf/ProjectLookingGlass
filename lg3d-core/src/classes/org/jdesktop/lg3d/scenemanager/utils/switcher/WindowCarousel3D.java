/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.jogamp.vecmath.Color4f;
import org.jdesktop.lg3d.sg.Texture;
import org.jdesktop.lg3d.utils.action.ActionBoolean;
import org.jdesktop.lg3d.utils.action.ActionInt;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseWheelEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.shape.ImagePanel;
import org.jdesktop.lg3d.utils.shape.RectShadow;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Container3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.LayoutManager3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;

/**
 * The native 3D desktop's window switcher rendered as a {@code CDViewer}-style
 * circular carousel: one card per open window, seated on a circle in the screen
 * plane with the selected window at the front, enlarged, and centred. Each card
 * is textured with the window's own live miniature (see
 * {@link WindowThumbnailSource}) instead of a CD, so the user recognises the
 * window by its content; a window with no live texture falls back to a titled
 * glass card.
 *
 * <p>The positioning arithmetic lives in the pure, headless-testable
 * {@link CarouselLayout}; this node only builds the cards, feeds that layout, and
 * turns pointer gestures into callbacks:</p>
 * <ul>
 *   <li>mouse wheel &rarr; {@linkplain #setRevolveListener(IntConsumer) revolve},
 *       so the user can spin the carousel to a window;</li>
 *   <li>click on a card &rarr; {@linkplain #setSelectListener(Consumer) select}
 *       that window, committing it straight to front;</li>
 *   <li>pointer enter/exit &rarr; {@linkplain #setFocusListener(Consumer) focus},
 *       which fans the ring out and lets the plugin pause its idle-commit timer
 *       while the user is pointing at the carousel.</li>
 * </ul>
 *
 * <p>Like the {@code CDViewer}, the node is pickable while shown so it receives
 * those gestures; the plugin hides it when the session ends. Textures are shared
 * (never re-created) and each card is fully built &mdash; appearance and texture
 * attached &mdash; before it is added to the live ring, so the Jogamp
 * "pixels before {@code setTexture} on a live graph" rule is respected.</p>
 */
public class WindowCarousel3D extends Component3D {

    /** Card face size in world units; a fixed size keeps the ring uniform. */
    static final float CARD_W = 0.14f;
    static final float CARD_H = 0.10f;
    /** Title band height beneath the card face. */
    static final float TITLE_H = 0.016f;
    /** Ring radius, and the wider radius when the pointer fans it out. */
    static final float FAN_RADIUS = 0.16f;
    static final float FAN_RADIUS_FOCUSED = 0.21f;

    private static final Color4f TITLE_COLOR = new Color4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Color4f FALLBACK_TINT = new Color4f(0.22f, 0.36f, 0.60f, 0.92f);

    private final Container3D ring;
    private final CarouselLayout layoutMath = new CarouselLayout();

    /** Cards in window (MRU) order; index == the slot fed to {@link CarouselLayout}. */
    private final List<Component3D> cardList = new ArrayList<>();
    private final Map<Frame3D, Component3D> cardByFrame = new HashMap<>();

    private WindowThumbnailSource source = new SwingNodeThumbnailSource();
    private int frontIndex = 0;
    private boolean focused = false;

    private IntConsumer revolveListener;
    private Consumer<Frame3D> selectListener;
    private Consumer<Boolean> focusListener;

    public WindowCarousel3D() {
        setName("WindowCarousel3D");
        ring = new Container3D();
        ring.setLayout(new RingLayout());
        addChild(ring);

        setPickable(true);
        setMouseEventPropagatable(true);
        setVisible(false);

        addListener(new MouseWheelEventAdapter(new ActionInt() {
            @Override
            public void performAction(LgEventSource src, int clicks) {
                IntConsumer l = revolveListener;
                if (l != null) {
                    l.accept(clicks);
                }
            }
        }));
        addListener(new MouseEnteredEventAdapter(new ActionBoolean() {
            @Override
            public void performAction(LgEventSource src, boolean flag) {
                setFocused(flag);
            }
        }));
    }

    // ------------------------------------------------------------- configuration

    /** Sets the miniature source (defaults to {@link SwingNodeThumbnailSource}). */
    public void setThumbnailSource(WindowThumbnailSource source) {
        this.source = (source != null) ? source : new SwingNodeThumbnailSource();
    }

    /** Wheel gesture: signed click count to spin the carousel. */
    public void setRevolveListener(IntConsumer listener) {
        this.revolveListener = listener;
    }

    /** Click gesture: the window whose card was clicked, to bring to front. */
    public void setSelectListener(Consumer<Frame3D> listener) {
        this.selectListener = listener;
    }

    /** Pointer enter/exit: whether the carousel is being pointed at. */
    public void setFocusListener(Consumer<Boolean> listener) {
        this.focusListener = listener;
    }

    // ------------------------------------------------------------------- session

    /**
     * Shows the carousel over {@code frames} (MRU order) with {@code selected}
     * seated at the front. Cards for new windows are built, cards for closed
     * windows torn down, and the ring re-laid-out.
     */
    public void show(List<Frame3D> frames, int selected) {
        syncCards(frames);
        frontIndex = (selected >= 0 && selected < cardList.size()) ? selected : 0;
        ring.revalidate();
        setVisible(true);
    }

    /** Hides the carousel; cards are kept for reuse until {@link #dispose()}. */
    public void hide() {
        setVisible(false);
    }

    private void setFocused(boolean flag) {
        if (focused == flag) {
            return;
        }
        focused = flag;
        ring.revalidate();
        Consumer<Boolean> l = focusListener;
        if (l != null) {
            l.accept(flag);
        }
    }

    // --------------------------------------------------------------------- cards

    private void syncCards(List<Frame3D> frames) {
        List<Frame3D> wanted = (frames == null) ? List.of() : frames;

        // Tear down cards whose window is gone.
        for (Frame3D frame : new ArrayList<>(cardByFrame.keySet())) {
            if (!wanted.contains(frame)) {
                Component3D card = cardByFrame.remove(frame);
                ring.removeChild(card);
                source.release(frame);
            }
        }

        // Rebuild the ordered slot list, reusing surviving cards.
        cardList.clear();
        for (Frame3D frame : wanted) {
            Component3D card = cardByFrame.get(frame);
            if (card == null) {
                card = buildCard(frame);
                cardByFrame.put(frame, card);
                ring.addChild(card);
            }
            cardList.add(card);
        }
    }

    /**
     * Builds one window card fully detached (geometry + appearance + texture) so
     * the texture is attached before the card joins the live ring.
     */
    private Component3D buildCard(Frame3D frame) {
        Component3D card = new Component3D();
        card.setName("WindowCard:" + frame.getName());
        // Cards turn tangentially about Z as the ring revolves (CDViewer's disc spin).
        card.setRotationAxis(0.0f, 0.0f, 1.0f);
        card.setPickable(true);
        card.setMouseEventPropagatable(true);

        Texture texture = null;
        try {
            texture = source.textureFor(frame);
        } catch (Throwable t) {
            texture = null;
        }

        SimpleAppearance app;
        if (texture != null) {
            app = new SimpleAppearance(1.0f, 1.0f, 1.0f, 1.0f,
                    SimpleAppearance.ENABLE_TEXTURE | SimpleAppearance.DISABLE_CULLING);
            app.setTexture(texture);
        } else {
            app = new SimpleAppearance(FALLBACK_TINT.x, FALLBACK_TINT.y,
                    FALLBACK_TINT.z, FALLBACK_TINT.w, SimpleAppearance.DISABLE_CULLING);
        }
        ImagePanel face = new ImagePanel(CARD_W, CARD_H);
        face.setAppearance(app);
        card.addChild(face);

        GlassyText2D title = new GlassyText2D(
                safeTitle(frame), CARD_W * 0.98f, TITLE_H, TITLE_COLOR,
                GlassyText2D.LightDirection.TOP_LEFT, GlassyText2D.Alignment.CENTER);
        // GlassyText2D is a Shape3D (no transform of its own), so it rides in a
        // positioning Component3D seated just below the card face.
        Component3D titleNode = new Component3D();
        titleNode.addChild(title);
        titleNode.setTranslation(0.0f, -CARD_H / 2 - TITLE_H, 0.001f);
        card.addChild(titleNode);

        RectShadow shadow = new RectShadow(CARD_W, CARD_H,
                0.004f, 0.004f, 0.004f, 0.004f, 0.002f, -0.002f, 0.4f);
        card.addChild(shadow);

        final Frame3D target = frame;
        card.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            @Override
            public void performAction(LgEventSource src) {
                Consumer<Frame3D> l = selectListener;
                if (l != null) {
                    l.accept(target);
                }
            }
        }));
        return card;
    }

    private static String safeTitle(Frame3D frame) {
        String name = frame.getName();
        return (name == null || name.isBlank()) ? "(untitled)" : name;
    }

    // -------------------------------------------------------------------- layout

    /**
     * Positions every card from the pure {@link CarouselLayout}. The ring is
     * lifted by its radius so the front card (at the circle's bottom, angle
     * {@code 3*PI/2}) lands centred on the node origin the plugin places.
     */
    private void layoutRing() {
        float radius = focused ? FAN_RADIUS_FOCUSED : FAN_RADIUS;
        int count = cardList.size();
        ring.setTranslation(0.0f, radius, 0.0f);
        for (int slot = 0; slot < count; slot++) {
            Component3D card = cardList.get(slot);
            CarouselLayout.Pose pose =
                    layoutMath.pose(slot, frontIndex, count, radius, true);
            card.changeTranslation(pose.x(), pose.y(), pose.z());
            card.changeRotationAngle(pose.rotAngle());
            card.changeScale(pose.scale());
        }
    }

    /**
     * The ring's {@link LayoutManager3D}: ordering lives in the node's
     * {@link #cardList} (window/MRU order), so the layout only has to apply poses
     * on {@link Container3D#revalidate()}. Revolve is driven externally by the
     * plugin updating {@link #frontIndex}, hence {@code rearrangeLayoutComponent}
     * is a no-op.
     */
    private final class RingLayout implements LayoutManager3D {
        @Override
        public void setContainer(Container3D cont) {
            // The node owns the ring; nothing to record.
        }

        @Override
        public void layoutContainer() {
            layoutRing();
        }

        @Override
        public void addLayoutComponent(Component3D comp, Object constraints) {
            // Ordering is authoritative in cardList, not add order.
        }

        @Override
        public void removeLayoutComponent(Component3D comp) {
            // Ordering is authoritative in cardList, not add order.
        }

        @Override
        public boolean rearrangeLayoutComponent(Component3D comp, Object newConstraints) {
            return false;
        }
    }

    // ------------------------------------------------------------------- teardown

    /** Removes every card and releases the thumbnail listeners. Frees resources. */
    public void dispose() {
        for (Frame3D frame : new ArrayList<>(cardByFrame.keySet())) {
            source.release(frame);
        }
        cardByFrame.clear();
        cardList.clear();
        ring.removeAllChildren();
        if (source instanceof SwingNodeThumbnailSource sns) {
            sns.releaseAll();
        }
        setVisible(false);
    }
}
