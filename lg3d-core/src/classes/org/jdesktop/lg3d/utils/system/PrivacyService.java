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
package org.jdesktop.lg3d.utils.system;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Privacy front-end for the LFS/BLFS system manager, normative in
 * {@code system-management-contract.md} §4.7. Tor is a <em>service</em>, so its
 * whole lifecycle (status / start / stop / restart) is delegated to the
 * {@link InitSystemService} §4.1 abstraction with {@value #TOR_SERVICE} as the
 * service name - this class adds <b>no tor-specific reimplementation</b> (§6).
 * On top of that it offers read-only views of the tor configuration
 * ({@value #TORRC}) and the tor notices log ({@value #TOR_LOG}).
 *
 * <p>Operation classes drive the UI's confirmation level:
 * <ul>
 *   <li>{@link Operation#TOR_STATUS} is <em>read-only</em> and runs unprivileged
 *       via {@link ProcessRunner} (§4.4: read-only probes MUST be unprivileged);</li>
 *   <li>{@link Operation#TOR_START}, {@link Operation#TOR_STOP} and
 *       {@link Operation#TOR_RESTART} are <em>mutating</em> - confirmed, then
 *       escalated via {@link PrivilegedRunner} (polkit).</li>
 * </ul>
 * The config/log views are plain file reads: they never spawn a process, never
 * write {@code /etc} or {@code /var/log} (§6), and are bounded to the trailing
 * {@value #MAX_READ_BYTES} bytes so a large log cannot exhaust memory. An
 * unreadable file (tor's log is often root-only) degrades to an honest
 * {@link FileContent#isReadable() unreadable} result rather than a fake empty
 * view.</p>
 *
 * <p>The detection, command-building, state parsing and file reading are pure /
 * side-effect-light and unit-testable ({@link #isTorManageable(Probes)},
 * {@link #buildCommand}, {@link #parseTorState}, {@link #readFile}); the live
 * {@link #probe()} and the {@code run*} helpers are thin wrappers that degrade
 * gracefully - an absent init system or an unsupported operation yields an empty
 * vector / a not-started result rather than throwing.</p>
 */
public final class PrivacyService {

    /** The tor service name driven through the init abstraction (§4.1). */
    public static final String TOR_SERVICE = "tor";

    /** The tor config the GUI may read (read-only for the GUI, §4.7). */
    public static final String TORRC = "/etc/tor/torrc";

    /** The tor notices log the GUI may read (read-only for the GUI, §4.7). */
    public static final String TOR_LOG = "/var/log/tor/notices.log";

    /** Trailing-byte cap on a read-only file view (bounds memory for the log). */
    static final int MAX_READ_BYTES = 256 * 1024;

    private PrivacyService() {
        // no instances
    }

    /** A §4.7 privacy operation, carrying its confirmation class. */
    public enum Operation {
        /** init-system {@code status tor} (§4.1) - read-only. */
        TOR_STATUS(false),
        /** init-system {@code start tor} (§4.1) - mutating. */
        TOR_START(true),
        /** init-system {@code stop tor} (§4.1) - mutating. */
        TOR_STOP(true),
        /** init-system {@code restart tor} (§4.1) - mutating. */
        TOR_RESTART(true);

        private final boolean mutating;

        Operation(boolean mutating) {
            this.mutating = mutating;
        }

        /** True if the operation changes state and needs polkit escalation. */
        public boolean isMutating() {
            return mutating;
        }
    }

    /** The tor daemon running state. */
    public enum TorState {
        /** The daemon is running. */
        RUNNING,
        /** The daemon is installed but not running. */
        STOPPED,
        /** The state could not be determined. */
        UNKNOWN
    }

    /**
     * The filesystem/PATH facts {@link #probe()} gathers and the pure predicates
     * consume. Splitting these out keeps detection unit-testable without tor or
     * a real init system present.
     */
    public static final class Probes {
        private final boolean torBinary;
        private final boolean initKnown;
        private final boolean torrc;
        private final boolean torLog;

        public Probes(boolean torBinary, boolean initKnown, boolean torrc, boolean torLog) {
            this.torBinary = torBinary;
            this.initKnown = initKnown;
            this.torrc = torrc;
            this.torLog = torLog;
        }

        boolean hasTorBinary() {
            return torBinary;
        }

        boolean hasInit() {
            return initKnown;
        }

        boolean hasTorrc() {
            return torrc;
        }

        boolean hasTorLog() {
            return torLog;
        }
    }

    // ------------------------------------------------------------------
    // Detection (pure predicates over Probes + a live probe()).

    /**
     * Live detection against this host: probes {@code PATH} for the tor binary,
     * asks {@link InitSystemService} whether a supervisor was detected, and
     * checks the two read-only files exist. Never spawns a process -
     * {@link ProcessRunner#isAvailable} and {@link InitSystemService#detect()}
     * are PATH/filesystem checks and the former is cached.
     */
    public static Probes probe() {
        return new Probes(
                ProcessRunner.isAvailable(TOR_SERVICE),
                InitSystemService.detect() != InitSystemService.InitSystem.UNKNOWN,
                Files.exists(Path.of(TORRC)),
                Files.exists(Path.of(TOR_LOG)));
    }

    /** True if the {@code tor} binary is present. */
    public static boolean isTorInstalled(Probes p) {
        return p != null && p.hasTorBinary();
    }

    /** True if a supported init system was detected (needed to drive tor). */
    public static boolean isInitKnown(Probes p) {
        return p != null && p.hasInit();
    }

    /**
     * True if tor can be driven: the binary is installed <em>and</em> a
     * supervisor was detected. Without both, the lifecycle vectors are empty.
     */
    public static boolean isTorManageable(Probes p) {
        return p != null && p.hasTorBinary() && p.hasInit();
    }

    /** True if {@value #TORRC} exists (read-only view available). */
    public static boolean hasTorrc(Probes p) {
        return p != null && p.hasTorrc();
    }

    /** True if {@value #TOR_LOG} exists (read-only view available). */
    public static boolean hasTorLog(Probes p) {
        return p != null && p.hasTorLog();
    }

    /** Live convenience: is the {@code tor} binary on this host? */
    public static boolean isTorAvailable() {
        return isTorInstalled(probe());
    }

    /** Live convenience: can tor be driven on this host? */
    public static boolean isTorManageable() {
        return isTorManageable(probe());
    }

    /** Live convenience: does {@value #TORRC} exist on this host? */
    public static boolean torrcPresent() {
        return hasTorrc(probe());
    }

    /** Live convenience: does {@value #TOR_LOG} exist on this host? */
    public static boolean torLogPresent() {
        return hasTorLog(probe());
    }

    // ------------------------------------------------------------------
    // Command building (pure; no shell interpolation).

    /**
     * Builds the argument vector for {@code op} by mapping it onto the matching
     * {@link InitSystemService.Operation} and delegating to
     * {@link InitSystemService#buildCommand} with {@value #TOR_SERVICE} as the
     * service name (§4.1). Returns an empty list when {@code op} is null or the
     * init system is unknown; the result never contains shell metacharacters and
     * is meant to be handed straight to {@link ProcessRunner} /
     * {@link PrivilegedRunner}.
     *
     * @param op   the privacy operation
     * @param init the detected init system
     */
    public static List<String> buildCommand(Operation op, InitSystemService.InitSystem init) {
        InitSystemService.Operation initOp = toInitOperation(op);
        if (initOp == null) {
            return Collections.emptyList();
        }
        return InitSystemService.buildCommand(init, initOp, TOR_SERVICE);
    }

    private static InitSystemService.Operation toInitOperation(Operation op) {
        if (op == null) {
            return null;
        }
        switch (op) {
            case TOR_STATUS:
                return InitSystemService.Operation.STATUS;
            case TOR_START:
                return InitSystemService.Operation.START;
            case TOR_STOP:
                return InitSystemService.Operation.STOP;
            case TOR_RESTART:
                return InitSystemService.Operation.RESTART;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // Pure output parsing (headless-testable; no process is spawned).

    /**
     * Parses an init-system {@code status tor} result into a {@link TorState}.
     * Recognises the systemd ({@code active (running)} / {@code inactive (dead)}),
     * runit ({@code run:} / {@code down:}) and openrc ({@code status: started} /
     * {@code status: stopped}) shapes; negative phrases are matched first so
     * "{@code not running}" never reads as running. With no usable text the exit
     * code decides ({@code 0} = running for a status command).
     */
    public static TorState parseTorState(int exitCode, String output) {
        String t = (output == null) ? "" : output.toLowerCase(Locale.ROOT);
        if (t.contains("inactive") || t.contains("dead") || t.contains("stopped")
                || t.contains("down:") || t.contains("not running") || t.contains("failed")) {
            return TorState.STOPPED;
        }
        if (t.contains("active (running)") || t.contains("run:") || t.contains("running")
                || t.contains("status: started") || t.contains("is running")) {
            return TorState.RUNNING;
        }
        return (exitCode == 0) ? TorState.RUNNING : TorState.UNKNOWN;
    }

    /** A short human label for a tor state; never null. */
    public static String describeTor(TorState state) {
        if (state == null) {
            return "Unknown";
        }
        return switch (state) {
            case RUNNING -> "Running";
            case STOPPED -> "Stopped";
            case UNKNOWN -> "Unknown";
        };
    }

    // ------------------------------------------------------------------
    // Read-only file views (torrc / notices.log). No process, no writes (§6).

    /** Reads {@value #TORRC} read-only. Never throws. */
    public static FileContent readTorrc() {
        return readFile(TORRC);
    }

    /** Reads {@value #TOR_LOG} read-only. Never throws. */
    public static FileContent readTorLog() {
        return readFile(TOR_LOG);
    }

    /**
     * Reads a text file for a read-only view, bounded to the trailing
     * {@value #MAX_READ_BYTES} bytes so a large log cannot exhaust memory. Pure
     * w.r.t. the process model (spawns nothing) and never throws: a missing path
     * yields {@code exists=false}, an unreadable one (tor's log is often
     * root-only) yields {@code readable=false}, and a read error is reported the
     * same way. The GUI never writes these files (§6).
     *
     * @param path the absolute path to read
     * @return an immutable {@link FileContent}; never null
     */
    public static FileContent readFile(String path) {
        if (path == null || path.isBlank()) {
            return new FileContent(false, false, "", false);
        }
        Path p;
        try {
            p = Path.of(path);
        } catch (RuntimeException e) {
            return new FileContent(false, false, "", false);
        }
        try {
            if (!Files.exists(p)) {
                return new FileContent(false, false, "", false);
            }
            if (!Files.isReadable(p)) {
                return new FileContent(true, false, "", false);
            }
            long size = Files.size(p);
            if (size <= MAX_READ_BYTES) {
                return new FileContent(true, true, Files.readString(p, StandardCharsets.UTF_8), false);
            }
            return new FileContent(true, true, readTail(p, MAX_READ_BYTES), true);
        } catch (IOException | RuntimeException e) {
            // A directory, a vanishing file, an I/O error: honest "unreadable".
            return new FileContent(true, false, "", false);
        }
    }

    private static String readTail(Path p, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long len = raf.length();
            long start = Math.max(0L, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) Math.min((long) maxBytes, len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Thin execution helpers (kept minimal; the app owns the UI wiring).

    /**
     * Runs a read-only operation unprivileged, delegating to
     * {@link InitSystemService#runRead} (§4.1). Returns a not-started
     * {@link ProcessRunner.Result} when the operation is mutating, unsupported,
     * or no init system was detected.
     */
    public static ProcessRunner.Result runRead(Operation op) {
        if (op == null || op.isMutating()) {
            return new ProcessRunner.Result(false, -1, "", "operation is mutating; use runMutating");
        }
        InitSystemService.Operation initOp = toInitOperation(op);
        if (initOp == null) {
            return new ProcessRunner.Result(false, -1, "", "unsupported operation: " + op);
        }
        return InitSystemService.runRead(InitSystemService.detect(), initOp, TOR_SERVICE);
    }

    /**
     * Runs a mutating operation with polkit escalation, delegating to
     * {@link InitSystemService#runMutating} (§4.1). Returns an ERROR result when
     * the operation is read-only, unsupported, or no init system was detected.
     * The caller MUST have confirmed first.
     */
    public static PrivilegedRunner.PrivilegedResult runMutating(Operation op) {
        if (op == null || !op.isMutating()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "operation is not mutating; use runRead", -1, "");
        }
        InitSystemService.Operation initOp = toInitOperation(op);
        if (initOp == null) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "unsupported operation: " + op, -1, "");
        }
        return InitSystemService.runMutating(InitSystemService.detect(), initOp, TOR_SERVICE);
    }

    // ------------------------------------------------------------------
    // Parsed value objects (immutable).

    /**
     * The result of a read-only file view: whether the file exists, whether the
     * GUI could read it, its (bounded) text and whether that text was truncated
     * to the trailing window.
     */
    public static final class FileContent {
        private final boolean exists;
        private final boolean readable;
        private final String text;
        private final boolean truncated;

        public FileContent(boolean exists, boolean readable, String text, boolean truncated) {
            this.exists = exists;
            this.readable = readable;
            this.text = (text == null) ? "" : text;
            this.truncated = truncated;
        }

        /** True if the file exists on disk. */
        public boolean isExists() {
            return exists;
        }

        /** True if the GUI could read the file (exists and permitted). */
        public boolean isReadable() {
            return readable;
        }

        /** The (bounded) file text; empty when unreadable or missing. */
        public String getText() {
            return text;
        }

        /** True when the text is only the trailing window of a larger file. */
        public boolean isTruncated() {
            return truncated;
        }

        /** The number of lines in {@link #getText()} (0 when empty). */
        public int getLineCount() {
            if (text.isEmpty()) {
                return 0;
            }
            int count = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    count++;
                }
            }
            return text.endsWith("\n") ? count : count + 1;
        }

        /** A one-line label for the viewer header; never null. */
        public String describe() {
            if (!exists) {
                return "not present";
            }
            if (!readable) {
                return "present, but not readable without privileges";
            }
            if (truncated) {
                return "showing the last " + (MAX_READ_BYTES / 1024) + " KiB of a larger file";
            }
            return getLineCount() + (getLineCount() == 1 ? " line" : " lines");
        }
    }
}
