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
package org.jdesktop.lg3d.utils.prefs;

import java.util.prefs.Preferences;
import java.util.logging.Logger;

/**
 * User-facing desktop configuration: taskbar thickness, position, icon size,
 * the Swing application UI font, and the taskbar auto-hide toggle.
 * <p>
 * The settings live in lg3d-core so both the scene-manager taskbar and the
 * (demo-apps) Control Center can share one model - demo-apps depends on core,
 * never the reverse. Values are persisted through {@link LgPreferencesHelper}
 * (i.e. {@code java.util.prefs}); setters only mutate in-memory state, so a
 * caller batches changes and then invokes {@link #save()}.
 * <p>
 * The instance is a lazily-created singleton ({@link #get()}) that reads the
 * stored preferences once on first access. After a {@code save()}, editors
 * post a {@code DesktopConfigChangeEvent} so live consumers (the taskbar, the
 * Swing look-and-feel) re-read the model and re-apply.
 */
public final class DesktopConfig {

    private static final Logger logger = Logger.getLogger("lg.utils");

    /** Default taskbar thickness (world units) at {@code barScale == 1.0}. */
    public static final float DEFAULT_BAR_HEIGHT = 0.034f;
    /** Default icon edge length (world units) at {@code iconScale == 1.0}. */
    public static final float DEFAULT_ICON_SIZE = 0.01f;

    // Preference keys.
    private static final String KEY_BAR_SCALE = "taskbar.barScale";
    private static final String KEY_POSITION = "taskbar.position";
    private static final String KEY_ICON_SCALE = "taskbar.iconScale";
    private static final String KEY_AUTO_HIDE = "taskbar.autoHide";
    private static final String KEY_FONT_NAME = "swing.fontName";
    private static final String KEY_FONT_SIZE = "swing.fontSize";

    // Defaults and ranges.
    private static final float DEF_BAR_SCALE = 1.0f;
    private static final float DEF_ICON_SCALE = 1.0f;
    private static final boolean DEF_AUTO_HIDE = false;
    /** Default Swing font family name. */
    public static final String DEFAULT_FONT_NAME = "SansSerif";
    /** Default Swing font size, in points. */
    public static final int DEFAULT_FONT_SIZE = 12;
    private static final String DEF_FONT_NAME = DEFAULT_FONT_NAME;
    private static final int DEF_FONT_SIZE = DEFAULT_FONT_SIZE;

    /** Minimum/maximum {@code barScale} and {@code iconScale}. */
    public static final float MIN_SCALE = 0.6f;
    public static final float MAX_SCALE = 2.0f;
    /** Minimum/maximum {@code fontSize}. */
    public static final int MIN_FONT_SIZE = 9;
    public static final int MAX_FONT_SIZE = 24;

    /** Where the taskbar is docked. {@code LEFT}/{@code RIGHT} are reserved
     *  for a later phase and are not offered in the UI yet. */
    public enum Position {
        BOTTOM, TOP, LEFT, RIGHT
    }

    private static volatile DesktopConfig instance;

    private final Preferences prefs;

    private float barScale = DEF_BAR_SCALE;
    private Position position = Position.BOTTOM;
    private float iconScale = DEF_ICON_SCALE;
    private boolean autoHide = DEF_AUTO_HIDE;
    private String fontName = DEF_FONT_NAME;
    private int fontSize = DEF_FONT_SIZE;

    private DesktopConfig() {
        this.prefs = LgPreferencesHelper.userNodeForPackage(DesktopConfig.class);
        load();
    }

    /** The shared instance, reading stored preferences on first access. */
    public static DesktopConfig get() {
        DesktopConfig c = instance;
        if (c == null) {
            synchronized (DesktopConfig.class) {
                c = instance;
                if (c == null) {
                    c = new DesktopConfig();
                    instance = c;
                }
            }
        }
        return c;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** (Re)reads all values from the backing preferences node. */
    public void load() {
        barScale = clampScale(prefs.getFloat(KEY_BAR_SCALE, DEF_BAR_SCALE));
        iconScale = clampScale(prefs.getFloat(KEY_ICON_SCALE, DEF_ICON_SCALE));
        autoHide = prefs.getBoolean(KEY_AUTO_HIDE, DEF_AUTO_HIDE);
        fontName = prefs.get(KEY_FONT_NAME, DEF_FONT_NAME);
        fontSize = clampFontSize(prefs.getInt(KEY_FONT_SIZE, DEF_FONT_SIZE));
        position = parsePosition(prefs.get(KEY_POSITION, Position.BOTTOM.name()));
    }

    /** Writes all in-memory values to the backing preferences node. */
    public void save() {
        prefs.putFloat(KEY_BAR_SCALE, barScale);
        prefs.put(KEY_POSITION, position.name());
        prefs.putFloat(KEY_ICON_SCALE, iconScale);
        prefs.putBoolean(KEY_AUTO_HIDE, autoHide);
        prefs.put(KEY_FONT_NAME, fontName);
        prefs.putInt(KEY_FONT_SIZE, fontSize);
        try {
            prefs.flush();
        } catch (Exception e) {
            logger.warning("Failed to persist desktop config: " + e);
        }
    }

    /** Restores every setting to its built-in default (in memory; call
     *  {@link #save()} to persist). */
    public void resetToDefaults() {
        barScale = DEF_BAR_SCALE;
        position = Position.BOTTOM;
        iconScale = DEF_ICON_SCALE;
        autoHide = DEF_AUTO_HIDE;
        fontName = DEF_FONT_NAME;
        fontSize = DEF_FONT_SIZE;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public float getBarScale() {
        return barScale;
    }

    public void setBarScale(float barScale) {
        this.barScale = clampScale(barScale);
    }

    /** The taskbar thickness in world units ({@code DEFAULT_BAR_HEIGHT * barScale}). */
    public float getBarHeight() {
        return DEFAULT_BAR_HEIGHT * barScale;
    }

    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = (position == null) ? Position.BOTTOM : position;
    }

    public float getIconScale() {
        return iconScale;
    }

    public void setIconScale(float iconScale) {
        this.iconScale = clampScale(iconScale);
    }

    /** The icon edge length in world units ({@code DEFAULT_ICON_SIZE * iconScale}). */
    public float getIconSize() {
        return DEFAULT_ICON_SIZE * iconScale;
    }

    public boolean isAutoHide() {
        return autoHide;
    }

    public void setAutoHide(boolean autoHide) {
        this.autoHide = autoHide;
    }

    public String getFontName() {
        return fontName;
    }

    public void setFontName(String fontName) {
        this.fontName = (fontName == null || fontName.isEmpty()) ? DEF_FONT_NAME : fontName;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = clampFontSize(fontSize);
    }

    // ------------------------------------------------------------------

    private static float clampScale(float v) {
        if (Float.isNaN(v)) {
            return DEF_BAR_SCALE;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, v));
    }

    private static int clampFontSize(int v) {
        return Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, v));
    }

    private static Position parsePosition(String s) {
        if (s != null) {
            try {
                return Position.valueOf(s);
            } catch (IllegalArgumentException e) {
                // fall through to the default
            }
        }
        return Position.BOTTOM;
    }
}
