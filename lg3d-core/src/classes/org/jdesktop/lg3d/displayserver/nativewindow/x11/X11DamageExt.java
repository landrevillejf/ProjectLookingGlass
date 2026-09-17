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
import gnu.x11.Rectangle;
import gnu.x11.Request;
import gnu.x11.Window;
import gnu.x11.XProtocolInfo;
import gnu.x11.event.Event;
import gnu.x11.extension.EventFactory;
import gnu.x11.extension.Extension;
import gnu.x11.extension.NotFoundException;

/**
 * Pure-Java Escher binding for the X Damage extension (version 1.1).
 *
 * <p>Damage reports which regions of a drawable have changed since the last
 * time the client acknowledged them. Combined with Composite (which redirects
 * a window's rendering into an offscreen pixmap), Damage is how a compositor
 * knows when to re-read a window's contents. lg3d uses it to trigger texture
 * re-uploads for {@code NativeWindow3D} quads only when the underlying X11
 * app actually repaints, instead of polling every frame.
 *
 * <p>Protocol reference: <em>Damage Extension Protocol, Version 1.1</em>.
 *
 * <h3>Usage in lg3d</h3>
 * <ol>
 *   <li>{@link #create} a Damage object on the window's NameWindowPixmap
 *       with {@link #REPORT_LEVEL_NON_EMPTY}.</li>
 *   <li>Listen for {@link NotifyEvent} on the X event loop.</li>
 *   <li>On each event, read the damaged region from the pixmap (via XShm or
 *       GetImage), then call {@link #subtract} with repair=None to
 *       acknowledge and reset the damage.</li>
 *   <li>{@link #destroy} when the window is unmapped or the compositor
 *       shuts down.</li>
 * </ol>
 *
 * @see X11CompositeExt
 * @see X11ShmExt
 */
public class X11DamageExt extends Extension implements EventFactory {

    /** Names of every minor opcode defined by Damage 1.1, in order. */
    static final String[] MINOR_OPCODE_STRINGS = {
        "QueryVersion",   // 0
        "Create",         // 1
        "Destroy",        // 2
        "Subtract",       // 3
        "Add"             // 4
    };

    /** Client-side Damage version we request. 1.1 is universally supported. */
    public static final int CLIENT_MAJOR_VERSION = 1;
    public static final int CLIENT_MINOR_VERSION = 1;

    // --- Report levels ---

    /**
     * Reports every individual rectangle as it is damaged. Highest
     * granularity, highest event volume.
     */
    public static final int REPORT_LEVEL_RAW_RECTANGLES = 0;

    /**
     * Reports the difference between the previous bounding box and the new
     * one. Fewer events than RAW_RECTANGLES but still precise.
     */
    public static final int REPORT_LEVEL_DELTA_RECTANGLES = 1;

    /**
     * Reports the bounding box of all damaged areas since the last
     * Subtract. One event per damage accumulation cycle.
     */
    public static final int REPORT_LEVEL_BOUNDING_BOX = 2;

    /**
     * Reports a single event whenever the damage becomes non-empty (i.e.
     * transitions from clean to dirty). The area field covers the entire
     * drawable. This is the level lg3d uses: one notification per repaint
     * burst, then the compositor reads the full window (or the damaged
     * region from the geometry fields).
     */
    public static final int REPORT_LEVEL_NON_EMPTY = 3;

    /** Server-side version, populated after successful QueryVersion. */
    public int server_major_version;
    public int server_minor_version;

    /**
     * Negotiates the Damage extension against the given display and issues
     * QueryVersion. Registers one event slot (DamageNotify at index 0).
     *
     * @throws NotFoundException if the X server does not advertise DAMAGE
     */
    public X11DamageExt(Display display) throws NotFoundException {
        super(display, "DAMAGE", MINOR_OPCODE_STRINGS, 0, 1);

        // QueryVersion expects a 32-byte reply.
        XProtocolInfo.extensionRequestExpectsReply(major_opcode, 0, 32);

        Request request = new Request(display, major_opcode, 0, 3);
        request.write4(CLIENT_MAJOR_VERSION);
        request.write4(CLIENT_MINOR_VERSION);

        Data reply = display.read_reply(request);
        server_major_version = reply.read4(8);
        server_minor_version = reply.read4(12);
    }

    // ------------------------------------------------------------------
    // Opcode 1: Create
    // ------------------------------------------------------------------

    /**
     * Creates a Damage object that monitors the given drawable.
     *
     * @param damage   a pre-allocated damage id ({@code display.allocate_id()})
     * @param drawable the window or pixmap to monitor
     * @param level    one of the {@code REPORT_LEVEL_*} constants
     */
    public void create(int damage, Drawable drawable, int level) {
        Request request = new Request(display, major_opcode, 1, 4);
        request.write4(damage);
        request.write4(drawable.id);
        request.write1(level);
        request.write3_unused();
        display.send_request(request);
    }

    /**
     * Convenience: creates a Damage object on a Window.
     */
    public void create(int damage, Window window, int level) {
        create(damage, (Drawable) window, level);
    }

    // ------------------------------------------------------------------
    // Opcode 2: Destroy
    // ------------------------------------------------------------------

    /**
     * Destroys a Damage object. No further events will be delivered for it.
     */
    public void destroy(int damage) {
        Request request = new Request(display, major_opcode, 2, 2);
        request.write4(damage);
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 3: Subtract
    // ------------------------------------------------------------------

    /**
     * Acknowledges (subtracts) damage. Passing {@code repair=0} (None) and
     * {@code parts=0} (None) subtracts ALL accumulated damage, resetting the
     * Damage object to the empty state. This is the standard "ack" call a
     * compositor makes after reading the damaged pixels.
     *
     * @param damage the damage object to acknowledge
     * @param repair an XFixes region to subtract, or 0 for None (subtract all)
     * @param parts  an XFixes region that receives the subtracted area, or 0
     */
    public void subtract(int damage, int repair, int parts) {
        Request request = new Request(display, major_opcode, 3, 4);
        request.write4(damage);
        request.write4(repair);
        request.write4(parts);
        display.send_request(request);
    }

    /**
     * Convenience: acknowledge all damage (repair=None, parts=None).
     */
    public void subtract(int damage) {
        subtract(damage, 0, 0);
    }

    // ------------------------------------------------------------------
    // Opcode 4: Add
    // ------------------------------------------------------------------

    /**
     * Manually adds damage to a drawable. This is used by clients that draw
     * directly to a redirected window's pixmap and need to inform the
     * compositor. lg3d does not normally call this.
     *
     * @param drawable the drawable to mark as damaged
     * @param region   an XFixes region describing the damaged area
     */
    public void add(Drawable drawable, int region) {
        Request request = new Request(display, major_opcode, 4, 3);
        request.write4(drawable.id);
        request.write4(region);
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // DamageNotify event
    // ------------------------------------------------------------------

    /**
     * The DamageNotify event, delivered when a monitored drawable accumulates
     * damage at or above the requested report level.
     *
     * <p>Wire format (32 bytes, offsets from start of event):
     * <pre>
     *   0     CARD8   type (extension event base + 0)
     *   1     CARD8   level
     *   2-3   CARD16  sequenceNumber
     *   4-7   DRAWABLE drawable
     *   8-11  DAMAGE   damage
     *  12-15  TIMESTAMP timestamp
     *  16-17  INT16   area.x
     *  18-19  INT16   area.y
     *  20-21  CARD16  area.width
     *  22-23  CARD16  area.height
     *  24-25  INT16   geometry.x
     *  26-27  INT16   geometry.y
     *  28-29  CARD16  geometry.width
     *  30-31  CARD16  geometry.height
     * </pre>
     */
    public static class NotifyEvent extends Event {
        /** Event code within the Damage extension (always 0). */
        public static final int code = 0;

        public NotifyEvent(Display display, byte[] data) {
            super(display, data, 4);
        }

        /** The report level that generated this event. */
        public int level() { return read1(1); }

        /** The drawable being monitored. */
        public int drawable_id() { return read4(4); }

        /** The Damage object that fired. */
        public int damage_id() { return read4(8); }

        /** Server timestamp when the damage was recorded. */
        public int timestamp() { return read4(12); }

        /** X coordinate of the damaged area (signed). */
        public int area_x() { return (short) read2(16); }

        /** Y coordinate of the damaged area (signed). */
        public int area_y() { return (short) read2(18); }

        /** Width of the damaged area. */
        public int area_width() { return read2(20); }

        /** Height of the damaged area. */
        public int area_height() { return read2(22); }

        /** X coordinate of the drawable's full geometry (signed). */
        public int geometry_x() { return (short) read2(24); }

        /** Y coordinate of the drawable's full geometry (signed). */
        public int geometry_y() { return (short) read2(26); }

        /** Width of the drawable's full geometry. */
        public int geometry_width() { return read2(28); }

        /** Height of the drawable's full geometry. */
        public int geometry_height() { return read2(30); }

        /** The damaged area as a Rectangle. */
        public Rectangle area() {
            return new Rectangle(area_x(), area_y(), area_width(), area_height());
        }

        /** The full drawable geometry as a Rectangle. */
        public Rectangle geometry() {
            return new Rectangle(geometry_x(), geometry_y(),
                                 geometry_width(), geometry_height());
        }

        @Override
        public String toString() {
            return "#DamageNotify"
                 + "\n  level: " + level()
                 + "\n  drawable: 0x" + Integer.toHexString(drawable_id())
                 + "\n  damage: 0x" + Integer.toHexString(damage_id())
                 + "\n  timestamp: " + timestamp()
                 + "\n  area: " + area()
                 + "\n  geometry: " + geometry();
        }
    }

    /**
     * EventFactory implementation. Damage has exactly one event type
     * (DamageNotify at index 0), so we always return a {@link NotifyEvent}.
     */
    @Override
    public Event build(Display display, byte[] data, int code) {
        return new NotifyEvent(display, data);
    }

    @Override
    public String more_string() {
        return "\n  client-version: " + CLIENT_MAJOR_VERSION + "." + CLIENT_MINOR_VERSION
             + "\n  server-version: " + server_major_version + "." + server_minor_version;
    }
}
