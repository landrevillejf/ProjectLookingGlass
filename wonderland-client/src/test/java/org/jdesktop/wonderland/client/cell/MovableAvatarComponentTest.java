/*
 * M2f: the avatar move component. Drives an AvatarMoveCell through the same
 * loopback pipeline as MovableComponentTest and pins the avatar-specific
 * deltas: the receiver registers under MovableAvatarMessage (exact-class key,
 * so the plain MovableMessage key stays empty), every outbound carries the
 * trigger/pressed/animation/height/collision payload, the trigger path is
 * deliberately UNthrottled, a non-null move-modified listener is "Not
 * supported", and the server reply folds trigger state into the component and
 * the trigger callbacks into the owning AvatarCell (guarded by the renderer
 * type, so headless they stay inert -- the scene-graph dispatch is M6).
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.AvatarMoveCell;
import org.jdesktop.wonderland.client.cell.view.ViewCell;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableAvatarMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableMessage;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.cell.state.MovableAvatarComponentClientState;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.view.ViewCellClientState;
import org.jdesktop.wonderland.common.messages.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MovableAvatarComponentTest {

    private CellClientSession session;
    private PipelineSession loop;
    private CellCacheBasicImpl cache;

    @BeforeEach
    void setUp() throws Exception {
        session = new CellClientSession();
        loop = new PipelineSession();
        loop.responder = (c, msg) -> {
            if (msg instanceof CellHierarchyMessage
                    && ((CellHierarchyMessage) msg).getActionType()
                            == CellHierarchyMessage.ActionType.SET_VIEW) {
                c.messageReceived(new ViewCreateResponseMessage(
                        msg.getMessageID(), new CellID(7777L)));
            }
        };
        session.connect(loop);
        cache = (CellCacheBasicImpl) session.getCellCache();
    }

    private AvatarMoveCell loadAvatarCell(CellID id) {
        cache.setViewCell(CellTestFixtures.viewCell(cache));
        // AvatarCell IS a ViewCell: the faithful setClientState cast demands a
        // ViewCellClientState setup, not the plain one the cache tests use
        ViewCellClientState state = new ViewCellClientState();
        state.setName("a-" + id);
        return (AvatarMoveCell) cache.loadCell(id, AvatarMoveCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), state, "a-" + id);
    }

    private static Vector3f translationOf(Cell cell) {
        return cell.getLocalTransform().getTranslation(null);
    }

    /** the avatar moves only -- outbound also holds connect's SET_VIEW message */
    private List<MovableAvatarMessage> sentAvatars() {
        List<MovableAvatarMessage> out = new ArrayList<>();
        for (Message m : loop.outbound) {
            if (m instanceof MovableAvatarMessage mam) {
                out.add(mam);
            }
        }
        return out;
    }

    @Test
    void avatarReceiverRegistersUnderTheAvatarMessageClass() {
        AvatarMoveCell cell = loadAvatarCell(CellTestFixtures.id(1));
        ChannelComponent ch = (ChannelComponent) cell.getChannel();
        assertThat(ch.getMessageReceiver(MovableAvatarMessage.class)).isNotNull();
        assertThat(ch.getMessageReceiver(MovableMessage.class)).isNull();
    }

    @Test
    void plainMoveCarriesTheNoTriggerAvatarPayload() {
        CellID id = CellTestFixtures.id(1);
        AvatarMoveCell cell = loadAvatarCell(id);

        cell.movable.localMoveRequest(CellTestFixtures.translation(1f, 2f, 3f));

        assertThat(sentAvatars()).hasSize(1);
        MovableAvatarMessage out = sentAvatars().get(0);
        assertThat(out.getCellID()).isEqualTo(id);
        assertThat(out.getTrigger()).isEqualTo(MovableAvatarComponent.NO_TRIGGER);
        assertThat(out.getAnimationName()).isNull();
        assertThat(translationOf(cell)).isEqualTo(new Vector3f(1f, 2f, 3f));
    }

    @Test
    @Timeout(5)
    void triggeredMovesBypassTheThrottle() {
        AvatarMoveCell cell = loadAvatarCell(CellTestFixtures.id(1));

        cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3),
                5, true, "walk", 0.2f, true, null);
        cell.movable.localMoveRequest(CellTestFixtures.translation(2, 3, 4),
                6, false, "run", 0.4f, false, null);

        // both immediate -- a triggered animation must never be rate-limited
        assertThat(sentAvatars()).hasSize(2);
        MovableAvatarMessage first = sentAvatars().get(0);
        assertThat(first.getTrigger()).isEqualTo(5);
        assertThat(first.isPressed()).isTrue();
        assertThat(first.getAnimationName()).isEqualTo("walk");
        assertThat(first.getHeight()).isEqualTo(0.2f);
        assertThat(first.isCollision()).isTrue();
        MovableAvatarMessage second = sentAvatars().get(1);
        assertThat(second.getTrigger()).isEqualTo(6);
        assertThat(second.isPressed()).isFalse();
        assertThat(translationOf(cell)).isEqualTo(new Vector3f(2f, 3f, 4f));
    }

    @Test
    void moveModifiedListenerIsNotSupportedForAvatars() {
        AvatarMoveCell cell = loadAvatarCell(CellTestFixtures.id(1));
        MovableComponent.CellMoveModifiedListener listener = (r, why, a) -> {
        };
        assertThatThrownBy(() -> cell.movable.createMoveResponseListener(listener))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Not supported");
        // the listener-taking public form degrades to the null-listener send
        cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3), listener);
        assertThat(sentAvatars()).hasSize(1);
    }

    @Test
    void serverMoveFoldsIntoTheComponentAndTheAvatarCell() {
        CellID id = CellTestFixtures.id(1);
        AvatarMoveCell cell = loadAvatarCell(id);
        // arm the trigger state so the reply's server fields are stored
        cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3),
                5, true, "walk", 0f, false, null);
        loop.outbound.clear();

        List<MovableComponent.CellMoveSource> sources = new ArrayList<>();
        cell.movable.addServerCellMoveListener((t, s) -> sources.add(s));

        MovableAvatarMessage inbound = MovableAvatarMessage.newMoveRequestMessage(
                id, new Vector3f(2f, 3f, 4f), new Quaternion(),
                7, true, "jump", 0.5f, true);
        session.getCellChannelConnection().handleMessage(inbound);

        assertThat(sources).containsExactly(MovableComponent.CellMoveSource.REMOTE);
        assertThat(translationOf(cell)).isEqualTo(new Vector3f(2f, 3f, 4f));
        assertThat(cell.movable.getServerTrigger()).isEqualTo(7);
        assertThat(cell.movable.getServerAnimationName()).isEqualTo("jump");
        // headless: the renderer-type guard holds, the AvatarCell callbacks
        // run but stay inert (the scene-graph dispatch is rebuilt at M6)
        assertThat(cell.getStatus().ordinal()).isGreaterThanOrEqualTo(CellStatus.ACTIVE.ordinal());
    }

    @Test
    void serverMoveWithoutArmedTriggerKeepsServerFieldsUnset() {
        // the false sides: this.trigger == NO_TRIGGER (a plain local move
        // armed it to -1, as the original's field does) and an inbound reply
        // carrying no trigger -- neither the server fields nor the action
        // callback fire
        CellID id = CellTestFixtures.id(1);
        AvatarMoveCell cell = loadAvatarCell(id);
        cell.movable.localMoveRequest(CellTestFixtures.translation(1, 2, 3));

        MovableAvatarMessage inbound = MovableAvatarMessage.newMoveRequestMessage(
                id, new Vector3f(2f, 3f, 4f), new Quaternion(),
                MovableAvatarComponent.NO_TRIGGER, false, null, 0f, false);
        session.getCellChannelConnection().handleMessage(inbound);

        assertThat(translationOf(cell)).isEqualTo(new Vector3f(2f, 3f, 4f));
        assertThat(cell.movable.getServerTrigger()).isZero();
        assertThat(cell.movable.getServerAnimationName()).isNull();
    }

    @Test
    void serverFoldAlsoRunsWhenTheRendererGuardOpens() {
        try {
            // the type is set BEFORE the load so the ACTIVE rung actually
            // invokes AvatarCell's renderer hook (headless: returns null)
            Cell.setCurrentRendererType(Cell.RendererType.RENDERER_JME);
            AvatarMoveCell cell = loadAvatarCell(CellTestFixtures.id(1));
            MovableAvatarMessage inbound = MovableAvatarMessage.newMoveRequestMessage(
                    cell.getCellID(), new Vector3f(1f, 1f, 1f), new Quaternion(),
                    4, false, "wave", 0.3f, false);
            session.getCellChannelConnection().handleMessage(inbound);
            // guard open, but AvatarCell's headless renderer hook returns null:
            // the dispatches find no AvatarActionTrigger and must not throw
            cell.triggerGoto(new Vector3f(1f, 1f, 1f), new Quaternion());
            assertThat(translationOf(cell)).isEqualTo(new Vector3f(1f, 1f, 1f));
        } finally {
            Cell.setCurrentRendererType(null);
        }
    }

    @Test
    void clientStateCarriesTheServerTriggerValues() {
        AvatarMoveCell cell = loadAvatarCell(CellTestFixtures.id(1));
        MovableAvatarComponentClientState state = new MovableAvatarComponentClientState();
        state.setTrigger(3);
        state.setAnimationName("wave");
        cell.movable.setClientState(state);
        assertThat(cell.movable.getServerTrigger()).isEqualTo(3);
        assertThat(cell.movable.getServerAnimationName()).isEqualTo("wave");
    }

    @Test
    @Timeout(10)
    void localMoveOverloadsAllDelegateToTheFullForm() throws InterruptedException {
        CellID id = CellTestFixtures.id(1);
        AvatarMoveCell cell = loadAvatarCell(id);
        CellTransform t = CellTestFixtures.translation(1, 2, 3);

        cell.movable.localMoveRequest(t, 0.7f, true);
        cell.movable.localMoveRequest(t);

        // both take the throttled NO_TRIGGER path: the second is buffered
        long deadline = System.currentTimeMillis() + 4000;
        while (sentAvatars().size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(sentAvatars()).hasSize(2);
        MovableAvatarMessage withHeight = sentAvatars().get(0);
        assertThat(withHeight.getHeight()).isEqualTo(0.7f);
        assertThat(withHeight.isCollision()).isTrue();
        assertThat(withHeight.getTrigger()).isEqualTo(MovableAvatarComponent.NO_TRIGGER);
    }

    @Test
    void plainClientStateDoesNotBreakTheViewCellCast() {
        // A seeded resident is an AvatarCell whose wire state the seeder now
        // gives as a ViewCellClientState -- but any foreign or legacy frame can
        // still hand a plain CellClientState. ViewCell.setClientState once cast
        // it blindly and threw ClassCastException inside loadCell, which the
        // client cache logged SEVERE and the desktop surfaced as a crash dialog
        // on walking into the clinic. The cast is now guarded: the cell loads and
        // the identity simply stays unset -- a name label never aborts a load.
        CellID id = CellTestFixtures.id(3);
        cache.setViewCell(CellTestFixtures.viewCell(cache));
        CellClientState plain = new CellClientState();
        plain.setName("resident");
        Cell cell = cache.loadCell(id, AvatarMoveCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), plain, "resident");
        assertThat(cell).isNotNull();
        assertThat(((ViewCell) cell).getIdentity()).isNull();
    }
}
