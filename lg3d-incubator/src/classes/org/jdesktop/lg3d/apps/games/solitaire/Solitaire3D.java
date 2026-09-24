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
package org.jdesktop.lg3d.apps.games.solitaire;

import org.jdesktop.lg3d.apps.games.solitaire.SolitaireModel.Hint;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaButton;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * A native 3D Klondike solitaire. The window is a plain {@link Frame3D}; its
 * body is a {@link SolitaireView} live-texture table over a strip of
 * {@link AgendaButton} controls ({@code New / Undo / Draw / Auto / Hint}).
 *
 * <p>Interaction is click-driven (dev mode has no keyboard-focus routing):
 * click the stock to draw, click a face-up card (or a whole descending run) to
 * select it, then click a destination pile or foundation to move it there.
 * Clicking a selected card again sends it to a foundation when one accepts it.
 * {@code Auto} builds every available card onto the foundations, {@code Hint}
 * highlights one legal move, and {@code Undo} rolls back one move.</p>
 */
public class Solitaire3D extends Frame3D {

    private static final float DEPTH = 0.01f;

    private final SolitaireModel model = new SolitaireModel();
    private SolitaireView view;

    private int selZone = SolitaireModel.ZONE_NONE;
    private int selPile = -1;
    private int selPos = -1;
    private Hint hint;
    private String message;

    private float width;
    private float height;
    private float viewW;
    private float viewH;
    private float viewCenterY;
    private float btnH;
    private float bottomY;

    public static void main(String[] args) {
        new Solitaire3D();
    }

    public Solitaire3D() {
        super();
        try {
            setName("Solitaire 3D");
            computeLayout();
            setPreferredSize(new Vector3f(width, height, DEPTH));
            createUI();
            setVisible(true);
            changeEnabled(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Solitaire3D", e);
        }
    }

    private void computeLayout() {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        height = tk.getScreenHeight() * 0.5f;

        float topMargin = height * 0.10f;
        float bottomMargin = height * 0.03f;
        float controlH = height * 0.10f;     // one button row
        float gap = height * 0.025f;

        viewH = height - topMargin - bottomMargin - controlH - gap;
        viewW = viewH;                        // the table texture is square
        width = viewW;
        viewCenterY = height * 0.5f - topMargin - viewH * 0.5f;

        btnH = controlH;
        bottomY = -height * 0.5f + bottomMargin + btnH * 0.5f;
    }

    private void createUI() {
        view = new SolitaireView(viewW, viewH);
        view.setTranslation(0.0f, viewCenterY, 0.001f);
        view.setModel(model);
        view.setPickListener(new SolitaireView.PickListener() {
            public void picked(int zone, int pile, int pos) {
                onPick(zone, pile, pos);
            }
        });
        addChild(view);

        addButtonRow(
            new String[] { "New", "Undo", "Draw", "Auto", "Hint" },
            new Runnable[] {
                new Runnable() { public void run() { newGame(); } },
                new Runnable() { public void run() { undo(); } },
                new Runnable() { public void run() { draw(); } },
                new Runnable() { public void run() { auto(); } },
                new Runnable() { public void run() { hint(); } },
            });
    }

    private void addButtonRow(String[] labels, Runnable[] actions) {
        int n = labels.length;
        float gap = width * 0.015f;
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
    // Interaction
    // ------------------------------------------------------------------

    private void onPick(int zone, int pile, int pos) {
        hint = null;
        message = null;
        if (model.isWon()) {
            paint();
            return;
        }
        switch (zone) {
            case SolitaireModel.ZONE_STOCK:
                model.drawStock();
                clearSel();
                break;
            case SolitaireModel.ZONE_WASTE:
                onWaste();
                break;
            case SolitaireModel.ZONE_FOUNDATION:
                onFoundation(pile);
                break;
            case SolitaireModel.ZONE_TABLEAU:
                onTableau(pile, pos);
                break;
            default:
                break;
        }
        if (model.isWon()) {
            message = "You win! Press New for another deal";
            clearSel();
        }
        paint();
    }

    private void onWaste() {
        if (model.wasteTop() < 0) {
            return;
        }
        if (selZone == SolitaireModel.ZONE_WASTE) {
            // Second click on the selected waste card: try a foundation.
            if (!model.sendWasteToFoundation()) {
                message = "No foundation takes that card";
            }
            clearSel();
        } else {
            selZone = SolitaireModel.ZONE_WASTE;
            selPile = -1;
            selPos = -1;
        }
    }

    private void onFoundation(int f) {
        if (selZone == SolitaireModel.ZONE_WASTE) {
            if (!model.moveWasteToFoundation(f)) {
                message = "Illegal move";
            }
            clearSel();
        } else if (selZone == SolitaireModel.ZONE_TABLEAU) {
            if (selPos != model.tableauSize(selPile) - 1) {
                message = "Select a single card for a foundation";
            } else if (!model.moveTableauToFoundation(selPile, f)) {
                message = "Illegal move";
            }
            clearSel();
        }
    }

    private void onTableau(int pile, int pos) {
        if (selZone == SolitaireModel.ZONE_WASTE) {
            if (!model.moveWasteToTableau(pile)) {
                message = "Illegal move";
            }
            clearSel();
            return;
        }
        if (selZone == SolitaireModel.ZONE_TABLEAU) {
            if (selPile == pile) {
                // Clicking the selection again: send a lone top card to a
                // foundation if one accepts it, otherwise just deselect.
                if (selPos == pos && pos == model.tableauSize(pile) - 1) {
                    if (!model.sendTableauToFoundation(pile)) {
                        message = null;   // not an error, just nothing to do
                    }
                }
                clearSel();
                return;
            }
            if (!model.moveTableauToTableau(selPile, selPos, pile)) {
                message = "Illegal move";
            }
            clearSel();
            return;
        }
        // Nothing selected: select a movable face-up card / run.
        int n = model.tableauSize(pile);
        if (n > 0 && pos >= 0 && model.isFaceUp(pile, pos)
                && model.isMovableRun(pile, pos)) {
            selZone = SolitaireModel.ZONE_TABLEAU;
            selPile = pile;
            selPos = pos;
        }
    }

    // ------------------------------------------------------------------
    // Button actions
    // ------------------------------------------------------------------

    private void newGame() {
        model.newGame();
        clearSel();
        hint = null;
        message = null;
        view.setModel(model);
        paint();
    }

    private void undo() {
        if (!model.undo()) {
            message = "Nothing to undo";
        }
        clearSel();
        hint = null;
        paint();
    }

    private void draw() {
        model.drawStock();
        clearSel();
        hint = null;
        message = null;
        paint();
    }

    private void auto() {
        int n = model.autoComplete();
        clearSel();
        hint = null;
        message = n > 0 ? ("Sent " + n + " card" + (n == 1 ? "" : "s")
                + " to the foundations")
                : "No card can move to a foundation yet";
        paint();
    }

    private void hint() {
        Hint h = model.hint();
        hint = h;
        clearSel();
        message = h == null ? "No moves — draw from the stock" : null;
        paint();
    }

    private void clearSel() {
        selZone = SolitaireModel.ZONE_NONE;
        selPile = -1;
        selPos = -1;
    }

    private void paint() {
        view.setState(selZone, selPile, selPos, hint, message);
    }
}
