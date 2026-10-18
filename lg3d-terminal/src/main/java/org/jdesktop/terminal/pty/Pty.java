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
package org.jdesktop.terminal.pty;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstract pseudo-terminal (PTY) interface. A PTY provides a bidirectional
 * byte stream connected to a virtual terminal device, allowing a process to
 * interact with a terminal emulator as if it were a real terminal.
 *
 * <p>Implementations must be thread-safe for concurrent reads and writes.</p>
 */
public interface Pty extends Closeable {
    
    /**
     * Returns the input stream for reading data from the PTY (output from the
     * child process).
     */
    InputStream getInputStream();
    
    /**
     * Returns the output stream for writing data to the PTY (input to the
     * child process).
     */
    OutputStream getOutputStream();
    
    /**
     * Returns the name of the slave device (e.g., "/dev/pts/0").
     */
    String getSlaveName();
    
    /**
     * Returns the file descriptor for the master side of the PTY.
     */
    int getMasterFd();
    
    /**
     * Resizes the terminal window. This sends a SIGWINCH signal to the
     * foreground process group, notifying it of the new dimensions.
     *
     * @param rows    the new number of rows
     * @param columns the new number of columns
     * @param width   the new width in pixels (may be 0 if unknown)
     * @param height  the new height in pixels (may be 0 if unknown)
     * @throws IOException if the resize operation fails
     */
    void resize(int rows, int columns, int width, int height) throws IOException;
    
    /**
     * Closes the PTY, releasing all associated resources. After closing,
     * the input and output streams are no longer valid.
     */
    @Override
    void close() throws IOException;
}
