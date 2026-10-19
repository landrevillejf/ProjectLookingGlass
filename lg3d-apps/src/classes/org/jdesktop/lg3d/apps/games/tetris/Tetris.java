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

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The Tetris application: the {@link TetrisPanel} (the falling-stack game,
 * its sidebar of score / level / lines / next piece, and its settings)
 * presented as an integrated 3D desktop window via {@link TitledSwingWindow},
 * which hosts the Swing panel on a {@code SwingNode} quad below a draggable
 * glassy title bar.
 *
 * <p>Unlike the four other entries in the Games group -- Chess 3D, Solitaire
 * 3D, Sudoku 3D and Tic-Tac-Toe 3D, each a scene graph built out of Java 3D
 * nodes -- this game is conventional Swing, so it comes to the 3D desktop the
 * same way Calculator and Paint do: as a hosted panel. The 2D desktop does not
 * launch this class at all; {@code Desktop2DAppRegistry} maps its name to
 * {@link TetrisPanel} and embeds the very same panel into an internal frame.
 * One set of rules, one pixel output, two surfaces.</p>
 */
public class Tetris {

    public static void main(String[] args) {
        new Tetris();
    }

    public Tetris() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Tetris",
                new TetrisPanel(),
                TetrisPanel.WIDTH_PX,
                TetrisPanel.HEIGHT_PX);
    }
}
