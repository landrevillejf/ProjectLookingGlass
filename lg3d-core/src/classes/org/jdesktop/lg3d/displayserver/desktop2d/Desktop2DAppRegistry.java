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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JComponent;
import org.jdesktop.lg3d.utils.system.ProcessRunner;

/**
 * Knows which of the desktop's applications the 2D desktop can run, and how.
 *
 * <p>Every start-menu descriptor carries a command string in one of the forms
 * the 3D desktop understands (see {@code AppLaunchAction}):</p>
 * <ul>
 *  <li>{@code java <mainClass> [args]} - runs the class's {@code main} inside
 *      the desktop JVM. Most of these build a Java 3D window
 *      ({@code Frame3D}/{@code Component3D}) and therefore cannot run without
 *      3D; the handful whose user interface is a plain Swing panel are hosted
 *      here in an internal frame instead.</li>
 *  <li>{@code swingapp <mainClass> [args]} - same, plus 3D window capture. The
 *      capture step is meaningless in 2D, so only the in-JVM launch is done.</li>
 *  <li>anything else - an external executable ({@code firefox}, {@code xterm},
 *      {@code javaws ...}), started as a child process exactly as in 3D.</li>
 * </ul>
 *
 * <p>The Swing panels are looked up reflectively by name: lg3d-core cannot
 * depend on lg3d-apps, and on a machine without 3D the app's wrapper class
 * (which builds the {@code Frame3D}) must never be loaded - only its panel.</p>
 */
public final class Desktop2DAppRegistry {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** The {@code java <class>} command verb. */
    private static final String JAVA_VERB = "java ";

    /** The {@code swingapp <class>} command verb (3D window capture + launch). */
    private static final String SWINGAPP_VERB = "swingapp ";

    /** Tooltip shown on menu entries the 2D desktop cannot run. */
    public static final String UNAVAILABLE_TOOLTIP =
            "Requires the 3D desktop";

    /** How the 2D desktop can run a given command. */
    public enum Kind {
        /** A plain Swing panel; hosted in a desktop internal frame. */
        PANEL,
        /** A conventional Swing app that shows its own JFrame. */
        SWING_FRAME,
        /** An external executable; started as a child process. */
        EXTERNAL,
        /** Needs Java 3D (or is otherwise unusable in 2D). */
        UNAVAILABLE
    }

    /**
     * {@code java <mainClass>} commands whose UI is a plain Swing panel, mapped
     * to that panel class. The panel is constructed instead of the app's
     * wrapper {@code main}, which would build a 3D window.
     */
    private static final Map<String, String> PANEL_APPS;

    /** Panel apps whose constructor takes the initial directory (may be null). */
    private static final Set<String> PANEL_APPS_TAKING_DIR;

    /**
     * {@code java}/{@code swingapp} commands that are conventional Swing apps
     * showing their own top-level window. They run beside the 2D desktop rather
     * than inside it (an MDI frame cannot adopt a {@code JFrame}).
     */
    private static final Set<String> SWING_FRAME_APPS;

    static {
        Map<String, String> panels = new LinkedHashMap<>();
        panels.put("org.jdesktop.lg3d.apps.filemanager.FileManager",
                "org.jdesktop.lg3d.apps.filemanager.FileManagerPanel");
        panels.put("org.jdesktop.lg3d.apps.taskmanager.TaskManager",
                "org.jdesktop.lg3d.apps.taskmanager.TaskManagerPanel");
        panels.put("org.jdesktop.lg3d.apps.controlcenter.ControlCenter",
                "org.jdesktop.lg3d.apps.controlcenter.ControlCenterPanel");
        panels.put("org.jdesktop.lg3d.apps.calculator.Calculator",
                "org.jdesktop.lg3d.apps.calculator.CalculatorPanel");
        panels.put("org.jdesktop.lg3d.apps.mediawriter.MediaWriter",
                "org.jdesktop.lg3d.apps.mediawriter.MediaWriterPanel");
        // The Help Center is a JavaHelp (javax.help) JHelp viewer inside a plain
        // Swing panel, so it hosts here as an internal frame just like the other
        // panel apps; the 3D desktop launches the same panel on a SwingNode via
        // its HelpCenter wrapper (the javahelp jar is on both classpaths).
        panels.put("org.jdesktop.lg3d.apps.help.HelpCenter",
                "org.jdesktop.lg3d.apps.help.HelpCenterPanel");
        // The widget gallery lives in lg3d-widgets (not lg3d-apps); its
        // Swing panel is the pure-2D counterpart of the 3D WidgetGallery. Both
        // jars are on the desktop classpath, so the reflective lookup resolves,
        // and hosting the panel means the gallery no longer needs the 3D desktop.
        panels.put("org.jdesktop.lg3d.widgets.gallery.WidgetGallery",
                "org.jdesktop.lg3d.widgets.swing.WidgetGalleryPanel");
        // The LPM Console lives in the standalone lpm-console module (a plain
        // Swing package-manager front-end that shells out to /usr/bin/lpm), not
        // in lg3d-apps. Its jar is on the desktop run classpath, so the
        // reflective lookup resolves and its panel is hosted as an internal
        // frame here; in the 3D desktop the same command is captured via the
        // swingapp verb. Both jars being present is what makes this work.
        panels.put("org.lpmconsole.LPMConsole",
                "org.lpmconsole.LPMConsolePanel");
        // The Software Update app (lg3d-apps, org.jdesktop.lg3d.apps.update)
        // wraps the standalone update-manager module's Swing pipeline in a plain
        // panel, so it hosts here as an internal frame like the other panel apps;
        // the 3D desktop builds the same panel on a SwingNode via its
        // UpdateManager wrapper. Both the lg3d-apps and update-manager jars
        // (plus jackson/slf4j/bouncycastle) are on the desktop run classpath, so
        // the reflective lookup resolves.
        panels.put("org.jdesktop.lg3d.apps.update.UpdateManager",
                "org.jdesktop.lg3d.apps.update.UpdateManagerPanel");
        // The Database Manager app (lg3d-apps, org.jdesktop.lg3d.apps.dbmanager)
        // wraps the standalone db-manager module's JDBC client in a plain Swing
        // panel, so it hosts here as an internal frame like the other panel apps;
        // the 3D desktop builds the same panel on a SwingNode via its DbManager
        // wrapper. Both the lg3d-apps and db-manager jars (plus jackson/slf4j and
        // the bundled JDBC drivers) are on the desktop run classpath, so the
        // reflective lookup resolves.
        panels.put("org.jdesktop.lg3d.apps.dbmanager.DbManager",
                "org.jdesktop.lg3d.apps.dbmanager.DbManagerPanel");
        // The Office-group native-3D apps (lg3d-incubator) each ship a plain
        // Swing panel that reuses the same AWT-free model and shared user
        // Preferences store as the 3D app, so the one start-menu descriptor
        // (keyed here on the 3D main class) launches the panel as an MDI frame
        // in the 2D/Swing desktop while the 3D desktop keeps building the
        // Frame3D. The incubator jar is on the desktop run classpath, so the
        // reflective lookup resolves, and none of these panels loads Java 3D.
        panels.put("org.jdesktop.lg3d.apps.mail.Mail3D",
                "org.jdesktop.lg3d.apps.mail.MailPanel");
        panels.put("org.jdesktop.lg3d.apps.orgchart.ui.agenda.Agenda3D",
                "org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaPanel");
        panels.put("org.jdesktop.lg3d.apps.orgchart.ui.contact.Contact3D",
                "org.jdesktop.lg3d.apps.orgchart.ui.contact.ContactCardsPanel");
        panels.put("org.jdesktop.lg3d.apps.orgchart.ui.chart.Chart3D",
                "org.jdesktop.lg3d.apps.orgchart.ui.chart.ChartPanel");
        // The Games-group native-3D apps (lg3d-incubator) each ship a plain
        // Swing panel that reuses the same AWT-free game engine (minimax /
        // generator-solver / negamax / Klondike) as the 3D app, so the one
        // start-menu descriptor (keyed here on the 3D main class) launches the
        // panel as an MDI frame in the 2D/Swing desktop while the 3D desktop
        // keeps building the Frame3D. The incubator jar is on the desktop run
        // classpath, so the reflective lookup resolves, and none of these
        // panels loads Java 3D.
        panels.put("org.jdesktop.lg3d.apps.games.tictactoe.TicTacToe3D",
                "org.jdesktop.lg3d.apps.games.tictactoe.TicTacToePanel");
        panels.put("org.jdesktop.lg3d.apps.games.sudoku.Sudoku3D",
                "org.jdesktop.lg3d.apps.games.sudoku.SudokuPanel");
        panels.put("org.jdesktop.lg3d.apps.games.chess.Chess3D",
                "org.jdesktop.lg3d.apps.games.chess.ChessPanel");
        panels.put("org.jdesktop.lg3d.apps.games.solitaire.Solitaire3D",
                "org.jdesktop.lg3d.apps.games.solitaire.SolitairePanel");
        PANEL_APPS = Collections.unmodifiableMap(panels);

        Set<String> withDir = new LinkedHashSet<>();
        withDir.add("org.jdesktop.lg3d.apps.filemanager.FileManager");
        PANEL_APPS_TAKING_DIR = Collections.unmodifiableSet(withDir);

        Set<String> frames = new LinkedHashSet<>();
        frames.add("org.jdesktop.lg3d.apps.paint.PaintApp");
        frames.add("org.jdesktop.lg3d.apps.swingtest.TestFrame");
        frames.add("org.jdesktop.lg3d.apps.screencapture.ScreenCaptureConfigFrame");
        SWING_FRAME_APPS = Collections.unmodifiableSet(frames);
    }

    private Desktop2DAppRegistry() {
        // no instances
    }

    /** How the 2D desktop can run {@code command}. */
    public static Kind classify(String command) {
        if (command == null || command.isBlank()) {
            return Kind.UNAVAILABLE;
        }
        String trimmed = command.trim();
        if (trimmed.startsWith(JAVA_VERB) || trimmed.startsWith(SWINGAPP_VERB)
                || trimmed.equals("java") || trimmed.equals("swingapp")) {
            String mainClass = mainClass(trimmed);
            if (mainClass == null) {
                // A bare verb with no class name is a malformed descriptor; it
                // must not be mistaken for an external "java" executable.
                return Kind.UNAVAILABLE;
            }
            if (PANEL_APPS.containsKey(mainClass)) {
                return Kind.PANEL;
            }
            if (SWING_FRAME_APPS.contains(mainClass)) {
                return Kind.SWING_FRAME;
            }
            return Kind.UNAVAILABLE;
        }
        return Kind.EXTERNAL;
    }

    /** The panel class a {@link Kind#PANEL} command is hosted from, else null. */
    public static String panelClass(String command) {
        return (command == null) ? null : PANEL_APPS.get(mainClass(command.trim()));
    }

    /** The main class of a {@code java}/{@code swingapp} command, else null. */
    public static String mainClass(String command) {
        if (command == null) {
            return null;
        }
        String rest = stripVerb(command.trim());
        if (rest == null || rest.isBlank()) {
            return null;
        }
        String[] tokens = rest.trim().split("\\s+");
        return (tokens.length == 0 || tokens[0].isEmpty()) ? null : tokens[0];
    }

    /** Everything after the main class of an in-JVM command (may be empty). */
    public static String arguments(String command) {
        if (command == null) {
            return "";
        }
        String rest = stripVerb(command.trim());
        if (rest == null) {
            return "";
        }
        String main = mainClass(command);
        if (main == null) {
            return "";
        }
        int idx = rest.indexOf(main);
        String tail = rest.substring(idx + main.length()).trim();
        return tail;
    }

    private static String stripVerb(String command) {
        if (command.startsWith(SWINGAPP_VERB)) {
            return command.substring(SWINGAPP_VERB.length());
        }
        if (command.startsWith(JAVA_VERB)) {
            return command.substring(JAVA_VERB.length());
        }
        if (command.equals("java") || command.equals("swingapp")) {
            return "";
        }
        return null;
    }

    /**
     * True if an {@link Kind#EXTERNAL} command's executable is on the PATH. The
     * 3D start menu drops entries whose executable is missing; the 2D menu does
     * the same so it never offers a button that cannot work.
     */
    public static boolean isExternalAvailable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String executable = command.trim().split("\\s+")[0];
        return ProcessRunner.isAvailable(executable);
    }

    // ------------------------------------------------------------------
    // Launching
    // ------------------------------------------------------------------

    /**
     * Builds the Swing panel for a {@link Kind#PANEL} command.
     *
     * @param command    the descriptor command
     * @param initialDir the directory to open, or null for the app's default
     * @throws ReflectiveOperationException if the panel class cannot be built
     */
    public static JComponent createPanel(String command, Path initialDir)
            throws ReflectiveOperationException {
        String panelClassName = panelClass(command);
        if (panelClassName == null) {
            throw new ReflectiveOperationException(
                    "Not a panel application: " + command);
        }
        Class<?> panelClass = Class.forName(panelClassName);
        Object panel;
        if (PANEL_APPS_TAKING_DIR.contains(mainClass(command))) {
            Constructor<?> ctor = panelClass.getConstructor(Path.class);
            panel = ctor.newInstance(initialDir);
        } else {
            panel = panelClass.getDeclaredConstructor().newInstance();
        }
        return (JComponent) panel;
    }

    /**
     * Wires the panel's optional "Close" toolbar button to {@code onClose}, if
     * the panel has one ({@code setOnClose(Runnable)}). Absent by design on
     * panels that rely on the window decoration's close button.
     */
    public static void setCloseCallback(JComponent panel, Runnable onClose) {
        if (panel == null || onClose == null) {
            return;
        }
        try {
            Method setter = panel.getClass().getMethod("setOnClose", Runnable.class);
            setter.invoke(panel, onClose);
        } catch (NoSuchMethodException nsme) {
            // This panel has no close button of its own; the frame's does.
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not wire the close button of "
                    + panel.getClass().getName(), e);
        }
    }

    /**
     * Runs a {@link Kind#SWING_FRAME} app's {@code main} inside this JVM on its
     * own thread, as {@code AppLaunchAction} does in the 3D desktop. The
     * {@code swingapp} verb's 3D window capture is deliberately skipped: there
     * is no scene to capture into.
     */
    public static void launchSwingFrame(final String command) {
        final String main = mainClass(command);
        final String args = arguments(command);
        if (main == null) {
            logger.log(Level.WARNING, "No main class in command: {0}", command);
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> cls = Class.forName(main);
                    Method mainMethod = cls.getMethod("main", String[].class);
                    mainMethod.invoke(null, (Object) new String[] { args });
                } catch (ClassNotFoundException cnfe) {
                    logger.log(Level.WARNING,
                            "Application class not found: " + main, cnfe);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to start: " + main, e);
                }
            }
        }, "2D app launcher: " + main);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Starts an {@link Kind#EXTERNAL} command as a child process on the lg3d
     * display, mirroring the 3D desktop's launcher.
     *
     * @return true if the process was started
     */
    public static boolean launchExternal(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        List<String> commandArgs = new ArrayList<>();
        for (String token : command.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                commandArgs.add(token);
            }
        }
        String displayName = System.getProperty("lg.lgserverdisplay");
        if (displayName == null) {
            displayName = System.getenv("DISPLAY");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(commandArgs);
            pb.redirectErrorStream(true);
            if (displayName != null) {
                pb.environment().put("DISPLAY", displayName);
            }
            Process process = pb.start();
            drainOutput(command, process);
            return true;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not start: " + command, e);
            return false;
        }
    }

    /** Consumes (and logs) a child process's merged output so it never blocks. */
    private static void drainOutput(final String command, Process process) {
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line = reader.readLine();
                    while (line != null) {
                        logger.log(Level.INFO, "Output from {0}: {1}",
                                new Object[] { command, line });
                        line = reader.readLine();
                    }
                } catch (IOException e) {
                    logger.log(Level.FINE,
                            "Error reading output of " + command, e);
                } finally {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        // nothing useful to do
                    }
                }
            }
        }, "2D process output: " + command);
        thread.setDaemon(true);
        thread.start();
    }
}
