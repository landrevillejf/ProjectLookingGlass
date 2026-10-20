/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps Wonderland M7d: the external-backend protocol proof. The framing
 * and parsing that ExternalSoftphoneBackend shares with the 2006 control is
 * exercised against an in-memory engine — no subprocess, no network, no audio
 * device — so CI asserts the exact wire the child would speak: commands are
 * newline-framed to stdin, stdout lines are translated to typed events, EOF
 * without a voluntary stop is a single death notice, and stop() says goodbye
 * before it terminates. The ProcessLauncher (ProcessBuilder + ProcessHandle)
 * is deliberately not run here; it is compile-checked and verified live.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jdesktop.wonderland.modules.softphone.SoftphoneBackend;
import org.jdesktop.wonderland.modules.softphone.SoftphoneCommand;
import org.jdesktop.wonderland.modules.softphone.SoftphoneEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExternalSoftphoneBackendTest {

    /** An engine that runs entirely in memory: stdin is a byte sink the test
     *  reads back, stdout is a pipe the test writes event lines into. */
    private static final class FakeEngine implements ExternalSoftphoneBackend.EngineProcess {

        final ByteArrayOutputStream capturedStdin = new ByteArrayOutputStream();
        final BufferedWriter in = new BufferedWriter(
                new OutputStreamWriter(capturedStdin, StandardCharsets.UTF_8));
        final PipedWriter outWriter = new PipedWriter();
        final PipedReader outReader;
        final BufferedReader out;
        volatile boolean alive = true;
        List<String> lastArgv = List.of();

        FakeEngine() throws IOException {
            outReader = new PipedReader(outWriter);
            out = new BufferedReader(outReader);
        }

        @Override
        public BufferedWriter stdin() {
            return in;
        }

        @Override
        public BufferedReader stdout() {
            return out;
        }

        @Override
        public boolean alive() {
            return alive;
        }

        @Override
        public void terminate() {
            alive = false;
            try {
                outWriter.close();
            } catch (IOException ignored) {
                // already closed
            }
        }

        String stdinText() throws IOException {
            in.flush();
            return capturedStdin.toString(StandardCharsets.UTF_8);
        }

        void emit(String line) throws IOException {
            outWriter.write(line);
            outWriter.write("\n");
            outWriter.flush();
        }
    }

    private final BlockingQueue<SoftphoneEvent> received = new ArrayBlockingQueue<>(16);
    private SoftphoneBackend backend;
    private FakeEngine engine;

    /** Records every event the backend delivers, off the reader thread. */
    private final SoftphoneBackend.EventHandler recorder = received::add;

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty(ExternalSoftphoneBackend.COMMAND_PROPERTY, "softphone --quiet");
        engine = new FakeEngine();
        backend = new ExternalSoftphoneBackend(argv -> {
            engine.lastArgv = argv;
            return engine;
        }, recorder);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ExternalSoftphoneBackend.COMMAND_PROPERTY);
        backend.stop();
    }

    private SoftphoneEvent awaitEvent() throws InterruptedException {
        SoftphoneEvent event = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(event, "an event within the timeout");
        return event;
    }

    private static void assertNotNull(Object value, String message) {
        assertTrue(value != null, message);
    }

    @Test
    @DisplayName("no configured engine: start honestly fails")
    void missingEngineConfigFails() {
        System.setProperty(ExternalSoftphoneBackend.COMMAND_PROPERTY, "");
        assertFalse(backend.start("sip.example", "alice", 5060, "20315"));
        assertFalse(backend.isRunning());
    }

    @Test
    @DisplayName("the child is launched with the account as SIP arguments")
    void startBuildsArgv() {
        assertTrue(backend.start("sip.example", "alice", 5060, "20315"));
        assertEquals(List.of(
                "softphone", "--quiet",
                "--host", "sip.example", "--port", "5060",
                "--user", "alice", "--number", "20315"), engine.lastArgv);
    }

    @Test
    @DisplayName("commands are newline-framed onto the engine's stdin")
    void commandsFrameToStdin() throws IOException {
        backend.start("sip.example", "alice", 5060, "20315");
        assertTrue(backend.send(SoftphoneCommand.callId("abc-123")));
        assertTrue(backend.send(SoftphoneCommand.register("alice")));
        assertTrue(backend.send(SoftphoneCommand.mute()));
        assertEquals("callID=abc-123\nregister alice\nmute\n", engine.stdinText());
    }

    @Test
    @DisplayName("stdout lines are translated to typed events")
    void stdoutLinesBecomeEvents() throws Exception {
        backend.start("sip.example", "alice", 5060, "20315");
        engine.emit("Softphone is visible");
        assertEquals(new SoftphoneEvent.Visible(true), awaitEvent());
        engine.emit("Softphone is connected");
        assertEquals(new SoftphoneEvent.Connected(true), awaitEvent());
    }

    @Test
    @DisplayName("a crash EOF is a single death notice the control did not ask for")
    void crashExitAnnouncesOnce() throws Exception {
        backend.start("sip.example", "alice", 5060, "20315");
        engine.terminate();                    // the child dies on its own
        assertEquals(new SoftphoneEvent.Exited(0), awaitEvent());
    }

    @Test
    @DisplayName("stop says goodbye, terminates, and stays quiet on the exit")
    void stopIsGracefulAndQuiet() throws Exception {
        backend.start("sip.example", "alice", 5060, "20315");
        received.clear();
        backend.stop();
        assertTrue(engine.stdinText().contains("Shutdown\n"));
        assertFalse(engine.alive);
        assertFalse(backend.isRunning());
        // a voluntary teardown must not also fire a phantom crash exit
        assertNull(received.poll(300, TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("a command to a dead engine reports false, never throws")
    void sendToDeadEngineFails() throws IOException {
        assertFalse(backend.send(SoftphoneCommand.mute())); // before start
        backend.start("sip.example", "alice", 5060, "20315");
        engine.alive = false;
        assertFalse(backend.send(SoftphoneCommand.mute()));
    }
}
