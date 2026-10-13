/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the receive-side reorder buffer, in
 * pure Java.
 *
 * The 2006 client smoothed network reordering inside JMF's RTP sources —
 * again, the behaviour is an RFC-level concern, not a javax.media one, so the
 * port owns the policy here where CI can prove it. The policy is the classic
 * one a voice client needs: deliver packets in sequence order; hold a packet
 * whose predecessors are missing; drop anything that arrives late (its slot
 * has already been played — voice is real time, a two-second-old syllable is
 * not a repair); and cap the wait so a lost packet stalls playback for at
 * most the tolerance window rather than forever, then resync past the hole.
 *
 * No clock is read and no thread waits: put() stores, poll() answers "what
 * can play right now", and the bridge calls poll() on its own pacing timer,
 * so every decision is deterministic and testable. Gap tolerance is a count
 * of packets, which for the fixed 20 ms G.711 frame size is milliseconds/20.
 *
 * Sequence numbers are 16-bit and wrap; the buffer keeps a wrap epoch so a
 * TreeMap ordered by the extended position plays 65535 before the next
 * stream's 0 instead of backwards at every rollover.
 */
package org.jdesktop.wonderland.modules.softphone;

import java.util.TreeMap;

/** Reorder / de-jitter store for one RTP stream. Single-consumer by design:
 *  the voice bridge owns it from its receive thread. */
public final class JitterBuffer {

    /** Packets held beyond this count drop the oldest: an endless backlog
     *  must not grow the heap. */
    public static final int DEFAULT_CAPACITY = 50;
    /** Missing predecessors tolerated before playback resyncs past the hole
     *  (5 x 20 ms = the classic 100 ms de-jitter window). */
    public static final int DEFAULT_MAX_GAP = 5;

    /** Half the 16-bit space: the RFC 3550 short-path boundary. */
    private static final int HALF_SPACE = 0x8000;

    private final int capacity;
    private final int maxGap;

    /** Stored packets keyed by the extended (epoch << 16 | raw) position. */
    private final TreeMap<Long, RtpPacket> store = new TreeMap<>();

    /** Next extended position the player expects. */
    private long expected;
    /** Whether the first put() has seeded the window. */
    private boolean primed;

    /** Wrap epoch of the highest position accepted. */
    private int epoch;
    /** Raw sequence of the highest position accepted, for the wrap test. */
    private int highestRaw = -1;

    /** Cumulative drops by cause; the control surfaces the totals through
     *  its problem-report path. */
    private long droppedLate;
    private long droppedOverflow;

    public JitterBuffer() {
        this(DEFAULT_CAPACITY, DEFAULT_MAX_GAP);
    }

    public JitterBuffer(int capacity, int maxGap) {
        if (capacity < 1 || maxGap < 0) {
            throw new IllegalArgumentException("bad jitter window");
        }
        this.capacity = capacity;
        this.maxGap = maxGap;
    }

    /**
     * Offer one packet.
     *
     * @return true when it is now stored; false when it was dropped as late
     *         (its slot already played), as a duplicate, or as overflow
     */
    public boolean put(RtpPacket packet) {
        long position = extend(packet.getSequenceNumber());
        if (!primed) {
            expected = position;
            primed = true;
        }
        if (position < expected) {
            droppedLate++;                   // its slot already played
            return false;
        }
        if (store.containsKey(position)) {
            return false;                    // the first copy stands
        }
        store.put(position, packet);
        while (store.size() > capacity) {
            store.remove(store.firstKey());  // oldest first, the classic policy
            droppedOverflow++;
        }
        return true;
    }

    /**
     * The next packet the player may take, advancing the window past it.
     * Returns null while the expected slot is empty and the hole is within
     * the tolerance; once the FIRST stored packet sits more than maxGap
     * positions ahead, the hole is declared unfillable and playback resyncs
     * to that packet — voice skips the lost syllable rather than stall.
     */
    public RtpPacket poll() {
        RtpPacket head = store.remove(expected);
        if (head != null) {
            expected++;
            return head;
        }
        if (store.isEmpty()) {
            return null;
        }
        // nothing at the expected slot: an empty hole (a dropped packet) or a
        // wait. A backlog that starts within tolerance may still be filled by
        // a late-arriving predecessor; one that starts past it never will.
        if (store.firstKey() - expected > maxGap) {
            expected = store.firstKey();
            return store.remove(expected++);
        }
        return null;
    }

    /**
     * Called by the paced consumer when the store is empty and nothing has
     * arrived for a full tolerance window: the silence is real, so the
     * expected position slides forward and a packet landing behind it counts
     * as late. Keeping this OUTSIDE poll() is what makes the buffer
     * deterministic — poll() never moves the window on an empty store, so
     * tests can assert the hold/deliver decision with no clock at all.
     *
     * @return the number of slots the window slid (0 when the store is not
     *         empty and a poll should happen first)
     */
    public long tickIdle() {
        if (!primed || !store.isEmpty()) {
            return 0;
        }
        expected += maxGap;
        return maxGap;
    }

    /** Resync the window to one packet's position and drop the backlog
     *  (a fresh SSRC, or the player restarting after a re-connect): the
     *  anchor's raw number restarts the wrap tracking from epoch zero, or a
     *  mid-space anchor like a re-keyed stream's seq 30000 would be read as
     *  a late survivor of an old roll. */
    public void resync(RtpPacket anchor) {
        store.clear();
        epoch = 0;
        highestRaw = -1;
        expected = extend(anchor.getSequenceNumber());
        primed = true;
    }

    /** Empty the store without moving the window (call teardown). */
    public void flush() {
        store.clear();
    }

    /** Packets currently held. */
    public int size() {
        return store.size();
    }

    /** The extended position the next poll() wants; a test seam. */
    public long expectedPosition() {
        return expected;
    }

    public long droppedLate() {
        return droppedLate;
    }

    public long droppedOverflow() {
        return droppedOverflow;
    }

    /**
     * Extend a 16-bit wire number to the buffer's monotonic position: the
     * current epoch when the number sits within half the space AHEAD of (or
     * at) the highest accepted, the previous epoch when it sits beyond half
     * the space BEHIND it — the RFC 3550 short-path rule, reading 65535 -> 1
     * as a forward wrap while an ordinary reordered 3-after-5 stays put.
     */
    private long extend(int raw) {
        if (highestRaw < 0) {
            // the very first accepted number seeds the wrap tracker; without
            // this the highest never moves and a forward wrap reads as late
            highestRaw = raw;
            return ((long) epoch << 16) | (raw & 0xffffL);
        }
        // the signed short-path delta against the highest accepted number:
        // forward within half the space keeps (or crosses into) the next roll,
        // behind it belongs to the previous roll
        int delta = ((raw - highestRaw + HALF_SPACE) & 0xffff) - HALF_SPACE;
        long base = ((long) epoch << 16) + (highestRaw & 0xffffL);
        long position = base + delta;
        if (position > base) {
            // crossed 65535 -> 0: adopt the new roll and record its head
            epoch = (int) (position >>> 16);
            highestRaw = raw;
        }
        return position;
    }
}
