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
package org.jdesktop.lg3d.apps;

import javax.swing.JPanel;
import javax.swing.LookAndFeel;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Font;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.Transform3D;
import org.jdesktop.lg3d.sg.TransformGroup;
import org.jdesktop.lg3d.scenemanager.utils.decoration.Frame3DWindowDecoration;
import org.jdesktop.lg3d.utils.eventaction.Component3DMover;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.utils.shape.FuzzyEdgePanel;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.shape.RectShadow;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Thumbnail;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * Presents a conventional Swing {@link JPanel} as an integrated 3D desktop
 * window: a {@link Frame3D} that hosts the panel on a {@link SwingNode} below a
 * real title bar.
 *
 * <p>Why this exists: a bare {@code SwingNode} fills the whole {@code Frame3D}
 * with one opaque, pickable quad. Because lg3d mouse events do <em>not</em>
 * propagate to ancestor components by default
 * ({@link Component3D#setMouseEventPropagatable}), that quad becomes the event
 * source for the entire window - it swallows the BUTTON1 drag that
 * {@code ZLayeredMovableLayout}'s {@link Component3DMover} needs (so the window
 * cannot be moved) and it covers the auto-attached minimize / maximize / close
 * buttons that {@code Frame3DWindowDecoration} places at the top-right corner
 * (so they are neither visible nor clickable).</p>
 *
 * <p>This helper reserves a title-bar strip at the top of the frame, shifts the
 * Swing content down into the remaining area, and makes the strip a pickable,
 * event-propagating drag handle: gestures picked on it travel up to the
 * frame-level listeners of {@code ZLayeredMovableLayout} and
 * {@link Frame3DWindowDecoration}, so the window keeps every desktop idiom -
 * left-drag the title bar to move it, middle-drag or CTRL+left-drag to rotate
 * it, right-click to flip it to the sticky note - and the standard window
 * buttons sit in the clear strip.</p>
 */
public final class TitledSwingWindow {

    /** Height of the title-bar strip in physical (world) units. */
    private static final float TITLE_BAR_HEIGHT = 0.012f;
    /** Thickness of the title-bar panel. */
    private static final float TITLE_BAR_DEPTH = 0.004f;
    /** Glyph height of the vertical edge ("spine") titles. */
    private static final float SPINE_GLYPH_HEIGHT = 0.006f;
    /** Lift that keeps the spine quad just outside the backdrop side face. */
    private static final float SPINE_MARGIN = 0.0002f;
    /** Taskbar thumbnail scale (matches DefaultThumbnail / native windows). */
    private static final float THUMBNAIL_SCALE = 0.13f;
    /** Fuzzy border of the thumbnail body (HelpThumbnail's, pre-scaled). */
    private static final float THUMBNAIL_DECO = 0.005f * THUMBNAIL_SCALE;
    /** Thickness of the thumbnail glass slab, pre-scaled. */
    private static final float THUMBNAIL_DEPTH = 0.02f * THUMBNAIL_SCALE;

    /** UIManager font-default keys overridden by the desktop configuration. */
    private static final String[] FONT_KEYS = {
        "Label.font", "Button.font", "ToggleButton.font", "TextField.font",
        "TextArea.font", "ComboBox.font", "List.font", "Table.font",
        "TableHeader.font", "Menu.font", "MenuItem.font", "PopupMenu.font",
        "Panel.font", "Dialog.font", "Frame.font", "TitledBorder.font",
        "OptionPane.font", "CheckBox.font", "RadioButton.font",
        "TabbedPane.font", "Tree.font", "ToolBar.font", "Spinner.font",
        "EditorPane.font", "TextPane.font", "FormattedTextField.font",
        "PasswordField.font", "ToolTip.font"
    };

    private TitledSwingWindow() {
    }

    /**
     * Installs the platform (system) Swing look-and-feel so hosted panels
     * render as conventional desktop UIs instead of with the default
     * cross-platform Metal look. Call <em>before</em> constructing the panel so
     * its child components are created with the right UI delegates. A failure
     * leaves the current look-and-feel untouched.
     */
    public static void installNativeLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Keep whatever look-and-feel is already active.
        }
        applySwingFontDefaults();
    }

    /**
     * Applies the configured Swing UI font (family + size) from
     * {@link DesktopConfig} to the {@link UIManager} defaults, so hosted panels
     * built afterwards render with the user's chosen font. While the config
     * still holds the built-in default the platform look-and-feel font is left
     * untouched (and any previous override is rolled back to the LAF default).
     * Safe to call again after a configuration change to re-apply live.
     */
    public static void applySwingFontDefaults() {
        DesktopConfig cfg = DesktopConfig.get();
        boolean isDefault = DesktopConfig.DEFAULT_FONT_NAME.equals(cfg.getFontName())
                && cfg.getFontSize() == DesktopConfig.DEFAULT_FONT_SIZE;
        if (isDefault) {
            // Restore the active look-and-feel's own fonts, undoing any earlier
            // override so "reset to defaults" really returns to the native look.
            LookAndFeel laf = UIManager.getLookAndFeel();
            UIDefaults defs = (laf == null) ? null : laf.getDefaults();
            if (defs != null) {
                for (String key : FONT_KEYS) {
                    Object v = defs.get(key);
                    if (v != null) {
                        UIManager.put(key, v);
                    }
                }
            }
            return;
        }
        FontUIResource font = new FontUIResource(
                new Font(cfg.getFontName(), Font.PLAIN, cfg.getFontSize()));
        for (String key : FONT_KEYS) {
            UIManager.put(key, font);
        }
    }

    /**
     * Builds, shows and returns a titled 3D window hosting {@code panel}.
     *
     * @param title    the text shown in the title bar (and the frame name)
     * @param panel    the Swing content, already constructed
     * @param widthPx  the panel width in native pixels
     * @param heightPx the panel height in native pixels
     */
    public static Frame3D show(String title, JPanel panel, int widthPx, int heightPx) {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        float contentW = tk.widthNativeToPhysical(widthPx);
        float contentH = tk.heightNativeToPhysical(heightPx);

        SwingNode node = new SwingNode();
        node.setJPanel(panel);
        node.setTransparency(0.0f);
        // Shift the Swing quad down so it fills only the content area, leaving
        // the top strip clear for the title bar and the window buttons.
        node.setTranslation(0.0f, -TITLE_BAR_HEIGHT * 0.5f, 0.0f);

        Frame3D frame = new Frame3D();
        frame.setName(title);
        frame.addChild(node);

        Component3D titleBar = buildTitleBar(title, contentW, contentH);
        // Every window gesture of the desktop lives in frame-level listeners:
        // ZLayeredMovableLayout adds the BUTTON1 mover and the CTRL spinner to
        // each frame, and Frame3DWindowDecoration adds the BUTTON2 spinner and
        // the BUTTON3 flip. PickEngine only delivers a picked event up the
        // ancestor chain while each source it meets is propagatable, which the
        // Swing quad must not be (or Swing would lose its own gestures). So
        // make the title bar propagatable instead: it becomes the window's
        // gesture handle - left-drag moves, middle-drag or CTRL+left-drag
        // rotates, right-click flips to the sticky note - the same idioms as
        // pure-3D windows, with no duplicate listeners (the native window
        // look-and-feel uses the same trick for its title panel).
        titleBar.setMouseEventPropagatable(true);
        frame.addChild(titleBar);

        // Vertical "spine" titles on the left and right edges, each pre-rotated
        // +/-90deg about Y. In the normal front-facing view they are edge-on and
        // effectively invisible; when the window is parked on the bookshelf
        // (right-click the desktop) BookshelfLayout rotates the frame +/-90deg,
        // so the matching spine turns to face the viewer and reads like a book
        // spine. This mirrors the SpineTitle chrome of the native X11 window
        // look-and-feel, which the pure-3D Frame3D path otherwise lacks.
        frame.addChild(buildSpineTitle(title, contentW, contentH, -1));
        frame.addChild(buildSpineTitle(title, contentW, contentH, +1));

        // Live miniature for the taskbar: without an explicit thumbnail
        // StandardAppContainer falls back to DefaultThumbnail, a plain coloured
        // glass plate with no content. Native apps (e.g. Lg3dHelp) avoid that
        // by supplying a thumbnail textured with the window's own image; do the
        // same by observing the SwingNode texture, so the miniature tracks the
        // panel live (same Texture2D object, updated in place on repaints).
        frame.setThumbnail(buildThumbnail(node, contentW, contentH));

        // The frame is content + title bar; Frame3DWindowDecoration (attached
        // during changeEnabled) reads this size and lands its min/max/close
        // buttons in the title strip.
        frame.setPreferredSize(
                new Vector3f(contentW, contentH + TITLE_BAR_HEIGHT, 0.01f));
        frame.changeEnabled(true);
        frame.changeVisible(true);
        return frame;
    }

    /**
     * Builds the taskbar miniature: a glass plate + drop shadow framing a
     * {@link FuzzyEdgePanel} textured with the SwingNode's rendered content
     * (the Lg3dHelp {@code HelpThumbnail} pattern). The texture does not exist
     * yet at construction time - {@code SwingNode.setJPanel} captures on the
     * EDT - so it is bound through {@link SwingNode#addTextureListener} and
     * re-bound on every resize (each resize recreates the {@code Texture2D}).
     */
    private static Thumbnail buildThumbnail(
            SwingNode node, float contentW, float contentH) {
        final float width = contentW * THUMBNAIL_SCALE;
        final float height = contentH * THUMBNAIL_SCALE;

        Thumbnail thumbnail = new Thumbnail();

        GlassyPanel deco = new GlassyPanel(
                width + THUMBNAIL_DECO * 2,
                height + THUMBNAIL_DECO * 2,
                THUMBNAIL_DEPTH,
                new SimpleAppearance(
                        0.6f, 1.0f, 0.6f, 1.0f, SimpleAppearance.DISABLE_CULLING));

        Shape3D shadow = new RectShadow(
                width + THUMBNAIL_DECO * 2,
                height + THUMBNAIL_DECO * 2,
                0.001f * 2, 0.0015f * 2, 0.002f * 2, 0.001f * 2,
                0.001f,
                -THUMBNAIL_DEPTH,
                0.3f);

        // ENABLE_TEXTURE sets ALLOW_TEXTURE_WRITE on the appearance, so the
        // texture can still be (re)bound once the thumbnail is live on the
        // taskbar. The pixels exist before this setTexture call runs (the
        // listener only fires after captureNow has filled the image).
        final SimpleAppearance bodyApp = new SimpleAppearance(
                1.0f, 1.0f, 1.0f, 1.0f,
                SimpleAppearance.ENABLE_TEXTURE | SimpleAppearance.DISABLE_CULLING);
        FuzzyEdgePanel body = new FuzzyEdgePanel(width, height, THUMBNAIL_DECO, bodyApp);

        Component3D thumbBody = new Component3D();
        thumbBody.addChild(deco);
        thumbBody.addChild(shadow);
        thumbBody.addChild(body);
        // No setScale here: width/height are already the thumbnail-sized
        // (content * THUMBNAIL_SCALE) dimensions, unlike HelpThumbnail which
        // builds its children at full size and scales the container.
        thumbnail.addChild(thumbBody);
        thumbnail.setPreferredSize(new Vector3f(
                width + THUMBNAIL_DECO * 2,
                height + THUMBNAIL_DECO * 2,
                THUMBNAIL_DEPTH));

        node.addTextureListener(new SwingNode.TextureListener() {
            public void textureChanged(Texture2D texture) {
                bodyApp.setTexture(texture);
            }
        });
        return thumbnail;
    }

    private static Component3D buildTitleBar(String title, float width, float contentH) {
        Component3D bar = new Component3D();

        SimpleAppearance barApp = new SimpleAppearance(
                0.35f, 0.50f, 0.75f, 0.95f, SimpleAppearance.DISABLE_CULLING);
        bar.addChild(new GlassyPanel(width, TITLE_BAR_HEIGHT, TITLE_BAR_DEPTH, barApp));

        // Title text: GlassyText2D grows upward from its origin (LEFT align), so
        // offset by -textHeight/2 to centre it vertically in the strip, and sit
        // it just in front of the bar panel.
        float textH = TITLE_BAR_HEIGHT * 0.5f;
        GlassyText2D label = new GlassyText2D(
                title, width * 0.6f, textH, new Color4f(0.95f, 0.97f, 1.0f, 1.0f));
        Transform3D t3d = new Transform3D();
        t3d.set(new Vector3f(
                -width * 0.5f + TITLE_BAR_HEIGHT * 0.4f,
                -textH * 0.5f,
                TITLE_BAR_DEPTH * 0.5f + 0.001f));
        TransformGroup tg = new TransformGroup(t3d);
        tg.addChild(label);
        bar.addChild(tg);

        // Centre of the top strip of a frame whose total height is
        // contentH + TITLE_BAR_HEIGHT is y == contentH / 2.
        bar.setTranslation(0.0f, contentH * 0.5f, 0.0f);
        bar.setCursor(Cursor3D.MOVE_CURSOR);
        return bar;
    }

    /**
     * Builds one vertical edge ("spine") title. {@code side} is -1 for the left
     * edge and +1 for the right edge; the sign also drives the pre-rotation so
     * the correct spine faces the viewer once the window is parked on that side
     * of the bookshelf. The component is non-pickable so it never intercepts the
     * drag that moves the window.
     */
    private static Component3D buildSpineTitle(
            String title, float contentW, float contentH, int side) {
        Component3D spine = new Component3D();
        // vertical=true lays the glyphs out along -Y from the origin, so the
        // label hangs down from the top of the content area. The parameters
        // match the native look-and-feel's SpineTitle (TOP_RIGHT light, LEFT
        // alignment, 1.8 width scale).
        GlassyText2D label = new GlassyText2D(
                title, contentH * 0.95f, SPINE_GLYPH_HEIGHT,
                new Color4f(0.95f, 0.97f, 1.0f, 1.0f),
                GlassyText2D.LightDirection.TOP_RIGHT,
                GlassyText2D.Alignment.LEFT,
                1.8f, true);
        spine.addChild(label);
        spine.setRotationAxis(0.0f, 1.0f, 0.0f);
        spine.setRotationAngle((float)Math.toRadians(90.0f * side));

        // Lay the glyph band on the pale green side face of the decoration
        // backdrop: the quad sits just outside the slab (so it never
        // intersects the glass) at x = +/-(contentW/2 + DECO_WIDTH), and its
        // 0.006 glyph band is centred on the slab depth. The GlassyPanel slab
        // grows backwards from its z=0 front face, so with the backdrop at
        // z = -BODY_DEPTH it spans z -0.010..-0.005; the band therefore runs
        // -0.0105..-0.0045, half a glyph proud of each glass face. After the
        // +/-90deg Y rotation the glyph height runs along Z, towards +Z for
        // the left spine and -Z for the right one, hence the side sign.
        float x = side
                * (contentW * 0.5f + Frame3DWindowDecoration.DECO_WIDTH + SPINE_MARGIN);
        float y = contentH * 0.5f;
        float z = -Frame3DWindowDecoration.BODY_DEPTH * 1.5f
                + side * SPINE_GLYPH_HEIGHT * 0.5f;
        spine.setTranslation(x, y, z);
        spine.setPickable(false);
        return spine;
    }
}
