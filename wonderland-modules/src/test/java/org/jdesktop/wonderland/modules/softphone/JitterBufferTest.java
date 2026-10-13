/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d: the reorder policy — hold, deliver, drop late,
 * resync past an unfillable hole, and stay monotonic across a 16-bit wrap.
 * No clock and no thread: every assertion is a put/poll sequence.
 */
package org.jdesktop.wonderland.modules.softphone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JitterBufferTest {

    private static RtpPacket pkt(int seq) {
        return new RtpPacket(false, false, 0, 0, seq, 160L * seq,
                1L, new byte[]{(byte) seq});
    }

    @Test
    @DisplayName("in-order arrival plays immediately")
    void inOrder() {
        JitterBuffer buffer = new JitterBuffer();
        assertThat(buffer.put(pkt(1))).isTrue();
        assertThat(buffer.poll().getSequenceNumber()).isEqualTo(1);
        assertThat(buffer.poll()).isNull();
    }

    @Test
    @DisplayName("a swapped pair waits for its predecessor, then plays in order")
    void reorderHolds() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.put(pkt(1));                        // seeds the window at 1
        assertThat(buffer.poll().getSequenceNumber()).isEqualTo(1);
        buffer.put(pkt(3));                        // 2 is missing: hold
        assertThat(buffer.poll()).isNull();
        buffer.put(pkt(2));                        // the late one lands
        assertThat(buffer.poll().getSequenceNumber()).isEqualTo(2);
        assertThat(buffer.poll().getSequenceNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("a packet whose slot already played is dropped late")
    void lateDrop() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.put(pkt(1));
        buffer.poll();                             // slot 1 consumed
        assertThat(buffer.put(pkt(1))).isFalse();  // retransmission is too late
        assertThat(buffer.droppedLate()).isEqualTo(1);
        assertThat(buffer.droppedOverflow()).isZero();
    }

    @Test
    @DisplayName("a duplicate while still stored plays the first copy once")
    void duplicateRejected() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.put(pkt(5));                        // seeds at 5, pollable
        assertThat(buffer.put(pkt(5))).isFalse();
        assertThat(buffer.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a hole past the tolerance is resynced over, not waited on")
    void resyncsPastBigHole() {
        JitterBuffer buffer = new JitterBuffer(50, 2);
        buffer.put(pkt(1));
        buffer.poll();                             // window expects 2
        buffer.put(pkt(10));                       // hole of 8 > tolerance 2
        RtpPacket played = buffer.poll();
        assertThat(played).isNotNull();
        assertThat(played.getSequenceNumber()).isEqualTo(10);
        assertThat(buffer.size()).isZero();
    }

    @Test
    @DisplayName("overflow drops the oldest held packet")
    void overflowDropsOldest() {
        JitterBuffer buffer = new JitterBuffer(3, 10);
        buffer.put(pkt(1));                        // seeds at 1; 1 is pollable
        buffer.poll();                             // but not yet dropped
        buffer.put(pkt(2));
        buffer.put(pkt(3));
        buffer.put(pkt(4));
        buffer.put(pkt(5));                        // capacity 3: 2 was evicted
        assertThat(buffer.droppedOverflow()).isEqualTo(1);
        assertThat(buffer.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("65535 plays BEFORE the wrapped 0, never after")
    void monotonicAcrossWrap() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.put(pkt(65534));
        buffer.poll();                             // window at 65535, epoch 0
        buffer.put(pkt(0));                        // forward wrap: epoch 1
        buffer.put(pkt(65535));                    // reordered predecessor
        assertThat(buffer.poll().getSequenceNumber()).isEqualTo(65535);
        assertThat(buffer.poll().getSequenceNumber()).isZero();
        assertThat(buffer.poll()).isNull();
    }

    @Test
    @DisplayName("a wrapped late survivor of the previous roll still slots in")
    void lateSurvivorAcrossWrap() {
        JitterBuffer buffer = new JitterBuffer(50, 100);   // generous window
        buffer.put(pkt(65530));
        buffer.poll();                             // window at 65531
        buffer.put(pkt(1));                        // rolls the epoch
        buffer.put(pkt(65531));                    // the late predecessor
        assertThat(buffer.poll().getSequenceNumber()).isEqualTo(65531);
        // the wrapped survivor (seq 1, position 65537) stays held: the hole
        // ahead is only 5 slots, inside the generous tolerance, so playback
        // waits rather than resyncing past it
        assertThat(buffer.poll()).isNull();
    }

    @Test
    @DisplayName("tickIdle slides an empty primed window, never a busy one")
    void idleSlide() {
        JitterBuffer buffer = new JitterBuffer(50, 5);
        assertThat(buffer.tickIdle()).isZero();    // unprimed: nothing to move
        buffer.put(pkt(10));
        assertThat(buffer.tickIdle()).isZero();    // store busy: poll first
        buffer.poll();                             // window now expects 11
        assertThat(buffer.tickIdle()).isEqualTo(5);
        assertThat(buffer.expectedPosition()).isEqualTo(16);   // 11 + 5
        assertThat(buffer.put(pkt(11))).isFalse(); // the old slot now reads late
    }

    @Test
    @DisplayName("resync anchors the window and drops the backlog")
    void resync() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.put(pkt(1));
        buffer.put(pkt(3));
        buffer.resync(pkt(50));
        assertThat(buffer.size()).isZero();
        assertThat(buffer.put(pkt(50))).isTrue();
        assertThat(buffer.poll().getSequenceNumber()).isEqualTo(50);
    }

    @Test
    @DisplayName("a flush empties without moving the window")
    void flushKeepsWindow() {
        JitterBuffer buffer = new JitterBuffer();
        buffer.put(pkt(7));
        buffer.flush();
        assertThat(buffer.size()).isZero();
        assertThat(buffer.poll()).isNull();
        assertThat(buffer.put(pkt(7))).isTrue();   // slot 7 never played, so
    }

    @Test
    @DisplayName("a nonsense window is refused at construction")
    void badWindow() {
        assertThatThrownBy(() -> new JitterBuffer(0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitterBuffer(5, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
