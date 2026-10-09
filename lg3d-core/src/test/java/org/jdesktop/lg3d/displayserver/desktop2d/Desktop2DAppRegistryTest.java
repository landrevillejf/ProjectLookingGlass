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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JPanel;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
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
    @DisplayName("the panel apps are hosted inside the desktop")
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
                "java org.jdesktop.lg3d.apps.archive.Archive"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.mediawriter.MediaWriter"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.firewall.Firewall"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.ssh.SshSwingClient"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.videoconference.VideoConference"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.messenger.Messenger"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.backup.Backup"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.audioplayer.AudioPlayer"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.videoplayer.VideoPlayer"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.imageeditor.ImageEditor"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.photoviewer.PhotoViewer"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.recorder.Recorder"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.tuner.Tuner"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.securitycenter.SecurityCenter"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.passwordmanager.PasswordManager"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.vpn.Vpn"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.gitgui.GitGui"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.webbrowser.WebBrowser"));
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
        // The OpenAPI Contract Editor launcher likewise forks an external fat jar
        // as a child process and owns no panel, so it is classified the same way.
        assertEquals(Kind.SWING_FRAME, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.openapieditor.OpenApiEditor"));
        // The PayloadMan launcher likewise forks the external tests-suite API
        // testing tool's fat jar as a child process and owns no panel, so it is
        // classified the same way.
        assertEquals(Kind.SWING_FRAME, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.payloadman.PayloadMan"));
    }

    @Test
    @DisplayName("pure-3D apps (and unknown java classes) are unavailable")
    void pure3dAppsAreUnavailable() {
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
        assertEquals("org.jdesktop.lg3d.apps.archive.ArchivePanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.archive.Archive"));
        assertEquals("org.jdesktop.lg3d.apps.taskmanager.TaskManagerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.taskmanager.TaskManager"));
        assertEquals("org.jdesktop.lg3d.apps.controlcenter.ControlCenterPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.controlcenter.ControlCenter"));
        assertEquals("org.jdesktop.lg3d.apps.mediawriter.MediaWriterPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.mediawriter.MediaWriter"));
        assertEquals("org.jdesktop.lg3d.apps.firewall.FirewallPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.firewall.Firewall"));
        // A non-panel command has no panel class.
        assertNull(Desktop2DAppRegistry.panelClass(
                "swingapp org.jdesktop.lg3d.apps.paint.PaintApp"));
        assertNull(Desktop2DAppRegistry.panelClass("firefox"));
        assertNull(Desktop2DAppRegistry.panelClass(null));
    }

    @Test
    @DisplayName("the Office-group 3D apps map to their 2D Swing panels")
    void officeAppsAreHostedPanels() {
        // The three Office start-menu apps are pure-3D in the 3D desktop but ship
        // an AWT/Swing panel (in lg3d-incubator) for the 2D/Swing desktop, keyed
        // on the 3D main class so the one shared descriptor serves both.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.mail.Mail3D"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.orgchart.ui.agenda.Agenda3D"));
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.orgchart.ui.chart.Chart3D"));

        assertEquals("org.jdesktop.lg3d.apps.mail.MailPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.mail.Mail3D"));
        assertEquals("org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.orgchart.ui.agenda.Agenda3D"));
        assertEquals("org.jdesktop.lg3d.apps.orgchart.ui.chart.ChartPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.orgchart.ui.chart.Chart3D"));
        // The legacy read-only Contact 3D card browser was replaced by the
        // production Contacts app and is no longer hosted.
        assertNull(Desktop2DAppRegistry.panelClass(
                "java org.jdesktop.lg3d.apps.orgchart.ui.contact.Contact3D"));
    }

    @Test
    @DisplayName("the Contacts address book maps to its 2D Swing panel")
    void contactsIsHostedPanel() {
        // The Contacts app (lg3d-apps) is the production address book over the
        // shared ~/.lg3d/contacts JSON store; the same ContactsPanel serves both
        // desktops, keyed on the Contacts main class the descriptor launches.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.contacts.Contacts"));
        assertEquals("org.jdesktop.lg3d.apps.contacts.ContactsPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.contacts.Contacts"));
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

    @Test
    @DisplayName("the Periodic Table 3D app maps to its 2D Swing panel")
    void periodicTableIsHostedPanel() {
        // The Periodic Table is pure-3D in the 3D desktop but ships a plain
        // Swing reference panel (in lg3d-incubator) for the 2D/Swing desktop,
        // keyed on the 3D main class so the one shared descriptor serves both.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.periodictable.PeriodicTable3D"));
        assertEquals("org.jdesktop.lg3d.apps.periodictable.PeriodicTablePanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.periodictable.PeriodicTable3D"));
    }

    @Test
    @DisplayName("Image Studio maps to its 2D Swing panel")
    void imageStudioIsHostedPanel() {
        // Image Studio is native-3D in the 3D desktop but ships a plain Swing
        // editing panel (in lg3d-incubator) reusing the same JAI engine, keyed
        // on the 3D entry class so the one shared descriptor serves both.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.imagestudio.ImageStudioApp"));
        assertEquals("org.jdesktop.lg3d.apps.imagestudio.ImageStudioPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.imagestudio.ImageStudioApp"));
    }

    @Test
    @DisplayName("the Weather app maps to its 2D Swing panel")
    void weatherIsHostedPanel() {
        // The Weather app (lg3d-apps) is a plain Swing Open-Meteo reader: in the
        // 3D desktop its wrapper hosts the panel on a SwingNode, and here the
        // very same panel opens as an MDI internal frame keyed on the 3D main
        // class so the one shared descriptor serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.weather.Weather"));
        assertEquals("org.jdesktop.lg3d.apps.weather.WeatherPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.weather.Weather"));
    }

    @Test
    @DisplayName("the PDF Viewer maps to its Swing reader panel")
    void pdfViewerIsHostedPanel() {
        // The PDF Viewer is a Swing document reader (Apache PDFBox); the 3D
        // desktop hosts the panel on a SwingNode via its PdfViewer wrapper while
        // the 2D/Swing desktop opens the same panel as an MDI internal frame.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.pdfviewer.PdfViewer"));
        assertEquals("org.jdesktop.lg3d.apps.pdfviewer.PdfViewerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.pdfviewer.PdfViewer"));
    }

    @Test
    @DisplayName("the Advanced Text Editor maps to its Swing panel")
    void textEditorIsHostedPanel() {
        // The Advanced Text Editor is a plain Swing multi-tab editor over
        // AWT-free engine classes; the 3D desktop hosts the panel on a
        // SwingNode via its AdvancedTextEditor wrapper while the 2D/Swing
        // desktop opens the same panel as an MDI internal frame.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.texteditor.AdvancedTextEditor"));
        assertEquals(
                "org.jdesktop.lg3d.apps.texteditor.AdvancedTextEditorPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.texteditor.AdvancedTextEditor"));
    }

    @Test
    @DisplayName("the Video Conference app maps to its Swing panel")
    void videoConferenceIsHostedPanel() {
        // The Video Conference app (lg3d-apps) is a Jitsi Meet client: a plain
        // Swing lobby/launcher that hands the WebRTC session to the browser. In
        // the 3D desktop its VideoConference wrapper hosts the panel on a
        // SwingNode, and here the very same panel opens as an MDI internal frame
        // keyed on the 3D main class so the one shared descriptor serves both.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.videoconference.VideoConference"));
        assertEquals("org.jdesktop.lg3d.apps.videoconference.VideoConferencePanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.videoconference.VideoConference"));
    }

    @Test
    @DisplayName("the Instant Messenger app maps to its Swing panel")
    void messengerIsHostedPanel() {
        // The Instant Messenger app (lg3d-apps) is a multi-protocol chat client:
        // a plain Swing accounts/conversations/transcript UI over a pluggable
        // ProtocolRegistry (native IRC plus deep-link bridges). In the 3D desktop
        // its Messenger wrapper hosts the panel on a SwingNode, and here the very
        // same panel opens as an MDI internal frame keyed on the 3D main class so
        // the one shared descriptor serves both.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.messenger.Messenger"));
        assertEquals("org.jdesktop.lg3d.apps.messenger.MessengerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.messenger.Messenger"));
    }

    @Test
    @DisplayName("the Backup tool maps to its Swing panel")
    void backupIsHostedPanel() {
        // The Backup tool (lg3d-apps) is a plain Swing backup/restore UI over an
        // AWT-free ZIP engine (profiles, glob excludes, Zip-Slip-hardened
        // restore). In the 3D desktop its Backup wrapper hosts the panel on a
        // SwingNode, and here the very same panel opens as an MDI internal
        // frame keyed on the 3D main class so the one shared descriptor serves
        // both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.backup.Backup"));
        assertEquals("org.jdesktop.lg3d.apps.backup.BackupPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.backup.Backup"));
    }

    @Test
    @DisplayName("the Audio Player maps to its Swing panel")
    void audioPlayerIsHostedPanel() {
        // The Audio Player (lg3d-apps) is a plain Swing library/transport UI
        // over an AWT-free seam that plays the JDK-native formats in process and
        // hands MP3, other codecs and every stream to a real external player. In
        // the 3D desktop its AudioPlayer wrapper hosts the panel on a SwingNode,
        // and here the very same panel opens as an MDI internal frame keyed on
        // the 3D main class so the one shared descriptor serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.audioplayer.AudioPlayer"));
        assertEquals("org.jdesktop.lg3d.apps.audioplayer.AudioPlayerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.audioplayer.AudioPlayer"));
    }

    @Test
    @DisplayName("the Video Player maps to its Swing panel")
    void videoPlayerIsHostedPanel() {
        // The Video Player (lg3d-apps) is a plain Swing VLC-style front-end over
        // an AWT-free seam that hands every file, stream or disc to a real
        // external player. In the 3D desktop its VideoPlayer wrapper hosts the
        // panel on a SwingNode, and here the very same panel opens as an MDI
        // internal frame keyed on the 3D main class so the one shared descriptor
        // serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.videoplayer.VideoPlayer"));
        assertEquals("org.jdesktop.lg3d.apps.videoplayer.VideoPlayerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.videoplayer.VideoPlayer"));
    }

    @Test
    @DisplayName("the Image Editor maps to its Swing panel")
    void imageEditorIsHostedPanel() {
        // The Image Editor (lg3d-apps) is a plain Swing GIMP-style layer/tool/
        // filter workspace over an AWT-free Java 2D model. In the 3D desktop its
        // ImageEditor wrapper hosts the panel on a SwingNode, and here the very
        // same panel opens as an MDI internal frame keyed on the 3D main class so
        // the one shared descriptor serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.imageeditor.ImageEditor"));
        assertEquals("org.jdesktop.lg3d.apps.imageeditor.ImageEditorPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.imageeditor.ImageEditor"));
    }

    @Test
    @DisplayName("the Photo Viewer maps to its Swing panel")
    void photoViewerIsHostedPanel() {
        // The Photo Viewer (lg3d-apps) is a plain Swing tagged gallery over an
        // AWT-free PhotoLibrary / PhotoItem model decoded with ImageIO. In the 3D
        // desktop its PhotoViewer wrapper hosts the panel on a SwingNode, and
        // here the very same panel opens as an MDI internal frame keyed on the 3D
        // main class so the one shared descriptor serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.photoviewer.PhotoViewer"));
        assertEquals("org.jdesktop.lg3d.apps.photoviewer.PhotoViewerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.photoviewer.PhotoViewer"));
    }

    @Test
    @DisplayName("the Recorder maps to its Swing panel")
    void recorderIsHostedPanel() {
        // The Recorder (lg3d-apps) records the microphone natively to WAV and
        // hands screen capture to an external ffmpeg, over an AWT-free model. In
        // the 3D desktop its Recorder wrapper hosts the panel on a SwingNode, and
        // here the very same panel opens as an MDI internal frame keyed on the 3D
        // main class so the one shared descriptor serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.recorder.Recorder"));
        assertEquals("org.jdesktop.lg3d.apps.recorder.RecorderPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.recorder.Recorder"));
    }

    @Test
    @DisplayName("the Tuner maps to its Swing panel")
    void tunerIsHostedPanel() {
        // The Tuner (lg3d-apps) analyses the microphone natively with an
        // in-process YIN pitch detector over an AWT-free PitchDetector / Note /
        // Tuning model - no external tool, no recording. In the 3D desktop its
        // Tuner wrapper hosts the panel on a SwingNode, and here the very same
        // panel opens as an MDI internal frame keyed on the 3D main class so the
        // one shared descriptor serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.tuner.Tuner"));
        assertEquals("org.jdesktop.lg3d.apps.tuner.TunerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.tuner.Tuner"));
    }

    @Test
    @DisplayName("the Security Center maps to its Swing panel")
    void securityCenterIsHostedPanel() {
        // The Security Center (lg3d-apps) is an antivirus / host-posture
        // front-end that delegates scanning to an installed ClamAV over an
        // AWT-free seam. In the 3D desktop its SecurityCenter wrapper hosts the
        // panel on a SwingNode, and here the very same panel opens as an MDI
        // internal frame keyed on the 3D main class so the one shared descriptor
        // serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.securitycenter.SecurityCenter"));
        assertEquals("org.jdesktop.lg3d.apps.securitycenter.SecurityCenterPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.securitycenter.SecurityCenter"));
    }

    @Test
    @DisplayName("the Password Manager maps to its Swing panel")
    void passwordManagerIsHostedPanel() {
        // The Password Manager (lg3d-apps) is an encrypted vault front-end over
        // an AWT-free PBKDF2 + AES-GCM seam (no key material touches disk). In
        // the 3D desktop its PasswordManager wrapper hosts the panel on a
        // SwingNode, and here the very same panel opens as an MDI internal frame
        // keyed on the 3D main class so the one shared descriptor serves both
        // desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.passwordmanager.PasswordManager"));
        assertEquals("org.jdesktop.lg3d.apps.passwordmanager.PasswordManagerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.passwordmanager.PasswordManager"));
    }

    @Test
    @DisplayName("the VPN client maps to its Swing panel")
    void vpnIsHostedPanel() {
        // The VPN client (lg3d-apps) is a tunnel front-end that delegates the
        // connection to an installed tool (nmcli / openvpn / wg-quick) over an
        // AWT-free seam. In the 3D desktop its Vpn wrapper hosts the panel on a
        // SwingNode, and here the very same panel opens as an MDI internal frame
        // keyed on the 3D main class so the one shared descriptor serves both
        // desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.vpn.Vpn"));
        assertEquals("org.jdesktop.lg3d.apps.vpn.VpnPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.vpn.Vpn"));
    }

    @Test
    @DisplayName("the Git GUI maps to its Swing client panel")
    void gitGuiIsHostedPanel() {
        // The Git GUI (lg3d-apps) is a GitKraken / GitHub Desktop-style client:
        // a plain Swing changes/staging/commit/branches/history/diff UI over an
        // AWT-free GitRepository seam that shells out to the system git. In the
        // 3D desktop its GitGui wrapper hosts the panel on a SwingNode, and here
        // the very same panel opens as an MDI internal frame keyed on the 3D main
        // class so the one shared descriptor serves both desktops.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.gitgui.GitGui"));
        assertEquals("org.jdesktop.lg3d.apps.gitgui.GitGuiPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.gitgui.GitGui"));
    }

    @Test
    @DisplayName("the Web Browser maps to its interactive JavaFX panel")
    void webBrowserIsHostedPanel() {
        // The Web Browser (lg3d-apps) is a JavaFX WebView (WebKit) browser. The
        // two desktops deliberately differ: in the 2D/Swing desktop this command
        // opens the real interactive BrowserPanel (a JFXPanel-hosted WebView) as
        // an MDI internal frame, where the heavyweight JavaFX peer composites
        // correctly; the 3D desktop instead shows a pure-Swing static preview
        // whose button spawns the browser into a child-process JVM. Only the 2D
        // panel is registered here, keyed on the shared descriptor's main class.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.webbrowser.WebBrowser"));
        assertEquals("org.jdesktop.lg3d.apps.webbrowser.BrowserPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.webbrowser.WebBrowser"));
    }

    @Test
    @DisplayName("the Docker Manager maps to its ported plugin panel")
    void dockerManagerIsHostedPanel() {
        // The Docker Manager (lg3d-apps) is a verbatim port of the Swing IDE
        // docker plugin's DockerManagementPanel (terminal / containers / files /
        // images over the system docker CLI). The same panel serves both desktops:
        // here as an MDI internal frame, in 3D on a SwingNode via its DockerManager
        // wrapper. Only the panel is registered, keyed on the descriptor's class.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.dockermanager.DockerManager"));
        assertEquals("org.jdesktop.lg3d.apps.dockermanager.DockerManagementPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.dockermanager.DockerManager"));
    }

    @Test
    @DisplayName("the Remote Viewer maps to its Swing panel")
    void remoteViewerIsHostedPanel() {
        // The Remote Viewer (lg3d-apps) is the ported jrdesktop RMI remote-desktop
        // tool: its RemoteViewerPanel main GUI is hosted on a SwingNode by the
        // RemoteViewer wrapper in the 3D desktop, and here the very same panel
        // opens as an MDI internal frame keyed on the 3D main class so the one
        // shared descriptor serves both desktops. Because it runs inside the
        // desktop JVM, its Exit button must close the window (setOnClose), never
        // System.exit(0).
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.remoteviewer.RemoteViewer"));
        assertEquals("org.jdesktop.lg3d.apps.remoteviewer.RemoteViewerPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.remoteviewer.RemoteViewer"));
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
    @DisplayName("Advanced Search is hosted as a panel, not gated on 3D")
    void searchIsAPanelApp() {
        // The Search app is a pure Swing panel over the headless lg3d-core
        // search engine, so it runs in the 2D desktop like any other panel app;
        // the 3D desktop builds the same panel on a SwingNode via its Search
        // wrapper. It is the target of the Ctrl+Shift+F accelerator.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.search.Search"));
        assertEquals("org.jdesktop.lg3d.apps.search.SearchPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.search.Search"));
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
    @DisplayName("FTP Client is hosted as a panel, not gated on 3D")
    void ftpClientIsAPanelApp() {
        // The FTP Client wraps the ftp-client module's file-transfer client in a
        // plain Swing panel, so it runs in the 2D desktop like any other panel
        // app; the 3D desktop builds the same panel on a SwingNode via its
        // FtpClient wrapper.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.ftpclient.FtpClient"));
        assertEquals("org.jdesktop.lg3d.apps.ftpclient.FtpClientPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.ftpclient.FtpClient"));
    }

    @Test
    @DisplayName("the About window is hosted as a panel, not gated on 3D")
    void aboutIsAPanelApp() {
        // The About window renders a plain Swing panel (product identity,
        // resolved version, host runtime facts, attribution / licence), so it
        // runs in the 2D desktop like any other panel app; the 3D desktop
        // builds the same panel on a SwingNode via its About wrapper.
        assertEquals(Kind.PANEL, Desktop2DAppRegistry.classify(
                "java org.jdesktop.lg3d.apps.about.About"));
        assertEquals("org.jdesktop.lg3d.apps.about.AboutPanel",
                Desktop2DAppRegistry.panelClass(
                        "java org.jdesktop.lg3d.apps.about.About"));
    }

    @Test
    @DisplayName("the unavailable tooltip explains the 3D requirement")
    void unavailableTooltip() {
        assertEquals("Requires the 3D desktop",
                Desktop2DAppRegistry.UNAVAILABLE_TOOLTIP);
    }

    @Test
    @DisplayName("showDate navigates a panel that has a jumpToDate hook")
    void showDateNavigatesDatePanels() {
        DatePanel panel = new DatePanel();
        LocalDate date = LocalDate.of(2026, 3, 20);
        Desktop2DAppRegistry.showDate(panel, date);
        assertEquals(date, panel.shown);
    }

    @Test
    @DisplayName("showDate tolerates null and panels without the hook")
    void showDateIsSafe() {
        Desktop2DAppRegistry.showDate(null, LocalDate.now());
        Desktop2DAppRegistry.showDate(new DatePanel(), null);
        // A plain JPanel has no jumpToDate(LocalDate): the reflective lookup
        // must swallow the NoSuchMethodException instead of propagating it.
        Desktop2DAppRegistry.showDate(new JPanel(), LocalDate.now());
    }

    @Test
    @DisplayName("openFile hands a document to a panel with an openFile(File) hook")
    void openFileUsesTheFileHook() {
        DocumentPanel panel = new DocumentPanel();
        File file = new File("/tmp/paper.pdf");
        assertTrue(Desktop2DAppRegistry.openFile(panel, file));
        assertEquals(file, panel.opened);
    }

    @Test
    @DisplayName("openFile falls back to an openFile(Path) hook")
    void openFileUsesThePathHook() {
        PathDocumentPanel panel = new PathDocumentPanel();
        File file = new File("/tmp/paper.pdf");
        assertTrue(Desktop2DAppRegistry.openFile(panel, file));
        assertEquals(file.toPath(), panel.opened);
    }

    @Test
    @DisplayName("openFile reports a panel that declines the document")
    void openFilePropagatesAFalseResult() {
        assertFalse(Desktop2DAppRegistry.openFile(new DecliningPanel(),
                new File("/tmp/paper.pdf")));
    }

    @Test
    @DisplayName("openFile tolerates null and panels without the hook")
    void openFileIsSafe() {
        File file = new File("/tmp/paper.pdf");
        assertFalse(Desktop2DAppRegistry.openFile(null, file));
        assertFalse(Desktop2DAppRegistry.openFile(new DocumentPanel(), null));
        // A plain JPanel has no openFile(File)/openFile(Path): the reflective
        // lookup must swallow the NoSuchMethodException instead of throwing.
        assertFalse(Desktop2DAppRegistry.openFile(new JPanel(), file));
    }

    /** A stand-in for a date-navigable panel (the real one is AgendaPanel). */
    public static class DatePanel extends JPanel {
        LocalDate shown;

        public void jumpToDate(LocalDate date) {
            this.shown = date;
        }
    }

    /** A stand-in for a document panel (the real one is PdfViewerPanel). */
    public static class DocumentPanel extends JPanel {
        File opened;

        public boolean openFile(File file) {
            this.opened = file;
            return true;
        }
    }

    /** A stand-in for a panel exposing only the {@code openFile(Path)} form. */
    public static class PathDocumentPanel extends JPanel {
        Path opened;

        public boolean openFile(Path path) {
            this.opened = path;
            return true;
        }
    }

    /** A stand-in for a panel that reports it could not open the document. */
    public static class DecliningPanel extends JPanel {
        public boolean openFile(File file) {
            return false;
        }
    }
}
