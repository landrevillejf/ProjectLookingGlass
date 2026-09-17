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
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import gnu.x11.Data;
import gnu.x11.Display;
import gnu.x11.Drawable;
import gnu.x11.GC;
import gnu.x11.Pixmap;
import gnu.x11.Request;
import gnu.x11.XProtocolInfo;
import gnu.x11.extension.Extension;
import gnu.x11.extension.NotFoundException;

/**
 * Pure-Java Escher binding for the MIT Shared Memory extension (MIT-SHM).
 *
 * <p>MIT-SHM allows a client to share a memory segment with the X server so
 * that pixel data can be transferred without copying over the socket. For a
 * compositor reading back redirected window contents at interactive frame
 * rates, this is the difference between ~5 ms (shm) and ~50 ms (socket
 * round-trip) per full-screen readback.
 *
 * <p>Protocol reference: <em>MIT-SHM Extension, Version 1.1</em>.
 *
 * <h3>Shared memory segment lifecycle</h3>
 *
 * <p>The X protocol's {@code ShmAttach} request takes a SysV shared memory
 * id (the integer returned by {@code shmget(2)}). Obtaining that id requires
 * a POSIX syscall that pure Java on JDK 21 cannot make without the Foreign
 * Function &amp; Memory API (preview in 21, final in 22). This class
 * therefore provides the <em>protocol bindings only</em>; the caller must
 * supply a valid {@code shmid} obtained by one of:
 *
 * <ul>
 *   <li>A tiny native helper invoked via {@link ProcessBuilder} (e.g. a
 *       compiled C one-liner or {@code python3 -c "import ctypes; ..."}).</li>
 *   <li>JDK 22+ {@code java.lang.foreign.Linker} (no preview flag needed).</li>
 *   <li>The lg3d LFS build can ship a {@code /usr/lib/lg3d/shm-helper}
 *       binary that prints the shmid to stdout.</li>
 * </ul>
 *
 * <p>If no shm segment is available, Stage 3's
 * {@code CompositeWindowImageLoader} falls back to the core
 * {@link Drawable#image} (XGetImage) request, which is slower but requires
 * no shared memory.
 *
 * @see X11CompositeExt
 * @see X11DamageExt
 */
public class X11ShmExt extends Extension {

    /** Names of every minor opcode defined by MIT-SHM 1.1, in order. */
    static final String[] MINOR_OPCODE_STRINGS = {
        "QueryVersion",   // 0
        "Attach",         // 1
        "Detach",         // 2
        "PutImage",       // 3
        "GetImage",       // 4
        "CreatePixmap",   // 5
        "FreePixmap"      // 6
    };

    /** Client-side SHM version we request. */
    public static final int CLIENT_MAJOR_VERSION = 1;
    public static final int CLIENT_MINOR_VERSION = 1;

    /** Image format constants (same as core X11). */
    public static final int FORMAT_BITMAP = 0;
    public static final int FORMAT_PIXMAP = 1;
    public static final int FORMAT_ZPIXMAP = 2;

    /** Server-side version, populated after successful QueryVersion. */
    public int server_major_version;
    public int server_minor_version;

    /** Whether the server supports shared pixmaps (SHM 1.1+). */
    public boolean shared_pixmaps_supported;

    /**
     * Negotiates the MIT-SHM extension against the given display and issues
     * QueryVersion.
     *
     * @throws NotFoundException if the X server does not advertise MIT-SHM
     */
    public X11ShmExt(Display display) throws NotFoundException {
        super(display, "MIT-SHM", MINOR_OPCODE_STRINGS);

        // QueryVersion and GetImage expect replies.
        XProtocolInfo.extensionRequestExpectsReply(major_opcode, 0, 32);
        XProtocolInfo.extensionRequestExpectsReply(major_opcode, 4, 32);

        // --- QueryVersion (minor opcode 0) ---
        Request request = new Request(display, major_opcode, 0, 1);

        Data reply = display.read_reply(request);
        server_major_version = reply.read2(8);
        server_minor_version = reply.read2(10);
        shared_pixmaps_supported = reply.read_boolean(12);
    }

    // ------------------------------------------------------------------
    // Opcode 1: Attach
    // ------------------------------------------------------------------

    /**
     * Attaches a SysV shared memory segment to the X connection. After this
     * call, the server maps the segment identified by {@code shmid} into its
     * address space and the client can use {@code shmseg} in PutImage,
     * GetImage, and CreatePixmap requests.
     *
     * @param shmseg    a pre-allocated X resource id for the segment
     *                  ({@code display.allocate_id()})
     * @param shmid     the SysV shared memory id from {@code shmget(2)}
     * @param read_only true if the server will only read from the segment
     */
    public void attach(int shmseg, int shmid, boolean read_only) {
        Request request = new Request(display, major_opcode, 1, 4);
        request.write4(shmseg);
        request.write4(shmid);
        request.write1(read_only);
        request.write3_unused();
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 2: Detach
    // ------------------------------------------------------------------

    /**
     * Detaches a previously attached shared memory segment. The server
     * unmaps its copy; the client should then {@code shmdt(2)} and
     * {@code shmctl(IPC_RMID)} on its side.
     */
    public void detach(int shmseg) {
        Request request = new Request(display, major_opcode, 2, 2);
        request.write4(shmseg);
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 3: PutImage
    // ------------------------------------------------------------------

    /**
     * Copies pixel data from the shared memory segment into a drawable.
     * This is the fast path for uploading lg3d-rendered content back to an
     * X window (e.g. for the overlay or for unredirected windows).
     *
     * @param drawable   destination
     * @param gc         graphics context
     * @param shmseg     attached shared memory segment
     * @param total_width  width of the image in the shm segment
     * @param total_height height of the image in the shm segment
     * @param src_x      source rectangle x within the shm image
     * @param src_y      source rectangle y
     * @param src_width  source rectangle width
     * @param src_height source rectangle height
     * @param dst_x      destination x on the drawable
     * @param dst_y      destination y on the drawable
     * @param depth      depth of the image
     * @param format     {@link #FORMAT_ZPIXMAP} or {@link #FORMAT_PIXMAP}
     * @param send_event if true, the server sends a ShmCompletion event
     * @param offset     byte offset within the shm segment
     */
    public void putImage(Drawable drawable, GC gc, int shmseg,
                         int total_width, int total_height,
                         int src_x, int src_y, int src_width, int src_height,
                         int dst_x, int dst_y,
                         int depth, int format, boolean send_event,
                         int offset) {
        Request request = new Request(display, major_opcode, 3, 12);
        request.write4(drawable.id);
        request.write4(gc.id);
        request.write2(total_width);
        request.write2(total_height);
        request.write2(src_x);
        request.write2(src_y);
        request.write2(src_width);
        request.write2(src_height);
        request.write2(dst_x);
        request.write2(dst_y);
        request.write1(depth);
        request.write1(format);
        request.write1(send_event);
        request.write1_unused();
        request.write4(shmseg);
        request.write4(offset);
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 4: GetImage
    // ------------------------------------------------------------------

    /** Reply of {@link #getImage}. */
    public static class GetImageReply extends Data {
        public GetImageReply(Data data) { super(data); }

        /** Depth of the source drawable. */
        public int depth() { return read1(1); }

        /** Visual of the source drawable (0 if none). */
        public int visual_id() { return read4(8); }

        /** Size in bytes of the image data following the 32-byte header. */
        public int size() { return read4(12); }

        /**
         * Raw pixel data. The reply's {@link #data} array contains the
         * 32-byte header followed by {@code size()} bytes of pixel data
         * in the requested format.
         */
        public byte[] pixels() {
            int sz = size();
            byte[] px = new byte[sz];
            System.arraycopy(data, 32, px, 0, Math.min(sz, data.length - 32));
            return px;
        }
    }

    /**
     * Reads pixel data from a drawable into the shared memory segment.
     * This is the fast path for the compositor: instead of sending pixels
     * over the socket (core GetImage), the server writes directly into the
     * shm segment that the client has already mapped.
     *
     * @param drawable  source drawable (typically a NameWindowPixmap)
     * @param shmseg    attached shared memory segment
     * @param x         source x
     * @param y         source y
     * @param width     source width
     * @param height    source height
     * @param plane_mask plane mask (AllPlanes = -1)
     * @param format    {@link #FORMAT_ZPIXMAP}
     * @param offset    byte offset within the shm segment to write into
     * @return reply containing depth, visual, and size metadata
     */
    public GetImageReply getImage(Drawable drawable, int shmseg,
                                  int x, int y, int width, int height,
                                  int plane_mask, int format, int offset) {
        Request request = new Request(display, major_opcode, 4, 8);
        request.write4(drawable.id);
        request.write4(shmseg);
        request.write2(x);
        request.write2(y);
        request.write2(width);
        request.write2(height);
        request.write4(plane_mask);
        request.write1(format);
        request.write3_unused();
        request.write4(offset);
        return new GetImageReply(display.read_reply(request));
    }

    // ------------------------------------------------------------------
    // Opcode 5: CreatePixmap
    // ------------------------------------------------------------------

    /**
     * Creates a pixmap backed by the shared memory segment. Drawing into
     * this pixmap writes directly to the shm memory, which the client can
     * then read without any X protocol round-trip. Requires SHM 1.1+ and
     * {@link #shared_pixmaps_supported}.
     *
     * @param pixmap   pre-allocated pixmap id
     * @param drawable a drawable of the same depth (used for the screen)
     * @param width    pixmap width
     * @param height   pixmap height
     * @param depth    pixmap depth
     * @param shmseg   attached shared memory segment
     * @param offset   byte offset within the segment
     */
    public void createPixmap(Pixmap pixmap, Drawable drawable,
                             int width, int height, int depth,
                             int shmseg, int offset) {
        Request request = new Request(display, major_opcode, 5, 6);
        request.write4(pixmap.id);
        request.write4(drawable.id);
        request.write2(width);
        request.write2(height);
        request.write2(depth);
        request.write2_unused();
        request.write4(shmseg);
        request.write4(offset);
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 6: FreePixmap
    // ------------------------------------------------------------------

    /**
     * Frees a pixmap created with {@link #createPixmap}. The underlying shm
     * segment is not detached; call {@link #detach} separately.
     */
    public void freePixmap(Pixmap pixmap) {
        Request request = new Request(display, major_opcode, 6, 2);
        request.write4(pixmap.id);
        display.send_request(request);
    }

    // ------------------------------------------------------------------

    @Override
    public String more_string() {
        return "\n  client-version: " + CLIENT_MAJOR_VERSION + "." + CLIENT_MINOR_VERSION
             + "\n  server-version: " + server_major_version + "." + server_minor_version
             + "\n  shared-pixmaps: " + shared_pixmaps_supported;
    }
}
