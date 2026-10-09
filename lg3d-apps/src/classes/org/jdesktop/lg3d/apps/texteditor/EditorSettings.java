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
package org.jdesktop.lg3d.apps.texteditor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * The Advanced Text Editor's user preferences: font, indent, view toggles,
 * theme and the recent-files list. The class is a plain mutable value object
 * with pure {@link #toMap()} / {@link #fromMap(Map)} serialisation &mdash;
 * everything a test needs without touching the platform preference store
 * &mdash; plus two thin wrappers, {@link #loadUser()} and {@link #saveUser()},
 * for the real desktop persistence under the {@code java.util.prefs} node
 * {@value #PREFS_NODE}.
 *
 * <p>Every setter clamps into a safe range (a persisted garbage value can
 * never produce a 4-pixel font or a negative tab width), and unknown keys in
 * a persisted map are ignored so settings written by a newer build degrade
 * gracefully.</p>
 */
public final class EditorSettings {

    /** The java.util.prefs node under the user root. */
    static final String PREFS_NODE = "org/jdesktop/lg3d/apps/texteditor";

    /** Most recent files remembered across sessions. */
    public static final int MAX_RECENT = 12;

    public static final int MIN_FONT_SIZE = 8;
    public static final int MAX_FONT_SIZE = 48;
    public static final int DEFAULT_FONT_SIZE = 14;

    private String fontFamily = "Monospaced";
    private int fontSize = DEFAULT_FONT_SIZE;
    private int tabSize = 4;
    private boolean hardTabs = false;
    private boolean wordWrap = true;
    private boolean lineNumbers = true;
    private boolean autoIndent = true;
    private boolean highlight = true;
    private String themeName = EditorTheme.LIGHT.getName();
    private final List<String> recentFiles = new ArrayList<>();

    /** A fresh instance carrying the built-in defaults. */
    public static EditorSettings defaults() {
        return new EditorSettings();
    }

    /** Loads the persisted user settings; defaults when nothing is stored. */
    public static EditorSettings loadUser() {
        try {
            Preferences prefs =
                    Preferences.userRoot().node(PREFS_NODE);
            Map<String, String> map = new LinkedHashMap<>();
            for (String key : prefs.keys()) {
                map.put(key, prefs.get(key, null));
            }
            EditorSettings settings = fromMap(map);
            // The recent list is stored as one '|'-joined entry so a single
            // clear removes it completely. pushRecent prepends, so the
            // stored (newest-first) order is restored by re-pushing the
            // entries oldest-first.
            String recent = prefs.get("recentFiles", "");
            String[] paths = recent.split("\\|");
            for (int i = paths.length - 1; i >= 0; i--) {
                if (!paths[i].isBlank()) {
                    settings.pushRecent(paths[i]);
                }
            }
            return settings;
        } catch (RuntimeException | java.util.prefs.BackingStoreException bse) {
            return defaults();
        }
    }

    /** Persists these settings to the user preference store. */
    public void saveUser() {
        Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
        Map<String, String> map = toMap();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            prefs.put(entry.getKey(), entry.getValue());
        }
        prefs.put("recentFiles", String.join("|", recentFiles));
    }

    /** Pure serialisation of everything except the recent-files list. */
    public Map<String, String> toMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("fontFamily", fontFamily);
        map.put("fontSize", Integer.toString(fontSize));
        map.put("tabSize", Integer.toString(tabSize));
        map.put("hardTabs", Boolean.toString(hardTabs));
        map.put("wordWrap", Boolean.toString(wordWrap));
        map.put("lineNumbers", Boolean.toString(lineNumbers));
        map.put("autoIndent", Boolean.toString(autoIndent));
        map.put("highlight", Boolean.toString(highlight));
        map.put("theme", themeName);
        return map;
    }

    /** Rebuilds settings from {@link #toMap()} output, clamping bad values. */
    public static EditorSettings fromMap(Map<String, String> map) {
        EditorSettings s = new EditorSettings();
        if (map == null) {
            return s;
        }
        s.setFontFamily(map.get("fontFamily"));
        s.setFontSize(parseInt(map.get("fontSize"), DEFAULT_FONT_SIZE));
        s.setTabSize(parseInt(map.get("tabSize"), 4));
        s.setHardTabs(Boolean.parseBoolean(map.get("hardTabs")));
        s.setWordWrap(boolOr(map.get("wordWrap"), true));
        s.setLineNumbers(boolOr(map.get("lineNumbers"), true));
        s.setAutoIndent(boolOr(map.get("autoIndent"), true));
        s.setHighlight(boolOr(map.get("highlight"), true));
        s.setThemeName(map.get("theme"));
        return s;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException rte) {
            return fallback;
        }
    }

    private static boolean boolOr(String value, boolean fallback) {
        return (value == null) ? fallback : Boolean.parseBoolean(value);
    }

    /**
     * Moves {@code path} to the front of the recent list, dropping a previous
     * occurrence and the oldest entry past {@link #MAX_RECENT}.
     */
    public void pushRecent(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        recentFiles.remove(path);
        recentFiles.add(0, path);
        while (recentFiles.size() > MAX_RECENT) {
            recentFiles.remove(recentFiles.size() - 1);
        }
    }

    /** Forgets every recent file. */
    public void clearRecent() {
        recentFiles.clear();
    }

    /** The recent files, most recent first (unmodifiable view). */
    public List<String> getRecentFiles() {
        return Collections.unmodifiableList(recentFiles);
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String family) {
        this.fontFamily = (family == null || family.isBlank())
                ? "Monospaced" : family.trim();
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int size) {
        this.fontSize = Math.max(MIN_FONT_SIZE,
                Math.min(size, MAX_FONT_SIZE));
    }

    public int getTabSize() {
        return tabSize;
    }

    public void setTabSize(int size) {
        this.tabSize = Math.max(1, Math.min(size, 16));
    }

    public boolean isHardTabs() {
        return hardTabs;
    }

    public void setHardTabs(boolean hardTabs) {
        this.hardTabs = hardTabs;
    }

    public boolean isWordWrap() {
        return wordWrap;
    }

    public void setWordWrap(boolean wordWrap) {
        this.wordWrap = wordWrap;
    }

    public boolean isLineNumbers() {
        return lineNumbers;
    }

    public void setLineNumbers(boolean lineNumbers) {
        this.lineNumbers = lineNumbers;
    }

    public boolean isAutoIndent() {
        return autoIndent;
    }

    public void setAutoIndent(boolean autoIndent) {
        this.autoIndent = autoIndent;
    }

    public boolean isHighlight() {
        return highlight;
    }

    public void setHighlight(boolean highlight) {
        this.highlight = highlight;
    }

    public String getThemeName() {
        return themeName;
    }

    public void setThemeName(String name) {
        if (name == null || name.isBlank()) {
            this.themeName = EditorTheme.LIGHT.getName();
            return;
        }
        // Unknown names fall back to Light (EditorTheme.byName's contract).
        this.themeName = EditorTheme.byName(name).getName();
    }

    /** The selected theme object; never null. */
    public EditorTheme getTheme() {
        return EditorTheme.byName(themeName);
    }

    /** The editing font described by these settings. */
    public java.awt.Font getFont() {
        return new java.awt.Font(fontFamily, java.awt.Font.PLAIN, fontSize);
    }
}
