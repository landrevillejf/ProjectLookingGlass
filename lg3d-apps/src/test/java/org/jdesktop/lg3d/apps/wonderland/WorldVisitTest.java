/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland M9: the decisions a walk-in visit makes, apart from its sockets.
 *
 * The session itself cannot be built in CI -- it needs a display for its window
 * and a server for its handshake, and neither is a thing a unit test should
 * pretend to have. What is asserted here is the vocabulary the visit is made of:
 * the name a person is listed under (which has to agree with the name the server
 * stamps, or one visitor appears twice in their own roster), the key a presence
 * row is filed under, the rule that says a loaded view cell is somebody else
 * rather than me, and the identity sent to the attach.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorldVisitTest {

    private static final CellID MINE = new CellID(1000L);

    private static WonderlandIdentity identity(String user, String full) {
        return new WonderlandIdentity(user, full, user + "@example.invalid");
    }

    @Test
    @DisplayName("the full name is the display name when there is one")
    void fullNameWins() {
        assertEquals("Alice A.",
                WorldVisit.displayName(identity("alice", "Alice A.")));
        assertEquals("Alice A.",
                WorldVisit.displayName(identity("alice", "  Alice A.  ")));
    }

    @Test
    @DisplayName("a blank full name falls to the username, then to a stated last resort")
    void displayNameFallsThrough() {
        assertEquals("alice", WorldVisit.displayName(identity("alice", null)));
        assertEquals("alice", WorldVisit.displayName(identity("alice", "   ")));
        assertEquals("guest", WorldVisit.displayName(identity(null, null)));
        assertEquals("guest", WorldVisit.displayName(identity("  ", "  ")));
        assertEquals("guest", WorldVisit.displayName(null));
    }

    @Test
    @DisplayName("the attach carries the name twice, the way the desktop's does")
    void identityCarriesTheNameItShows() {
        Properties props = WorldVisit.identityFor("Alice A.");
        assertEquals("Alice A.", props.getProperty("username"));
        assertEquals("Alice A.", props.getProperty("fullname"));

        // No name asked means the JVM's own user, which is what the server then
        // echoes back -- so the roster row and the transcript line agree.
        Properties plain = WorldVisit.identityFor(null);
        assertEquals(System.getProperty("user.name", "guest"),
                plain.getProperty("fullname"));
        assertEquals(plain.getProperty("username"),
                plain.getProperty("fullname"));
    }

    @Test
    @DisplayName("my own camera cell is the only one that is me")
    void onlyTheRegisteredViewCellIsLocal() {
        assertFalse(WorldVisit.isForeignView(MINE, MINE));
        assertTrue(WorldVisit.isForeignView(new CellID(2000L), MINE));
        // Before the handshake answers there is no "me" cell at all, so every
        // view cell is somebody else's -- including, for one moment, my own,
        // which the reload of the row corrects.
        assertTrue(WorldVisit.isForeignView(MINE, null));
    }

    @Test
    @DisplayName("a presence row is keyed by its cell, and two cells are two rows")
    void keysAreStableAndDistinct() {
        assertEquals("cell-" + MINE, WorldVisit.keyOf(MINE));
        assertNotEquals(WorldVisit.keyOf(MINE),
                WorldVisit.keyOf(new CellID(2000L)));
    }

    @Test
    @DisplayName("a visit in a JVM with no display says so instead of opening sockets")
    void headlessStartMakesNoAttempt() {
        // CI is headless, which is exactly the case this branch is for: the
        // scene never opened either, and a session that connects to a world
        // nobody is looking at is a name on other people's rosters.
        assertTrue(java.awt.GraphicsEnvironment.isHeadless(),
                "this suite's guard assumes a headless JVM");
        WorldVisitPanel panel = new WorldVisitPanel();
        try (WorldVisit visit = new WorldVisit("http://localhost:8080",
                "Tester", panel)) {
            visit.start();
            assertEquals("this JVM has no display to visit a world in",
                    panel.statusText());
        }
    }

    @Test
    @DisplayName("the panel a visit is built with is the panel it paints")
    void visitOwnsThePanelItWasGiven() {
        WorldVisitPanel panel = new WorldVisitPanel();
        WorldVisit visit = new WorldVisit("http://localhost:8080", null, panel);

        assertEquals(panel, visit.panel());
        // A blank name is the JVM's own user, and the roster header has to be
        // the same string the relay will stamp on this visitor's lines.
        assertEquals(System.getProperty("user.name", "guest"),
                panel.getLocalName());
        visit.close();
        visit.close();                        // closing twice is not an error
    }
}
