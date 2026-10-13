/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps Wonderland M7d: the live SoftphoneBackend — the winp → JDK
 * java.lang.Process supervision replacement for the wonderland softphone
 * module.
 *
 * The 2006 SoftphoneControlImpl spawned a bundled command-line SIP softphone
 * and drove it over a stdin/stdout line protocol, watching the child with the
 * winp library (a Windows-only process-handle toolkit that has no maintained
 * JDK 21 build and never ran on Linux). The modern equivalent needs no third
 * party at all: ProcessBuilder starts the child, ProcessHandle (the JDK's own
 * live process handle, JDK 9+) replaces every winp liveness query, and the
 * line vocabulary is unchanged so a host softphone is scripted exactly as the
 * 2006 binary was.
 *
 * Why it lives HERE and not in wonderland-modules: spawning a process is live
 * OS work, and the module keeps a hard headless floor with no such types. The
 * module owns the call STATE (SoftphoneControl) and reaches this mechanism
 * through SoftphonePlugin.installBackendFactory — the same installer-seam
 * idiom M7a used for the video surface and M7b for the rasteriser.
 *
 * The two halves of this class split along testability. The stdin/stdout
 * PROTOCOL — frame a command line, read an event line, hand it to
 * SoftphoneEvent.parse, tear down on EOF — is pure enough to prove headlessly,
 * so it is written against the small {@link EngineProcess}/{@link Launcher}
 * seam and ExternalSoftphoneBackendTest exercises it against an in-memory
 * engine, no subprocess involved. The {@link ProcessLauncher} that pays the
 * real ProcessBuilder/ProcessHandle is compile-checked by CI and verified live
 * on the desktop; it is deliberately not claimed from a headless run.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jdesktop.wonderland.modules.softphone.SoftphoneBackend;
import org.jdesktop.wonderland.modules.softphone.SoftphoneCommand;
import org.jdesktop.wonderland.modules.softphone.SoftphoneEvent;

/**
 * The external-process {@link SoftphoneBackend}. One instance owns one child
 * process and its reader thread; the child is launched per {@link #start} and
 * reaped by {@link #stop}.
 */
public final class ExternalSoftphoneBackend implements SoftphoneBackend {

    /**
     * A running softphone engine's three live channels. The production
     * implementation wraps a {@link Process}; the test injects an in-memory
     * stand-in so the framing/parsing logic is exercised with no subprocess.
     */
    interface EngineProcess {
        /** Where stdin command lines are written (the caller flushes). */
        BufferedWriter stdin();

        /** Where stdout event lines are read; blocks until a line arrives or
         *  the engine closes its end (EOF). */
        BufferedReader stdout();

        /** Whether the engine is alive right now (winp's liveness query). */
        boolean alive();

        /** Terminate the engine and release its channels; idempotent. */
        void terminate();
    }

    /** Starts an engine from a command line. Injected for testing. */
    @FunctionalInterface
    interface Launcher {
        EngineProcess launch(List<String> argv) throws IOException;
    }

    /** The default command that launches a host softphone; override with
     *  {@code -Dlg.wonderland.softphone.command}. Empty means "no engine
     *  configured" and start() honestly fails — the module then runs on the
     *  Noop backend's contract, never a phantom call. */
    public static final String COMMAND_PROPERTY = "lg.wonderland.softphone.command";

    private final Launcher launcher;
    private final SoftphoneBackend.EventHandler handler;

    private volatile EngineProcess engine;
    // The channels are captured ONCE at start and reused; re-wrapping them
    // per write/read would throw away the stream buffering (and, for stdout,
    // lose the reader's position between lines).
    private volatile BufferedWriter engineStdin;
    private volatile BufferedReader engineStdout;
    private volatile Thread reader;
    private final AtomicBoolean stopping = new AtomicBoolean();

    /** Build the production backend: real ProcessBuilder launcher. */
    public ExternalSoftphoneBackend(SoftphoneBackend.EventHandler handler) {
        this(new ProcessLauncher(), handler);
    }

    /** Build a backend over an injected launcher (test seam). */
    ExternalSoftphoneBackend(Launcher launcher, SoftphoneBackend.EventHandler handler) {
        this.launcher = launcher;
        this.handler = handler;
    }

    @Override
    public boolean start(String host, String user, int port, String number) {
        List<String> argv = command(host, port, user, number);
        if (argv.isEmpty()) {
            return false;                     // no engine configured
        }
        stopping.set(false);
        EngineProcess launched;
        try {
            launched = launcher.launch(argv);
        } catch (IOException e) {
            return false;                     // the child would not spawn
        }
        if (launched == null || !launched.alive()) {
            return false;
        }
        engine = launched;
        engineStdin = launched.stdin();
        engineStdout = launched.stdout();
        reader = new Thread(this::pumpStdout, "lg3d-softphone-stdout");
        reader.setDaemon(true);
        reader.start();
        return true;
    }

    @Override
    public boolean send(SoftphoneCommand command) {
        EngineProcess live = engine;
        if (live == null || !live.alive()) {
            return false;                     // a line to a dead engine
        }
        try {
            BufferedWriter in = engineStdin;
            in.write(command.toLine());
            in.newLine();
            in.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean isRunning() {
        EngineProcess live = engine;
        return live != null && live.alive();
    }

    @Override
    public void stop() {
        EngineProcess live = engine;
        if (live == null) {
            return;
        }
        stopping.set(true);
        send(SoftphoneCommand.shutdown());    // the graceful goodbye first
        live.terminate();
        engine = null;
        engineStdin = null;
        engineStdout = null;
    }

    /** Read the engine's stdout until EOF, translating each line to a typed
     *  event for the control. A child that closes its end is a death notice. */
    private void pumpStdout() {
        EngineProcess live = engine;
        if (live == null) {
            return;
        }
        try {
            String line;
            while ((line = engineStdout.readLine()) != null) {
                handler.onEvent(SoftphoneEvent.parse(line));
            }
        } catch (IOException expectedOnTeardown) {
            // closing a stream to end the pump is how stop() reaps the reader;
            // a genuine read failure simply looks like the same EOF
        } finally {
            // EOF: the process is gone. Announce it exactly once, and only if
            // we did not ask for it — stop() already reported the exit through
            // the control's own stop path, so a voluntary teardown stays quiet.
            if (!stopping.get()) {
                handler.onEvent(new SoftphoneEvent.Exited(0));
            }
        }
    }

    /**
     * Assemble the child command line: the configured engine plus the account
     * as SIP arguments, so the same modern softphone is dial-configured the
     * way the 2006 binary was. The registration handshake (callID / register)
     * is NOT here — the control drives it over stdin once the child is up.
     */
    static List<String> command(String host, int port, String user, String number) {
        String raw = System.getProperty(COMMAND_PROPERTY, "").strip();
        if (raw.isEmpty()) {
            return List.of();
        }
        List<String> argv = new ArrayList<>();
        for (String token : raw.split("\\s+")) {
            if (!token.isEmpty()) {
                argv.add(token);
            }
        }
        argv.add("--host");
        argv.add(host);
        argv.add("--port");
        argv.add(Integer.toString(port));
        argv.add("--user");
        argv.add(user);
        argv.add("--number");
        argv.add(number);
        return argv;
    }

    /**
     * The production launcher: {@link ProcessBuilder} to spawn, and the JDK's
     * own {@link ProcessHandle} (JDK 9+) for the liveness query that winp
     * used to answer. stdout and stderr are merged so a crash line still
     * reaches the event parser instead of vanishing.
     */
    static final class ProcessLauncher implements Launcher {
        @Override
        public EngineProcess launch(List<String> argv) throws IOException {
            Process process = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .start();
            ProcessHandle handle = process.toHandle();
            return new EngineProcess() {
                @Override
                public BufferedWriter stdin() {
                    return new BufferedWriter(
                            new OutputStreamWriter(process.getOutputStream(),
                                    StandardCharsets.UTF_8));
                }

                @Override
                public BufferedReader stdout() {
                    return new BufferedReader(
                            new InputStreamReader(process.getInputStream(),
                                    StandardCharsets.UTF_8));
                }

                @Override
                public boolean alive() {
                    return handle.isAlive();
                }

                @Override
                public void terminate() {
                    handle.destroy();
                    process.destroyForcibly();
                }
            };
        }
    }
}
