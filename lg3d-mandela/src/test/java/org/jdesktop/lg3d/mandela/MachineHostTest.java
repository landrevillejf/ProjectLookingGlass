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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaClass;
import org.jdesktop.lg3d.mandela.values.MandelaClosure;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaInstance;
import org.jdesktop.lg3d.mandela.vm.Frame;
import org.jdesktop.lg3d.mandela.vm.VM;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The machine as the host sees it: the seam an Espresso panel or a browser page
 * stands on.
 *
 * <p>A script reaches the machine through syntax, and {@link MachineDispatchTest} and
 * {@link MachineCodeTest} cover that. A Java host reaches it through
 * {@link VM}'s own methods &mdash; it publishes a name into the global table, calls a
 * declared type, watches the frame that is executing, decides where {@code print}
 * goes, and sometimes has a script call back into it from inside a host function.
 * Those are the calls the embedding guide documents, and each one either answers the
 * host or refuses in a sentence the host has to be able to read out to its user.</p>
 *
 * <p>The re-entrancy limit is the reason this suite exists. A host function that
 * invokes script code starts another run inside the first, and a callback that does
 * it forever grows the Java stack until the JVM throws {@code StackOverflowError}
 * past the sandbox. The machine counts those re-entries and refuses; what it says has
 * to be the depth it actually reached, because a host tunes its budget against that
 * number.</p>
 */
class MachineHostTest {

    /** The console-only shape a desktop panel runs a snippet under. */
    private static final Capabilities CONSOLE = Capabilities.builder()
            .label("host")
            .grant(Capabilities.Grant.STDOUT)
            .grant(Capabilities.Grant.STDERR)
            .maxSteps(2_000_000L)
            .maxDepth(128)
            .build();

    /** A sandbox with neither channel: what a bare {@code new VM(caps)} is. */
    private static final Capabilities SILENT = Capabilities.builder()
            .label("silent")
            .maxSteps(2_000_000L)
            .maxDepth(32)
            .build();

    // -- the two ways a host builds a machine ---------------------------------

    @Test
    @DisplayName("a host either starts with an empty table or adopts one it owns")
    void theTwoConstructorsAreTheTwoShapes() {
        VM alone = new VM(SILENT);
        assertTrue(alone.globals().isEmpty(), "the empty table is empty");

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("shared", 1L);
        VM over = new VM(SILENT, table);
        // Retained, not copied: a host that publishes a name after building the
        // machine still reaches the script, because it is the same map.
        assertSame(table, over.globals());
        assertEquals(1L, over.global("shared"));
        over.globals().put("added", 2L);
        assertEquals(2L, over.global("added"), "and a name added through the machine is read back");
        assertNull(over.global("missing"), "a name nobody set reads back null, not an error");
        assertSame(SILENT, over.capabilities(), "the sandbox the host decided on is the one it gets back");
    }

    @Test
    @DisplayName("a host watching a call sees the frame, the stack and the count")
    void theMachineReportsWhatIsExecuting() {
        List<String> seen = new ArrayList<>();
        final Runtime[] holder = new Runtime[1];
        holder[0] = Mandela.engine(CONSOLE).bind("watch", HostFunction.of("watch", 1,
                args -> {
                    VM machine = holder[0].machine();
                    Frame at = machine.currentFrame();
                    seen.add(at == null ? "no frame"
                            : at.name() + " in " + at.sourceName() + " depth " + at.depth());
                    seen.add("counted " + (machine.steps() > 0));
                    // The operand stack at the instant of the call: the arguments are
                    // already in the frame's slots, so what is left is the caller's
                    // own state -- which is exactly what a debugger wants.
                    seen.add("stack " + machine.snapshotStack());
                    seen.add("trace " + machine.trace());
                    return args.get(0);
                })).build();

        assertEquals(7L, holder[0].run("watch(7)", "test.mnd"));
        assertEquals("<script> in test.mnd depth 1", seen.get(0));
        assertEquals("counted true", seen.get(1));
        assertEquals("stack []", seen.get(2));
        assertEquals("trace [<script> (test.mnd:1)]", seen.get(3));
        assertNull(holder[0].machine().currentFrame(),
                "and nothing is executing once the run returns");
    }

    // -- the channels the host owns -------------------------------------------

    @Test
    @DisplayName("a host that grants a console but gives none is refused in words")
    void writingWithoutAChannelNamesTheHostAsTheOneAtFault() {
        VM quiet = new VM(CONSOLE);
        MandelaError noOut = assertThrows(MandelaError.class, () -> quiet.write("hello"));
        assertEquals("IOError", noOut.kind());
        assertEquals("this host gave the script no console to write to", noOut.getMessage());
        // writeLine is the same sink with a newline, so it fails the same way.
        assertEquals("this host gave the script no console to write to",
                assertThrows(MandelaError.class, () -> quiet.writeLine("hello")).getMessage());
        MandelaError noErr = assertThrows(MandelaError.class, () -> quiet.writeError("bad"));
        assertEquals("IOError", noErr.kind());
        assertEquals("this host gave the script no error channel", noErr.getMessage());

        // The capability is checked first: a host that never granted the channel is
        // refused by the sandbox, which is a different answer for a different fault.
        VM locked = new VM(SILENT);
        MandelaError refused = assertThrows(MandelaError.class, () -> locked.write("hello"));
        assertEquals("PermissionError", refused.kind());
        assertTrue(refused.getMessage().contains("writing to the console"),
                refused.getMessage());
    }

    @Test
    @DisplayName("the two channels are separate sinks and arrive exactly as written")
    void theChannelsAreRawSinks() {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        VM machine = new VM(CONSOLE);
        machine.setOutput(out::append, err::append);
        machine.write("no newline");
        machine.writeLine(" plus one");
        machine.writeError("on the other side");
        assertEquals("no newline plus one\n", out.toString());
        assertEquals("on the other side\n", err.toString());
        // A host may wire only one of them, and the other keeps its refusal.
        VM half = new VM(CONSOLE);
        half.setOutput(out::append, null);
        half.write("fine");
        assertEquals("IOError",
                assertThrows(MandelaError.class, () -> half.writeError("nope")).kind());
    }

    @Test
    @DisplayName("a host that offers no modules says so where import and use fail")
    void modulesNeedAHostThatOffersThem() {
        VM bare = new VM(SILENT);
        MandelaError imported = assertThrows(MandelaError.class, () -> bare.importModule("json"));
        assertEquals("'import' needs a host that offers modules; this runtime does not",
                imported.getMessage());
        MandelaError used = assertThrows(MandelaError.class, () -> bare.useModule("json"));
        assertEquals("'use' needs a host that offers modules; this runtime does not",
                used.getMessage());
    }

    // -- calling script values from Java --------------------------------------

    @Test
    @DisplayName("constructing a declared type from Java runs its initialiser")
    void constructingFromTheHostRunsScriptCode() {
        Runtime runtime = Scripts.engine().build();
        MandelaClass point = (MandelaClass) runtime.run("type P(x: Int, y: Int = 9)\nP", "test.mnd");
        MandelaInstance built = (MandelaInstance) runtime.machine()
                .invoke(point, null, new Object[] { 1L });
        assertEquals("P", built.typeName());
        assertEquals(1L, built.get("x"));
        // The default is filled by the initialiser's own prologue, which is script
        // code, so a short call from Java behaves exactly as a short call in a script.
        assertEquals(9L, built.get("y"));
        // Too many arguments is the constructor's answer, in the same sentence.
        MandelaError raised = assertThrows(MandelaError.class, () -> runtime.machine()
                .invoke(point, null, new Object[] { 1L, 2L, 3L }));
        assertEquals("ValueError", raised.kind());
        assertEquals("'init' takes 2 argument(s); the call gave 3", raised.getMessage());
    }

    @Test
    @DisplayName("a type a host declares without an initialiser is still built")
    void aHostDeclaredTypeWithoutAnInitialiserIsBuilt() {
        MandelaClass plain = new MandelaClass(MandelaClass.Kind.CLASS, "Plain", null,
                List.of(), 0);
        VM machine = new VM(SILENT);
        Object built = machine.invoke(plain, null, new Object[] {});
        assertInstanceOf(MandelaInstance.class, built);
        assertEquals("Plain", ((MandelaInstance) built).typeName());
        // With nothing to fill, an argument is refused on the type's own name.
        MandelaError raised = assertThrows(MandelaError.class,
                () -> machine.invoke(plain, null, new Object[] { 1L }));
        assertEquals("TypeError", raised.kind());
        assertEquals("'Plain' takes no arguments", raised.getMessage());
    }

    @Test
    @DisplayName("a host-declared type reached from script code fills no fields")
    void aScriptCanConstructAHostDeclaredType() {
        MandelaClass roomy = new MandelaClass(MandelaClass.Kind.CLASS, "Roomy", null,
                List.of("a"), 0);
        Runtime runtime = Mandela.engine(CONSOLE).bind("Roomy", roomy).build();
        MandelaInstance built = (MandelaInstance) runtime.run("Roomy()", "test.mnd");
        assertNotNull(built, "the machine pushes the instance itself when there is no body to run");
        assertNull(built.get("a"), "and nobody filled the field");
        // One argument is inside the field count, so the count check lets it through
        // and the absence of an initialiser is what refuses it.
        MandelaError raised = assertThrows(MandelaError.class, () -> runtime.run("Roomy(1)", "test.mnd"));
        assertEquals("ValueError", raised.kind());
        assertEquals("'Roomy' takes no arguments", raised.getMessage());
    }

    @Test
    @DisplayName("a host cannot hand the machine an initialiser written in Java")
    void aHostInitialiserIsRefusedRatherThanEnteredAsAFrame() {
        MandelaClass made = new MandelaClass(MandelaClass.Kind.CLASS, "Made", null,
                List.of(), 0);
        made.define("init", HostFunction.of("init", 0, args -> "made"));
        VM machine = new VM(SILENT);
        MandelaError raised = assertThrows(MandelaError.class,
                () -> machine.invoke(made, null, new Object[] {}));
        assertEquals("TypeError", raised.kind());
        // The frame needs a compiled body; a host function has none, and the machine
        // says which of the two it was handed.
        assertEquals("'init' is a host function and cannot be entered as a frame",
                raised.getMessage());
    }

    @Test
    @DisplayName("a receiver that is not an instance is named by the field it broke")
    void writingFieldsOntoAForeignReceiverIsTheCallersFault() {
        Runtime runtime = Scripts.engine().build();
        MandelaClass box = (MandelaClass) runtime.run("class Box { var v = 1 }\nBox", "test.mnd");
        Callable init = box.lookup("init");
        // The host may pass any receiver it likes, and the initialiser's first
        // instruction writes a field onto it: a String holds no fields, and the
        // machine blames the value rather than pretending the write landed.
        MandelaError raised = assertThrows(MandelaError.class, () -> runtime.machine()
                .invoke(init, "not an instance", new Object[] {}));
        assertEquals("TypeError", raised.kind());
        assertEquals("the initialiser of 'v' wrote onto a Str, which holds no fields",
                raised.getMessage());
    }

    @Test
    @DisplayName("a plain function called with an instance resolves super from it")
    void superViewFallsBackToTheReceiver() {
        Runtime runtime = Scripts.engine().build();
        MandelaClosure plain = (MandelaClosure) runtime.run("fun tag() { return \"t\" }\ntag", "test.mnd");
        MandelaInstance receiver = (MandelaInstance) runtime.run(
                "class Base(tag: Str) { fun describe() { return \"base\" } }\n"
                        + "class Derived(tag: Str) extends Base { }\nDerived(\"x\")", "test.mnd");
        assertNull(plain.ownerClass(), "nothing declared this function, so the receiver decides");
        assertEquals("t", runtime.machine().invoke(plain, receiver, new Object[] {}));
        // A receiver that is not an instance is the other half of the same decision:
        // there is simply no base class, which is what `super` then reports.
        assertEquals("t", runtime.machine().invoke(plain, "a string", new Object[] {}));
    }

    // -- the re-entrancy limit ------------------------------------------------

    @Test
    @DisplayName("a callback that calls back is stopped, and says how deep it went")
    void theReentryLimitCountsTheCallbackNotTheStack() {
        Capabilities shallow = Capabilities.builder().label("host").grant(Capabilities.Grant.STDOUT)
                .grant(Capabilities.Grant.STDERR).maxSteps(2_000_000L).maxDepth(8).build();
        final Runtime[] holder = new Runtime[1];
        holder[0] = Mandela.engine(shallow).bind("bounce", HostFunction.of("bounce", 1,
                args -> {
                    long n = ((Long) args.get(0)).longValue();
                    Callable step = (Callable) holder[0].machine().global("step");
                    return holder[0].machine().invoke(step, null,
                            new Object[] { Long.valueOf(n + 1L) });
                })).build();
        MandelaError raised = Scripts.failure("fun step(n) { if (n > 4000) { return n }\n"
                + "  return bounce(n) }\nbounce(0)", holder[0]);
        // maxDepth(8) frames means four re-entries are allowed: the guard stops the
        // fifth, and the number it reports is the depth it stopped at -- a host tunes
        // its budget against that number, so an off-by-one is a wrong instruction.
        assertEquals("LimitError", raised.kind());
        assertEquals("the script has been calling back into the host 5 deep without"
                + " returning (this host allows 4); a callback is probably invoking"
                + " itself", raised.getMessage());
        // The ordinary recursion limit is the other guard, and it names the function
        // the author wrote rather than the re-entry count.
        MandelaError frames = Scripts.failure("fun spin(n) { return spin(n + 1) }\nspin(0)",
                Mandela.engine(Capabilities.builder().label("host")
                        .grant(Capabilities.Grant.STDOUT).grant(Capabilities.Grant.STDERR)
                        .maxSteps(2_000_000L).maxDepth(24).build()));
        assertEquals("LimitError", frames.kind());
        assertTrue(frames.getMessage().contains("the call stack passed 24 frames"),
                frames.getMessage());
    }

    // -- the step budget ------------------------------------------------------

    @Test
    @DisplayName("a runaway loop is retired, and the guard names the ceiling it hit")
    void theStepBudgetStopsARunawayLoop() {
        // The sandbox's headline promise is that a mistyped `loop { }` cannot hang
        // the desktop: the machine counts instructions retired and refuses past the
        // budget. The message quotes maxSteps back to the reader because a host
        // tunes its budget against that number -- an off-by-one there is a wrong
        // instruction handed to whoever has to act on it.
        Capabilities tiny = Capabilities.builder().label("host")
                .grant(Capabilities.Grant.STDOUT).grant(Capabilities.Grant.STDERR)
                .maxSteps(1_000L).maxDepth(64).build();
        MandelaError loop = Scripts.failure("var i = 0\nloop { i += 1 }",
                Mandela.engine(tiny));
        assertEquals("LimitError", loop.kind());
        assertEquals("the script retired more than 1000 instructions; a loop is"
                + " probably never terminating", loop.getMessage());
        // `while` that never reaches its exit is the same runaway wearing different
        // syntax, so the one guard has to catch it too.
        MandelaError whileLoop = Scripts.failure("var n = 0\nwhile n < 1000000 { }",
                Mandela.engine(tiny));
        assertEquals("LimitError", whileLoop.kind());
        assertTrue(whileLoop.getMessage().contains("the script retired more than 1000"),
                whileLoop.getMessage());
    }

    @Test
    @DisplayName("the budget is a ceiling, not a trip wire: honest work under it runs")
    void workUnderTheBudgetIsNotRefused() {
        // The other half of the `>` comparison the guard is written against. A
        // program that retires fewer instructions than the budget must complete,
        // and the count is reset per run, so a finished program does not spend the
        // next one's allowance.
        Capabilities small = Capabilities.builder().label("host")
                .grant(Capabilities.Grant.STDOUT).grant(Capabilities.Grant.STDERR)
                .maxSteps(100_000L).maxDepth(64).build();
        Runtime runtime = Mandela.engine(small).build();
        assertEquals(45L, runtime.run(
                "var total = 0\nvar k = 0\nwhile k < 10 { total += k; k += 1 }\ntotal",
                "test.mnd"));
        long afterFirst = runtime.machine().steps();
        assertTrue(afterFirst > 0, "the run retired instructions");
        assertTrue(afterFirst < 100_000L, "and stayed under the ceiling");
        // A second honest run still fits on the same engine: the budget is per run.
        assertEquals(3L, runtime.run("1 + 2", "test.mnd"));
        assertTrue(runtime.machine().steps() < 100_000L);
    }

    @Test
    @DisplayName("tripping the budget once does not retire the machine")
    void theBudgetTripIsRecoverable() {
        // The limit is a catchable MandelaError, not a fatal state: after a runaway
        // was retired, the very same engine must run a bounded program. That is what
        // lets a host `try` around untrusted code, keep its engine, and refuse a
        // hang without sacrificing the session.
        Capabilities tiny = Capabilities.builder().label("host")
                .grant(Capabilities.Grant.STDOUT).grant(Capabilities.Grant.STDERR)
                .maxSteps(500L).maxDepth(64).build();
        Runtime runtime = Mandela.engine(tiny).build();
        MandelaError raised = Scripts.failure("loop { }", runtime);
        assertEquals("LimitError", raised.kind());
        // The machine survived the runaway: a fresh run gets a fresh budget.
        assertEquals(7L, runtime.run("3 + 4", "test.mnd"));
    }

    @Test
    @DisplayName("a failure that escapes leaves no frame for the host to read")
    void anEscapedFailureClearsTheChain() {
        Runtime runtime = Scripts.engine().build();
        MandelaError raised = Scripts.failure("fun deep() { return error(\"gone\") }\ndeep()",
                runtime);
        assertEquals("Error", raised.kind());
        assertNull(runtime.machine().currentFrame(), "the chain is gone with the run");
        assertTrue(runtime.machine().trace().isEmpty());
        assertFalse(runtime.machine().failureTrace().isEmpty(),
                "but the frames it unwound through are kept for the report");
        // And the next run starts clean: steps are counted per run, not per machine.
        runtime.run("1 + 1", "test.mnd");
        assertTrue(runtime.machine().steps() > 0);
        assertNull(runtime.machine().currentFrame());
    }
}
