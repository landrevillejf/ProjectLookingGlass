/*
 * M1 codec pin: the comms handshake messages. The M3 Spring Boot session
 * server accepts these on its WebSocket endpoint in exactly this order
 * (SessionInitialization -> AttachClient -> DetachClient), so their Java
 * serialisation shape is the wire contract.
 */
package org.jdesktop.wonderland.common.comms.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Properties;
import org.jdesktop.wonderland.common.auth.WonderlandIdentity;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.comms.SessionInternalConnectionType;
import org.jdesktop.wonderland.common.messages.MessageID;
import org.jdesktop.wonderland.common.messages.MessagePacker;
import org.jdesktop.wonderland.common.messages.MessagePacker.ReceivedMessage;
import org.junit.jupiter.api.Test;

class CommsMessagesCodecTest {

    private static ReceivedMessage roundTrip(Object msg, short clientID) {
        try {
            return MessagePacker.unpack(
                    MessagePacker.pack((org.jdesktop.wonderland.common.messages.Message) msg, clientID));
        } catch (MessagePacker.PackerException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void attachClientRoundTripsConnectionTypeAndProperties() {
        Properties props = new Properties();
        props.setProperty("locale", "fr_FR");
        props.setProperty("version", "0.5-preview5");
        ConnectionType type = new ConnectionType("LOCAL_SESSION");
        AttachClientMessage msg = new AttachClientMessage(type, props);

        AttachClientMessage back = (AttachClientMessage) roundTrip(msg, (short) 9).getMessage();
        assertThat(back.getClientType()).isEqualTo(type);
        assertThat(back.getProperties()).containsEntry("locale", "fr_FR");
        assertThat(back.getProperties()).containsEntry("version", "0.5-preview5");
    }

    @Test
    void attachClientAcceptsEmptyPropertiesEnvelope() {
        AttachClientMessage msg =
                new AttachClientMessage(new ConnectionType("INTERNAL"), new Properties());
        AttachClientMessage back =
                (AttachClientMessage) roundTrip(msg, (short) 1).getMessage();
        assertThat(back.getProperties()).isEmpty();
    }

    @Test
    void sessionInternalConnectionTypeIsSerializableAsAConnectionType() {
        // SessionInternalConnectionType is the marker subclass the client
        // sends when the attach is a same-JVM session; the server-side cast
        // depends on the class surviving deserialisation as that type, not
        // as its ConnectionType base.
        Properties props = new Properties();
        AttachClientMessage msg =
                new AttachClientMessage(new SessionInternalConnectionType(), props);
        AttachClientMessage back =
                (AttachClientMessage) roundTrip(msg, (short) 3).getMessage();
        assertThat(back.getClientType()).isInstanceOf(SessionInternalConnectionType.class);
    }

    @Test
    void attachedClientRoundTripsClientIDAndReplyTo() {
        MessageID replyTo = new MessageID(0xABCDL);
        AttachedClientMessage msg = new AttachedClientMessage(replyTo, (short) 42);
        AttachedClientMessage back =
                (AttachedClientMessage) roundTrip(msg, (short) 0).getMessage();
        assertThat(back.getClientID()).isEqualTo((short) 42);
        assertThat(back.getMessageID()).isEqualTo(replyTo);
    }

    @Test
    void detachClientRoundTripsClientID() {
        DetachClientMessage msg = new DetachClientMessage((short) -7);
        DetachClientMessage back =
                (DetachClientMessage) roundTrip(msg, (short) 1).getMessage();
        assertThat(back.getClientID()).isEqualTo((short) -7);
    }

    @Test
    void sessionInitializationRoundTripsBigIntegerSessionAndIdentity() {
        // sessionIDs in Wonderland are BigInteger (they can exceed long range
        // across restarts), and WonderlandIdentity is the plain Serializable
        // user record -- both are core to what M3 will keep in a session map.
        BigInteger sessionID = new BigInteger(
                "12345678901234567890123456789012345678901234567890");
        WonderlandIdentity user =
                new WonderlandIdentity("alice", "Alice Example", "alice@example.com");
        SessionInitializationMessage msg =
                new SessionInitializationMessage(sessionID, user);

        SessionInitializationMessage back = (SessionInitializationMessage)
                roundTrip(msg, (short) 11).getMessage();
        assertThat(back.getSessionID()).isEqualTo(sessionID);
        assertThat(back.getUserID().getUsername()).isEqualTo("alice");
        assertThat(back.getUserID().getFullName()).isEqualTo("Alice Example");
        assertThat(back.getUserID().getEmail()).isEqualTo("alice@example.com");
        assertThat(back.getUserID()).isEqualTo(user);
    }

    @Test
    void sessionInitializationAcceptsNullIdentityForAnonymousHandshake() {
        SessionInitializationMessage msg =
                new SessionInitializationMessage(BigInteger.valueOf(7L), null);
        SessionInitializationMessage back = (SessionInitializationMessage)
                roundTrip(msg, (short) 1).getMessage();
        assertThat(back.getSessionID()).isEqualTo(BigInteger.valueOf(7L));
        assertThat(back.getUserID()).isNull();
    }
}
