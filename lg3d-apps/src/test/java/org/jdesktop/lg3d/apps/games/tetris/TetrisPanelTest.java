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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

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
        assertEquals(new Dimension(TetrisPanel.WIDTH_PX, TetrisPanel.HEIGHT_PX),
                panel.getPreferredSize());
        assertEquals(wellWidth, panel.getBoard().getPreferredSize().width);
    }

    @Test
    @DisplayName("the no-arg constructor exists for the 2D registry lookup")
    void noArgConstructorExists() {
        // Desktop2DAppRegistry.PANEL_APPS builds the panel reflectively through
        // this constructor, so it must exist. (It reads the real user store and
        // never writes it without a settings dialog, which is harmless here.)
        ThrowingSupplier<TetrisPanel> ctor = TetrisPanel::new;
        TetrisPanel built = assertDoesNotThrow(ctor);
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
    @DisplayName("a row paints its caption once, not in the value too")
    void captionsAreNotDoubled() {
        panel.newGame();
        // fieldRow() puts the caption in its own label, so a value label that
        // also named itself rendered "Score:  Score: 0" on the live desktop.
        for (JLabel caption : labelsOf(panel)) {
            String name = caption.getText();
            if (name == null || !name.endsWith(":")) {
                continue;
            }
            String word = name.substring(0, name.length() - 1);
            for (Component sibling : caption.getParent().getComponents()) {
                if (!(sibling instanceof JLabel) || sibling == caption) {
                    continue;
                }
                String value = ((JLabel) sibling).getText();
                assertFalse(value != null && value.startsWith(word),
                        "the row paints \"" + name + "\" and its value repeats it: \""
                        + value + "\"");
            }
        }
    }

    @Test
    @DisplayName("no sidebar control is clipped at the panel's own size")
    void nothingIsClipped() {
        // The control pad is a 3x3 grid inside a fixed-width sidebar: at the
        // width this game first shipped with, the live desktop rendered the
        // rotate button as "Rot...", which no preferred-size assertion sees.
        panel.setSize(panel.getPreferredSize());
        relayout(panel);
        for (JButton button : buttonsOf(panel)) {
            assertTrue(button.getWidth() >= button.getPreferredSize().width,
                    "\"" + button.getText() + "\" wants "
                    + button.getPreferredSize().width + "px but has "
                    + button.getWidth() + "px");
        }
        for (JLabel label : labelsOf(panel)) {
            assertTrue(label.getWidth() >= label.getPreferredSize().width,
                    "the label \"" + label.getText() + "\" is clipped");
        }
    }

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
    @DisplayName("the well is a full grid of cells and the piece lands on it")
    void boardPaintsTheFallingPiece() {
        panel.newGame();
        TetrisBoard board = panel.getBoard();
        board.setSize(board.getPreferredSize());
        BufferedImage image = newBuffer(board);
        board.paintComponent(image.createGraphics());
        assertTrue(containsAnyColour(image, panel.getEngine().getCurrent().color()),
                "the falling piece reached the canvas");
        assertEquals(TetrisEngine.WIDTH * TetrisBoard.CELL_PX, image.getWidth());
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
        // Column 0 is deliberately left open: a row the test itself completed
        // would pay out instead of ending the run.
        for (int y = 0; y < TetrisEngine.HEIGHT; y++) {
            for (int x = 1; x < TetrisEngine.WIDTH; x++) {
                if (y > 1 || (x != 4 && x != 5)) {
                    engine.setCell(x, y, Color.GRAY);
                }
            }
        }
        assertEquals(TetrisEngine.Turn.GAME_OVER, engine.hardDrop());
        assertTrue(engine.isGameOver());
    }

    /** Lays out this container and every container below it, top down. */
    private static void relayout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container) {
                relayout((Container) child);
            }
        }
    }

    private static List<JLabel> labelsOf(Container container) {
        List<JLabel> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel) {
                found.add((JLabel) child);
            } else if (child instanceof Container) {
                found.addAll(labelsOf((Container) child));
            }
        }
        return found;
    }

    private static List<JButton> buttonsOf(Container container) {
        List<JButton> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JButton) {
                found.add((JButton) child);
            } else if (child instanceof Container) {
                found.addAll(buttonsOf((Container) child));
            }
        }
        return found;
    }

    private static void paint(TetrisPanel target) {
        target.setSize(TetrisPanel.WIDTH_PX, TetrisPanel.HEIGHT_PX);
        target.doLayout();
        BufferedImage image = newBuffer(target);
        Graphics2D g = image.createGraphics();
        target.paint(g);
        g.dispose();
        assertTrue(image.getHeight() > 0);
    }

    private static BufferedImage newBuffer(Component component) {
        return new BufferedImage(Math.max(1, component.getWidth()),
                Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_ARGB);
    }

    /** Whether any pixel of the canvas carries that colour, ignoring alpha. */
    private static boolean containsAnyColour(BufferedImage image, Color color) {
        int wanted = color.getRGB() & 0xFFFFFF;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == wanted) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sends a keystroke to the listeners the panel installed on the board, the
     * same way the 3D desktop's {@code SwingNodeRenderer} does: it redispatches
     * a real {@link KeyEvent} at a component rather than going through input
     * maps, so the board's listener is the one path both surfaces share.
     */
    private static void press(Component target, int keyCode) {
        KeyEvent event = new KeyEvent(target, KeyEvent.KEY_PRESSED, 0L, 0, keyCode,
                KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_STANDARD);
        for (KeyListener listener : ((JComponent) target).getKeyListeners()) {
            listener.keyPressed(event);
        }
    }
}
