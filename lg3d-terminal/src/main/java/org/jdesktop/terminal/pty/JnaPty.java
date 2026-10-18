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

import com.sun.jna.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A pseudo-terminal allocated through native libc, by JNA, without JNI.
 *
 * <p>This owns the <i>master</i> end: the descriptor the emulator reads the
 * shell's output from and writes the user's keystrokes to. The slave end is a
 * device path - {@code /dev/pts/3} on Linux, {@code /dev/ttys003} on macOS -
 * which {@link PtySpawn} hands to the child as its standard streams.</p>
 *
 * <p><b>Why allocation never forks.</b> The obvious single call, {@code forkpty},
 * allocates a PTY <i>and</i> forks: in the child it returns 0 and execution
 * continues in the caller. Inside a multi-threaded desktop JVM that "caller" is a
 * half-born clone of the whole process - garbage collector and timer threads left
 * behind, locks inherited already held - which then runs Java code and survives as
 * a stray {@code java} process for every terminal window ever opened. It also
 * returns the child's pid in the parent, so treating any non-zero result as
 * failure rejects the one answer that means success. Both problems are structural
 * and neither applies to {@code posix_openpt} + {@code grantpt} +
 * {@code unlockpt} + {@code ptsname_r}, which allocate without forking; macOS has
 * no {@code posix_openpt}, but its {@code openpty} does the same allocation in one
 * call and, like the Linux path, never forks. Those are what this class uses, and
 * no {@code fork} may be introduced anywhere in this package.</p>
 *
 * <p>Every native call is made by {@link LibC}, which is where the errno
 * discipline and the platform-specific request numbers live.</p>
 */
public class JnaPty implements Pty {

    /**
     * True when this host can produce a PTY at all: JNA present, libc carrying the
     * allocation calls, and the kernel's PTY driver mounted.
     *
     * <p>A probe rather than an assumption, because "no PTY here" is an environment
     * fact a caller can act on - report it in the window, or fall back - whereas
     * the static {@code Native.register} this class used to carry answered the same
     * situation with an {@code UnsatisfiedLinkError} that nothing caught. It never
     * throws, and it never allocates.</p>
     */
    public static boolean isAvailable() {
        return LibC.available();
    }

    /** Why {@link #isAvailable()} is false; null when it is true. */
    public static String unavailableReason() {
        return LibC.unavailableReason();
    }

    private final int masterFd;
    private final String slaveName;
    private final PtyInputStream inputStream;
    private final PtyOutputStream outputStream;
    private volatile boolean closed = false;

    /**
     * Creates a PTY with the given dimensions already applied.
     *
     * <p>The size is set before the constructor returns, so the first program
     * spawned onto the slave sees the terminal the emulator thinks it has rather
     * than the kernel's default of no rows and no columns.</p>
     *
     * @param rows    initial number of rows
     * @param columns initial number of columns
     * @throws IOException if this host cannot build a PTY, or refused this one
     */
    public JnaPty(int rows, int columns) throws IOException {
        if (!Platform.isLinux() && !Platform.isMac()) {
            throw new IOException("PTY unavailable: a native pseudo-terminal needs "
                    + "Linux or macOS, this host reports "
                    + System.getProperty("os.name", "an unknown system"));
        }
        LibC.Master master = LibC.allocate();
        this.masterFd = master.fd;
        this.slaveName = master.slavePath;
        this.inputStream = new PtyInputStream(masterFd);
        this.outputStream = new PtyOutputStream(masterFd);
        try {
            LibC.setWinsize(masterFd, rows, columns, 0, 0);
        } catch (IOException | RuntimeException | Error e) {
            // An unusable master must not be left open: the caller only sees the
            // failure, so nothing downstream would ever close this descriptor.
            closeQuietly();
            throw e;
        }
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

    /** The size the kernel currently has for this PTY, as {@code {rows, columns}}. */
    int[] getWinsize() throws IOException {
        return LibC.getWinsize(masterFd);
    }

    @Override
    public void resize(int rows, int columns, int width, int height) throws IOException {
        if (closed) {
            throw new IOException("PTY is closed");
        }
        LibC.setWinsize(masterFd, rows, columns, width, height);
    }

    /**
     * Closes the master, once. Idempotent, and quiet about a descriptor the kernel
     * has already reclaimed: when the shell exits, reading the master answers
     * {@code EIO} and the slave is gone, so a close that reports {@code EBADF} is
     * reporting a pty that is already closed for every purpose that matters.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        LibC.close(masterFd);
    }

    /** Closes the master and keeps whatever failure the caller is reporting. */
    private void closeQuietly() {
        try {
            LibC.close(masterFd);
        } catch (IOException | RuntimeException | Error ignored) {
            // Nothing a caller can do about a failed release, mid-failure.
        }
    }

    /**
     * Reads the shell's output from the master. A blocking read, which is what the
     * session's reader thread wants: it parks in the kernel until there is
     * something to emulate, and {@link LibC#read} turns the shell's exit - Linux's
     * {@code EIO} on a master with no slaves left - into end of stream.
     */
    private final class PtyInputStream extends InputStream {
        private final int fd;

        PtyInputStream(int fd) {
            this.fd = fd;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            if (readInto(one, 0, 1) != 1) {
                return -1;
            }
            return one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            checkOpen();
            if (length == 0) {
                return 0;
            }
            return readInto(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            JnaPty.this.close();
        }

        private int readInto(byte[] buffer, int offset, int length) throws IOException {
            byte[] chunk = buffer;
            if (offset != 0) {
                // JNA maps a whole byte[] to the native buffer, so a read into the
                // middle of a caller's array goes through a temporary.
                chunk = new byte[length];
            }
            int n = LibC.read(fd, chunk, length);
            if (n > 0 && chunk != buffer) {
                System.arraycopy(chunk, 0, buffer, offset, n);
            }
            return n;
        }
    }

    /**
     * Writes the user's keystrokes to the master, as though typed on the slave.
     */
    private final class PtyOutputStream extends OutputStream {
        private final int fd;

        PtyOutputStream(int fd) {
            this.fd = fd;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            checkOpen();
            LibC.write(fd, buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            checkOpen();
            // A pty has no user-space buffer to push: whatever was written is
            // already the kernel's, and flow control there is the line
            // discipline's business, not an OutputStream flush's.
        }

        @Override
        public void close() throws IOException {
            JnaPty.this.close();
        }
    }

    /**
     * Streams are "no longer valid" once the PTY is closed, per {@link Pty}, and
     * an unbounded native call on a reclaimed descriptor is not how that should
     * surface.
     */
    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("PTY is closed");
        }
    }
}
