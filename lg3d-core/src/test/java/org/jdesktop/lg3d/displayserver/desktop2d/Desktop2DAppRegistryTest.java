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
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JPanel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DAppRegistry.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers how the 2D desktop classifies a start-menu command: which commands
 * become hosted Swing panels, which launch their own frame, which run as an
 * external process, and which are pure-3D and therefore disabled. The parsing
 * helpers ({@code mainClass}/{@code arguments}) and the launch guards are
 * checked too; the panels themselves are exercised at runtime (they live in
 * lg3d-apps, which lg3d-core must not depend on).
 */
class Desktop2DAppRegistryTest {

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the five panel apps are hosted inside the desktop")
    void panelAppsAreClassifiedAsPanel() {
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.filemanager.FileManager"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.taskmanager.TaskManager"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.controlcenter.ControlCenter"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.calculator.Calculator"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.mediawriter.MediaWriter"));
    }

    @Test
    @DisplayName("conventional Swing apps that own a JFrame launch beside it")
    void swingFrameApps() {
        assertEquals(Kind.SWING_FRAME, Desktop2DAppRegistry.classify(
                "swingapp org.jdesktop.lg3d.apps.paint.PaintApp"));
        assertEquals(Kind.SWING_FRAME, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.swingtest.TestFrame"));
        assertEquals(Kind.SWING_FRAME, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.screencapture.ScreenCaptureConfigFrame"));
        // The IDE launcher forks the external swing-ide jar as a child process;
        // it owns no panel, so the 2D desktop runs its main beside the desktop.
        assertEquals(Kind.SWING_FRAME, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.swingide.SwingIde"));
    }

    @Test
    @DisplayName("pure-3D apps (and unknown java classes) are unavailable")
    void pure3dAppsAreUnavailable() {
        assertEquals(Kind.UNAVAILABLE, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.imagestudio.ImageStudio"));
        assertEquals(Kind.UNAVAILABLE, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.demos.some.Demo"));
        assertEquals(Kind.UNAVAILABLE, Desktop2DAppRegistry.classify(
                "swingapp org.jdesktop.lg3d.apps.unknown.Thing"));
    }

    @Test
    @DisplayName("anything not java/swingapp is an external executable")
    void externalCommands() {
        assertEquals(Kind.EXTERNAL, Desktop2DAppRegistry.classify("firefox"));
        assertEquals(Kind.EXTERNAL, Desktop2DAppRegistry.classify("xterm -e top"));
        assertEquals(Kind.EXTERNAL,
                Desktop2DAppRegistry.classify("javaws http://host/app.jnlp"));
    }

    @Test
    @DisplayName("null, blank and verb-only commands are unavailable")
    void degenerateCommands() {
        assertEquals(Kind.UNAVAILABLE, Desktop2DAppRegistry.classify(null));
        assertEquals(Kind.UNAVAILABLE, Desktop2DAppRegistry.classify(""));
        assertEquals(Kind.UNAVAILABLE, Desktop2DAppRegistry.classify("   "));
        // "java " with no class name trims to the bare verb -> no main class.
        assertEquals(Kind.UNAVAILABLE, Desktop2DAppRegistry.classify("java "));
    }

    // ------------------------------------------------------------------
    // Command parsing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mainClass strips the verb and any trailing arguments")
    void parsesMainClass() {
        assertEquals("org.jdesktop.lg3d.apps.calculator.Calculator",
                Desktop2DAppRegistry.mainClass(
                        "java org.jdesktop.lg3d.apps.calculator.Calculator"));
        assertEquals("org.jdesktop.lg3d.apps.paint.PaintApp",
                Desktop2DAppRegistry.mainClass(
                        "swingapp org.jdesktop.lg3d.apps.paint.PaintApp"));
        assertEquals("org.foo.Bar",
                Desktop2DAppRegistry.mainClass("java org.foo.Bar a b"));
        assertNull(Desktop2DAppRegistry.mainClass(null));
        assertNull(Desktop2DAppRegistry.mainClass("firefox"),
                "an external command has no in-JVM main class");
    }

    @Test
    @DisplayName("arguments returns everything after the main class")
    void parsesArguments() {
        assertEquals("", Desktop2DAppRegistry.arguments(
                "java org.jdesktop.lg3d.apps.calculator.Calculator"));
        assertEquals("a b c",
                Desktop2DAppRegistry.arguments("java org.foo.Bar a b c"));
        assertEquals("", Desktop2DAppRegistry.arguments(null));
        assertEquals("", Desktop2DAppRegistry.arguments("firefox"));
    }

    @Test
    @DisplayName("panelClass maps each panel app to its Swing panel FQN")
    void mapsPanelClasses() {
        assertEquals("org.jdesktop.lg3d.apps.filemanager.FileManagerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.filemanager.FileManager"));
        assertEquals("org.jdesktop.lg3d.apps.calculator.CalculatorPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.calculator.Calculator"));
        assertEquals("org.jdesktop.lg3d.apps.taskmanager.TaskManagerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.taskmanager.TaskManager"));
        assertEquals("org.jdesktop.lg3d.apps.controlcenter.ControlCenterPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.controlcenter.ControlCenter"));
        assertEquals("org.jdesktop.lg3d.apps.mediawriter.MediaWriterPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.mediawriter.MediaWriter"));
        // A non-panel command has no panel class.
        assertNull(Desktop2DAppRegistry.panelClass(
                "swingapp org.jdesktop.lg3d.apps.paint.PaintApp"));
        assertNull(Desktop2DAppRegistry.panelClass("firefox"));
        assertNull(Desktop2DAppRegistry.panelClass(null));
    }

    @Test
    @DisplayName("the Office-group 3D apps map to their 2D Swing panels")
    void officeAppsAreHostedPanels() {
        // The four Office start-menu apps are pure-3D in the 3D desktop but ship
        // an AWT/Swing panel (in lg3d-incubator) for the 2D/Swing desktop, keyed
        // on the 3D main class so the one shared descriptor serves both.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.mail.Mail3D"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.orgchart.ui.agenda.Agenda3D"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.orgchart.ui.contact.Contact3D"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.orgchart.ui.chart.Chart3D"));

        assertEquals("org.jdesktop.lg3d.apps.mail.MailPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.mail.Mail3D"));
        assertEquals("org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.orgchart.ui.agenda.Agenda3D"));
        assertEquals("org.jdesktop.lg3d.apps.orgchart.ui.contact.ContactCardsPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.orgchart.ui.contact.Contact3D"));
        assertEquals("org.jdesktop.lg3d.apps.orgchart.ui.chart.ChartPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.orgchart.ui.chart.Chart3D"));
    }

    @Test
    @DisplayName("the Games-group 3D apps map to their 2D Swing panels")
    void gameAppsAreHostedPanels() {
        // The four Games start-menu apps are pure-3D in the 3D desktop but ship
        // an AWT/Swing panel (in lg3d-incubator) for the 2D/Swing desktop, keyed
        // on the 3D main class so the one shared descriptor serves both.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.games.tictactoe.TicTacToe3D"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.games.sudoku.Sudoku3D"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.games.chess.Chess3D"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.games.solitaire.Solitaire3D"));

        assertEquals("org.jdesktop.lg3d.apps.games.tictactoe.TicTacToePanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.games.tictactoe.TicTacToe3D"));
        assertEquals("org.jdesktop.lg3d.apps.games.sudoku.SudokuPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.games.sudoku.Sudoku3D"));
        assertEquals("org.jdesktop.lg3d.apps.games.chess.ChessPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.games.chess.Chess3D"));
        assertEquals("org.jdesktop.lg3d.apps.games.solitaire.SolitairePanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.games.solitaire.Solitaire3D"));
    }

    // ------------------------------------------------------------------
    // External availability
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isExternalAvailable resolves the executable token")
    void externalAvailability() {
        assertTrue(Desktop2DAppRegistry.isExternalAvailable("/bin/sh"),
                "/bin/sh exists on every supported host");
        assertTrue(Desktop2DAppRegistry.isExternalAvailable("/bin/sh -c echo hi"),
                "only the first token is checked");
        assertFalse(Desktop2DAppRegistry.isExternalAvailable(
                "/definitely/not/a/real/binary-xyz"));
        assertFalse(Desktop2DAppRegistry.isExternalAvailable(null));
        assertFalse(Desktop2DAppRegistry.isExternalAvailable("   "));
    }

    // ------------------------------------------------------------------
    // Launch guards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("createPanel rejects commands that are not panel apps")
    void createPanelRejectsNonPanel() {
        assertThrows(ReflectiveOperationException.class,
                () -> Desktop2DAppRegistry.createPanel("firefox", null));
        assertThrows(ReflectiveOperationException.class,
                () -> Desktop2DAppRegistry.createPanel(null, null));
    }

    @Test
    @DisplayName("createPanel fails cleanly when the panel class is absent")
    void createPanelMissingClass() {
        // lg3d-core does not (and must not) depend on lg3d-apps, so the
        // panel class is not on this test classpath: the reflective lookup must
        // surface a ReflectiveOperationException rather than an Error.
        assertThrows(ReflectiveOperationException.class,
                () -> Desktop2DAppRegistry.createPanel(
                        "java org.jdesktop.lg3d.apps.calculator.Calculator", null));
    }

    @Test
    @DisplayName("launchExternal returns false for empty or impossible commands")
    void launchExternalGuards() {
        assertFalse(Desktop2DAppRegistry.launchExternal(null));
        assertFalse(Desktop2DAppRegistry.launchExternal("   "));
        assertFalse(Desktop2DAppRegistry.launchExternal(
                "/definitely/not/a/real/binary-xyz"));
    }

    @Test
    @DisplayName("launchSwingFrame ignores a command with no main class")
    void launchSwingFrameIgnoresNullMain() {
        // Must not throw; a null main class is logged and dropped.
        Desktop2DAppRegistry.launchSwingFrame(null);
        Desktop2DAppRegistry.launchSwingFrame("firefox");
    }

    @Test
    @DisplayName("setCloseCallback tolerates null and panels without a hook")
    void setCloseCallbackIsSafe() {
        Runnable onClose = () -> { /* no-op */ };
        Desktop2DAppRegistry.setCloseCallback(null, onClose);
        Desktop2DAppRegistry.setCloseCallback(new JPanel(), null);
        // A plain JPanel has no setOnClose(Runnable): the reflective lookup must
        // swallow the NoSuchMethodException instead of propagating it.
        Desktop2DAppRegistry.setCloseCallback(new JPanel(), onClose);
    }

    @Test
    @DisplayName("the widget gallery is hosted as a panel, not gated on 3D")
    void widgetGalleryIsAPanelApp() {
        // The widgets are pure Swing under the hood, so the gallery runs in the
        // 2D desktop like any other panel app instead of demanding the 3D one.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.widgets.gallery.WidgetGallery"));
        assertEquals("org.jdesktop.lg3d.widgets.swing.WidgetGalleryPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.widgets.gallery.WidgetGallery"));
    }

    @Test
    @DisplayName("the Help Center is hosted as a panel, not gated on 3D")
    void helpCenterIsAPanelApp() {
        // The Help Center is a JavaHelp viewer inside a plain Swing panel, so it
        // runs in the 2D desktop like any other panel app; the 3D desktop builds
        // the same panel on a SwingNode via its HelpCenter wrapper.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.help.HelpCenter"));
        assertEquals("org.jdesktop.lg3d.apps.help.HelpCenterPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.help.HelpCenter"));
    }

    @Test
    @DisplayName("Software Update is hosted as a panel, not gated on 3D")
    void softwareUpdateIsAPanelApp() {
        // The Software Update app wraps the update-manager module's Swing
        // pipeline in a plain panel, so it runs in the 2D desktop like any other
        // panel app; the 3D desktop builds the same panel on a SwingNode via its
        // UpdateManager wrapper.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.update.UpdateManager"));
        assertEquals("org.jdesktop.lg3d.apps.update.UpdateManagerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.update.UpdateManager"));
    }

    @Test
    @DisplayName("Database Manager is hosted as a panel, not gated on 3D")
    void databaseManagerIsAPanelApp() {
        // The Database Manager wraps the db-manager module's JDBC client in a
        // plain Swing panel, so it runs in the 2D desktop like any other panel
        // app; the 3D desktop builds the same panel on a SwingNode via its
        // DbManager wrapper.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.dbmanager.DbManager"));
        assertEquals("org.jdesktop.lg3d.apps.dbmanager.DbManagerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.dbmanager.DbManager"));
    }

    @Test
    @DisplayName("the unavailable tooltip explains the 3D requirement")
    void unavailableTooltip() {
        assertEquals("Requires the 3D desktop",
                Desktop2DAppRegistry.UNAVAILABLE_TOOLTIP);
    }
}
