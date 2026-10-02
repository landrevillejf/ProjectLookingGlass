package org.jdesktop.lg3d.apps.dockermanager;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * 3D-desktop entry point for the Docker Manager.
 *
 * <p>Hosts the Swing {@link DockerManagementPanel} on a {@code SwingNode}
 * inside a {@code Frame3D} via {@link TitledSwingWindow}, exactly like every
 * other hosted lg3d application. The same panel is reused by the 2D/Swing
 * desktop through {@code Desktop2DAppRegistry.PANEL_APPS}.</p>
 */
public class DockerManager {

    /** Preferred width of the hosted panel, in pixels. */
    public static final int WIDTH_PX = 1024;
    /** Preferred height of the hosted panel, in pixels. */
    public static final int HEIGHT_PX = 768;

    public static void main(String[] args) {
        new DockerManager();
    }

    public DockerManager() {
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Docker Manager", new DockerManagementPanel(), WIDTH_PX, HEIGHT_PX);
    }
}
