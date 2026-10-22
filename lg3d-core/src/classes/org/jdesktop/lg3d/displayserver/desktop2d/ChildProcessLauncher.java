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

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds and starts the isolated child-process JVMs the 2D desktop forks for
 * its {@link Desktop2DAppRegistry.Kind#CHILD_PROCESS} applications (QNX-style
 * process isolation).
 *
 * <p>An app that runs in its own OS process can crash, leak or be OOM-killed
 * without taking the desktop JVM down: the worst case is a window that closes
 * and a supervisor that offers to restart it (see {@link AppProcessSupervisor}).
 * This class owns only the two decisions that are easy to get wrong and cheap
 * to test - the exact {@code java} command line and the environment the child
 * inherits - and isolates the one impure act (starting the process) in
 * {@link #start}.</p>
 *
 * <p>The command builders are pure so they can be asserted without forking a
 * JVM; {@link #start} is the single seam that touches the operating system.</p>
 */
public final class ChildProcessLauncher {

    /** Property overriding the child browser JVM's maximum heap. */
    public static final String XMX_PROPERTY = "lg3d.webbrowser.childXmx";

    /** Default maximum heap for the child browser JVM. */
    public static final String DEFAULT_XMX = "512m";

    /** Property overriding the JavaFX (Prism) pipeline order. */
    public static final String PRISM_ORDER_PROPERTY = "lg3d.webbrowser.prismOrder";

    /**
     * Default JavaFX pipeline order: software rendering, so the child never
     * fights the desktop (or a compositor) over a hardware GL context it does
     * not own.
     */
    public static final String DEFAULT_PRISM_ORDER = "sw";

    /** Property overriding a plain-Swing child JVM's maximum heap. */
    public static final String SWING_XMX_PROPERTY = "lg3d.child.swingXmx";

    /**
     * Default maximum heap for a plain-Swing isolated child. Smaller than the
     * browser's: a Swing panel has no WebKit engine to feed, and a bounded heap
     * is the whole point of isolation - a runaway child is OOM-killed on its own
     * instead of eating the machine's memory.
     */
    public static final String DEFAULT_SWING_XMX = "256m";

    /**
     * System property set to {@code true} on every isolated child JVM, so a
     * child-aware application (one whose {@code main} also runs in-JVM as a
     * {@code SWING_FRAME}) can tell it is the forked child and therefore own its
     * JVM lifetime - installing a crash handler and exiting when its last window
     * closes - without changing its in-JVM behaviour.
     */
    public static final String ISOLATED_CHILD_PROPERTY = "lg3d.isolatedChild";

    /** WebKit compositing is disabled under a foreign-window compositor. */
    private static final String WEBKIT_COMPOSITING = "WEBKIT_DISABLE_COMPOSITING_MODE";

    /** WebKit DMABUF rendering is unreliable under redirection; force the copy path. */
    private static final String WEBKIT_DMABUF = "WEBKIT_DISABLE_DMABUF_RENDERER";

    /**
     * Non-{@code lg.}/{@code lg3d.}-prefixed system properties still propagated
     * to an isolated child: the helper fat-jar paths the desktop resolves its
     * forked tools from, the update changelog location and the logging config,
     * so a child logs and finds its jars exactly as the desktop JVM does.
     */
    private static final Set<String> EXTRA_PROPAGATED = new TreeSet<>(Arrays.asList(
            "java.util.logging.config.file",
            "openapieditor.jar",
            "payloadman.jar",
            "swingide.jar",
            "update.changelog.path",
            "webserver.jar"));

    /** The kind of isolated child, which decides its JVM arguments and environment. */
    public enum ChildProfile {
        /** A JavaFX/WebKit child (the Web Browser): software pipeline, WebKit workarounds. */
        BROWSER,
        /** A plain Swing child (a hosted panel or a Swing frame app). */
        SWING
    }

    private ChildProcessLauncher() {
        // no instances
    }

    /** The {@code java} launcher of the running JVM ({@code $JAVA_HOME/bin/java}). */
    public static String defaultJavaBin() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** The running JVM's classpath, handed to the child so it resolves the app. */
    public static String currentClasspath() {
        return System.getProperty("java.class.path", "");
    }

    /**
     * The X display the child must draw on: the lg3d server display when set,
     * else the inherited {@code DISPLAY}. Mirrors
     * {@link Desktop2DAppRegistry#launchExternal} so an isolated app lands on
     * the same screen as an external one.
     */
    public static String lg3dDisplay() {
        String display = System.getProperty("lg.lgserverdisplay");
        if (display == null || display.isBlank()) {
            display = System.getenv("DISPLAY");
        }
        return display;
    }

    /**
     * Assembles the {@code java} command line:
     * {@code [javaBin, <jvmArgs...>, "-cp", classpath, mainClass, <mainArgs...>]}.
     *
     * @param javaBin   the {@code java} executable
     * @param classpath the {@code -cp} value
     * @param jvmArgs   JVM arguments placed before {@code -cp} (may be null)
     * @param mainClass the application's main class
     * @param mainArgs  arguments after the main class (may be null)
     * @return the immutable command tokens
     */
    public static List<String> buildCommand(final String javaBin,
            final String classpath, final List<String> jvmArgs,
            final String mainClass, final List<String> mainArgs) {
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        if (jvmArgs != null) {
            command.addAll(jvmArgs);
        }
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        if (mainArgs != null) {
            command.addAll(mainArgs);
        }
        return Collections.unmodifiableList(command);
    }

    /**
     * JVM arguments for the isolated Web Browser child: a bounded heap (so a
     * runaway WebView cannot eat the machine's memory) and the software Prism
     * pipeline with HiDPI scaling pinned off, so the JavaFX peer sizes to the
     * MDI frame it is composited into rather than to a display scale it cannot
     * see. Both are property-overridable for tuning.
     *
     * @return the immutable JVM argument list
     */
    public static List<String> browserJvmArgs() {
        List<String> args = new ArrayList<>();
        args.add("-Xmx" + System.getProperty(XMX_PROPERTY, DEFAULT_XMX));
        args.add("-Dprism.order="
                + System.getProperty(PRISM_ORDER_PROPERTY, DEFAULT_PRISM_ORDER));
        args.add("-Dprism.allowhidpi=false");
        return Collections.unmodifiableList(args);
    }

    /**
     * Environment overrides for the isolated Web Browser child. Starts from
     * {@code base} (the caller's privacy-critical Tor proxy env, which always
     * wins) and adds the display plus the two WebKit workarounds, never
     * overwriting a key the base already set.
     *
     * @param base    authoritative overrides (may be null)
     * @param display the X display to draw on (may be null)
     * @return the immutable environment overrides
     */
    public static Map<String, String> browserEnv(final Map<String, String> base,
            final String display) {
        Map<String, String> env = new LinkedHashMap<>();
        if (base != null) {
            env.putAll(base);
        }
        if (display != null && !display.isBlank()) {
            env.putIfAbsent("DISPLAY", display);
        }
        env.putIfAbsent(WEBKIT_COMPOSITING, "1");
        env.putIfAbsent(WEBKIT_DMABUF, "1");
        return Collections.unmodifiableMap(env);
    }

    /**
     * JVM arguments for a plain-Swing isolated child (a hosted panel or a Swing
     * frame app): only a bounded heap. No Prism pipeline and no WebKit
     * workarounds, because there is no JavaFX in the child. Property-overridable
     * for tuning, like the browser's.
     *
     * @return the immutable JVM argument list
     */
    public static List<String> swingJvmArgs() {
        List<String> args = new ArrayList<>();
        args.add("-Xmx" + System.getProperty(SWING_XMX_PROPERTY, DEFAULT_SWING_XMX));
        return Collections.unmodifiableList(args);
    }

    /**
     * The full JVM argument list for an isolated child of the given profile:
     * the {@link #ISOLATED_CHILD_PROPERTY} marker (so a child-aware app knows it
     * is the fork), the propagated {@code lg.*} configuration (so the child
     * resolves resources, config and helper jars exactly as in-JVM) and then the
     * profile's own arguments.
     *
     * @param profile which kind of child is being forked
     * @return the immutable JVM argument list
     */
    public static List<String> jvmArgsFor(final ChildProfile profile) {
        List<String> args = new ArrayList<>();
        args.add("-D" + ISOLATED_CHILD_PROPERTY + "=true");
        args.addAll(lgSystemPropertyArgs());
        args.addAll(profile == ChildProfile.BROWSER ? browserJvmArgs() : swingJvmArgs());
        return Collections.unmodifiableList(args);
    }

    /**
     * {@code -D} flags carrying every {@code lg.}/{@code lg3d.}-prefixed system
     * property this JVM was started with, plus the small allow-list of helper
     * jar-path and logging properties ({@link #EXTRA_PROPAGATED}). A forked
     * child does <em>not</em> inherit the parent's {@code -D} properties, so
     * without this an isolated app could not find {@code lg.resourcedir}, its
     * config URLs or its helper jars. Sorted by key for a deterministic command
     * line; keys whose value is unset are skipped.
     *
     * @return the immutable list of {@code -Dkey=value} arguments
     */
    public static List<String> lgSystemPropertyArgs() {
        List<String> keys = new ArrayList<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith("lg.") || name.startsWith("lg3d.")
                    || EXTRA_PROPAGATED.contains(name)) {
                keys.add(name);
            }
        }
        Collections.sort(keys);
        List<String> args = new ArrayList<>();
        for (String key : keys) {
            String value = System.getProperty(key);
            if (value != null) {
                args.add("-D" + key + "=" + value);
            }
        }
        return Collections.unmodifiableList(args);
    }

    /**
     * Environment overrides for a plain-Swing isolated child: the caller's
     * privacy-critical {@code base} (which always wins) plus the display. No
     * WebKit workarounds, because there is no WebKit in a Swing child.
     *
     * @param base    authoritative overrides (may be null)
     * @param display the X display to draw on (may be null)
     * @return the immutable environment overrides
     */
    public static Map<String, String> swingEnv(final Map<String, String> base,
            final String display) {
        Map<String, String> env = new LinkedHashMap<>();
        if (base != null) {
            env.putAll(base);
        }
        if (display != null && !display.isBlank()) {
            env.putIfAbsent("DISPLAY", display);
        }
        return Collections.unmodifiableMap(env);
    }

    /**
     * The environment overrides for a child of the given profile, over the
     * privacy-critical {@code base}.
     *
     * @param profile which kind of child is being forked
     * @param base    authoritative overrides (may be null)
     * @param display the X display to draw on (may be null)
     * @return the immutable environment overrides
     */
    public static Map<String, String> envFor(final ChildProfile profile,
            final Map<String, String> base, final String display) {
        return profile == ChildProfile.BROWSER
                ? browserEnv(base, display) : swingEnv(base, display);
    }

    /**
     * Starts the child process: the single impure act of this class, isolated
     * here so the command/env builders around it stay pure and testable. The
     * child inherits this JVM's environment, with {@code envOverrides} applied
     * on top, and its stderr is merged into stdout for the supervisor to drain.
     *
     * @param command      the command tokens from {@link #buildCommand}
     * @param envOverrides environment overrides (may be null)
     * @return the started process
     * @throws IOException if the process could not be started
     */
    public static Process start(final List<String> command,
            final Map<String, String> envOverrides) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        if (envOverrides != null) {
            pb.environment().putAll(envOverrides);
        }
        return pb.start();
    }
}
