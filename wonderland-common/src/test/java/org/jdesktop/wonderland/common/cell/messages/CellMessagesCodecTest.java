/*
 * M1 codec pin: the cell-level wire messages. These ride the M3 Spring Boot
 * server's WebSocket on every user action (create / delete / reparent /
 * move / edit state / request state), so their Java serialisation shape is
 * the wire contract between the ported client and the rebuilt server.
 *
 * Three things these tests lock beyond the obvious round trip:
 *   1. the descriptor-id cache in WonderlandObjectOutputStream for the nine
 *      core classes (CellID, CellMessage, MessageID, Message, Vector3f,
 *      Quaternion, MovableMessage, MovableMessage$ActionType, java.lang.Enum)
 *      works when a message crosses them -- MovableMessage is the only wire
 *      type that touches every one of them, so it is exercised first;
 *   2. the raw `Set` on CellServerStateUpdateMessage round-trips, since that
 *      is the field whose generics were erased on decompilation;
 *   3. the abstract @ServerState hierarchy (CellServerState + concrete
 *      subclasses like ModelCellServerState) survives deserialisation as
 *      the concrete class -- that is what ScannedClassLoader's ServiceLoader
 *      rebuild assumes at the other end.
 */
package org.jdesktop.wonderland.common.cell.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.jme.bounding.BoundingVolume;
import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.util.HashSet;
import java.util.Set;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.state.AvatarCellServerState;
import org.jdesktop.wonderland.common.cell.state.CellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellClientState;
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.junit.jupiter.api.Test;

class CellMessagesCodecTest {

    private static final float EPS = 1e-5f;

    private static Object rt(org.jdesktop.wonderland.common.messages.Message msg) {
        try {
            return MessagePacker.unpack(MessagePacker.pack(msg, (short) 1)).getMessage();
        } catch (MessagePacker.PackerException ex) {
            throw new AssertionError(ex);
        }
    }

    // ------------------------------------------------------------------------
    // CellID / CellMessage base
    // ------------------------------------------------------------------------

    @Test
    void cellMessageRoundTripsCellIDAndSender() {
        // Any CellMessage subclass carries the sender BigInteger too; MovableMessage
        // is the closest concrete subclass with a public factory.
        CellID id = new CellID(1234L);
        MovableMessage msg = MovableMessage.newMovedMessage(
                id, new Vector3f(1f, 2f, 3f), new Quaternion());
        msg.setSenderID(java.math.BigInteger.valueOf(99L));

        MovableMessage back = (MovableMessage) rt(msg);
        assertThat(back.getCellID()).isEqualTo(id);
        assertThat(back.getSenderID()).isEqualTo(java.math.BigInteger.valueOf(99L));
    }

    // ------------------------------------------------------------------------
    // MovableMessage -- the flagship test because it crosses every core
    // class descriptor in WonderlandObjectOutputStream.coreClass.
    // ------------------------------------------------------------------------

    @Test
    void movableMovedMessageRoundTripsTranslationRotationAndScale() {
        CellID id = new CellID(77L);
        Vector3f tr = new Vector3f(1.5f, -2.25f, 3.0f);
        Quaternion rot = new Quaternion().fromAngleAxis((float) Math.PI / 3f, new Vector3f(0f, 1f, 0f));
        MovableMessage msg = MovableMessage.newMovedMessage(id, tr, rot, 2.5f);

        MovableMessage back = (MovableMessage) rt(msg);
        assertThat(back.getActionType()).isEqualTo(MovableMessage.ActionType.MOVED);
        Vector3f bt = back.getTranslation();
        assertThat(bt.x).isCloseTo(1.5f, within(EPS));
        assertThat(bt.y).isCloseTo(-2.25f, within(EPS));
        assertThat(bt.z).isCloseTo(3.0f, within(EPS));
        assertThat(back.getScale()).isCloseTo(2.5f, within(EPS));
        Quaternion br = back.getRotation();
        for (int i = 0; i < 4; i++) {
            assertThat(component(br, i)).isCloseTo(component(rot, i), within(EPS));
        }
    }

    @Test
    void movableRequestAndMovedCarryDistinctActionTypes() {
        CellID id = new CellID(1L);
        MovableMessage req = MovableMessage.newMoveRequestMessage(id,
                new Vector3f(0f, 0f, 0f), new Quaternion());
        MovableMessage moved = MovableMessage.newMovedMessage(id,
                new Vector3f(0f, 0f, 0f), new Quaternion());
        assertThat(((MovableMessage) rt(req)).getActionType())
                .isEqualTo(MovableMessage.ActionType.MOVE_REQUEST);
        assertThat(((MovableMessage) rt(moved)).getActionType())
                .isEqualTo(MovableMessage.ActionType.MOVED);
    }

    @Test
    void movableMessageGetCellTransformRoundTripsThroughFullTransform() {
        CellTransform t = new CellTransform(
                new Quaternion(0f, 0.7071f, 0f, 0.7071f),
                new Vector3f(10f, 20f, 30f),
                3f);
        MovableMessage msg = MovableMessage.newMovedMessage(new CellID(2L), t);
        MovableMessage back = (MovableMessage) rt(msg);
        CellTransform bt = back.getCellTransform();
        assertThat(bt.getScaling()).isCloseTo(3f, within(EPS));
        Vector3f tr = bt.getTranslation(null);
        assertThat(tr.x).isCloseTo(10f, within(EPS));
        assertThat(tr.y).isCloseTo(20f, within(EPS));
        assertThat(tr.z).isCloseTo(30f, within(EPS));
    }

    @Test
    void movableMessageResponseRoundTripsWithMoveModifiedAction() {
        MessageID replyTo = new MessageID(31415L);
        MovableMessageResponse msg = MovableMessageResponse.newMoveModifiedMessage(
                replyTo, new Vector3f(4f, 5f, 6f), new Quaternion(), 1.25f);
        MovableMessageResponse back = (MovableMessageResponse) rt(msg);
        assertThat(back.getActionType())
                .isEqualTo(MovableMessageResponse.ActionType.MOVE_MODIFIED);
        assertThat(back.getMessageID()).isEqualTo(replyTo);
        assertThat(back.getTranslation()).isEqualTo(new Vector3f(4f, 5f, 6f));
        assertThat(back.getScale()).isCloseTo(1.25f, within(EPS));
    }

    @Test
    void movableAvatarMessageCarriesTriggerPressedAnimationHeightCollision() {
        CellID id = new CellID(501L);
        MovableAvatarMessage msg = MovableAvatarMessage.newMoveRequestMessage(
                id, new Vector3f(1f, 2f, 3f), new Quaternion(),
                /* trigger */ 5, /* pressed */ true,
                /* animationName */ "walk", /* height */ 1.7f,
                /* collision */ false);

        MovableAvatarMessage back = (MovableAvatarMessage) rt(msg);
        assertThat(back.getCellID()).isEqualTo(id);
        assertThat(back.getActionType()).isEqualTo(MovableMessage.ActionType.MOVE_REQUEST);
        assertThat(back.getTrigger()).isEqualTo(5);
        assertThat(back.isPressed()).isTrue();
        assertThat(back.getAnimationName()).isEqualTo("walk");
        assertThat(back.getHeight()).isCloseTo(1.7f, within(EPS));
        assertThat(back.isCollision()).isFalse();
    }

    // ------------------------------------------------------------------------
    // Cell edit family (CREATE / DELETE / DUPLICATE / REPARENT)
    // ------------------------------------------------------------------------

    @Test
    void cellCreateRoundTripsParentIDAndConcreteServerStateClass() {
        CellID parent = new CellID(9L);
        ModelCellServerState setup = new ModelCellServerState();
        setup.setName("cube");
        CellCreateMessage msg = new CellCreateMessage(parent, setup);

        CellCreateMessage back = (CellCreateMessage) rt(msg);
        assertThat(back.getEditType()).isEqualTo(CellEditMessage.EditType.CREATE_CELL);
        assertThat(back.getParentCellID()).isEqualTo(parent);
        // The concrete subclass identity is what the server uses to look up
        // the matching cell MO -- do not let it flatten to CellServerState.
        assertThat(back.getCellSetup()).isInstanceOf(ModelCellServerState.class);
        assertThat(back.getCellSetup().getName()).isEqualTo("cube");
    }

    @Test
    void cellDeleteRoundTripsCellID() {
        CellID id = new CellID(42L);
        CellDeleteMessage msg = new CellDeleteMessage(id);
        CellDeleteMessage back = (CellDeleteMessage) rt(msg);
        assertThat(back.getEditType()).isEqualTo(CellEditMessage.EditType.DELETE_CELL);
        assertThat(back.getCellID()).isEqualTo(id);
    }

    @Test
    void cellDuplicateRoundTripsCellIDAndNewName() {
        CellID id = new CellID(43L);
        CellDuplicateMessage msg = new CellDuplicateMessage(id, "copy-of-42");
        CellDuplicateMessage back = (CellDuplicateMessage) rt(msg);
        assertThat(back.getEditType()).isEqualTo(CellEditMessage.EditType.DUPLICATE_CELL);
        assertThat(back.getCellID()).isEqualTo(id);
        assertThat(back.getCellName()).isEqualTo("copy-of-42");
    }

    @Test
    void cellReparentRoundTripsChildParentAndChildTransform() {
        CellID child = new CellID(100L);
        CellID parent = new CellID(200L);
        CellTransform t = new CellTransform(new Quaternion(), new Vector3f(1f, 2f, 3f), 1f);
        CellReparentMessage msg = new CellReparentMessage(child, parent, t);

        CellReparentMessage back = (CellReparentMessage) rt(msg);
        assertThat(back.getEditType()).isEqualTo(CellEditMessage.EditType.REPARENT_CELL);
        assertThat(back.getCellID()).isEqualTo(child);
        assertThat(back.getParentCellID()).isEqualTo(parent);
        Vector3f tr = back.getChildCellTransform().getTranslation(null);
        assertThat(tr.x).isCloseTo(1f, within(EPS));
        assertThat(tr.y).isCloseTo(2f, within(EPS));
        assertThat(tr.z).isCloseTo(3f, within(EPS));
    }

    @Test
    void cellCreatedResponseRoundTripsNewCellIDAndReplyTo() {
        MessageID replyTo = new MessageID(500L);
        CellID newID = new CellID(600L);
        CellCreatedMessage msg = new CellCreatedMessage(replyTo, newID);
        CellCreatedMessage back = (CellCreatedMessage) rt(msg);
        assertThat(back.getMessageID()).isEqualTo(replyTo);
        assertThat(back.getCellID()).isEqualTo(newID);
    }

    // ------------------------------------------------------------------------
    // CellServerState request / response / set / update
    // ------------------------------------------------------------------------

    @Test
    void cellServerStateRequestRoundTripsCellID() {
        CellID id = new CellID(31L);
        CellServerStateRequestMessage msg = new CellServerStateRequestMessage(id);
        CellServerStateRequestMessage back = (CellServerStateRequestMessage) rt(msg);
        assertThat(back.getCellID()).isEqualTo(id);
    }

    @Test
    void cellServerStateResponseCarriesAvatarConcreteServerState() {
        MessageID replyTo = new MessageID(123L);
        AvatarCellServerState state = new AvatarCellServerState("alice");
        state.setName("alice");
        CellServerStateResponseMessage msg =
                new CellServerStateResponseMessage(replyTo, state);

        CellServerStateResponseMessage back =
                (CellServerStateResponseMessage) rt(msg);
        assertThat(back.getMessageID()).isEqualTo(replyTo);
        assertThat(back.getCellServerState()).isInstanceOf(AvatarCellServerState.class);
        assertThat(back.getCellServerState().getName()).isEqualTo("alice");
        assertThat(((AvatarCellServerState) back.getCellServerState()).getUserName())
                .isEqualTo("alice");
        // M6b: the avatarModelUrl field is optional; the pre-M6b constructor
        // leaves it null and Java serialisation round-trips that null cleanly.
        assertThat(((AvatarCellServerState) back.getCellServerState()).getAvatarModelUrl())
                .isNull();
    }

    @Test
    void avatarCellServerStateCarriesTheM6bAvatarModelUrlWhenSet() {
        // The wire extension is a plain optional String: an AvatarCellServerState
        // with a URL set must survive pack/unpack with the URL intact, because
        // the client's AvatarLoadResolver reads it back to pick the .dae body.
        MessageID replyTo = new MessageID(124L);
        AvatarCellServerState state = new AvatarCellServerState("bob");
        state.setName("bob");
        state.setAvatarModelUrl("wla://wonderland-avatars/humanoid-a.dae");
        CellServerStateResponseMessage msg =
                new CellServerStateResponseMessage(replyTo, state);

        CellServerStateResponseMessage back =
                (CellServerStateResponseMessage) rt(msg);
        assertThat(back.getCellServerState()).isInstanceOf(AvatarCellServerState.class);
        AvatarCellServerState bs = (AvatarCellServerState) back.getCellServerState();
        assertThat(bs.getUserName()).isEqualTo("bob");
        assertThat(bs.getAvatarModelUrl())
                .isEqualTo("wla://wonderland-avatars/humanoid-a.dae");
    }

    @Test
    void cellServerStateSetRoundTripsCellIDAndState() {
        CellID id = new CellID(77L);
        ModelCellServerState state = new ModelCellServerState();
        state.setName("lamp");
        CellServerStateSetMessage msg = new CellServerStateSetMessage(id, state);

        CellServerStateSetMessage back = (CellServerStateSetMessage) rt(msg);
        assertThat(back.getCellID()).isEqualTo(id);
        assertThat(back.getCellServerState()).isInstanceOf(ModelCellServerState.class);
        assertThat(back.getCellServerState().getName()).isEqualTo("lamp");
    }

    @Test
    void cellServerStateUpdateRoundTripsRawComponentStateSet() {
        // The field is `private Set componentStateSet` -- no generics after
        // decompilation. This is the pin that keeps the erased shape usable:
        // the server code that reads it back still iterates CellComponentServerState.
        CellID id = new CellID(1L);
        CellServerState state = new ModelCellServerState();
        Set<ComponentHolder> set = new HashSet<>();
        set.add(new ComponentHolder(new PositionComponentServerState()));
        set.add(new ComponentHolder(new ModelCellComponentServerState()));

        // The message's constructor signature is `Set` (raw); build with an
        // untyped view so the compiler is happy on both sides.
        CellServerStateUpdateMessage msg = new CellServerStateUpdateMessage(
                id, state, (Set) (Set<?>) set);

        CellServerStateUpdateMessage back = (CellServerStateUpdateMessage) rt(msg);
        assertThat(back.getCellServerState()).isInstanceOf(ModelCellServerState.class);
        @SuppressWarnings("unchecked")
        Set<ComponentHolder> bs = (Set<ComponentHolder>) back.getCellComponentServerStateSet();
        assertThat(bs).hasSize(2);
        assertThat(bs).allSatisfy(h -> assertThat(h.state).isNotNull());
    }

    /**
     * Serializable holder so we can put component states in a HashSet --
     * CellComponentServerState does not override hashCode/equals, so two
     * equal-looking instances would otherwise collide. Wrapping with a
     * value-based holder keeps the test deterministic.
     */
    private static final class ComponentHolder implements java.io.Serializable {
        final CellComponentServerState state;
        ComponentHolder(CellComponentServerState state) { this.state = state; }
        @Override public int hashCode() { return state.getClass().hashCode(); }
        @Override public boolean equals(Object o) {
            return o instanceof ComponentHolder
                    && ((ComponentHolder) o).state.getClass() == this.state.getClass();
        }
    }

    // ------------------------------------------------------------------------
    // Component add / remove / request
    // ------------------------------------------------------------------------

    @Test
    void cellServerComponentAddMessageRoundTripsStateAndClassName() {
        CellID id = new CellID(11L);
        ModelCellComponentServerState comp = new ModelCellComponentServerState();
        comp.setDeployedModelURL("http://example/models/lamp.dae");
        CellServerComponentMessage msg =
                CellServerComponentMessage.newAddMessage(id, comp);

        CellServerComponentMessage back = (CellServerComponentMessage) rt(msg);
        assertThat(back.getComponentAction())
                .isEqualTo(CellServerComponentMessage.ComponentAction.ADD);
        assertThat(back.getCellID()).isEqualTo(id);
        assertThat(back.getCellComponentServerClassName())
                .isEqualTo(comp.getServerComponentClassName());
        assertThat(back.getCellComponentServerState())
                .isInstanceOf(ModelCellComponentServerState.class);
        assertThat(((ModelCellComponentServerState) back.getCellComponentServerState())
                .getDeployedModelURL()).isEqualTo("http://example/models/lamp.dae");
    }

    @Test
    void cellServerComponentRemoveMessageCarriesOnlyClassName() {
        CellID id = new CellID(12L);
        CellServerComponentMessage msg = CellServerComponentMessage.newRemoveMessage(
                id, "org.jdesktop.wonderland.server.components.SomeRemoved");
        CellServerComponentMessage back = (CellServerComponentMessage) rt(msg);
        assertThat(back.getComponentAction())
                .isEqualTo(CellServerComponentMessage.ComponentAction.REMOVE);
        assertThat(back.getCellComponentServerState()).isNull();
        assertThat(back.getCellComponentServerClassName())
                .isEqualTo("org.jdesktop.wonderland.server.components.SomeRemoved");
    }

    @Test
    void cellServerComponentResponseCarriesConcreteStateAndReplyTo() {
        MessageID replyTo = new MessageID(999L);
        PositionComponentServerState state = new PositionComponentServerState();
        state.translation = new Vector3f(5f, 6f, 7f);
        CellServerComponentResponseMessage msg =
                new CellServerComponentResponseMessage(replyTo, state);
        CellServerComponentResponseMessage back =
                (CellServerComponentResponseMessage) rt(msg);
        assertThat(back.getMessageID()).isEqualTo(replyTo);
        assertThat(back.getCellComponentServerState())
                .isInstanceOf(PositionComponentServerState.class);
        Vector3f t = ((PositionComponentServerState) back.getCellComponentServerState()).translation;
        assertThat(t).isEqualTo(new Vector3f(5f, 6f, 7f));
    }

    @Test
    void cellClientComponentAddMessageCarriesClientClassNameAndState() {
        CellID id = new CellID(13L);
        CellClientComponentMessage msg = CellClientComponentMessage.newAddMessage(
                id, "org.jdesktop.wonderland.client.components.Model", null);
        CellClientComponentMessage back = (CellClientComponentMessage) rt(msg);
        assertThat(back.getComponentAction())
                .isEqualTo(CellClientComponentMessage.ComponentAction.ADD);
        assertThat(back.getCellComponentClientClassName())
                .isEqualTo("org.jdesktop.wonderland.client.components.Model");
        assertThat(back.getCellComponentClientState()).isNull();
    }

    @Test
    void cellClientComponentRemoveMessageHasNoClientState() {
        CellID id = new CellID(14L);
        CellClientComponentMessage msg = CellClientComponentMessage.newRemoveMessage(
                id, "org.jdesktop.wonderland.client.components.Gone");
        CellClientComponentMessage back = (CellClientComponentMessage) rt(msg);
        assertThat(back.getComponentAction())
                .isEqualTo(CellClientComponentMessage.ComponentAction.REMOVE);
        assertThat(back.getCellComponentClientState()).isNull();
    }

    @Test
    void cellClientStateMessageRoundTripsModelClientState() {
        CellID id = new CellID(15L);
        ModelCellClientState state = new ModelCellClientState();
        state.setName("client-side-model");
        state.setDeployedModelURL("wla://wonderland-outpatient-clinic/box.kmz/box.dep");
        CellClientStateMessage msg = new CellClientStateMessage(id, state);
        CellClientStateMessage back = (CellClientStateMessage) rt(msg);
        assertThat(back.getCellID()).isEqualTo(id);
        assertThat(back.getClientState()).isInstanceOf(ModelCellClientState.class);
        assertThat(back.getClientState().getName()).isEqualTo("client-side-model");
        // Stage 2: the deployed-model URL is now part of the wire payload, so a
        // model cell's wla:// reference survives serialization to the client.
        assertThat(((ModelCellClientState) back.getClientState()).getDeployedModelURL())
                .isEqualTo("wla://wonderland-outpatient-clinic/box.kmz/box.dep");
    }

    @Test
    void cellChannelComponentRequestCarriesCellIDOnly() {
        CellID id = new CellID(16L);
        CellChannelComponentRequestMessage msg = new CellChannelComponentRequestMessage(id);
        CellChannelComponentRequestMessage back =
                (CellChannelComponentRequestMessage) rt(msg);
        assertThat(back.getCellID()).isEqualTo(id);
    }

    // ------------------------------------------------------------------------
    // Hierarchy message (uses jME BoundingVolume + CellClientState together)
    // ------------------------------------------------------------------------

    @Test
    void cellHierarchyUnloadCarriesCellIDAndUnloadAction() {
        CellID id = new CellID(17L);
        CellHierarchyUnloadMessage msg = new CellHierarchyUnloadMessage(id);
        CellHierarchyUnloadMessage back = (CellHierarchyUnloadMessage) rt(msg);
        assertThat(back.getCellID()).isEqualTo(id);
        assertThat(back.getActionType()).isEqualTo(CellHierarchyMessage.ActionType.UNLOAD_CELL);
    }

    @Test
    void cellHierarchyLoadCarriesBoundsTransformAndSetupData() {
        CellID id = new CellID(18L);
        CellID parent = new CellID(19L);
        BoundingVolume bounds = new com.jme.bounding.BoundingSphere(1.0f, new Vector3f());
        CellTransform t = new CellTransform(new Quaternion(), new Vector3f(1f, 0f, 0f), 1f);
        ModelCellClientState setup = new ModelCellClientState();
        setup.setName("hier");
        setup.setDeployedModelURL("wla://wonderland-outpatient-clinic/box.kmz/box.dep");
        CellHierarchyMessage msg = new CellHierarchyMessage(
                CellHierarchyMessage.ActionType.LOAD_CELL,
                "org.jdesktop.wonderland.client.cell.ModelCell",
                bounds, id, parent, t, setup, "lamp-post");

        CellHierarchyMessage back = (CellHierarchyMessage) rt(msg);
        assertThat(back.getActionType()).isEqualTo(CellHierarchyMessage.ActionType.LOAD_CELL);
        assertThat(back.getCellID()).isEqualTo(id);
        assertThat(back.getCellClassName())
                .isEqualTo("org.jdesktop.wonderland.client.cell.ModelCell");
        assertThat(back.getSetupData()).isInstanceOf(ModelCellClientState.class);
        assertThat(back.getSetupData().getName()).isEqualTo("hier");
        // the LOAD_CELL the seeder's WorldCell.toLoadMessage() produces carries the
        // model URL inside its setup data -- this is the whole wire contract.
        assertThat(((ModelCellClientState) back.getSetupData()).getDeployedModelURL())
                .isEqualTo("wla://wonderland-outpatient-clinic/box.kmz/box.dep");
        assertThat(back.getCellName()).isEqualTo("lamp-post");
    }

    @Test
    void viewCreateResponseCarriesViewCellIDAndReplyTo() {
        MessageID replyTo = new MessageID(44L);
        CellID view = new CellID(45L);
        ViewCreateResponseMessage msg = new ViewCreateResponseMessage(replyTo, view);
        ViewCreateResponseMessage back = (ViewCreateResponseMessage) rt(msg);
        assertThat(back.getMessageID()).isEqualTo(replyTo);
        assertThat(back.getViewCellID()).isEqualTo(view);
    }

    // ------------------------------------------------------------------------
    // Chat (M9): the one cell message whose body is a person's sentence
    // ------------------------------------------------------------------------

    @Test
    void textChatMessageRoundTripsSenderTextAndTimestamp() {
        // The transcript renders exactly these three fields plus the sender id,
        // so a wire that loses any of them loses the line's attribution or puts
        // every peer's clock skew into the log.
        CellID env = new CellID(1000L);
        TextChatMessage msg = new TextChatMessage(env, "Alice A",
                "meet me in exam room 2", 1234567890L);
        msg.setSenderID(java.math.BigInteger.valueOf(7L));

        TextChatMessage back = (TextChatMessage) rt(msg);
        assertThat(back.getCellID()).isEqualTo(env);
        assertThat(back.getSenderID()).isEqualTo(java.math.BigInteger.valueOf(7L));
        assertThat(back.getSenderName()).isEqualTo("Alice A");
        assertThat(back.getText()).isEqualTo("meet me in exam room 2");
        assertThat(back.getSentAt()).isEqualTo(1234567890L);
    }

    @Test
    void textChatMessageRefusesWhatCannotBeDisplayed() {
        CellID env = new CellID(1001L);
        // A blank author cannot be attributed and a blank body is a keystroke;
        // both are refused here so no reader downstream has to guess. The
        // bounds are the wire's, not a text field's: a hand-built client is
        // exactly the one that would send a megabyte.
        assertThatThrownBy(() -> new TextChatMessage(env, null, "hi"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChatMessage(env, "   ", "hi"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChatMessage(env, "Alice", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChatMessage(env, "Alice",
                "x".repeat(TextChatMessage.MAX_TEXT_CHARS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChatMessage(env,
                "A".repeat(TextChatMessage.MAX_NAME_CHARS + 1), "hi"))
                .isInstanceOf(IllegalArgumentException.class);
        // the boundary itself is legal -- a line of exactly the maximum is long,
        // not malformed
        assertThat(new TextChatMessage(env, "Alice",
                "x".repeat(TextChatMessage.MAX_TEXT_CHARS)).getText()).hasSize(
                TextChatMessage.MAX_TEXT_CHARS);
    }

    private static float component(Quaternion q, int i) {
        switch (i) {
            case 0: return q.x;
            case 1: return q.y;
            case 2: return q.z;
            default: return q.w;
        }
    }
}
