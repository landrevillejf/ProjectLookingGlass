/**
 * $RCSfile: EscherSocket.java,v $
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * A socket to an X display.
 *
 * <p>For a local display (empty host, {@code localhost}, {@code 127.0.0.1},
 * or the running machine's own hostname) this class opens an AF_UNIX
 * {@link SocketChannel} against {@code /tmp/.X11-unix/X<n>}. For a remote
 * display it opens a TCP {@link Socket} against port {@code 6000+n}.
 *
 * <p><b>Why the rewrite</b>: the original implementation had a JNI static
 * initializer {@code System.loadLibrary("Escher")} and two native methods
 * ({@code socketCreateAndConnect(int)}, {@code socketClose(int)}) because
 * JDK 15 and earlier had no first-class AF_UNIX socket support, so the
 * local-display path had to be implemented in C. JDK 16 added
 * {@link UnixDomainSocketAddress} plus {@link StandardProtocolFamily#UNIX}
 * support in {@link SocketChannel}, which covers the same functionality
 * in pure Java. As of the lg3d JDK-21 port this class no longer loads any
 * native library, no longer holds a raw file descriptor, and no longer
 * requires {@code libEscher.so} on {@code java.library.path}.
 *
 * <p>The {@link EscherOutputStream} and {@link EscherDataInputStream}
 * adapters returned from this class are likewise pure-Java stream
 * wrappers now that the fd-based constructors have been removed.
 *
 * <p><b>Abstract socket namespace</b>: Xorg on Linux listens on the
 * filesystem socket {@code /tmp/.X11-unix/X<n>} by default. Some hardened
 * configurations also (or instead) expose an abstract-namespace socket
 * {@code @/tmp/.X11-unix/X<n>}; {@link UnixDomainSocketAddress} in JDK 16+
 * does not support the abstract namespace, so if a target system uses only
 * abstract sockets the caller must set {@link #FORCE_IP} (via
 * {@code -Descher.forceIP=true}) and ensure Xorg is not started with
 * {@code -nolisten tcp}.
 */
public class EscherSocket {

    // For debug or for environments where the filesystem Unix socket is
    // unavailable (e.g. abstract-namespace-only Xorg). Set via the system
    // property "escher.forceIP=true" to always use TCP/IP even for what
    // looks like a local host.
    private static final boolean FORCE_IP =
        Boolean.getBoolean("escher.forceIP");

    /** Standard filesystem path of the X11 Unix-domain socket directory. */
    private static final String UNIX_SOCKET_DIR = "/tmp/.X11-unix";

    private Socket remoteSocket;
    private SocketChannel localChannel;

    public EscherSocket (String host, int displayNum, int remotePort)
       throws UnknownHostException, IOException
    {
        if (!FORCE_IP && isLocalHost(host)) {
            Path socketPath = Path.of(UNIX_SOCKET_DIR, "X" + displayNum);
            UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
            localChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                localChannel.connect(addr);
            } catch (IOException e) {
                try { localChannel.close(); } catch (IOException ignored) {}
                localChannel = null;
                throw e;
            }
        } else {
            remoteSocket = new Socket();
            try {
                remoteSocket.connect(new InetSocketAddress(host, remotePort));
            } catch (IOException e) {
                try { remoteSocket.close(); } catch (IOException ignored) {}
                remoteSocket = null;
                throw e;
            }
        }
    }

    public EscherOutputStream getOutputStream ()
        throws IOException
    {
        OutputStream raw = (localChannel != null)
            ? Channels.newOutputStream(localChannel)
            : remoteSocket.getOutputStream();
        return new EscherOutputStream(raw);
    }

    public EscherDataInputStream getDataInputStream ()
        throws IOException
    {
        InputStream raw = (localChannel != null)
            ? Channels.newInputStream(localChannel)
            : remoteSocket.getInputStream();
        return new EscherDataInputStream(new DataInputStream(raw));
    }

    public void close ()
        throws IOException
    {
        if (localChannel != null) {
            localChannel.close();
        }
        if (remoteSocket != null) {
            remoteSocket.close();
        }
    }

    private boolean isLocalHost (String host)
        throws UnknownHostException
    {
        if (host == null) return true;

        String hostName;
        int colonIndex = host.indexOf(":");
        if (colonIndex == -1) {
            // Entire string is host name
            hostName = host;
        } else {
            // Historical bug preserved: original code used colonIndex-1
            // which chops the last character of the host. Callers in
            // Connection.java already strip the ":" themselves, so this
            // branch is not exercised by the standard path.
            hostName = host.substring(0, Math.max(0, colonIndex - 1));
        }

        if (hostName.length() == 0) return true;

        if (hostName.equals("127.0.0.1") ||
            hostName.equals("localhost") ||
            hostName.equals("::1") ||
            hostName.equals("unix")) {
            return true;
        }

        // Get hostname of this host
        InetAddress ia = InetAddress.getLocalHost();
        String iaHostName = ia.getHostName();
        if (hostName.equals(iaHostName)) {
            return true;
        }

        return false;
    }
}
