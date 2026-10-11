/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d.screensaver;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import org.jdesktop.lg3d.utils.prefs.LgPreferencesHelper;

/**
 * The persisted screen-saver settings, one in-memory model shared by the
 * desktop's {@link ScreensaverService} and the Control Center's Screen Saver
 * panel (both live in the 2D desktop's JVM, so the same singleton instance
 * serves reader and writer - exactly how {@code DesktopConfig} works for the
 * taskbar).
 *
 * <p>State is persisted through {@link Preferences} via
 * {@link LgPreferencesHelper} - the same user-pref tree {@code DesktopConfig}
 * uses, in this class's own package node, so the screensaver keys live apart
 * from the taskbar keys - in
 * {@link #save()}: setters only mutate memory, callers batch and save.
 * Per-saver option values live under {@code opt.<saverId>.<optionKey>} so a
 * saver's settings cannot collide with another's, stale keys of an uninstalled
 * saver are simply ignored on read, and unknown or hand-edited garbage is
 * filtered by {@link ScreenSaverOption#coerce} at use time - never here.</p>
 *
 * <p>This class performs no Swing threading: read on the EDT via the service,
 * write from the panel followed by {@code save()} plus
 * {@link org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D#applyScreensaverConfig()}.</p>
 */
public final class ScreensaverSettings {

    // Preference keys (inside the "screensaver" node).
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TIMEOUT = "timeoutSec";
    private static final String KEY_SAVER = "saverId";
    private static final String KEY_CYCLE = "cycleAll";
    private static final String KEY_SUPPRESS_FULLSCREEN = "suppressFullscreen";
    /** Prefix of the per-saver option keys: opt.&lt;saverId&gt;.&lt;key&gt;. */
    private static final String KEY_OPT_PREFIX = "opt.";
    private static final char OPT_SEPARATOR = '.';

    /** Smallest idle timeout the UI may store (seconds). */
    public static final int MIN_TIMEOUT_SEC = 15;
    /** Largest idle timeout the UI may store (seconds, = 4 hours). */
    public static final int MAX_TIMEOUT_SEC = 14400;
    /** Default idle timeout (seconds, = 5 minutes). */
    public static final int DEFAULT_TIMEOUT_SEC = 300;

    private final Preferences prefs;

    private boolean enabled;
    private int timeoutSec;
    private String saverId;
    private boolean cycleAll;
    private boolean suppressFullscreen;
    /** saverId -> (optionKey -> raw stored value); mutable while editing. */
    private final Map<String, Map<String, String>> options = new HashMap<>();

    /** The shared instance, loaded from the stored preferences once. */
    private static ScreensaverSettings instance;

    private ScreensaverSettings(Preferences prefs) {
        this.prefs = prefs;
        load();
    }

    /** The shared settings instance (loads the store on first access). */
    public static synchronized ScreensaverSettings get() {
        if (instance == null) {
            instance = new ScreensaverSettings(
                    LgPreferencesHelper.userNodeForPackage(
                            ScreensaverSettings.class));
        }
        return instance;
    }

    /**
     * Re-reads every value from the store, discarding unsaved edits. The
     * service calls it from {@code apply()} so a save from the panel takes
     * live effect without the two having to share more than the store.
     */
    public void load() {
        enabled = prefs.getBoolean(KEY_ENABLED, false);
        timeoutSec = clampTimeout(
                prefs.getInt(KEY_TIMEOUT, DEFAULT_TIMEOUT_SEC));
        saverId = prefs.get(KEY_SAVER, "");
        cycleAll = prefs.getBoolean(KEY_CYCLE, false);
        suppressFullscreen = prefs.getBoolean(KEY_SUPPRESS_FULLSCREEN, true);
        options.clear();
        String[] keys;
        try {
            keys = prefs.keys();
        } catch (Exception e) {
            keys = new String[0];
        }
        for (String key : keys) {
            if (key == null || !key.startsWith(KEY_OPT_PREFIX)) {
                continue;
            }
            // opt.<saverId>.<optionKey>: split on the first separator after
            // the prefix; saver ids are documented to contain no dots, so the
            // remainder is the option key.
            int sep = key.indexOf(OPT_SEPARATOR, KEY_OPT_PREFIX.length());
            if (sep <= KEY_OPT_PREFIX.length()
                    || sep == key.length() - 1) {
                continue;
            }
            String saver = key.substring(KEY_OPT_PREFIX.length(), sep);
            String optKey = key.substring(sep + 1);
            options.computeIfAbsent(saver, s -> new HashMap<>())
                    .put(optKey, prefs.get(key, ""));
        }
    }

    /** Writes the whole model to the store, pruning stale option keys. */
    public synchronized void save() {
        prefs.putBoolean(KEY_ENABLED, enabled);
        prefs.putInt(KEY_TIMEOUT, clampTimeout(timeoutSec));
        prefs.put(KEY_SAVER, saverId == null ? "" : saverId);
        prefs.putBoolean(KEY_CYCLE, cycleAll);
        prefs.putBoolean(KEY_SUPPRESS_FULLSCREEN, suppressFullscreen);
        // Delete every stored option key first, then write back the model, so
        // options of a renamed/removed saver do not linger forever.
        try {
            for (String key : prefs.keys()) {
                if (key != null && key.startsWith(KEY_OPT_PREFIX)) {
                    prefs.remove(key);
                }
            }
        } catch (Exception e) {
            // Key enumeration failed: writes below still proceed, and coerce()
            // keeps any leftover garbage from ever reaching a saver view.
        }
        for (Map.Entry<String, Map<String, String>> saver
                : options.entrySet()) {
            for (Map.Entry<String, String> opt
                    : saver.getValue().entrySet()) {
                prefs.put(KEY_OPT_PREFIX + saver.getKey()
                        + OPT_SEPARATOR + opt.getKey(), opt.getValue());
            }
        }
        try {
            prefs.flush();
        } catch (Exception e) {
            // Persisting failed (read-only home); the in-memory model still
            // drives this session, which is the documented degradation.
        }
    }

    /** Restores every default (and persists it). Used by "Reset to defaults". */
    public void resetToDefaults() {
        enabled = false;
        timeoutSec = DEFAULT_TIMEOUT_SEC;
        saverId = "";
        cycleAll = false;
        suppressFullscreen = true;
        options.clear();
        save();
    }

    // ------------------------------------------------------------------
    // Typed accessors (setters mutate memory; call save() to persist)
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public int getTimeoutSec() {
        return timeoutSec;
    }

    public void setTimeoutSec(int value) {
        timeoutSec = clampTimeout(value);
    }

    /** The selected saver id, or the empty string for "first available". */
    public String getSaverId() {
        return saverId;
    }

    public void setSaverId(String value) {
        saverId = value == null ? "" : value;
    }

    /** True to rotate through every installed saver instead of one. */
    public boolean isCycleAll() {
        return cycleAll;
    }

    public void setCycleAll(boolean value) {
        cycleAll = value;
    }

    /**
     * True (default) to postpone engagement while a maximized window fills a
     * screen - a presentation or full-screen view should not be blanked mid
     * act; the saver engages as soon as the fullscreen window goes away.
     */
    public boolean isSuppressFullscreen() {
        return suppressFullscreen;
    }

    public void setSuppressFullscreen(boolean value) {
        suppressFullscreen = value;
    }

    /**
     * The raw stored option values for {@code saverId} (never null; values
     * are still unvalidated - the service coerces them against the schema).
     */
    public Map<String, String> optionsFor(String saverId) {
        Map<String, String> m = options.get(saverId);
        return m == null ? Map.of() : Map.copyOf(m);
    }

    /** Sets one option value in memory for the given saver. */
    public void setOption(String saverId, String key, String value) {
        options.computeIfAbsent(saverId, s -> new HashMap<>())
                .put(key, value == null ? "" : value);
    }

    private static int clampTimeout(int v) {
        return Math.max(MIN_TIMEOUT_SEC, Math.min(MAX_TIMEOUT_SEC, v));
    }
}
