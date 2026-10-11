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

import java.util.List;
import java.util.Locale;

/**
 * One user-tweakable option of a {@link ScreenSaver}, described declaratively
 * so the Control Center can build the option's editor generically: every
 * saver's settings UI is derived from its option schema, so a newly installed
 * saver is configurable the moment ServiceLoader can see it, with no panel
 * code change (customizable and extensible by construction).
 *
 * <p>Values always travel as strings (that is what
 * {@link ScreensaverSettings} persists and {@link ScreenSaver#createView}
 * receives); {@link #coerce} is the one place a raw stored string is validated
 * and clamped into a legal value, so a hand-edited or stale preference can
 * never crash a saver.</p>
 *
 * <p>Instances are immutable value objects; use the static factories
 * ({@link #toggle}, {@link #slider}, {@link #choice}, {@link #text}).</p>
 */
public final class ScreenSaverOption {

    /** The editor kind the Control Center renders for this option. */
    public enum Type {
        /** On/off; the value is {@code "true"} or {@code "false"}. */
        TOGGLE,
        /** Integer in [{@link #getMin()}, {@link #getMax()}]. */
        SLIDER,
        /** One of a fixed list ({@link #getChoices()}). */
        CHOICE,
        /** Free text (a label, a folder path); never clamped. */
        TEXT
    }

    private final String key;
    private final String label;
    private final Type type;
    private final int min;
    private final int max;
    private final List<String> choices;
    private final String defaultValue;

    private ScreenSaverOption(String key, String label, Type type,
                              int min, int max, List<String> choices,
                              String defaultValue) {
        this.key = key;
        this.label = label;
        this.type = type;
        this.min = min;
        this.max = max;
        this.choices = choices == null ? List.of()
                : List.copyOf(choices);
        this.defaultValue = defaultValue;
    }

    /** An on/off option whose stored value is {@code "true"}/{@code "false"}. */
    public static ScreenSaverOption toggle(String key, String label,
                                           boolean defaultValue) {
        return new ScreenSaverOption(key, label, Type.TOGGLE, 0, 1, null,
                Boolean.toString(defaultValue));
    }

    /**
     * An integer option clamped to [{@code min}, {@code max}].
     */
    public static ScreenSaverOption slider(String key, String label, int min,
                                           int max, int defaultValue) {
        if (min > max) {
            throw new IllegalArgumentException(
                    "option " + key + ": min > max");
        }
        return new ScreenSaverOption(key, label, Type.SLIDER, min, max,
                // Choices are unused for sliders; step is folded into min/max
                // only for the UI, which reads them via getMin()/getMax().
                List.of(), Integer.toString(clamp(defaultValue, min, max)));
    }

    /** A pick-one option; the default must be one of {@code choices}. */
    public static ScreenSaverOption choice(String key, String label,
                                           String defaultValue,
                                           String... choices) {
        if (choices == null || choices.length == 0) {
            throw new IllegalArgumentException(
                    "option " + key + ": no choices");
        }
        List<String> list = List.of(choices);
        String def = list.contains(defaultValue) ? defaultValue : list.get(0);
        return new ScreenSaverOption(key, label, Type.CHOICE, 0, 0, list, def);
    }

    /** A free-text option (a name, a folder path); blank means "unset". */
    public static ScreenSaverOption text(String key, String label,
                                         String defaultValue) {
        return new ScreenSaverOption(key, label, Type.TEXT, 0, 0, null,
                defaultValue == null ? "" : defaultValue);
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public Type getType() {
        return type;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public List<String> getChoices() {
        return choices;
    }

    /** The value used when the stored setting is absent or illegal. */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * Validates a raw stored value against this option's schema and returns a
     * legal value, falling back to {@link #getDefaultValue()} for anything
     * unparseable or out of range. Never throws.
     */
    public String coerce(String raw) {
        if (raw == null) {
            return defaultValue;
        }
        switch (type) {
            case TOGGLE:
                return "true".equalsIgnoreCase(raw.trim().toLowerCase(Locale.ROOT))
                        ? "true" : "false";
            case SLIDER: {
                try {
                    return Integer.toString(clamp(Integer.parseInt(raw.trim()),
                            min, max));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            case CHOICE: {
                String trimmed = raw.trim();
                for (String c : choices) {
                    if (c.equals(trimmed)) {
                        return c;
                    }
                }
                return defaultValue;
            }
            case TEXT:
            default:
                return raw;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public String toString() {
        return key + "=" + defaultValue;
    }
}
