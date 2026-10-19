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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import org.jdesktop.lg3d.apps.games.tetris.audio.TetrisAudio;

/**
 * The Tetris window's content: the well on the left, the run's numbers, the
 * next piece, the controls and the settings on the right.
 *
 * <p>This is the class both desktop surfaces show. In the 3D desktop the
 * {@link Tetris} entry point hands it to {@code TitledSwingWindow}, which
 * paints it onto a {@code SwingNode} quad inside a {@code Frame3D}; in the 2D
 * desktop {@code Desktop2DAppRegistry} instantiates this same class into an MDI
 * internal frame, so the 3D wrapper is never loaded on a machine without a
 * virtual universe. Nothing here touches the scene graph.</p>
 *
 * <p>It owns the two things a {@link TetrisEngine} deliberately does not: the
 * clock that ticks the fall, and the sound each turn makes. The engine answers
 * every input with a {@link TetrisEngine.Turn}; this maps the turn to a
 * repaint, a {@link TetrisAudio} effect and, when a level was reached, a new
 * timer delay.</p>
 *
 * <p>Keyboard focus stays on the board: the sidebar's buttons are
 * non-focusable (still clickable) so that a player who reaches for the mouse
 * once does not lose the arrow keys afterwards -- and because a focused button
 * would swallow the space bar as its own "press".</p>
 */
public class TetrisPanel extends JPanel {

    private static final Logger logger = Logger.getLogger("lg.apps.tetris");

    /** Natural size of the whole surface, in the pixels the 3D host scales. */
    public static final int WIDTH_PX = 500;
    public static final int HEIGHT_PX = TetrisEngine.HEIGHT * TetrisBoard.CELL_PX;

    /**
     * Width of the sidebar the numbers and controls live in. It has to hold the
     * control pad's three columns of full words: at the width this game shipped
     * with, the live desktop rendered the rotate button as "Rot...".
     */
    private static final int SIDEBAR_PX = 240;

    private final TetrisEngine engine;
    private final TetrisAudio audio;
    private final TetrisSettings settings;
    private final Timer timer;

    private final TetrisBoard board;
    // Each of these is the value half of a row whose caption {@code fieldRow}
    // already paints, so they carry the number alone -- text that repeated the
    // caption read "Score:  Score: 0" on the live desktop.
    private final JLabel scoreLabel = new JLabel("0");
    private final JLabel levelLabel = new JLabel("1");
    private final JLabel linesLabel = new JLabel("0");
    private final NextPieceView nextView = new NextPieceView();
    private final JButton primaryButton = new JButton("New game");
    private final JButton pauseButton = new JButton("Pause");
    private final JButton settingsButton = new JButton("Settings...");

    /**
     * A game reading the player's saved preferences and its own random feed.
     */
    public TetrisPanel() {
        this(new TetrisSettings());
    }

    /** The seam a test uses to keep the real user's preferences untouched. */
    TetrisPanel(TetrisSettings settings) {
        this.settings = settings;
        this.engine = new TetrisEngine();
        this.audio = new TetrisAudio();
        this.timer = new Timer(engine.getTickDelayMs(), e -> tick());

        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        board = new TetrisBoard(engine);
        board.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                handleKey(event.getKeyCode());
            }
        });
        add(board, BorderLayout.CENTER);
        add(buildSidebar(), BorderLayout.EAST);

        primaryButton.addActionListener(e -> newGame());
        pauseButton.addActionListener(e -> togglePause());
        settingsButton.addActionListener(e -> showSettings());

        settings.applyTo(audio);
        engine.setBaseDelayMs(settings.getStartDelayMs());
        refresh();
    }

    // ------------------------------------------------------------------ play

    /** Starts a fresh run: the only path that puts a piece in the well. */
    public void newGame() {
        engine.start();
        timer.setDelay(engine.getTickDelayMs());
        timer.restart();
        audio.play(TetrisAudio.Effect.START);
        board.requestFocusInWindow();
        refresh();
    }

    /** Stops the clock and clears the well, leaving the window at rest. */
    public void resetGame() {
        timer.stop();
        engine.stop();
        audio.silence();
        refresh();
    }

    /** Pauses or resumes the run in progress; a no-op on an idle board. */
    public void togglePause() {
        if (engine.getCurrent() == null) {
            return;
        }
        boolean paused = engine.togglePause();
        if (paused) {
            timer.stop();
            audio.play(TetrisAudio.Effect.PAUSE);
        } else if (engine.isRunning()) {
            audio.play(TetrisAudio.Effect.RESUME);
            timer.restart();
        }
        refresh();
    }

    private void tick() {
        apply(engine.step());
    }

    /**
     * One input, one turn: the clock's tick and every key and button go through
     * here, so the sound, the repaint and the button states are decided in one
     * place instead of at each call site.
     */
    private void apply(TetrisEngine.Turn turn) {
        switch (turn) {
            case MOVED:
                break;  // the sound is the input method's, which knows the gesture
            case REJECTED:
                audio.play(TetrisAudio.Effect.BLOCKED);
                break;
            case LINE_LOCKED:
                audio.play(TetrisAudio.Effect.LOCK);
                break;
            case LINES_CLEARED:
                playClear(engine.getLastLinesCleared());
                break;
            case LEVEL_UP:
                playClear(engine.getLastLinesCleared());
                audio.play(TetrisAudio.Effect.LEVEL_UP);
                timer.setDelay(engine.getTickDelayMs());
                break;
            case GAME_OVER:
                timer.stop();
                audio.play(TetrisAudio.Effect.GAME_OVER);
                logger.log(Level.FINE, "Tetris run over at score " + engine.getScore());
                break;
            case NOOP:
            case STEPPED:
            default:
                break;
        }
        board.repaint();
        nextView.repaint();
        refresh();
    }

    private void playClear(int lines) {
        switch (lines) {
            case 1:
                audio.play(TetrisAudio.Effect.CLEAR_ONE);
                break;
            case 2:
                audio.play(TetrisAudio.Effect.CLEAR_TWO);
                break;
            case 3:
                audio.play(TetrisAudio.Effect.CLEAR_THREE);
                break;
            default:
                audio.play(TetrisAudio.Effect.CLEAR_FOUR);
                break;
        }
    }

    private void handleKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.VK_LEFT:
                apply(shift(engine.moveLeft(), TetrisAudio.Effect.MOVE));
                break;
            case KeyEvent.VK_RIGHT:
                apply(shift(engine.moveRight(), TetrisAudio.Effect.MOVE));
                break;
            case KeyEvent.VK_DOWN:
                apply(shift(engine.moveDown(), TetrisAudio.Effect.SOFT_DROP));
                break;
            case KeyEvent.VK_UP:
            case KeyEvent.VK_X:
                apply(shift(engine.rotate(), TetrisAudio.Effect.ROTATE));
                break;
            case KeyEvent.VK_SPACE:
                apply(engine.hardDrop());
                break;
            case KeyEvent.VK_P:
                togglePause();
                break;
            case KeyEvent.VK_ENTER:
                if (!isPlaying()) {
                    newGame();
                }
                break;
            default:
                break;
        }
    }

    /** Whether a run is on the board, paused or not: what Enter must not restart. */
    private boolean isPlaying() {
        return engine.getCurrent() != null && !engine.isGameOver();
    }

    /**
     * A accepted gesture of a known kind plays its own effect; a rejected one
     * plays the block, and a turn that ended the piece (a soft drop reaching
     * the floor) is left to {@link #apply} so locking still sounds like
     * locking.
     */
    private TetrisEngine.Turn shift(TetrisEngine.Turn turn, TetrisAudio.Effect effect) {
        if (turn == TetrisEngine.Turn.MOVED) {
            audio.play(effect);
        }
        return turn;
    }

    // ------------------------------------------------------------------ view

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setPreferredSize(new Dimension(SIDEBAR_PX, HEIGHT_PX));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));

        sidebar.add(fieldRow("Score", scoreLabel));
        sidebar.add(fieldRow("Level", levelLabel));
        sidebar.add(fieldRow("Lines", linesLabel));

        JPanel nextBox = new JPanel(new BorderLayout(6, 0));
        nextBox.add(new JLabel("Next:"), BorderLayout.WEST);
        nextBox.add(nextView, BorderLayout.CENTER);
        nextBox.setAlignmentX(LEFT_ALIGNMENT);
        nextBox.setBorder(new EmptyBorder(6, 0, 6, 0));
        sidebar.add(nextBox);

        sidebar.add(primaryButton);
        sidebar.add(pauseButton);
        sidebar.add(settingsButton);
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(buildControlPad());
        sidebar.add(Box.createVerticalStrut(8));
        sidebar.add(legendLine("Arrow keys move and rotate,"));
        sidebar.add(legendLine("Space drops, P pauses, Enter starts."));
        sidebar.add(Box.createVerticalGlue());

        // Buttons stay out of the focus cycle on purpose (see the class
        // comment): the board keeps the keyboard for the whole window.
        for (JButton button : new JButton[] {primaryButton, pauseButton, settingsButton}) {
            button.setFocusable(false);
        }
        return sidebar;
    }

    /**
     * The mouse twin of the key controls, for a player at the other end of the
     * desktop -- and the only path that works when the host never gives the
     * board keyboard focus. Same engine calls, same turn handling.
     */
    private JPanel buildControlPad() {
        JPanel pad = new JPanel(new GridLayout(3, 3, 4, 4));
        pad.setAlignmentX(LEFT_ALIGNMENT);
        pad.add(gap());
        pad.add(padButton("Rotate", e ->
                apply(shift(engine.rotate(), TetrisAudio.Effect.ROTATE))));
        pad.add(gap());
        pad.add(padButton("Left", e ->
                apply(shift(engine.moveLeft(), TetrisAudio.Effect.MOVE))));
        pad.add(padButton("Down", e ->
                apply(shift(engine.moveDown(), TetrisAudio.Effect.SOFT_DROP))));
        pad.add(padButton("Right", e ->
                apply(shift(engine.moveRight(), TetrisAudio.Effect.MOVE))));
        pad.add(gap());
        pad.add(padButton("Drop", e -> apply(engine.hardDrop())));
        pad.add(gap());
        return pad;
    }

    private JButton padButton(String name, java.awt.event.ActionListener action) {
        JButton button = new JButton(name);
        button.setFocusable(false);
        button.addActionListener(action);
        return button;
    }

    /** An empty grid cell, so the pad reads as a cross rather than a block. */
    private Component gap() {
        return Box.createRigidArea(new Dimension(1, 1));
    }

    private JComponent legendLine(String text) {
        JLabel label = new JLabel(text);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private JComponent fieldRow(String name, JLabel value) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(new JLabel(name + ":"), BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(new EmptyBorder(2, 0, 2, 0));
        return row;
    }

    /**
     * Puts the numbers and the button captions in step with the engine. Called
     * after every turn and after every state change, so no call site has to
     * remember which of the three it changed.
     */
    private void refresh() {
        scoreLabel.setText(String.valueOf(engine.getScore()));
        levelLabel.setText(String.valueOf(engine.getLevel()));
        linesLabel.setText(String.valueOf(engine.getLinesCleared()));

        boolean playedBefore = engine.getCurrent() != null;
        primaryButton.setText(playedBefore ? "New game" : "Start game");
        primaryButton.setEnabled(!engine.isRunning());
        boolean pausable = engine.isRunning() || engine.isPaused();
        pauseButton.setEnabled(pausable);
        pauseButton.setText(engine.isPaused() ? "Resume" : "Pause");
    }

    // -------------------------------------------------------------- settings

    /**
     * The player's own dialog: effects on or off, how loud, and how fast a
     * fresh stack falls. Shown modally over this panel, the same way the
     * desktop's other hosted apps confirm a destructive action or open a file;
     * nothing here can leave the game's window behind.
     */
    public void showSettings() {
        JCheckBox soundCheck = new JCheckBox("Sound effects", settings.isSoundEnabled());
        JSlider volume = new JSlider(0, 100, settings.getVolumePercent());
        volume.setMajorTickSpacing(25);
        volume.setPaintTicks(true);
        volume.setPaintLabels(true);
        JSlider speed = new JSlider(TetrisSettings.MIN_START_DELAY_MS,
                TetrisSettings.MAX_START_DELAY_MS, settings.getStartDelayMs());
        speed.setMajorTickSpacing(150);
        speed.setPaintTicks(true);
        speed.setInverted(true);  // the slider reads left = slow, right = fast
        JLabel speedHint = new JLabel("Fall delay at level 1, in milliseconds.");

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(4, 4, 4, 4));
        JPanel volumeRow = new JPanel(new BorderLayout(6, 0));
        volumeRow.add(new JLabel("Volume:"), BorderLayout.WEST);
        volumeRow.add(volume, BorderLayout.CENTER);
        JPanel speedRow = new JPanel(new BorderLayout(6, 0));
        speedRow.add(new JLabel("Speed:"), BorderLayout.WEST);
        speedRow.add(speed, BorderLayout.CENTER);
        form.add(soundCheck);
        form.add(volumeRow);
        form.add(speedRow);
        form.add(speedHint);

        int choice = JOptionPane.showConfirmDialog(this, form, "Tetris Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            audio.play(TetrisAudio.Effect.CANCEL);
            return;
        }

        settings.setSoundEnabled(soundCheck.isSelected());
        settings.setVolumePercent(volume.getValue());
        settings.setStartDelayMs(speed.getValue());
        settings.applyTo(audio);
        engine.setBaseDelayMs(settings.getStartDelayMs());
        if (engine.isRunning()) {
            timer.setDelay(engine.getTickDelayMs());
        }
        settings.save();
        audio.play(TetrisAudio.Effect.CLICK);
        logger.log(Level.FINE, "Tetris settings: sound=" + settings.isSoundEnabled()
                + " volume=" + settings.getVolumePercent()
                + " startDelay=" + settings.getStartDelayMs());
    }

    // ---------------------------------------------------------------- close

    /**
     * Stops the clock and hands the mixer's lines back when the window goes
     * away -- a closed game must not leave a timer firing at an engine nobody
     * can see, in either desktop. Never throws: {@code removeNotify} runs on
     * the way out of a window that may already be half-destroyed.
     */
    @Override
    public void removeNotify() {
        try {
            timer.stop();
            engine.stop();
            audio.shutdown();
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Tetris window close was not clean", e);
        }
        super.removeNotify();
    }

    /** Test and host hook: the engine behind this surface. */
    public TetrisEngine getEngine() {
        return engine;
    }

    /** Test and host hook: the board canvas behind this surface. */
    public TetrisBoard getBoard() {
        return board;
    }

    /**
     * The little canvas that shows the queued piece, so a player can plan the
     * stack. Paints the one {@link TetrisEngine} has queued, at the board's own
     * cell size, centred in its 4&times;4 field.
     */
    private final class NextPieceView extends JPanel {

        private static final int CELLS = 4;

        private NextPieceView() {
            setOpaque(false);
            setPreferredSize(new Dimension(
                    CELLS * TetrisBoard.CELL_PX, CELLS * TetrisBoard.CELL_PX));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Tetromino piece = engine.getNext();
            if (piece == null) {
                return;
            }
            int cell = TetrisBoard.CELL_PX;
            // Centre the shape in the field, on half-cell resolution so a
            // 2-wide and a 4-wide piece both sit square in the middle.
            int dx = (getWidth() - piece.width() * cell) / 2;
            int dy = (getHeight() - piece.height() * cell) / 2;
            for (int r = 0; r < piece.height(); r++) {
                for (int c = 0; c < piece.width(); c++) {
                    if (piece.covers(r, c)) {
                        paintBlock(graphics, dx + c * cell, dy + r * cell,
                                piece.color());
                    }
                }
            }
        }

        private void paintBlock(Graphics graphics, int px, int py, java.awt.Color color) {
            int cell = TetrisBoard.CELL_PX;
            graphics.setColor(color);
            graphics.fillRect(px, py, cell - 1, cell - 1);
            graphics.setColor(color.brighter());
            graphics.drawLine(px, py, px + cell - 2, py);
            graphics.drawLine(px, py, px, py + cell - 2);
            graphics.setColor(color.darker());
            graphics.drawLine(px + cell - 2, py, px + cell - 2, py + cell - 2);
            graphics.drawLine(px, py + cell - 2, px + cell - 2, py + cell - 2);
        }
    }
}
