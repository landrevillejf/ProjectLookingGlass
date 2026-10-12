/*
 * M1 codec pin: the protocol envelope itself. Every Message carries a
 * MessageID written via Externalizable -- not Java serialisation -- so a
 * regression in writeExternal/readExternal breaks every subsequent message.
 *
 * These tests also lock the "class-descriptor id" cache that
 * WonderlandObjectOutputStream installs for the nine core classes: pack
 * twice in the same JVM and the second stream reuses the short id form
 * rather than re-encoding the class name. That is what lets M3's Spring
 * Boot WebSocket handler stay within the wire budget.
 */
package org.jdesktop.wonderland.common.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import org.jdesktop.wonderland.common.comms.DefaultProtocolVersion;
import org.jdesktop.wonderland.common.comms.ProtocolVersion;
import org.jdesktop.wonderland.common.messages.MessagePacker.ReceivedMessage;
import org.junit.jupiter.api.Test;

class MessagePackerTest {

    @Test
    void messageIDRoundTripsLongValueViaExternalizable() {
        MessageID original = new MessageID(0x1122334455667788L);
        Message msg = new OKMessage(original);
        ReceivedMessage back = RoundTrip.through(msg, (short) 7);

        assertThat(back.getClientID()).isEqualTo((short) 7);
        assertThat(back.getMessage()).isInstanceOf(OKMessage.class);
        assertThat(back.getMessage().getMessageID()).isEqualTo(original);
        assertThat(back.getMessage().getMessageID().getID()).isEqualTo(0x1122334455667788L);
    }

    @Test
    void nullMessageIDSurvivesAsLongMinValueSentinel() {
        // MessageID.equals() has a special case for Long.MIN_VALUE -- any two
        // "null" ids compare equal. Locking that here because the client uses
        // it as the "no reply-to" marker on non-response messages.
        Message msg = new OKMessage(new MessageID());
        ReceivedMessage back = RoundTrip.through(msg);
        assertThat(back.getMessage().getMessageID().getID()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void generateMessageIDProducesDistinctValues() {
        MessageID a = MessageID.generateMessageID();
        MessageID b = MessageID.generateMessageID();
        assertThat(a).isNotEqualTo(b);
        assertThat(a.getID()).isNotEqualTo(b.getID());
    }

    @Test
    void errorMessageCarriesTextCauseAndReplyTo() {
        MessageID replyTo = new MessageID(1234L);
        ErrorMessage msg = new ErrorMessage(replyTo, "boom", new IllegalStateException("inner"));
        ReceivedMessage back = RoundTrip.through(msg);

        assertThat(back.getMessage()).isInstanceOf(ErrorMessage.class);
        ErrorMessage e = (ErrorMessage) back.getMessage();
        assertThat(e.getErrorMessage()).isEqualTo("boom");
        assertThat(e.getErrorCause()).isInstanceOf(IllegalStateException.class);
        assertThat(e.getErrorCause().getMessage()).isEqualTo("inner");
        // ResponseMessage passes the reply-to id into Message's messageID slot.
        assertThat(e.getMessageID()).isEqualTo(replyTo);
    }

    @Test
    void errorMessageWithNullCauseRoundTrips() {
        ErrorMessage msg = new ErrorMessage(new MessageID(9L), "solo");
        ErrorMessage back = (ErrorMessage) RoundTrip.through(msg).getMessage();
        assertThat(back.getErrorMessage()).isEqualTo("solo");
        assertThat(back.getErrorCause()).isNull();
    }

    @Test
    void protocolSelectionRoundTripsNameAndVersion() {
        ProtocolVersion v = new DefaultProtocolVersion(1, 2, 3);
        ProtocolSelectionMessage msg = new ProtocolSelectionMessage("wonderland", v);
        ProtocolSelectionMessage back =
                (ProtocolSelectionMessage) RoundTrip.through(msg).getMessage();

        assertThat(back.getProtocolName()).isEqualTo("wonderland");
        assertThat(back.getProtocolVersion()).isInstanceOf(DefaultProtocolVersion.class);
        DefaultProtocolVersion bv = (DefaultProtocolVersion) back.getProtocolVersion();
        assertThat(bv.getMajorVersion()).isEqualTo(1);
        assertThat(bv.getMinorVersion()).isEqualTo(2);
        assertThat(bv.getSubVersion()).isEqualTo(3);
    }

    @Test
    void emptyMessageListSurvivesRoundTrip() {
        MessageList list = new MessageList();
        MessageList back = (MessageList) RoundTrip.through(list).getMessage();
        assertThat(back.size()).isZero();
        assertThat(back.getMessages()).isInstanceOf(LinkedList.class);
    }

    @Test
    void messageListCarriesNestedMessagesWithTheirOwnIDs() {
        MessageList list = new MessageList();
        MessageID ok1 = new MessageID(11L);
        MessageID err2 = new MessageID(22L);
        list.addMessage(new OKMessage(ok1));
        list.addMessage(new ErrorMessage(err2, "nested"));
        assertThat(list.size()).isEqualTo(2);

        MessageList back = (MessageList) RoundTrip.through(list).getMessage();
        assertThat(back.size()).isEqualTo(2);
        assertThat(back.getMessages()).hasSize(2);
        Message first = (Message) back.getMessages().get(0);
        Message second = (Message) back.getMessages().get(1);
        assertThat(first).isInstanceOf(OKMessage.class);
        assertThat(first.getMessageID()).isEqualTo(ok1);
        assertThat(second).isInstanceOf(ErrorMessage.class);
        assertThat(((ErrorMessage) second).getErrorMessage()).isEqualTo("nested");
        assertThat(second.getMessageID()).isEqualTo(err2);
    }

    @Test
    void wonderlandSetupMessageIsAnEmptyEnvelopeThatStillRoundTrips() {
        // Subclasses Message directly with no extra fields; the value of
        // testing it is that even the trivial path exercises the descriptor
        // cache and the Externalizable id.
        WonderlandSetupMessage msg = new WonderlandSetupMessage();
        WonderlandSetupMessage back =
                (WonderlandSetupMessage) RoundTrip.through(msg).getMessage();
        assertThat(back.getMessageID()).isEqualTo(msg.getMessageID());
    }

    @Test
    void twoConsecutivePacksProduceIndependentBuffers() {
        // The WonderlandObjectOutputStream keeps a *static* descriptor-id map
        // for the nine core wire classes (see WonderlandObjectOutputStream.coreClass).
        // It is populated in the class's static initializer and *never
        // mutated*, so two consecutive packs in the same JVM are independent
        // streams that both resolve those ids. This test is the pin that
        // keeps the map read-only; a future refactor that made it per-stream
        // would still round-trip here but break for MovableMessage (whose
        // Vector3f/Quaternion fields ride the id path).
        try {
            ByteBuffer a = MessagePacker.pack(new OKMessage(new MessageID(1L)), (short) 5);
            ByteBuffer b = MessagePacker.pack(new OKMessage(new MessageID(2L)), (short) 6);
            ReceivedMessage ra = MessagePacker.unpack(a);
            ReceivedMessage rb = MessagePacker.unpack(b);
            assertThat(ra.getClientID()).isEqualTo((short) 5);
            assertThat(rb.getClientID()).isEqualTo((short) 6);
            assertThat(ra.getMessage().getMessageID().getID()).isEqualTo(1L);
            assertThat(rb.getMessage().getMessageID().getID()).isEqualTo(2L);
        } catch (MessagePacker.PackerException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void packedEnvelopeCarriesClientIDThroughEvenForZero() {
        Message msg = new OKMessage(new MessageID(1L));
        ReceivedMessage back = RoundTrip.through(msg, (short) 0);
        assertThat(back.getClientID()).isEqualTo((short) 0);
    }
}
