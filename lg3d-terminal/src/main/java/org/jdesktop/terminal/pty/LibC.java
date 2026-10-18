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

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The terminal's whole libc surface, in one package-private class.
 *
 * <p>Every native call the PTY layer makes is declared here, so the
 * pseudo-terminal's kernel contract lives in exactly one file: the allocation
 * calls, the window {@code winsize} ioctl, the master file-descriptor I/O, the
 * signal and wait calls that end a shell, and the symbol inventory that decides
 * whether a PTY can be built at all. {@link JnaPty} allocates through it and
 * {@link PtySpawn} launches through it; neither repeats its plumbing.</p>
 *
 * <p><b>Two rules this class exists to enforce.</b></p>
 * <ol>
 *   <li><i>An error number is only meaningful for the call it came from.</i>
 *   JNA's {@code Native.getLastError()} keeps whatever the previous native call
 *   left behind, so a successful call does not clear it and a message built from
 *   it later describes a different failure - or none. Every method here reads
 *   errno on the line after the call that failed and renders it into an
 *   {@link IOException} with {@code strerror}'s text; callers never see a bare
 *   number. (The defect this rule removes reported "Failed to create PTY: errno
 *   2" for a PTY that had been created successfully - the 2 was stale, left by
 *   JNA's own library search at startup.)</li>
 *   <li><i>Nothing here may throw an {@code Error} at the desktop.</i> The
 *   terminal is launched on the shell's event-dispatch thread, and an
 *   {@code UnsatisfiedLinkError} or a {@code NoClassDefFoundError} for a missing
 *   JNA is an environment fact, not a crash. The symbol inventory is therefore
 *   resolved once, defensively, into {@link #available()} and
 *   {@link #unavailableReason()}; the caller that would have needed a symbol
 *   reports its absence as an {@code IOException}.</li>
 * </ol>
 *
 * <p>No {@code fork} or {@code vfork} appears anywhere in this layer, and none
 * may be added: the JVM that hosts the desktop is multi-threaded, so a child
 * that forks and returns to Java code instead of executing a program is a broken
 * process. The PTY is allocated without forking and the child is attached to it
 * with {@code posix_spawn} - see {@link JnaPty} and {@link PtySpawn}.</p>
 */
final class LibC {

    /** The errno values this layer branches on; every other one is only reported. */
    private static final int ESRCH = 3;    // no such process
    private static final int EIO = 5;      // master read once the last slave closed
    private static final int EBADF = 9;    // close of an already-gone descriptor

    // open() flags. O_NOCTTY's value is platform-specific, unlike the rest.
    static final int O_RDWR = 0x2;
    static final int O_NOCTTY = Platform.isMac() ? 0x200000 : 0x100;

    /** The mode {@code posix_spawn_file_actions_addopen} asks the slave for. */
    static final int SLAVE_MODE = 0666;

    private static final int F_GETFD = 1;
    private static final int F_SETFD = 2;
    private static final int FD_CLOEXEC = 1;

    /** {@code struct winsize} is four shorts: row, column, x-pixels, y-pixels. */
    private static final int WINSIZE_BYTES = 8;

    // The ioctls that write and read it. Linux numbers them from 0x5400; BSD and
    // macOS encode direction, type, number and size into one word, so a Linux
    // request passed to macOS ioctls is a different - and here, unwritable - one.
    static final long TIOCSWINSZ = Platform.isMac() ? 0x40087468L : 0x5414L;
    static final long TIOCGWINSZ = Platform.isMac() ? 0x40087467L : 0x5413L;

    static final int SIGTERM = 15;
    static final int SIGKILL = 9;

    /** A slave path is a {@code ptsname_r} buffer, and 256 is what it expects. */
    private static final int PATH_BYTES = 256;

    private static final String LIBRARY_NAME = Platform.isMac() ? "c" : "libc.so.6";

    /**
     * The clone device every pseudo-terminal on both supported platforms is
     * allocated from. Absent means no PTY driver - a container without
     * {@code /dev/pts} mounted, typically.
     */
    private static final String PTMX_DEVICE = "/dev/ptmx";

    /** Resolved libc, or null when no PTY can be built on this host. */
    private static final Symbols SYMBOLS;

    /** Why the PTY is unusable; null exactly when {@link #available()} is true. */
    private static final String REASON;

    static {
        Symbols symbols = null;
        String reason = null;
        try {
            symbols = new Symbols();
        } catch (Throwable t) {
            // Throwable, not Exception: an absent JNA is a NoClassDefFoundError
            // and a libc without the symbols is an UnsatisfiedLinkError. Both are
            // reasons the terminal cannot open; neither is a desktop crash.
            symbols = null;
            reason = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
        if (symbols != null && !new java.io.File(PTMX_DEVICE).exists()) {
            // Worth naming before the attempt rather than after: the kernel's own
            // answer here is ENOENT, and an errno 2 with no context is exactly the
            // unreadable report this layer was written to stop producing.
            reason = PTMX_DEVICE + " is missing, so this system has no "
                    + "pseudo-terminal driver to allocate one from";
            symbols = null;
        }
        SYMBOLS = symbols;
        REASON = reason;
    }

    private LibC() {
    }

    /** True when every native call the PTY layer needs resolved. */
    static boolean available() {
        return SYMBOLS != null;
    }

    /** Why {@link #available()} is false; null when it is true. */
    static String unavailableReason() {
        return REASON;
    }

    /** A function this host's libc may not carry, or null if it does not. */
    static Function optional(String name) {
        return SYMBOLS == null ? null : SYMBOLS.maybe(name);
    }

    /**
     * A function every PTY needs, resolved from the same inventory the
     * availability probe used. {@link NativeLibrary} caches its lookups, so
     * asking by name at the call site costs nothing and keeps this class the
     * only one that knows how libc is reached.
     *
     * @throws IOException if libc is unusable or does not carry the symbol
     */
    static Function function(String name) throws IOException {
        require();
        Function function = SYMBOLS.maybe(name);
        if (function == null) {
            throw new IOException("PTY unavailable: " + LIBRARY_NAME + " has no " + name);
        }
        return function;
    }

    // -- PTY allocation -----------------------------------------------------

    /**
     * Allocates one end of a pseudo-terminal and reports the other one's path.
     *
     * <p>On Linux that is {@code posix_openpt} + {@code grantpt} +
     * {@code unlockpt} + {@code ptsname_r}; on macOS {@code openpty} does the
     * same four in one call. Neither forks. The window size is not set here -
     * {@link #setWinsize} does it next, so both platforms share one path.</p>
     *
     * <p>The master comes back marked {@code FD_CLOEXEC}, because this JVM forks
     * child processes constantly (the web server's boot jar, browsers,
     * {@code java -jar} back-ends) and none of them may inherit a desktop
     * terminal's master: an inherited master keeps the shell's session alive
     * after its window is long closed.</p>
     *
     * @throws IOException if the kernel refused the pair, or libc cannot be used
     */
    static Master allocate() throws IOException {
        Symbols s = require();
        int master;
        String slavePath;
        if (Platform.isMac()) {
            int[] fds = new int[2];
            byte[] name = new byte[PATH_BYTES];
            if (s.openpty.invokeInt(new Object[]{fds, fds, name, null, null}) != 0) {
                throw fail("openpty", Native.getLastError());
            }
            master = fds[0];
            // The slave descriptor is of no use to us: the child opens the slave
            // by path, which is what makes it acquire a controlling terminal.
            if (fds[1] >= 0) {
                s.close.invokeInt(new Object[]{fds[1]});
            }
            slavePath = cString(name);
        } else {
            master = s.posixOpenpt.invokeInt(new Object[]{O_RDWR | O_NOCTTY});
            if (master < 0) {
                // Failure is < 0, never != 0: descriptor 0 is a legal answer.
                throw fail("posix_openpt", Native.getLastError());
            }
            if (s.grantpt.invokeInt(new Object[]{master}) != 0) {
                throw fail("grantpt", Native.getLastError());
            }
            if (s.unlockpt.invokeInt(new Object[]{master}) != 0) {
                throw fail("unlockpt", Native.getLastError());
            }
            byte[] name = new byte[PATH_BYTES];
            int rc = s.ptsnameR.invokeInt(
                    new Object[]{master, name, new NativeLong(name.length)});
            if (rc != 0) {
                throw fail("ptsname_r", Native.getLastError());
            }
            slavePath = cString(name);
        }
        if (slavePath.isEmpty()) {
            throw new IOException(
                    "the kernel allocated master fd " + master + " but named no slave device");
        }
        closeOnExec(s, master);
        return new Master(master, slavePath);
    }

    /** The two facts one end of a pseudo-terminal is known by. */
    static final class Master {
        final int fd;
        final String slavePath;

        Master(int fd, String slavePath) {
            this.fd = fd;
            this.slavePath = slavePath;
        }
    }

    // -- window size --------------------------------------------------------

    /**
     * Writes a PTY master's window size. The kernel raises {@code SIGWINCH} on
     * the terminal's foreground process group, which is how a shell and every
     * program running in it learn the display changed size.
     */
    static void setWinsize(int fd, int rows, int columns, int width, int height)
            throws IOException {
        Symbols s = require();
        Memory winsize = new Memory(WINSIZE_BYTES);
        // The four shorts are written before the pointer is handed over: an
        // ioctl reads native memory, and a struct whose fields were never
        // flushed sends 0x0 - a terminal with no rows and no columns, which
        // every full-screen program handles badly. That was the resize defect.
        winsize.setShort(0, (short) rows);
        winsize.setShort(2, (short) columns);
        winsize.setShort(4, (short) width);
        winsize.setShort(6, (short) height);
        if (s.ioctl.invokeInt(new Object[]{fd, new NativeLong(TIOCSWINSZ), winsize}) != 0) {
            throw fail("ioctl(TIOCSWINSZ)", Native.getLastError());
        }
    }

    /** Reads a PTY master's window size back, as {@code {rows, columns}}. */
    static int[] getWinsize(int fd) throws IOException {
        Symbols s = require();
        Memory winsize = new Memory(WINSIZE_BYTES);
        if (s.ioctl.invokeInt(new Object[]{fd, new NativeLong(TIOCGWINSZ), winsize}) != 0) {
            throw fail("ioctl(TIOCGWINSZ)", Native.getLastError());
        }
        return new int[]{Short.toUnsignedInt(winsize.getShort(0)),
                Short.toUnsignedInt(winsize.getShort(2))};
    }

    // -- master file-descriptor I/O ----------------------------------------

    /**
     * Reads from the master, blocking until the shell has written something.
     *
     * <p>{@code -1} means no more will ever arrive: once the last slave
     * descriptor is closed, Linux answers {@code EIO} on the master, and that is
     * how a terminal emulator learns the shell exited.</p>
     *
     * @return the number of bytes read, or -1 at end of stream
     * @throws IOException for a read failure that is not the shell going away
     */
    static int read(int fd, byte[] buffer, int length) throws IOException {
        Symbols s = requireReady();
        int n = s.read.invokeInt(new Object[]{fd, buffer, length});
        if (n > 0) {
            return n;
        }
        if (n == 0) {
            return -1;
        }
        int errno = Native.getLastError();
        if (errno == EIO || errno == EBADF) {
            return -1;
        }
        throw fail("read", errno);
    }

    /**
     * Writes to the master, exactly as if the user had typed. Loops over short
     * writes, which a pty can hand back when its input buffer is nearly full.
     */
    static void write(int fd, byte[] buffer, int offset, int length) throws IOException {
        Symbols s = requireReady();
        int done = 0;
        while (done < length) {
            int chunk = length - done;
            // libc's write() would accept a slice, but JNA maps a byte[] to the
            // whole array, so copy the remainder out. Keystrokes and command
            // lines are small; a big paste is the only case that loops.
            byte[] part = new byte[chunk];
            System.arraycopy(buffer, offset + done, part, 0, chunk);
            int n = s.write.invokeInt(new Object[]{fd, part, chunk});
            if (n <= 0) {
                // A blocking master never answers 0 for a non-empty write, so 0
                // here would spin; only a real failure may end the loop.
                throw fail("write", Native.getLastError());
            }
            done += n;
        }
    }

    /**
     * Closes the master. A descriptor the kernel already reclaimed is closed for
     * every purpose this class has, so {@code EBADF} is not reported.
     */
    static void close(int fd) throws IOException {
        Symbols s = requireReady();
        if (s.close.invokeInt(new Object[]{fd}) != 0) {
            int errno = Native.getLastError();
            if (errno != EBADF) {
                throw fail("close", errno);
            }
        }
    }

    // -- the child's life cycle --------------------------------------------

    /**
     * Sends a signal to a pid. A process that already exited answers
     * {@code ESRCH}, which is success for anyone whose goal was "that shell is
     * not running any more", so it is reported as such rather than as a failure.
     */
    static void kill(int pid, int signal) throws IOException {
        Symbols s = requireReady();
        if (s.kill.invokeInt(new Object[]{pid, signal}) != 0) {
            int errno = Native.getLastError();
            if (errno != ESRCH) {
                throw fail("kill", errno);
            }
        }
    }

    /**
     * Waits for one child and returns its exit code, or null when somebody else
     * reaped it first. OpenJDK's own process reaper can win that race for a child
     * this JVM did not {@code exec} itself, and there is no documented way to
     * opt out of it, so the answer here is to treat "gone, status unknown" as an
     * outcome rather than an error.
     *
     * <p>Blocks, so it belongs on a reaper thread and never on the EDT.</p>
     */
    static Integer waitPid(int pid) {
        Symbols s = requireReady();
        int[] status = new int[1];
        int n = s.waitpid.invokeInt(new Object[]{pid, status, 0});
        return n == pid ? Integer.valueOf(exitCodeOf(status[0])) : null;
    }

    /** Decodes a {@code waitpid} status: the exit code, or 128 + signal number. */
    static int exitCodeOf(int status) {
        int terminatedBy = status & 0x7f;
        return terminatedBy == 0 ? (status >> 8) & 0xff : 128 + terminatedBy;
    }

    // -- diagnostics --------------------------------------------------------

    /** A failure that names the call, the number and what the number means. */
    static IOException fail(String call, int errno) {
        return new IOException(message(call, errno));
    }

    /**
     * Renders an errno. The number is a parameter, never a lookup here: reading
     * it after the fact would read whatever {@code strerror} itself left behind.
     */
    static String message(String call, int errno) {
        String text = null;
        if (SYMBOLS != null) {
            try {
                text = SYMBOLS.strerror.invokeString(new Object[]{errno}, false);
            } catch (RuntimeException | Error ignored) {
                // A diagnostic never gets to be the reason nothing was reported.
            }
        }
        return call + " failed: " + errno
                + (text == null ? " (no error text available)" : " " + text);
    }

    // -- internals ----------------------------------------------------------

    private static Symbols require() throws IOException {
        Symbols s = SYMBOLS;
        if (s == null) {
            throw new IOException("PTY unavailable: " + REASON);
        }
        return s;
    }

    /**
     * For the calls that only ever run after an allocation already succeeded, so
     * a library that vanished mid-session is a programming error, not an
     * environment fact the user can act on.
     */
    private static Symbols requireReady() {
        Symbols s = SYMBOLS;
        if (s == null) {
            throw new IllegalStateException("PTY call made while unavailable: " + REASON);
        }
        return s;
    }

    private static void closeOnExec(Symbols s, int fd) throws IOException {
        int flags = s.fcntl.invokeInt(new Object[]{fd, F_GETFD, 0});
        if (flags < 0) {
            throw fail("fcntl(F_GETFD)", Native.getLastError());
        }
        if (s.fcntl.invokeInt(new Object[]{fd, F_SETFD, flags | FD_CLOEXEC}) < 0) {
            throw fail("fcntl(F_SETFD)", Native.getLastError());
        }
        if (!isCloseOnExec(fd)) {
            // Verified rather than assumed: an inherited master is the one
            // failure mode this call exists to prevent, and it is silent.
            throw new IOException("fcntl(F_SETFD, FD_CLOEXEC) on master fd " + fd
                    + " did not take effect, so a forked child could inherit the"
                    + " terminal");
        }
    }

    /**
     * True when {@code fd} will be closed by {@code exec}, so no child process of
     * this JVM can inherit it. Package-visible for the same reason the flag is set
     * here rather than at each call site: the master's {@code CLOEXEC} is a
     * property of the PTY, not of whoever happens to allocate one.
     */
    static boolean isCloseOnExec(int fd) throws IOException {
        Symbols s = require();
        int flags = s.fcntl.invokeInt(new Object[]{fd, F_GETFD, 0});
        if (flags < 0) {
            throw fail("fcntl(F_GETFD)", Native.getLastError());
        }
        return (flags & FD_CLOEXEC) != 0;
    }

    /** A native {@code char[]} read up to its first NUL. */
    private static String cString(byte[] bytes) {
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * The libc inventory. Its constructor is the only code that touches JNA's
     * library handle, and it is instantiated only if JNA itself loaded - which is
     * what lets {@link #available()} answer for a host with no JNA on its
     * classpath without throwing, and lets an optional {@code _np} symbol be
     * missed without a fuss.
     */
    private static final class Symbols {
        private final NativeLibrary library;
        final Function posixOpenpt;
        final Function grantpt;
        final Function unlockpt;
        final Function ptsnameR;
        final Function openpty;
        final Function ioctl;
        final Function read;
        final Function write;
        final Function close;
        final Function fcntl;
        final Function kill;
        final Function waitpid;
        final Function strerror;
        final Function spawnattrInit;
        final Function spawnattrSetflags;
        final Function spawnattrDestroy;
        final Function fileActionsInit;
        final Function fileActionsAddOpen;
        final Function fileActionsAddDup2;
        final Function fileActionsAddClose;
        final Function fileActionsDestroy;
        final Function spawnp;

        Symbols() {
            library = NativeLibrary.getInstance(LIBRARY_NAME);
            posixOpenpt = need("posix_openpt");
            grantpt = need("grantpt");
            unlockpt = need("unlockpt");
            ptsnameR = need("ptsname_r");
            openpty = need("openpty");
            ioctl = need("ioctl");
            read = need("read");
            write = need("write");
            close = need("close");
            fcntl = need("fcntl");
            kill = need("kill");
            waitpid = need("waitpid");
            strerror = need("strerror");
            spawnattrInit = need("posix_spawnattr_init");
            spawnattrSetflags = need("posix_spawnattr_setflags");
            spawnattrDestroy = need("posix_spawnattr_destroy");
            fileActionsInit = need("posix_spawn_file_actions_init");
            fileActionsAddOpen = need("posix_spawn_file_actions_addopen");
            fileActionsAddDup2 = need("posix_spawn_file_actions_adddup2");
            fileActionsAddClose = need("posix_spawn_file_actions_addclose");
            fileActionsDestroy = need("posix_spawn_file_actions_destroy");
            spawnp = need("posix_spawnp");
        }

        private Function need(String name) {
            Function function = library.getFunction(name);
            if (function == null) {
                throw new UnsatisfiedLinkError(LIBRARY_NAME + " has no " + name);
            }
            return function;
        }

        Function maybe(String name) {
            try {
                return library.getFunction(name);
            } catch (RuntimeException | Error ignored) {
                return null;
            }
        }
    }
}
