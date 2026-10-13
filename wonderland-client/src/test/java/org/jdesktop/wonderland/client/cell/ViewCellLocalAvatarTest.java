/*
 * M2f: the view/avatar closure, end to end on the loopback pipeline. Pins the
 * configure handshake: the session registers the view (SET_VIEW ->
 * ViewCreateResponseMessage carries the view cell id); loading THAT cell walks
 * it to ACTIVE (the restored load guard: a ViewCell reaches ACTIVE even with no
 * view set), the ACTIVE rung matches the id and fires
 * LocalAvatar.viewCellConfigured -- which stores the cell on the avatar, hands
 * it to cache.setViewCell (the unconditional roots-to-VISIBLE walk, the
 * re-entrant up-walk observed from inside the load's own ACTIVE walk), and
 * notifies ViewCellConfiguredListeners. Unloading runs the mirror: the INACTIVE
 * rung (decreasing only) clears the view. Also the AvatarCell trigger dispatch
 * behind the RENDERER_JME guard, and the headless LocalAvatar no-ops.
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.MovableViewCell;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.TriggerableAvatarCell;
import org.jdesktop.wonderland.client.cell.view.AvatarCell;
import org.jdesktop.wonderland.client.cell.view.LocalAvatar;
import org.jdesktop.wonderland.client.cell.view.ViewCell;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.MovableAvatarMessage;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.cell.view.ViewCellClientState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ViewCellLocalAvatarTest {

    private static final CellID VIEW_ID = new CellID(42L);

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
                // the server says: your view cell is the one under test
                c.messageReceived(new ViewCreateResponseMessage(msg.getMessageID(), VIEW_ID));
            }
        };
        session.connect(loop);
        cache = (CellCacheBasicImpl) session.getCellCache();
    }

    private static ViewCellClientState viewState(String name) {
        ViewCellClientState state = new ViewCellClientState();
        state.setName(name);
        return state;
    }

    private MovableViewCell loadViewCell() {
        return (MovableViewCell) cache.loadCell(VIEW_ID, MovableViewCell.class.getName(),
                null, null, CellTestFixtures.translation(0f, 0f, 0f),
                viewState("view"), "view");
    }

    @Test
    void loadingTheViewCellConfiguresTheAvatar() {
        List<LocalAvatar> configured = new ArrayList<>();
        session.getLocalAvatar().addViewCellConfiguredListener(configured::add);
        // a second listener: the list exists by the time this one is added
        session.getLocalAvatar().addViewCellConfiguredListener(a -> {
        });

        Cell cell = loadViewCell();

        // ACTIVE rung -> viewCellConfigured -> setViewCell walks the root to
        // VISIBLE from inside the load's own walk (the re-entrant up-walk)
        assertThat(cell.getStatus()).isEqualTo(CellStatus.VISIBLE);
        assertThat(cache.getViewCell()).isSameAs(cell);
        assertThat(session.getLocalAvatar().getViewCell()).isSameAs(cell);
        assertThat(configured).hasSize(1);
        assertThat(configured.get(0)).isSameAs(session.getLocalAvatar());

        // with a view configured, the avatar's own move reaches the view cell
        loop.outbound.clear();
        session.getLocalAvatar().localMoveRequest(new Vector3f(1f, 2f, 3f), new Quaternion());
        assertThat(loop.outbound).hasSize(1);
        assertThat(loop.outbound.get(0)).isInstanceOf(MovableAvatarMessage.class);
    }

    @Test
    void viewCellMoveDelegatesToTheCapturedAvatarComponent() {
        ViewCell cell = loadViewCell();
        loop.outbound.clear(); // drop nothing pending -- the load sends no move

        cell.localMoveRequest(CellTestFixtures.translation(1f, 2f, 3f));

        assertThat(loop.outbound).hasSize(1);
        assertThat(loop.outbound.get(0)).isInstanceOf(MovableAvatarMessage.class);
        assertThat(cell.getLocalTransform().getTranslation(null))
                .isEqualTo(new Vector3f(1f, 2f, 3f));
    }

    @Test
    void unloadingTheViewCellClearsIt() {
        Cell cell = loadViewCell();
        assertThat(cache.getViewCell()).isNotNull();

        cache.unloadCell(VIEW_ID);

        assertThat(session.getLocalAvatar().getViewCell()).isNull();
        assertThat(cache.getViewCell()).isNull();
        assertThat(cell.getStatus()).isEqualTo(CellStatus.DISK);

        // the INACTIVE rung dropped movableComp: a later move request is inert
        loop.outbound.clear();
        ((ViewCell) cell).localMoveRequest(CellTestFixtures.translation(9f, 9f, 9f));
        assertThat(loop.outbound).isEmpty();
    }

    @Test
    void viewCellClientStateCarriesTheIdentity() {
        ViewCell cell = loadViewCell();
        WonderlandIdentity identity =
                new WonderlandIdentity("u", "User", "u@example.invalid");
        cell.setClientState(new ViewCellClientState(identity));
        assertThat(cell.getIdentity()).isSameAs(identity);
    }

    @Test
    void aViewCellThatIsNotTheRegisteredViewStaysPlain() {
        CellID other = CellTestFixtures.id(7);
        cache.setViewCell(CellTestFixtures.viewCell(cache)); // detached 9999 view
        MovableViewCell cell = (MovableViewCell) cache.loadCell(other,
                MovableViewCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f),
                viewState("other"), "other");

        // id 7 != viewCellID 42: the ACTIVE rung skips viewCellConfigured, so
        // the avatar never points at any cell; the cache still holds the
        // detached stand-in view that was set before the load
        assertThat(cell.getStatus()).isEqualTo(CellStatus.VISIBLE);
        assertThat(session.getLocalAvatar().getViewCell()).isNull();
        assertThat(cache.getViewCell().getCellID()).isEqualTo(CellTestFixtures.id(9999));

        // INACTIVE rung, still a mismatch: viewCellConfigured is not called,
        // the cache keeps the stand-in view it was handed
        cache.unloadCell(other);
        assertThat(cell.getStatus()).isEqualTo(CellStatus.DISK);
        assertThat(cache.getViewCell().getCellID()).isEqualTo(CellTestFixtures.id(9999));
    }

    @Test
    void avatarTriggersDispatchWhenTheRendererGuardOpens() {
        CellID id = CellTestFixtures.id(7);
        TriggerableAvatarCell cell;
        try {
            Cell.setCurrentRendererType(Cell.RendererType.RENDERER_JME);
            // ViewCell load guard: reaches ACTIVE even with no view set, and
            // the ACTIVE rung creates the renderer through the M2c hook
            cell = (TriggerableAvatarCell) cache.loadCell(id,
                    TriggerableAvatarCell.class.getName(), null, null,
                    CellTestFixtures.translation(0f, 0f, 0f),
                    viewState("av"), "av");
            assertThat(cell.getCellRenderer(Cell.RendererType.RENDERER_JME)).isNotNull();

            cell.triggerAction(1, true, "wave");
            cell.triggerCollision(0.5f, true);
            cell.triggerGoto(new Vector3f(1f, 1f, 1f), new Quaternion());
            assertThat(cell.renderer.triggers).containsExactly(
                    "action:1/true/wave", "collision:0.5/true", "goto");
        } finally {
            Cell.setCurrentRendererType(null);
        }

        // guard closed: the same calls record nothing and must not throw
        cell.triggerAction(2, false, "x");
        cell.triggerCollision(1f, false);
        cell.triggerGoto(new Vector3f(), new Quaternion());
        assertThat(cell.renderer.triggers).hasSize(3);
    }

    @Test
    void avatarCellTracksInputSelection() {
        AvatarCell cell = new CellTestFixtures.AvatarMoveCell(
                CellTestFixtures.id(8), null);
        assertThat(cell.isSelectedForInput()).isFalse();
        cell.setSelectedForInput(true);
        assertThat(cell.isSelectedForInput()).isTrue();
    }

    @Test
    void localAvatarWithoutAViewIsSilentAndNotifiesOnDemand() {
        CellClientSession bare = new CellClientSession(); // never connected
        assertThat(bare.getID()).isNull();
        LocalAvatar avatar = bare.getLocalAvatar();
        assertThat(avatar.getViewID()).isEqualTo("DEFAULT");

        List<LocalAvatar> seen = new ArrayList<>();
        LocalAvatar.ViewCellConfiguredListener listener = seen::add;
        avatar.removeViewCellConfiguredListener(listener); // nothing registered yet: silent
        avatar.addViewCellConfiguredListener(listener);

        avatar.localMoveRequest(new Vector3f(1f, 0f, 0f), new Quaternion()); // no view: no-op
        avatar.viewCellConfigured(null); // clears an already-null view, still notifies
        assertThat(seen).hasSize(1);
        assertThat(avatar.getViewCell()).isNull();

        avatar.removeViewCellConfiguredListener(listener);
        avatar.viewCellConfigured(null);
        assertThat(seen).hasSize(1);
    }
}
