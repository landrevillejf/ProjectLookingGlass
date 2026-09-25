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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.swing.KeyStroke;

/**
 * The 2D desktop's global keyboard-shortcut table: a mapping from a
 * {@link KeyStroke} (parsed from its {@code "control alt T"} string form) to an
 * action id.
 *
 * <p>This is pure lookup with no AWT event plumbing, so the default bindings and
 * the parse/resolve rules are unit-testable headless. {@link Shortcuts} turns a
 * resolved action id into a call on the desktop, and {@link Desktop2D} installs
 * the {@code KeyEventDispatcher} that feeds strokes in.</p>
 *
 * <p>The window switcher's <em>Alt+`</em> binding is deliberately <strong>not</strong>
 * in this map, so the dispatcher never claims it and the switcher keeps working.</p>
 */
final class ShortcutMap {

    // Action ids. Kept as strings so they can be logged and customised.
    static final String SHOW_DESKTOP = "show-desktop";
    static final String SNAP_LEFT = "snap-left";
    static final String SNAP_RIGHT = "snap-right";
    static final String SNAP_MAXIMIZE = "snap-maximize";
    static final String RUN_DIALOG = "run-dialog";
    static final String OPEN_TERMINAL = "open-terminal";
    static final String WINDOW_CLOSE = "window-close";

    private final Map<KeyStroke, String> bindings;

    /**
     * Builds a map from {@code specToAction}, a table of keystroke-spec string
     * to action id. Unparseable specs and null/blank actions are skipped, so a
     * bad custom entry can never break the whole table.
     */
    ShortcutMap(Map<String, String> specToAction) {
        Map<KeyStroke, String> parsed = new LinkedHashMap<>();
        if (specToAction != null) {
            for (Map.Entry<String, String> entry : specToAction.entrySet()) {
                KeyStroke stroke = parse(entry.getKey());
                String action = entry.getValue();
                if (stroke != null && action != null && !action.isBlank()) {
                    parsed.put(stroke, action.trim());
                }
            }
        }
        this.bindings = Collections.unmodifiableMap(parsed);
    }

    /** The map carrying the default bindings. */
    static ShortcutMap defaults() {
        return new ShortcutMap(defaultBindings());
    }

    /**
     * The default keystroke-spec → action table. The combos are chosen to
     * reach an ordinary application window: the host window manager (GNOME/Mutter,
     * Xwayland) grabs Super+key and Ctrl+Alt+arrow before the JVM sees them, so
     * those are avoided here (and reserved for workspace switching later).
     */
    static Map<String, String> defaultBindings() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("control alt D", SHOW_DESKTOP);
        map.put("alt shift LEFT", SNAP_LEFT);
        map.put("alt shift RIGHT", SNAP_RIGHT);
        map.put("alt shift UP", SNAP_MAXIMIZE);
        map.put("alt F2", RUN_DIALOG);
        map.put("control alt T", OPEN_TERMINAL);
        map.put("control W", WINDOW_CLOSE);
        return Collections.unmodifiableMap(map);
    }

    /** Parses a keystroke spec, or null when it is blank/invalid. */
    static KeyStroke parse(String spec) {
        if (spec == null) {
            return null;
        }
        String trimmed = spec.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return KeyStroke.getKeyStroke(trimmed);
    }

    /** The action bound to {@code stroke}, or empty when it is not bound. */
    Optional<String> actionFor(KeyStroke stroke) {
        if (stroke == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bindings.get(stroke));
    }

    /** The action bound to a keystroke spec string, or empty. */
    Optional<String> actionForSpec(String spec) {
        return actionFor(parse(spec));
    }

    /** True when {@code stroke} is bound to some action. */
    boolean isBound(KeyStroke stroke) {
        return actionFor(stroke).isPresent();
    }

    /** The set of bound action ids (may be fewer than the bindings if shared). */
    Set<String> actions() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(bindings.values()));
    }

    /** How many keystrokes are bound. */
    int size() {
        return bindings.size();
    }
}
