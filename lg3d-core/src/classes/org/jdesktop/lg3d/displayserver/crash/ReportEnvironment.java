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
package org.jdesktop.lg3d.displayserver.crash;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The build and machine facts a maintainer needs to reproduce a crash.
 *
 * <p>"It crashes" is unactionable; "1.68.1-dev, JDK 21.0.12.1, Fedora 44 x86_64,
 * {@code lg.fws.mode=dev}, 3 of 4 CPUs, 2 GB of the 4 GB heap used, fullscreen
 * display config {@code j3d1x1-nbfs}" is a bug report. This class assembles that
 * sentence from values the desktop already knows, and nothing else.</p>
 *
 * <p><b>What is deliberately absent is the contract.</b> No user name, no home
 * directory, no host name, no e-mail, no screen resolution as such, no list of
 * mounted volumes: an environment block is the one part of a crash report every
 * implementation copies verbatim, so a field added here leaks on every report
 * forever. Everything below answers a question a maintainer would ask, and each
 * value is either a version string or a capability, never an identity. Host name
 * in particular would let a maintainer reach the reporter's machine - which turns
 * an issue on a public tracker into an attack surface.</p>
 *
 * <p>Every collector is total: a JVM that refuses {@code availableProcessors()} or
 * a security manager that blocks a property yields {@code unknown} rather than an
 * exception thrown out of the error path. A crash reporter that can crash is a
 * crash reporter that hides crashes.</p>
 */
public final class ReportEnvironment {

    /** The value printed when a fact could not be read. */
    public static final String UNKNOWN = "unknown";

    /** Format of the timestamp line: readable, sortable, zone-aware. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withLocale(Locale.US);

    private final Map<String, String> values;

    private ReportEnvironment(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    /**
     * Reads the live environment. Insertion order is the display order: version
     * first, because it is the field a maintainer scans for before reading
     * anything else.
     *
     * @param occurredAt the moment of the failure, used for the timestamp line
     * @return the environment block, never {@code null}
     */
    public static ReportEnvironment collect(Instant occurredAt) {
        return collect(occurredAt, System::getProperty, Runtime.getRuntime(),
                ZoneId.systemDefault().getId());
    }

    /**
     * The same block from injected inputs, so a test can pin every field without
     * running inside the JVM the desktop runs in. The properties arrive as a
     * function rather than a {@code Map} because {@link System#getProperties()}
     * is a {@code Hashtable<Object,Object>}: a map parameter would force every
     * caller to copy the whole property table to type an argument.
     *
     * @param occurredAt the moment of the failure
     * @param props      a property reader, {@code null} for an unset name
     * @param runtime    the runtime for heap and CPU counts
     * @param zoneId     the time-zone identifier to print
     * @return the environment block
     */
    public static ReportEnvironment collect(Instant occurredAt, Function<String, String> props,
                                            Runtime runtime, String zoneId) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("lg3d version", version(props));
        out.put("java", nonBlank(props.apply("java.version"), UNKNOWN)
                + " (" + nonBlank(props.apply("java.vendor"), UNKNOWN) + ")");
        out.put("os", nonBlank(props.apply("os.name"), UNKNOWN) + " "
                + nonBlank(props.apply("os.version"), UNKNOWN) + " "
                + nonBlank(props.apply("os.arch"), UNKNOWN));
        out.put("locale", Locale.getDefault().toLanguageTag());
        out.put("zone", nonBlank(zoneId, UNKNOWN));
        out.put("when", occurredAt == null ? UNKNOWN
                : STAMP.format(occurredAt.atZone(zoneOrFallback(zoneId))));
        // The desktop-mode pair is the single biggest discriminator in this
        // project: the 2D Swing desktop and the 3D Java 3D desktop share almost
        // no code path, and a crash in one is unrelated to the other.
        out.put("fws mode", nonBlank(props.apply("lg.fws.mode"), "user"));
        out.put("display config", nonBlank(props.apply("lg.displayconfigurl"), UNKNOWN));
        out.put("3D background", nonBlank(props.apply("lg.3dbackground"), "false"));
        out.put("heap", memory(runtime));
        out.put("cpus", cpus(runtime));
        out.put("thread", Thread.currentThread().getName());
        return new ReportEnvironment(out);
    }

    /**
     * The block as a Markdown table, the shape that reads best on the tracker.
     *
     * @return one header line per fact, pipe-separated
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("| field | value |\n| --- | --- |\n");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            sb.append("| ").append(entry.getKey()).append(" | ")
              .append(escapeTable(entry.getValue())).append(" |\n");
        }
        return sb.toString();
    }

    /** @return the facts in display order, unmodifiable */
    public Map<String, String> values() {
        return values;
    }

    /**
     * The version line. Three sources are tried in order because the desktop is
     * launched three different ways and each publishes the number elsewhere: the
     * generated {@code LgBuildInfo} is package-private to {@code displayserver}
     * and therefore unreadable from here, {@code lg3d.version} is set by the
     * launcher scripts, and the jar manifest's {@code Implementation-Version} is
     * set by Gradle - a developer running from Gradle sees the second, a user
     * running the release bundle sees the third, and printing {@code unknown} for
     * either would waste the report.
     */
    private static String version(Function<String, String> props) {
        String fromProperty = props.apply("lg3d.version");
        if (isBlank(fromProperty)) {
            fromProperty = props.apply("product.version");
        }
        if (!isBlank(fromProperty)) {
            return fromProperty.trim();
        }
        Package pkg = ReportEnvironment.class.getPackage();
        String fromManifest = (pkg == null) ? null : pkg.getImplementationVersion();
        return nonBlank(fromManifest, UNKNOWN);
    }

    /**
     * A zone the parser accepts, falling back to UTC. {@code ZoneId.of} throws on
     * a garbage identifier, and this is reached from the error path: an unreadable
     * zone must cost one field value, not the report.
     */
    private static ZoneId zoneOrFallback(String zoneId) {
        try {
            return ZoneId.of(nonBlank(zoneId, "UTC"));
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private static String memory(Runtime runtime) {
        try {
            long max = runtime.maxMemory();
            long used = runtime.totalMemory() - runtime.freeMemory();
            if (max <= 0L) {
                return UNKNOWN;
            }
            // MiB, not bytes: a maintainer compares the number against the
            // documented -Xmx of the launcher script, which is written in MiB.
            return toMiB(used) + " MiB used of " + toMiB(max) + " MiB max";
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    private static String cpus(Runtime runtime) {
        try {
            int cpus = runtime.availableProcessors();
            return (cpus > 0) ? String.valueOf(cpus) : UNKNOWN;
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    private static long toMiB(long bytes) {
        return bytes >> 20;
    }

    /** A pipe inside a table cell would break the table; backticks hide markup. */
    private static String escapeTable(String value) {
        if (value == null || value.isEmpty()) {
            return "—";
        }
        return value.replace("|", "\\|").replace("<", "&lt;");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
