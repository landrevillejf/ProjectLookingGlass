/**
 * Project Looking Glass
 *
 * $RCSfile: Main.java,v $
 *
 * Copyright (c) 2005, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 *
 * $Revision: 1.17 $
 * $Date: 2006-08-15 19:07:01 $
 * $State: Exp $
 */

package org.jdesktop.lg3d.displayserver;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.LogManager;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.displayserver.socketconnector.ServerHandler;
import org.jdesktop.lg3d.displayserver.SplashWindow;

/**
 * The main class of LG3D DisplayServer.
 */
public final class Main {
    /**
    * The entry point of LG3D DisplayServer.
    */
    public static void main(String[] args) {
        Logger logger = Logger.getLogger("lg.displayserver");
        Locale locale = Locale.getDefault();
        
        StringBuffer logMsg = new StringBuffer("\n");
        logMsg.append("\tLG3D Version              : " + LgBuildInfo.getVersion() + "\n");
        logMsg.append("\tLG3D Build Time           : " + LgBuildInfo.getBuildDate() + "(" 
                                                       + LgBuildInfo.getBuildTimeStamp() + ")\n");
        logMsg.append("\tLG3D Build Type           : " + LgBuildInfo.getBuildType() + "\n");
        logMsg.append("\tLG3D Java Version         : " + LgBuildInfo.getJavaVersion() + "\n");
        logMsg.append("\n");
        logMsg.append("\tJava Version              : " + System.getProperty("java.version") + "\n");
        logMsg.append("\tJava Vendor               : " + System.getProperty("java.vendor") + "\n");
        logMsg.append("\tJava Class Version        : " + System.getProperty("java.class.version") + "\n");
        logMsg.append("\tJava Class Path           : " + System.getProperty("java.class.path") + "\n");
        logMsg.append("\tApp Codebase              : " + System.getProperty("lg.appcodebase") + "\n");
        logMsg.append("\n");
        logMsg.append("\tOS Name                   : " + System.getProperty("os.name") + "\n");
        logMsg.append("\tOS Arch                   : " + System.getProperty("os.arch") + "\n");
        logMsg.append("\tOS Version                : " + System.getProperty("os.version") + "\n");
        logMsg.append("\n");
        logMsg.append("\tDef. Locale Language Code : " + locale.getLanguage() + "\n");
        logMsg.append("\tDef. Locale Country Code  : " + locale.getCountry() + "\n");
        
        logger.info(logMsg.toString());
               
	// if the logger config file is not set, then read in the config file
        if (System.getProperty("java.util.logging.config.file") == null) {
            try {
                String etcDir = System.getProperty("lg.etcdir");
                
                if (etcDir!=null) {
                    BufferedInputStream in;
                    if (etcDir.startsWith("http") || etcDir.startsWith("file:")) {
                        URL url = new URL(etcDir+"lg3d/logging.properties");
                        url.openConnection();
                        in = new BufferedInputStream(url.openStream());
                    } else {
                        File file = new File( etcDir+"lg3d/logging.properties");
                        in = new BufferedInputStream(new FileInputStream( file ));
                    }
                    LogManager.getLogManager().readConfiguration(in);     
                }
            } catch (Exception ioe) {
                logger.log(Level.WARNING, "Could not load the logging.properties file:", ioe);
            }
        }
        
        String compileJavaVersion = LgBuildInfo.getJavaVersion();
        String currentJavaVersion = System.getProperty("java.version");
        
        if (!compileJavaVersion.substring(0,5).equals(currentJavaVersion.substring(0,5))) {
//            logger.severe("Java Version mismatch !\nProject Looking Glass was compiled with Java version "
//                    +compileJavaVersion+" but the current Java version is "+currentJavaVersion+"\n"+
//                    "Please upgrade to version "+compileJavaVersion+" or newer.");
            throw new SevereRuntimeError("Java Version mismatch !\nProject Looking Glass was compiled with Java version "
                    +compileJavaVersion+" but the current Java version is "+currentJavaVersion+"\n"+
                    "Please upgrade to version "+compileJavaVersion+" or newer.");
        }
        
        // NOTE: the legacy build popped a modal "works best with JDK 1.6
        // (Mustang)" dialog here for any non-1.6 JVM. That nag is obsolete on a
        // modern JDK and, being modal, would block startup, so it is dropped in
        // the JDK 21 port.

        // Boot the 3D display server, unless this machine cannot render 3D.
        // DesktopMode.probe() checks both that Java 3D is present and new enough
        // (the pickfast package, shipped since the 1.5 build 2 release this
        // desktop has always required) and that a 3D-capable graphics
        // configuration exists; startDesktop() then either continues with the 3D
        // boot or hands over to the conventional Swing (2D) desktop.
        DesktopMode.Capability capability = DesktopMode.probe();
        String fwsMode = System.getProperty(DesktopMode.MODE_PROPERTY);
        if (startDesktop(fwsMode, capability, logger)) {
            return;   // the 2D desktop took over; nothing more to boot here
        }

        try {
            (new SplashStarter()).start();
            new ServerHandler();
            SplashWindow.destroySplashscreen();
        } catch (Throwable t) {
            // The probe passed but the display server still could not come up
            // (typically a GL context that only fails once it is created). Offer
            // the 2D desktop instead of leaving a dead JVM behind.
            DesktopMode.Capability failed = new DesktopMode.Capability(
                    capability.isJava3dPresent(), false,
                    "The 3D display server failed to start:\n" + t);
            logger.log(Level.SEVERE, "3D display server failed to start", t);
            if (startDesktop(fwsMode, failed, logger)) {
                return;
            }
            ErrorDialog.showGenericText = false;
            throw new SevereRuntimeError(failed.getReason(), t);
        }
    }

    /**
     * Resolves which desktop to run and starts the 2D one when that is the
     * outcome.
     *
     * @return true if the conventional Swing desktop was started, in which case
     *         the caller must not continue booting the 3D display server
     * @throws SevereRuntimeError if 3D was demanded (or the user declined the
     *         2D fallback) and 3D is not available - the historical behaviour
     */
    private static boolean startDesktop(String fwsMode,
                                        DesktopMode.Capability capability,
                                        Logger logger) {
        DesktopMode.Mode mode = DesktopMode.resolve(fwsMode, capability);
        if (mode == DesktopMode.Mode.THREE_D) {
            if (!capability.is3dAvailable()) {
                // lg.fws.mode=3d: fail loudly, exactly as before the 2D desktop.
                ErrorDialog.showGenericText = false;
                throw new SevereRuntimeError(capability.getReason());
            }
            return false;
        }
        if (DesktopMode.requiresConfirmation(fwsMode, capability)
                && !confirmFallback(capability.getReason())) {
            ErrorDialog.showGenericText = false;
            throw new SevereRuntimeError(capability.getReason());
        }
        logger.log(Level.WARNING, "Starting the 2D desktop: {0}",
                capability.getReason());
        Desktop2D.start();
        return true;
    }

    /**
     * Asks whether to fall back to the 2D desktop. Only reached when the
     * fallback was not explicitly requested via {@code lg.fws.mode=2d}.
     */
    private static boolean confirmFallback(String reason) {
        final String message = "3D is not available on this system.\n\n"
                + reason + "\n\n"
                + "Start Project Looking Glass in 2D mode instead?\n"
                + "The 2D desktop runs the conventional Swing applications;\n"
                + "applications that need Java 3D stay disabled.";
        final int[] answer = { JOptionPane.CLOSED_OPTION };
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    answer[0] = JOptionPane.showOptionDialog(null, message,
                            "3D unavailable",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE, null,
                            new Object[] { "Start in 2D mode", "Exit" },
                            "Start in 2D mode");
                }
            });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            Logger.getLogger("lg.displayserver").log(Level.WARNING,
                    "Could not ask about the 2D fallback", e);
            return false;
        }
        return answer[0] == JOptionPane.YES_OPTION;
    }
}
