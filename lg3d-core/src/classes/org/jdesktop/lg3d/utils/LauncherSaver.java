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
package org.jdesktop.lg3d.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for saving user-created application launchers as .lgcfg descriptor files.
 * 
 * <p>Launchers are saved to the user's config directory (~/.config/lg3d/launchers/)
 * and are automatically discovered by the desktop's start menu on the next restart.</p>
 */
public final class LauncherSaver {

    private static final Logger logger = Logger.getLogger("lg.launcher");

    /** Directory where user launchers are stored. */
    private static final String LAUNCHERS_DIR = ".config/lg3d/launchers";

    private LauncherSaver() {
        // no instances
    }

    /**
     * Saves a launcher configuration as a .lgcfg file.
     *
     * @param name        the launcher display name
     * @param description the launcher description
     * @param command     the command to execute (e.g., "java org.example.MyApp")
     * @param iconPath    the icon path (classpath resource or file path), or null
     * @param menuGroup   the menu group (e.g., "Applications", "Utilities")
     * @return the path to the saved file, or null if saving failed
     */
    public static Path saveLauncher(String name, String description, String command,
                                     String iconPath, String menuGroup) {
        if (name == null || name.isBlank()) {
            logger.warning("Cannot save launcher: name is required");
            return null;
        }
        if (command == null || command.isBlank()) {
            logger.warning("Cannot save launcher: command is required");
            return null;
        }

        try {
            Path launchersDir = getLaunchersDirectory();
            if (launchersDir == null) {
                return null;
            }

            // Sanitize filename: replace spaces with underscores, remove special chars
            String filename = name.replaceAll("[^a-zA-Z0-9_-]", "_") + ".lgcfg";
            Path launcherFile = launchersDir.resolve(filename);

            String iconUrl = (iconPath != null && !iconPath.isBlank())
                    ? iconPath
                    : "resource:///resources/images/icon/application.png";

            String xml = buildLgcfgXml(name, description, command, iconUrl,
                    menuGroup != null ? menuGroup : "Applications");

            try (BufferedWriter writer = Files.newBufferedWriter(launcherFile)) {
                writer.write(xml);
            }

            logger.info("Saved launcher: " + launcherFile);
            return launcherFile;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save launcher: " + name, e);
            return null;
        }
    }

    /**
     * Returns the directory where user launchers are stored, creating it if needed.
     *
     * @return the launchers directory, or null if it cannot be created
     */
    private static Path getLaunchersDirectory() throws IOException {
        Path homeDir = Paths.get(System.getProperty("user.home"));
        Path launchersDir = homeDir.resolve(LAUNCHERS_DIR);

        if (!Files.exists(launchersDir)) {
            Files.createDirectories(launchersDir);
            logger.info("Created launchers directory: " + launchersDir);
        }

        return launchersDir;
    }

    /**
     * Builds the XML content for a .lgcfg descriptor file.
     */
    private static String buildLgcfgXml(String name, String description, String command,
                                         String iconUrl, String menuGroup) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<java version=\"1.5.0\" class=\"java.beans.XMLDecoder\">\n");
        xml.append(" <!-- User-created launcher -->\n");
        xml.append(" <object class=\"org.jdesktop.lg3d.scenemanager.utils.startmenu.StartMenuItemConfig\">\n");
        xml.append("  <void property=\"command\">\n");
        xml.append("   <string>").append(escapeXml(command)).append("</string>\n");
        xml.append("  </void>\n");
        xml.append("  <void property=\"desc\">\n");
        xml.append("   <string>").append(escapeXml(description != null ? description : "")).append("</string>\n");
        xml.append("  </void>\n");
        xml.append("  <void property=\"displayResourceType\">\n");
        xml.append("   <string>ICON</string>\n");
        xml.append("  </void>\n");
        xml.append("  <void property=\"displayResourceUrlName\">\n");
        xml.append("   <string>").append(escapeXml(iconUrl)).append("</string>\n");
        xml.append("  </void>\n");
        xml.append("  <void property=\"menuGroup\">\n");
        xml.append("   <string>").append(escapeXml(menuGroup)).append("</string>\n");
        xml.append("  </void>\n");
        xml.append("  <void property=\"name\">\n");
        xml.append("   <string>").append(escapeXml(name)).append("</string>\n");
        xml.append("  </void>\n");
        xml.append(" </object>\n");
        xml.append("</java>\n");
        return xml.toString();
    }

    /**
     * Escapes special XML characters.
     */
    private static String escapeXml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
