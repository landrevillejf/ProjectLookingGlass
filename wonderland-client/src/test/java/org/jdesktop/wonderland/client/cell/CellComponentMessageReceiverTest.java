/*
 * M2c: the component ADD/REMOVE receiver, driven directly (the real channel
 * arrives with M2d). Pins the reflection-injection seam, the lookup-class map
 * key, the already-present update branch, the removal path and the failure
 * branches the 2006 code logged rather than threw.
 */
package org.jdesktop.wonderland.client.cell;

import static org.assertj.core.api.Assertions.assertThat;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.PlainComponent;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.ProbeComponent;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.ComponentLookupClass;
import org.jdesktop.wonderland.common.cell.messages.CellClientComponentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CellComponentMessageReceiverTest {

    private Cell cell;
    private CellComponentMessageReceiver receiver;

    @BeforeEach
    void setUp() {
        cell = new Cell(CellTestFixtures.id(1));
        receiver = new CellComponentMessageReceiver(cell);
    }

    @Test
    void addMessageCreatesComponentUnderLookupKey() {
        receiver.messageReceived(CellClientComponentMessage.newAddMessage(
                cell.getCellID(), ProbeComponent.class.getName(),
                new org.jdesktop.wonderland.common.cell.state.CellComponentClientState()));

        ProbeComponent injected = (ProbeComponent) cell.getComponent(CellTestFixtures.Probe.class);
        assertThat(injected).isInstanceOf(ProbeComponent.class);
        assertThat(injected.lastState).isNotNull();
    }

    @Test
    void addMessageWithoutClassNameIsIgnored() {
        CellClientComponentMessage message =
                CellClientComponentMessage.newAddMessage(cell.getCellID(), null, null);
        receiver.messageReceived(message);
        assertThat(cell.getComponents()).isEmpty();
    }

    @Test
    void addMessageOnExistingComponentUpdatesStateOnly() {
        PlainComponent existing = new PlainComponent(cell);
        cell.addComponent(existing);
        receiver.messageReceived(CellClientComponentMessage.newAddMessage(
                cell.getCellID(), PlainComponent.class.getName(),
                new org.jdesktop.wonderland.common.cell.state.CellComponentClientState()));
        assertThat(cell.getComponent(PlainComponent.class)).isSameAs(existing);
        assertThat(cell.getComponents()).hasSize(1);
    }

    @Test
    void addMessageUnknownClassLogsAndContinues() {
        receiver.messageReceived(CellClientComponentMessage.newAddMessage(
                cell.getCellID(), "com.example.NoSuchComponent", null));
        assertThat(cell.getComponents()).isEmpty();
    }

    @Test
    void addMessageNonComponentClassLogsAndContinues() {
        receiver.messageReceived(CellClientComponentMessage.newAddMessage(
                cell.getCellID(), String.class.getName(), null));
        assertThat(cell.getComponents()).isEmpty();
    }

    @Test
    void removeMessageDropsTheLookupKey() {
        receiver.messageReceived(CellClientComponentMessage.newAddMessage(
                cell.getCellID(), ProbeComponent.class.getName(), null));
        assertThat(cell.getComponent(CellTestFixtures.Probe.class)).isNotNull();

        receiver.messageReceived(CellClientComponentMessage.newRemoveMessage(
                cell.getCellID(), ProbeComponent.class.getName()));
        assertThat(cell.getComponent(CellTestFixtures.Probe.class)).isNull();
    }

    @Test
    void removeMessageWithoutClassNameIsIgnored() {
        receiver.messageReceived(CellClientComponentMessage.newRemoveMessage(
                cell.getCellID(), null));
        assertThat(cell.getComponents()).isEmpty();
    }

    @Test
    void removeMessageUnknownClassLogsAndContinues() {
        receiver.messageReceived(CellClientComponentMessage.newRemoveMessage(
                cell.getCellID(), "com.example.NoSuchComponent"));
        assertThat(cell.getComponents()).isEmpty();
    }

    /** The annotation itself is part of the contract: pin its runtime retention. */
    @Test
    void lookupAnnotationIsVisibleAtRuntime() {
        ComponentLookupClass annotation =
                ProbeComponent.class.getAnnotation(ComponentLookupClass.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(CellTestFixtures.Probe.class);
        assertThat(Cell.lookupClassOf(ProbeComponent.class))
                .isEqualTo(CellTestFixtures.Probe.class);
        assertThat(Cell.lookupClassOf(PlainComponent.class)).isEqualTo(PlainComponent.class);
    }

    /** A cell ID only used for message construction -- keeps the fixture honest. */
    @Test
    void messageCarriesTheCellID() {
        CellID id = new CellID(42L);
        CellClientComponentMessage message =
                CellClientComponentMessage.newRemoveMessage(id, "x");
        assertThat(message.getCellID()).isEqualTo(id);
    }
}
