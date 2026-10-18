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

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * JNA-based pseudo-terminal implementation using native libc functions.
 * Supports Linux and macOS via forkpty/openpty.
 *
 * <p>This implementation uses JNA to call native PTY functions without
 * requiring JNI compilation. The PTY is created via forkpty() on Linux
 * or openpty() on macOS.</p>
 */
public class JnaPty implements Pty {
    
    private static final boolean IS_LINUX = Platform.isLinux();
    private static final boolean IS_MAC = Platform.isMac();
    
    static {
        Native.register(Platform.isMac() ? "c" : "libc.so.6");
    }
    
    // Native function declarations
    private static native int forkpty(int[] amaster, Pointer slave_name,
                                       Pointer termp, Pointer winp);
    private static native int openpty(int[] amaster, int[] aslave,
                                       byte[] slave_name, Pointer termp,
                                       Pointer winp);
    private static native int ioctl(int fd, int request, Pointer data);
    private static native int close(int fd);
    private static native int write(int fd, byte[] buf, int count);
    private static native int read(int fd, byte[] buf, int count);
    
    // ioctl requests
    private static final int TIOCSWINSZ = 0x5414; // Linux
    private static final int TIOCGWINSZ = 0x5413; // Linux
    
    private final int masterFd;
    private final String slaveName;
    private final PtyInputStream inputStream;
    private final PtyOutputStream outputStream;
    private volatile boolean closed = false;
    
    /**
     * Creates a new PTY with the specified initial dimensions.
     *
     * @param rows    initial number of rows
     * @param columns initial number of columns
     * @throws IOException if PTY creation fails
     */
    public JnaPty(int rows, int columns) throws IOException {
        if (!IS_LINUX && !IS_MAC) {
            throw new UnsupportedOperationException(
                "JnaPty only supports Linux and macOS");
        }
        
        int[] amaster = new int[1];
        Winsize winsize = new Winsize(rows, columns, 0, 0);
        String slaveNameValue = null;
        
        int result;
        if (IS_LINUX) {
            result = forkpty(amaster, null, null, winsize.getPointer());
            // forkpty doesn't return slave name
        } else {
            int[] aslave = new int[1];
            byte[] slaveNameBuf = new byte[256];
            result = openpty(amaster, aslave, slaveNameBuf, null, winsize.getPointer());
            if (result == 0) {
                int nullIndex = 0;
                while (nullIndex < slaveNameBuf.length && slaveNameBuf[nullIndex] != 0) {
                    nullIndex++;
                }
                slaveNameValue = new String(slaveNameBuf, 0, nullIndex);
            }
        }
        
        if (result != 0) {
            throw new IOException("Failed to create PTY: errno " + Native.getLastError());
        }
        
        this.masterFd = amaster[0];
        this.slaveName = slaveNameValue;
        this.inputStream = new PtyInputStream(masterFd);
        this.outputStream = new PtyOutputStream(masterFd);
    }
    
    @Override
    public InputStream getInputStream() {
        return inputStream;
    }
    
    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }
    
    @Override
    public String getSlaveName() {
        return slaveName;
    }
    
    @Override
    public int getMasterFd() {
        return masterFd;
    }
    
    @Override
    public void resize(int rows, int columns, int width, int height) throws IOException {
        if (closed) {
            throw new IOException("PTY is closed");
        }
        
        Winsize winsize = new Winsize(rows, columns, width, height);
        int result = ioctl(masterFd, TIOCSWINSZ, winsize.getPointer());
        if (result != 0) {
            throw new IOException("Failed to resize PTY: errno " + Native.getLastError());
        }
    }
    
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        
        int result = close(masterFd);
        if (result != 0) {
            throw new IOException("Failed to close PTY: errno " + Native.getLastError());
        }
    }
    
    /**
     * Winsize structure for terminal window size.
     */
    public static class Winsize extends Structure {
        public short ws_row;
        public short ws_col;
        public short ws_xpixel;
        public short ws_ypixel;
        
        public Winsize() {
            super();
        }
        
        public Winsize(int rows, int cols, int xpixel, int ypixel) {
            super();
            this.ws_row = (short) rows;
            this.ws_col = (short) cols;
            this.ws_xpixel = (short) xpixel;
            this.ws_ypixel = (short) ypixel;
        }
        
        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("ws_row", "ws_col", "ws_xpixel", "ws_ypixel");
        }
    }
    
    /**
     * Input stream for reading from the PTY master.
     */
    private static class PtyInputStream extends InputStream {
        private final int fd;
        
        PtyInputStream(int fd) {
            this.fd = fd;
        }
        
        @Override
        public int read() throws IOException {
            byte[] buf = new byte[1];
            int result = JnaPty.read(fd, buf, 1);
            if (result <= 0) {
                return -1;
            }
            return buf[0] & 0xFF;
        }
        
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            byte[] buf = new byte[len];
            int result = JnaPty.read(fd, buf, len);
            if (result <= 0) {
                return -1;
            }
            System.arraycopy(buf, 0, b, off, result);
            return result;
        }
    }
    
    /**
     * Output stream for writing to the PTY master.
     */
    private static class PtyOutputStream extends OutputStream {
        private final int fd;
        
        PtyOutputStream(int fd) {
            this.fd = fd;
        }
        
        @Override
        public void write(int b) throws IOException {
            byte[] buf = new byte[]{(byte) b};
            int result = JnaPty.write(fd, buf, 1);
            if (result < 0) {
                throw new IOException("Write failed: errno " + Native.getLastError());
            }
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            byte[] buf = new byte[len];
            System.arraycopy(b, off, buf, 0, len);
            int result = JnaPty.write(fd, buf, len);
            if (result < 0) {
                throw new IOException("Write failed: errno " + Native.getLastError());
            }
        }
    }
}
