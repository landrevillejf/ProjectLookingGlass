/*
 * M2c: the Cell base class, headless. Covers the tree (add/remove child,
 * multiple-parent), the component map (add/remove/duplicate, the ordinal status
 * walk in both directions), the DISK-down teardown (components, renderers,
 * receiver unregistration), the ACTIVE-up wiring through the MessageChannel
 * seam (with and without a channel), @UsesCellComponent injection on both a
 * Cell and a component, reflection component injection from a CellClientState
 * (including the failure branches), transform propagation with world-bounds
 * aggregation and the no-bounds-hint guard, the renderer hook plumbing, and
 * every listener fan-out plus its removal.
 */
package org.jdesktop.wonderland.client.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.AnnotatedCell;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.CarrierComponent;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.PlainComponent;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.ProbeComponent;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.RecordingChannel;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.RecordingComponent;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.RecordingRenderer;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.RecordingResponse;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.RendererCell;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.StateRecordingComponent;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.UnconstructibleComponent;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.MultipleParentException;
import org.jdesktop.wonderland.common.cell.messages.CellClientStateMessage;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CellTest {

    private Cell cell;
    private CellID cellID;

    @BeforeEach
    void setUp() {
        cellID = CellTestFixtures.id(1);
        cell = new Cell(cellID);
    }

    @AfterEach
    void tearDown() {
        Cell.setCurrentRendererType(null);
    }

    @Test
    void identityAndName() {
        assertThat(cell.getCellID()).isSameAs(cellID);
        assertThat(cell.getName()).isEqualTo(cellID.toString());
        cell.setName("parlor");
        assertThat(cell.getName()).isEqualTo("parlor");
        assertThat(cell.getStatus()).isSameAs(CellStatus.DISK);
    }

    @Test
    void treeAddRemoveAndListeners() throws Exception {
        Cell childA = new Cell(CellTestFixtures.id(2));
        Cell childB = new Cell(CellTestFixtures.id(3));
        final java.util.List<String> events = new java.util.ArrayList<>();
        cell.addChildrenChangeListener(new CellChildrenChangeListener() {
            @Override
            public void childAdded(Cell parent, Cell added) {
                events.add("added:" + added.getCellID());
            }

            @Override
            public void childRemoved(Cell parent, Cell removed) {
                events.add("removed:" + removed.getCellID());
            }
        });
        final java.util.List<Cell> parentEvents = new java.util.ArrayList<>();
        childA.addParentChangeListener(new CellParentChangeListener() {
            @Override
            public void parentChanged(Cell changed, Cell newParent) {
                parentEvents.add(newParent);
            }
        });

        cell.addChild(childA);
        cell.addChild(childB);
        assertThat(cell.getNumChildren()).isEqualTo(2);
        assertThat(cell.getChildren()).containsExactly(childA, childB);
        assertThat(childA.getParent()).isSameAs(cell);

        // a child cannot hang from two parents -- the 2006 contract, kept
        assertThatThrownBy(() -> childB.addChild(childA)).isInstanceOf(MultipleParentException.class);

        cell.removeChild(childA);
        assertThat(childA.getParent()).isNull();
        assertThat(cell.getNumChildren()).isEqualTo(1);
        // removing a cell that is not a child is a silent no-op
        cell.removeChild(new Cell(CellTestFixtures.id(99)));

        events.clear();
        cell.removeChild(childB);
        assertThat(events).containsExactly("removed:" + childB.getCellID());
        // add listener twice -> CopyOnWriteArraySet dedups; remove stops events
        events.clear();
        CellChildrenChangeListener dupe = new CellChildrenChangeListener() {
            @Override
            public void childAdded(Cell parent, Cell added) {
                events.add("dupe");
            }

            @Override
            public void childRemoved(Cell parent, Cell removed) {
                events.add("dupe");
            }
        };
        cell.addChildrenChangeListener(dupe);
        cell.addChildrenChangeListener(dupe);   // set dedups -- one registration
        cell.addChild(new Cell(CellTestFixtures.id(4)));
        assertThat(events).containsExactlyInAnyOrder("dupe", "added:4");
        cell.removeChildChangeListener(dupe);
        events.clear();
        cell.addChild(new Cell(CellTestFixtures.id(5)));
        assertThat(events).containsExactly("added:5");   // only the first listener left
        assertThat(parentEvents).containsExactly(cell, null);
    }

    @Test
    void componentMapAndStatusWalk() {
        RecordingComponent component = new RecordingComponent(cell);
        final java.util.List<String> events = new java.util.ArrayList<>();
        cell.addComponentChangeListener(
                (changed, type, comp) -> events.add(type + ":" + comp.getClass().getSimpleName()));

        cell.addComponent(component);
        assertThat(cell.getComponent(PlainComponent.class)).isNull();
        assertThat(cell.getComponent(RecordingComponent.class)).isSameAs(component);
        assertThat(cell.getComponents()).containsExactly(component);
        // DISK cell: adding walked nothing (same ordinal)
        assertThat(component.calls).isEmpty();

        // DISK -> ACTIVE walks INACTIVE then ACTIVE, one ordinal at a time
        cell.setStatus(CellStatus.INACTIVE, true);
        cell.setStatus(CellStatus.ACTIVE, true);
        assertThat(component.calls).containsExactly(
                "INACTIVE/true", "ACTIVE/true");

        // ACTIVE -> DISK walks back down; the DISK branch clears the component map
        // DIRECTLY (the 2006 behaviour), so no REMOVED notification is fired there.
        RecordingChannel channel = new RecordingChannel();
        cell.setMessageChannel(channel);
        cell.setStatus(CellStatus.INACTIVE, false);
        cell.setStatus(CellStatus.DISK, false);
        assertThat(component.calls).containsExactly(
                "INACTIVE/true", "ACTIVE/true", "INACTIVE/false", "DISK/false");
        assertThat(cell.getComponents()).isEmpty();
        assertThat(events).containsExactly("ADDED:RecordingComponent");

        // an explicit removeComponent is the path that fires REMOVED
        PlainComponent removable = new PlainComponent(cell);
        cell.addComponent(removable);
        cell.removeComponent(PlainComponent.class);
        assertThat(events).containsExactly(
                "ADDED:RecordingComponent", "ADDED:PlainComponent", "REMOVED:PlainComponent");
        // removing a key that was never added is a silent no-op
        cell.removeComponent(UnconstructibleComponent.class);

        // duplicate lookup key is rejected -- the 2006 contract
        cell.addComponent(new PlainComponent(cell));
        assertThatThrownBy(() -> cell.addComponent(new PlainComponent(cell)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void componentAddedToLiveCellWalksUpToItsStatus() {
        cell.setStatus(CellStatus.INACTIVE, true);
        RecordingComponent component = new RecordingComponent(cell);
        cell.addComponent(component);
        assertThat(component.calls).containsExactly("INACTIVE/true");
        // the @UsesCellComponent resolution for components runs above DISK:
        CarrierComponent carrier = new CarrierComponent(cell);
        cell.addComponent(carrier);
        assertThat(carrier.probe).isNotNull();
        assertThat((ProbeComponent) cell.getComponent(CellTestFixtures.Probe.class))
                .isSameAs(carrier.probe);
    }

    @Test
    void activeUpRegistersReceiversOnlyWithAChannel() throws Exception {
        // no channel yet: receivers are still created, registration skipped
        cell.setStatus(CellStatus.INACTIVE, true);
        cell.setStatus(CellStatus.ACTIVE, true);
        assertThat(cell.getStatus()).isSameAs(CellStatus.ACTIVE);

        RecordingChannel channel = new RecordingChannel();
        cell.setMessageChannel(channel);
        Cell other = new Cell(CellTestFixtures.id(2));
        other.setMessageChannel(channel);
        other.setStatus(CellStatus.INACTIVE, true);
        other.setStatus(CellStatus.ACTIVE, true);
        assertThat(channel.registered).containsOnlyKeys(
                CellClientStateMessage.class,
                org.jdesktop.wonderland.common.cell.messages.CellClientComponentMessage.class);
        assertThat(channel.registered.get(CellClientStateMessage.class))
                .isInstanceOf(CellClientStateMessageReceiver.class);

        // DISK-down unregisters and nulls the receivers
        channel.removed.clear();
        other.setStatus(CellStatus.INACTIVE, false);
        other.setStatus(CellStatus.DISK, false);
        assertThat(channel.removed).containsExactly(
                CellClientStateMessage.class,
                org.jdesktop.wonderland.common.cell.messages.CellClientComponentMessage.class);
        assertThat(channel.registered).isEmpty();
    }

    @Test
    void annotationInjectionOnCellSubclass() {
        AnnotatedCell annotated = new AnnotatedCell(CellTestFixtures.id(10));
        annotated.setStatus(CellStatus.INACTIVE, true);
        assertThat(annotated.probe).isNotNull();
        assertThat((ProbeComponent) annotated.getComponent(CellTestFixtures.Probe.class))
                .isSameAs(annotated.probe);
        // second activation must not re-create the component (receiver == guard)
        ProbeComponent first = annotated.probe;
        annotated.setStatus(CellStatus.ACTIVE, true);
        assertThat(annotated.probe).isSameAs(first);
    }

    @Test
    void clientStateInjectionOverTheClassLoaderSeam() {
        CellClientState state = new CellClientState();
        state.setName("injected");
        state.addClientComponentClasses(new String[] {ProbeComponent.class.getName()});
        cell.setClientState(state);
        assertThat(cell.getName()).isEqualTo("injected");
        assertThat(cell.getComponent(CellTestFixtures.Probe.class)).isNotNull();
        // re-injecting the same key updates the existing component instead of duplicating
        StateRecordingComponent existing =
                (StateRecordingComponent) cell.getComponent(CellTestFixtures.Probe.class);
        // with a client state attached it is setClientState'd
        CellClientState withState = new CellClientState();
        withState.addClientComponentClasses(ProbeComponent.class.getName(),
                new org.jdesktop.wonderland.common.cell.state.CellComponentClientState());
        cell.setClientState(withState);
        assertThat(existing.lastState).isNotNull();

        // unknown class -> SEVERE logged, nothing thrown
        CellClientState missing = new CellClientState();
        missing.addClientComponentClasses(new String[] {"com.example.NoSuchComponent"});
        cell.setClientState(missing);
        // wrong constructor -> reflection failure branch, SEVERE logged, nothing thrown
        CellClientState unconstructible = new CellClientState();
        unconstructible.addClientComponentClasses(
                new String[] {UnconstructibleComponent.class.getName()});
        cell.setClientState(unconstructible);
        assertThat(cell.getComponents()).hasSize(1);
    }

    @Test
    void transformPropagationAndBoundsAggregation() throws Exception {
        Cell parent = new Cell(CellTestFixtures.id(20));
        parent.setLocalBounds(CellTestFixtures.box(0f, 0f, 0f, 1f));

        Cell child = new Cell(CellTestFixtures.id(21));
        child.setLocalBounds(CellTestFixtures.box(0f, 0f, 0f, 0.5f));
        child.setLocalTransform(CellTestFixtures.translation(2f, 0f, 0f),
                TransformChangeListener.ChangeSource.LOCAL);
        parent.addChild(child);

        parent.setLocalTransform(CellTestFixtures.translation(10f, 0f, 0f),
                TransformChangeListener.ChangeSource.REMOTE);

        // parent world = local (no parent): centre (10,0,0), extent 1
        assertThat(parent.getWorldTransform()).isNotNull();
        assertThat(parent.getWorldBounds().getCenter().x).isCloseTo(10f, offset(1e-4f));
        // child world = parentWorld * childLocal -> centre (12,0,0)
        assertThat(child.getWorldBounds().getCenter().x).isCloseTo(12f, offset(1e-4f));
        // child WITHOUT bounds hint: tree update must not throw (the 2006 NPE fix),
        // and a bounds-less cell keeps a null world bound (it has no hint to aggregate)
        Cell childless = new Cell(CellTestFixtures.id(22));
        parent.addChild(childless);
        parent.setLocalTransform(CellTestFixtures.translation(20f, 0f, 0f),
                TransformChangeListener.ChangeSource.SERVER_ADJUST);
        assertThat(childless.getWorldBounds()).isNull();

        // a bounds-hinting child with a bounds-hinting grandchild exercises the
        // mergeLocal aggregation (world bounds grow to enclose both)
        Cell childWithKid = new Cell(CellTestFixtures.id(24));
        childWithKid.setLocalBounds(CellTestFixtures.box(0f, 0f, 0f, 1f));
        Cell grandChild = new Cell(CellTestFixtures.id(25));
        grandChild.setLocalBounds(CellTestFixtures.box(100f, 0f, 0f, 1f));
        childWithKid.addChild(grandChild);
        parent.addChild(childWithKid);
        parent.setLocalTransform(CellTestFixtures.translation(30f, 0f, 0f),
                TransformChangeListener.ChangeSource.LOCAL);
        assertThat(childWithKid.getWorldBounds()).isNotNull();
        // child box (extent 1 at x=30) merged with grandchild box (x=130) encloses both
        assertThat(childWithKid.getWorldBounds().contains(new com.jme.math.Vector3f(130f, 0f, 0f)))
                .isTrue();

        // null local transform: inherit the nearest defined ancestor world transform
        Cell orphan = new Cell(CellTestFixtures.id(23));
        parent.addChild(orphan);
        orphan.setLocalTransform(null, TransformChangeListener.ChangeSource.LOCAL);
        // ... and the local-to-world inverse recomputes after a world change
        assertThat(parent.getLocalToWorldTransform()).isNotNull();
    }

    @Test
    void transformListenerAndNoOpEquivalence() {
        final java.util.List<TransformChangeListener.ChangeSource> fired = new java.util.ArrayList<>();
        cell.addTransformChangeListener(
                (changed, source) -> fired.add(source));
        // identity initial world: setting the same value twice notifies once
        cell.setLocalTransform(new CellTransform(), TransformChangeListener.ChangeSource.LOCAL);
        cell.setLocalTransform(new CellTransform(), TransformChangeListener.ChangeSource.LOCAL);
        assertThat(fired).hasSize(1);
        cell.removeTransformChangeListener((changed, source) -> fired.add(source));
    }

    @Test
    void rendererHookPlumbing() {
        RendererCell rendererCell = new RendererCell(CellTestFixtures.id(30));
        Cell.setCurrentRendererType(Cell.RendererType.RENDERER_JME);

        rendererCell.setStatus(CellStatus.INACTIVE, true);
        rendererCell.setStatus(CellStatus.ACTIVE, true);
        RecordingRenderer renderer = rendererCell.renderer;
        assertThat(rendererCell.getCellRenderer(Cell.RendererType.RENDERER_JME)).isSameAs(renderer);
        // the freshly created renderer is walked up to the cell's ACTIVE status
        assertThat(renderer.statusCalls).containsExactly("INACTIVE/true", "ACTIVE/true");

        // a transform change reaches the renderer
        rendererCell.setLocalTransform(CellTestFixtures.translation(1f, 2f, 3f),
                TransformChangeListener.ChangeSource.LOCAL);
        assertThat(renderer.transformCalls).hasSize(1);

        // DISK-down clears the renderer map
        rendererCell.setStatus(CellStatus.INACTIVE, false);
        rendererCell.setStatus(CellStatus.DISK, false);
        assertThat(rendererCell.getCellRenderer(Cell.RendererType.RENDERER_JME)).isNull();

        // base Cell creates none: the hook returns null and stays silent
        Cell plain = new Cell(CellTestFixtures.id(31));
        plain.setStatus(CellStatus.INACTIVE, true);
        plain.setStatus(CellStatus.ACTIVE, true);
        assertThat(plain.getCellRenderer(Cell.RendererType.RENDERER_JME)).isNull();
    }

    @Test
    void boundsAccessorsAndCachedInverseTransform() {
        // getLocalBounds: null with no hint, a clone once one is set
        assertThat(cell.getLocalBounds()).isNull();
        cell.setLocalBounds(CellTestFixtures.box(0f, 0f, 0f, 1f));
        assertThat(cell.getLocalBounds()).isNotNull();

        // getLocalToWorldTransform: the first call recomputes the cached inverse
        // (local2VW == null), the second returns the memoised clone (!= null)
        cell.setLocalTransform(CellTestFixtures.translation(5f, 0f, 0f),
                TransformChangeListener.ChangeSource.LOCAL);
        assertThat(cell.getLocalToWorldTransform()).isNotNull();
        assertThat(cell.getLocalToWorldTransform()).isNotNull();
    }

    @Test
    void clientStateInjectionCoversThePresentAndAbsentStateBranches() {
        // new component WITH a client state (the component==null / state!=null path)
        Cell fresh = new Cell(CellTestFixtures.id(50));
        CellClientState newState = new CellClientState();
        newState.addClientComponentClasses(ProbeComponent.class.getName(),
                new org.jdesktop.wonderland.common.cell.state.CellComponentClientState());
        fresh.setClientState(newState);
        StateRecordingComponent created =
                (StateRecordingComponent) fresh.getComponent(CellTestFixtures.Probe.class);
        assertThat(created.lastState).isNotNull();

        // re-inject the same key with NO state (the else / state==null path)
        CellClientState bareState = new CellClientState();
        bareState.addClientComponentClasses(new String[] {ProbeComponent.class.getName()});
        fresh.setClientState(bareState);
        assertThat(fresh.getComponents()).hasSize(1);
    }

    @Test
    void sendPathsWithAndWithoutChannel() throws Exception {
        CellMessage message = new CellClientStateMessage(cellID, new CellClientState());

        // no channel: the original severe-log guards, nothing thrown
        cell.sendCellMessage(message);
        assertThat(cell.sendCellMessageAndWait(message)).isNull();

        RecordingChannel channel = new RecordingChannel();
        channel.response = new CellTestFixtures.RecordingResponse(new MessageID());
        cell.setMessageChannel(channel);
        cell.sendCellMessage(message);
        assertThat(channel.sent).containsExactly(message);
        assertThat(cell.sendCellMessageAndWait(message)).isSameAs(channel.response);

        channel.sendAndWaitException = new InterruptedException("test");
        assertThat(cell.sendCellMessageAndWait(message)).isNull();
    }

    @Test
    void statusListenersAndFire() {
        CellManager manager = CellManager.getCellManager();
        final java.util.List<String> fired = new java.util.ArrayList<>();
        CellStatusChangeListener cellListener =
                (changed, status) -> fired.add("cell:" + status);
        CellStatusChangeListener managerListener =
                (changed, status) -> fired.add("mgr:" + status);
        cell.addStatusChangeListener(cellListener);
        manager.addCellStatusChangeListener(managerListener);

        cell.setStatus(CellStatus.INACTIVE, true);
        cell.fireCellStatusChanged(CellStatus.INACTIVE);
        assertThat(fired).containsExactly("cell:INACTIVE", "mgr:INACTIVE");

        cell.removeStatusChangeListener(cellListener);
        manager.removeCellStatusChangeListener(managerListener);
        cell.fireCellStatusChanged(CellStatus.ACTIVE);
        assertThat(fired).containsExactly("cell:INACTIVE", "mgr:INACTIVE");

        // the reset hook rebuilds the singleton lazily, empty
        CellManager.resetForTests();
        assertThat(CellManager.getCellManager()).isNotSameAs(manager);
    }

    @Test
    void stateClientMessageAppliesThroughRegisteredReceiver() {
        RecordingChannel channel = new RecordingChannel();
        cell.setMessageChannel(channel);
        cell.setStatus(CellStatus.INACTIVE, true);
        cell.setStatus(CellStatus.ACTIVE, true);

        CellClientState state = new CellClientState();
        state.setName("from-message");
        channel.registered.get(CellClientStateMessage.class)
                .messageReceived(new CellClientStateMessage(cellID, state));
        assertThat(cell.getName()).isEqualTo("from-message");
    }
}
