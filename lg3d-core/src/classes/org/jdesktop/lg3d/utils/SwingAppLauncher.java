/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.utils;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a conventional Swing application's {@code main} inside the lg3d desktop
 * JVM so that every top-level window it creates is seen by the global
 * {@code SwingNodeWindowCapture} hook and integrated into the 3D desktop - its
 * {@code JFrame}s become real desktop windows and its {@code JOptionPane} /
 * {@code JFileChooser} / popup dialogs render in-scene - with NO changes to the
 * application.
 *
 * <p>{@code main} is invoked reflectively on a dedicated non-EDT thread (the
 * same arrangement {@code AppLaunchAction} uses for {@code java <class>} app
 * commands), so the app is free to construct its UI via
 * {@code SwingUtilities.invokeLater} / {@code EventQueue.invokeLater} as usual.
 *
 * <p>Because the app shares the desktop JVM, a call to {@code System.exit} would
 * tear down the whole desktop. Conventional apps launched this way should
 * dispose their frames rather than exit; closing the last captured frame simply
 * removes its desktop window.
 */
public final class SwingAppLauncher {

    private static final Logger logger = Logger.getLogger("lg.util");

    private SwingAppLauncher() {
    }

    /**
     * Launches the application named by the {@code lg.swingapp} system property,
     * if set. The property is a whitespace-separated command whose first token is
     * the fully-qualified main class and whose remaining tokens (if any) are the
     * arguments passed to {@code main}. No-op when the property is absent or
     * blank. Safe to call once at desktop start-up.
     */
    public static void launchFromProperties() {
        String command = System.getProperty("lg.swingapp");
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        String[] tokens = command.trim().split("\\s+");
        String mainClass = tokens[0];
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);
        launch(mainClass, args);
    }

    /**
     * Reflectively invokes {@code main(String[])} on {@code mainClass} on a
     * dedicated non-EDT thread inside this JVM.
     *
     * @param mainClass the fully-qualified class exposing a {@code main} method
     * @param args      the arguments to pass to {@code main} (never null)
     */
    public static void launch(final String mainClass, final String[] args) {
        if (mainClass == null || mainClass.trim().isEmpty()) {
            return;
        }
        // Opt this app into 3D window capture so its JFrames are presented as
        // desktop windows (see SwingNodeWindowCapture.registerCapturePackage).
        org.jdesktop.lg3d.wg.internal.swingnode.SwingNodeWindowCapture
                .registerCapturePackage(mainClass);
        final String[] safeArgs = (args == null) ? new String[0] : args;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Class<?> cls = Class.forName(mainClass);
                    Method main = cls.getMethod("main", String[].class);
                    main.invoke(null, (Object) safeArgs);
                } catch (ClassNotFoundException cnfe) {
                    logger.log(Level.WARNING,
                            "SwingAppLauncher: main class not found on the "
                            + "desktop classpath: " + mainClass, cnfe);
                } catch (Exception ex) {
                    logger.log(Level.WARNING,
                            "SwingAppLauncher: failed to start " + mainClass, ex);
                }
            }
        }, "SwingAppLauncher-" + mainClass);
        // Not a daemon: a conventional app may spawn its own threads and we do
        // not want this launcher thread to be killed while the app initialises.
        t.setDaemon(false);
        t.start();
    }
}
