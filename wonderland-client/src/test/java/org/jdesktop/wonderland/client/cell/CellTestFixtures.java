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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdesktop.wonderland.client.cell.annotation.UsesCellComponent;
import org.jdesktop.wonderland.common.cell.ComponentLookupClass;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;
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
}
