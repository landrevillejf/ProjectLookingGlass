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
public final class ShortcutMap {

    // Action ids. Kept as strings so they can be logged and customised.
    public static final String SHOW_DESKTOP = "show-desktop";
    public static final String SNAP_LEFT = "snap-left";
    public static final String SNAP_RIGHT = "snap-right";
    public static final String SNAP_MAXIMIZE = "snap-maximize";
    public static final String RUN_DIALOG = "run-dialog";
    public static final String OPEN_TERMINAL = "open-terminal";
    public static final String WINDOW_CLOSE = "window-close";
    public static final String WORKSPACE_NEXT = "workspace-next";
    public static final String WORKSPACE_PREVIOUS = "workspace-previous";
    /**
     * Prefix for the "move the focused window to workspace <em>n</em>" actions;
     * the action id is this prefix followed by the 0-based workspace index
     * (e.g. {@code move-to-workspace-2}). Bound to <em>Alt+Shift+1..9</em>.
     */
    public static final String MOVE_TO_WORKSPACE_PREFIX = "move-to-workspace-";

    private final Map<KeyStroke, String> bindings;

    /**
     * Builds a map from {@code specToAction}, a table of keystroke-spec string
     * to action id. Unparseable specs and null/blank actions are skipped, so a
     * bad custom entry can never break the whole table.
     */
    public ShortcutMap(Map<String, String> specToAction) {
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
    public static ShortcutMap defaults() {
        return new ShortcutMap(defaultBindings());
    }

    /**
     * The default keystroke-spec → action table. The combos are chosen to
     * reach an ordinary application window: the host window manager (GNOME/Mutter,
     * Xwayland) grabs Super+key and Ctrl+Alt+arrow before the JVM sees them, so
     * those are avoided here (and reserved for workspace switching later).
     */
    public static Map<String, String> defaultBindings() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("control alt D", SHOW_DESKTOP);
        map.put("alt shift LEFT", SNAP_LEFT);
        map.put("alt shift RIGHT", SNAP_RIGHT);
        map.put("alt shift UP", SNAP_MAXIMIZE);
        map.put("alt F2", RUN_DIALOG);
        map.put("control alt T", OPEN_TERMINAL);
        map.put("control W", WINDOW_CLOSE);
        // Workspace paging. Alt+Shift+PageDown/PageUp step through the
        // workspaces; the host window manager reserves Ctrl+Alt+arrow and
        // Super+digit for its own workspace switching, so those are avoided.
        map.put("alt shift PAGE_DOWN", WORKSPACE_NEXT);
        map.put("alt shift PAGE_UP", WORKSPACE_PREVIOUS);
        // Alt+Shift+<n> moves the focused window to workspace n (1-based key,
        // 0-based action id), matching the WorkspaceModel single-digit range.
        for (int n = 1; n <= WorkspaceModel.MAX_COUNT; n++) {
            map.put("alt shift " + n, MOVE_TO_WORKSPACE_PREFIX + (n - 1));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Overlays {@code customActionToSpec} (an action -> keystroke-spec table, as
     * decoded from {@code DesktopConfig.getCustomShortcuts()}) onto
     * {@link #defaultBindings()}: any default binding whose action is overridden
     * is dropped, then each custom action -> spec is added. The result is a
     * spec -> action table ready for {@link #ShortcutMap(Map)}. Pure so it can be
     * unit-tested headless; null/blank custom actions or specs are ignored, so a
     * bad override can never remove a default without replacing it.
     */
    public static Map<String, String> mergeBindings(Map<String, String> customActionToSpec) {
        Map<String, String> merged = new LinkedHashMap<>(defaultBindings());
        if (customActionToSpec == null || customActionToSpec.isEmpty()) {
            return merged;
        }
        Set<String> overridden = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, String> e : customActionToSpec.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank()
                    && e.getValue() != null && !e.getValue().isBlank()) {
                overridden.add(e.getKey().trim());
            }
        }
        merged.values().removeIf(overridden::contains);
        for (Map.Entry<String, String> e : customActionToSpec.entrySet()) {
            String action = e.getKey();
            String spec = e.getValue();
            if (action != null && !action.isBlank() && spec != null && !spec.isBlank()) {
                merged.put(spec.trim(), action.trim());
            }
        }
        return merged;
    }

    /** Parses a keystroke spec, or null when it is blank/invalid. */
    public static KeyStroke parse(String spec) {
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
    public Optional<String> actionFor(KeyStroke stroke) {
        if (stroke == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bindings.get(stroke));
    }

    /** The action bound to a keystroke spec string, or empty. */
    public Optional<String> actionForSpec(String spec) {
        return actionFor(parse(spec));
    }

    /** True when {@code stroke} is bound to some action. */
    public boolean isBound(KeyStroke stroke) {
        return actionFor(stroke).isPresent();
    }

    /** The set of bound action ids (may be fewer than the bindings if shared). */
    public Set<String> actions() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(bindings.values()));
    }

    /** How many keystrokes are bound. */
    public int size() {
        return bindings.size();
    }
}
