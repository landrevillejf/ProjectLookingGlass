/**
 * $RCSfile: EscherDataInputStream.java,v $
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 *
 * $Revision: 1.2 $
 * $Date: 2026 lg3d JDK-21 port $
 * $State: Exp $
 */

package gnu.x11;

import java.io.DataInputStream;
import java.io.IOException;

/**
 * Thin wrapper around a {@link DataInputStream} used by {@link Connection}
 * and {@link DinReader} to consume X11 reply and event packets.
 *
 * <p>Historically this class also supported a raw file-descriptor mode
 * backed by six JNI native methods in {@code libEscher.so}
 * ({@code read}, {@code readFully}, {@code readUnsignedByte},
 * {@code readUnsignedShort}, {@code skip}, {@code available}) taking an
 * {@code int} fd. That mode existed only because JDK 15 and earlier had
 * no first-class AF_UNIX support, so {@link EscherSocket} had to open
 * the {@code /tmp/.X11-unix/X<n>} socket natively and hand back an
 * {@code int} fd. On JDK 16+ {@link java.net.UnixDomainSocketAddress}
 * plus {@link java.nio.channels.SocketChannel} cover the same
 * functionality in pure Java, so the fd mode is gone.
 *
 * <p><b>available() caveat</b>: when the wrapped stream originates from
 * {@link java.nio.channels.Channels#newInputStream} on a blocking
 * SocketChannel, {@link DataInputStream#available()} typically reports 0
 * because the JDK channel-adapter does not maintain a read-ahead buffer.
 * The only in-tree caller is {@link DinReader#skipAllAvailableBytes()} on
 * the {@link Connection#check_error()} recovery path, where a zero return
 * merely makes the buffer-drain a no-op; the accompanying
 * {@link DinReader#clearReplies()} still resets the reply queue, so
 * error recovery remains correct.
 */
public class EscherDataInputStream {

    private final DataInputStream inStream;

    public EscherDataInputStream (DataInputStream inStream) {
        if (inStream == null) {
            throw new NullPointerException("inStream");
        }
        this.inStream = inStream;
    }

    public long skip (long n)
        throws IOException
    {
        return inStream.skip(n);
    }

    public int available ()
        throws IOException
    {
        return inStream.available();
    }

    public int readUnsignedByte ()
        throws IOException
    {
        return inStream.readUnsignedByte();
    }

    public int readUnsignedShort ()
        throws IOException
    {
        // DataInputStream.readUnsignedShort() is always big-endian. The X11
        // wire format follows the byte order the client declared in the
        // connection setup, so on little-endian hosts we must swap.
        int b0 = inStream.readUnsignedByte();
        int b1 = inStream.readUnsignedByte();
        return Data.LSB_FIRST ? ((b1 << 8) | b0) : ((b0 << 8) | b1);
    }

    public int read ()
        throws IOException
    {
        return inStream.read();
    }

    public void readFully(byte[] b)
        throws IOException
    {
        inStream.readFully(b, 0, b.length);
    }

    public void readFully(byte[] b, int off, int len)
        throws IOException
    {
        inStream.readFully(b, off, len);
    }
}
