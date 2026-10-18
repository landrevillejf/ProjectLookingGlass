/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland M9: the walk-in's company panel, tested the way the other panels
 * in this package are -- built, never shown, and driven through the methods the
 * owner calls rather than through synthetic mouse clicks.
 *
 * What is claimed here is the panel's own contract: the roster is the model's
 * rows and nothing else, the header counts them, Send only exists once a wire
 * owns it, and a typed line goes out and leaves the field empty without ever
 * posting itself into the transcript (the server's relay brings the author's own
 * copy back, and a local echo would be the same sentence twice).
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldVisitPanelTest {

    private WorldVisitPanel panel;
    private List<String> sent;

    @BeforeEach
    void setUp() {
        panel = new WorldVisitPanel();
        sent = new ArrayList<>();
    }

    private void wire() {
        panel.setSender(sent::add);
    }

    @Test
    @DisplayName("an empty roster says no one else is here, not zero of something")
    void emptyRosterHasItsOwnWords() {
        assertEquals(WorldVisitPanel.ALONE_LABEL, panel.rosterHeaderText());

        // One row is the visitor: a list of one is not a crowd, and "Users (1)"
        // is what an empty room would print about the person standing in it.
        panel.join("cell-1", "Tester");
        assertEquals(WorldVisitPanel.ALONE_LABEL, panel.rosterHeaderText());

        panel.join("cell-2", "Alice");
        assertEquals("Users (2)", panel.rosterHeaderText());

        panel.leave("cell-2");
        assertEquals(WorldVisitPanel.ALONE_LABEL, panel.rosterHeaderText());

        panel.leave("cell-1");
        assertEquals(0, panel.rosterSize());
        assertEquals(WorldVisitPanel.ALONE_LABEL, panel.rosterHeaderText());
    }

    @Test
    @DisplayName("the rows shown are the model's rows, in the model's order")
    void rosterRowsComeFromTheModel() {
        panel.join("c-2", "Chris");
        panel.join("c-1", "Alice");
        panel.join("c-3", "bob");

        // UserList sorts case-insensitively by name, and the panel must not
        // re-sort: two orders in one widget is a roster that shuffles.
        assertEquals(List.of("Alice", "bob", "Chris"), panel.rosterNames());
        assertEquals(3, panel.rosterSize());
        assertEquals(3, panel.rosterModel().getSize());
        assertEquals("Alice", panel.rosterModel().getElementAt(0));
    }

    @Test
    @DisplayName("a repeat id updates its row rather than adding a second one")
    void aReappearanceIsNotANewPerson() {
        panel.join("c-1", "Alice");
        panel.join("c-1", "Alice A.");

        assertEquals(List.of("Alice A."), panel.rosterNames());
        assertEquals(1, panel.rosterSize());
    }

    @Test
    @DisplayName("a blank name still gets a row, keyed by what is known")
    void anUnnamedPresenceIsStillAPresence() {
        panel.join("c-9", "   ");
        panel.join(null, "nobody");

        assertEquals(List.of("c-9"), panel.rosterNames());
    }

    @Test
    @DisplayName("leaving drops the row; leaving twice changes nothing")
    void leavingDropsTheRow() {
        panel.join("c-1", "Alice");
        panel.leave("c-1");
        panel.leave("c-1");
        panel.leave(null);

        assertTrue(panel.rosterNames().isEmpty());
    }

    @Test
    @DisplayName("Send is dark until a wire owns the button")
    void sendNeedsAWire() {
        panel.inputField().setText("hello");

        assertFalse(panel.sendTypedLine());
        assertTrue(sent.isEmpty());
        assertEquals("not connected to a world yet", panelStatus());

        wire();
        assertTrue(panel.sendTypedLine());
        assertEquals(List.of("hello"), sent);
        assertEquals("", panel.inputField().getText());
    }

    @Test
    @DisplayName("an empty field sends nothing and says so")
    void nothingToSendIsNotASentLine() {
        wire();
        panel.inputField().setText("   ");

        assertFalse(panel.sendTypedLine());
        assertTrue(sent.isEmpty());
        assertEquals("nothing to send", panelStatus());
    }

    @Test
    @DisplayName("a sent line is not echoed locally -- the relay brings it back")
    void sendingDoesNotPostToTheTranscript() {
        wire();
        panel.inputField().setText("exam room 1 is free");
        assertTrue(panel.sendTypedLine());

        // The transcript stays empty: the author's line arrives the same way
        // everyone else's does, under the name the server holds. A local echo
        // here would put two copies of one sentence on the screen, in two
        // different fonts of authority.
        assertEquals("", panel.transcriptText());
        assertTrue(panel.conversation().history().isEmpty());
    }

    @Test
    @DisplayName("a received line paints under the name the server stamped")
    void receivedLinesPaint() {
        panel.showMessage("Alice A", "the wait is 20 minutes", 1234L);

        assertEquals("Alice A: the wait is 20 minutes\n", panel.transcriptText());
        assertEquals(1, panel.conversation().history().size());
        assertEquals("Alice A",
                panel.conversation().history().get(0).getSender());
    }

    @Test
    @DisplayName("the local name is what the strip and the roster agree on")
    void localNameIsTrimmedAndHonest() {
        panel.setLocalName("  Tester  ");
        assertEquals("Tester", panel.getLocalName());

        panel.setLocalName(null);
        assertEquals("you", panel.getLocalName());
        panel.setLocalName("   ");
        assertEquals("you", panel.getLocalName());
    }

    @Test
    @DisplayName("a blank status is a blank line, not the word null")
    void statusHandlesBlank() {
        panel.setStatus("joining the world \u2026");
        assertEquals("joining the world \u2026", panelStatus());

        panel.setStatus(null);
        assertEquals(" ", panelStatus());
        panel.setStatus("");
        assertEquals(" ", panelStatus());
    }

    @Test
    @DisplayName("the audio controls read On and full until touched")
    void audioDefaultsAreOnAndFull() {
        assertEquals(100, panel.volume());
        assertTrue(panel.micOn());
    }

    @Test
    @DisplayName("the volume slider and the Mic pill drive the live call")
    void audioControlsDriveTheCall() {
        JavaSoundVoiceBridge call = new JavaSoundVoiceBridge();
        panel.setVoice(call);

        panel.setVolume(40);
        assertEquals(0.4, call.getOutputGain(), 1e-9);

        panel.setMicOn(false);
        assertTrue(call.isMuted());
        panel.setMicOn(true);
        assertFalse(call.isMuted());
    }

    /** The strip's text, read through the panel's own accessor. */
    private String panelStatus() {
        return panel.statusText();
    }
}
