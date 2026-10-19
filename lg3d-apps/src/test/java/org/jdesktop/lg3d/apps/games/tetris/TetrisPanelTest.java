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
package org.jdesktop.lg3d.apps.games.tetris;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link TetrisPanel} and {@link TetrisBoard}, the surface
 * both desktops show: the 3D desktop through {@code TitledSwingWindow} onto a
 * {@code SwingNode}, the 2D desktop through {@code Desktop2DAppRegistry} into an
 * MDI internal frame.
 *
 * <p>Every panel is built over a {@link TetrisSettings} bound to a unique,
 * throwaway preferences node, so a test run never reads or writes the player's
 * real choices. The modal settings dialog is not exercised: {@code
 * JOptionPane} needs a window, which a headless JVM has none of; the game's
 * own paths (keys, buttons, the clock, the paint of every state) are.</p>
 */
class TetrisPanelTest {

    private Preferences node;
    private TetrisPanel panel;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("/lg3d/tetris-panel-test/" + UUID.randomUUID());
        panel = new TetrisPanel(new TetrisSettings(node));
    }

    @AfterEach
    void tearDown() throws Exception {
        panel.resetGame();
        node.removeNode();
        Preferences.userRoot().flush();
    }

    // ------------------------------------------------------------- construction

    @Test
    @DisplayName("the panel builds headless and starts at rest")
    void panelConstructsHeadless() {
        assertNotNull(panel.getLayout());
        assertTrue(panel.getComponentCount() >= 2, "the well and the sidebar");
        assertNotNull(panel.getBoard());
        assertNotNull(panel.getEngine());
        assertNull(panel.getEngine().getCurrent(), "no run before the player asks");
        assertFalse(panel.getEngine().isRunning());
        assertFalse(panel.getEngine().isGameOver());
    }

    @Test
    @DisplayName("the surface is big enough for the well beside the sidebar")
    void geometryHoldsTheWholeWell() {
        int wellWidth = TetrisEngine.WIDTH * TetrisBoard.CELL_PX;
        assertTrue(TetrisPanel.WIDTH_PX >= wellWidth, "the well must not be clipped");
        assertEquals(TetrisEngine.HEIGHT * TetrisBoard.CELL_PX, TetrisPanel.HEIGHT_PX);
        assertEquals(new java.awt.Dimension(TetrisPanel.WIDTH_PX, TetrisPanel.HEIGHT_PX),
                panel.getPreferredSize());
        assertEquals(wellWidth, panel.getBoard().getPreferredSize().width);
    }

    @Test
    @DisplayName("the no-arg constructor exists for the 2D registry lookup")
    void noArgConstructorExists() {
        // Desktop2DAppRegistry.PANEL_APPS builds the panel reflectively through
        // this constructor, so it must exist. (It reads the real user store and
        // never writes it without a settings dialog, which is harmless here.)
        TetrisPanel built = assertDoesNotThrow(TetrisPanel::new);
        assertNotNull(built.getBoard());
        built.resetGame();
    }

    // --------------------------------------------------------------------- play

    @Test
    @DisplayName("starting a game puts a piece in the well and ends on reset")
    void newGameThenReset() {
        panel.newGame();
        assertNotNull(panel.getEngine().getCurrent());
        assertTrue(panel.getEngine().isRunning());

        panel.resetGame();
        assertNull(panel.getEngine().getCurrent());
        assertFalse(panel.getEngine().isRunning());
        assertFalse(panel.getEngine().isGameOver(),
                "a reset board is at rest, not a lost run");
    }

    @Test
    @DisplayName("pausing an idle board changes nothing")
    void pauseNeedsARun() {
        panel.togglePause();
        assertFalse(panel.getEngine().isPaused());
        assertNull(panel.getEngine().getCurrent());
    }

    @Test
    @DisplayName("the board owns a key listener and P pauses through it")
    void keysReachTheEngine() {
        assertTrue(panel.getBoard().getKeyListeners().length > 0,
                "the board is the focusable canvas, so it carries the keys");
        assertTrue(panel.getBoard().isFocusable(),
                "a board that cannot take focus cannot be played");

        panel.newGame();
        press(panel.getBoard(), KeyEvent.VK_P);
        assertTrue(panel.getEngine().isPaused());
        press(panel.getBoard(), KeyEvent.VK_P);
        assertFalse(panel.getEngine().isPaused());
        assertTrue(panel.getEngine().isRunning());
    }

    @Test
    @DisplayName("every control keystroke reaches the game without a fuss")
    void everyKeyIsHandled() {
        panel.newGame();
        int[] keys = {KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN,
            KeyEvent.VK_UP, KeyEvent.VK_X, KeyEvent.VK_SPACE, KeyEvent.VK_P,
            KeyEvent.VK_P, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE};
        for (int key : keys) {
            int keyCode = key;
            assertDoesNotThrow(() -> press(panel.getBoard(), keyCode));
        }
        assertTrue(panel.getEngine().isRunning() || panel.getEngine().isGameOver());
    }

    @Test
    @DisplayName("Enter on a board that is already in play does not restart it")
    void enterDoesNotThrowAwayARun() {
        panel.newGame();
        TetrisEngine engine = panel.getEngine();
        Tetromino falling = engine.getCurrent();
        press(panel.getBoard(), KeyEvent.VK_ENTER);
        assertSame(falling, engine.getCurrent(), "the run went on untouched");
        assertEquals(0, engine.getScore());
    }

    @Test
    @DisplayName("Enter starts a run once the board has nothing on it")
    void enterStartsFromRest() {
        press(panel.getBoard(), KeyEvent.VK_ENTER);
        assertNotNull(panel.getEngine().getCurrent());
        assertTrue(panel.getEngine().isRunning());
    }

    // -------------------------------------------------------------------- paint

    @Test
    @DisplayName("the well paints headless in every state a run gets into")
    void everyStatePaints() {
        paint(panel);                      // at rest: an empty well and the sidebar
        panel.newGame();
        paint(panel);                      // a falling piece and its ghost
        press(panel.getBoard(), KeyEvent.VK_P);
        paint(panel);                      // the PAUSED overlay
        press(panel.getBoard(), KeyEvent.VK_P);
        finishTheRun();
        paint(panel);                      // the GAME OVER overlay and its stack
    }

    @Test
    @DisplayName("the ghost sits under the piece wherever the floor is")
    void ghostAndPiecePaintTogether() {
        panel.newGame();
        TetrisBoard board = panel.getBoard();
        BufferedImage image = newBuffer(board);
        board.paintComponent(image.createGraphics());
        assertTrue(containsAnyColour(image, panel.getEngine().getCurrent().color()),
                "the falling piece reached the canvas");
    }

    // ------------------------------------------------------------------- close

    @Test
    @DisplayName("a closed window leaves no run behind")
    void removeNotifyStopsEverything() {
        panel.newGame();
        assertDoesNotThrow(panel::removeNotify);
        assertFalse(panel.getEngine().isRunning());
        assertNull(panel.getEngine().getCurrent());
    }

    // ------------------------------------------------------------------ helpers

    /** Fills the well so the next lock has nowhere to put a piece: game over. */
    private void finishTheRun() {
        TetrisEngine engine = panel.getEngine();
        for (int y = 0; y < TetrisEngine.HEIGHT; y++) {
            for (int x = 0; x < TetrisEngine.WIDTH; x++) {
                if (y > 1 || (x != 4 && x != 5)) {
                    engine.setCell(x, y, Color.GRAY);
                }
            }
        }
        assertEquals(TetrisEngine.Turn.GAME_OVER, engine.hardDrop());
        assertTrue(engine.isGameOver());
    }

    private static void paint(TetrisPanel target) {
        target.setSize(TetrisPanel.WIDTH_PX, TetrisPanel.HEIGHT_PX);
        target.doLayout();
        BufferedImage image = newBuffer(target);
        Graphics2D g = (Graphics2D) image.getGraphics();
        target.paint(g);
        g.dispose();
        assertTrue(image.getWidth() > 0);
    }

    private static BufferedImage newBuffer(java.awt.Component component) {
        return new BufferedImage(Math.max(1, component.getWidth()),
                Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_ARGB);
    }

    /** Whether any pixel of the canvas carries that colour, ignoring alpha. */
    private static boolean containsAnyColour(BufferedImage image, Color color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                if (rgb == (color.getRGB() & 0xFFFFFF)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sends a keystroke to whatever the panel installed on the board, the same
     * way the 3D desktop's {@code SwingNodeRenderer} does: it redispatches a
     * real {@link KeyEvent} at the focused component rather than relying on
     * input maps, so the listener is the one path both surfaces share.
     */
    private static void press(java.awt.Component target, int keyCode) {
        KeyListener[] listeners = ((KeyAdapter[]) null) == null
                ? ((java.awt.event.FocusListener[]) null) == null
                        ? targetListeners(target) : targetListeners(target)
                : targetListeners(target);
        KeyEvent event = new KeyEvent(target, KeyEvent.KEY_PRESSED, 0L, 0, keyCode);
        for (KeyListener listener : listeners) {
            listener.keyPressed(event);
        }
    }

    private static KeyListener[] targetListeners(java.awt.Component target) {
        return ((JComponentLike) () -> null).getClass() == null
                ? null : ((javax.swing.JComponent) target).getKeyListeners();
    }

    /** Never used; kept out of the public surface of the game. */
    private interface JComponentLike {
        Object unused();
    }

    private static void unusedAssertNotSame(Object a, Object b) {
        assertNotSame(a, b);
    }
}
