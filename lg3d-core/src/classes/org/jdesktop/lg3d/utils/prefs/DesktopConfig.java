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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.logging.Logger;
import org.jdesktop.lg3d.utils.schedule.ScheduleEntry;
import org.jdesktop.lg3d.utils.shape.ShaderEffects;

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
    /** Per-item taskbar visibility: {@code taskbar.show.<ITEM>} booleans. */
    private static final String KEY_TASKBAR_SHOW_PREFIX = "taskbar.show.";
    private static final String KEY_TASKBAR_LABELS = "taskbar.labels";
    private static final String KEY_INDICATOR_SHOW_PREFIX = "indicator.show.";
    private static final String KEY_INDICATORS_SYSTEM_TRAY = "indicators.systemTray";
    private static final String KEY_FONT_NAME = "swing.fontName";
    private static final String KEY_FONT_SIZE = "swing.fontSize";
    private static final String KEY_HOLIDAY_REGION = "calendar.holidayRegion";
    private static final String KEY_SLIDESHOW_ENABLED = "wallpaper.slideshowEnabled";
    private static final String KEY_SLIDESHOW_INTERVAL = "wallpaper.slideshowIntervalSec";
    private static final String KEY_SLIDESHOW_FOLDER = "wallpaper.slideshowFolder";
    private static final String KEY_WALLPAPER_PREFIX = "wallaper.workspace.";
    private static final String KEY_DND_ENABLED = "notifications.dndEnabled";
    private static final String KEY_DND_UNTIL = "notifications.dndUntil";
    private static final String KEY_WORKSPACE_COUNT = "workspace.count";
    private static final String KEY_FROSTED_GLASS = "window.frostedGlass";
    private static final String KEY_ROUNDED_CORNERS = "window.roundedCorners";
    private static final String KEY_METAL_THEME = "metal.theme";
    private static final String KEY_METAL_CUSTOM_THEMES = "metal.customThemes";
    private static final String KEY_THEME_ACCENT = "theme.accent";
    private static final String KEY_ICON_PACK = "icon.pack";
    private static final String KEY_ICON_PACK_DIR = "icon.packDir";
    private static final String KEY_CORNER_LOGO = "desktop.cornerLogo";
    private static final String KEY_SCHEDULE_WALLPAPER_ENABLED = "schedule.wallpaperEnabled";
    private static final String KEY_SCHEDULE_LIGHTING_ENABLED = "schedule.lightingEnabled";
    /** Wallpaper-schedule entries: a count plus per-index hour/minute/file. */
    private static final String KEY_WP_COUNT = "schedule.wp.count";
    private static final String KEY_WP_PREFIX = "schedule.wp.";
    /** The lighting schedule's own dawn/dusk times (independent of wallpaper). */
    private static final String KEY_LIGHTING_DAWN_HOUR = "schedule.lightingDawnHour";
    private static final String KEY_LIGHTING_DAWN_MINUTE = "schedule.lightingDawnMinute";
    private static final String KEY_LIGHTING_DUSK_HOUR = "schedule.lightingDuskHour";
    private static final String KEY_LIGHTING_DUSK_MINUTE = "schedule.lightingDuskMinute";
    private static final String KEY_RAMP_MINUTES = "schedule.rampMinutes";
    /** Legacy two-slot keys, read only to migrate into the entry list. */
    private static final String KEY_LEGACY_DAYLIGHT_HOUR = "schedule.daylightHour";
    private static final String KEY_LEGACY_DAYLIGHT_MINUTE = "schedule.daylightMinute";
    private static final String KEY_LEGACY_NIGHTLIGHT_HOUR = "schedule.nightlightHour";
    private static final String KEY_LEGACY_NIGHTLIGHT_MINUTE = "schedule.nightlightMinute";
    private static final String KEY_LEGACY_DAYLIGHT_WALLPAPER = "schedule.daylightWallpaper";
    private static final String KEY_LEGACY_NIGHTLIGHT_WALLPAPER = "schedule.nightlightWallpaper";
    private static final String KEY_SHORTCUTS_CUSTOM = "shortcuts.custom";

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
    /**
     * Default rounded-corner state for the GPU frosted glass: on, matching the
     * look the frosted window body has always had. Only meaningful when the
     * frosted glass is in use; the fixed-function {@code GlassyPanel} is always
     * square.
     */
    public static final boolean DEFAULT_ROUNDED_CORNERS = true;
    private static final boolean DEF_ROUNDED_CORNERS = DEFAULT_ROUNDED_CORNERS;
    /**
     * Default Metal theme name for the 2D desktop: the empty string means "no
     * Metal theme chosen", so the shell keeps its native platform look until
     * the user picks one in the control center's theme manager.
     */
    public static final String DEFAULT_METAL_THEME = "";
    private static final String DEF_METAL_THEME = DEFAULT_METAL_THEME;
    /** Default set of user-created Metal themes: none (empty encoded list). */
    public static final String DEFAULT_METAL_CUSTOM_THEMES = "";
    private static final String DEF_METAL_CUSTOM_THEMES = DEFAULT_METAL_CUSTOM_THEMES;
    /**
     * Default theme accent colour: the empty string means "no explicit accent",
     * so the accent derives from the active Metal theme's {@code primary2} shade.
     */
    public static final String DEFAULT_THEME_ACCENT = "";
    private static final String DEF_THEME_ACCENT = DEFAULT_THEME_ACCENT;
    /**
     * Default icon pack id: the empty string means "no pack", so application
     * icons keep their procedurally generated IconManager art.
     */
    public static final String DEFAULT_ICON_PACK = "";
    private static final String DEF_ICON_PACK = DEFAULT_ICON_PACK;
    /**
     * Default imported icon-pack location: empty means no folder/zip has been
     * imported (only the bundled packs and the default are selectable).
     */
    public static final String DEFAULT_ICON_PACK_DIR = "";
    private static final String DEF_ICON_PACK_DIR = DEFAULT_ICON_PACK_DIR;
    /**
     * Default 3D-desktop corner-logo model id: {@code "java"} keeps the classic
     * Java/Sun logo that rotates with the mouse; {@code "mascot"} shows the
     * Looking-Glass mascot (the same icon the 2D splash and the About window
     * reflect). Read by
     * {@link org.jdesktop.lg3d.scenemanager.utils.background.CornerLogo}.
     */
    public static final String DEFAULT_CORNER_LOGO = "java";
    private static final String DEF_CORNER_LOGO = DEFAULT_CORNER_LOGO;
    /**
     * Whether the schedule swaps the wallpaper / re-lights the scene by default.
     * The two are independent opt-ins: either, both or neither can be enabled.
     */
    public static final boolean DEFAULT_SCHEDULE_WALLPAPER_ENABLED = false;
    private static final boolean DEF_SCHEDULE_WALLPAPER_ENABLED = DEFAULT_SCHEDULE_WALLPAPER_ENABLED;
    public static final boolean DEFAULT_SCHEDULE_LIGHTING_ENABLED = false;
    private static final boolean DEF_SCHEDULE_LIGHTING_ENABLED = DEFAULT_SCHEDULE_LIGHTING_ENABLED;
    /** Default lighting dawn transition time: 7:00 AM. */
    public static final int DEFAULT_LIGHTING_DAWN_HOUR = 7;
    public static final int DEFAULT_LIGHTING_DAWN_MINUTE = 0;
    private static final int DEF_LIGHTING_DAWN_HOUR = DEFAULT_LIGHTING_DAWN_HOUR;
    private static final int DEF_LIGHTING_DAWN_MINUTE = DEFAULT_LIGHTING_DAWN_MINUTE;
    /** Default lighting dusk transition time: 8:00 PM. */
    public static final int DEFAULT_LIGHTING_DUSK_HOUR = 20;
    public static final int DEFAULT_LIGHTING_DUSK_MINUTE = 0;
    private static final int DEF_LIGHTING_DUSK_HOUR = DEFAULT_LIGHTING_DUSK_HOUR;
    private static final int DEF_LIGHTING_DUSK_MINUTE = DEFAULT_LIGHTING_DUSK_MINUTE;
    /**
     * Default wallpaper schedule: two entries (morning / evening) mirroring the
     * historical daylight/nightlight pair. Users add or remove entries freely.
     */
    public static final List<ScheduleEntry> DEFAULT_WALLPAPER_SCHEDULE = List.of(
            new ScheduleEntry(DEFAULT_LIGHTING_DAWN_HOUR, DEFAULT_LIGHTING_DAWN_MINUTE,
                    ScheduleEntry.DEFAULT_WALLPAPER),
            new ScheduleEntry(DEFAULT_LIGHTING_DUSK_HOUR, DEFAULT_LIGHTING_DUSK_MINUTE,
                    ScheduleEntry.DEFAULT_WALLPAPER));
    /** Default daylight-to-night light transition width, in minutes. */
    public static final int DEFAULT_RAMP_MINUTES = 30;
    private static final int DEF_RAMP_MINUTES = DEFAULT_RAMP_MINUTES;
    /** Minimum/maximum {@code rampMinutes} (0 is a hard step). */
    public static final int MIN_RAMP_MINUTES = 0;
    public static final int MAX_RAMP_MINUTES = 180;

    /**
     * The empty default for the custom keyboard-shortcut overrides: no
     * overrides, so every binding keeps its built-in default (the
     * {@code ShortcutMap.defaultBindings()} value).
     */
    public static final String DEFAULT_SHORTCUTS_CUSTOM = "";
    private static final String DEF_SHORTCUTS_CUSTOM = DEFAULT_SHORTCUTS_CUSTOM;

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

    /**
     * The fixed pieces the 2D taskbar can show or hide, individually, from the
     * control center. Every item is shown by default; hiding one removes it
     * (and the space it occupied) from the bar.
     */
    public enum TaskbarItem {
        START, QUICK_LAUNCH, DOCUMENTS, DOWNLOADS, WORKSPACES,
        INDICATORS, NOTIFICATIONS, CLOCK, EXIT
    }

    /**
     * The system indicators that can be mirrored into the host
     * {@code java.awt.SystemTray}, toggled individually from the control
     * center. Every indicator is shown by default; hiding one removes its tray
     * icon. Only takes effect while {@link #isIndicatorsSystemTray()} is on.
     */
    public enum Indicator {
        VOLUME, NETWORK
    }

    /** How the taskbar's fixed buttons present themselves. */
    public enum Labels {
        /** Icon only; the descriptive text lives in the tooltip. */
        ICONS_ONLY,
        /** Icon plus a text label beside it. */
        ICONS_AND_TEXT
    }

    /**
     * Default taskbar button label style: icon-only, so the tray reads as a row
     * of glyphs and the descriptive text is a hover tooltip.
     */
    public static final Labels DEFAULT_TASKBAR_LABELS = Labels.ICONS_ONLY;
    private static final Labels DEF_TASKBAR_LABELS = DEFAULT_TASKBAR_LABELS;

    /**
     * By default the volume/network indicators live only on the lg3d taskbar;
     * mirroring them into the host {@code java.awt.SystemTray} is opt-in.
     */
    private static final boolean DEF_INDICATORS_SYSTEM_TRAY = false;

    private static volatile DesktopConfig instance;

    private final Preferences prefs;

    private float barScale = DEF_BAR_SCALE;
    private Position position = Position.BOTTOM;
    private float iconScale = DEF_ICON_SCALE;
    private boolean autoHide = DEF_AUTO_HIDE;
    private Labels taskbarLabels = DEF_TASKBAR_LABELS;
    private final EnumSet<TaskbarItem> taskbarShown =
            EnumSet.allOf(TaskbarItem.class);
    private final EnumSet<Indicator> indicatorsShown =
            EnumSet.allOf(Indicator.class);
    private boolean indicatorsSystemTray = DEF_INDICATORS_SYSTEM_TRAY;
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
    private boolean roundedCorners = DEF_ROUNDED_CORNERS;
    private String metalTheme = DEF_METAL_THEME;
    private String metalCustomThemes = DEF_METAL_CUSTOM_THEMES;
    private String accentColor = DEF_THEME_ACCENT;
    private String iconPack = DEF_ICON_PACK;
    private String iconPackDir = DEF_ICON_PACK_DIR;
    private String cornerLogo = DEF_CORNER_LOGO;
    private boolean scheduleWallpaperEnabled = DEF_SCHEDULE_WALLPAPER_ENABLED;
    private boolean scheduleLightingEnabled = DEF_SCHEDULE_LIGHTING_ENABLED;
    private List<ScheduleEntry> wallpaperSchedule =
            new ArrayList<>(DEFAULT_WALLPAPER_SCHEDULE);
    private int lightingDawnHour = DEF_LIGHTING_DAWN_HOUR;
    private int lightingDawnMinute = DEF_LIGHTING_DAWN_MINUTE;
    private int lightingDuskHour = DEF_LIGHTING_DUSK_HOUR;
    private int lightingDuskMinute = DEF_LIGHTING_DUSK_MINUTE;
    private int rampMinutes = DEF_RAMP_MINUTES;
    private String shortcutsCustom = DEF_SHORTCUTS_CUSTOM;

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
        taskbarLabels = parseLabels(
                prefs.get(KEY_TASKBAR_LABELS, DEF_TASKBAR_LABELS.name()));
        taskbarShown.clear();
        for (TaskbarItem item : TaskbarItem.values()) {
            if (prefs.getBoolean(KEY_TASKBAR_SHOW_PREFIX + item.name(), true)) {
                taskbarShown.add(item);
            }
        }
        indicatorsShown.clear();
        for (Indicator ind : Indicator.values()) {
            if (prefs.getBoolean(KEY_INDICATOR_SHOW_PREFIX + ind.name(), true)) {
                indicatorsShown.add(ind);
            }
        }
        indicatorsSystemTray = prefs.getBoolean(
                KEY_INDICATORS_SYSTEM_TRAY, DEF_INDICATORS_SYSTEM_TRAY);
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
        // The -Pshaders dev flag pre-selects the frosted style in memory only
        // (never persisted): the preference stays the single live source of
        // truth that Apply writes and every open window and the bar listen to.
        if (ShaderEffects.isEnabled()) {
            frostedGlass = true;
        }
        roundedCorners = prefs.getBoolean(KEY_ROUNDED_CORNERS, DEF_ROUNDED_CORNERS);
        metalTheme = normalizeThemeName(prefs.get(KEY_METAL_THEME, DEF_METAL_THEME));
        metalCustomThemes = normalizeCustomThemes(
                prefs.get(KEY_METAL_CUSTOM_THEMES, DEF_METAL_CUSTOM_THEMES));
        accentColor = normalizeAccentColor(prefs.get(KEY_THEME_ACCENT, DEF_THEME_ACCENT));
        iconPack = normalizeIconPack(prefs.get(KEY_ICON_PACK, DEF_ICON_PACK));
        iconPackDir = normalizeIconPackDir(prefs.get(KEY_ICON_PACK_DIR, DEF_ICON_PACK_DIR));
        cornerLogo = normalizeCornerLogo(prefs.get(KEY_CORNER_LOGO, DEF_CORNER_LOGO));
        scheduleWallpaperEnabled = prefs.getBoolean(
                KEY_SCHEDULE_WALLPAPER_ENABLED, DEF_SCHEDULE_WALLPAPER_ENABLED);
        scheduleLightingEnabled = prefs.getBoolean(
                KEY_SCHEDULE_LIGHTING_ENABLED, DEF_SCHEDULE_LIGHTING_ENABLED);
        wallpaperSchedule = loadWallpaperSchedule();
        lightingDawnHour = clampHour(
                prefs.getInt(KEY_LIGHTING_DAWN_HOUR, DEF_LIGHTING_DAWN_HOUR));
        lightingDawnMinute = clampMinute(
                prefs.getInt(KEY_LIGHTING_DAWN_MINUTE, DEF_LIGHTING_DAWN_MINUTE));
        lightingDuskHour = clampHour(
                prefs.getInt(KEY_LIGHTING_DUSK_HOUR, DEF_LIGHTING_DUSK_HOUR));
        lightingDuskMinute = clampMinute(
                prefs.getInt(KEY_LIGHTING_DUSK_MINUTE, DEF_LIGHTING_DUSK_MINUTE));
        rampMinutes = clampRampMinutes(prefs.getInt(KEY_RAMP_MINUTES, DEF_RAMP_MINUTES));
        shortcutsCustom = normalizeShortcuts(prefs.get(KEY_SHORTCUTS_CUSTOM, DEF_SHORTCUTS_CUSTOM));
    }

    /**
     * Reads the wallpaper-schedule entry list. When the new {@code count} key is
     * absent (first run after an upgrade) the legacy two-slot daylight/nightlight
     * scalars are migrated into entries so saved preferences are not lost; with
     * no legacy values either, the defaults are used.
     */
    private List<ScheduleEntry> loadWallpaperSchedule() {
        int count = prefs.getInt(KEY_WP_COUNT, -1);
        if (count < 0) {
            return migrateLegacyWallpaperSchedule();
        }
        List<ScheduleEntry> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int hour = clampHour(prefs.getInt(KEY_WP_PREFIX + i + ".hour", 0));
            int minute = clampMinute(prefs.getInt(KEY_WP_PREFIX + i + ".minute", 0));
            String file = normalizeWallpaper(prefs.get(KEY_WP_PREFIX + i + ".file",
                    ScheduleEntry.DEFAULT_WALLPAPER));
            out.add(new ScheduleEntry(hour, minute, file));
        }
        if (out.isEmpty()) {
            out.addAll(DEFAULT_WALLPAPER_SCHEDULE);
        }
        return out;
    }

    /** Seeds the entry list from the legacy daylight/nightlight scalars. */
    private List<ScheduleEntry> migrateLegacyWallpaperSchedule() {
        boolean legacy = prefs.get(KEY_LEGACY_DAYLIGHT_WALLPAPER, null) != null
                || prefs.get(KEY_LEGACY_NIGHTLIGHT_WALLPAPER, null) != null
                || prefs.get(KEY_LEGACY_DAYLIGHT_HOUR, null) != null
                || prefs.get(KEY_LEGACY_NIGHTLIGHT_HOUR, null) != null;
        if (!legacy) {
            return new ArrayList<>(DEFAULT_WALLPAPER_SCHEDULE);
        }
        List<ScheduleEntry> out = new ArrayList<>();
        out.add(new ScheduleEntry(
                clampHour(prefs.getInt(KEY_LEGACY_DAYLIGHT_HOUR, DEF_LIGHTING_DAWN_HOUR)),
                clampMinute(prefs.getInt(KEY_LEGACY_DAYLIGHT_MINUTE, DEF_LIGHTING_DAWN_MINUTE)),
                normalizeWallpaper(prefs.get(KEY_LEGACY_DAYLIGHT_WALLPAPER,
                        ScheduleEntry.DEFAULT_WALLPAPER))));
        out.add(new ScheduleEntry(
                clampHour(prefs.getInt(KEY_LEGACY_NIGHTLIGHT_HOUR, DEF_LIGHTING_DUSK_HOUR)),
                clampMinute(prefs.getInt(KEY_LEGACY_NIGHTLIGHT_MINUTE, DEF_LIGHTING_DUSK_MINUTE)),
                normalizeWallpaper(prefs.get(KEY_LEGACY_NIGHTLIGHT_WALLPAPER,
                        ScheduleEntry.DEFAULT_WALLPAPER))));
        return out;
    }

    /** Writes all in-memory values to the backing preferences node. */
    public void save() {
        prefs.putFloat(KEY_BAR_SCALE, barScale);
        prefs.put(KEY_POSITION, position.name());
        prefs.putFloat(KEY_ICON_SCALE, iconScale);
        prefs.putBoolean(KEY_AUTO_HIDE, autoHide);
        prefs.put(KEY_TASKBAR_LABELS, taskbarLabels.name());
        for (TaskbarItem item : TaskbarItem.values()) {
            prefs.putBoolean(KEY_TASKBAR_SHOW_PREFIX + item.name(),
                    taskbarShown.contains(item));
        }
        for (Indicator ind : Indicator.values()) {
            prefs.putBoolean(KEY_INDICATOR_SHOW_PREFIX + ind.name(),
                    indicatorsShown.contains(ind));
        }
        prefs.putBoolean(KEY_INDICATORS_SYSTEM_TRAY, indicatorsSystemTray);
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
        prefs.putBoolean(KEY_ROUNDED_CORNERS, roundedCorners);
        prefs.put(KEY_METAL_THEME, metalTheme);
        prefs.put(KEY_METAL_CUSTOM_THEMES, metalCustomThemes);
        prefs.put(KEY_THEME_ACCENT, accentColor);
        prefs.put(KEY_ICON_PACK, iconPack);
        prefs.put(KEY_ICON_PACK_DIR, iconPackDir);
        prefs.put(KEY_CORNER_LOGO, cornerLogo);
        prefs.putBoolean(KEY_SCHEDULE_WALLPAPER_ENABLED, scheduleWallpaperEnabled);
        prefs.putBoolean(KEY_SCHEDULE_LIGHTING_ENABLED, scheduleLightingEnabled);
        prefs.putInt(KEY_WP_COUNT, wallpaperSchedule.size());
        for (int i = 0; i < wallpaperSchedule.size(); i++) {
            ScheduleEntry e = wallpaperSchedule.get(i);
            prefs.putInt(KEY_WP_PREFIX + i + ".hour", e.hour());
            prefs.putInt(KEY_WP_PREFIX + i + ".minute", e.minute());
            prefs.put(KEY_WP_PREFIX + i + ".file", e.wallpaper());
        }
        prefs.putInt(KEY_LIGHTING_DAWN_HOUR, lightingDawnHour);
        prefs.putInt(KEY_LIGHTING_DAWN_MINUTE, lightingDawnMinute);
        prefs.putInt(KEY_LIGHTING_DUSK_HOUR, lightingDuskHour);
        prefs.putInt(KEY_LIGHTING_DUSK_MINUTE, lightingDuskMinute);
        prefs.putInt(KEY_RAMP_MINUTES, rampMinutes);
        prefs.put(KEY_SHORTCUTS_CUSTOM, shortcutsCustom);
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
        taskbarLabels = DEF_TASKBAR_LABELS;
        taskbarShown.clear();
        taskbarShown.addAll(EnumSet.allOf(TaskbarItem.class));
        indicatorsShown.clear();
        indicatorsShown.addAll(EnumSet.allOf(Indicator.class));
        indicatorsSystemTray = DEF_INDICATORS_SYSTEM_TRAY;
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
        roundedCorners = DEF_ROUNDED_CORNERS;
        metalTheme = DEF_METAL_THEME;
        metalCustomThemes = DEF_METAL_CUSTOM_THEMES;
        accentColor = DEF_THEME_ACCENT;
        iconPack = DEF_ICON_PACK;
        iconPackDir = DEF_ICON_PACK_DIR;
        cornerLogo = DEF_CORNER_LOGO;
        scheduleWallpaperEnabled = DEF_SCHEDULE_WALLPAPER_ENABLED;
        scheduleLightingEnabled = DEF_SCHEDULE_LIGHTING_ENABLED;
        wallpaperSchedule = new ArrayList<>(DEFAULT_WALLPAPER_SCHEDULE);
        lightingDawnHour = DEF_LIGHTING_DAWN_HOUR;
        lightingDawnMinute = DEF_LIGHTING_DAWN_MINUTE;
        lightingDuskHour = DEF_LIGHTING_DUSK_HOUR;
        lightingDuskMinute = DEF_LIGHTING_DUSK_MINUTE;
        rampMinutes = DEF_RAMP_MINUTES;
        shortcutsCustom = DEF_SHORTCUTS_CUSTOM;
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
     * {@code GlassyPanel}. Decorations and the taskbar shelf listen for
     * {@code DesktopConfigChangeEvent} and flip their glass {@code Switch} in
     * place, so a change takes effect immediately on every open window and the
     * bar - no restart needed.
     */
    public boolean isFrostedGlass() {
        return frostedGlass;
    }

    public void setFrostedGlass(boolean frostedGlass) {
        this.frostedGlass = frostedGlass;
    }

    /**
     * Whether the GPU frosted glass of <em>window decorations</em> renders with
     * anti-aliased rounded corners ({@code true}) or square corners
     * ({@code false}). The frosted taskbar shelf is deliberately excluded - it
     * always keeps square corners - and the fixed-function {@code GlassyPanel}
     * is always square. On {@code DesktopConfigChangeEvent} every open
     * decoration rewrites the frosted corner radius live, so a change takes
     * effect immediately on existing windows.
     */
    public boolean isRoundedCorners() {
        return roundedCorners;
    }

    public void setRoundedCorners(boolean roundedCorners) {
        this.roundedCorners = roundedCorners;
    }

    /**
     * The name of the Metal theme the 2D desktop is skinned with, or the empty
     * string when no Metal theme has been chosen (the native platform look is
     * kept). Read at start-up by
     * {@link org.jdesktop.lg3d.displayserver.desktop2d.MetalThemeManager#applyStored()}.
     */
    public String getMetalTheme() {
        return metalTheme;
    }

    public void setMetalTheme(String metalTheme) {
        this.metalTheme = normalizeThemeName(metalTheme);
    }

    /**
     * The encoded list of user-created Metal themes (see
     * {@link org.jdesktop.lg3d.displayserver.desktop2d.MetalThemeSpec#encodeAll}).
     * Never null; empty means no custom themes.
     */
    public String getMetalCustomThemes() {
        return metalCustomThemes;
    }

    public void setMetalCustomThemes(String metalCustomThemes) {
        this.metalCustomThemes = normalizeCustomThemes(metalCustomThemes);
    }

    /**
     * The explicit theme accent colour as a {@code #rrggbb} string, or the empty
     * string when none has been chosen (the accent then derives from the active
     * Metal theme). Read by
     * {@link org.jdesktop.lg3d.displayserver.desktop2d.MetalThemeManager#accentColor()}.
     */
    public String getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(String hex) {
        this.accentColor = normalizeAccentColor(hex);
    }

    /**
     * The selected icon-pack id, or the empty string for the default (no pack:
     * the generated IconManager icons are kept). Read by
     * {@link org.jdesktop.lg3d.displayserver.desktop2d.IconPackManager#active()}.
     */
    public String getIconPack() {
        return iconPack;
    }

    public void setIconPack(String id) {
        this.iconPack = normalizeIconPack(id);
    }

    /**
     * The imported icon-pack location: a folder of PNGs or a {@code .zip}, or
     * the empty string when nothing has been imported. Never null.
     */
    public String getIconPackDir() {
        return iconPackDir;
    }

    public void setIconPackDir(String path) {
        this.iconPackDir = normalizeIconPackDir(path);
    }

    /**
     * The id of the 3D-desktop corner-logo model to display: {@code "java"} (the
     * classic Java/Sun logo) or {@code "mascot"} (the Looking-Glass mascot). Only
     * the backgrounds that host the corner logo render it; the value is read when
     * a background is built and re-applied live on
     * {@link org.jdesktop.lg3d.scenemanager.utils.event.DesktopConfigChangeEvent}.
     */
    public String getCornerLogo() {
        return cornerLogo;
    }

    public void setCornerLogo(String id) {
        this.cornerLogo = normalizeCornerLogo(id);
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

    /** How the taskbar's fixed buttons present themselves (icon-only default). */
    public Labels getTaskbarLabels() {
        return taskbarLabels;
    }

    public void setTaskbarLabels(Labels labels) {
        this.taskbarLabels = (labels == null) ? DEF_TASKBAR_LABELS : labels;
    }

    /** Whether {@code item} is shown on the 2D taskbar (all shown by default). */
    public boolean isTaskbarItemShown(TaskbarItem item) {
        return item != null && taskbarShown.contains(item);
    }

    public void setTaskbarItemShown(TaskbarItem item, boolean shown) {
        if (item == null) {
            return;
        }
        if (shown) {
            taskbarShown.add(item);
        } else {
            taskbarShown.remove(item);
        }
    }

    /** Whether {@code indicator} is shown in the tray (all shown by default). */
    public boolean isIndicatorShown(Indicator indicator) {
        return indicator != null && indicatorsShown.contains(indicator);
    }

    public void setIndicatorShown(Indicator indicator, boolean shown) {
        if (indicator == null) {
            return;
        }
        if (shown) {
            indicatorsShown.add(indicator);
        } else {
            indicatorsShown.remove(indicator);
        }
    }

    /**
     * Whether the volume/network indicators are also mirrored into the host
     * {@code java.awt.SystemTray} (off by default). When the host has no system
     * tray the mirror is silently skipped regardless of this flag.
     */
    public boolean isIndicatorsSystemTray() {
        return indicatorsSystemTray;
    }

    public void setIndicatorsSystemTray(boolean enabled) {
        this.indicatorsSystemTray = enabled;
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

    /**
     * The wallpaper URL persisted for a specific workspace (0-indexed).
     * An empty string means the first bundled wallpaper is used as fallback.
     * Never null.
     */
    public String getWorkspaceWallpaper(int workspaceIndex) {
        String key = KEY_WALLPAPER_PREFIX + workspaceIndex;
        return prefs.get(key, "");
    }

    /**
     * Sets the wallpaper URL for a specific workspace (0-indexed).
     * Null is treated as empty (use default fallback).
     */
    public void setWorkspaceWallpaper(int workspaceIndex, String url) {
        String key = KEY_WALLPAPER_PREFIX + workspaceIndex;
        String value = (url == null) ? "" : url;
        prefs.put(key, value);
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

    /**
     * Whether the schedule swaps the wallpaper at the daylight/nightlight times.
     * Independent of {@link #isLightingScheduleEnabled()}.
     */
    public boolean isWallpaperScheduleEnabled() {
        return scheduleWallpaperEnabled;
    }

    public void setWallpaperScheduleEnabled(boolean enabled) {
        this.scheduleWallpaperEnabled = enabled;
    }

    /**
     * Whether the schedule fades the scene lighting (3D) or night veil (2D) at
     * the daylight/nightlight times. Independent of
     * {@link #isWallpaperScheduleEnabled()}.
     */
    public boolean isLightingScheduleEnabled() {
        return scheduleLightingEnabled;
    }

    public void setLightingScheduleEnabled(boolean enabled) {
        this.scheduleLightingEnabled = enabled;
    }

    /** True when either the wallpaper or the lighting schedule is enabled. */
    public boolean isScheduleEnabled() {
        return scheduleWallpaperEnabled || scheduleLightingEnabled;
    }

    /**
     * The wallpaper schedule: an ordered, variable-length list of
     * {@link ScheduleEntry} (time -> wallpaper). Returns a defensive copy sorted
     * by time of day; never null (at least the defaults). Independent of the
     * lighting schedule, which carries its own times and no wallpapers.
     */
    public List<ScheduleEntry> getWallpaperSchedule() {
        List<ScheduleEntry> copy = new ArrayList<>(wallpaperSchedule);
        copy.sort(null);
        return copy;
    }

    /** Replaces the wallpaper schedule with a defensive copy of {@code entries}. */
    public void setWallpaperSchedule(List<ScheduleEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            this.wallpaperSchedule = new ArrayList<>(DEFAULT_WALLPAPER_SCHEDULE);
        } else {
            this.wallpaperSchedule = new ArrayList<>(entries);
        }
    }

    /** The hour (0-23) of the lighting schedule's dawn (day) transition. */
    public int getLightingDawnHour() {
        return lightingDawnHour;
    }

    public void setLightingDawnHour(int hour) {
        this.lightingDawnHour = clampHour(hour);
    }

    /** The minute (0-59) of the lighting schedule's dawn (day) transition. */
    public int getLightingDawnMinute() {
        return lightingDawnMinute;
    }

    public void setLightingDawnMinute(int minute) {
        this.lightingDawnMinute = clampMinute(minute);
    }

    /** The hour (0-23) of the lighting schedule's dusk (night) transition. */
    public int getLightingDuskHour() {
        return lightingDuskHour;
    }

    public void setLightingDuskHour(int hour) {
        this.lightingDuskHour = clampHour(hour);
    }

    /** The minute (0-59) of the lighting schedule's dusk (night) transition. */
    public int getLightingDuskMinute() {
        return lightingDuskMinute;
    }

    public void setLightingDuskMinute(int minute) {
        this.lightingDuskMinute = clampMinute(minute);
    }

    /**
     * The width, in minutes, of each daylight/nightlight transition window:
     * the light (3D) and veil (2D) ramp smoothly across this many minutes
     * centred on each transition time. 0 is a hard step.
     */
    public int getRampMinutes() {
        return rampMinutes;
    }

    public void setRampMinutes(int minutes) {
        this.rampMinutes = clampRampMinutes(minutes);
    }

    /**
     * The encoded custom keyboard-shortcut overrides: a {@code ;}-separated
     * list of {@code action=keyspec} pairs (e.g. {@code run-dialog=alt F2})
     * that replace the matching default bindings. Never null; empty means "all
     * defaults". Read by {@code Desktop2D} when it builds the shortcut table.
     */
    public String getCustomShortcuts() {
        return shortcutsCustom;
    }

    public void setCustomShortcuts(String encoded) {
        this.shortcutsCustom = normalizeShortcuts(encoded);
    }

    /**
     * Parses the encoded custom-shortcut string into an action -> keystroke-spec
     * map, skipping blank/malformed entries (a keystroke spec may contain spaces
     * but never {@code ;} or {@code =}, so the split is unambiguous). Pure so it
     * can be unit-tested headless.
     */
    public static java.util.Map<String, String> parseCustomShortcuts(String encoded) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (encoded == null) {
            return out;
        }
        for (String entry : encoded.split(";")) {
            String pair = entry.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                continue;
            }
            String action = pair.substring(0, eq).trim();
            String spec = pair.substring(eq + 1).trim();
            if (!action.isEmpty() && !spec.isEmpty()) {
                out.put(action, spec);
            }
        }
        return out;
    }

    /**
     * Serializes an action -> keystroke-spec map back into the encoded
     * {@code action=keyspec;...} form, skipping null/blank keys or values. The
     * inverse of {@link #parseCustomShortcuts(String)}.
     */
    public static String serializeCustomShortcuts(java.util.Map<String, String> actionToSpec) {
        if (actionToSpec == null || actionToSpec.isEmpty()) {
            return DEFAULT_SHORTCUTS_CUSTOM;
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : actionToSpec.entrySet()) {
            String action = e.getKey();
            String spec = e.getValue();
            if (action == null || action.isBlank() || spec == null || spec.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(action.trim()).append('=').append(spec.trim());
        }
        return sb.toString();
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

    /** Trims a Metal theme name; null falls back to the empty (native) default. */
    private static String normalizeThemeName(String s) {
        return (s == null) ? DEF_METAL_THEME : s.trim();
    }

    /** Trims the encoded custom-theme list; null falls back to empty. */
    private static String normalizeCustomThemes(String s) {
        return (s == null) ? DEF_METAL_CUSTOM_THEMES : s.trim();
    }

    /**
     * Keeps a well-formed {@code #rrggbb} accent colour (lower-cased); null,
     * blank or malformed input falls back to the empty (derive-from-theme)
     * default.
     */
    private static String normalizeAccentColor(String s) {
        if (s == null) {
            return DEF_THEME_ACCENT;
        }
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return t.matches("#[0-9a-f]{6}") ? t : DEF_THEME_ACCENT;
    }

    /** Trims the icon-pack id; null falls back to empty (the default pack). */
    private static String normalizeIconPack(String s) {
        return (s == null) ? DEF_ICON_PACK : s.trim();
    }

    /** Trims the imported icon-pack path; null falls back to empty (none). */
    private static String normalizeIconPackDir(String s) {
        return (s == null) ? DEF_ICON_PACK_DIR : s.trim();
    }

    /**
     * Normalizes a corner-logo model id to one of the supported values
     * ({@code "java"} or {@code "mascot"}); null, blank or an unknown token all
     * fall back to the default ({@code "java"}).
     */
    private static String normalizeCornerLogo(String s) {
        if (s == null) {
            return DEF_CORNER_LOGO;
        }
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        return t.equals("mascot") ? "mascot" : DEF_CORNER_LOGO;
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

    private static Labels parseLabels(String s) {
        if (s != null) {
            try {
                return Labels.valueOf(s.trim());
            } catch (IllegalArgumentException e) {
                // fall through to the default
            }
        }
        return DEF_TASKBAR_LABELS;
    }

    /** Trims/upper-cases a region token; null or blank falls back to {@code AUTO}. */
    private static String normalizeRegion(String s) {
        if (s == null) {
            return DEF_HOLIDAY_REGION;
        }
        String t = s.trim();
        return t.isEmpty() ? DEF_HOLIDAY_REGION : t.toUpperCase(java.util.Locale.ROOT);
    }

    /** Clamps hour to valid range 0-23. */
    private static int clampHour(int v) {
        return Math.max(0, Math.min(23, v));
    }

    /** Clamps minute to valid range 0-59. */
    private static int clampMinute(int v) {
        return Math.max(0, Math.min(59, v));
    }

    /** Clamps the transition width to the valid {@code rampMinutes} range. */
    private static int clampRampMinutes(int v) {
        return Math.max(MIN_RAMP_MINUTES, Math.min(MAX_RAMP_MINUTES, v));
    }

    /** Trims wallpaper filename; null falls back to empty (default). */
    private static String normalizeWallpaper(String s) {
        return (s == null) ? ScheduleEntry.DEFAULT_WALLPAPER : s.trim();
    }

    /** Trims the encoded custom-shortcut list; null falls back to empty. */
    private static String normalizeShortcuts(String s) {
        return (s == null) ? DEF_SHORTCUTS_CUSTOM : s.trim();
    }
}
