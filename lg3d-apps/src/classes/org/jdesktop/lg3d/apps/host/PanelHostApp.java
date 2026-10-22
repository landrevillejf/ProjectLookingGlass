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
package org.jdesktop.lg3d.apps.host;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DAppRegistry;

/**
 * The generic isolated-application host: a child-process {@code main} that builds
 * any leaf Swing panel reflectively and shows it in its own top-level
 * {@link JFrame}. This is the scalable half of the desktop's QNX-style process
 * isolation - where {@code WebBrowserApp} is a <em>bespoke</em> child for the one
 * app that needs JavaFX hardening, {@code PanelHostApp} isolates an
 * <em>ordinary</em> Swing panel with no per-app code at all.
 *
 * <p>The 2D desktop forks it through
 * {@link Desktop2DAppRegistry#panelHostMain}: a panel app registered as a
 * {@code CHILD_PROCESS} target runs
 * {@code PanelHostApp --panel <panelClass> [--title <name>] [--path <file>]}.
 * Running the panel in its own JVM means a {@code System.exit}, an OOM or a
 * native fault inside it can never take the desktop down; under the combined
 * launch ({@code run-lg3d.sh --iso}) the X11 compositor redirects this frame back
 * into an MDI window, so the app is both isolated and integrated. Because the
 * JVM exists solely to host the panel, closing the frame exits the process, so
 * the desktop's {@code AppProcessSupervisor} sees a clean stop and no zombie
 * lingers.</p>
 *
 * <p>The argument parsing and title derivation are pure and headless-tested; only
 * {@link #main} and the frame assembly touch a display.</p>
 */
public final class PanelHostApp {

    /** The flag naming the panel class to host. */
    static final String PANEL_FLAG = "--panel";

    /** The flag naming the window title (optional). */
    static final String TITLE_FLAG = "--title";

    /** The flag naming an initial directory or file to open (optional). */
    static final String PATH_FLAG = "--path";

    /** The {@code Panel} suffix stripped when deriving a title from a class name. */
    private static final String PANEL_SUFFIX = "Panel";

    private PanelHostApp() {
        // no instances
    }

    /**
     * The parsed host request: which panel to build, the window title (may be
     * null, in which case one is derived) and an optional initial path.
     */
    static final class HostSpec {

        private final String panelClassName;
        private final String title;
        private final Path path;

        HostSpec(final String panelClassName, final String title, final Path path) {
            this.panelClassName = panelClassName;
            this.title = title;
            this.path = path;
        }

        /** The panel's fully-qualified class name (never null). */
        String panelClassName() {
            return panelClassName;
        }

        /** The explicit window title, or null to derive one. */
        String title() {
            return title;
        }

        /** The initial directory/file to open, or null for the panel default. */
        Path path() {
            return path;
        }
    }

    /**
     * Parses the child's arguments. Recognises {@code --panel}, {@code --title}
     * and {@code --path}, each taking the following token as its value; unknown
     * tokens are ignored. A missing or blank {@code --panel} (the one required
     * value) yields null, so {@link #main} can fail honestly rather than build a
     * frame with nothing in it.
     *
     * @param args the child's arguments (may be null)
     * @return the parsed spec, or null when no panel class was given
     */
    static HostSpec parse(final String[] args) {
        if (args == null) {
            return null;
        }
        String panel = null;
        String title = null;
        String path = null;
        for (int i = 0; i < args.length; i++) {
            if (PANEL_FLAG.equals(args[i]) && i + 1 < args.length) {
                panel = args[++i];
            } else if (TITLE_FLAG.equals(args[i]) && i + 1 < args.length) {
                title = args[++i];
            } else if (PATH_FLAG.equals(args[i]) && i + 1 < args.length) {
                path = args[++i];
            }
        }
        if (panel == null || panel.isBlank()) {
            return null;
        }
        return new HostSpec(panel, title, (path == null) ? null : Paths.get(path));
    }

    /**
     * The window title for a spec: the explicit {@code --title} when present,
     * else one derived from the panel's class name.
     *
     * @param spec the parsed host request
     * @return the title to show (never null)
     */
    static String titleOf(final HostSpec spec) {
        if (spec.title() != null && !spec.title().isBlank()) {
            return spec.title();
        }
        return humanize(spec.panelClassName());
    }

    /**
     * Derives a human-readable title from a panel's class name: drops the package
     * and a trailing {@code Panel}, and separates the remaining camel-case humps
     * ({@code org.foo.CalculatorPanel} becomes {@code Calculator}).
     *
     * @param panelClassName the panel's fully-qualified class name
     * @return the derived title
     */
    static String humanize(final String panelClassName) {
        String simple = panelClassName.substring(panelClassName.lastIndexOf('.') + 1);
        if (simple.endsWith(PANEL_SUFFIX) && simple.length() > PANEL_SUFFIX.length()) {
            simple = simple.substring(0, simple.length() - PANEL_SUFFIX.length());
        }
        StringBuilder words = new StringBuilder(simple.length() + 8);
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                words.append(' ');
            }
            words.append(c);
        }
        return words.toString();
    }

    /**
     * Hosts the panel named by {@code args} in a top-level frame.
     *
     * @param args {@code --panel <class> [--title <name>] [--path <file>]}
     */
    public static void main(final String[] args) {
        installCrashHandler();
        final HostSpec spec = parse(args);
        if (spec == null) {
            System.err.println("PanelHostApp: --panel <panelClass> is required");
            // halt (not exit): a bad invocation must produce a non-zero status the
            // supervisor reads as a failure, without running shutdown hooks.
            Runtime.getRuntime().halt(2);
            return;
        }
        SwingUtilities.invokeLater(() -> createAndShow(spec));
    }

    /**
     * Installs a default uncaught-exception handler that stops the child
     * <em>non-zero</em>, so the desktop's {@code AppProcessSupervisor} sees a
     * crash and can offer a bounded restart instead of leaving a broken frame
     * alive. Mirrors {@code WebBrowserApp}'s handler.
     */
    private static void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("Panel host child fault on " + thread.getName());
            error.printStackTrace(System.err);
            Runtime.getRuntime().halt(1);
        });
    }

    /**
     * Builds the panel and shows the frame. A panel that cannot be constructed
     * (an absent class, a failing constructor) stops the child non-zero rather
     * than showing an empty frame, so the supervisor reports the failure.
     */
    private static void createAndShow(final HostSpec spec) {
        final JComponent panel;
        try {
            panel = Desktop2DAppRegistry.createPanelByClassName(
                    spec.panelClassName(), spec.path());
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("PanelHostApp: cannot build " + spec.panelClassName());
            e.printStackTrace(System.err);
            Runtime.getRuntime().halt(3);
            return;
        }
        final JFrame frame = new JFrame(titleOf(spec));
        // A panel with its own Close toolbar button closes this frame (and so the
        // child); a panel without one relies on the window decoration.
        Desktop2DAppRegistry.setCloseCallback(panel, frame::dispose);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(panel, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(final WindowEvent e) {
                // This JVM exists only to host the panel, so let it exit once the
                // frame is gone; a lingering EDT thread would otherwise keep the
                // process alive and the supervisor would think it still runs.
                System.exit(0);
            }
        });
        frame.setVisible(true);
    }
}
