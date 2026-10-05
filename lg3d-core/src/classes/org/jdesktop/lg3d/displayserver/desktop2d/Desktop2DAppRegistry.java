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
        // The About window (lg3d-apps, org.jdesktop.lg3d.apps.about) renders a
        // plain Swing panel of product identity, resolved version, host runtime
        // facts and the attribution / licence text, so it hosts here as an
        // internal frame like the other panel apps; the 3D desktop builds the
        // same panel on a SwingNode via its About wrapper. It touches no Java
        // 3D, so the 2D path never needs the scene graph.
        panels.put("org.jdesktop.lg3d.apps.about.About",
                "org.jdesktop.lg3d.apps.about.AboutPanel");
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
        // The FTP Client app (lg3d-apps, org.jdesktop.lg3d.apps.ftpclient)
        // wraps the standalone ftp-client module's file-transfer client in a
        // plain Swing panel, so it hosts here as an internal frame like the
        // other panel apps; the 3D desktop builds the same panel on a SwingNode
        // via its FtpClient wrapper. Both the lg3d-apps and ftp-client jars
        // (plus commons-net/jsch/jackson/slf4j/bouncycastle) are on the desktop
        // run classpath, so the reflective lookup resolves.
        panels.put("org.jdesktop.lg3d.apps.ftpclient.FtpClient",
                "org.jdesktop.lg3d.apps.ftpclient.FtpClientPanel");
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
        panels.put("org.jdesktop.lg3d.apps.orgchart.ui.chart.Chart3D",
                "org.jdesktop.lg3d.apps.orgchart.ui.chart.ChartPanel");
        // The Contacts address book (lg3d-apps, org.jdesktop.lg3d.apps.contacts)
        // is the production replacement for the legacy read-only Contact 3D
        // card browser (orgchart.ui.contact, demo contacts.xml): a full CRUD
        // manager over the shared JSON store in ~/.lg3d/contacts
        // (org.jdesktop.lg3d.contacts.ContactStore in lg3d-core) that the
        // Agenda, Messenger and Video Conference also read. In the 3D desktop
        // its Contacts wrapper hosts the panel on a SwingNode inside a Frame3D;
        // here the very same panel opens as an MDI internal frame. The lg3d-apps
        // jar is on the desktop run classpath, so the reflective lookup resolves
        // and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.contacts.Contacts",
                "org.jdesktop.lg3d.apps.contacts.ContactsPanel");
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
        // The Periodic Table (lg3d-incubator) ships a plain Swing reference
        // panel that renders the element grid without Java 3D, so the one
        // start-menu descriptor (keyed here on the 3D main class) launches the
        // panel as an MDI frame in the 2D/Swing desktop while the 3D desktop
        // keeps building the Frame3D. As with the games, the incubator jar is on
        // the desktop run classpath so the reflective lookup resolves, and the
        // panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.periodictable.PeriodicTable3D",
                "org.jdesktop.lg3d.apps.periodictable.PeriodicTablePanel");
        // The Weather app (lg3d-apps, org.jdesktop.lg3d.apps.weather) is an
        // idiomatic Swing current-conditions + forecast reader fed by the free
        // Open-Meteo API over the JDK java.net.http client (no third-party
        // library). In the 3D desktop its Weather wrapper hosts the panel on a
        // SwingNode inside a Frame3D via TitledSwingWindow; here the very same
        // panel opens as an MDI internal frame, so the reader is fully usable
        // without 3D. The lg3d-apps jar is on the desktop run classpath, so the
        // reflective lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.weather.Weather",
                "org.jdesktop.lg3d.apps.weather.WeatherPanel");
        // The PDF Viewer (lg3d-apps, org.jdesktop.lg3d.apps.pdfviewer) is an
        // idiomatic Swing document reader over Apache PDFBox. In the 3D desktop
        // its PdfViewer wrapper hosts the panel on a SwingNode inside a Frame3D;
        // here the very same panel opens as an MDI internal frame, so the reader
        // is fully usable without 3D. The lg3d-apps jar and the PDFBox jars are
        // on the desktop run classpath, so the reflective lookup resolves and
        // the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.pdfviewer.PdfViewer",
                "org.jdesktop.lg3d.apps.pdfviewer.PdfViewerPanel");
        // The Firewall app (lg3d-apps, org.jdesktop.lg3d.apps.firewall) is a
        // production firewall management application with a Swing panel showing
        // status, active rules, and enable/disable controls. In the 3D desktop
        // its Firewall wrapper hosts the panel on a SwingNode inside a Frame3D
        // via TitledSwingWindow; here the very same panel opens as an MDI
        // internal frame, so the firewall manager is fully usable without 3D.
        // The lg3d-apps jar is on the desktop run classpath, so the reflective
        // lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.firewall.Firewall",
                "org.jdesktop.lg3d.apps.firewall.FirewallPanel");


        panels.put("org.jdesktop.lg3d.apps.ssh.SshSwingClient",
                "org.jdesktop.lg3d.apps.ssh.SshPanel");
        // The Video Conference app (lg3d-apps,
        // org.jdesktop.lg3d.apps.videoconference) is a Jitsi Meet conference
        // client: a Swing lobby/address-book/launcher that builds the meeting
        // deep link and hands the real WebRTC audio/video session to the system
        // browser (or an external meeting command). In the 3D desktop its
        // VideoConference wrapper hosts the panel on a SwingNode inside a
        // Frame3D via TitledSwingWindow; here the very same panel opens as an MDI
        // internal frame, so the client is fully usable without 3D. The lg3d-apps
        // jar (with jackson/slf4j) is on the desktop run classpath, so the
        // reflective lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.videoconference.VideoConference",
                "org.jdesktop.lg3d.apps.videoconference.VideoConferencePanel");
        // The Instant Messenger app (lg3d-apps, org.jdesktop.lg3d.apps.messenger)
        // is a multi-protocol chat client: a Swing accounts/conversations/
        // transcript UI over a pluggable ProtocolRegistry (a fully native IRC
        // client plus deep-link bridges for XMPP/Matrix/Telegram/WhatsApp/Signal/
        // SMS/SIP). In the 3D desktop its Messenger wrapper hosts the panel on a
        // SwingNode inside a Frame3D via TitledSwingWindow; here the very same
        // panel opens as an MDI internal frame, so the messenger is fully usable
        // without 3D. The lg3d-apps jar (with jackson/slf4j) is on the desktop
        // run classpath, so the reflective lookup resolves and the panel loads no
        // Java 3D.
        panels.put("org.jdesktop.lg3d.apps.messenger.Messenger",
                "org.jdesktop.lg3d.apps.messenger.MessengerPanel");
        // The Backup tool (lg3d-apps, org.jdesktop.lg3d.apps.backup) archives
        // selected folders/files to standard ZIP backups and restores them: a
        // Swing profiles/sources/destination UI over an AWT-free BackupEngine
        // (Zip-Slip-hardened restore, atomic archive writes, glob excludes). In
        // the 3D desktop its Backup wrapper hosts the panel on a SwingNode
        // inside a Frame3D via TitledSwingWindow; here the very same panel opens
        // as an MDI internal frame, so backup/restore is fully usable without
        // 3D. The lg3d-apps jar (with jackson/slf4j) is on the desktop run
        // classpath, so the reflective lookup resolves and the panel loads no
        // Java 3D.
        panels.put("org.jdesktop.lg3d.apps.backup.Backup",
                "org.jdesktop.lg3d.apps.backup.BackupPanel");
        // The Audio Player (lg3d-apps, org.jdesktop.lg3d.apps.audioplayer) plays
        // music, internet radio and podcasts: a Swing library/transport UI over
        // an AWT-free seam that plays the JDK-native formats (WAV/AU/AIFF) in
        // process and hands MP3, other codecs and every network stream to a real
        // external player (mpv/mpg123/ffplay/vlc). In the 3D desktop its
        // AudioPlayer wrapper hosts the panel on a SwingNode inside a Frame3D via
        // TitledSwingWindow; here the very same panel opens as an MDI internal
        // frame, so the player is fully usable without 3D. The lg3d-apps jar
        // (with jackson/slf4j) is on the desktop run classpath, so the reflective
        // lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.audioplayer.AudioPlayer",
                "org.jdesktop.lg3d.apps.audioplayer.AudioPlayerPanel");
        // The Video Player (lg3d-apps, org.jdesktop.lg3d.apps.videoplayer) is a
        // VLC-style front-end: a Swing library/launcher UI over an AWT-free seam
        // that hands every file, stream or disc to a real external player
        // (vlc/mpv/mplayer/totem/ffplay), since the JDK has no video decoder. In
        // the 3D desktop its VideoPlayer wrapper hosts the panel on a SwingNode
        // inside a Frame3D via TitledSwingWindow; here the very same panel opens
        // as an MDI internal frame, so the player is fully usable without 3D.
        // The lg3d-apps jar (with jackson/slf4j) is on the desktop run classpath,
        // so the reflective lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.videoplayer.VideoPlayer",
                "org.jdesktop.lg3d.apps.videoplayer.VideoPlayerPanel");
        // The Image Editor (lg3d-apps, org.jdesktop.lg3d.apps.imageeditor) is a
        // GIMP-style layer/tool/filter workspace built entirely on plain Java 2D
        // (no codec, no Java 3D): a Swing canvas + layers panel + filter/undo bar
        // over an AWT-free EditorDocument / ToolEngine / FilterEngine / UndoStack.
        // In the 3D desktop its ImageEditor wrapper hosts the panel on a SwingNode
        // inside a Frame3D via TitledSwingWindow; here the very same panel opens as
        // an MDI internal frame, so the editor is fully usable without 3D. The
        // lg3d-apps jar is on the desktop run classpath, so the reflective lookup
        // resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.imageeditor.ImageEditor",
                "org.jdesktop.lg3d.apps.imageeditor.ImageEditorPanel");
        // The Photo Viewer (lg3d-apps, org.jdesktop.lg3d.apps.photoviewer) is a
        // tagged gallery built entirely on plain Java 2D / ImageIO (no codec, no
        // Java 3D): a Swing thumbnail grid + preview + tag/rating editor over an
        // AWT-free PhotoLibrary / PhotoItem model persisted as JSON. In the 3D
        // desktop its PhotoViewer wrapper hosts the panel on a SwingNode inside a
        // Frame3D via TitledSwingWindow; here the very same panel opens as an MDI
        // internal frame, so the viewer is fully usable without 3D. The lg3d-apps
        // jar (with jackson/slf4j) is on the desktop run classpath, so the
        // reflective lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.photoviewer.PhotoViewer",
                "org.jdesktop.lg3d.apps.photoviewer.PhotoViewerPanel");
        // The Recorder (lg3d-apps, org.jdesktop.lg3d.apps.recorder) records the
        // microphone natively to WAV via javax.sound.sampled and hands screen
        // capture to an external ffmpeg/avconv (the JDK has no screen encoder):
        // a Swing tabbed UI over an AWT-free RecorderBackend / RecordingSettings /
        // Recording model persisted as JSON. In the 3D desktop its Recorder
        // wrapper hosts the panel on a SwingNode inside a Frame3D via
        // TitledSwingWindow; here the very same panel opens as an MDI internal
        // frame, so the recorder is fully usable without 3D. The lg3d-apps jar
        // (with jackson/slf4j) is on the desktop run classpath, so the reflective
        // lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.recorder.Recorder",
                "org.jdesktop.lg3d.apps.recorder.RecorderPanel");
        // The Tuner (lg3d-apps, org.jdesktop.lg3d.apps.tuner) is a guitar / bass
        // tuner: a Swing meter UI over an AWT-free PitchDetector (the YIN
        // algorithm) / Note / Tuning model. Pitch detection is genuinely native
        // and in-process - javax.sound.sampled opens the default capture line and
        // a daemon thread streams 16-bit mono PCM into the detector - so there is
        // no external tool, no codec and nothing to install. In the 3D desktop its
        // Tuner wrapper hosts the panel on a SwingNode inside a Frame3D via
        // TitledSwingWindow; here the very same panel opens as an MDI internal
        // frame, so the tuner is fully usable without 3D. No capture device is
        // opened until the user presses Start, so the panel constructs headless.
        // The lg3d-apps jar is on the desktop run classpath, so the reflective
        // lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.tuner.Tuner",
                "org.jdesktop.lg3d.apps.tuner.TunerPanel");
        // The Security Center (lg3d-apps, org.jdesktop.lg3d.apps.securitycenter)
        // is an antivirus / host-posture front-end: a Swing tabbed UI over an
        // AWT-free AntivirusBackend / SecurityProbe seam. The desktop ships no
        // virus engine, so scanning is delegated honestly to an installed ClamAV
        // (clamdscan, falling back to clamscan), and the overview aggregates the
        // SELinux / firewall / antivirus posture. In the 3D desktop its
        // SecurityCenter wrapper hosts the panel on a SwingNode inside a Frame3D
        // via TitledSwingWindow; here the very same panel opens as an MDI
        // internal frame, so the app is fully usable without 3D. The lg3d-apps
        // jar (with jackson/slf4j) is on the desktop run classpath, so the
        // reflective lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.securitycenter.SecurityCenter",
                "org.jdesktop.lg3d.apps.securitycenter.SecurityCenterPanel");
        // The VPN client (lg3d-apps, org.jdesktop.lg3d.apps.vpn) is a tunnel
        // front-end: a Swing profile dock over an AWT-free VpnBackend seam. The
        // desktop ships no tunnel stack, so the connection is delegated honestly
        // to an installed tool (nmcli preferred, else openvpn / wg-quick for an
        // imported config); a missing tool or a connect needing privilege the
        // session lacks surfaces as guidance, never a fake "connected". In the 3D
        // desktop its Vpn wrapper hosts the panel on a SwingNode inside a Frame3D
        // via TitledSwingWindow; here the very same panel opens as an MDI internal
        // frame, so the app is fully usable without 3D. The lg3d-apps jar (with
        // jackson/slf4j) is on the desktop run classpath, so the reflective lookup
        // resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.vpn.Vpn",
                "org.jdesktop.lg3d.apps.vpn.VpnPanel");
        // The Password Manager (lg3d-apps, org.jdesktop.lg3d.apps.passwordmanager)
        // is a credential vault: a Swing lock screen over an AWT-free VaultCrypto
        // seam (PBKDF2 + AES-GCM). The desktop ships no keyring, so the vault is
        // sealed with the user's own master password and only the ciphertext is
        // persisted; the derived key and decrypted entries live in memory only
        // while unlocked and are cleared on lock / auto-lock. In the 3D desktop
        // its PasswordManager wrapper hosts the panel on a SwingNode inside a
        // Frame3D via TitledSwingWindow; here the very same panel opens as an MDI
        // internal frame, so the app is fully usable without 3D. The lg3d-apps
        // jar (with jackson/slf4j) is on the desktop run classpath, so the
        // reflective lookup resolves and the panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.passwordmanager.PasswordManager",
                "org.jdesktop.lg3d.apps.passwordmanager.PasswordManagerPanel");
        // The Git GUI (lg3d-apps, org.jdesktop.lg3d.apps.gitgui) is a GitKraken /
        // GitHub Desktop-style client: a Swing changes/staging/commit/branches/
        // history/diff UI over an AWT-free GitRepository seam that honestly
        // shells out to the system git (no bundled JGit). In the 3D desktop its
        // GitGui wrapper hosts the panel on a SwingNode inside a Frame3D via
        // TitledSwingWindow; here the very same panel opens as an MDI internal
        // frame, so the client is fully usable without 3D. The lg3d-apps jar is
        // on the desktop run classpath (git itself is an external executable, so
        // no extra jar is needed), so the reflective lookup resolves and the
        // panel loads no Java 3D.
        panels.put("org.jdesktop.lg3d.apps.gitgui.GitGui",
                "org.jdesktop.lg3d.apps.gitgui.GitGuiPanel");
        // The Web Browser (lg3d-apps, org.jdesktop.lg3d.apps.webbrowser) is the
        // desktop's full-featured browser over the JavaFX WebView (WebKit)
        // engine. Its two faces deliberately differ: here in the 2D/Swing
        // desktop the very same start-menu command opens the real interactive
        // BrowserPanel - a JFXPanel-hosted WebView in an MDI internal frame -
        // because a heavyweight JavaFX peer composites correctly against an
        // on-screen window. The 3D desktop instead shows a pure-Swing static
        // preview (BrowserPreviewPanel) whose "Open Full Browser" button spawns
        // WebBrowserApp into a separate child-process JVM, since a WebView
        // paints blank when SwingNode captures it offscreen and JavaFX must not
        // share the Java 3D OpenGL context. JavaFX (and its Linux natives) is
        // therefore initialised only in the 2D desktop JVM and in the spawned
        // browser child process; the OpenJFX jars are on the desktop run
        // classpath, so the reflective lookup resolves.
        panels.put("org.jdesktop.lg3d.apps.webbrowser.WebBrowser",
                "org.jdesktop.lg3d.apps.webbrowser.BrowserPanel");
        // The Docker Manager (lg3d-apps, org.jdesktop.lg3d.apps.dockermanager) is
        // a port of the Swing IDE docker plugin: a Swing terminal / containers /
        // files / images workspace that shells out to the system docker CLI (no
        // bundled docker-java, no Java 3D). Its DockerManagementPanel draws every
        // glyph through the bundled IconManager library, which is on the desktop
        // run classpath. In the 3D desktop its DockerManager wrapper hosts the
        // very same panel on a SwingNode inside a Frame3D via TitledSwingWindow;
        // here it opens as an MDI internal frame, so the manager is fully usable
        // without 3D. The lg3d-apps jar (with slf4j and IconManager) is on the
        // desktop run classpath, so the reflective lookup resolves.
        panels.put("org.jdesktop.lg3d.apps.dockermanager.DockerManager",
                "org.jdesktop.lg3d.apps.dockermanager.DockerManagementPanel");
        // Remote Viewer (lg3d-apps, org.jdesktop.lg3d.apps.remoteviewer) is a
        // port of the standalone jrdesktop/Remote Viewer RMI remote-desktop
        // tool: its original MainFrame GUI (server start/stop, status, viewer
        // connect, file transfer, about, exit) hosted unchanged as
        // RemoteViewerPanel. Its runtime deps (IconManager, slf4j) are on the
        // lg3d-core desktop run classpath too, so the in-JVM start-menu launch
        // works and the 2D desktop can host the same panel as an MDI frame.
        panels.put("org.jdesktop.lg3d.apps.remoteviewer.RemoteViewer",
                "org.jdesktop.lg3d.apps.remoteviewer.RemoteViewerPanel");
        PANEL_APPS = Collections.unmodifiableMap(panels);

        Set<String> withDir = new LinkedHashSet<>();
        withDir.add("org.jdesktop.lg3d.apps.filemanager.FileManager");
        PANEL_APPS_TAKING_DIR = Collections.unmodifiableSet(withDir);

        Set<String> frames = new LinkedHashSet<>();
        frames.add("org.jdesktop.lg3d.apps.paint.PaintApp");
        frames.add("org.jdesktop.lg3d.apps.swingtest.TestFrame");
        frames.add("org.jdesktop.lg3d.apps.screencapture.ScreenCaptureConfigFrame");
        // The IDE (lg3d-apps, org.jdesktop.lg3d.apps.swingide) forks the
        // external swing-ide fat jar as a separate child process. Its launcher
        // main shows no window inside the desktop JVM (so it is not a PANEL app);
        // running that main here simply spawns the child, whose own JFrame then
        // appears beside the 2D desktop exactly as it does over the 3D scene.
        frames.add("org.jdesktop.lg3d.apps.swingide.SwingIde");
        // The OpenAPI Contract Editor (lg3d-apps,
        // org.jdesktop.lg3d.apps.openapieditor) forks the external
        // openapi-editor fat jar as a separate child process. Its launcher main
        // shows no window inside the desktop JVM (so it is not a PANEL app);
        // running that main here simply spawns the child, whose own JFrame then
        // appears beside the 2D desktop exactly as it does over the 3D scene.
        frames.add("org.jdesktop.lg3d.apps.openapieditor.OpenApiEditor");
        // PayloadMan (lg3d-apps, org.jdesktop.lg3d.apps.payloadman) forks the
        // external tests-suite API testing tool's fat jar as a separate child
        // process. Its launcher main shows no window inside the desktop JVM (so
        // it is not a PANEL app); running that main here simply spawns the child,
        // whose own JFrame then appears beside the 2D desktop exactly as it does
        // over the 3D scene.
        frames.add("org.jdesktop.lg3d.apps.payloadman.PayloadMan");
        // The Launcher (lg3d-apps, org.jdesktop.lg3d.apps.launcher.LauncherFrame)
        // is a Swing-based tool for creating custom application launchers. It
        // shows its own JFrame, so it runs beside the 2D desktop rather than
        // inside it. The same launcher is available in the 3D desktop.
        frames.add("org.jdesktop.lg3d.apps.launcher.LauncherFrame");
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
     * Navigates a hosted panel to {@code date}, if the panel supports it (a
     * public {@code jumpToDate(LocalDate)} method). Used by the taskbar calendar
     * to open the Agenda at a double-clicked day; a panel without the method is
     * left untouched. Reflective, like {@link #createPanel}, so lg3d-core keeps
     * no compile-time dependency on the panel's module.
     */
    public static void showDate(JComponent panel, java.time.LocalDate date) {
        if (panel == null || date == null) {
            return;
        }
        try {
            Method navigate =
                    panel.getClass().getMethod("jumpToDate", java.time.LocalDate.class);
            navigate.invoke(panel, date);
        } catch (NoSuchMethodException nsme) {
            // This panel is not date-navigable; nothing to do.
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not navigate "
                    + panel.getClass().getName() + " to " + date, e);
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
