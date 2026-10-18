/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file except in
 * compliance with the License. A copy of the License is available at
 * http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.terminal.emulation;

/**
 * Manages the alternate screen buffer for full-screen applications (vim, less, etc.).
 * When activated, the current screen is saved and a fresh buffer is shown; when
 * deactivated, the original screen is restored.
 */
public final class AlternateScreen {
    
    private final int rows;
    private final int columns;
    private CharacterCell[][] savedGrid;
    private boolean active = false;
    
    public AlternateScreen(int rows, int columns) {
        this.rows = rows;
        this.columns = columns;
    }
    
    /** Activates the alternate screen, saving the current grid. */
    public void activate(CharacterCell[][] currentGrid) {
        if (active) return;
        savedGrid = new CharacterCell[rows][columns];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(currentGrid[r], 0, savedGrid[r], 0, columns);
        }
        active = true;
    }
    
    /** Deactivates the alternate screen, restoring the saved grid. */
    public CharacterCell[][] deactivate() {
        if (!active || savedGrid == null) return null;
        active = false;
        CharacterCell[][] restored = savedGrid;
        savedGrid = null;
        return restored;
    }
    
    /** Returns true if the alternate screen is currently active. */
    public boolean isActive() {
        return active;
    }
    
    /** Clears the alternate screen buffer. */
    public void clearAlternate(CharacterCell[][] grid) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                grid[r][c] = CharacterCell.SPACE;
            }
        }
    }
}
