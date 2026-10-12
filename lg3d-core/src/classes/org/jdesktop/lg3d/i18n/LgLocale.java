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

import java.util.Locale;
import java.util.ResourceBundle;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * The desktop's one language decision, resolved once per run.
 *
 * <p>Precedence, highest first: the {@value #SYSPROP} system property (a
 * developer's hammer, {@code -Dlg.locale=fr}), then the language stored in
 * {@link DesktopConfig#getLocale()} by Control Center, then the JVM default
 * locale the host desktop was started with. Anything the resolver does not
 * recognise falls back to English - the language of the source code and of
 * the untranslated bundle, so a bad value can never blank a label.</p>
 *
 * <p>There is deliberately <b>no hot language switch</b>: the value is read
 * once and cached, and Control Center says so on its language row. Changing
 * the language means restarting the desktop, which keeps every surface -
 * 3D {@code GlassyText2D} strings baked at construction, menu models built
 * once from the {@code .lgcfg} descriptors - honest about what it shows.</p>
 */
public final class LgLocale {

    /** System property forcing the language, bypassing the stored setting. */
    public static final String SYSPROP = "lg.locale";

    /** Stored values: follow the host, or pin one of the shipped languages. */
    public static final String SYSTEM = "system";
    public static final String EN = "en";
    public static final String FR = "fr";

    /** The languages the selector and the resolver accept, in display order. */
    public static final String[] PINNED = {EN, FR};

    private static volatile Locale resolved;

    private LgLocale() {
        // static access only
    }

    /** The language of this desktop run; resolved on first call, then cached. */
    public static Locale get() {
        Locale local = resolved;
        if (local == null) {
            synchronized (LgLocale.class) {
                local = resolved;
                if (local == null) {
                    local = resolve(System.getProperty(SYSPROP),
                            storedValue(), Locale.getDefault());
                    resolved = local;
                }
            }
        }
        return local;
    }

    /**
     * The pure precedence rule, separate from the live sources so the tests
     * can pin every branch without touching system properties or preferences.
     *
     * @param sysProp     the {@value #SYSPROP} property value, may be null
     * @param stored      the {@link DesktopConfig} value, may be null
     * @param jvmDefault  the fallback when both ask for "system"
     * @return the language to run the desktop in; never null
     */
    public static Locale resolve(String sysProp, String stored, Locale jvmDefault) {
        String requested = normalize(sysProp);
        if (SYSTEM.equals(requested)) {
            requested = normalize(stored);
        }
        if (SYSTEM.equals(requested) || requested.isEmpty()) {
            return (jvmDefault == null) ? Locale.ENGLISH : jvmDefault;
        }
        // A recognized code pins the language; an unknown one is English,
        // never a guess - "de" must not silently become "fr" or a crash.
        return isPinned(requested) ? Locale.of(requested) : Locale.ENGLISH;
    }

    /** True when the run is in English, i.e. the untranslated source language. */
    public static boolean isEnglish() {
        String language = get().getLanguage();
        return language.isEmpty() || EN.equals(language);
    }

    /**
     * Forgets the cached decision and every loaded bundle. Only for tests:
     * production changes to the language go in effect through a restart,
     * which is the whole point of resolving once.
     */
    public static void resetForTests() {
        synchronized (LgLocale.class) {
            resolved = null;
        }
        ResourceBundle.clearCache();
    }

    /** The three accepted values, trimmed and lower-cased; anything else is "system". */
    static String normalize(String value) {
        if (value == null) {
            return SYSTEM;
        }
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        return candidate.isEmpty() ? SYSTEM : candidate;
    }

    private static boolean isPinned(String value) {
        for (String pinned : PINNED) {
            if (pinned.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The stored Control Center value, read defensively: the preferences node
     * may be unreachable (a locked-down home directory, a headless probe with
     * no user realm), and a missing setting is simply "follow the host".
     */
    private static String storedValue() {
        try {
            return DesktopConfig.get().getLocale();
        } catch (RuntimeException e) {
            return SYSTEM;
        }
    }
}
