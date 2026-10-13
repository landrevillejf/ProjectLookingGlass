/*
 * M2c test fixtures: the in-test MessageChannel (the seam made real without a
 * connection), status-recording components, a lookup-annotated interface pair
 * pinning the CellComponentUtils contract, and cells that override the renderer
 * hook. Package-private access to Cell.setStatus / setLocalTransform is the
 * point of the same-package placement -- the cache/comms layers that normally
 * drive those arrive in M2d.
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.bounding.BoundingBox;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jdesktop.wonderland.client.cell.annotation.UsesCellComponent;
import org.jdesktop.wonderland.common.cell.ComponentLookupClass;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

class CellTestFixtures {

    /** Records registrations/removals/sends; hands back a canned response or throw. */
    static class RecordingChannel implements MessageChannel {
        final Map<Class<? extends CellMessage>, MessageReceiver> registered =
                new LinkedHashMap<>();
        final List<Class<? extends CellMessage>> removed = new ArrayList<>();
        final List<CellMessage> sent = new ArrayList<>();
        ResponseMessage response;
        InterruptedException sendAndWaitException;

        @Override
        public void addMessageReceiver(Class<? extends CellMessage> messageClass,
                MessageReceiver receiver) {
            if (registered.containsKey(messageClass) && registered.get(messageClass) != receiver) {
                throw new IllegalStateException("Duplicate Message class added " + messageClass);
            }
            registered.put(messageClass, receiver);
        }

        @Override
        public void removeMessageReceiver(Class<? extends CellMessage> messageClass) {
            registered.remove(messageClass);
            removed.add(messageClass);
        }

        @Override
        public void send(CellMessage message) {
            sent.add(message);
        }

        @Override
        public ResponseMessage sendAndWait(CellMessage message) throws InterruptedException {
            sent.add(message);
            if (sendAndWaitException != null) {
                throw sendAndWaitException;
            }
            return response;
        }

        /** M2e: dispatch mirrors the real ChannelComponent's exact-class map. */
        @Override
        public void messageReceived(CellMessage message) {
            received.add(message);
            MessageReceiver receiver = registered.get(message.getClass());
            if (receiver != null) {
                receiver.messageReceived(message);
            }
        }

        final List<CellMessage> received = new ArrayList<>();
    }

    static class RecordingComponent extends CellComponent {
        final List<String> calls = new ArrayList<>();

        RecordingComponent(Cell cell) {
            super(cell);
        }

        @Override
        protected void setStatus(CellStatus status, boolean increasing) {
            calls.add(status + "/" + increasing);
            super.setStatus(status, increasing);
        }
    }

    static class StateRecordingComponent extends RecordingComponent {
        CellComponentClientState lastState;

        StateRecordingComponent(Cell cell) {
            super(cell);
        }

        @Override
        public void setClientState(CellComponentClientState clientState) {
            lastState = clientState;
        }
    }

    /** No (Cell) constructor: pins the reflection-failure branch of injection. */
    static class UnconstructibleComponent extends CellComponent {
        UnconstructibleComponent(String notACell) {
            super(null);
        }
    }

    /** The lookup-class pair: the map key is a plain interface, not the impl --
     *  exactly how the 2006 @ComponentLookupClass convention reads (the component
     *  implements the interface). The (Cell) constructor is public because Cell
     *  injects it via getConstructor(Cell.class). */
    @ComponentLookupClass(Probe.class)
    static class ProbeComponent extends StateRecordingComponent implements Probe {
        public ProbeComponent(Cell cell) {
            super(cell);
        }
    }

    interface Probe {
    }

    static class PlainComponent extends CellComponent {
        PlainComponent(Cell cell) {
            super(cell);
        }
    }

    /** @UsesCellComponent on a Cell subclass field, resolved at INACTIVE. */
    static class AnnotatedCell extends Cell {
        @UsesCellComponent
        ProbeComponent probe;

        AnnotatedCell(CellID cellID) {
            super(cellID);
        }
    }

    /** @UsesCellComponent on a component field, resolved when the cell activates. */
    static class CarrierComponent extends RecordingComponent {
        @UsesCellComponent
        ProbeComponent probe;

        CarrierComponent(Cell cell) {
            super(cell);
        }
    }

    /** Overrides the renderer hook so the ACTIVE status walk is observable. */
    static class RendererCell extends Cell {
        final RecordingRenderer renderer = new RecordingRenderer();

        RendererCell(CellID cellID) {
            super(cellID);
        }

        @Override
        protected CellRenderer createCellRenderer(RendererType rendererType) {
            return renderer;
        }
    }

    static class RecordingRenderer implements CellRenderer {
        final List<String> statusCalls = new ArrayList<>();
        final List<CellTransform> transformCalls = new ArrayList<>();

        @Override
        public void cellTransformUpdate(CellTransform transform) {
            transformCalls.add(transform);
        }

        @Override
        public void setStatus(CellStatus status, boolean increasing) {
            statusCalls.add(status + "/" + increasing);
            this.status = status;
        }

        @Override
        public CellStatus getStatus() {
            return status == null ? CellStatus.DISK : status;
        }

        private CellStatus status;
    }

    /** ResponseMessage is abstract; a bare instance identifies by message ID. */
    static class RecordingResponse extends ResponseMessage {
        RecordingResponse(MessageID messageID) {
            super(messageID);
        }
    }

    // ---- M2d cell-cache fixtures ------------------------------------------------

    /** Records what the cache sends session-level (the reparent seam). */
    static class RecordingSink implements MessageSink {
        final List<Message> sent = new ArrayList<>();

        @Override
        public void send(Message message) {
            sent.add(message);
        }
    }

    /** A cache-loadable cell: the public (CellID, CellCache) ctor instantiateCell
     *  reflects open, and it captures the thread-local active cell seen mid-walk. */
    public static class CacheableCell extends Cell {
        Cell activeDuringStatusWalk;
        final List<String> statusCalls = new ArrayList<>();

        public CacheableCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
        }

        @Override
        protected void setStatus(CellStatus status, boolean increasing) {
            activeDuringStatusWalk = CellCacheBasicImpl.getCurrentActiveCell();
            statusCalls.add(status + "/" + increasing);
            super.setStatus(status, increasing);
        }
    }

    /** A concrete EnvironmentCell so getEnvironmentCell()'s cast has a target. */
    public static class CacheableEnvCell extends EnvironmentCell {
        public CacheableEnvCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
        }
    }

    static Cell PRE_PARENT;

    /** Attaches itself to PRE_PARENT in its ctor, so the cache's own
     *  parent.addChild(cell) then hits the MultipleParentException catch. */
    public static class PreParentedCell extends CacheableCell {
        public PreParentedCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
            if (PRE_PARENT != null) {
                try {
                    PRE_PARENT.addChild(this);
                } catch (org.jdesktop.wonderland.common.cell.MultipleParentException ignored) {
                    // expected -- the point is that this cell already has a parent
                }
            }
        }
    }

    /** getClientComponentClasses() throws: drives loadCell's failure/catch branch. */
    static class ThrowingClientState extends CellClientState {
        @Override
        public String[] getClientComponentClasses() {
            throw new IllegalStateException("boom");
        }
    }

    /** Records every stat add so the noop-by-default SPI is observable in tests. */
    static class RecordingStatsProvider implements CellStatistics.CellStatisticsSPI {
        final Map<String, CellStatistics.CellStat> store = new LinkedHashMap<>();

        @Override
        public void add(Cell cell, CellStatistics.CellStat stat) {
            store.put(stat.getId(), stat);
        }

        @Override
        public CellStatistics.CellStat get(Cell cell, String id) {
            return store.get(id);
        }

        @Override
        public Collection<CellStatistics.CellStat> getAll(Cell cell) {
            return store.values();
        }

        @Override
        public CellStatistics.CellStat remove(Cell cell, String id) {
            return store.remove(id);
        }
    }

    // ---- M2f movable / view-cell fixtures -------------------------------------

    /** A cache-loadable cell that owns a plain MovableComponent (attached in the
     *  ctor, before the channel exists); the ACTIVE rung resolves the channel. */
    public static class MovableCell extends CacheableCell {
        public final MovableComponent movable;

        public MovableCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
            movable = new MovableComponent(this);
            addComponent(movable);
        }
    }

    /** A ViewCell with a MovableAvatarComponent, so the ACTIVE rung captures
     *  movableComp and localMoveRequest has a delegate to forward to. */
    public static class MovableViewCell extends
            org.jdesktop.wonderland.client.cell.view.ViewCell {
        public final MovableAvatarComponent movable;

        public MovableViewCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
            movable = new MovableAvatarComponent(this);
            addComponent(movable);
        }
    }

    /** An AvatarCell carrying a MovableAvatarComponent -- the serverMoveRequest
     *  fold path casts the cell to AvatarCell, so the trigger methods must exist. */
    public static class AvatarMoveCell extends
            org.jdesktop.wonderland.client.cell.view.AvatarCell {
        public final MovableAvatarComponent movable;

        public AvatarMoveCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
            movable = new MovableAvatarComponent(this);
            addComponent(movable);
        }
    }

    /** An AvatarCell whose renderer implements AvatarActionTrigger, so the
     *  trigger* dispatch (behind the RENDERER_JME guard) is observable. */
    public static class TriggerableAvatarCell extends
            org.jdesktop.wonderland.client.cell.view.AvatarCell {
        public final RecordingAvatarRenderer renderer = new RecordingAvatarRenderer();

        public TriggerableAvatarCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
        }

        @Override
        protected CellRenderer createCellRenderer(RendererType rendererType) {
            return renderer;
        }
    }

    /** Records the three avatar trigger callbacks; also a minimal CellRenderer. */
    public static class RecordingAvatarRenderer implements CellRenderer,
            org.jdesktop.wonderland.client.cell.view.AvatarCell.AvatarActionTrigger {
        public final List<String> triggers = new ArrayList<>();
        private CellStatus status = CellStatus.DISK;

        @Override
        public void trigger(int trigger, boolean pressed, String animationName) {
            triggers.add("action:" + trigger + "/" + pressed + "/" + animationName);
        }

        @Override
        public void triggerGoto(com.jme.math.Vector3f worldPosition, com.jme.math.Quaternion look) {
            triggers.add("goto");
        }

        @Override
        public void triggerCollision(float height, boolean collision) {
            triggers.add("collision:" + height + "/" + collision);
        }

        @Override
        public void cellTransformUpdate(CellTransform transform) {
        }

        @Override
        public void setStatus(CellStatus status, boolean increasing) {
            this.status = status;
        }

        @Override
        public CellStatus getStatus() {
            return status;
        }
    }

    static CellClientState namedState(String name) {
        CellClientState state = new CellClientState();
        state.setName(name);
        return state;
    }

    static CellID id(int i) {
        return new CellID((long) i);
    }

    static BoundingBox box(float x, float y, float z, float e) {
        return new BoundingBox(new com.jme.math.Vector3f(x, y, z), e, e, e);
    }

    static CellTransform translation(float x, float y, float z) {
        CellTransform t = new CellTransform();
        t.setTranslation(new com.jme.math.Vector3f(x, y, z));
        return t;
    }

    /** M2f: a detached view cell to feed cache.setViewCell() -- it is not in the
     *  cache so the roots-to-VISIBLE walk never visits it; the caller's loaded
     *  cells are the ones walked. Stands in for the old setHasView(true). */
    static org.jdesktop.wonderland.client.cell.view.ViewCell viewCell(CellCache cache) {
        return new org.jdesktop.wonderland.client.cell.view.ViewCell(id(9999), cache);
    }

    /**
     * Runs a body with the owner's logger pinned to {@code level} and returns
     * the records of that level it published. Doubles as the way to exercise
     * the isLoggable-guarded FINE/FINEST branches verbatim ported from 2006.
     */
    static List<LogRecord> capture(Class<?> owner, Level level, Runnable body) {
        Logger lg = Logger.getLogger(owner.getName());
        List<LogRecord> seen = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == level) {
                    seen.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level old = lg.getLevel();
        lg.setLevel(Level.ALL);
        lg.addHandler(handler);
        try {
            body.run();
        } finally {
            lg.removeHandler(handler);
            lg.setLevel(old);
        }
        return seen;
    }
}
