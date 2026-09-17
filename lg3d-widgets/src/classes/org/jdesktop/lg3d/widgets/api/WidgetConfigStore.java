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
package org.jdesktop.lg3d.widgets.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists the desktop widget layout to
 * {@code ${user.home}/.config/lg3d/widgets.properties}.
 *
 * <p>The store is a thin, typed wrapper over a {@link Properties} object. It
 * records the ordered list of placed widget instances and, for each instance, its
 * type id, its position (as a fraction of the screen so it survives resolution
 * changes) and any widget-specific options.</p>
 *
 * <p>Key layout:</p>
 * <pre>
 *   instances              = clock-1,temperature-2
 *   clock-1.type           = clock
 *   clock-1.x              = 0.08        (0 = left edge, 1 = right edge)
 *   clock-1.y              = 0.10        (0 = top edge,  1 = bottom edge)
 *   clock-1.mode           = analog      (widget-defined option)
 * </pre>
 *
 * <p>All mutating methods are synchronized; {@link #save()} writes atomically
 * enough for a single-user desktop config (write to a temp file, then move).</p>
 */
public final class WidgetConfigStore {
    private static final Logger logger = Logger.getLogger("lg.widgets");

    /** The key holding the comma-separated list of instance ids. */
    public static final String KEY_INSTANCES = "instances";

    private final Path file;
    private final Properties props = new Properties();

    /** Uses the default per-user location. */
    public WidgetConfigStore() {
        this(defaultPath());
    }

    public WidgetConfigStore(Path file) {
        this.file = file;
        load();
    }

    /** {@code ${user.home}/.config/lg3d/widgets.properties}. */
    public static Path defaultPath() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".config", "lg3d", "widgets.properties");
    }

    private synchronized void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not read widget config " + file, e);
        }
    }

    /** Writes the current state back to disk. */
    public synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = Files.createTempFile(
                    parent != null ? parent : Paths.get("."), "widgets", ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, "lg3d desktop widgets - managed automatically");
            }
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveEx) {
                // Some filesystems cannot atomic-move; fall back to a plain copy.
                Files.copy(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write widget config " + file, e);
        }
    }

    // ------------------------------------------------------------------
    // Typed accessors
    // ------------------------------------------------------------------

    public synchronized String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public synchronized void set(String key, String value) {
        if (value == null) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
    }

    public int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public void setInt(String key, int value) { set(key, Integer.toString(value)); }

    public float getFloat(String key, float def) {
        String v = get(key, null);
        if (v == null) return def;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public void setFloat(String key, float value) { set(key, Float.toString(value)); }

    public boolean getBoolean(String key, boolean def) {
        String v = get(key, null);
        return (v == null) ? def : Boolean.parseBoolean(v.trim());
    }

    public void setBoolean(String key, boolean value) { set(key, Boolean.toString(value)); }

    /** Reads a comma-separated list; empty when the key is absent. */
    public List<String> getList(String key) {
        List<String> out = new ArrayList<>();
        String v = get(key, "");
        for (String part : v.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public void setList(String key, List<String> values) {
        set(key, (values == null) ? "" : String.join(",", values));
    }

    // ------------------------------------------------------------------
    // Instance-oriented helpers
    // ------------------------------------------------------------------

    /** The ordered list of placed widget instance ids. */
    public List<String> instances() {
        return getList(KEY_INSTANCES);
    }

    public void setInstances(List<String> instances) {
        setList(KEY_INSTANCES, instances);
    }

    public String getType(String instanceId) {
        return get(instanceId + ".type", null);
    }

    public void setType(String instanceId, String typeId) {
        set(instanceId + ".type", typeId);
    }

    /** Fractional X (0..1, left to right). */
    public float getX(String instanceId, float def) {
        return getFloat(instanceId + ".x", def);
    }

    /** Fractional Y (0..1, top to bottom). */
    public float getY(String instanceId, float def) {
        return getFloat(instanceId + ".y", def);
    }

    public void setPosition(String instanceId, float fx, float fy) {
        setFloat(instanceId + ".x", fx);
        setFloat(instanceId + ".y", fy);
    }

    /** A widget-specific option namespaced under the instance id. */
    public String getOption(String instanceId, String option, String def) {
        return get(instanceId + "." + option, def);
    }

    public void setOption(String instanceId, String option, String value) {
        set(instanceId + "." + option, value);
    }

    /**
     * Adds an instance to the ordered list if not already present.
     */
    public void addInstance(String instanceId) {
        List<String> list = instances();
        if (!list.contains(instanceId)) {
            list.add(instanceId);
            setInstances(list);
        }
    }

    /**
     * Removes an instance from the ordered list and deletes every key that
     * belongs to it.
     */
    public synchronized void removeInstance(String instanceId) {
        List<String> list = instances();
        list.remove(instanceId);
        setInstances(list);
        String prefix = instanceId + ".";
        props.keySet().removeIf(k -> ((String) k).startsWith(prefix));
    }

    /** The underlying file (for diagnostics). */
    public Path getFile() {
        return file;
    }
}
