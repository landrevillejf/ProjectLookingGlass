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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.code.CompiledFunction;
import org.jdesktop.lg3d.mandela.code.Op;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaClosure;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.Ref;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The machine's own invariants, driven at the instruction level.
 *
 * <p>{@code MachineDispatchTest} and {@code MachineCodeTest} cover the opcodes a
 * script reaches; this suite covers the machine's plumbing that a mutation can
 * quietly bend without changing what an ordinary script prints: the frame a
 * method opens, the cell a captured slot is wrapped in, the {@code defer} bodies
 * owed on the way out, the sandbox check that has to fire <em>before</em> the
 * missing-channel check, the stack a host sees when it calls back in, and the
 * source a host failure is located at. Each test names the mutant it exists to
 * catch, because a passing assertion that cannot distinguish the healthy machine
 * from a mutated one is not a test.</p>
 */
class VmTest {

    /** A sandbox with no grants at all: computation only, and a locked channel. */
    private static final Capabilities CAPS = Capabilities.builder()
            .label("vm")
            .maxSteps(200_000L)
            .maxDepth(32)
            .build();

    /** The same budget with a console, for the two tests that run a real script. */
    private static final Capabilities HOSTCAPS = Capabilities.builder()
            .label("vm-host")
            .grant(Capabilities.Grant.STDOUT)
            .grant(Capabilities.Grant.STDERR)
            .maxSteps(200_000L)
            .maxDepth(32)
            .build();

    private static VM machine() {
        return new VM(CAPS);
    }

    /** A top-level body: no parameters, no captures. */
    private static CompiledFunction.Builder body() {
        CompiledFunction.Builder builder = new CompiledFunction.Builder("body", "vm.mnd", 1);
        builder.params(0, 0);
        builder.frame(0, 0);
        return builder;
    }

    private static Object run(CompiledFunction.Builder builder) {
        return machine().run(builder.build());
    }

    /** A {@code defer} body that writes {@code tag} into {@code cell} when it runs. */
    private static MandelaClosure deferSetting(Ref cell, String tag) {
        CompiledFunction.Builder thunk = new CompiledFunction.Builder("defer", "vm.mnd", 1);
        thunk.params(0, 0);
        thunk.frame(0, 1);
        thunk.emit(Op.LOAD_CONST, thunk.constant(tag), 1);
        thunk.emit(Op.SET_UPVALUE, 0, 1);
        thunk.emit(Op.RETURN_NULL, 1);
        return new MandelaClosure(thunk.build(), new Object[] { cell });
    }

    // -- the sandbox check runs before the missing-channel check --------------

    @Test
    @DisplayName("writeError is refused by the sandbox before the missing sink")
    void writeErrorChecksTheCapabilityFirst() {
        VM locked = new VM(CAPS);
        // CAPS grants neither channel and no sink is wired, so two faults compete:
        // the missing STDERR grant and the missing error sink. The grant is checked
        // first, and a mutation that drops the require() would fall through to the
        // IOError for the missing sink -- a different kind for a different fault.
        MandelaError refused = assertThrows(MandelaError.class, () -> locked.writeError("bad"));
        assertEquals("PermissionError", refused.kind());
        assertTrue(refused.getMessage().contains("writing to the error channel"),
                refused.getMessage());
    }

    // -- the operand the machine reports to a host that calls back in ----------

    @Test
    @DisplayName("a host called mid-expression sees the operands already stacked")
    void snapshotStackCopiesTheLiveOperands() {
        final Runtime[] holder = new Runtime[1];
        List<Object> seen = new ArrayList<>();
        holder[0] = Mandela.engine(HOSTCAPS).bind("peek", HostFunction.of("peek", 0,
                args -> {
                    seen.addAll(holder[0].machine().snapshotStack());
                    return 0L;
                })).build();
        // When peek() runs, the left operand of `+` is on the stack and the callee
        // has just been popped, so the snapshot is exactly [1]. Returning an empty
        // list instead -- the mutation -- would leave `seen` empty.
        assertEquals(1L, holder[0].run("1 + peek()", "test.mnd"));
        assertEquals(List.of(1L), seen, "the operand stacked so far, in order");
    }

    // -- the frame a method opens ---------------------------------------------

    @Test
    @DisplayName("a method frame is wide enough for this, super and the arguments")
    void aMethodFrameReservesRoomForThisAndSuperAndArgs() {
        CompiledFunction.Builder method = new CompiledFunction.Builder("m", "vm.mnd", 1);
        method.markMethod();
        method.params(2, 2);
        method.frame(0, 0);
        method.emit(Op.LOAD_CONST, method.constant(42L), 1);
        method.emit(Op.RETURN, 1);
        // localsCount is 0, so the width is driven entirely by paramsAt + arity
        // (2 + 2 = 4). A mutation to paramsAt - arity would size the cells array 0
        // and the very first write -- `this` into slot 0 -- would run off the end.
        MandelaClosure closure = new MandelaClosure(method.build(), new Object[0]);
        assertEquals(42L, machine().invoke(closure, null, new Object[] { 1L, 2L }));
    }

    @Test
    @DisplayName("a captured slot is wrapped in a cell, and an out-of-range one is skipped")
    void aBoxedSlotIsWrappedAtEitherBoundary() {
        // Slot 0 is boxed: the `slot >= 0` guard is what wraps it, so a body that
        // reads it as a cell finds one holding null. A `> 0` boundary would skip
        // the wrap and the read would report a lost cell instead.
        CompiledFunction.Builder zero = new CompiledFunction.Builder("z", "vm.mnd", 1);
        zero.params(0, 0);
        zero.frame(1, 0);
        zero.box(0);
        zero.emit(Op.GET_LOCAL_BOX, 0, 1);
        zero.emit(Op.RETURN, 1);
        assertNull(machine().run(zero.build()), "slot 0 was boxed, so it reads back as null");

        // Slot 1 is one past a one-slot frame: the `slot < cells.length` guard skips
        // it, because there is no such slot to wrap. A `<=` boundary would index
        // past the end of the cells array.
        CompiledFunction.Builder past = new CompiledFunction.Builder("p", "vm.mnd", 1);
        past.params(0, 0);
        past.frame(1, 0);
        past.box(1);
        past.emit(Op.LOAD_CONST, past.constant(7L), 1);
        past.emit(Op.RETURN, 1);
        assertEquals(7L, machine().run(past.build()), "the out-of-range box was ignored");
    }

    // -- returning with no value ----------------------------------------------

    @Test
    @DisplayName("a body that returns nothing stops at the barrier")
    void returnNullStopsTheLoop() {
        CompiledFunction.Builder builder = body();
        builder.emit(Op.RETURN_NULL, 1);
        // RETURN_NULL hands back null and, at the barrier, stops the loop. A negated
        // test would fall through into a frame chain that is already empty and read
        // a null `current`.
        assertNull(run(builder));
    }

    // -- the comparison and type tests ----------------------------------------

    @Test
    @DisplayName("`!=` on two equal values is false, not simply true")
    void notEqualIsFalseForEqualOperands() {
        CompiledFunction.Builder same = body();
        same.emit(Op.LOAD_CONST, same.constant(1L), 1);
        same.emit(Op.LOAD_CONST, same.constant(1L), 1);
        same.emit(Op.NE, 1);
        same.emit(Op.RETURN, 1);
        // A boolean return silently replaced by `true` is invisible unless a case
        // expects false: 1 != 1 is that case.
        assertEquals(false, run(same));

        CompiledFunction.Builder different = body();
        different.emit(Op.LOAD_CONST, different.constant(1L), 1);
        different.emit(Op.LOAD_CONST, different.constant(2L), 1);
        different.emit(Op.NE, 1);
        different.emit(Op.RETURN, 1);
        assertEquals(true, run(different), "and the healthy half still says true");
    }

    @Test
    @DisplayName("an error answers to its own kind, to Error, and to nothing else")
    void anErrorIsTypedByItsKind() {
        MandelaError failure = MandelaError.value("nope");
        // Values.isType says no to all three (an error is not a builtin-typed
        // value), so each reaches the error branch: kind().equals(name) for the
        // first, the ERROR catch-all for the second, and neither for the third.
        assertEquals(true, isTypeOf(failure, "ValueError"), "its own kind");
        assertEquals(true, isTypeOf(failure, "Error"), "the catch-all filter");
        // A boolean replaced by `true`, or a negated kind test, would make a filter
        // that must not match report that it does.
        assertEquals(false, isTypeOf(failure, "TypeError"), "a kind it does not have");
    }

    private static Object isTypeOf(Object value, String name) {
        CompiledFunction.Builder builder = body();
        builder.emit(Op.LOAD_CONST, builder.constant(value), 1);
        builder.emit(Op.IS_TYPE, builder.constant(name), 1);
        builder.emit(Op.RETURN, 1);
        return run(builder);
    }

    // -- map keys that are not strings ----------------------------------------

    @Test
    @DisplayName("a numeric or boolean map key is stored under its display form")
    void aScalarMapKeyIsStoredByItsDisplayForm() {
        // Mandela maps are keyed by name, so a scalar key is accepted only where its
        // text form is unambiguous: 1 becomes "1", true becomes "true". A keyName
        // that returned "" would file the value under the empty string and the
        // read-back by the real display form would miss; a negated type test would
        // refuse the key outright.
        CompiledFunction.Builder numeric = body();
        numeric.emit(Op.LOAD_CONST, numeric.constant(1L), 1);
        numeric.emit(Op.LOAD_CONST, numeric.constant("v"), 1);
        numeric.emit(Op.MAKE_MAP, 1, 1);
        numeric.emit(Op.LOAD_CONST, numeric.constant("1"), 1);
        numeric.emit(Op.GET_INDEX, 1);
        numeric.emit(Op.RETURN, 1);
        assertEquals("v", run(numeric));

        CompiledFunction.Builder flag = body();
        flag.emit(Op.LOAD_CONST, flag.constant(Boolean.TRUE), 1);
        flag.emit(Op.LOAD_CONST, flag.constant("yes"), 1);
        flag.emit(Op.MAKE_MAP, 1, 1);
        flag.emit(Op.LOAD_CONST, flag.constant("true"), 1);
        flag.emit(Op.GET_INDEX, 1);
        flag.emit(Op.RETURN, 1);
        assertEquals("yes", run(flag));
    }

    // -- defer bodies owed on the way out -------------------------------------

    @Test
    @DisplayName("every defer body runs when the frame returns")
    void deferBodiesRunOnReturn() {
        Ref outer = new Ref("unset");
        Ref inner = new Ref("unset");
        CompiledFunction.Builder builder = body();
        builder.emit(Op.LOAD_CONST, builder.constant(deferSetting(outer, "outer")), 1);
        builder.emit(Op.ADD_DEFER, 1);
        builder.emit(Op.LOAD_CONST, builder.constant(deferSetting(inner, "inner")), 1);
        builder.emit(Op.ADD_DEFER, 1);
        builder.emit(Op.LOAD_CONST, builder.constant(1L), 1);
        builder.emit(Op.RETURN, 1);

        assertEquals(1L, run(builder), "the body's own value still comes back");
        // Two defers, both owed. A dropped ADD_DEFER, a returnFrom that never runs
        // them, a runDefers that returns early on a non-zero count, or a loop that
        // stops one turn short (i > 0) or never starts (i < 0) leaves one unset.
        assertEquals("outer", outer.get(), "the first-registered body ran");
        assertEquals("inner", inner.get(), "and so did the second");
    }

    // -- the host seam --------------------------------------------------------

    @Test
    @DisplayName("a zero-arity host function refuses an argument")
    void aZeroArityHostFunctionIsCheckedToo() {
        VM machine = machine();
        HostFunction zero = HostFunction.of("z", 0, args -> "ok");
        assertEquals("ok", machine.invoke(zero, null, new Object[0]), "no argument, no complaint");
        // `declared >= 0` is what lets a zero-arity function be checked at all; a
        // `> 0` boundary would skip the check and silently accept the extra argument.
        MandelaError raised = assertThrows(MandelaError.class,
                () -> machine.invoke(zero, null, new Object[] { 1L }));
        assertEquals("ValueError", raised.kind());
        assertEquals("'z' takes 0 argument(s); the call gave 1", raised.getMessage());
    }

    @Test
    @DisplayName("a host failure is located at the script that called it")
    void aHostErrorCarriesTheRunningScriptsSource() {
        Runtime runtime = Mandela.engine(HOSTCAPS).bind("boom",
                HostFunction.of("boom", 0, args -> {
                    throw MandelaError.value("boom");
                })).build();
        // The raised error carries no line of its own, so callHost locates it at the
        // running frame. sourceOf() reads `current`; a negated null test would hand
        // back null (dropping the source) exactly where a frame is plainly present.
        MandelaError raised = assertThrows(MandelaError.class,
                () -> runtime.run("boom()", "host.mnd"));
        assertEquals("ValueError", raised.kind());
        assertEquals("host.mnd", raised.sourceName());
    }
}
