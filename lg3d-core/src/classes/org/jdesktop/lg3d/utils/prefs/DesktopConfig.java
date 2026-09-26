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
    private static final String KEY_HOLIDAY_REGION = "calendar.holidayRegion";
    private static final String KEY_SLIDESHOW_ENABLED = "wallpaper.slideshowEnabled";
    private static final String KEY_SLIDESHOW_INTERVAL = "wallpaper.slideshowIntervalSec";
    private static final String KEY_SLIDESHOW_FOLDER = "wallpaper.slideshowFolder";
    private static final String KEY_DND_ENABLED = "notifications.dndEnabled";
    private static final String KEY_DND_UNTIL = "notifications.dndUntil";
    private static final String KEY_WORKSPACE_COUNT = "workspace.count";
    private static final String KEY_FROSTED_GLASS = "window.frostedGlass";

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
    /**
     * Default holiday region for the calendar: {@code "AUTO"} resolves the
     * statutory-holiday set from the system {@link java.util.Locale} (Canada ->
     * Canadian federal, otherwise US federal). Any explicit token overrides it:
     * {@code "US"}, {@code "CA"} (Canadian federal), {@code "CA:<PROVINCE>"} or
     * {@code "US:<STATE>"} (e.g. {@code "CA:QUEBEC"}).
     */
    public static final String DEFAULT_HOLIDAY_REGION = "AUTO";
    private static final String DEF_HOLIDAY_REGION = DEFAULT_HOLIDAY_REGION;
    /** Whether the wallpaper slideshow cycles by default (off). */
    public static final boolean DEFAULT_SLIDESHOW_ENABLED = false;
    private static final boolean DEF_SLIDESHOW_ENABLED = DEFAULT_SLIDESHOW_ENABLED;
    /** Default wallpaper-slideshow interval, in seconds (5 minutes). */
    public static final int DEFAULT_SLIDESHOW_INTERVAL_SEC = 300;
    private static final int DEF_SLIDESHOW_INTERVAL = DEFAULT_SLIDESHOW_INTERVAL_SEC;
    /**
     * Default slideshow source folder: the empty string means the wallpapers
     * bundled with the shell (the same set the "Change Wallpaper" menu lists),
     * rather than a directory on disk.
     */
    public static final String DEFAULT_SLIDESHOW_FOLDER = "";
    private static final String DEF_SLIDESHOW_FOLDER = DEFAULT_SLIDESHOW_FOLDER;
    /** Minimum/maximum slideshow interval, in seconds. */
    public static final int MIN_SLIDESHOW_INTERVAL_SEC = 10;
    public static final int MAX_SLIDESHOW_INTERVAL_SEC = 3600;
    private static final boolean DEF_DND_ENABLED = false;
    private static final long DEF_DND_UNTIL = 0L;
    /** Default number of 2D-desktop workspaces (virtual desktops). */
    public static final int DEFAULT_WORKSPACE_COUNT = 4;
    private static final int DEF_WORKSPACE_COUNT = DEFAULT_WORKSPACE_COUNT;
    /** Minimum/maximum number of workspaces. */
    public static final int MIN_WORKSPACE_COUNT = 1;
    public static final int MAX_WORKSPACE_COUNT = 9;
    /** Default window-glass style: the fixed-function 2006 GlassyPanel. */
    public static final boolean DEFAULT_FROSTED_GLASS = false;
    private static final boolean DEF_FROSTED_GLASS = DEFAULT_FROSTED_GLASS;

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
    private String holidayRegion = DEF_HOLIDAY_REGION;
    private boolean slideshowEnabled = DEF_SLIDESHOW_ENABLED;
    private int slideshowIntervalSec = DEF_SLIDESHOW_INTERVAL;
    private String slideshowFolder = DEF_SLIDESHOW_FOLDER;
    private boolean dndEnabled = DEF_DND_ENABLED;
    private long dndUntil = DEF_DND_UNTIL;
    private int workspaceCount = DEF_WORKSPACE_COUNT;
    private boolean frostedGlass = DEF_FROSTED_GLASS;

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
        holidayRegion = normalizeRegion(prefs.get(KEY_HOLIDAY_REGION, DEF_HOLIDAY_REGION));
        slideshowEnabled = prefs.getBoolean(KEY_SLIDESHOW_ENABLED, DEF_SLIDESHOW_ENABLED);
        slideshowIntervalSec = clampSlideshowInterval(
                prefs.getInt(KEY_SLIDESHOW_INTERVAL, DEF_SLIDESHOW_INTERVAL));
        slideshowFolder = normalizeFolder(prefs.get(KEY_SLIDESHOW_FOLDER, DEF_SLIDESHOW_FOLDER));
        dndEnabled = prefs.getBoolean(KEY_DND_ENABLED, DEF_DND_ENABLED);
        dndUntil = prefs.getLong(KEY_DND_UNTIL, DEF_DND_UNTIL);
        workspaceCount = clampWorkspaceCount(
                prefs.getInt(KEY_WORKSPACE_COUNT, DEF_WORKSPACE_COUNT));
        frostedGlass = prefs.getBoolean(KEY_FROSTED_GLASS, DEF_FROSTED_GLASS);
    }

    /** Writes all in-memory values to the backing preferences node. */
    public void save() {
        prefs.putFloat(KEY_BAR_SCALE, barScale);
        prefs.put(KEY_POSITION, position.name());
        prefs.putFloat(KEY_ICON_SCALE, iconScale);
        prefs.putBoolean(KEY_AUTO_HIDE, autoHide);
        prefs.put(KEY_FONT_NAME, fontName);
        prefs.putInt(KEY_FONT_SIZE, fontSize);
        prefs.put(KEY_HOLIDAY_REGION, holidayRegion);
        prefs.putBoolean(KEY_SLIDESHOW_ENABLED, slideshowEnabled);
        prefs.putInt(KEY_SLIDESHOW_INTERVAL, slideshowIntervalSec);
        prefs.put(KEY_SLIDESHOW_FOLDER, slideshowFolder);
        prefs.putBoolean(KEY_DND_ENABLED, dndEnabled);
        prefs.putLong(KEY_DND_UNTIL, dndUntil);
        prefs.putInt(KEY_WORKSPACE_COUNT, workspaceCount);
        prefs.putBoolean(KEY_FROSTED_GLASS, frostedGlass);
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
        holidayRegion = DEF_HOLIDAY_REGION;
        slideshowEnabled = DEF_SLIDESHOW_ENABLED;
        slideshowIntervalSec = DEF_SLIDESHOW_INTERVAL;
        slideshowFolder = DEF_SLIDESHOW_FOLDER;
        dndEnabled = DEF_DND_ENABLED;
        dndUntil = DEF_DND_UNTIL;
        workspaceCount = DEF_WORKSPACE_COUNT;
        frostedGlass = DEF_FROSTED_GLASS;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public float getBarScale() {
        return barScale;
    }

    /**
     * Whether decorated {@code Frame3D} windows use the GPU frosted-glass body
     * ({@code FrostedGlassPanel}) instead of the fixed-function 2006
     * {@code GlassyPanel}. Read when a window decoration is built, so it takes
     * effect on newly opened windows.
     */
    public boolean isFrostedGlass() {
        return frostedGlass;
    }

    public void setFrostedGlass(boolean frostedGlass) {
        this.frostedGlass = frostedGlass;
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

    /** The configured holiday-region token (never null/blank; {@code "AUTO"} by default). */
    public String getHolidayRegion() {
        return holidayRegion;
    }

    public void setHolidayRegion(String holidayRegion) {
        this.holidayRegion = normalizeRegion(holidayRegion);
    }

    /** True when the wallpaper slideshow cycles rather than showing one image. */
    public boolean isSlideshowEnabled() {
        return slideshowEnabled;
    }

    public void setSlideshowEnabled(boolean slideshowEnabled) {
        this.slideshowEnabled = slideshowEnabled;
    }

    /** The slideshow interval, in seconds (clamped to the min/max range). */
    public int getSlideshowIntervalSec() {
        return slideshowIntervalSec;
    }

    public void setSlideshowIntervalSec(int seconds) {
        this.slideshowIntervalSec = clampSlideshowInterval(seconds);
    }

    /**
     * The slideshow source folder: a directory path, or the empty string for the
     * wallpapers bundled with the shell. Never null.
     */
    public String getSlideshowFolder() {
        return slideshowFolder;
    }

    public void setSlideshowFolder(String folder) {
        this.slideshowFolder = normalizeFolder(folder);
    }

    /** Whether Do Not Disturb is switched on (ignoring any deadline). */
    public boolean isDoNotDisturbEnabled() {
        return dndEnabled;
    }

    public void setDoNotDisturbEnabled(boolean dndEnabled) {
        this.dndEnabled = dndEnabled;
        if (!dndEnabled) {
            this.dndUntil = DEF_DND_UNTIL;
        }
    }

    /** The absolute epoch millis a timed DND ends, or 0 when indefinite. */
    public long getDoNotDisturbUntil() {
        return dndUntil;
    }

    public void setDoNotDisturbUntil(long dndUntil) {
        this.dndUntil = Math.max(0L, dndUntil);
    }

    /** The number of 2D-desktop workspaces (clamped to the min/max range). */
    public int getWorkspaceCount() {
        return workspaceCount;
    }

    public void setWorkspaceCount(int workspaceCount) {
        this.workspaceCount = clampWorkspaceCount(workspaceCount);
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

    private static int clampSlideshowInterval(int v) {
        return Math.max(MIN_SLIDESHOW_INTERVAL_SEC,
                Math.min(MAX_SLIDESHOW_INTERVAL_SEC, v));
    }

    private static int clampWorkspaceCount(int v) {
        return Math.max(MIN_WORKSPACE_COUNT, Math.min(MAX_WORKSPACE_COUNT, v));
    }

    /** Trims a folder path; null falls back to the empty (bundled) default. */
    private static String normalizeFolder(String s) {
        return (s == null) ? DEF_SLIDESHOW_FOLDER : s.trim();
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

    /** Trims/upper-cases a region token; null or blank falls back to {@code AUTO}. */
    private static String normalizeRegion(String s) {
        if (s == null) {
            return DEF_HOLIDAY_REGION;
        }
        String t = s.trim();
        return t.isEmpty() ? DEF_HOLIDAY_REGION : t.toUpperCase(java.util.Locale.ROOT);
    }
}
