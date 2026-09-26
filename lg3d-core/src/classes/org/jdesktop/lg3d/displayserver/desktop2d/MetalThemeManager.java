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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.metal.MetalLookAndFeel;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * Chooses, creates and persists the Swing {@code Metal} theme the conventional
 * 2D desktop is skinned with.
 *
 * <p>The 2D shell starts on the platform look-and-feel (GTK/Synth on Linux).
 * Metal themes only affect the {@code Metal} look-and-feel, so applying one
 * here switches the shell onto Metal (if it is not already) with the chosen
 * palette, then refreshes every open window so the change is immediate. The
 * selection and any user-created themes are persisted through
 * {@link DesktopConfig}, and {@link #applyStored()} re-applies the saved theme
 * at desktop start-up - a no-op while no theme has been chosen, so the desktop
 * keeps its native look until the user picks one in the control center.</p>
 *
 * <p>The pure palette work lives in {@link MetalThemeSpec} (unit-tested
 * headless); this class is the thin, EDT-bound live-application layer.</p>
 */
public final class MetalThemeManager {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** Name of the theme last pushed through the live look-and-feel. */
    private static volatile String appliedName;

    private MetalThemeManager() {
        // no instances
    }

    /** The built-in themes (Steel, Ocean), in display order. */
    public static List<MetalThemeSpec> builtIns() {
        return List.of(MetalThemeSpec.STEEL, MetalThemeSpec.OCEAN);
    }

    /**
     * Every selectable theme: the built-ins followed by the user-created themes
     * decoded from {@link DesktopConfig}. Names are unique enough for the UI;
     * a custom theme shadowing a built-in name still resolves to the custom one
     * because {@link #resolve(String)} searches customs first.
     */
    public static List<MetalThemeSpec> available() {
        List<MetalThemeSpec> all = new ArrayList<>(builtIns());
        all.addAll(customThemes());
        return all;
    }

    /** The persisted user-created themes (never null). */
    public static List<MetalThemeSpec> customThemes() {
        return MetalThemeSpec.decodeAll(DesktopConfig.get().getMetalCustomThemes());
    }

    /**
     * Finds a theme by name, preferring user-created themes over built-ins.
     * Returns {@code null} when no theme carries that name.
     */
    public static MetalThemeSpec resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.trim();
        for (MetalThemeSpec spec : customThemes()) {
            if (target.equals(spec.name())) {
                return spec;
            }
        }
        for (MetalThemeSpec spec : builtIns()) {
            if (target.equals(spec.name())) {
                return spec;
            }
        }
        return null;
    }

    /** Wraps {@code spec} in the live {@link CustomMetalTheme} Metal installs. */
    public static CustomMetalTheme toTheme(MetalThemeSpec spec) {
        return new CustomMetalTheme(spec);
    }

    /**
     * Applies {@code spec} to the running desktop and persists the choice.
     * Switches the shell onto Metal if needed, installs the palette and
     * refreshes every open window. Safe from any thread; the look-and-feel work
     * runs on the EDT. A null {@code spec} is ignored.
     */
    public static void apply(MetalThemeSpec spec) {
        if (spec == null) {
            return;
        }
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setMetalTheme(spec.name());
        cfg.save();
        onEdt(() -> applyLive(spec));
    }

    /**
     * Reverts the 2D desktop to the native platform look-and-feel (GTK/Synth on
     * Linux) that the shell starts on, undoing any applied Metal theme. Clears
     * the persisted selection so the next start-up keeps the native look too
     * ({@link #applyStored()} is then a no-op), and refreshes every open window.
     * Safe from any thread; the look-and-feel work runs on the EDT.
     */
    public static void applySystem() {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setMetalTheme("");
        cfg.save();
        onEdt(MetalThemeManager::applySystemLive);
    }

    /**
     * Re-applies the persisted theme, if one was chosen. Called from the 2D
     * shell's {@code reapplyConfig()}; a no-op while the stored theme name is
     * blank (the default), so the desktop keeps its native look until the user
     * selects a Metal theme. Skips the refresh when that theme is already live.
     */
    public static void applyStored() {
        String name = DesktopConfig.get().getMetalTheme();
        if (name == null || name.isBlank()) {
            return;
        }
        MetalThemeSpec spec = resolve(name);
        if (spec == null) {
            return;
        }
        onEdt(() -> {
            if (spec.name().equals(appliedName) && isMetalActive()) {
                return;
            }
            applyLive(spec);
        });
    }

    /**
     * Persists {@code spec} as a new user-created theme (replacing any existing
     * custom theme of the same name) without changing the live look. Returns
     * the stored spec.
     */
    public static MetalThemeSpec addCustom(MetalThemeSpec spec) {
        if (spec == null) {
            return null;
        }
        List<MetalThemeSpec> customs = new ArrayList<>();
        for (MetalThemeSpec existing : customThemes()) {
            if (!existing.name().equals(spec.name())) {
                customs.add(existing);
            }
        }
        customs.add(spec);
        saveCustoms(customs);
        return spec;
    }

    /**
     * Removes the user-created theme named {@code name} (built-ins cannot be
     * removed). When the removed theme was the selected one, the selection is
     * cleared so the next start-up falls back to the native look. Returns true
     * when a theme was removed.
     */
    public static boolean removeCustom(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        List<MetalThemeSpec> customs = new ArrayList<>();
        boolean removed = false;
        for (MetalThemeSpec spec : customThemes()) {
            if (name.equals(spec.name())) {
                removed = true;
            } else {
                customs.add(spec);
            }
        }
        if (!removed) {
            return false;
        }
        saveCustoms(customs);
        DesktopConfig cfg = DesktopConfig.get();
        if (name.equals(cfg.getMetalTheme())) {
            cfg.setMetalTheme("");
            cfg.save();
        }
        return true;
    }

    private static void saveCustoms(List<MetalThemeSpec> customs) {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setMetalCustomThemes(MetalThemeSpec.encodeAll(customs));
        cfg.save();
    }

    // ------------------------------------------------------------------
    // Live application (EDT)
    // ------------------------------------------------------------------

    /** Installs the palette and refreshes open windows. Must run on the EDT. */
    private static void applyLive(MetalThemeSpec spec) {
        try {
            MetalLookAndFeel.setCurrentTheme(toTheme(spec));
            UIManager.setLookAndFeel(new MetalLookAndFeel());
            appliedName = spec.name();
            refreshWindows();
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not apply the Metal theme " + spec.name(), e);
        }
    }

    /** Restores the native platform look-and-feel. Must run on the EDT. */
    private static void applySystemLive() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            appliedName = null;
            refreshWindows();
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not restore the system look and feel", e);
        }
    }

    /** Re-skins every open window so the new theme shows without a restart. */
    private static void refreshWindows() {
        for (Window w : Window.getWindows()) {
            try {
                SwingUtilities.updateComponentTreeUI(w);
                w.invalidate();
                w.validate();
                w.repaint();
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Could not refresh a window for the new theme", e);
            }
        }
    }

    private static boolean isMetalActive() {
        return UIManager.getLookAndFeel() instanceof MetalLookAndFeel;
    }

    /** Runs {@code task} on the EDT, immediately if already there. */
    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }
}
