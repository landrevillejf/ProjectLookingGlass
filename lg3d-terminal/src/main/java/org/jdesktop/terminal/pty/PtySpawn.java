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
import com.sun.jna.Platform;
import java.io.IOException;

/**
 * Launches a program onto the slave side of a PTY with {@code posix_spawn},
 * without forking the JVM that hosts it.
 *
 * <p>This is the half that makes a terminal a terminal. {@link JnaPty} owns the
 * master, and that descriptor is the only door into the session - but the shell
 * has to be attached to the <i>slave</i>, and a {@code ProcessBuilder} child gets
 * pipes for its standard streams, so it never sees the pseudo-terminal at all and
 * the emulator is left reading a master nobody writes to. {@code posix_spawn} is
 * the one libc call that can hand a child the slave as descriptors 0, 1 and 2
 * from a multi-threaded JVM, because no Java code ever runs in the child: the
 * fork and exec happen inside {@code libc}, in a context that is safe to fork.</p>
 *
 * <p><b>Why the two parts are not optional.</b></p>
 * <ul>
 *   <li>{@code POSIX_SPAWN_SETSID} puts the child in a new session with no
 *   controlling terminal. Linux then makes the first terminal a session leader
 *   opens <i>without</i> {@code O_NOCTTY} become its controlling terminal - which
 *   is exactly the {@code addopen} below. Without the session the shell has a tty
 *   but no ctty, so Ctrl-C goes nowhere and job control is dead; with it the shell
 *   shows as {@code Ss} in {@code ps} and interrupts its own children.</li>
 *   <li>Closing the master in the child: a child that inherits the master keeps
 *   the pty alive after the shell exits, so the reader never sees EOF and the
 *   window goes on holding a session that has already ended.</li>
 * </ul>
 *
 * <p>{@code posix_spawn_file_actions_addtcsetpgrp_np} (glibc 2.35+) additionally
 * makes the child the terminal's <i>foreground</i> process group, which is what
 * routes {@code SIGINT} and {@code SIGTSTP} to the right job. Where it is missing
 * the shell still gets a controlling terminal - an interactive bash calls
 * {@code tcsetpgrp} for itself - so it is resolved as an option, never demanded.</p>
 */
final class PtySpawn {

    /**
     * Puts the child in a new session. The flag's value is not portable: glibc
     * spells it 0x80, Apple and the BSDs 0x00000040. One constant for both would
     * silently set a different flag - or none - on the other platform.
     */
    static final short POSIX_SPAWN_SETSID = Platform.isMac() ? (short) 0x40 : (short) 0x80;

    /** The glibc extension that makes the child the terminal's foreground pgrp. */
    static final String TCSETPGRP_ACTION = "posix_spawn_file_actions_addtcsetpgrp_np";

    /** The glibc extension that starts the child in another directory. */
    static final String CHDIR_ACTION = "posix_spawn_file_actions_addchdir_np";

    // posix_spawnattr_t is 336 bytes on glibc 2.43 / x86-64 and an opaque 128 on
    // macOS; posix_spawn_file_actions_t is 80 and 88. Each init call writes its
    // struct's whole layout, padding included, so a buffer sized from a guess
    // would corrupt the native heap JNA allocated for it. These are ceilings with
    // room for the padding both libc families document, not this machine's numbers.
    private static final int ATTR_BYTES = 512;
    private static final int ACTIONS_BYTES = 256;

    private PtySpawn() {
    }

    /**
     * True when this libc can make the child the terminal's foreground process
     * group, so a caller can tell "no job control on this host" apart from "the
     * shell refused to run".
     */
    static boolean supportsForegroundProcessGroup() {
        return LibC.optional(TCSETPGRP_ACTION) != null;
    }

    /**
     * Spawns {@code path} on the slave end of an already-allocated PTY.
     *
     * @param path      the program to execute, resolved through {@code PATH}
     * @param argv      its argument vector; {@code argv[0]} is the program name
     * @param envp      its complete environment - a spawned child inherits nothing
     * @param slavePath the PTY slave's device path
     * @param masterFd  the caller's master descriptor, closed in the child
     * @param dir       the working directory, or null to inherit this process's
     * @return the child's process id
     * @throws IOException if libc refused, naming the call and what it meant
     */
    static int spawn(String path, String[] argv, String[] envp, String slavePath,
                     int masterFd, String dir) throws IOException {
        Function attrInit = LibC.function("posix_spawnattr_init");
        Function attrSetFlags = LibC.function("posix_spawnattr_setflags");
        Function attrDestroy = LibC.function("posix_spawnattr_destroy");
        Function actionsInit = LibC.function("posix_spawn_file_actions_init");
        Function actionsDestroy = LibC.function("posix_spawn_file_actions_destroy");
        Function spawn = LibC.function("posix_spawnp");

        Memory attr = new Memory(ATTR_BYTES);
        attr.clear();
        Memory actions = new Memory(ACTIONS_BYTES);
        actions.clear();
        try {
            call(attrInit, "posix_spawnattr_init", attr);
            // The flags parameter is a `short int` in glibc's prototype, hence a
            // Short here: an Integer would write 4 bytes where 2 belong.
            call(attrSetFlags, "posix_spawnattr_setflags(POSIX_SPAWN_SETSID)",
                    attr, POSIX_SPAWN_SETSID);
            addDescriptorActions(actionsInit, actions, actionsAddOpen(), actionsAddDup2(),
                    actionsAddClose(), slavePath, masterFd, dir);

            int[] pid = new int[1];
            // posix_spawn reports its errno as its return value rather than in
            // errno, which is exactly what `call` renders.
            call(spawn, "posix_spawnp " + path, pid, path, actions, attr, argv, envp);
            return pid[0];
        } finally {
            // The actions struct owns a malloc'd array of actions from glibc 2.41
            // on, so a terminal opened and closed all day would leak one per
            // session without these. Both are best-effort: a destroy that fails
            // cannot change what the spawn already decided.
            destroy(attrDestroy, attr);
            destroy(actionsDestroy, actions);
        }
    }

    /**
     * Builds the child's descriptor table: the slave on 0, duplicated onto 1 and
     * 2, the master closed, and - where libc offers the extensions - a working
     * directory and a foreground process group.
     *
     * <p>Descriptor 0 is opened first and deliberately without {@code O_NOCTTY}:
     * that open is the moment the new session leader acquires its controlling
     * terminal, so asking for {@code O_NOCTTY} here would silently produce a shell
     * with no job control and no working interrupt key.</p>
     */
    private static void addDescriptorActions(Function init, Memory actions,
                                             Function addOpen, Function addDup2,
                                             Function addClose, String slavePath,
                                             int masterFd, String dir) throws IOException {
        call(init, "posix_spawn_file_actions_init", actions);
        call(addOpen, "posix_spawn_file_actions_addopen(0," + slavePath + ")",
                actions, 0, slavePath, LibC.O_RDWR, LibC.SLAVE_MODE);
        call(addDup2, "posix_spawn_file_actions_adddup2(0,1)", actions, 0, 1);
        call(addDup2, "posix_spawn_file_actions_adddup2(0,2)", actions, 0, 2);
        call(addClose, "posix_spawn_file_actions_addclose(" + masterFd + ")",
                actions, masterFd);
        if (dir != null && !dir.isEmpty()) {
            Function chdir = LibC.optional(CHDIR_ACTION);
            if (chdir == null) {
                throw new IOException("this libc has no " + CHDIR_ACTION
                        + ", so a terminal cannot start in " + dir);
            }
            call(chdir, CHDIR_ACTION + "(" + dir + ")", actions, dir);
        }
        Function tcsetpgrp = LibC.optional(TCSETPGRP_ACTION);
        if (tcsetpgrp != null) {
            // The parameter names the descriptor whose terminal to take over. An
            // older libc without this action answers 0 instead, and the shell
            // still works - it just starts out outside the foreground.
            call(tcsetpgrp, TCSETPGRP_ACTION, actions, 0);
        }
    }

    private static Function actionsAddOpen() throws IOException {
        return LibC.function("posix_spawn_file_actions_addopen");
    }

    private static Function actionsAddDup2() throws IOException {
        return LibC.function("posix_spawn_file_actions_adddup2");
    }

    private static Function actionsAddClose() throws IOException {
        return LibC.function("posix_spawn_file_actions_addclose");
    }

    /**
     * One libc call. These functions return an errno rather than -1, so non-zero
     * is failure and the number is the reason; the message names the call, which
     * is what a maintainer reading a crash report needs.
     */
    private static void call(Function function, String name, Object... args)
            throws IOException {
        int rc = function.invokeInt(args);
        if (rc != 0) {
            throw LibC.fail(name, rc);
        }
    }

    /** Runs a {@code destroy} on a spawn struct, ignoring a result nobody can use. */
    private static void destroy(Function function, Memory struct) {
        try {
            function.invokeInt(new Object[]{struct});
        } catch (RuntimeException | Error ignored) {
            // Cleanup never gets to replace the failure being reported.
        }
    }
}
