/*
 * Round-trip helper for the M1 codec tests. Every wire message class in
 * common/{messages,comms/messages,cell/messages} is packed with
 * MessagePacker.pack() and unpacked with MessagePacker.unpack(), so the
 * Java serialization format, the Externalizable MessageID envelope and the
 * Wonderland class-descriptor cache are exercised exactly as the M3 Spring
 * Boot WebSocket handler will use them.
 */
package org.jdesktop.wonderland.common.messages;

import java.nio.ByteBuffer;
import org.jdesktop.wonderland.common.messages.MessagePacker.PackerException;
import org.jdesktop.wonderland.common.messages.MessagePacker.ReceivedMessage;

/**
 * Package-private helper used by {@link MessagePackerTest} and the codec tests
 * that live alongside it. Not a test class itself; the JUnit-platform scanner
 * ignores it because it has no {@code @Test} methods.
 */
final class RoundTrip {

    private RoundTrip() {
    }

    /**
     * Pack then unpack, asserting both directions complete and the returned
     * envelope carries the same clientID we sent. The Message reference is
     * <em>not</em> identity-equal after unpack (Java deserialisation gives us
     * a fresh object) so the caller compares semantic fields.
     */
    static ReceivedMessage through(Message message, short clientID) {
        try {
            ByteBuffer packed = MessagePacker.pack(message, clientID);
            return MessagePacker.unpack(packed);
        } catch (PackerException ex) {
            throw new AssertionError("round trip failed for "
                    + message.getClass().getSimpleName(), ex);
        }
    }

    static ReceivedMessage through(Message message) {
        return through(message, (short) 42);
    }
}
