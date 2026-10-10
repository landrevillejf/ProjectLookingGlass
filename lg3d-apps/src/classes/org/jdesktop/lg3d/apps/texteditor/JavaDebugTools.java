/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Java Debugger" extension: a real single-file JDI debugger for
 * the Java/Kotlin IDE (Espresso Phase 3), driven through the {@code DEBUG}
 * capabilities of the Phase-0 chrome. It compiles the current document,
 * launches a <em>child</em> JVM with the JDWP agent listening on a
 * loopback-only ephemeral port, attaches with {@code com.sun.jdi}, installs a
 * breakpoint request on every gutter-toggled line, and pumps
 * suspend/step/resume events into the Debug panel (state, call stack, locals,
 * transcript) plus the program's stdout into the Output panel.
 *
 * <p><b>Security and reliability contract.</b> The target VM is a process this
 * extension owns, launched with {@code server=y,suspend=y} bound to
 * {@code 127.0.0.1} on a port reserved (and immediately released) via an
 * ephemeral {@link java.net.ServerSocket} — no remote attach, no public
 * socket. Attach, compile and run all carry hard timeouts; stopping (or the
 * VM dying) detaches and force-kills the child in a {@code finally} path.
 * Kotlin debugging is out of scope (there is no supported line-table story
 * without the Kotlin compiler daemon), so the action refuses politely.</p>
 *
 * <p><b>Testability.</b> Every {@code com.sun.jdi} call lives inside
 * {@link JdiDebugBackend}, behind the {@link Backend} seam; the state machine
 * and command dispatch in this class are exercised headless with a fake
 * backend, a fake compile runner and an inline event-delivery seam. No test
 * spawns a JVM (see {@code JavaDebugToolsTest}); the real attach path is
 * covered by one opt-in integration test.</p>
 */
public final class JavaDebugTools implements TextEditorExtension {

    /** The session lifecycle this extension's state machine walks through. */
    enum State { IDLE, COMPILING, LAUNCHING, RUNNING, PAUSED, STOPPING }

    /** Step depths for the three step commands. */
    enum StepDepth { OVER, INTO, OUT }

    /**
     * A compile-and-launch runner: executes {@code javac} (and only
     * {@code javac}) to completion, off the EDT. Production binds this to
     * {@link Toolchain#execute}; tests inject a fake.
     */
    interface CompileRunner {
        Toolchain.Result run(List<String> command, Path workDir, long timeoutMs);
    }

    /**
     * The seam around <em>all</em> JDI work. The production implementation is
     * {@link JdiDebugBackend}; tests substitute a fake that records commands
     * and drives the listener by hand.
     */
    interface Backend {
        /**
         * Launches the target VM ({@code suspend=y}, loopback {@code port}),
         * attaches to it, installs breakpoints on {@code breakpointLines}
         * (1-based, in {@code sourceFile}) and starts pumping events into
         * {@code listener}.
         *
         * @return the live session
         * @throws IOException when the child cannot start or attach fails
         */
        Session launch(Path classesDir, String mainClass, int port,
                       List<Integer> breakpointLines, String sourceFile,
                       SessionListener listener) throws IOException;
    }

    /** Commands the debugger sends to a live session. */
    interface Session {
        void resume();

        void step(StepDepth depth);

        /** Detaches from the VM and force-kills the child process. Never throws. */
        void stop();

        boolean alive();
    }

    /** Event callbacks from a live session; delivered on a worker thread. */
    interface SessionListener {
        /** The suspended thread is at {@code line} of {@code sourceName}. */
        void onSuspended(String sourceName, int line,
                         List<String> frames, List<String> locals);

        /** The VM resumed (e.g. after a class-prepare breakpoint install). */
        void onResumed();

        /** One line of the target program's merged stdout/stderr. */
        void onOutput(String line);

        /** The session ended; {@code why} is a short human phrase. */
        void onExited(String why);
    }

    /** Marshals a backend callback onto the EDT. */
    interface Deliverer {
        void run(Runnable onEdt);
    }

    /**
     * Starts background work off the EDT. Production spawns a virtual thread;
     * tests inject a runner that executes inline, which makes the whole
     * compile/launch state machine deterministically testable headless.
     */
    interface AsyncRunner {
        void start(Runnable work);
    }

    private static final Deliverer ASYNC_DELIVERER =
            onEdt -> javax.swing.SwingUtilities.invokeLater(onEdt);

    private static final AsyncRunner VIRTUAL_ASYNC =
            work -> Thread.ofVirtual().start(work);

    private static final long COMPILE_TIMEOUT_MS = 60_000;
    private static final long OUTPUT_FLUSH_LIMIT = 200; // buffered target lines

    private EditorContext editor;
    private DocumentContext currentDoc;
    private Backend backend;
    private CompileRunner compiler = Toolchain::execute;
    private Deliverer deliverer = ASYNC_DELIVERER;
    private AsyncRunner async = VIRTUAL_ASYNC;

    private State state = State.IDLE;
    private Session session;
    private final List<String> pendingOutput = new ArrayList<>();

    /** Public no-arg constructor: required by the {@link java.util.ServiceLoader} SPI. */
    public JavaDebugTools() {
        this.backend = new JdiDebugBackend();
    }

    // -- test seams ----------------------------------------------------------

    final void setBackendForTesting(Backend replacement) {
        this.backend = (replacement != null) ? replacement : new JdiDebugBackend();
    }

    final void setCompileRunnerForTesting(CompileRunner replacement) {
        this.compiler = (replacement != null) ? replacement : Toolchain::execute;
    }

    final void setDelivererForTesting(Deliverer replacement) {
        this.deliverer = (replacement != null) ? replacement : ASYNC_DELIVERER;
    }

    final void setAsyncRunnerForTesting(AsyncRunner replacement) {
        this.async = (replacement != null) ? replacement : VIRTUAL_ASYNC;
    }

    /** @return the current state-machine state (test seam). */
    State state() {
        return state;
    }

    // -- SPI -------------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        return new TextEditorManifest(
                "lg3d.java-debug",
                "Java Debugger",
                "1.0.0",
                "JDI breakpoint debugger for the current Java file: JDWP on loopback, "
                        + "gutter breakpoints, stack and locals in the Debug tab",
                "Project Looking Glass",
                java.util.EnumSet.of(
                        TextEditorPermission.READ,
                        TextEditorPermission.FILE_IO,
                        TextEditorPermission.DEBUG,
                        TextEditorPermission.TOOLBAR)
        );
    }

    @Override
    public String category() {
        return "Java/Kotlin";
    }

    @Override
    public void onEditorStarted(EditorContext ctx) {
        this.editor = ctx;
    }

    @Override
    public void onEditorStopping(EditorContext ctx) {
        stopSession("editor closing");
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public void onDocumentChanged(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public void onDocumentSaved(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("debug-start", "Start Debugging",
                        "Compile the document, launch a loopback JDWP VM and attach; "
                                + "gutter breakpoints are installed",
                        this::startDebugging),
                new ToolbarContribution("debug-resume", "Resume",
                        "Let the suspended program run until the next breakpoint",
                        () -> command(Session::resume, "Running")),
                new ToolbarContribution("debug-step-over", "Step Over",
                        "Run to the next line, stepping over calls",
                        () -> step(StepDepth.OVER)),
                new ToolbarContribution("debug-step-into", "Step Into",
                        "Run to the next executed line, entering calls",
                        () -> step(StepDepth.INTO)),
                new ToolbarContribution("debug-step-out", "Step Out",
                        "Run until the current method returns",
                        () -> step(StepDepth.OUT)),
                new ToolbarContribution("debug-stop", "Stop Debugging",
                        "Detach from the VM and kill the target process",
                        () -> stopSession("user stop"))
        );
    }

    // -- state machine -----------------------------------------------------------

    private void startDebugging() {
        EditorContext ctx = editor;
        DocumentContext doc = currentDoc;
        if (ctx == null || doc == null) {
            return;
        }
        if (state != State.IDLE) {
            ctx.showMessage("A debug session is already active (" + label(state) + ")");
            return;
        }
        JavaDiagnosticsTools.Lang lang =
                JavaDiagnosticsTools.detectKind(doc.getFileName(), doc.getFullText());
        if (lang != JavaDiagnosticsTools.Lang.JAVA) {
            ctx.showMessage(lang == JavaDiagnosticsTools.Lang.KOTLIN
                    ? "Kotlin debugging is not supported (Java files only)"
                    : "Not a Java file");
            return;
        }
        String fileName = doc.getFileName().isBlank() ? "Main.java" : doc.getFileName();
        final String source = doc.getFullText();
        List<Integer> breakpoints = ctx.getBreakpoints(doc.getFilePath());
        state = State.COMPILING;
        ctx.setDebugState("Compiling " + fileName + "…");
        async.start(() -> {
            CompileOutcome outcome;
            try {
                Path work = Toolchain.stageSource(source, fileName);
                Optional<Path> javac = Toolchain.jdkTool("javac");
                if (javac.isEmpty()) {
                    throw new IOException("no javac in this JDK");
                }
                Path classes = work.resolve("classes");
                Toolchain.Result result = compiler.run(
                        Toolchain.javacCommand(javac.get(), work.resolve(fileName), classes),
                        work, COMPILE_TIMEOUT_MS);
                outcome = new CompileOutcome(classes, source, result);
            } catch (IOException | RuntimeException re) {
                outcome = null;
            }
            final CompileOutcome done = outcome;
            deliverer.run(() -> finishCompile(done, fileName, breakpoints));
        });
    }

    /** A staged compile: where the classes landed, the source they came from. */
    private record CompileOutcome(Path classesDir, String source, Toolchain.Result result) {
    }

    /** Runs back on the EDT (via the deliverer): compile outcome -> launch. */
    private void finishCompile(CompileOutcome outcome, String fileName,
                               List<Integer> breakpoints) {
        EditorContext ctx = editor;
        if (ctx == null) {
            return;
        }
        if (outcome == null || !outcome.result().success()) {
            state = State.IDLE;
            ctx.setDebugState("Not debugging");
            if (outcome != null) {
                ctx.showOutput("javac", outcome.result().output());
            }
            ctx.showMessage(outcome == null
                    ? "Compile step failed (javac unavailable or staging error)"
                    : "Fix the compile errors first");
            return;
        }
        state = State.LAUNCHING;
        ctx.setDebugState("Launching…");
        async.start(() -> {
            final Path classes = outcome.classesDir();
            String mainClass = Toolchain.deriveClassName(outcome.source());
            if (mainClass == null || mainClass.isBlank()) {
                deliverer.run(() -> failLaunch(ctx, "the file declares no class to debug"));
                return;
            }
            int port;
            try {
                port = reserveLoopbackPort();
            } catch (IOException | RuntimeException re) {
                deliverer.run(() -> failLaunch(ctx, "no free loopback port: " + re.getMessage()));
                return;
            }
            try {
                session = backend.launch(classes, mainClass, port, breakpoints,
                        fileName, new FrontListener());
            } catch (IOException | RuntimeException re) {
                deliverer.run(() -> failLaunch(ctx, re.getMessage()));
                return;
            }
            final String launchedClass = mainClass;
            final int launchedPort = port;
            deliverer.run(() -> {
                state = State.RUNNING;
                ctx.setDebugState("Running " + launchedClass);
                ctx.appendDebugOutput("Attached to " + launchedClass
                        + " on 127.0.0.1:" + launchedPort);
                List<String> locations = new ArrayList<>();
                for (int line : breakpoints) {
                    locations.add(fileName + ":" + line);
                }
                ctx.setBreakpoints(locations);
                ctx.showMessage("Debugging " + fileName + " ("
                        + breakpoints.size() + " breakpoint(s))");
            });
        });
    }

    private void failLaunch(EditorContext ctx, String why) {
        state = State.IDLE;
        session = null;
        ctx.setDebugState("Not debugging");
        ctx.appendDebugOutput("Launch failed: " + why);
        ctx.showMessage("Could not start the debugger: " + why);
    }

    /** One user command on the live session. */
    interface Action {
        void run(Session session);
    }

    private void command(Action action, String stateLabel) {
        EditorContext ctx = editor;
        Session s = session;
        if (ctx == null) {
            return;
        }
        if (s == null || !s.alive()) {
            ctx.showMessage("No active debug session");
            return;
        }
        action.run(s);
        state = State.RUNNING;
        ctx.setDebugState(stateLabel);
    }

    private void step(StepDepth depth) {
        command(s -> s.step(depth), "Stepping " + depth.name().toLowerCase());
    }

    private void stopSession(String why) {
        EditorContext ctx = editor;
        Session s = session;
        if (s != null) {
            state = State.STOPPING;
            s.stop();
        }
        session = null;
        state = State.IDLE;
        pendingOutput.clear();
        if (ctx != null && s != null) {
            ctx.setDebugState("Not debugging");
            ctx.showStack(List.of());
            ctx.showLocals(List.of());
            ctx.setBreakpoints(List.of());
            ctx.showMessage("Debug session stopped (" + why + ")");
        }
    }

    // -- backend listener -----------------------------------------------------------

    /** Adapts raw backend events to permission-gated, EDT-marshalled chrome updates. */
    private final class FrontListener implements SessionListener {

        @Override
        public void onSuspended(String sourceName, int line,
                                List<String> frames, List<String> locals) {
            deliverer.run(() -> {
                EditorContext ctx = editor;
                if (ctx == null) {
                    return;
                }
                state = State.PAUSED;
                ctx.setDebugState("Paused at " + sourceName + ":" + line);
                ctx.showStack(frames);
                ctx.showLocals(locals);
                flushOutput();
                ctx.navigateTo("", line); // path blank: the tab under debug
                ctx.showMessage("Paused at line " + line);
            });
        }

        @Override
        public void onResumed() {
            deliverer.run(() -> {
                EditorContext ctx = editor;
                if (ctx != null && state == State.RUNNING) {
                    ctx.appendDebugOutput("Resumed");
                }
            });
        }

        @Override
        public void onOutput(String line) {
            synchronized (pendingOutput) {
                pendingOutput.add(line);
                if (pendingOutput.size() >= OUTPUT_FLUSH_LIMIT) {
                    flushOutputNow();
                }
            }
        }

        @Override
        public void onExited(String why) {
            deliverer.run(() -> {
                EditorContext ctx = editor;
                session = null;
                state = State.IDLE;
                flushOutput();
                if (ctx != null) {
                    ctx.setDebugState("Not debugging");
                    ctx.showStack(List.of());
                    ctx.showLocals(List.of());
                    ctx.setBreakpoints(List.of());
                    ctx.showMessage("Debug session ended: " + why);
                }
            });
        }

        private void flushOutput() {
            synchronized (pendingOutput) {
                flushOutputNow();
            }
        }

        /** Moves buffered target output into the Output panel (still gated). */
        private void flushOutputNow() {
            EditorContext ctx = editor;
            if (ctx == null || pendingOutput.isEmpty()) {
                return;
            }
            ctx.showOutput("debug target", String.join("\n", pendingOutput));
            pendingOutput.clear();
        }
    }

    // -- pure helpers (headless-testable) -----------------------------------------

    /**
     * The child-JVM command: the JDWP agent listens (server) on loopback only,
     * suspends at startup until we attach, and assertions are on.
     */
    static List<String> debugLaunchArgs(Path java, Path classesDir,
                                        String mainClass, int port) {
        return List.of(java.toString(),
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:"
                        + port,
                "-ea",
                "-cp", classesDir.toString(),
                mainClass);
    }

    /**
     * Binds an ephemeral loopback port, reads it and releases it immediately,
     * so the child VM can own it. The tiny race window is acceptable: a failure
     * surfaces as an attach timeout, never a mis-bind (the agent binds, we only
     * attach — loopback-only by construction).
     */
    static int reserveLoopbackPort() throws IOException {
        try (java.net.ServerSocket socket =
                     new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Formats a state for status messages. */
    static String label(State s) {
        return switch (s) {
            case IDLE -> "idle";
            case COMPILING -> "compiling";
            case LAUNCHING -> "launching";
            case RUNNING -> "running";
            case PAUSED -> "paused";
            case STOPPING -> "stopping";
        };
    }
}
