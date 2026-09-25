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
package org.jdesktop.lg3d.scenemanager.utils.run;

import java.awt.event.KeyEvent;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DAppRegistry;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig;
import org.jdesktop.lg3d.displayserver.desktop2d.PrefsRunHistoryStore;
import org.jdesktop.lg3d.displayserver.desktop2d.RunHistory;
import org.jdesktop.lg3d.displayserver.desktop2d.RunHistoryStore;
import org.jdesktop.lg3d.displayserver.desktop2d.RunResolver;
import org.jdesktop.lg3d.displayserver.desktop2d.ShortcutMap;
import org.jdesktop.lg3d.scenemanager.utils.SceneControl;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudLayer;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudPlugin;
import org.jdesktop.lg3d.scenemanager.utils.plugin.SceneManagerPlugin;
import org.jdesktop.lg3d.utils.action.AppLaunchAction;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.KeyEvent3D;
import org.jdesktop.lg3d.wg.event.LgEvent;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.LgEventSource;

/**
 * The native 3D desktop's global keyboard shortcuts and its Alt+F2 "run
 * command" dialog: the plugin counterpart of the 2D/Swing desktop's
 * {@code KeyEventDispatcher} + {@code RunDialog}, and the first Phase-5 feature
 * ported off the taskbar. {@code GlassyTaskbar} is not touched.
 *
 * <p>The plugin reuses the 2D desktop's pure seams rather than re-inventing
 * them: the binding table is {@link ShortcutMap#defaults()}, resolution and
 * recall go through {@link RunResolver} and the persisted
 * {@link RunHistory}/{@link RunHistoryStore}, and the dialog itself is the
 * headless-testable {@link RunDialogPanel} hosted on the HUD by
 * {@link RunDialog3D}. Both desktops therefore share one shortcut table, one
 * decision table and one command history.</p>
 *
 * <h2>Which shortcuts this plugin claims</h2>
 * <p>Only {@link ShortcutMap#RUN_DIALOG} (Alt+F2) and
 * {@link ShortcutMap#OPEN_TERMINAL} (Ctrl+Alt+T). The snap, show-desktop,
 * window-close and workspace bindings in the same table are already owned by
 * {@code WindowSnapPlugin} and {@code WorkspacePlugin}, each of which installs
 * its own global key listener; this plugin resolves every keystroke through the
 * shared table but acts on those two ids and deliberately ignores the rest, so
 * the plugins never double-bind.</p>
 *
 * <h2>Why keys are dispatched programmatically, on the EDT</h2>
 * <p>A hosted {@code SwingNode} only delivers {@code KeyEvent3D} to its panel
 * while the node holds lg3d focus, which follows the pointer &mdash; but a run
 * dialog opened by Alt+F2 must accept typing wherever the pointer is. This
 * plugin therefore owns one global {@link KeyEvent3D} listener and, while the
 * card is up, feeds each keystroke straight to {@link RunDialogPanel#dispatch}
 * instead of relying on AWT focus.</p>
 *
 * <p>That listener runs on the lg3d event thread, whereas the {@code SwingNode}
 * capture timer repaints the hosted panel on the EDT. Mutating Swing state
 * (here, the command field) off the EDT races that repaint, so every call that
 * touches the panel &mdash; {@code show}, {@code hide} and {@code dispatch}
 * &mdash; is marshalled onto the EDT with {@link SwingUtilities#invokeLater}.
 * A {@code volatile} {@link #showing} flag is flipped synchronously on the lg3d
 * thread so a keystroke that arrives immediately after Alt+F2 is still routed
 * to the card rather than racing the not-yet-run {@code show()}.</p>
 *
 * <p>{@link #getPluginRoot()} returns null: the card is parented to the HUD
 * layer rather than the scene root, so it inherits the layer's
 * perspective-compensated front pose. When {@link DesktopHudPlugin} is not
 * running the card cannot be shown and is skipped with a warning, but
 * Ctrl+Alt+T still opens a terminal.</p>
 */
public class RunDialogPlugin implements SceneManagerPlugin {

    private static final Logger logger =
            Logger.getLogger("lg.scenemanager.run");

    /** Fractional HUD position of the card: centred, in the upper third. */
    private static final float CARD_FX = 0.5f;
    private static final float CARD_FY = 0.30f;

    /**
     * Terminal executables tried, in order, for Ctrl+Alt+T. Mirrors the 2D
     * desktop's list so both shells open the same emulator.
     */
    private static final String[] TERMINALS = {
        "xterm", "gnome-terminal", "konsole", "xfce4-terminal",
        "mate-terminal", "lxterminal",
    };

    /** The shared global-shortcut table. */
    private final ShortcutMap shortcuts = ShortcutMap.defaults();

    /**
     * Whether the card is logically up. Flipped synchronously on the lg3d event
     * thread (never read from the scene-graph visible flag) so routing survives
     * the show-then-type race described in the class javadoc.
     */
    private volatile boolean showing;

    private RunDialog3D dialog;
    private DesktopHudLayer layer;
    private RunHistoryStore store;
    private LgEventListener keyListener;

    public RunDialogPlugin() {
    }

    @Override
    public void initialize(SceneControl sceneControl) {
        showing = false;

        store = new PrefsRunHistoryStore();
        RunHistory history = store.load();
        MenuModel model = Desktop2DMenuConfig.load();
        RunDialogPanel panel = new RunDialogPanel(
                model, history, store, this::launch, this::hideDialog);

        // Installed before the HUD check so Ctrl+Alt+T works even with no card.
        installKeyListener();

        layer = DesktopHudPlugin.layer();
        if (layer == null) {
            logger.warning("DesktopHudPlugin is not running; the Alt+F2 run "
                    + "dialog card is disabled (Ctrl+Alt+T still opens a "
                    + "terminal). Register RunDialogPlugin after "
                    + "DesktopHudPlugin in glassy.lgcfg.");
            return;
        }
        try {
            dialog = new RunDialog3D(panel);
            layer.addChild(dialog);
            layer.placeAt(dialog, CARD_FX, CARD_FY);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not mount the run dialog", t);
            dialog = null;
        }
    }

    @Override
    public Component3D getPluginRoot() {
        // The card is attached to the HUD layer, not the scene root.
        return null;
    }

    @Override
    public void destroy() {
        LgEventConnector connector = LgEventConnector.getLgEventConnector();
        removeQuietly(connector, LgEventSource.ALL_SOURCES, keyListener);
        keyListener = null;
        showing = false;

        RunDialog3D d = dialog;
        if (d != null) {
            try {
                d.hide();
                d.dispose();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "run dialog dispose failed", t);
            }
            if (layer != null) {
                layer.removeChild(d);
            }
            dialog = null;
        }
        layer = null;
        store = null;
    }

    @Override
    public boolean isRemovable() {
        return true;
    }

    // ------------------------------------------------------------------- keys

    private void installKeyListener() {
        keyListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                if (evt instanceof KeyEvent3D ke) {
                    KeyEvent awt = ke.getKeyEvent();
                    if (awt != null) {
                        handleKey(awt);
                    }
                }
            }

            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { KeyEvent3D.class };
            }
        };
        LgEventConnector.getLgEventConnector()
                .addListener(LgEventSource.ALL_SOURCES, keyListener);
    }

    /**
     * Routes one AWT key event. While the card is up every keystroke goes to the
     * panel (on the EDT); otherwise a key-press is resolved through the shared
     * shortcut table and only the run-dialog / open-terminal actions are taken.
     * Package-private for tests.
     */
    void handleKey(KeyEvent awt) {
        if (showing && dialog != null) {
            final RunDialogPanel panel = dialog.panel();
            SwingUtilities.invokeLater(() -> panel.dispatch(awt));
            return;
        }
        if (awt.getID() != KeyEvent.KEY_PRESSED) {
            return;
        }
        Optional<String> action =
                shortcuts.actionFor(KeyStroke.getKeyStrokeForEvent(awt));
        if (action.isEmpty()) {
            return;
        }
        switch (action.get()) {
            case ShortcutMap.RUN_DIALOG -> showDialog();
            case ShortcutMap.OPEN_TERMINAL -> openTerminal();
            // Snap / show-desktop / window-close / workspace bindings belong to
            // WindowSnapPlugin and WorkspacePlugin; ignored here on purpose.
            default -> {
            }
        }
    }

    // ----------------------------------------------------------------- dialog

    /** Raises the card (reset to an empty field). Package-private for tests. */
    void showDialog() {
        if (dialog == null) {
            logger.warning("Alt+F2 pressed but the run dialog card is not "
                    + "mounted (DesktopHudPlugin is not running).");
            return;
        }
        showing = true;
        final RunDialog3D d = dialog;
        SwingUtilities.invokeLater(d::show);
    }

    /** Hides the card; the panel's Escape/submit close callback. */
    void hideDialog() {
        showing = false;
        final RunDialog3D d = dialog;
        if (d == null) {
            return;
        }
        // Usually reached from the panel's close callback, which already runs on
        // the EDT (dispatch is marshalled there); hide directly in that case so
        // the card disappears in the same tick rather than a queued one later.
        if (SwingUtilities.isEventDispatchThread()) {
            d.hide();
        } else {
            SwingUtilities.invokeLater(d::hide);
        }
    }

    /** Whether the card is logically up. Package-private for tests/probe. */
    boolean isShowing() {
        return showing;
    }

    // ---------------------------------------------------------------- launch

    /**
     * Carries out a resolved run-dialog entry through the 3D desktop's normal
     * launch path. {@link RunResolver.Decision#command()} already carries the
     * start-menu command for an {@code APP} decision and the raw command for a
     * {@code COMMAND} decision, so both funnel into one {@link AppLaunchAction}.
     */
    void launch(RunResolver.Decision decision) {
        if (decision == null || decision.isNotFound()) {
            return;
        }
        String command = decision.command();
        if (command == null || command.isBlank()) {
            return;
        }
        performLaunch(command);
    }

    /** Opens the first terminal emulator present on the PATH. */
    void openTerminal() {
        String command = terminalCommand();
        if (command == null) {
            logger.warning("No terminal emulator found on the PATH (tried: "
                    + String.join(", ", TERMINALS) + ").");
            return;
        }
        performLaunch(command);
    }

    /** The first installed terminal executable, or null when none is present. */
    static String terminalCommand() {
        for (String terminal : TERMINALS) {
            if (Desktop2DAppRegistry.isExternalAvailable(terminal)) {
                return terminal;
            }
        }
        return null;
    }

    private void performLaunch(String command) {
        try {
            new AppLaunchAction(command, getClass().getClassLoader())
                    .performAction(null);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not launch: " + command, t);
        }
    }

    private static void removeQuietly(LgEventConnector connector,
            Class sourceClass, LgEventListener listener) {
        if (listener == null) {
            return;
        }
        try {
            connector.removeListener(sourceClass, listener);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not remove a run dialog listener", t);
        }
    }
}
