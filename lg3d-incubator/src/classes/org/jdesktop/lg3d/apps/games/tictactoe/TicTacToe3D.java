/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.games.tictactoe;

import java.util.ArrayDeque;
import java.util.Deque;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaButton;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * A native 3D tic-tac-toe. The window is a plain {@link Frame3D} using the
 * standard glassy decoration; its body is a {@link TicTacToeView} live-texture
 * board over a strip of {@link AgendaButton} controls.
 *
 * <p>Play is click-driven (dev mode has no keyboard-focus routing): click an
 * empty cell to drop your {@code X}; the unbeatable minimax opponent answers
 * with {@code O}. {@code New} restarts with you opening, {@code AI First} lets
 * the opponent open, and {@code Undo} rolls back your last move (and the AI's
 * reply) so it is your turn again.</p>
 */
public class TicTacToe3D extends Frame3D {

    private static final float DEPTH = 0.01f;

    private final TicTacToeModel model = new TicTacToeModel();
    private TicTacToeView view;

    /** Board snapshots (nine cells + side to move) for Undo. */
    private final Deque<int[]> history = new ArrayDeque<int[]>();

    private float width;
    private float height;
    private float viewW;
    private float viewH;
    private float viewCenterY;
    private float btnW;
    private float btnH;
    private float startX;
    private float bottomY;
    private float colGap;

    public static void main(String[] args) {
        new TicTacToe3D();
    }

    public TicTacToe3D() {
        super();
        try {
            setName("Tic Tac Toe 3D");
            computeLayout();
            setPreferredSize(new Vector3f(width, height, DEPTH));
            createUI();
            setVisible(true);
            changeEnabled(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start TicTacToe3D", e);
        }
    }

    private void computeLayout() {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        height = tk.getScreenHeight() * 0.5f;

        float topMargin = height * 0.10f;    // clears the corner window buttons
        float bottomMargin = height * 0.03f;
        float controlH = height * 0.11f;     // a single button row
        float gap = height * 0.025f;

        viewH = height - topMargin - bottomMargin - controlH - gap;
        viewW = viewH;                       // the board texture is square
        width = viewW;
        viewCenterY = height * 0.5f - topMargin - viewH * 0.5f;

        int cols = 3;
        colGap = width * 0.03f;
        btnW = (width - (cols - 1) * colGap) / cols;
        btnH = controlH;
        startX = -width * 0.5f + btnW * 0.5f;
        bottomY = -height * 0.5f + bottomMargin + btnH * 0.5f;
    }

    private void createUI() {
        view = new TicTacToeView(viewW, viewH);
        view.setTranslation(0.0f, viewCenterY, 0.001f);
        view.setModel(model);
        view.setCellListener(new TicTacToeView.CellListener() {
            public void cellClicked(int cell) {
                humanPlay(cell);
            }
        });
        addChild(view);

        addButton("New", 0, new Runnable() {
            public void run() { newGame(true); } });
        addButton("AI First", 1, new Runnable() {
            public void run() { newGame(false); } });
        addButton("Undo", 2, new Runnable() {
            public void run() { undo(); } });
    }

    private void addButton(String label, int col, Runnable action) {
        AgendaButton button = new AgendaButton(label, btnW, btnH,
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        action.run();
                    }
                });
        button.setTranslation(startX + col * (btnW + colGap), bottomY, 0.002f);
        addChild(button);
    }

    // ------------------------------------------------------------------
    // Game actions
    // ------------------------------------------------------------------

    private void humanPlay(int cell) {
        if (!model.isHumanTurn() || model.cell(cell) != TicTacToeModel.EMPTY) {
            return;
        }
        pushHistory();
        model.play(cell);
        if (!model.isGameOver()) {
            model.aiMove();       // the AI answers immediately
        }
        view.refresh();
    }

    private void newGame(boolean humanFirst) {
        history.clear();
        model.reset(humanFirst);
        if (!humanFirst) {
            model.aiMove();
        }
        view.refresh();
    }

    private void undo() {
        // Roll back to the position before the human's last move so it is the
        // human's turn again (undoing both the reply and the move).
        int[] snap = null;
        while (!history.isEmpty()) {
            snap = history.pop();
            if (snap[9] == TicTacToeModel.HUMAN) {
                break;
            }
        }
        if (snap == null) {
            return;
        }
        int[] cells = new int[9];
        System.arraycopy(snap, 0, cells, 0, 9);
        model.restore(cells, snap[9]);
        view.refresh();
    }

    private void pushHistory() {
        int[] snap = new int[10];
        int[] cells = model.snapshot();
        System.arraycopy(cells, 0, snap, 0, 9);
        snap[9] = model.getTurn();
        history.push(snap);
    }
}
