/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland chat (M9): the client's chat channel, driven through the real
 * cache and the real CellChannelConnection with the M2e in-JVM pipeline as its
 * transport.
 *
 * What these cases pin is the whole of the class's contract, and none of it
 * needs a server: the world cell is attached whether it loaded before or after
 * start(); a line goes out addressed to the world with the server's placeholder
 * as its claimed name (the relay rewrites it, so what is claimed here is
 * deliberately not what is shown); a refusal comes back as the server's reason
 * rather than an exception; and a line arriving from the world reaches every
 * listener exactly once, chat or not.
 */
package org.jdesktop.wonderland.client.cell;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.CacheableEnvCell;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.client.comms.ConnectionTestFixtures.PipelineSession;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.messages.TextChatMessage;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.messages.ErrorMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatChannelTest {

    /** One received line, as the listener saw it. */
    private record Line(String sender, String text, long sentAt, BigInteger senderID) {
    }

    /** A cell-scoped message that is not chat, for the guard's negative case. */
    static class ProbeMessage extends CellMessage {
        ProbeMessage(CellID cellID) {
            super(cellID);
        }
    }

    private CellClientSession session;
    private PipelineSession loop;
    private CellCacheBasicImpl cache;
    private ChatChannel chat;
    private List<Line> received;

    @BeforeEach
    void setUp() throws Exception {
        session = new CellClientSession();
        loop = new PipelineSession();
        loop.responder = (connection, message) -> {
            if (message instanceof CellHierarchyMessage
                    && ((CellHierarchyMessage) message).getActionType()
                            == CellHierarchyMessage.ActionType.SET_VIEW) {
                connection.messageReceived(new ViewCreateResponseMessage(
                        message.getMessageID(), new CellID(7777L)));
            }
        };
        session.connect(loop);
        cache = (CellCacheBasicImpl) session.getCellCache();
        chat = new ChatChannel(session);
        received = new ArrayList<>();
        chat.addListener((sender, text, sentAt, senderID) ->
                received.add(new Line(sender, text, sentAt, senderID)));
    }

    /** Load the world root the way the server's fan-out does. */
    private Cell loadWorld() {
        return cache.loadCell(CellID.getEnvironmentCellID(),
                CacheableEnvCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), null, "world");
    }

    @Test
    void nullSessionIsRefusedAtOnce() {
        assertThatThrownBy(() -> new ChatChannel(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(5)
    void startAttachesAWorldThatLoadedFirstAndDeliversALine() {
        loadWorld();
        chat.start();
        assertThat(chat.isReady()).isTrue();

        session.getCellChannelConnection().handleMessage(new TextChatMessage(
                CellID.getEnvironmentCellID(), "Alice A", "the wait is 20 minutes",
                1234L));

        assertThat(received).containsExactly(new Line("Alice A",
                "the wait is 20 minutes", 1234L, null));
    }

    @Test
    @Timeout(5)
    void startBeforeTheHandshakeAttachesWhenTheWorldArrives() {
        chat.start();
        assertThat(chat.isReady()).isFalse();

        loadWorld();

        assertThat(chat.isReady()).isTrue();
        // and the receiver is on the cell's channel, so the wire reaches it
        session.getCellChannelConnection().handleMessage(new TextChatMessage(
                CellID.getEnvironmentCellID(), "Bob", "you there?", 5L));
        assertThat(received).hasSize(1);
        assertThat(received.get(0).sender()).isEqualTo("Bob");
    }

    @Test
    @Timeout(5)
    void aLoadOfSomeOtherCellNeverAttachesChat() {
        chat.start();
        cache.loadCell(CellTestFixtures.id(11),
                CellTestFixtures.CacheableCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), null, "room");
        assertThat(chat.isReady()).isFalse();
    }

    @Test
    @Timeout(5)
    void aWorldCellWithoutAChannelIsNotAttachedYet() {
        chat.start();
        // The session installs every loaded cell's channel, so this only
        // happens for a cell that arrived by another route -- and the honest
        // answer is "not ready", not a half-attached channel.
        chat.cellLoaded(CellID.getEnvironmentCellID(), new CacheableEnvCell(
                CellID.getEnvironmentCellID(), cache));
        assertThat(chat.isReady()).isFalse();
    }

    @Test
    @Timeout(5)
    void aSecondLoadOfTheWorldWhileAttachedRegistersNoExtraReceiver() {
        Cell world = loadWorld();
        chat.start();
        // the cache keeps its listener registered to catch a reload, so a load
        // that arrives while already attached must be the no-op it looks like
        chat.cellLoaded(CellID.getEnvironmentCellID(), new CacheableEnvCell(
                CellID.getEnvironmentCellID(), cache));

        session.getCellChannelConnection().handleMessage(new TextChatMessage(
                CellID.getEnvironmentCellID(), "Alice", "still one receiver", 4L));
        assertThat(received).hasSize(1);
        assertThat(chat.isReady()).isTrue();
        assertThat(world.getChannel()).isNotNull();
    }

    @Test
    @Timeout(5)
    void anUnloadOfNothingOrOfAnotherCellLeavesChatAttached() {
        loadWorld();
        chat.start();

        chat.cellUnloaded(CellTestFixtures.id(5), null);
        chat.cellUnloaded(CellTestFixtures.id(5), new CacheableEnvCell(
                CellTestFixtures.id(5), cache));

        assertThat(chat.isReady()).isTrue();
        session.getCellChannelConnection().handleMessage(new TextChatMessage(
                CellID.getEnvironmentCellID(), "Alice", "unaffected", 6L));
        assertThat(received).hasSize(1);
    }

    @Test
    @Timeout(5)
    void stoppingBeforeTheWorldLoadsHasNothingToDetach() {
        chat.start();
        chat.stop();
        assertThat(chat.isReady()).isFalse();
        // and it can still come back to life once the world does arrive
        chat.start();
        loadWorld();
        assertThat(chat.isReady()).isTrue();
    }

    @Test
    @Timeout(5)
    void aRefusedLineSentWithNoWhereForTheReasonIsSilentNotThrown() {
        // the one-argument send: a caller that cannot show a reason must not
        // pay for the guard with an exception on the typing thread
        chat.start();
        loop.outbound.clear();
        chat.send("anyone?");
        assertThat(loop.outbound).isEmpty();

        loadWorld();
        chat.start();
        loop.outbound.clear();
        chat.send("   ");
        assertThat(loop.outbound).isEmpty();
    }

    @Test
    @Timeout(5)
    void sendAddressesTheWorldAndClaimsNothingAboutTheAuthor() {
        loadWorld();
        chat.start();
        loop.outbound.clear();

        chat.send("exam room 1 is free");

        TextChatMessage sent = (TextChatMessage) loop.outbound.get(0);
        assertThat(sent.getCellID()).isEqualTo(CellID.getEnvironmentCellID());
        assertThat(sent.getText()).isEqualTo("exam room 1 is free");
        // the name is the placeholder, because the server is the one that
        // decides who spoke -- a client that claimed a name would show its
        // author one transcript and everyone else another
        assertThat(sent.getSenderName()).isEqualTo(ChatChannel.UNCLAIMED_NAME);
    }

    @Test
    @Timeout(5)
    void sendBeforeTheWorldLoadsSaysSoAndTouchesNoWire() {
        chat.start();
        List<String> reasons = new ArrayList<>();
        loop.outbound.clear();

        chat.send("anyone?", reasons::add);

        assertThat(reasons).containsExactly(ChatChannel.NO_WORLD);
        assertThat(loop.outbound).isEmpty();
    }

    @Test
    @Timeout(5)
    void aLineTheLocalBoundsRefuseNeverReachesTheServer() {
        loadWorld();
        chat.start();
        List<String> reasons = new ArrayList<>();
        loop.outbound.clear();

        chat.send("   ", reasons::add);
        chat.send("x".repeat(TextChatMessage.MAX_TEXT_CHARS + 1), reasons::add);

        assertThat(reasons).hasSize(2);
        assertThat(reasons.get(0)).contains("blank");
        assertThat(reasons.get(1)).contains("longer than");
        assertThat(loop.outbound).isEmpty();
    }

    @Test
    @Timeout(5)
    void theServersRefusalComesBackAsItsReasonNotAnException() {
        // answer every outbound chat with the server's own refusal, the way
        // ProtocolRouter does for a line addressed at the wrong cell
        loop.responder = (connection, message) -> {
            if (message instanceof TextChatMessage) {
                connection.messageReceived(new ErrorMessage(
                        message.getMessageID(), "chat must be addressed elsewhere"));
            }
        };
        loadWorld();
        chat.start();
        List<String> reasons = new ArrayList<>();

        chat.send("shouted into the room", reasons::add);

        assertThat(reasons).containsExactly("chat must be addressed elsewhere");
    }

    @Test
    @Timeout(5)
    void anAcceptedLineIsNotEchoedLocallyBecauseTheRelayBringsItBack() {
        loop.responder = (connection, message) -> {
            if (message instanceof TextChatMessage) {
                connection.messageReceived(new org.jdesktop.wonderland.common
                        .messages.OKMessage(message.getMessageID()));
            }
        };
        loadWorld();
        chat.start();
        List<String> reasons = new ArrayList<>();

        chat.send("back soon", reasons::add);

        // no failure, and no local post either: the author's copy arrives the
        // same way everyone else's does, carrying the authenticated name
        assertThat(reasons).isEmpty();
        assertThat(received).isEmpty();
    }

    @Test
    @Timeout(5)
    void aMessageThatIsNotChatForTheWorldCellIsIgnored() {
        loadWorld();
        chat.start();
        // the channel dispatches by exact class, so this cannot reach the chat
        // receiver at all -- the guard is for the direct call a test can make
        chat.messageReceived(new ProbeMessage(CellTestFixtures.id(3)));
        assertThat(received).isEmpty();
    }

    @Test
    @Timeout(5)
    void stopDetachesAndASendAfterItIsRefused() {
        loadWorld();
        chat.start();
        chat.stop();
        List<String> reasons = new ArrayList<>();
        loop.outbound.clear();

        chat.send("still here?", reasons::add);

        assertThat(chat.isReady()).isFalse();
        assertThat(reasons).containsExactly(ChatChannel.NO_WORLD);
        assertThat(loop.outbound).isEmpty();
    }

    @Test
    @Timeout(5)
    void startingAndStoppingTwiceChangeNothing() {
        loadWorld();
        chat.start();
        chat.start();                       // the receiver must not be added twice
        assertThat(chat.isReady()).isTrue();

        chat.stop();
        chat.stop();
        assertThat(chat.isReady()).isFalse();

        // and it can be re-armed after a stop without a duplicate registration
        chat.start();
        session.getCellChannelConnection().handleMessage(new TextChatMessage(
                CellID.getEnvironmentCellID(), "Alice", "round two", 9L));
        assertThat(received).hasSize(1);
    }

    @Test
    @Timeout(5)
    void unloadingTheWorldDetachesTheChannel() {
        loadWorld();
        chat.start();

        cache.unloadCell(CellID.getEnvironmentCellID());

        assertThat(chat.isReady()).isFalse();
    }

    @Test
    @Timeout(5)
    void aWorldThatReloadsIsHeardAgain() {
        chat.start();
        loadWorld();
        cache.unloadCell(CellID.getEnvironmentCellID());

        loadWorld();

        // the cache listener was never dropped, so the re-entered world is not
        // a deaf transcript behind a connected wire
        assertThat(chat.isReady()).isTrue();
        session.getCellChannelConnection().handleMessage(new TextChatMessage(
                CellID.getEnvironmentCellID(), "Alice", "back in the room", 3L));
        assertThat(received).hasSize(1);
    }

    @Test
    @Timeout(5)
    void aFailedWorldLoadLeavesChatSilentRatherThanThrowing() {
        chat.start();
        chat.cellLoadFailed(CellID.getEnvironmentCellID(), "x", null,
                new IllegalStateException("nope"));
        assertThat(chat.isReady()).isFalse();
    }

    @Test
    @Timeout(5)
    void listenersAreRegisteredOnceAndANullListenerIsIgnored() {
        List<Line> second = new ArrayList<>();
        ChatChannel.Listener listener = (sender, text, sentAt, id) -> second.add(
                new Line(sender, text, sentAt, id));

        chat.addListener(null);
        chat.addListener(listener);
        chat.addListener(listener);
        loadWorld();
        chat.start();

        session.getCellChannelConnection().handleMessage(new TextChatMessage(
                CellID.getEnvironmentCellID(), "Alice", "twice is once", 1L));

        assertThat(second).hasSize(1);
        chat.removeListener(listener);
        session.getCellChannelConnection().handleMessage(new TextChatMessage(
                CellID.getEnvironmentCellID(), "Alice", "gone", 2L));
        assertThat(second).hasSize(1);
        assertThat(received).hasSize(2);
    }
}
