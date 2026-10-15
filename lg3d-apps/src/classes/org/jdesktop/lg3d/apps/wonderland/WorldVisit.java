/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland M9: the walk-in's own live session, and the reason a world browser
 * is a browser rather than a viewer.
 *
 * {@link WorldRender} reads a world over REST and builds a scene from it. That is
 * the world as authored -- correct, complete and entirely alone: nobody else is
 * in it, because nothing here is talking to anybody. This class opens the other
 * half, the same three {@code /comms} sockets {@link WonderlandWorld3D} opens for
 * the 3D desktop, driven by the same {@link CellClientSession}, and folds what
 * they carry into the panel that docks beside the scene:
 *
 * <ul><li><strong>who is here</strong> -- every session gets a view cell of its
 * own (the server's {@code ProtocolRouter} mints one per {@code SET_VIEW} and
 * pushes the foreign ones to everyone else), so the roster is not a list this
 * client invents. It is the set of cells the server said exist, read through
 * {@link ViewCell#getIdentity()}; a depart arrives as {@code cellUnloaded},
 * which is why the rows go away as reliably as they come.</li>
 *
 * <li><strong>what they say</strong> -- {@link ChatChannel} on the same session.
 * The name on a received line is the one the server stamped, never the one the
 * author claimed, and the author's own line comes back around the relay like
 * anyone else's; so there is exactly one transcript and it is the server's.</li>
 * </ul>
 *
 * <p><strong>What this does not do.</strong> It does not put another visitor's
 * <em>body</em> into the scene. The loaded cells arrive here as presence and as
 * chat traffic only; rendering them as avatars standing in the room is the
 * desktop bridge's job ({@code CellFrameBridge} over lg3d's scene graph), and the
 * walk-in window has no such bridge -- it is a Java 3D canvas the renderer built
 * from a REST read, and mounting a live cell into that graph is a separate piece
 * of work, not a switch this class can throw. A roster of three with two empty
 * chairs is therefore the honest current state of the walk-in, and the status
 * strip says "here" rather than "standing in front of you".</p>
 *
 * <p><strong>Threading.</strong> The handshake blocks, so it runs on a daemon
 * thread like {@code WonderlandWorld3D}'s walker; the cell callbacks arrive on
 * the transport thread; and Swing may only be touched on the EDT. Every hop is
 * explicit. Nothing here throws: a world that will not connect leaves the scene
 * up, the roster empty and a reason on the strip, because the walk-in is the
 * visitor's own window and a failed join is a sentence, not a stack trace.</p>
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.awt.GraphicsEnvironment;
import java.math.BigInteger;
import java.util.Properties;
import javax.swing.SwingUtilities;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.cell.ChatChannel;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.client.comms.ConnectionFailureException;
import org.jdesktop.wonderland.client.comms.WebSocketWonderlandSession;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.client.cell.view.ViewCell;

/**
 * One walk-in window's live visit: its session, its roster and its chat.
 * Construct after the window is up, {@link #start()} it, and {@link #close()} it
 * when the window goes away.
 */
public final class WorldVisit implements AutoCloseable {

    /** What the strip says before the handshake has answered. */
    static final String CONNECTING = "joining the world \u2026";

    /** What the strip says once it has. */
    static final String CONNECTED = "in the world \u00B7 chat is live";

    /**
     * The name the panel shows for the local visitor, whose own line comes back
     * from the relay under the name the server holds.
     */
    private final String localName;

    private final String server;

    private final WorldVisitPanel panel;

    /** The session this visit owns; null only before {@link #start()}. */
    private final CellClientSession client = new CellClientSession();

    private final ChatChannel chat = new ChatChannel(client);

    /** True once the sockets are open, so {@link #close()} knows what to undo. */
    private volatile boolean connected;

    private volatile boolean closed;

    /**
     * @param server    the world's http base, the same address the REST read used
     * @param localName the name the visitor is listed under; the server derives
     *                  the same string from the identity handed to
     *                  {@link #identityFor(String)}, so the two agree by
     *                  construction rather than by luck
     * @param panel     the face this visit paints
     */
    public WorldVisit(String server, String localName, WorldVisitPanel panel) {
        this.server = server;
        this.localName = (localName == null || localName.isBlank())
                ? System.getProperty("user.name", "guest") : localName.trim();
        this.panel = panel;
        panel.setLocalName(this.localName);
        chat.addListener((sender, text, sentAt, senderID) -> onLine(
                sender, text, sentAt, senderID));
        // The wire behind the button. Set once, here: a line typed before the
        // handshake answers is refused by the channel with its own reason
        // ("not in a world yet"), which is a truer sentence than a disabled
        // button that says nothing about why it is dark. The reason is shown as
        // the channel words it, never re-parsed here -- the day that string
        // changes, a parse would silently start lying about what went wrong.
        panel.setSender(text -> chat.send(text,
                reason -> say("not sent \u00B7 " + reason)));
    }

    /**
     * The identity the attach carries: the same two fields
     * {@code WonderlandWorld3D} sends, so a walk-in and a 3D-desktop session are
     * indistinguishable to the server.
     */
    public static Properties identityFor(String name) {
        String who = (name == null || name.isBlank())
                ? System.getProperty("user.name", "guest") : name.trim();
        Properties props = new Properties();
        props.setProperty("username", who);
        props.setProperty("fullname", who);
        return props;
    }

    /**
     * The name the roster and the relay both show for an identity: the full name
     * when there is one, the username next, and a stated last resort rather than
     * a {@code null} row. This is the server's rule ({@code
     * ProtocolRouter.displayName}) restated for the client's copy of the same
     * person, and the two must agree -- a roster that spells you one way and a
     * transcript that spells you another is two people in one list.
     */
    public static String displayName(WonderlandIdentity identity) {
        if (identity == null) {
            return "guest";
        }
        String full = identity.getFullName();
        if (full != null && !full.isBlank()) {
            return full.trim();
        }
        String user = identity.getUsername();
        return (user == null || user.isBlank()) ? "guest" : user.trim();
    }

    /**
     * Whether a loaded view cell is the local visitor's own camera rather than
     * somebody else's presence. The session registers exactly one view cell and
     * the cache connection remembers which; everything else of that type is
     * another person, including when the two share a name.
     */
    static boolean isForeignView(CellID cellId, CellID localViewCellId) {
        if (localViewCellId == null) {
            // No view cell registered yet -- which means this cell cannot be
            // it, so it is somebody else's, or a stray that the roster will
            // drop when the real registration arrives.
            return true;
        }
        return !localViewCellId.equals(cellId);
    }

    /** The panel this visit paints, so a caller can dock it and nothing else. */
    public WorldVisitPanel panel() {
        return panel;
    }

    /**
     * Open the sockets on a daemon thread. Returns at once; the panel's status
     * strip is where the outcome lands. Never throws, and never blocks the EDT.
     */
    public void start() {
        if (closed || GraphicsEnvironment.isHeadless()) {
            // A headless JVM has no window to dock a panel on, so there is no
            // visit to make -- and the probe that runs this far says so rather
            // than opening sockets nobody will ever read.
            panel.setStatus("this JVM has no display to visit a world in");
            return;
        }
        panel.setStatus(CONNECTING);
        // Registered before connect(): the server's foreign-cell flush starts
        // with the SET_VIEW handshake itself, so a listener added after the
        // connect call returns would miss the people already in the room.
        client.getCellCache().addCellCacheListener(
                new CellCache.CellCacheListener() {
                    @Override
                    public void cellLoaded(CellID cellId, Cell cell) {
                        present(cellId, cell);
                    }

                    @Override
                    public void cellLoadFailed(CellID cellId, String className,
                            CellID parentCellID, Throwable cause) {
                        // No row: a cell that never loaded is not a person who
                        // left, and a roster that lists-then-unlists an identity
                        // that never existed reads as a flicker of a ghost.
                    }

                    @Override
                    public void cellUnloaded(CellID cellId, Cell cell) {
                        absent(cellId);
                    }
                });
        chat.start();
        Thread walker = new Thread(this::connect, "WonderlandWorldVisit-session");
        walker.setDaemon(true);
        walker.start();
    }

    /** The blocking half, off the caller's thread. */
    private void connect() {
        try {
            client.connect(WebSocketWonderlandSession.forServer(
                    server, identityFor(localName)));
            connected = true;
            say(CONNECTED);
        } catch (ConnectionFailureException | RuntimeException failed) {
            // Leave the scene and the panel alone: the world the visitor can see
            // is real whether or not anyone else is in it.
            say("no one to join (" + failed.getMessage() + "); start one: "
                    + WorldCatalog.startCommand());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        chat.stop();
        if (connected) {
            connected = false;
            client.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Presence
    // ------------------------------------------------------------------

    /**
     * Put a loaded cell in the roster if it is a person. Loads arrive on the
     * transport thread, so the decision is made here and the painting hops.
     */
    private void present(CellID cellId, Cell cell) {
        if (!(cell instanceof ViewCell)) {
            return;                      // a room is not a person
        }
        WonderlandIdentity identity = ((ViewCell) cell).getIdentity();
        String name = displayName(identity);
        String key = keyOf(cellId);
        if (isForeignView(cellId, localViewCellId())) {
            paint(() -> panel.join(key, name));
        } else {
            // The visitor's own camera cell: its row is the local name, keyed by
            // the same id, so a re-registration updates rather than duplicates.
            paint(() -> panel.join(key, localName));
        }
    }

    private void absent(CellID cellId) {
        String key = keyOf(cellId);
        paint(() -> panel.leave(key));
    }

    /** The cell the session's own camera lives in, or null before it is known. */
    private CellID localViewCellId() {
        try {
            return client.getCellCacheConnection().getViewCellID();
        } catch (RuntimeException notYet) {
            return null;    // the handshake has not registered it yet
        }
    }

    /** The roster key for a cell: its id, stable across a reload. */
    static String keyOf(CellID cellId) {
        return "cell-" + cellId;
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private void onLine(String sender, String text, long sentAt,
            BigInteger senderID) {
        say("[" + sender + "] " + text);
        paint(() -> panel.showMessage(sender, text, sentAt));
    }

    private void paint(Runnable onEdt) {
        if (SwingUtilities.isEventDispatchThread()) {
            onEdt.run();
        } else {
            SwingUtilities.invokeLater(onEdt);
        }
    }

    private void say(String status) {
        paint(() -> panel.setStatus(status));
    }
}
