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
package org.jdesktop.terminal.display;

/**
 * Marker interface for terminal output components. Implementations receive
 * data from a PTY and render it visually. The primary implementation is
 * {@link TerminalDisplay}.
 */
public interface TerminalOutput {
    
    /**
     * Releases any resources held by this output (timers, threads, etc.).
     * Called when the terminal session is closed.
     */
    void dispose();
}
