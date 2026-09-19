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
package org.jdesktop.lg3d.apps.games.chess;

import java.util.List;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaButton;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * A native 3D chess game: the human plays White against a negamax + alpha-beta
 * engine ({@link ChessModel}). The window is a plain {@link Frame3D}; its body
 * is a {@link ChessView} live-texture board over a strip of
 * {@link AgendaButton} controls ({@code New / Undo / Flip}).
 *
 * <p>Interaction is click-driven (dev mode has no keyboard-focus routing):
 * click one of your pieces to select it — its legal destinations are dotted —
 * then click a destination to move; the engine replies immediately. Castling,
 * en passant and promotion (auto-queen) are all reachable through the same
 * click-a-square gesture. {@code Undo} rolls back your last move and the
 * engine's reply, and {@code Flip} turns the board around.</p>
 */
public class Chess3D extends Frame3D {

    private static final float DEPTH = 0.01f;
    private static final int[] EMPTY = new int[0];

    private final ChessModel model = new ChessModel();
    private ChessView view;

    private int selected = -1;
    private int[] legalTargets = EMPTY;

    private float width;
    private float height;
    private float viewW;
    private float viewH;
    private float viewCenterY;
    private float btnH;
    private float bottomY;

    public static void main(String[] args) {
        new Chess3D();
    }

    public Chess3D() {
        super();
        try {
            setName("Chess 3D");
            computeLayout();
            setPreferredSize(new Vector3f(width, height, DEPTH));
            createUI();
            setVisible(true);
            changeEnabled(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Chess3D", e);
        }
    }

    private void computeLayout() {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        height = tk.getScreenHeight() * 0.5f;

        float topMargin = height * 0.10f;
        float bottomMargin = height * 0.03f;
        float controlH = height * 0.11f;     // one button row
        float gap = height * 0.025f;

        viewH = height - topMargin - bottomMargin - controlH - gap;
        viewW = viewH;                        // the board texture is square
        width = viewW;
        viewCenterY = height * 0.5f - topMargin - viewH * 0.5f;

        btnH = controlH;
        bottomY = -height * 0.5f + bottomMargin + btnH * 0.5f;
    }

    private void createUI() {
        view = new ChessView(viewW, viewH);
        view.setTranslation(0.0f, viewCenterY, 0.001f);
        view.setModel(model);
        view.setSquareListener(new ChessView.SquareListener() {
            public void squareClicked(int square) {
                onSquare(square);
            }
        });
        addChild(view);

        addButtonRow(
            new String[] { "New", "Undo", "Flip" },
            new Runnable[] {
                new Runnable() { public void run() { newGame(); } },
                new Runnable() { public void run() { undo(); } },
                new Runnable() { public void run() { flip(); } },
            });
    }

    private void addButtonRow(String[] labels, Runnable[] actions) {
        int n = labels.length;
        float gap = width * 0.02f;
        float bw = (width - (n - 1) * gap) / n;
        float x0 = -width * 0.5f + bw * 0.5f;
        for (int i = 0; i < n; i++) {
            final Runnable action = actions[i];
            AgendaButton button = new AgendaButton(labels[i], bw, btnH,
                    new ActionNoArg() {
                        public void performAction(LgEventSource source) {
                            action.run();
                        }
                    });
            button.setTranslation(x0 + i * (bw + gap), bottomY, 0.002f);
            addChild(button);
        }
    }

    // ------------------------------------------------------------------
    // Game interaction
    // ------------------------------------------------------------------

    private void onSquare(int sq) {
        if (!model.isHumanTurn()) {
            return;
        }
        // A click on a legal destination of the current selection plays it.
        if (selected >= 0 && isLegalTarget(sq)) {
            ChessModel.Move m = model.makeHumanMove(selected, sq);
            selected = -1;
            legalTargets = EMPTY;
            view.clearSelection();
            if (m != null && !model.isGameOver()) {
                model.aiMove();
            }
            view.refresh();
            return;
        }
        // Otherwise, clicking one of our pieces (re)selects it.
        if (model.pieceAt(sq) > 0) {
            List<ChessModel.Move> moves = model.legalMovesFrom(sq);
            selected = sq;
            legalTargets = new int[moves.size()];
            for (int i = 0; i < moves.size(); i++) {
                legalTargets[i] = moves.get(i).getTo();
            }
            view.setSelection(sq, legalTargets);
        } else {
            selected = -1;
            legalTargets = EMPTY;
            view.clearSelection();
            view.refresh();
        }
    }

    private boolean isLegalTarget(int sq) {
        for (int t : legalTargets) {
            if (t == sq) {
                return true;
            }
        }
        return false;
    }

    private void newGame() {
        model.newGame();
        selected = -1;
        legalTargets = EMPTY;
        view.setModel(model);
    }

    private void undo() {
        // Roll back to the previous position with White (the human) to move:
        // undo the engine reply and then the human move that prompted it.
        int guard = 0;
        while (guard++ < 2) {
            if (!model.undo()) {
                break;
            }
            if (model.whiteToMove()) {
                break;
            }
        }
        selected = -1;
        legalTargets = EMPTY;
        view.setModel(model);
    }

    private void flip() {
        view.setFlipped(!view.isFlipped());
    }
}
