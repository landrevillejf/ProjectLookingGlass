/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */

package gnu.x11;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal pure-Java reader for the X authority file used by
 * {@code xauth(1)}, {@code Xorg}, {@code Xwayland}, and every other X11
 * implementation.
 *
 * <p>Added during the lg3d JDK-21 port. Escher 0.2.2 originally hard-coded
 * empty {@code auth_name}/{@code auth_data} in
 * {@link Display#Display(String, int, int, String)}, which meant it could
 * only connect to X servers started with {@code -noauth} or with
 * host-based access control. Modern desktops (systemd user sessions,
 * GNOME/KDE on Wayland with Xwayland, and the LFS-style Xorg-on-VT
 * configuration lg3d targets) all require MIT-MAGIC-COOKIE-1
 * authentication, so the connection setup would silently hang or fail
 * with "Authorization required".
 *
 * <p>This class implements only the read path (find the cookie for a given
 * display number). Writing, merging, and removing entries is left to
 * {@code xauth(1)}.
 *
 * <h3>File format</h3>
 *
 * Each record is a big-endian sequence of length-prefixed fields:
 * <pre>
 *   uint16  family           (0=Internet, 1=DECnet, 2=Internet6,
 *                             256=Local, 65535=Wild)
 *   uint16  address_length
 *   byte[]  address
 *   uint16  number_length
 *   byte[]  number           (display number as ASCII digits)
 *   uint16  name_length
 *   byte[]  name             (typically "MIT-MAGIC-COOKIE-1")
 *   uint16  data_length
 *   byte[]  data             (the 16-byte magic cookie)
 * </pre>
 *
 * <h3>Selection strategy</h3>
 *
 * For a local Unix-socket connection to display {@code :n} this class
 * returns, in priority order:
 * <ol>
 *   <li>The first entry with {@code family=Local} (256) whose number
 *       matches {@code n} and whose name is a supported auth scheme.</li>
 *   <li>The first entry with {@code family=Wild} (65535) whose number
 *       matches.</li>
 *   <li>The first entry with any family whose number matches. This is a
 *       last-resort fallback that lets us connect to servers which were
 *       configured with an Internet-family cookie even when we reach them
 *       via the Unix socket (common with {@code xhost +SI:localuser:}
 *       setups and some display managers).</li>
 * </ol>
 *
 * Only {@code MIT-MAGIC-COOKIE-1} and {@code XDM-AUTHORIZATION-1} are
 * recognized by name; anything else is skipped. Xwayland and modern Xorg
 * use MIT-MAGIC-COOKIE-1 exclusively.
 *
 * @see <a href="https://www.x.org/releases/X11R7.6/doc/libXau/">libXau</a>
 */
public final class XAuthority {

    /** Family constant: IPv4. */
    public static final int FAMILY_INTERNET = 0;
    /** Family constant: DECnet. */
    public static final int FAMILY_DECNET = 1;
    /** Family constant: IPv6. */
    public static final int FAMILY_INTERNET6 = 2;
    /** Family constant: local Unix-domain socket. */
    public static final int FAMILY_LOCAL = 256;
    /** Family constant: matches any family. */
    public static final int FAMILY_WILD = 65535;

    /** Auth scheme name we know how to send. */
    public static final String MIT_MAGIC_COOKIE_1 = "MIT-MAGIC-COOKIE-1";

    /**
     * One entry from the authority file.
     */
    public static final class Entry {
        public final int family;
        public final byte[] address;
        public final String number;
        public final String name;
        public final byte[] data;

        Entry(int family, byte[] address, String number, String name, byte[] data) {
            this.family = family;
            this.address = address;
            this.number = number;
            this.name = name;
            this.data = data;
        }

        @Override
        public String toString() {
            return "XAuthority.Entry[family=" + family
                 + ", number=\"" + number + "\""
                 + ", name=\"" + name + "\""
                 + ", data=" + data.length + " bytes]";
        }
    }

    private XAuthority() {
        // utility class
    }

    /**
     * Resolves the authority file to read, honoring {@code $XAUTHORITY}
     * and falling back to {@code ~/.Xauthority}. Returns {@code null} if
     * neither is set or the file does not exist.
     */
    public static File defaultFile() {
        String env = System.getenv("XAUTHORITY");
        if (env != null && !env.isEmpty()) {
            File f = new File(env);
            if (f.isFile() && f.canRead()) return f;
            // $XAUTHORITY was set but unusable - fall through to the
            // home-directory default rather than silently sending no
            // cookie, since the user explicitly asked for a specific
            // file.
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isEmpty()) return null;
        File f = new File(home, ".Xauthority");
        return (f.isFile() && f.canRead()) ? f : null;
    }

    /**
     * Reads every record from the given authority file. Never throws; on
     * any I/O or parse failure returns whatever entries were successfully
     * decoded before the error, so a partially corrupt file still yields
     * usable cookies.
     */
    public static List<Entry> read(File file) {
        List<Entry> entries = new ArrayList<>();
        if (file == null) return entries;

        try (InputStream in = new FileInputStream(file);
             DataInputStream din = new DataInputStream(in)) {

            while (true) {
                int family;
                try {
                    family = din.readUnsignedShort();
                } catch (EOFException e) {
                    break; // clean end of file
                }
                byte[] address = readBlob(din);
                String number = new String(readBlob(din), StandardCharsets.US_ASCII);
                String name = new String(readBlob(din), StandardCharsets.US_ASCII);
                byte[] data = readBlob(din);
                if (address == null || data == null) break;
                entries.add(new Entry(family, address, number, name, data));
            }
        } catch (IOException e) {
            // Partial results are still useful; caller decides whether to
            // proceed. Debug output would go here but Escher has no
            // logger, and stderr noise during lg3d startup is worse than
            // a silently short list.
        }
        return entries;
    }

    /**
     * Convenience: reads the default authority file and returns the best
     * cookie for the given display number, or {@code null} if none was
     * found. The returned array is {@code {name, data}} where
     * {@code name} is UTF-8/ASCII bytes ready to be sent in the
     * connection setup request and {@code data} is the raw cookie.
     */
    public static Entry findForDisplay(int display_no) {
        File f = defaultFile();
        if (f == null) return null;
        return selectBest(read(f), Integer.toString(display_no));
    }

    /**
     * Picks the highest-priority matching entry from a pre-read list.
     * Priority: Local > Wild > any. Within a priority tier, first match
     * wins. Only entries with a supported auth name are considered.
     *
     * <p>An entry whose {@code number} field is empty is treated as a
     * wildcard that matches any display number. This is the convention
     * used by mutter/Xwayland when writing
     * {@code /run/user/<uid>/.mutter-Xwaylandauth.*}: the record has
     * {@code family=Local, address=<hostname>, number=""}, which means
     * "this cookie is valid for any display on this host's Unix socket".
     */
    static Entry selectBest(List<Entry> entries, String displayNumber) {
        Entry local = null, wild = null, any = null;
        for (Entry e : entries) {
            if (!matchesNumber(e.number, displayNumber)) continue;
            if (!isSupportedName(e.name)) continue;
            if (e.family == FAMILY_LOCAL && local == null) local = e;
            else if (e.family == FAMILY_WILD && wild == null) wild = e;
            else if (any == null) any = e;
        }
        if (local != null) return local;
        if (wild != null) return wild;
        return any;
    }

    /**
     * Returns true if the authority entry's number field matches the
     * requested display number. An empty entry number is a wildcard that
     * matches everything.
     */
    private static boolean matchesNumber(String entryNumber, String displayNumber) {
        if (entryNumber == null || entryNumber.isEmpty()) return true;
        return entryNumber.equals(displayNumber);
    }

    private static boolean isSupportedName(String name) {
        // XDM-AUTHORIZATION-1 is included for completeness; lg3d does not
        // currently use it, but reading it costs nothing and future-proofs
        // the parser against unusual deployments.
        return MIT_MAGIC_COOKIE_1.equals(name)
            || "XDM-AUTHORIZATION-1".equals(name);
    }

    /**
     * Reads a uint16-prefixed blob. Returns an empty (zero-length) array
     * when the length field is 0, and {@code null} when the stream ends
     * prematurely (which the caller treats as "stop parsing").
     */
    private static byte[] readBlob(DataInputStream din) throws IOException {
        int len;
        try {
            len = din.readUnsignedShort();
        } catch (EOFException e) {
            return null;
        }
        if (len == 0) return new byte[0];
        byte[] buf = new byte[len];
        try {
            din.readFully(buf);
        } catch (EOFException e) {
            return null;
        }
        return buf;
    }

    /**
     * Formats a raw cookie as a lowercase hex string. Useful for debug
     * output; the connection setup request itself sends the raw bytes,
     * not the hex form.
     */
    public static String toHex(byte[] data) {
        if (data == null) return "<null>";
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2);
        for (byte b : data) {
            out.write(Character.forDigit((b >> 4) & 0xF, 16));
            out.write(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
