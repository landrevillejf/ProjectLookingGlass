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

import java.util.ArrayList;
import java.util.List;

/**
 * System locale discovery and management for the control center.
 *
 * <p>Mirrors the {@link PrinterStatus} seam shape: the parsing and
 * command-building are pure and unit-tested ({@link #parseLang},
 * {@link #parseLocales}, {@link #setLocaleCommand}); the probes are thin
 * wrappers over {@code localectl}. On a host without systemd every probe
 * degrades to an empty result / {@code false} rather than throwing. Setting the
 * locale writes {@code /etc/locale.conf} and normally needs administrator
 * privileges (polkit); it also only takes effect for <em>new</em> login
 * sessions, which the panel states explicitly.</p>
 */
public final class LocaleStatus {

    private LocaleStatus() {
        // no instances
    }

    /** True when the {@code localectl} tool is present on this host. */
    public static boolean available() {
        return PrinterStatus.exec(new String[] {"localectl", "--version"}) != null;
    }

    /**
     * Parses the {@code LANG} value from {@code localectl status} output. The
     * relevant line reads {@code "System Locale: LANG=en_US.UTF-8"} (there may be
     * further {@code LC_*} assignments after it); returns the {@code LANG} value,
     * or {@code ""} when there is no system locale set ({@code "n/a"}).
     */
    static String parseLang(String statusOutput) {
        if (statusOutput == null) {
            return "";
        }
        for (String raw : statusOutput.split("\n")) {
            String line = raw.trim();
            int idx = line.indexOf("System Locale:");
            if (idx < 0) {
                continue;
            }
            String rest = line.substring(idx + "System Locale:".length()).trim();
            for (String token : rest.split("\\s+")) {
                if (token.startsWith("LANG=")) {
                    return token.substring("LANG=".length()).trim();
                }
            }
            return "";
        }
        return "";
    }

    /** Parses {@code localectl list-locales} output into locale ids, skipping blanks. */
    static List<String> parseLocales(String listOutput) {
        List<String> out = new ArrayList<>();
        if (listOutput == null) {
            return out;
        }
        for (String raw : listOutput.split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** The current system {@code LANG} value, or {@code ""} when localectl is absent. */
    public static String currentLang() {
        String out = PrinterStatus.exec(new String[] {"localectl", "status"});
        return (out == null) ? "" : parseLang(out);
    }

    /** The available locales, empty when localectl is absent. */
    public static List<String> listLocales() {
        String out = PrinterStatus.exec(new String[] {"localectl", "list-locales"});
        return (out == null) ? new ArrayList<>() : parseLocales(out);
    }

    /** The command that sets the system {@code LANG} locale. */
    static String[] setLocaleCommand(String locale) {
        return new String[] {"localectl", "set-locale", "LANG=" + locale};
    }

    /** Sets the system {@code LANG}; false when unavailable or the locale is blank. */
    public static boolean setLocale(String locale) {
        return locale != null && !locale.isBlank()
                && PrinterStatus.run(setLocaleCommand(locale));
    }
}
