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
package org.jdesktop.lg3d.mandela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.jdesktop.lg3d.mandela.code.CompiledFunction;
import org.jdesktop.lg3d.mandela.code.Op;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.values.MandelaClosure;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaIterator;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.Ref;
import org.jdesktop.lg3d.mandela.vm.VM;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The machine addressed in its own instruction set.
 *
 * <p>Everything else in this module asks the language for an answer and lets the
 * compiler choose the instructions. This suite writes the instructions, because the
 * machine's contract with the compiler has a second half that no script can reach:
 * the operand stack that has to grow, the cell a captured name lives in, the cursor
 * an iteration opcode promises to find, the label array a named call promises, and
 * the underflow that means the compiler emitted a stream the machine cannot run.</p>
 *
 * <p>Those paths are not decoration. Each one is the difference between a host
 * reading <em>"the Mandela operand stack underflowed; the compiler emitted an
 * instruction stream the machine cannot execute"</em> and an
 * {@code ArrayIndexOutOfBoundsException} with no author, no file and no fix. A test
 * here therefore asserts the sentence, not just the exception type &mdash; and the
 * healthy half of each opcode is asserted too, so a retirement cannot quietly take
 * the working branch with it.</p>
 */
class MachineCodeTest {

    /** A sandbox with no console and no modules: only the machine is under test. */
    private static final Capabilities CAPS = Capabilities.builder()
            .label("machine-code")
            .maxSteps(200_000L)
            .maxDepth(32)
            .build();

    private static VM machine() {
        return new VM(CAPS);
    }

    /** A top-level body: no parameters, so {@code invoke} and {@code run} both fit it. */
    private static CompiledFunction.Builder body() {
        CompiledFunction.Builder builder = new CompiledFunction.Builder("body", "code.mnd", 1);
        builder.params(0, 0);
        builder.frame(0, 0);
        return builder;
    }

    /** Runs one hand-assembled body and hands back whatever it returned. */
    private static Object run(CompiledFunction.Builder builder) {
        return machine().run(builder.build());
    }

    // -- the operand stack -----------------------------------------------------

    @Test
    @DisplayName("a body that pops what was never pushed blames the instruction stream")
    void underflowNamesTheCompilerAsTheFaultySide() {
        CompiledFunction.Builder builder = body();
        builder.emit(Op.POP, 1);
        builder.emit(Op.RETURN, 1);
        IllegalStateException raised = assertThrows(IllegalStateException.class,
                () -> run(builder));
        assertEquals("the Mandela operand stack underflowed; the compiler emitted an"
                + " instruction stream the machine cannot execute", raised.getMessage());
        // The same is true of a return with nothing to return: the value is popped
        // before the frame leaves, so an empty stack is caught here rather than
        // handing null back as if it were the script's answer.
        CompiledFunction.Builder bareReturn = body();
        bareReturn.emit(Op.RETURN, 1);
        assertThrows(IllegalStateException.class, () -> run(bareReturn));
    }

    @Test
    @DisplayName("the operand stack grows as far as a deep expression needs")
    void theOperandStackDoublesUntilTheExpressionFits() {
        // The machine starts at sixty-four slots, so two hundred pushes have to
        // double it three times and still land the arithmetic.
        CompiledFunction.Builder builder = body();
        for (int i = 0; i < 200; i++) {
            builder.emit(Op.LOAD_CONST, builder.constant(1L), 1);
        }
        for (int i = 0; i < 199; i++) {
            builder.emit(Op.ADD, 1);
        }
        builder.emit(Op.RETURN, 1);
        assertEquals(200L, run(builder));
    }

    @Test
    @DisplayName("a captured name is a cell, and losing it is the machine's fault")
    void aLostCellIsReportedAsAMachineBug() {
        CompiledFunction.Builder read = body();
        read.emit(Op.GET_UPVALUE, 0, 1);
        read.emit(Op.RETURN, 1);

        // The healthy half: an upvalue is a cell the declaring frame shares, so a
        // host that hands one over reads and writes the same storage.
        Ref shared = new Ref(5L);
        MandelaClosure healthy = new MandelaClosure(read.build(), new Object[] { shared });
        assertEquals(5L, machine().invoke(healthy, null, new Object[0]));

        CompiledFunction.Builder write = body();
        write.emit(Op.LOAD_CONST, write.constant(9L), 1);
        write.emit(Op.SET_UPVALUE, 0, 1);
        write.emit(Op.GET_UPVALUE, 0, 1);
        write.emit(Op.RETURN, 1);
        MandelaClosure writer = new MandelaClosure(write.build(), new Object[] { shared });
        assertEquals(9L, machine().invoke(writer, null, new Object[0]));
        assertEquals(9L, shared.get(), "and the write landed in the cell the reader holds");

        // The broken half: a bare value where a cell was promised. Nothing in the
        // language can produce it, so the message names the two sides that disagree.
        MandelaClosure broken = new MandelaClosure(read.build(), new Object[] { 5L });
        IllegalStateException raised = assertThrows(IllegalStateException.class,
                () -> machine().invoke(broken, null, new Object[0]));
        assertEquals("a captured variable lost its cell; slot boxing and the machine"
                + " disagree", raised.getMessage());
    }

    // -- the shapes an opcode promises on the stack ---------------------------

    @Test
    @DisplayName("iteration asks for a cursor and says what it found instead")
    void iterationBlamesTheMachineWhenNoCursorIsThere() {
        for (Op step : new Op[] { Op.ITER_NEXT, Op.ITER_VALUE, Op.ITER_KV }) {
            CompiledFunction.Builder builder = body();
            builder.emit(Op.LOAD_CONST, builder.constant("x"), 1);
            builder.emit(step, 1);
            builder.emit(Op.RETURN_NULL, 1);
            IllegalStateException raised = assertThrows(IllegalStateException.class,
                    () -> run(builder), step + " should refuse a string");
            assertEquals("iteration found Str where the compiler promised a cursor",
                    raised.getMessage());
        }
        // The cursor itself walks a list; a hand-built one answers the same three
        // instructions the compiler uses, which is what keeps the guard above about
        // the stack rather than about the opcode.
        CompiledFunction.Builder walk = body();
        int cursor = walk.constant(MandelaIterator.of(new MandelaList(List.<Object>of(1L, 2L))));
        walk.emit(Op.LOAD_CONST, cursor, 1);
        walk.emit(Op.ITER_NEXT, 1);
        // advance() leaves its answer on top of the cursor it peeked, and the value
        // opcode reads the cursor at the top -- which is why a `for` loop pops the
        // answer into its own conditional jump.
        walk.emit(Op.POP, 1);
        walk.emit(Op.ITER_VALUE, 1);
        walk.emit(Op.RETURN, 1);
        assertEquals(1L, run(walk));
    }

    @Test
    @DisplayName("a field read asks for an instance, in the machine's own words")
    void aFieldReadNamesWhatWasOnTheStack() {
        CompiledFunction.Builder builder = body();
        builder.emit(Op.LOAD_CONST, builder.constant(7L), 1);
        builder.emit(Op.GET_FIELD, 0, 1);
        builder.emit(Op.RETURN, 1);
        MandelaError raised = assertThrows(MandelaError.class, () -> run(builder));
        assertEquals("TypeError", raised.kind());
        assertEquals("cannot read field 0 of a Int; a 'Type(...)' pattern needs an"
                + " instance", raised.getMessage());
    }

    @Test
    @DisplayName("an initialiser that writes onto a value says so by field and type")
    void anInitialiserWritingOntoAValueIsNamed() {
        CompiledFunction.Builder builder = body();
        builder.emit(Op.LOAD_CONST, builder.constant("text"), 1);
        builder.emit(Op.LOAD_CONST, builder.constant(1L), 1);
        builder.emit(Op.INIT_FIELD, builder.constant("v"), 1);
        builder.emit(Op.RETURN_NULL, 1);
        MandelaError raised = assertThrows(MandelaError.class, () -> run(builder));
        assertEquals("TypeError", raised.kind());
        assertEquals("the initialiser of 'v' wrote onto a Str, which holds no fields",
                raised.getMessage());
    }

    @Test
    @DisplayName("a named call carries labels, and the machine says when it does not")
    void aNamedCallWithoutLabelsIsTheMachinesFaultToReport() {
        CompiledFunction.Builder builder = body();
        builder.emit(Op.LOAD_CONST, builder.constant(3L), 1);
        builder.emit(Op.LOAD_CONST, builder.constant("Point"), 1);
        builder.emit(Op.LOAD_CONST, builder.constant(1L), 1);
        builder.emit(Op.CALL_KW, 1, 1);
        IllegalStateException raised = assertThrows(IllegalStateException.class,
                () -> run(builder));
        assertEquals("a named call carried Int where the compiler promised labels",
                raised.getMessage());
    }

    @Test
    @DisplayName("a named call cannot hand more labels than values")
    void labelsAndValuesAreCountedInTheSameCall() {
        CompiledFunction.Builder builder = body();
        // A declared type with two fields, reached as a constant the way the
        // compiler reaches it, so only the label/value mismatch is under test.
        Object pair = Scripts.run("type Pair(a: Int, b: Int)\nPair");
        builder.emit(Op.LOAD_CONST, builder.constant(new String[] { "a", "b" }), 1);
        builder.emit(Op.LOAD_CONST, builder.constant(pair), 1);
        builder.emit(Op.LOAD_CONST, builder.constant(1L), 1);
        builder.emit(Op.CALL_KW, 1, 1);
        IllegalStateException raised = assertThrows(IllegalStateException.class,
                () -> run(builder));
        assertEquals("a named call gave 1 argument(s) for 2 label(s)",
                raised.getMessage());
    }

    // -- the arity gate a frame opens behind ----------------------------------

    @Test
    @DisplayName("arity is settled before the frame exists, in both directions")
    void arityIsCheckedBeforeTheFrameOpens() {
        CompiledFunction.Builder twoRequired = body();
        twoRequired.params(2, 2);
        twoRequired.frame(2, 0);
        twoRequired.emit(Op.LOAD_CONST, twoRequired.constant(0L), 1);
        twoRequired.emit(Op.RETURN, 1);
        MandelaClosure needsTwo = new MandelaClosure(twoRequired.build(), new Object[0]);

        MandelaError tooFew = assertThrows(MandelaError.class,
                () -> machine().invoke(needsTwo, null, new Object[] { 1L }));
        assertEquals("ValueError", tooFew.kind());
        assertEquals("'body' needs at least 2 argument(s); the call gave 1",
                tooFew.getMessage());
        MandelaError tooMany = assertThrows(MandelaError.class,
                () -> machine().invoke(needsTwo, null, new Object[] { 1L, 2L, 3L }));
        assertEquals("'body' takes 2 argument(s); the call gave 3", tooMany.getMessage());

        // One declared, none required: the fewest and the most differ, and a call
        // with nothing at all is legal -- the body's own default prologue handles it.
        CompiledFunction.Builder oneDefaulted = body();
        oneDefaulted.params(1, 0);
        oneDefaulted.frame(1, 0);
        oneDefaulted.emit(Op.LOAD_CONST, oneDefaulted.constant(4L), 1);
        oneDefaulted.emit(Op.RETURN, 1);
        MandelaClosure optional = new MandelaClosure(oneDefaulted.build(), new Object[0]);
        assertEquals(4L, machine().invoke(optional, null, new Object[0]));
        assertEquals(4L, machine().invoke(optional, null, new Object[] { 7L }));
        assertThrows(MandelaError.class,
                () -> machine().invoke(optional, null, new Object[] { 7L, 8L }));
    }

    @Test
    @DisplayName("a lambda with no name of its own is described as one")
    void anUnnamedBodyIsDescribedAsALambda() {
        CompiledFunction.Builder anonymous = new CompiledFunction.Builder("", "code.mnd", 1);
        anonymous.params(1, 1);
        anonymous.frame(1, 0);
        anonymous.emit(Op.LOAD_CONST, anonymous.constant(0L), 1);
        anonymous.emit(Op.RETURN, 1);
        MandelaClosure lambda = new MandelaClosure(anonymous.build(), new Object[0]);
        MandelaError raised = assertThrows(MandelaError.class,
                () -> machine().invoke(lambda, null, new Object[0]));
        // The word a host and an author both recognise, because nothing else in the
        // diagnostic would say which of the script's ten lambdas failed.
        assertEquals("'lambda' needs at least 1 argument(s); the call gave 0",
                raised.getMessage());
    }
}
