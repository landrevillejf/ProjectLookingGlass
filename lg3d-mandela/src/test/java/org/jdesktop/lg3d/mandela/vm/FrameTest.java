/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.mandela.vm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.mandela.code.CompiledFunction;
import org.jdesktop.lg3d.mandela.values.MandelaClosure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One activation record, driven directly rather than through a script.
 *
 * <p>A {@link Frame}'s bookkeeping has a second half no script reaches on its
 * own: the two-slot {@code defer} and {@code handler} arrays only grow past
 * their first page when a body owes more than two bodies or nests more than two
 * {@code try}s, and {@link Frame#clear()} exists purely so a discarded frame
 * stops pinning the values it held. Those are exactly the paths a mutation
 * hides in &mdash; a doubling that becomes a halving, a copy that is dropped, a
 * post-increment that becomes a post-decrement, a fill that never runs &mdash;
 * and none of them changes what a shallow script prints. So this suite builds
 * frames by hand, in the frame's own package, and reads the fields back.</p>
 */
class FrameTest {

    /** A body the frame can name; its instructions are never run here. */
    private static CompiledFunction fn(String name) {
        return new CompiledFunction.Builder(name, "frame.mnd", 1).build();
    }

    /** A distinct {@code defer} body, so identity and order are observable. */
    private static MandelaClosure thunk(String name) {
        return new MandelaClosure(fn(name), new Object[0]);
    }

    /** A bare frame with the given slots and no caller. */
    private static Frame frame(Object... slots) {
        return new Frame(fn("f"), slots, new Object[0], null, null, 0, 1);
    }

    // -- defer bookkeeping ----------------------------------------------------

    @Test
    @DisplayName("hasDefers is false for a fresh frame and true once one is owed")
    void hasDefersTracksTheCount() {
        Frame frame = frame();
        // Both halves matter: a boolean that is simply `true`, or a boundary that
        // reads `>= 0`, would pass the second assert and fail this one.
        assertFalse(frame.hasDefers(), "a frame that owes nothing says so");
        frame.addDefer(thunk("d"));
        assertTrue(frame.hasDefers(), "a frame that owes a body says so");
    }

    @Test
    @DisplayName("the defer array doubles on the third body and keeps the first two")
    void addDeferGrowsWithoutLosingWhatItHeld() {
        Frame frame = frame();
        MandelaClosure first = thunk("first");
        MandelaClosure second = thunk("second");
        MandelaClosure third = thunk("third");

        frame.addDefer(first);
        // A post-increment turned post-decrement would leave the count at -1 here.
        assertEquals(1, frame.deferCount, "one body owed");
        assertSame(first, frame.defers[0]);

        frame.addDefer(second);
        assertEquals(2, frame.deferCount, "two bodies owed, still on the first page");
        assertEquals(2, frame.defers.length, "no growth until the page is full");

        // The third body is the one that grows the array: the boundary check has to
        // fire exactly here, the doubling has to make room, and the copy has to
        // carry the two bodies already recorded into the larger array.
        frame.addDefer(third);
        assertEquals(3, frame.deferCount, "three bodies owed");
        assertEquals(4, frame.defers.length, "the two-slot page doubled to four");
        assertSame(first, frame.defers[0], "the copy kept the oldest body");
        assertSame(second, frame.defers[1], "the copy kept the second body");
        assertSame(third, frame.defers[2], "and the new body landed after them");
    }

    // -- try-handler bookkeeping ----------------------------------------------

    @Test
    @DisplayName("the handler arrays double on the third try and keep every target")
    void pushHandlerGrowsWithoutLosingWhatItHeld() {
        Frame frame = frame();
        frame.pushHandler(10, 1);
        frame.pushHandler(20, 2);
        assertEquals(2, frame.handlers, "two handlers, still on the first page");

        // The third handler grows both arrays together; a halving instead of a
        // doubling, or a dropped copy, shows up as a lost target or a thrown index.
        frame.pushHandler(30, 3);
        assertEquals(3, frame.handlers, "three handlers installed");
        assertEquals(4, frame.handlerTargets.length, "the target page doubled");
        assertEquals(4, frame.handlerBases.length, "the base page doubled with it");
        assertArrayEquals(new int[] { 10, 20, 30, 0 }, frame.handlerTargets,
                "the copy kept both earlier targets");
        assertArrayEquals(new int[] { 1, 2, 3, 0 }, frame.handlerBases,
                "and both earlier bases");
    }

    @Test
    @DisplayName("peekHandlerBase names the innermost depth, or -1 with no handler")
    void peekHandlerBaseReadsTheInnermostDepth() {
        Frame frame = frame();
        // -1 and not 0: an int return silently replaced by 0 would pass a fresh
        // frame's "no handler" case only if the sentinel were 0, which it is not.
        assertEquals(-1, frame.peekHandlerBase(), "no handler restores nothing");

        frame.pushHandler(100, 7);
        assertEquals(7, frame.peekHandlerBase(), "the innermost base, before any pop");
        frame.pushHandler(200, 9);
        assertEquals(9, frame.peekHandlerBase(), "the newer handler shadows the older");
    }

    // -- clear ----------------------------------------------------------------

    @Test
    @DisplayName("clear erases every slot and every owed body, and spares the spare cell")
    void clearErasesSlotsAndDefers() {
        Frame frame = frame("x", "y");
        frame.addDefer(thunk("a"));
        frame.addDefer(thunk("b"));
        // Exactly two defers on a two-slot page: deferCount == defers.length, so a
        // loop that ran one turn too far (i <= deferCount) would index defers[2]
        // and throw, which is the only way to see that boundary from out here.
        assertEquals(2, frame.deferCount);
        assertEquals(2, frame.defers.length);

        assertDoesNotThrow(frame::clear, "clear stops at the last owed body");
        assertNull(frame.slots[0], "the first slot no longer pins its value");
        assertNull(frame.slots[1], "nor the second");
        assertNull(frame.defers[0], "the first body was released");
        assertNull(frame.defers[1], "and the second");
        assertEquals(0, frame.deferCount, "nothing is owed afterwards");
    }

    // -- the description a stack trace prints ---------------------------------

    @Test
    @DisplayName("toString names the body, or calls an unnamed one a lambda")
    void toStringNamesTheBodyOrLambda() {
        Frame named = new Frame(fn("compute"), new Object[0], new Object[0], null, null, 0, 1);
        String text = named.toString();
        // A return silently replaced by "" fails the first assert; a negated
        // isEmpty() renders "lambda" for a body that has a name, failing both.
        assertTrue(text.contains("compute"), "a named body shows its name: " + text);
        assertFalse(text.contains("lambda"), "and is not called a lambda: " + text);

        Frame anonymous = new Frame(fn(""), new Object[0], new Object[0], null, null, 0, 1);
        assertTrue(anonymous.toString().contains("lambda"),
                "an empty name reads as lambda, the word a trace needs");
    }
}
