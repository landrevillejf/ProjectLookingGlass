/**
 * $RCSfile: EscherOutputStream.java,v $
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

import java.io.IOException;
import java.io.OutputStream;

/**
 * Thin wrapper around an {@link OutputStream} used by {@link Connection} to
 * emit X11 request packets.
 *
 * <p>Historically this class also supported a raw file-descriptor mode backed
 * by a JNI {@code write(int fd, ...)} native method in {@code libEscher.so}.
 * That path existed only because JDK 15 and earlier had no first-class
 * support for AF_UNIX sockets, so {@link EscherSocket} had to open the
 * {@code /tmp/.X11-unix/X<n>} socket natively and hand back an {@code int}
 * fd. On JDK 16+ {@link java.net.UnixDomainSocketAddress} plus
 * {@link java.nio.channels.SocketChannel} cover the same functionality in
 * pure Java, so the fd mode is gone and this class is now a plain stream
 * forwarder with no native dependency.
 */
public class EscherOutputStream {

    private final OutputStream outStream;

    public EscherOutputStream (OutputStream outStream) {
        if (outStream == null) {
            throw new NullPointerException("outStream");
        }
        this.outStream = outStream;
    }

    public void write (byte[] b)
        throws IOException
    {
        outStream.write(b);
    }

    public void write (byte[] b, int off, int len)
        throws IOException
    {
        outStream.write(b, off, len);
    }

    /**
     * Flushes the underlying stream. Exposed so {@link Connection#flush()}
     * can guarantee that request packets hit the wire before the caller
     * blocks on a reply. The original fd-backed implementation relied on
     * the kernel to auto-flush each {@code write(2)}; with a buffered
     * {@link java.nio.channels.Channels#newOutputStream} wrapper we need
     * to be explicit.
     */
    public void flush ()
        throws IOException
    {
        outStream.flush();
    }
}
