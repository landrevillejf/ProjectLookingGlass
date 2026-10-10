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

import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdesktop.lg3d.apps.texteditor.JavaDebugTools.Backend;
import org.jdesktop.lg3d.apps.texteditor.JavaDebugTools.Session;
import org.jdesktop.lg3d.apps.texteditor.JavaDebugTools.SessionListener;
import org.jdesktop.lg3d.apps.texteditor.JavaDebugTools.StepDepth;

/**
 * The production {@link Backend}: every {@code com.sun.jdi} call in the
 * debugger lives here, so {@link JavaDebugTools} stays headless-testable
 * behind its seam.
 *
 * <p>Launch contract (Espresso Phase 3): the target is a <em>child</em> JVM
 * this class owns, started with
 * {@code -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:PORT}
 * — loopback only, never a remote attach. We attach over the plain-socket
 * {@code dt_socket} transport with a bounded retry (the agent needs a moment
 * to bind), install a {@link BreakpointRequest} on each gutter line once the
 * main class is prepared, then pump the event queue into the
 * {@link SessionListener}. {@code stop()} (and every session end) disposes
 * the VM and {@code destroyForcibly()}s the child in a {@code finally} path,
 * so no debuggee can outlive the session.</p>
 *
 * <p>No {@code ExceptionRequest} is installed on purpose: the JVM throws many
 * internal exceptions (classpath probes and the like) that would pause the
 * user on code they cannot see; uncaught exceptions still print their trace
 * to the Output tab through the stdout pump before {@code VMDeath} arrives.</p>
 */
final class JdiDebugBackend implements Backend {

    /** How long to keep retrying the JDWP attach before giving up. */
    private static final long ATTACH_TIMEOUT_MS = 15_000;
    /** Attach retry interval. */
    private static final long ATTACH_RETRY_MS = 250;
    /** Event-queue poll slice, so the {@code running} flag is checked often. */
    private static final long EVENT_POLL_MS = 500;

    @Override
    public Session launch(Path classesDir, String mainClass, int port,
                          List<Integer> breakpointLines, String sourceFile,
                          SessionListener listener) throws IOException {
        Optional<Path> java = Toolchain.jdkTool("java");
        if (java.isEmpty()) {
            throw new IOException("no java launcher in this JDK");
        }
        ProcessBuilder pb = new ProcessBuilder(
                JavaDebugTools.debugLaunchArgs(java.get(), classesDir, mainClass, port));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        startOutputPump(process, listener);

        VirtualMachine vm = attachWithRetry(port, process);

        JdiSession session = new JdiSession(vm, process, listener,
                mainClass, sourceFile, breakpointLines);
        session.startEventLoop();
        return session;
    }

    /** Streams the child's merged stdout/stderr into the listener line by line. */
    private static void startOutputPump(Process process, SessionListener listener) {
        Thread pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    listener.onOutput(line);
                }
            } catch (IOException | RuntimeException re) {
                // stream closed on stop: the pump simply retires
            }
        }, "lg3d-debug-output-pump");
        pump.setDaemon(true);
        pump.start();
    }

    /**
     * Attaches to the child's JDWP socket, retrying until the agent has bound
     * or the deadline passes / the child dies. Loopback-only by construction.
     */
    private static VirtualMachine attachWithRetry(int port, Process process)
            throws IOException {
        AttachingConnector connector = null;
        for (AttachingConnector c
                : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(c.name())) {
                connector = c;
                break;
            }
        }
        if (connector == null) {
            throw new IOException("no dt_socket attaching connector in this JDK");
        }
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(Integer.toString(port));
        args.get("timeout").setValue("5000");

        long deadline = System.currentTimeMillis() + ATTACH_TIMEOUT_MS;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("target exited before we could attach (code "
                        + process.exitValue() + ")");
            }
            try {
                return connector.attach(args);
            } catch (IOException | IllegalConnectorArgumentsException ex) {
                last = (ex instanceof IOException io) ? io
                        : new IOException(ex.getMessage(), ex);
                try {
                    Thread.sleep(ATTACH_RETRY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw (last != null) ? last : new IOException("attach timed out");
    }

    /**
     * One live debugging session: owns the VM, the child process and the
     * single event-loop thread. All JDI mutation happens here. Commands
     * ({@code resume}/{@code step}) arrive on the EDT and only touch volatile
     * flags plus JDI calls, which are thread-safe by contract.
     */
    private static final class JdiSession implements Session {

        private final VirtualMachine vm;
        private final Process process;
        private final SessionListener listener;
        private final String mainClass;
        private final String sourceFile;
        private final List<Integer> breakpointLines;

        private volatile boolean running = true;
        private volatile boolean detached;
        private volatile ThreadReference suspendedThread;
        private EventRequestManager requests;
        private EventQueue events;

        JdiSession(VirtualMachine vm, Process process, SessionListener listener,
                   String mainClass, String sourceFile, List<Integer> breakpointLines) {
            this.vm = vm;
            this.process = process;
            this.listener = listener;
            this.mainClass = mainClass;
            this.sourceFile = sourceFile;
            this.breakpointLines = List.copyOf(breakpointLines);
        }

        void startEventLoop() {
            requests = vm.eventRequestManager();
            events = vm.eventQueue();
            ClassPrepareRequest prepare = requests.createClassPrepareRequest();
            prepare.addClassFilter(mainClass);
            prepare.enable();

            Thread loop = new Thread(this::pumpEvents, "lg3d-debug-event-loop");
            loop.setDaemon(true);
            loop.start();
        }

        /** The single JDI event consumer; lives until the VM dies or we stop. */
        private void pumpEvents() {
            try {
                while (running) {
                    EventSet set;
                    try {
                        set = events.remove(EVENT_POLL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (VMDisconnectedException ve) {
                        break;
                    }
                    if (set == null) {
                        continue; // poll slice elapsed, re-check running
                    }
                    boolean hold = false;
                    for (Event event : set) {
                        if (event instanceof ClassPrepareEvent cp) {
                            installBreakpoints(cp.referenceType());
                        } else if (event instanceof BreakpointEvent bp) {
                            deliverSuspended(bp.thread());
                            hold = true;
                        } else if (event instanceof StepEvent st) {
                            deliverSuspended(st.thread());
                            hold = true;
                        } else if (event instanceof VMDeathEvent) {
                            running = false;
                            listener.onExited("Program finished");
                        } else if (event instanceof VMDisconnectEvent) {
                            running = false;
                            listener.onExited("VM disconnected");
                        }
                    }
                    if (!hold) {
                        set.resume(); // class-prepare / death sets: let it run
                    }
                }
            } catch (RuntimeException ex) {
                if (running) {
                    running = false;
                    listener.onExited("VM disconnected: " + ex.getMessage());
                }
            } finally {
                cleanup();
            }
        }

        /**
         * Installs a breakpoint request on every location of each gutter
         * line; lines with no code throw {@code AbsentInformationException}
         * (or resolve to no locations) and are skipped.
         */
        private void installBreakpoints(com.sun.jdi.ReferenceType referenceType) {
            for (int line : breakpointLines) {
                try {
                    for (Location loc : referenceType.locationsOfLine(line)) {
                        requests.createBreakpointRequest(loc).enable();
                    }
                } catch (Exception skip) {
                    // no executable code at that line: nothing to install
                }
            }
        }

        /** Reads the suspended thread's stack/locals and hands them over. */
        private void deliverSuspended(ThreadReference thread) {
            suspendedThread = thread;
            List<String> frames = new ArrayList<>();
            List<String> locals = new ArrayList<>();
            String sourceName = sourceFile;
            int line = -1;
            try {
                List<StackFrame> all = thread.frames();
                for (int i = 0; i < all.size(); i++) {
                    StackFrame frame = all.get(i);
                    Location loc = frame.location();
                    String method = (loc.method() == null) ? "<unknown>" : loc.method().name();
                    frames.add(loc.declaringType().name() + "." + method
                            + " (" + loc.sourceName() + ":" + loc.lineNumber() + ")");
                    if (i == 0) {
                        sourceName = loc.sourceName();
                        line = loc.lineNumber();
                        collectLocals(frame, locals);
                    }
                }
            } catch (Exception re) {
                // partial capture is better than none
            }
            listener.onSuspended(sourceName, line, frames, locals);
        }

        /** Renders the top frame's visible variables as {@code name = value : type}. */
        private static void collectLocals(StackFrame frame, List<String> locals) {
            try {
                for (com.sun.jdi.LocalVariable var : frame.visibleVariables()) {
                    String value;
                    try {
                        Value v = frame.getValue(var);
                        if (v == null) {
                            value = "null";
                        } else if (v instanceof StringReference sr) {
                            value = "\"" + sr.value() + "\"";
                        } else if (v instanceof ObjectReference or) {
                            value = "<" + or.referenceType().name() + ">";
                        } else {
                            value = v.toString();
                        }
                    } catch (Exception ve) {
                        value = "?";
                    }
                    locals.add(var.name() + " = " + value + " : " + var.typeName());
                }
            } catch (Exception re) {
                // no locals this round
            }
        }

        @Override
        public void resume() {
            suspendedThread = null;
            try {
                vm.resume();
                listener.onResumed();
            } catch (RuntimeException re) {
                running = false;
                listener.onExited("VM disconnected");
            }
        }

        @Override
        public void step(StepDepth depth) {
            ThreadReference thread = suspendedThread;
            if (thread == null) {
                return;
            }
            int size = switch (depth) {
                case OVER -> StepRequest.STEP_OVER;
                case INTO -> StepRequest.STEP_INTO;
                case OUT -> StepRequest.STEP_OUT;
            };
            try {
                // one step request per thread at most: drop any stale one first
                for (StepRequest old : requests.stepRequests()) {
                    requests.deleteEventRequest(old);
                }
            } catch (RuntimeException ignored) {
                // manager already gone: the create below will fail loudly
            }
            try {
                requests.createStepRequest(thread, StepRequest.STEP_LINE, size).enable();
            } catch (RuntimeException re) {
                return;
            }
            resume();
        }

        @Override
        public void stop() {
            running = false;
            cleanup();
        }

        @Override
        public boolean alive() {
            return !detached && process.isAlive();
        }

        /** Detaches the VM and force-kills the child; idempotent, never throws. */
        private synchronized void cleanup() {
            if (detached) {
                return;
            }
            detached = true;
            try {
                vm.dispose();
            } catch (RuntimeException | Error ignored) {
                // best effort
            }
            try {
                process.destroyForcibly();
            } catch (RuntimeException | Error ignored) {
                // best effort
            }
        }
    }
}
