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
package org.jdesktop.lg3d.i18n;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.ResourceBundle.Control;

/**
 * The shell's user-visible labels, looked up in the run's language.
 *
 * <p>One bundle family, {@code org/jdesktop/lg3d/i18n/messages*.properties},
 * shipped inside the lg3d-core jar next to this class: the plain file is the
 * English source of truth, {@code messages_fr.properties} the French
 * translation. The {@code i18n-keys.mnd} repository gate fails the build when
 * the two drift apart or when the code asks for a key the base bundle does
 * not define, so a lookup here is expected to hit; it still never throws.</p>
 *
 * <p>Only labels belong here. Log messages, exception texts and preference
 * keys stay English - they are for the developer reading a log, not for the
 * person using the desktop.</p>
 */
public final class Messages {

    private static final String BASE_NAME = "org.jdesktop.lg3d.i18n.messages";

    /**
     * Without this control, a missing translation falls back to the JVM
     * default locale before the base bundle - which on a French machine would
     * mean an unknown language silently renders in French. The base bundle is
     * the fallback, full stop.
     */
    private static final Control FALLBACK_TO_BASE = new Control() {
        @Override
        public java.util.Locale getFallbackLocale(String baseName, java.util.Locale locale) {
            return null;
        }
    };

    private Messages() {
        // static access only
    }

    /**
     * The label for {@code key} in the run's language, formatted with
     * {@code args} when given. A missing key returns the key itself - an
     * untranslated label on screen is a bug report, a blank dialog is worse.
     *
     * <p>Patterns that take arguments are {@link MessageFormat} patterns and
     * must double their apostrophes ({@code ''}); plain labels need no such
     * ceremony because formatting only runs when arguments are passed.</p>
     */
    public static String tr(String key, Object... args) {
        String pattern = raw(key);
        if (pattern == null) {
            return key;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return MessageFormat.format(pattern, args);
        } catch (RuntimeException e) {
            // A broken pattern must not take a menu item down with it.
            return pattern;
        }
    }

    /**
     * The label for {@code key}, or {@code fallback} when no bundle defines
     * it. This is how the {@code .lgcfg} descriptors are translated: the XML
     * keeps its English literal and doubles as the fallback, so adding a
     * language never requires touching the 83 descriptor files.
     */
    public static String trOrDefault(String key, String fallback) {
        String pattern = raw(key);
        return (pattern == null) ? fallback : pattern;
    }

    /**
     * The display name of a start-menu descriptor or group, translated under
     * {@code lgcfg.<slug>.name} and falling back to the XML literal. The key
     * is the slug of the raw name, not the descriptor file: one {@code .lgcfg}
     * can declare six items ({@code startmenu.lgcfg} does), and two descriptors
     * that show the same name should share one translation, not drift.
     */
    public static String trLgcfgName(String xmlName) {
        if (xmlName == null || xmlName.isBlank()) {
            return xmlName;
        }
        return trOrDefault("lgcfg." + slug(xmlName) + ".name", xmlName);
    }

    /**
     * The description shown for the descriptor named {@code xmlName}, under
     * {@code lgcfg.<slug>.desc}, falling back to the XML description. Keyed on
     * the name because the name is what identifies the entry to the reader.
     */
    public static String trLgcfgDesc(String xmlName, String xmlDesc) {
        if (xmlName == null || xmlName.isBlank()) {
            return xmlDesc;
        }
        return trOrDefault("lgcfg." + slug(xmlName) + ".desc", xmlDesc);
    }

    /** Lower-case, hyphen-separated, alphanumeric-only form of a display name. */
    static String slug(String display) {
        String s = display.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "unnamed" : s;
    }

    /** Whether the run's bundle chain defines {@code key}. */
    public static boolean hasKey(String key) {
        try {
            return bundle().containsKey(key);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String raw(String key) {
        if (key == null) {
            return null;
        }
        try {
            return bundle().getString(key);
        } catch (MissingResourceException | ClassCastException e) {
            return null;
        }
    }

    private static ResourceBundle bundle() {
        return ResourceBundle.getBundle(BASE_NAME, LgLocale.get(), FALLBACK_TO_BASE);
    }
}
