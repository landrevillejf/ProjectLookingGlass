/*
 * wonderland-server (M3): the protocol brain -- the transport-agnostic core the
 * Spring WebSocket handler (M3b) and the M3a in-JVM loopback both call. It speaks
 * M1's wire: every inbound ByteBuffer is unpacked with MessagePacker.unpack and
 * every outbound frame is packed with MessagePacker.pack against the short client
 * id the session registry assigned that connection. Nothing here knows sockets
 * from loopback pipes; it knows the cell protocol and the world.
 *
 * What it reproduces, faithfully, from the 2006 SGS server:
 *   - the view handshake: a CellHierarchyMessage SET_VIEW (the first thing the
 *     client's CellCacheConnection sends) is answered with a
 *     ViewCreateResponseMessage carrying a freshly minted view-cell id, then the
 *     whole world hierarchy (environment first, content after) is fanned out as
 *     LOAD_CELL messages -- the push that seeds the client cache. The minted view
 *     id alone is NOT functional: the client's ViewCell only configures the
 *     avatar when the view cell itself reaches ACTIVE (ViewCell.setStatus ->
 *     LocalAvatar.viewCellConfigured -> cache.setViewCell, the walk that lifts
 *     the whole tree to VISIBLE). So the router also registers a ViewCell-typed
 *     WorldCell for the session and pushes LOAD_CELL frames through the sink AFTER
 *     the synchronous reply frames -- the original's async socket timing, where
 *     CellCacheConnection had already stored viewCellID by the time a view cell's
 *     frame was processed; a frame delivered mid-sendAndWait would walk a ViewCell
 *     to ACTIVE against a not-yet-set id. The sync fan-out therefore carries only
 *     NON-view cells, and the async push is own-view first, foreign views after:
 *     a foreign view cell activating before the owning view would hijack the
 *     fresh session's LocalAvatar (a 2006 race the loopback's determinism would
 *     turn into a certainty).
 *   - the authoritative move: a MovableMessage MOVE_REQUEST on the channel
 *     connection mutates the WorldModel, answers the mover with a
 *     MovableMessageResponse (MOVE_MODIFIED) routed by the request's MessageID,
 *     and broadcasts the accepted transform to EVERY channel-connected session
 *     as a MovableMessage MOVED stamped with the mover's session id as senderID.
 *     That senderID is exactly what the client's MovableComponent compares to its
 *     own session id to suppress the echo of its own move -- so multiplayer apply
 *     and self-suppression both survive the rebuild.
 *   - a move for an unknown cell is refused with an ErrorMessage (a stale client
 *     cannot forge a cell); an edit (reparent) is applied and echoed as a
 *     CONFIGURE_CELL.
 *
 * The port adds one behaviour the 2006 server got from a module the wire never
 * saw, and keeps it to the same discipline as the move above: a CHAT line is
 * relayed to every channel-connected session with the sender's <em>name</em>
 * rewritten from the authenticated identity and the sender's session id stamped
 * as senderID. A client therefore cannot say something on someone else's
 * behalf, and the author's own copy comes back stamped as his own, which is the
 * same echo the move path suppresses. The line is addressed to the world's
 * environment cell -- the one cell every session has loaded, and so the only
 * address a client channel is guaranteed to be listening on.
 */
package org.jdesktop.wonderland.server.comms;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.jme.bounding.BoundingSphere;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellChannelConnectionType;
import org.jdesktop.wonderland.common.cell.CellEditConnectionType;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage.ActionType;
import org.jdesktop.wonderland.common.cell.messages.CellReparentMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessageResponse;
import org.jdesktop.wonderland.common.cell.messages.TextChatMessage;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.ErrorMessage;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.jdesktop.wonderland.common.messages.MessagePacker.PackerException;
import org.jdesktop.wonderland.common.messages.MessagePacker.ReceivedMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.view.ViewCellClientState;
import org.jdesktop.wonderland.server.net.InterestManager;
import org.jdesktop.wonderland.server.world.WorldCell;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProtocolRouter {

    /**
     * The default client-side view cell type, inert data like every stored class
     * name (the 2006 server likewise shipped the view cell's class name as world
     * data; the concrete public ViewCell is instantiable headless). A deployment
     * passes its own subclass -- with a MovableAvatarComponent, the way the
     * original avatar cell had one -- through the constructor.
     */
    public static final String VIEW_CELL_CLASS =
            "org.jdesktop.wonderland.client.cell.view.ViewCell";

    /**
     * How far a moving avatar's watch reaches (roadmap P5.4 interest). Generous
     * by the same rule as the InterestManager's own comment -- an omitted
     * recipient is a person who cannot see the world change, an extra one is a
     * packet, and the server picks packets. A deployment that wants tighter
     * interest passes its own InterestManager to the constructor.
     */
    public static final float WATCH_RADIUS = 100f;

    /**
     * The reach attributed to a moved cell's event when its own bounds carry no
     * radius. Combined with a viewer's {@link #WATCH_RADIUS} by the union rule.
     */
    public static final float DEFAULT_EVENT_RADIUS = 10f;

    private final WorldModel world;
    private final SessionRegistry registry;
    private final InterestManager interest;
    private final String viewCellClass;
    private final Map<BigInteger, CellID> viewCells = new ConcurrentHashMap<>();
    /** Per-session frames the transport must deliver AFTER its reply list (see flush). */
    private final Map<BigInteger, Deque<ByteBuffer>> deferred = new ConcurrentHashMap<>();

    @Autowired
    public ProtocolRouter(WorldModel world, SessionRegistry registry) {
        this(world, registry, VIEW_CELL_CLASS);
    }

    public ProtocolRouter(WorldModel world, SessionRegistry registry, String viewCellClass) {
        this(world, registry, viewCellClass, new InterestManager());
    }

    /**
     * The interest-aware form: a deployment injects the InterestManager whose
     * watches it maintains (a shared one lets the REST layer and the transport
     * consult one audience table). A fresh, empty InterestManager reproduces the
     * pre-P5.4 behaviour exactly, because an untracked session is a full-audience
     * recipient (see {@link InterestManager#isTracked}).
     */
    public ProtocolRouter(WorldModel world, SessionRegistry registry,
            String viewCellClass, InterestManager interest) {
        this.world = world;
        this.registry = registry;
        this.viewCellClass = viewCellClass;
        this.interest = interest;
    }

    /**
     * Register one of a session's connections with the registry so the wire has a
     * client id to stamp. Returns the assigned short. This is the router-facing
     * half of attach; the transport still owns its own handshake frames.
     */
    public short attach(ServerSession session, ConnectionType type) {
        return registry.attach(session, type);
    }

    public void detach(ServerSession session, ConnectionType type) {
        registry.detach(session, type);
        if (CellCacheConnectionType.CLIENT_TYPE.equals(type)) {
            deferred.remove(session.getID());
            // a departed session's watch goes with it, so its ghost is never a
            // recipient of a move it can no longer see
            interest.forget(session.getID());
            CellID viewCellID = viewCells.remove(session.getID());
            if (viewCellID != null && world.find(viewCellID) != null) {
                world.removeCell(viewCellID);
                broadcastUnload(viewCellID);
            }
        }
    }

    /**
     * Unpack and route one inbound frame for a session's connection, returning the
     * frames to deliver back to THAT connection. Cross-session broadcasts are
     * pushed straight to the other sessions' sinks as a side effect, so the caller
     * only handles its own reply list.
     */
    public List<ByteBuffer> routeInbound(ServerSession session, ConnectionType type,
            ByteBuffer inbound) {
        return routeMessage(session, type, unpack(inbound).getMessage());
    }

    /**
     * Route an already-unpacked message. A transport that peeked the frame to
     * tell an attach from a data message calls this so the payload is decoded
     * exactly once (a ByteBuffer cannot be unpacked twice without a rewind).
     */
    public List<ByteBuffer> routeMessage(ServerSession session, ConnectionType type,
            Message message) {
        if (CellCacheConnectionType.CLIENT_TYPE.equals(type)) {
            return onCacheMessage(session, message);
        }
        if (CellChannelConnectionType.CLIENT_TYPE.equals(type)) {
            return onChannelMessage(session, message);
        }
        if (CellEditConnectionType.CLIENT_TYPE.equals(type)) {
            return onEditMessage(session, message);
        }
        return new ArrayList<>();
    }

    /**
     * Pop the next frame the router owes this session AFTER its reply list --
     * the view-cell chain the 2006 async socket delivered once the client had
     * recorded its viewCellID. The transport calls this after delivering the
     * routeMessage replies (see the loopback and the WebSocket handler); an
     * empty queue means nothing is pending.
     */
    public ByteBuffer flush(ServerSession session) {
        Deque<ByteBuffer> queue = deferred.get(session.getID());
        if (queue == null) {
            return null;
        }
        ByteBuffer frame = queue.pollFirst();
        if (frame == null) {
            deferred.remove(session.getID(), queue);
        }
        return frame;
    }

    /** Queue one post-reply frame for a cache-attached session; silent otherwise. */
    private void defer(ServerSession session, Message message) {
        if (session.isAttached(CellCacheConnectionType.CLIENT_TYPE)) {
            deferred.computeIfAbsent(session.getID(), k -> new ArrayDeque<>())
                    .addLast(pack(message, session, CellCacheConnectionType.CLIENT_TYPE));
        }
    }

    private List<ByteBuffer> onCacheMessage(ServerSession session, Message message) {
        List<ByteBuffer> out = new ArrayList<>();
        if (!(message instanceof CellHierarchyMessage)) {
            return out;
        }
        CellHierarchyMessage msg = (CellHierarchyMessage) message;
        if (msg.getActionType() != ActionType.SET_VIEW) {
            return out;
        }
        CellID viewCellID = world.newCellID();
        viewCells.put(session.getID(), viewCellID);
        // the view is a real cell in the authoritative world -- an avatar shell
        // every other session eventually learns to load too (presence)
        WorldCell viewCell = new WorldCell(viewCellID, world.getEnvironmentCellID(),
                viewCellClass, "view-" + session.getID(), null,
                new CellTransform(), new ViewCellClientState(session.getUserID()));
        world.putCell(viewCell);
        out.add(pack(new ViewCreateResponseMessage(msg.getMessageID(), viewCellID),
                session, CellCacheConnectionType.CLIENT_TYPE));
        // the synchronous fan-out carries the NON-view world only: environment
        // first, then content, so every parent precedes its children, and no
        // foreign avatar cell can hijack this session's LocalAvatar before its
        // own view is configured (see header)
        for (WorldCell cell : world.hierarchy()) {
            if (!viewCellClass.equals(cell.getClassName())) {
                out.add(pack(cell.toLoadMessage(), session,
                        CellCacheConnectionType.CLIENT_TYPE));
            }
        }
        // the async push (deferred, delivered after the reply list above): own
        // view first -- it configures the avatar and lifts the tree -- then the
        // foreign view cells the sync pass skipped; by the time those land the
        // session's viewCellID is set, so a foreign cell's ACTIVE rung no-ops
        defer(session, viewCell.toLoadMessage());
        for (WorldCell cell : world.hierarchy()) {
            if (viewCellClass.equals(cell.getClassName())
                    && !cell.getCellID().equals(viewCellID)) {
                defer(session, cell.toLoadMessage());
            }
        }
        return out;
    }

    private List<ByteBuffer> onChannelMessage(ServerSession session, Message message) {
        List<ByteBuffer> out = new ArrayList<>();
        if (message instanceof TextChatMessage) {
            return onChatMessage(session, (TextChatMessage) message, out);
        }
        if (!(message instanceof MovableMessage)) {
            return out;
        }
        MovableMessage move = (MovableMessage) message;
        CellTransform transform = move.getCellTransform();
        CellID cellID = move.getCellID();
        if (!world.applyMove(cellID, transform)) {
            out.add(pack(new ErrorMessage(move.getMessageID(),
                    "unknown cell " + cellID), session,
                    CellChannelConnectionType.CLIENT_TYPE));
            return out;
        }
        // answer the mover: routed back through its waiting response listener by id
        out.add(pack(MovableMessageResponse.newMoveModifiedMessage(move.getMessageID(),
                transform.getTranslation(null), transform.getRotation(null),
                transform.getScaling()), session, CellChannelConnectionType.CLIENT_TYPE));
        // Roadmap P5.4: an avatar move is also the session's new watch point.
        // The moved cell is the mover's OWN view cell exactly when the id the
        // client dragged matches the one its SET_VIEW minted -- a furniture move
        // is not the person walking, so it must not re-home the mover's eye.
        if (cellID.equals(viewCells.get(session.getID()))) {
            Vector3f at = transform.getTranslation(null);
            if (at != null) {
                interest.watch(session.getID(), at.x, at.y, at.z, WATCH_RADIUS);
            }
        }
        // broadcast the accepted transform to the sessions that can see it; the
        // senderID is the mover's session id, which the mover's own component
        // compares to itself and drops (echo suppression)
        broadcastMove(session.getID(), cellID, transform);
        return out;
    }

    private List<ByteBuffer> onEditMessage(ServerSession session, Message message) {
        List<ByteBuffer> out = new ArrayList<>();
        if (!(message instanceof CellReparentMessage)) {
            return out;
        }
        CellReparentMessage reparent = (CellReparentMessage) message;
        WorldCell existing = world.find(reparent.getCellID());
        if (existing == null) {
            return out;
        }
        world.putCell(new WorldCell(existing.getCellID(), reparent.getParentCellID(),
                existing.getClassName(), existing.getName(), existing.getLocalBounds(),
                reparent.getChildCellTransform(), existing.getClientState()));
        broadcastConfigure(reparent.getCellID());
        return out;
    }

    /**
     * Relay one chat line. Two things are the server's to decide, not the
     * client's: <em>who</em> the line is from, and <em>where</em> it may be sent.
     *
     * <p>The name is rewritten from the session's authenticated identity, so a
     * client that claims to be someone else is simply not believed; the session
     * id goes into {@code senderID} so every receiver, the author included, can
     * tell its own lines from everyone else's. The address must be the world's
     * environment cell: that is the cell every session has loaded and therefore
     * the only one on which a broadcast reaches anybody, and a client that
     * addressed someone else's cell is refused rather than silently ignored.</p>
     *
     * <p>The rebuild is also the validation. Java serialisation does not run a
     * constructor, so a hand-made frame can arrive with a null or enormous body
     * that no {@link TextChatMessage} factory would have produced; constructing
     * the relayed copy runs the message's own bounds again, and a refusal is an
     * ErrorMessage to the sender, never a stack trace on the transport thread.</p>
     */
    private List<ByteBuffer> onChatMessage(ServerSession session,
            TextChatMessage chat, List<ByteBuffer> out) {
        CellID environment = world.getEnvironmentCellID();
        if (!environment.equals(chat.getCellID())) {
            out.add(pack(new ErrorMessage(chat.getMessageID(),
                    "chat must be addressed to the world cell " + environment),
                    session, CellChannelConnectionType.CLIENT_TYPE));
            return out;
        }
        TextChatMessage relayed;
        try {
            relayed = new TextChatMessage(environment, displayName(session),
                    chat.getText(), chat.getSentAt());
        } catch (IllegalArgumentException malformed) {
            out.add(pack(new ErrorMessage(chat.getMessageID(),
                    "rejected: " + malformed.getMessage()), session,
                    CellChannelConnectionType.CLIENT_TYPE));
            return out;
        }
        relayed.setSenderID(session.getID());
        broadcastChat(relayed);
        return out;
    }

    /** What a roster and a transcript call this person: the name they chose. */
    private static String displayName(ServerSession session) {
        WonderlandIdentity user = session.getUserID();
        if (user == null) {
            return "guest";
        }
        String full = user.getFullName();
        if (full != null && !full.trim().isEmpty()) {
            return full;
        }
        String name = user.getUsername();
        return (name == null || name.trim().isEmpty()) ? "guest" : name;
    }

    /**
     * Push one chat line to every channel-connected session, the author's
     * included. Every client suppresses its own by senderID, exactly as the move
     * broadcast above does -- one echo rule for both, so a client that
     * understands one understands both.
     */
    private void broadcastChat(TextChatMessage chat) {
        for (ServerSession target : registry.all()) {
            if (target.isAttached(CellChannelConnectionType.CLIENT_TYPE)) {
                target.deliver(CellChannelConnectionType.CLIENT_TYPE,
                        pack(chat, target, CellChannelConnectionType.CLIENT_TYPE));
            }
        }
    }

    private void broadcastMove(BigInteger senderID, CellID cellID, CellTransform transform) {
        MovableMessage moved = MovableMessage.newMovedMessage(cellID,
                transform.getTranslation(null), transform.getRotation(null),
                transform.getScaling());
        moved.setSenderID(senderID);
        // Roadmap P5.4: fan to the audience, not the whole server. The event
        // happens where the cell now is; a session hears it when the union of
        // its watch radius and this cell's reach covers that place. The mover
        // always receives its own echo (client-side suppression depends on it),
        // and an UNTRACKED session always receives everything -- so before any
        // avatar has moved, this is byte-identical to the pre-P5.4 broadcast.
        Vector3f at = transform.getTranslation(null);
        Set<BigInteger> audience = at == null ? Set.of()
                : new HashSet<>(interest.audienceFor(at.x, at.y, at.z,
                        eventRadiusOf(cellID)));
        for (ServerSession target : registry.all()) {
            if (!target.isAttached(CellChannelConnectionType.CLIENT_TYPE)) {
                continue;
            }
            BigInteger id = target.getID();
            boolean hears = id.equals(senderID)
                    || !interest.isTracked(id)
                    || audience.contains(id);
            if (hears) {
                target.deliver(CellChannelConnectionType.CLIENT_TYPE,
                        pack(moved, target, CellChannelConnectionType.CLIENT_TYPE));
            }
        }
    }

    /**
     * The reach a moved cell's event is credited with. A cell whose authored
     * bounds carry a radius broadcasts at least that far; one without answers
     * the documented default, so a bare transform never shrinks interest to a
     * point.
     */
    private float eventRadiusOf(CellID cellID) {
        WorldCell cell = world.find(cellID);
        if (cell != null && cell.getLocalBounds() instanceof BoundingSphere sphere) {
            float radius = sphere.getRadius();
            if (radius > 0f && !Float.isNaN(radius)) {
                return radius;
            }
        }
        // a cell with no authored spherical bounds (a box, or none at all) still
        // happens somewhere, so it broadcasts at the documented default reach
        // rather than collapsing interest to a dimensionless point.
        return DEFAULT_EVENT_RADIUS;
    }

    private void broadcastConfigure(CellID cellID) {
        WorldCell cell = world.find(cellID);
        if (cell == null) {
            return;
        }
        CellHierarchyMessage configure = cell.toConfigureMessage();
        for (ServerSession target : registry.all()) {
            if (target.isAttached(CellCacheConnectionType.CLIENT_TYPE)) {
                target.deliver(CellCacheConnectionType.CLIENT_TYPE,
                        pack(configure, target, CellCacheConnectionType.CLIENT_TYPE));
            }
        }
    }

    /** Tell every cache-connected session the departed avatar cell is gone. */
    private void broadcastUnload(CellID cellID) {
        CellHierarchyMessage unload = new CellHierarchyMessage(ActionType.UNLOAD_CELL,
                null, null, cellID, null, null, (CellClientState) null, null);
        for (ServerSession target : registry.all()) {
            if (target.isAttached(CellCacheConnectionType.CLIENT_TYPE)) {
                target.deliver(CellCacheConnectionType.CLIENT_TYPE,
                        pack(unload, target, CellCacheConnectionType.CLIENT_TYPE));
            }
        }
    }

    private ByteBuffer pack(Message message, ServerSession session, ConnectionType type) {
        try {
            return MessagePacker.pack(message, session.clientID(type));
        } catch (PackerException ex) {
            throw new IllegalStateException("failed to pack " + message.getClass(), ex);
        }
    }

    private ReceivedMessage unpack(ByteBuffer buffer) {
        try {
            ReceivedMessage received = MessagePacker.unpack(buffer);
            if (received == null) {
                throw new PackerException();
            }
            return received;
        } catch (PackerException ex) {
            throw new IllegalStateException("failed to unpack inbound frame", ex);
        }
    }

    /** The view cell id minted for a session, or null before its SET_VIEW lands. */
    public CellID viewCellOf(ServerSession session) {
        return viewCells.get(session.getID());
    }
}
