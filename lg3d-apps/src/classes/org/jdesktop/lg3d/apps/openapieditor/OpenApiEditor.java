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
package org.jdesktop.lg3d.apps.openapieditor;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;

/**
 * Start-Menu launcher for the external <em>OpenAPI Contract Editor</em>: a
 * full-featured YAML/OpenAPI specification editor (RSyntaxTextArea, swagger-parser
 * validation, OpenAPI Generator code generation, endpoint tree, structure navigator)
 * that ships as a self-contained fat jar ({@code libs/openapi-editor.jar}).
 *
 * <p>Like the IDE, the editor is <strong>not</strong> loaded into the desktop JVM.
 * This launcher starts it as a <em>separate</em> child process
 * ({@code java -jar libs/openapi-editor.jar}). That isolation is deliberate:</p>
 * <ul>
 *   <li>the editor calls {@code System.exit} when its main window closes
 *       ({@code EXIT_ON_CLOSE}), which would otherwise tear down the whole lg3d
 *       desktop; and</li>
 *   <li>its fat jar bundles unrelocated third-party libraries (snakeyaml,
 *       swagger-parser, openapi-generator, RSyntaxTextArea, Jackson&hellip;) that
 *       could clash with the desktop's own classpath.</li>
 * </ul>
 *
 * <p>Because it is a plain child process, the editor's own {@code JFrame} (menu bar
 * and all) appears as a normal top-level window on the lg3d display: a composited
 * native window over the 3D desktop, and an ordinary window beside the 2D/Swing
 * desktop. The start-menu descriptor registers this class with the {@code java <class>}
 * verb, so {@code AppLaunchAction} (3D) and {@code Desktop2DAppRegistry.launchSwingFrame}
 * (2D, via {@code SWING_FRAME_APPS}) both run {@link #main(String[])} in-JVM; the
 * only thing {@code main} does in that JVM is fork the child and return.</p>
 *
 * <p>The jar path is resolved without any shell expansion (lg3d's launcher splits
 * the command on whitespace and execs it directly), so it is looked up here against
 * absolute locations known at build time: the {@code openapieditor.jar} system
 * property (set by {@code :lg3d-core:run} and the release {@code lg3d.sh}), then
 * {@code <lg.appcodebase>/libs/openapi-editor.jar}, then the working directory.
 * If it cannot be found the launcher logs and, on a headed desktop, shows a
 * readable message instead of throwing.</p>
 */
public final class OpenApiEditor {

    /** Name of the vendored fat jar dropped into {@code libs/}. */
    static final String JAR_NAME = "openapi-editor.jar";

    /** System property carrying the jar's absolute path (set by the launchers). */
    static final String JAR_PROPERTY = "openapieditor.jar";

    /** System property holding the application codebase as an absolute file URL. */
    static final String APPCODEBASE_PROPERTY = "lg.appcodebase";

    /** Display the child editor is launched on when nothing better is known. */
    static final String DEFAULT_DISPLAY = ":0";

    private static final Logger logger = Logger.getLogger("lg.apps");

    private OpenApiEditor() {
        // no instances
    }

    /**
     * Forks the OpenAPI Contract Editor fat jar as a child process on the lg3d display.
     *
     * @param args ignored
     */
    public static void main(final String[] args) {
        File jar = resolveJar();
        if (jar == null) {
            reportMissingJar();
            return;
        }
        List<String> command = buildCommand(jar);
        String display = resolveDisplay();
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.environment().put("DISPLAY", display);
            logger.log(Level.INFO, "Launching OpenAPI Contract Editor: {0} (DISPLAY={1})",
                    new Object[] {command, display});
            Process process = builder.start();
            drainOutput(process);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not start OpenAPI Contract Editor: " + command, e);
            showMessage("Could not start the OpenAPI Contract Editor",
                    "The editor process failed to start:\n" + e.getMessage());
        }
    }

    /**
     * Resolves the fat jar against the current working directory.
     *
     * @return the jar file, or {@code null} when it cannot be found
     */
    static File resolveJar() {
        return resolveJar(new File(System.getProperty("user.dir")));
    }

    /**
     * Resolves the fat jar, in precedence order: the {@code openapieditor.jar}
     * system property (absolute), {@code <lg.appcodebase>/libs/openapi-editor.jar},
     * then the working-directory-relative {@code libs/} and {@code ../libs/}.
     *
     * @param cwd the directory the {@code libs/} fallbacks are resolved against
     * @return the jar file, or {@code null} when it cannot be found
     */
    static File resolveJar(final File cwd) {
        String property = System.getProperty(JAR_PROPERTY);
        if (property != null && !property.isBlank()) {
            File file = new File(property);
            if (file.isFile()) {
                return file.getAbsoluteFile();
            }
        }

        File codebase = appCodeBaseDir();
        if (codebase != null) {
            File file = new File(codebase, "libs" + File.separator + JAR_NAME);
            if (file.isFile()) {
                return file.getAbsoluteFile();
            }
        }

        if (cwd != null) {
            String[] relatives = {"libs" + File.separator + JAR_NAME,
                                  ".." + File.separator + "libs" + File.separator + JAR_NAME};
            for (String relative : relatives) {
                File file = new File(cwd, relative);
                if (file.isFile()) {
                    return file.getAbsoluteFile();
                }
            }
        }
        return null;
    }

    /**
     * Builds the child-process command: this JVM's own {@code java} binary,
     * {@code -jar}, and the resolved fat jar's absolute path.
     *
     * @param jar the fat jar to run
     * @return the argument list handed to {@link ProcessBuilder}
     */
    static List<String> buildCommand(final File jar) {
        String javaBin = System.getProperty("java.home")
                + File.separator + "bin" + File.separator + "java";
        List<String> command = new ArrayList<>(3);
        command.add(javaBin);
        command.add("-jar");
        command.add(jar.getAbsolutePath());
        return command;
    }

    /**
     * Resolves the X display to launch the editor on: the lg3d server display when
     * set, else the inherited {@code DISPLAY}, else {@value #DEFAULT_DISPLAY}.
     *
     * @return the display name, never {@code null}
     */
    static String resolveDisplay() {
        String server = System.getProperty("lg.lgserverdisplay");
        if (server != null && !server.isBlank()) {
            return server;
        }
        String env = System.getenv("DISPLAY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DEFAULT_DISPLAY;
    }

    /**
     * Reads {@code lg.appcodebase} (an absolute {@code file:} URL, or a plain
     * path) as a directory.
     *
     * @return the codebase directory, or {@code null} when unset or malformed
     */
    private static File appCodeBaseDir() {
        String value = System.getProperty(APPCODEBASE_PROPERTY);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.startsWith("file:")) {
                return new File(new java.net.URI(value));
            }
            return new File(value);
        } catch (URISyntaxException | IllegalArgumentException e) {
            logger.log(Level.FINE, "Ignoring malformed " + APPCODEBASE_PROPERTY
                    + ": " + value, e);
            return null;
        }
    }

    /** Consumes (and logs) the child's merged output so its pipe never fills. */
    private static void drainOutput(final Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    logger.log(Level.INFO, "openapi-editor: {0}", line);
                    line = reader.readLine();
                }
            } catch (IOException e) {
                logger.log(Level.FINE, "Ended openapi-editor output stream", e);
            }
        }, "openapi-editor output");
        thread.setDaemon(true);
        thread.start();
    }

    private static void reportMissingJar() {
        logger.warning("OpenAPI Contract Editor fat jar not found; set -Dopenapieditor.jar=<path> "
                + "or place it at libs/" + JAR_NAME + " (see :fetchOpenApiEditorJar).");
        showMessage("OpenAPI Contract Editor unavailable",
                "The OpenAPI Contract Editor jar (libs/" + JAR_NAME + ") was not found.\n"
                + "Build the OpenAPI-Contract-Editor project and copy its fat jar there, "
                + "or run the :fetchOpenApiEditorJar task.");
    }

    /** Shows a modal message, but only on a headed desktop (never headless/CI). */
    private static void showMessage(final String title, final String body) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JOptionPane.showMessageDialog(null, body, title, JOptionPane.WARNING_MESSAGE);
    }
}
