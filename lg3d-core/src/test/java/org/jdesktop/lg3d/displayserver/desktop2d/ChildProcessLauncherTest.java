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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure half of {@link ChildProcessLauncher}: the {@code java}
 * command line it assembles and the environment an isolated child inherits.
 * These are the decisions that are easy to get wrong (argument order, the
 * bounded heap, the WebKit workarounds, the privacy-critical base env winning)
 * and cheap to assert without forking a JVM. The one impure act
 * ({@link ChildProcessLauncher#start}) is exercised only live.
 */
class ChildProcessLauncherTest {

    private String savedXmx;
    private String savedPrism;
    private String savedDisplay;
    private String savedSwingXmx;

    @BeforeEach
    void saveProperties() {
        savedXmx = System.getProperty(ChildProcessLauncher.XMX_PROPERTY);
        savedPrism = System.getProperty(ChildProcessLauncher.PRISM_ORDER_PROPERTY);
        savedDisplay = System.getProperty("lg.lgserverdisplay");
        savedSwingXmx = System.getProperty(ChildProcessLauncher.SWING_XMX_PROPERTY);
    }

    @AfterEach
    void restoreProperties() {
        restore(ChildProcessLauncher.XMX_PROPERTY, savedXmx);
        restore(ChildProcessLauncher.PRISM_ORDER_PROPERTY, savedPrism);
        restore("lg.lgserverdisplay", savedDisplay);
        restore(ChildProcessLauncher.SWING_XMX_PROPERTY, savedSwingXmx);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    @DisplayName("buildCommand orders: java, jvmArgs, -cp, classpath, main, args")
    void buildCommandOrder() {
        List<String> cmd = ChildProcessLauncher.buildCommand(
                "/jdk/bin/java", "/cp/a.jar",
                Arrays.asList("-Xmx512m", "-Dprism.order=sw"),
                "org.foo.Main", Arrays.asList("one", "two"));
        assertEquals(List.of("/jdk/bin/java", "-Xmx512m", "-Dprism.order=sw",
                "-cp", "/cp/a.jar", "org.foo.Main", "one", "two"), cmd);
    }

    @Test
    @DisplayName("buildCommand tolerates null jvmArgs and null mainArgs")
    void buildCommandNulls() {
        List<String> cmd = ChildProcessLauncher.buildCommand(
                "java", "cp", null, "org.foo.Main", null);
        assertEquals(List.of("java", "-cp", "cp", "org.foo.Main"), cmd);
    }

    @Test
    @DisplayName("buildCommand returns an immutable list")
    void buildCommandIsImmutable() {
        List<String> cmd = ChildProcessLauncher.buildCommand(
                "java", "cp", null, "org.foo.Main", null);
        assertThrows(UnsupportedOperationException.class, () -> cmd.add("x"));
    }

    @Test
    @DisplayName("browserJvmArgs bounds the heap and pins the software pipeline")
    void browserJvmArgsDefaults() {
        System.clearProperty(ChildProcessLauncher.XMX_PROPERTY);
        System.clearProperty(ChildProcessLauncher.PRISM_ORDER_PROPERTY);
        List<String> args = ChildProcessLauncher.browserJvmArgs();
        assertEquals(List.of("-Xmx512m", "-Dprism.order=sw",
                "-Dprism.allowhidpi=false"), args);
    }

    @Test
    @DisplayName("browserJvmArgs honours the heap and pipeline overrides")
    void browserJvmArgsOverrides() {
        System.setProperty(ChildProcessLauncher.XMX_PROPERTY, "256m");
        System.setProperty(ChildProcessLauncher.PRISM_ORDER_PROPERTY, "es2");
        List<String> args = ChildProcessLauncher.browserJvmArgs();
        assertTrue(args.contains("-Xmx256m"), "heap override applied");
        assertTrue(args.contains("-Dprism.order=es2"), "pipeline override applied");
        assertTrue(args.contains("-Dprism.allowhidpi=false"), "hidpi stays pinned");
    }

    @Test
    @DisplayName("browserEnv adds the display and both WebKit workarounds")
    void browserEnvAddsWebkitAndDisplay() {
        Map<String, String> env =
                ChildProcessLauncher.browserEnv(null, ":0");
        assertEquals(":0", env.get("DISPLAY"));
        assertEquals("1", env.get("WEBKIT_DISABLE_COMPOSITING_MODE"));
        assertEquals("1", env.get("WEBKIT_DISABLE_DMABUF_RENDERER"));
    }

    @Test
    @DisplayName("browserEnv never overwrites a key the base already set")
    void browserEnvBaseWins() {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("DISPLAY", ":9");
        base.put("WEBKIT_DISABLE_COMPOSITING_MODE", "0");
        base.put("ALL_PROXY", "socks5h://127.0.0.1:9050");
        Map<String, String> env = ChildProcessLauncher.browserEnv(base, ":0");
        assertEquals(":9", env.get("DISPLAY"), "base display wins");
        assertEquals("0", env.get("WEBKIT_DISABLE_COMPOSITING_MODE"),
                "base WebKit value wins");
        assertEquals("socks5h://127.0.0.1:9050", env.get("ALL_PROXY"),
                "the privacy-critical Tor env is preserved");
        assertEquals("1", env.get("WEBKIT_DISABLE_DMABUF_RENDERER"),
                "a key the base did not set is still added");
    }

    @Test
    @DisplayName("browserEnv skips DISPLAY when none is known")
    void browserEnvNoDisplay() {
        Map<String, String> env = ChildProcessLauncher.browserEnv(null, null);
        assertFalse(env.containsKey("DISPLAY"));
        assertTrue(env.containsKey("WEBKIT_DISABLE_COMPOSITING_MODE"));
    }

    @Test
    @DisplayName("defaultJavaBin points at the running JVM's launcher")
    void defaultJavaBinIsThisJvm() {
        String bin = ChildProcessLauncher.defaultJavaBin();
        assertNotNull(bin);
        assertTrue(bin.endsWith("bin/java"), "ends with bin/java: " + bin);
    }

    @Test
    @DisplayName("lg3dDisplay prefers the lg3d server display property")
    void lg3dDisplayPrefersProperty() {
        System.setProperty("lg.lgserverdisplay", ":7");
        assertEquals(":7", ChildProcessLauncher.lg3dDisplay());
    }

    @Test
    @DisplayName("lg3dDisplay falls back without throwing when unset")
    void lg3dDisplayFallbackIsSafe() {
        System.clearProperty("lg.lgserverdisplay");
        // Either the inherited DISPLAY or null; the contract is only that it
        // resolves without throwing, mirroring launchExternal.
        ChildProcessLauncher.lg3dDisplay();
    }

    @Test
    @DisplayName("start rejects an impossible executable")
    void startPropagatesFailure() {
        List<String> cmd = List.of("/definitely/not/a/real/binary-xyz");
        assertThrows(Exception.class, () -> ChildProcessLauncher.start(cmd, null));
    }

    // ------------------------------------------------------------------
    // Plain-Swing profile
    // ------------------------------------------------------------------

    @Test
    @DisplayName("swingJvmArgs bounds the heap and carries no Prism pipeline")
    void swingJvmArgsDefault() {
        System.clearProperty(ChildProcessLauncher.SWING_XMX_PROPERTY);
        List<String> args = ChildProcessLauncher.swingJvmArgs();
        assertEquals(List.of("-Xmx256m"), args);
    }

    @Test
    @DisplayName("swingJvmArgs honours the heap override")
    void swingJvmArgsOverride() {
        System.setProperty(ChildProcessLauncher.SWING_XMX_PROPERTY, "128m");
        assertTrue(ChildProcessLauncher.swingJvmArgs().contains("-Xmx128m"));
    }

    @Test
    @DisplayName("jvmArgsFor marks the child isolated and adds the browser pipeline")
    void jvmArgsForBrowser() {
        System.clearProperty(ChildProcessLauncher.XMX_PROPERTY);
        System.clearProperty(ChildProcessLauncher.PRISM_ORDER_PROPERTY);
        List<String> args =
                ChildProcessLauncher.jvmArgsFor(ChildProcessLauncher.ChildProfile.BROWSER);
        assertEquals("-D" + ChildProcessLauncher.ISOLATED_CHILD_PROPERTY + "=true",
                args.get(0), "the isolated-child marker leads the argument list");
        assertTrue(args.containsAll(ChildProcessLauncher.browserJvmArgs()),
                "the browser profile arguments are appended");
        assertTrue(args.contains("-Xmx512m"));
    }

    @Test
    @DisplayName("jvmArgsFor for a Swing child omits the Prism/WebKit pipeline")
    void jvmArgsForSwing() {
        System.clearProperty(ChildProcessLauncher.SWING_XMX_PROPERTY);
        List<String> args =
                ChildProcessLauncher.jvmArgsFor(ChildProcessLauncher.ChildProfile.SWING);
        assertEquals("-D" + ChildProcessLauncher.ISOLATED_CHILD_PROPERTY + "=true",
                args.get(0));
        assertTrue(args.contains("-Xmx256m"), "the bounded Swing heap is present");
        assertTrue(args.stream().noneMatch(a -> a.startsWith("-Dprism.order=")),
                "a Swing child gets no JavaFX pipeline");
    }

    @Test
    @DisplayName("lgSystemPropertyArgs propagates lg.* and the jar-path allow-list")
    void lgSystemPropertyArgsPropagatesConfiguration() {
        System.setProperty("lg.test.isolation.probe", "hello world");
        System.setProperty("swingide.jar", "/opt/swing-ide.jar");
        try {
            List<String> args = ChildProcessLauncher.lgSystemPropertyArgs();
            assertTrue(args.contains("-Dlg.test.isolation.probe=hello world"),
                    "an lg.* property is carried to the child verbatim");
            assertTrue(args.contains("-Dswingide.jar=/opt/swing-ide.jar"),
                    "a helper jar path is carried to the child");
            assertTrue(args.stream().noneMatch(a -> a.startsWith("-Djava.class.path=")),
                    "the classpath is passed via -cp, never as a -D property");
        } finally {
            System.clearProperty("lg.test.isolation.probe");
            System.clearProperty("swingide.jar");
        }
    }

    @Test
    @DisplayName("lgSystemPropertyArgs is deterministic across calls")
    void lgSystemPropertyArgsIsSorted() {
        assertEquals(ChildProcessLauncher.lgSystemPropertyArgs(),
                ChildProcessLauncher.lgSystemPropertyArgs(),
                "the sorted key order makes the child command line reproducible");
    }

    @Test
    @DisplayName("swingEnv adds the display and no WebKit workarounds")
    void swingEnvAddsDisplayOnly() {
        Map<String, String> env = ChildProcessLauncher.swingEnv(null, ":0");
        assertEquals(":0", env.get("DISPLAY"));
        assertFalse(env.containsKey("WEBKIT_DISABLE_COMPOSITING_MODE"),
                "a Swing child needs no WebKit workaround");
    }

    @Test
    @DisplayName("swingEnv never overwrites a base variable")
    void swingEnvBaseWins() {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("DISPLAY", ":9");
        base.put("ALL_PROXY", "socks5h://127.0.0.1:9050");
        Map<String, String> env = ChildProcessLauncher.swingEnv(base, ":0");
        assertEquals(":9", env.get("DISPLAY"), "the base display wins");
        assertEquals("socks5h://127.0.0.1:9050", env.get("ALL_PROXY"),
                "the privacy-critical Tor env is preserved");
    }

    @Test
    @DisplayName("envFor dispatches to the browser or the Swing environment")
    void envForDispatchesOnProfile() {
        Map<String, String> browser = ChildProcessLauncher.envFor(
                ChildProcessLauncher.ChildProfile.BROWSER, null, ":0");
        assertEquals("1", browser.get("WEBKIT_DISABLE_COMPOSITING_MODE"));
        Map<String, String> swing = ChildProcessLauncher.envFor(
                ChildProcessLauncher.ChildProfile.SWING, null, ":0");
        assertFalse(swing.containsKey("WEBKIT_DISABLE_COMPOSITING_MODE"));
        assertEquals(":0", swing.get("DISPLAY"));
    }
}
