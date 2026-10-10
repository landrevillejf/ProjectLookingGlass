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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jdesktop.lg3d.mandela.code.ClassDef;
import org.jdesktop.lg3d.mandela.code.CompiledFunction;
import org.jdesktop.lg3d.mandela.code.Op;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaClass;
import org.jdesktop.lg3d.mandela.values.MandelaClosure;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaInstance;
import org.jdesktop.lg3d.mandela.values.MandelaIterator;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.MandelaRange;
import org.jdesktop.lg3d.mandela.values.Operators;
import org.jdesktop.lg3d.mandela.values.Ref;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * The Mandela virtual machine: a two-word-per-instruction stack interpreter.
 *
 * <p>Everything the compiler promises is settled here and nowhere else. One
 * shared operand stack grows as deep as the values need; one chain of
 * {@link Frame} records holds the activations; the dispatch loop reads
 * {@code code[ip]} and {@code code[ip + 1]} and does not call a method to fetch
 * them. There is no IR, no peephole pass and no JIT: the language is a desktop
 * scripting language, and an interpreter that stays this small is fast enough
 * for panels, widgets and page scripts while costing nothing to start up.</p>
 *
 * <h2>Re-entrancy</h2>
 *
 * <p>A built-in has to be able to run script code &mdash; {@code map { }} calls a
 * callback, a widget's {@code onClick} fires a handler long after the script
 * that installed it returned. Java cannot "step into" the interpreter loop from
 * the middle of it, so the loop cooperates: {@link #invoke} pushes a frame and
 * marks it as the <em>barrier</em>, and the dispatch loop that is already
 * running picks the frame up and stops the moment that frame returns. The return
 * value is then handed straight back to the native caller instead of being
 * pushed onto the stack the outer expression is still building. Nested callbacks
 * therefore cost one Java frame each, and {@link Capabilities#maxDepth()}
 * bounds both the script frames and the nesting.</p>
 *
 * <h2>Errors</h2>
 *
 * <p>A Mandela error is a Java exception on the way out of an instruction and a
 * stacked value on the way into a {@code catch}. {@link #dispatchError} walks
 * the frame chain for a live handler, runs the {@code defer} bodies of every
 * frame it discards, and stops at the barrier: past that point the exception
 * resumes travelling up through the native call that caught it, into the outer
 * loop, which looks for handlers there. This is what lets an error thrown inside
 * a {@code map} callback be caught by a {@code try} in the calling script.</p>
 *
 * <h2>Thread safety</h2>
 *
 * <p>None, on purpose. A machine runs one script on one thread; a desktop that
 * wants scripts in parallel builds one machine per thread, each with its own
 * globals and its own budget.</p>
 *
 * @see Machine
 * @see Builtins
 * @see org.jdesktop.lg3d.mandela.code.Compiler
 */
public final class VM implements Machine {

    /** The shared empty capture array; most closures capture nothing. */
    private static final Object[] NO_CAPTURES = new Object[0];

    /** The shared empty argument array. */
    private static final Object[] NO_ARGS = new Object[0];

    /** The ordering a comparison instruction asked for. */
    private static final int EQ = 0;
    private static final int NE = 1;
    private static final int LT = 2;
    private static final int LE = 3;
    private static final int GT = 4;
    private static final int GE = 5;

    /** A destination for script output: a raw character sink, one call per write. */
    @FunctionalInterface
    public interface Output {
        /**
         * @param text the characters to emit, exactly as the machine produced them
         */
        void write(String text);
    }

    /** An arithmetic or concatenation operator, so one helper covers six opcodes. */
    private interface BinOp {
        /** @param left the deeper operand
         *  @param right the operand on top
         *  @return the result value */
        Object apply(Object left, Object right);
    }

    private final Capabilities caps;
    private final Map<String, Object> globals;

    private Builtins builtins;
    private Output out;
    private Output err;

    /** The shared operand stack. */
    private Object[] stack = new Object[64];

    /** The next free stack cell. */
    private int sp;

    /** The activation currently executing. */
    private Frame current;

    /** The frame whose return must hand control back to a native caller. */
    private Frame barrier;

    /** What the barrier frame returned, valid while a native {@link #invoke} unwinds. */
    private Object lastReturn;

    /** Java frames consumed by nested native invocations. */
    private int driveDepth;

    /** Instructions retired so far, checked against the budget. */
    private long steps;

    /**
     * The frame chain a failure escaped through, kept because the unwind that lets
     * the error leave the run pops every frame, and a {@code --trace} report is
     * written after that. Reset by each {@link #run}.
     */
    private List<String> lastTrace = List.of();

    /**
     * Creates a machine with an empty global table.
     *
     * @param capabilities the sandbox the host decided on
     */
    public VM(Capabilities capabilities) {
        this(capabilities, new LinkedHashMap<>());
    }

    /**
     * Creates a machine over an existing global table, which is how a host
     * publishes functions once and runs many scripts against them.
     *
     * @param capabilities the sandbox for this machine
     * @param globalTable  the names scripts may read and bind; retained, not copied
     */
    public VM(Capabilities capabilities, Map<String, Object> globalTable) {
        this.caps = Objects.requireNonNull(capabilities, "capabilities");
        this.globals = Objects.requireNonNull(globalTable, "globalTable");
    }

    /** Installs the standard library this machine delegates to. */
    public void setBuiltins(Builtins table) {
        this.builtins = table;
    }

    /**
     * Installs the two console channels. Either may be null, in which case
     * writing to it fails the way a missing capability does.
     *
     * <p>Both are raw character sinks: the machine decides where a line ends, so a
     * host that shows whole lines (a browser console, an editor panel) and a host
     * that pipes a stream (the CLI) both work without guessing.</p>
     *
     * @param standardOut   where {@code print} and {@code println} go
     * @param standardError where {@code eprintln} and uncaught errors go
     */
    public void setOutput(Output standardOut, Output standardError) {
        this.out = standardOut;
        this.err = standardError;
    }

    /** @return the live global table, so a host can publish and read names */
    public Map<String, Object> globals() {
        return globals;
    }

    /** @return the frame now executing, or null between runs; for a debugger */
    public Frame currentFrame() {
        return current;
    }

    /** @return the instructions retired so far, for a profiler or a benchmark */
    public long steps() {
        return steps;
    }

    // -- entry points ----------------------------------------------------------

    /**
     * Runs a compiled script to completion.
     *
     * @param script the top-level body the compiler produced
     * @return the value of the script's last expression, or null
     * @throws MandelaError when the script fails and nothing catches it
     */
    public Object run(CompiledFunction script) {
        Objects.requireNonNull(script, "script");
        steps = 0;
        sp = 0;
        current = null;
        lastTrace = List.of();
        return drive(enterFrame(new MandelaClosure(script, NO_CAPTURES), null, NO_ARGS));
    }

    /**
     * Calls a script or host callable from Java: the seam behind an Espresso
     * panel's event handler or a browser page's bridge.
     *
     * {@inheritDoc}
     */
    @Override
    public Object invoke(Callable target, Object receiver, Object[] args) {
        Objects.requireNonNull(target, "target");
        Object[] argv = (args == null) ? NO_ARGS : args;
        if (target instanceof HostFunction host) {
            return callHost(host, argv);
        }
        if (target instanceof MandelaClosure closure) {
            return drive(enterFrame(closure, receiver, argv));
        }
        if (target instanceof MandelaClass declared) {
            // Constructing from Java runs the initialiser, which is script code,
            // through the same drive path a call does.
            Callable init = declared.lookup("init");
            if (init == null) {
                if (argv.length > 0) {
                    throw MandelaError.type("'" + declared.name() + "' takes no arguments");
                }
                return new MandelaInstance(declared);
            }
            return drive(enterFrame(asClosure(init), new MandelaInstance(declared), argv));
        }
        throw MandelaError.type("'" + target.name() + "' is not callable by this host");
    }

    /**
     * @return the frames now executing, outermost first, in the
     *         {@code name (source:line)} form the CLI prints under a crash
     */
    public List<String> trace() {
        List<String> frames = new ArrayList<>();
        for (Frame at = current; at != null; at = at.caller) {
            frames.add((at.name().isEmpty() ? "lambda" : at.name()) + " ("
                    + at.sourceName() + ":" + at.line() + ")");
        }
        Collections.reverse(frames);
        return frames;
    }

    /**
     * @return the frames the last uncaught failure unwound through, outermost
     *         first, or an empty list when nothing has escaped this run
     */
    public List<String> failureTrace() {
        return lastTrace;
    }

    // -- the Machine service ---------------------------------------------------

    @Override
    public Object global(String name) {
        return globals.get(name);
    }

    @Override
    public Capabilities capabilities() {
        return caps;
    }

    @Override
    public void write(String text) {
        caps.require(Capabilities.Grant.STDOUT, "writing to the console");
        if (out == null) {
            throw MandelaError.io("this host gave the script no console to write to");
        }
        out.write(text);
    }

    @Override
    public void writeLine(String text) {
        write(text + "\n");
    }

    @Override
    public void writeError(String text) {
        caps.require(Capabilities.Grant.STDERR, "writing to the error channel");
        if (err == null) {
            throw MandelaError.io("this host gave the script no error channel");
        }
        err.write(text + "\n");
    }

    @Override
    public Object importModule(String path) {
        requireBuiltins("import");
        return builtins.importModule(this, path);
    }

    @Override
    public int useModule(String path) {
        requireBuiltins("use");
        MandelaMap names = builtins.stdlibNames(this, path);
        int installed = 0;
        for (Map.Entry<String, Object> entry : names.entries().entrySet()) {
            globals.put(entry.getKey(), entry.getValue());
            installed++;
        }
        return installed;
    }

    @Override
    public List<Object> snapshotStack() {
        List<Object> copy = new ArrayList<>(sp);
        for (int i = 0; i < sp; i++) {
            copy.add(stack[i]);
        }
        return copy;
    }

    // -- frames ---------------------------------------------------------------

    /**
     * Pushes an activation for {@code closure} onto the chain.
     *
     * <p>A method frame reserves slot 0 for {@code this} and slot 1 for the
     * base-class view the compiler resolved {@code super} against. Slot 1 comes
     * from the closure's declaring class rather than from the receiver, which is
     * what makes a three-level hierarchy's {@code super.describe()} land one
     * level up instead of recursing into itself. Arguments the call site left
     * out arrive as {@link Values#ABSENT} so the body's own prologue can
     * substitute the default expression.</p>
     */
    private Frame enterFrame(MandelaClosure closure, Object receiver, Object[] args) {
        CompiledFunction function = closure.function();
        Object[] argv = (args == null) ? NO_ARGS : args;
        checkArity(function, argv.length);
        Object self = (receiver != null) ? receiver : closure.boundThis();
        int paramsAt = function.isMethod() ? 2 : 0;
        int width = Math.max(function.localsCount(), paramsAt + function.arity());
        Object[] cells = new Object[width];
        if (paramsAt == 2) {
            cells[0] = self;
            cells[1] = superView(closure, self);
        }
        for (int i = 0; i < function.arity(); i++) {
            cells[paramsAt + i] = (i < argv.length) ? argv[i] : Values.ABSENT;
        }
        // A boxed slot is shared with the closures that captured it, so it has to
        // hold a cell rather than the bare value. Wrapping happens after the
        // arguments land, because a parameter may be captured too, and a defaulted
        // one still needs to arrive as ABSENT for the body's own prologue.
        for (int slot : function.boxedSlots()) {
            if (slot >= 0 && slot < cells.length && !(cells[slot] instanceof Ref)) {
                cells[slot] = new Ref(cells[slot]);
            }
        }
        Frame frame = new Frame(function, cells, closure.upvalues(), closure, current, sp,
                (current == null) ? 0 : current.line());
        if (frame.depth > caps.maxDepth()) {
            throw MandelaError.limit("the call stack passed " + caps.maxDepth()
                    + " frames inside '" + describe(function) + "'; a recursive call"
                    + " is probably never reaching its base case");
        }
        current = frame;
        return frame;
    }

    /** The base class a method's {@code super} searches, or null when there is none. */
    private static MandelaClass superView(MandelaClosure closure, Object self) {
        MandelaClass declaring = closure.ownerClass();
        if (declaring != null) {
            return declaring.parent();
        }
        if (self instanceof MandelaInstance instance) {
            return instance.owner().parent();
        }
        return null;
    }

    private static void checkArity(CompiledFunction function, int given) {
        if (function.arity() < 0) {
            return;
        }
        if (given < function.minArgs()) {
            throw MandelaError.value("'" + describe(function) + "' needs at least "
                    + function.minArgs() + " argument(s); the call gave " + given);
        }
        if (given > function.arity()) {
            throw MandelaError.value("'" + describe(function) + "' takes "
                    + function.arity() + " argument(s); the call gave " + given);
        }
    }

    private static String describe(CompiledFunction function) {
        return function.name().isEmpty() ? "lambda" : function.name();
    }

    /**
     * Runs the loop until {@code frame} returns, then hands its value back.
     *
     * <p>Used both for a top-level {@link #run} and for a built-in that called
     * script code: in the second case the loop is already on the Java stack
     * underneath this call, and the barrier is what makes it return here rather
     * than carry on with the caller's own expression.</p>
     */
    private Object drive(Frame frame) {
        Frame outer = barrier;
        int outerDepth = driveDepth;
        barrier = frame;
        driveDepth++;
        if (driveDepth > caps.maxDepth() / 2) {
            barrier = outer;
            driveDepth = outerDepth;
            throw MandelaError.limit("the script has been calling back into the host"
                    + " " + driveDepth + " deep without returning; a callback is"
                    + " probably invoking itself");
        }
        try {
            loop();
        } finally {
            barrier = outer;
            driveDepth = outerDepth;
        }
        return lastReturn;
    }

    /** The dispatcher's outer shell: retire instructions, or resume a handler. */
    private void loop() {
        while (true) {
            try {
                dispatch();
                return;
            } catch (MandelaError error) {
                if (!dispatchError(error)) {
                    throw error;
                }
                // `current` now holds a live handler and the error sits on the
                // stack for its `catch` body, so the script resumes there.
            }
        }
    }

    /** @return true when a handler caught {@code error} inside this run's barrier */
    private boolean dispatchError(MandelaError error) {
        // The chain is still linked at this instant, and this is the last moment it
        // is: the unwind below pops every frame, so a report that wants to say where
        // a failure came from has to read the frames now and keep them.
        List<String> frames = trace();
        Frame at = current;
        while (at != null) {
            int base = at.peekHandlerBase();
            int target = at.popHandlerTarget();
            if (target >= 0) {
                MandelaError located = (error.sourceName() == null)
                        ? error.at(at.sourceName(), at.line()) : error;
                current = at;
                sp = base;
                push(located);
                at.ip = target;
                return true;
            }
            Frame caller = at.caller;
            sp = at.stackBase;
            boolean pastBarrier = (at == barrier);
            runDefers(at);
            at.clear();
            current = caller;
            if (pastBarrier) {
                // Let the exception leave through the native call that started
                // this frame; the loop underneath owns the next round of handlers.
                lastTrace = frames;
                return false;
            }
            at = caller;
        }
        current = null;
        lastTrace = frames;
        return false;
    }

    /** Runs and forgets a frame's {@code defer} bodies, innermost first. */
    private void runDefers(Frame frame) {
        if (frame.deferCount == 0) {
            return;
        }
        MandelaClosure[] pending = frame.defers;
        int count = frame.deferCount;
        frame.defers = new MandelaClosure[2];
        frame.deferCount = 0;
        // A defer that throws replaces the error already in flight and the
        // remaining bodies are dropped: the same trade every language with
        // unwind-time side effects settles on.
        for (int i = count - 1; i >= 0; i--) {
            invoke(pending[i], null, NO_ARGS);
            pending[i] = null;
        }
    }

    /**
     * Removes the finished frame, paying its {@code defer} bodies first.
     *
     * <p>When the finished frame is the barrier the value is <em>not</em> pushed:
     * the native caller that started it takes it from {@link #drive} instead, and
     * the outer expression's stack has to look exactly as it did before the
     * callback.</p>
     *
     * @return true when the dispatch loop must stop and hand back to a native call
     */
    private boolean returnFrom(Object value) {
        Frame finished = current;
        current = finished.caller;
        sp = finished.stackBase;
        runDefers(finished);
        finished.clear();
        lastReturn = value;
        if (finished == barrier) {
            return true;
        }
        push(value);
        return false;
    }

    // -- the dispatch loop ----------------------------------------------------

    /** Executes instructions until the barrier frame returns or the chain empties. */
    private void dispatch() {
        while (current != null) {
            Frame frame = current;
            int[] code = frame.function.code();
            int ip = frame.ip;
            Op op = Op.byId(code[ip]);
            int arg = code[ip + 1];
            frame.ip = ip + 2;
            if (++steps > caps.maxSteps()) {
                throw MandelaError.limit("the script retired more than " + caps.maxSteps()
                        + " instructions; a loop is probably never terminating");
            }
            Object[] constants = frame.function.constants();
            switch (op) {
                case LOAD_CONST:
                    push(constants[arg]);
                    break;
                case POP:
                    pop();
                    break;
                case DUP:
                    push(peek());
                    break;
                case LOAD_THIS:
                    push(frame.slots[arg]);
                    break;
                case GET_LOCAL:
                    push(frame.slots[arg]);
                    break;
                case SET_LOCAL:
                    frame.slots[arg] = pop();
                    break;
                case GET_LOCAL_BOX:
                    push(ref(frame.slots[arg]).get());
                    break;
                case SET_LOCAL_BOX:
                    ref(frame.slots[arg]).set(pop());
                    break;
                case GET_UPVALUE:
                    push(ref(frame.upvalues[arg]).get());
                    break;
                case SET_UPVALUE:
                    ref(frame.upvalues[arg]).set(pop());
                    break;
                case CAPTURE_LOCAL:
                    push(ref(frame.slots[arg]));
                    break;
                case CAPTURE_UPVALUE:
                    push(ref(frame.upvalues[arg]));
                    break;
                case GET_GLOBAL:
                    push(globals.get((String) constants[arg]));
                    break;
                case SET_GLOBAL:
                    globals.put((String) constants[arg], pop());
                    break;
                case DEFINE_GLOBAL:
                    globals.put((String) constants[arg], pop());
                    break;
                case ADD:
                    apply(Operators::add);
                    break;
                case CONCAT:
                    apply(Operators::concat);
                    break;
                case SUB:
                    apply(Operators::sub);
                    break;
                case MUL:
                    apply(Operators::mul);
                    break;
                case DIV:
                    apply(Operators::div);
                    break;
                case MOD:
                    apply(Operators::mod);
                    break;
                case POW:
                    apply(Operators::pow);
                    break;
                case NEG:
                    push(Operators.neg(pop()));
                    break;
                case NOT:
                    push(!Values.truthy(pop()));
                    break;
                case EQ:
                    push(compare(EQ));
                    break;
                case NE:
                    push(compare(NE));
                    break;
                case LT:
                    push(compare(LT));
                    break;
                case LE:
                    push(compare(LE));
                    break;
                case GT:
                    push(compare(GT));
                    break;
                case GE:
                    push(compare(GE));
                    break;
                case CONTAINS: {
                    // The container is popped first because it was pushed second.
                    Object container = pop();
                    push(Operators.contains(pop(), container));
                    break;
                }
                case IS_TYPE:
                    push(isType(pop(), (String) constants[arg]));
                    break;
                case AS_TYPE:
                    push(Values.convert(pop(), (String) constants[arg]));
                    break;
                case JMP:
                    frame.ip = arg;
                    break;
                case JMP_IF_FALSE:
                    if (!Values.truthy(pop())) {
                        frame.ip = arg;
                    }
                    break;
                case JMP_IF_TRUE:
                    if (Values.truthy(pop())) {
                        frame.ip = arg;
                    }
                    break;
                case JMP_IF_NOT_NULL:
                    if (pop() != null) {
                        frame.ip = arg;
                    }
                    break;
                case JMP_IF_NULL:
                    if (pop() == null) {
                        frame.ip = arg;
                    }
                    break;
                case IS_ABSENT:
                    push(Values.isAbsent(pop()));
                    break;
                case NEW_FUNCTION:
                    push(new MandelaClosure((CompiledFunction) constants[arg], NO_CAPTURES));
                    break;
                case NEW_CLOSURE:
                    push(newClosure(constants, arg));
                    break;
                case CALL:
                    callValue(arg, null);
                    break;
                case CALL_KW:
                    callKw(arg);
                    break;
                case CALL_METHOD:
                    callMember((String) constants[arg >>> 8], arg & 0xff);
                    break;
                case SUPER_CALL:
                    callSuper(frame, (String) constants[arg >>> 8], arg & 0xff);
                    break;
                case RETURN:
                    if (returnFrom(pop())) {
                        return;
                    }
                    break;
                case RETURN_NULL:
                    if (returnFrom(null)) {
                        return;
                    }
                    break;
                case MAKE_LIST:
                    makeList(arg);
                    break;
                case MAKE_MAP:
                    makeMap(arg);
                    break;
                case MAKE_RANGE:
                    makeRange(arg);
                    break;
                case GET_INDEX: {
                    Object key = pop();
                    Object target = pop();
                    push(Operators.index(target, key));
                    break;
                }
                case SET_INDEX: {
                    Object value = pop();
                    Object key = pop();
                    Object target = pop();
                    Operators.setIndex(target, key, value);
                    break;
                }
                case GET_MEMBER:
                    push(readMember(pop(), (String) constants[arg]));
                    break;
                case GET_FIELD:
                    push(readField(pop(), arg));
                    break;
                case SET_MEMBER: {
                    Object value = pop();
                    Object target = pop();
                    writeMember(target, (String) constants[arg], value);
                    break;
                }
                case INIT_FIELD: {
                    Object value = pop();
                    Object target = pop();
                    initField(target, (String) constants[arg], value);
                    break;
                }
                case LENGTH:
                    push(Operators.length(pop()));
                    break;
                case NEW_ITER:
                    push(MandelaIterator.of(pop()));
                    break;
                case ITER_NEXT:
                    push(iterator(peek()).advance());
                    break;
                case ITER_VALUE:
                    push(iterator(peek()).value());
                    break;
                case ITER_KV: {
                    MandelaIterator walked = iterator(peek());
                    push(walked.key());
                    push(walked.value());
                    break;
                }
                case MATCH_LIST: {
                    Object value = pop();
                    push(value instanceof MandelaList list && list.size() == arg);
                    break;
                }
                case ERROR_NO_MATCH: {
                    // No `break`: an unmatched pattern is an error, and the value
                    // it names is popped here so the stack cannot drift.
                    throw MandelaError.of("MatchError", "no pattern matched "
                            + Values.repr(pop()));
                }
                case DEFINE_CLASS:
                    push(defineClass((ClassDef) constants[arg]));
                    break;
                case THROW:
                    throw toError(pop(), frame);
                case ENTER_TRY:
                    frame.pushHandler(arg, sp);
                    break;
                case LEAVE_TRY:
                    frame.popHandlerTarget();
                    break;
                case POP_HANDLERS:
                    for (int i = 0; i < arg; i++) {
                        frame.popHandlerTarget();
                    }
                    break;
                case ADD_DEFER:
                    frame.addDefer((MandelaClosure) pop());
                    break;
                case RUN_DEFERS:
                    runDefers(frame);
                    break;
                case HALT:
                    // The host's own stop button: retire the value on top and end
                    // this run, which is what a debugger's "finish frame" needs.
                    lastReturn = (sp > 0) ? pop() : null;
                    current = null;
                    break;
                case NOOP:
                default:
                    break;
            }
        }
    }

    // -- operand stack --------------------------------------------------------

    private void push(Object value) {
        if (sp == stack.length) {
            Object[] bigger = new Object[stack.length * 2];
            System.arraycopy(stack, 0, bigger, 0, stack.length);
            stack = bigger;
        }
        stack[sp++] = value;
    }

    private Object pop() {
        if (sp == 0) {
            throw new IllegalStateException("the Mandela operand stack underflowed;"
                    + " the compiler emitted an instruction stream the machine"
                    + " cannot execute");
        }
        Object value = stack[--sp];
        stack[sp] = null;
        return value;
    }

    private Object peek() {
        return stack[sp - 1];
    }

    /** Pops {@code count} arguments so the result is in call order. */
    private Object[] popArgs(int count) {
        if (count == 0) {
            return NO_ARGS;
        }
        Object[] args = new Object[count];
        for (int i = count - 1; i >= 0; i--) {
            args[i] = pop();
        }
        return args;
    }

    private static Ref ref(Object cell) {
        if (cell instanceof Ref reference) {
            return reference;
        }
        throw new IllegalStateException("a captured variable lost its cell; slot"
                + " boxing and the machine disagree");
    }

    private static MandelaIterator iterator(Object value) {
        if (value instanceof MandelaIterator walked) {
            return walked;
        }
        throw new IllegalStateException("iteration found " + Values.typeName(value)
                + " where the compiler promised a cursor");
    }

    private static MandelaClosure asClosure(Callable callable) {
        if (callable instanceof MandelaClosure closure) {
            return closure;
        }
        throw MandelaError.type("'" + callable.name() + "' is a host function and"
                + " cannot be entered as a frame");
    }

    private void requireBuiltins(String keyword) {
        if (builtins == null) {
            throw MandelaError.of("'" + keyword + "' needs a host that offers"
                    + " modules; this runtime does not");
        }
    }

    // -- operators ------------------------------------------------------------

    /** Pops two values and pushes the operator's result. */
    private void apply(BinOp op) {
        Object right = pop();
        Object left = pop();
        push(op.apply(left, right));
    }

    /**
     * Evaluates one of the six comparisons.
     *
     * <p>{@code ==} and {@code !=} are total &mdash; any two values may be
     * compared &mdash; while the ordering operators go through
     * {@link Values#compare}, which names the two types it refused to order.</p>
     *
     * @param mode one of {@link #EQ}, {@link #NE}, {@link #LT}, {@link #LE},
     *             {@link #GT}, {@link #GE}
     * @return the boolean the instruction pushes
     */
    private boolean compare(int mode) {
        Object right = pop();
        Object left = pop();
        if (mode == EQ) {
            return Values.equal(left, right);
        }
        if (mode == NE) {
            return !Values.equal(left, right);
        }
        int order = Values.compare(left, right);
        return switch (mode) {
            case LT -> order < 0;
            case LE -> order <= 0;
            case GT -> order > 0;
            default -> order >= 0;
        };
    }

    /**
     * {@code value is Name}, and the type filter on a {@code catch} clause.
     *
     * <p>The builtin type names are tried first, then a declared class &mdash;
     * where the test must also accept subclasses. An error is a special case: it
     * is not an instance of anything, so a clause reads its {@code kind}, and
     * {@code catch e: Error} is the clause that means "any error".</p>
     */
    private boolean isType(Object value, String name) {
        if (Values.isType(value, name)) {
            return true;
        }
        if (value instanceof MandelaError error) {
            return error.kind().equals(name) || MandelaError.ERROR.equals(name);
        }
        return globals.get(name) instanceof MandelaClass declared
                && Values.isInstanceOf(value, declared);
    }

    // -- collections ----------------------------------------------------------

    private void makeList(int count) {
        Object[] items = popArgs(count);
        MandelaList list = new MandelaList(count);
        for (Object item : items) {
            list.add(item);
        }
        push(list);
    }

    private void makeMap(int count) {
        Object[] flat = popArgs(count * 2);
        MandelaMap map = new MandelaMap(count);
        for (int i = 0; i < flat.length; i += 2) {
            map.put(keyName(flat[i]), flat[i + 1]);
        }
        push(map);
    }

    /**
     * The name a map key is stored under. Mandela maps are keyed by name, the way
     * JSON objects and every desktop config file are, so a scalar is accepted only
     * where its text form is unambiguous.
     */
    private static String keyName(Object key) {
        if (key instanceof String text) {
            return text;
        }
        if (key instanceof Long || key instanceof Double || key instanceof Boolean) {
            return Values.display(key);
        }
        throw MandelaError.value("a map key must be a Str, an Int or a Bool; a "
                + Values.typeName(key) + " cannot name an entry");
    }

    private void makeRange(int exclusive) {
        Object stride = pop();
        Object to = pop();
        Object from = pop();
        long start = Values.asInt(from);
        long end = Values.asInt(to);
        long step = Values.asInt(stride);
        if (step == 0L) {
            // Checked here rather than trusted: the compiler cannot see what a
            // variable used as a stride holds at run time, and a zero stride would
            // otherwise turn every range walk into an endless loop.
            throw MandelaError.range("a range 'step' must not be 0, because no value"
                    + " is ever reached by adding 0 to the bounds");
        }
        push(MandelaRange.strided(start, end, exclusive == 1, step));
    }

    // -- calls ----------------------------------------------------------------

    /** Builds the closure a {@code NEW_CLOSURE} operand describes. */
    private MandelaClosure newClosure(Object[] constants, int operand) {
        CompiledFunction function = (CompiledFunction) constants[operand >>> 8];
        int count = operand & 0xff;
        Object[] cells = popArgs(count);
        return new MandelaClosure(function, cells);
    }

    /**
     * Calls the value under {@code argc} arguments.
     *
     * <p>A script closure becomes a frame and the loop carries on &mdash; that is
     * what keeps deep recursion cheap in Java frames. A host function or a class
     * is settled here and now, so the result is already on the stack when the next
     * instruction fetches.</p>
     *
     * @param argc  the argument count from the operand
     * @param names the named-argument labels, or null for a positional call
     */
    private void callValue(int argc, String[] names) {
        Object[] args = popArgs(argc);
        Object callee = pop();
        invokeTarget(callee, null, args, names);
    }

    /** Calls with named arguments, whose label array sits under the callee. */
    private void callKw(int argc) {
        Object[] args = popArgs(argc);
        Object callee = pop();
        Object labels = pop();
        if (!(labels instanceof String[] names)) {
            throw new IllegalStateException("a named call carried "
                    + Values.typeName(labels) + " where the compiler promised labels");
        }
        invokeTarget(callee, null, args, names);
    }

    /** Runs {@code receiver.name(args)}. */
    private void callMember(String name, int argc) {
        Object[] args = popArgs(argc);
        Object receiver = pop();
        if (receiver instanceof MandelaInstance instance) {
            Callable method = instance.owner().lookup(name);
            if (method != null) {
                invokeTarget(method, instance, args, null);
                return;
            }
        }
        Object member = readMember(receiver, name);
        if (member instanceof Callable callable) {
            // A field holding a function, or a built-in member: both are settled
            // outside the loop, because a built-in may not need a frame at all.
            push(invoke(callable, receiver, args));
            return;
        }
        if (args.length == 0) {
            push(member);
            return;
        }
        throw MandelaError.type("'" + name + "' on a " + Values.typeName(receiver)
                + " is a value, not a function; it cannot take " + args.length
                + " argument(s)");
    }

    /** Runs {@code super.name(args)} from the base class the frame recorded. */
    private void callSuper(Frame frame, String name, int argc) {
        Object[] args = popArgs(argc);
        Object self = frame.slots[0];
        if (!(frame.slots[1] instanceof MandelaClass base)) {
            throw MandelaError.value("'super' has no base class to search here"
                    + " ('" + frame.name() + "' is a method of a type that extends"
                    + " nothing)");
        }
        Callable method = base.lookup(name);
        if (method == null) {
            throw MandelaError.type("'" + base.name() + "' has no method named '"
                    + name + "' for 'super' to reach");
        }
        invokeTarget(method, self, args, null);
    }

    /**
     * Dispatches one resolved callee.
     *
     * @param callee   the value the call site named
     * @param receiver the instance for a method, or null
     * @param args     the evaluated arguments, in order
     * @param names    the named-argument labels, or null
     */
    private void invokeTarget(Object callee, Object receiver, Object[] args, String[] names) {
        if (callee instanceof MandelaClosure closure) {
            if (names != null) {
                throw MandelaError.type("named arguments are only accepted when"
                        + " constructing a 'class' or a 'type'");
            }
            enterFrame(closure, receiver, args);
            return;
        }
        if (callee instanceof HostFunction host) {
            if (names != null) {
                throw MandelaError.type("the host function '" + host.name()
                        + "' cannot be called with named arguments");
            }
            push(callHost(host, args));
            return;
        }
        if (callee instanceof MandelaClass declared) {
            construct(declared, args, names);
            return;
        }
        throw MandelaError.type("cannot call a " + Values.typeName(callee) + ";"
                + " only a function, a method or a declared type can be called");
    }

    /**
     * Builds one instance of a declared type.
     *
     * <p>A synthesised initialiser ends by returning {@code this}, so entering its
     * frame is the whole construction and the instance reaches the stack through
     * the normal return path. A type with no initialiser at all is pushed here.</p>
     */
    private void construct(MandelaClass declared, Object[] args, String[] names) {
        if (declared.kind() == MandelaClass.Kind.ENUM) {
            throw MandelaError.value("'" + declared.name() + "' is an enum; its"
                    + " constants are already built, so it cannot be constructed");
        }
        Object[] positional = (names == null) ? args : position(declared, args, names);
        if (positional.length < declared.arity() || positional.length > declared.declaredArity()) {
            throw MandelaError.value("'" + declared.name() + "' takes"
                    + " " + declared.declaredArity() + " argument(s)"
                    + (declared.arity() == declared.declaredArity() ? ""
                            : " (as few as " + declared.arity() + " with defaults)")
                    + "; the call gave " + positional.length);
        }
        MandelaInstance instance = new MandelaInstance(declared);
        Callable init = declared.lookup("init");
        if (init == null) {
            if (positional.length > 0) {
                throw MandelaError.value("'" + declared.name() + "' takes no arguments");
            }
            push(instance);
            return;
        }
        if (init instanceof MandelaClosure ctor) {
            enterFrame(ctor, instance, positional);
            return;
        }
        Object built = invoke(init, instance, positional);
        push(built == null ? instance : built);
    }

    /**
     * Turns named arguments into the positional order the initialiser expects.
     *
     * <p>Names are matched against the declared fields, so an argument left out
     * lands as {@link Values#ABSENT} and the body's default expression fills it
     * exactly as a short positional call would.</p>
     */
    private static Object[] position(MandelaClass declared, Object[] args, String[] names) {
        if (names.length != args.length) {
            throw new IllegalStateException("a named call gave " + args.length
                    + " argument(s) for " + names.length + " label(s)");
        }
        List<String> fields = declared.fieldNames();
        Object[] positional = new Object[args.length];
        java.util.Arrays.fill(positional, Values.ABSENT);
        for (int i = 0; i < names.length; i++) {
            int at = fields.indexOf(names[i]);
            if (at < 0) {
                throw MandelaError.key("'" + declared.name() + "' has no field named '"
                        + names[i] + "'; the declared fields are " + fields);
            }
            if (at < positional.length && positional[at] != Values.ABSENT) {
                throw MandelaError.value("'" + names[i] + "' was given twice");
            }
            if (at >= positional.length) {
                Object[] widened = new Object[at + 1];
                java.util.Arrays.fill(widened, Values.ABSENT);
                System.arraycopy(positional, 0, widened, 0, positional.length);
                positional = widened;
            }
            positional[at] = args[i];
        }
        return positional;
    }

    /** Calls a host function, turning its failure into a catchable error. */
    private Object callHost(HostFunction host, Object[] args) {
        int declared = host.arity();
        if (declared >= 0 && args.length != declared) {
            throw MandelaError.value("'" + host.name() + "' takes " + declared
                    + " argument(s); the call gave " + args.length);
        }
        try {
            return host.apply(new ArrayList<>(java.util.Arrays.asList(args)));
        } catch (MandelaError error) {
            // A host function that raised a Mandela error (a permission refusal,
            // most often) passes it through untouched so the script's own `catch`
            // sees the kind the author was promised.
            throw error.line() > 0 ? error : error.at(sourceOf(), lineOf());
        } catch (RuntimeException failure) {
            throw MandelaError.runtime("the host function '" + host.name() + "' failed: "
                    + failure.getMessage(), failure).at(sourceOf(), lineOf());
        }
    }

    private String sourceOf() {
        return (current == null) ? null : current.sourceName();
    }

    private int lineOf() {
        return (current == null) ? 0 : current.line();
    }

    // -- members --------------------------------------------------------------

    /** Reads {@code target.name} as written in the script. */
    private Object readMember(Object target, String name) {
        if (target instanceof MandelaInstance instance) {
            if (instance.has(name)) {
                return instance.get(name);
            }
            Callable method = instance.owner().lookup(name);
            if (method != null) {
                // `obj.method` without a call is a real value: a callback to hand
                // to a built-in. Binding it costs one small object.
                return (method instanceof MandelaClosure closure)
                        ? closure.bindTo(instance) : method;
            }
            return builtinMember(target, name);
        }
        if (target instanceof MandelaError error) {
            // An error is a value the script holds, and its report is read with
            // `err.message` / `err.kind`, so the four fields are answered here
            // rather than by a host that may not have a standard library.
            switch (name) {
                case "message":
                    return error.getMessage();
                case "kind":
                    return error.kind();
                case "line":
                    return (long) error.line();
                case "source":
                    return error.sourceName();
                default:
                    return builtinMember(target, name);
            }
        }
        if (target instanceof MandelaClass declared) {
            MandelaInstance constant = declared.constants().get(name);
            if (constant != null) {
                return constant;
            }
            if (declared.lookup(name) != null) {
                throw MandelaError.type("'" + declared.name() + "." + name + "' is a"
                        + " method; call it on an instance, not on the type");
            }
            return builtinMember(target, name);
        }
        return builtinMember(target, name);
    }

    private Object builtinMember(Object target, String name) {
        if (builtins != null) {
            Object found = builtins.member(this, target, name);
            if (found != null) {
                return found;
            }
        }
        throw MandelaError.type("'" + Values.typeName(target) + "' has no member named '"
                + name + "'");
    }

    /**
     * Fills one field of a value that is still under construction.
     *
     * <p>This is the only path allowed to write a {@code type} record, and only a
     * synthesised initialiser reaches it; script code goes through
     * {@link #writeMember}, which keeps the immutability guard.</p>
     */
    private static void initField(Object target, String name, Object value) {
        if (target instanceof MandelaInstance instance) {
            instance.set(name, value);
            return;
        }
        throw MandelaError.type("the initialiser of '" + name + "' wrote onto a "
                + Values.typeName(target) + ", which holds no fields");
    }

    /**
     * Reads one declared field of an instance by position.
     *
     * <p>A {@code Type(a, b)} pattern binds under whatever names the script chose,
     * so the only stable way to fetch the parts is the order the type declared them
     * in &mdash; the same order a construction fills.</p>
     */
    private static Object readField(Object target, int index) {
        if (target instanceof MandelaInstance instance) {
            List<String> names = instance.owner().fieldNames();
            if (index < 0 || index >= names.size()) {
                throw MandelaError.type("'" + instance.typeName() + "' declares "
                        + names.size() + " field(s), so it has no field number " + index);
            }
            return instance.get(names.get(index));
        }
        throw MandelaError.type("cannot read field " + index + " of a "
                + Values.typeName(target) + "; a 'Type(...)' pattern needs an instance");
    }

    /** Assigns {@code target.name = value}. */
    private void writeMember(Object target, String name, Object value) {
        if (target instanceof MandelaInstance instance) {
            if (instance.owner().kind() == MandelaClass.Kind.TYPE) {
                throw MandelaError.value("'" + instance.typeName() + "' is a 'type'"
                        + " record and cannot be changed; build a new one instead");
            }
            instance.set(name, value);
            return;
        }
        if (target instanceof MandelaClass) {
            throw MandelaError.type("the members of the type '"
                    + ((MandelaClass) target).name() + "' are not writable;"
                    + " change an instance");
        }
        throw MandelaError.type("cannot assign to '" + name + "' on a "
                + Values.typeName(target));
    }

    // -- types ----------------------------------------------------------------

    /**
     * Realises one {@code DEFINE_CLASS} constant: resolve the base, install the
     * methods as closures that know their declaring class, run the synthesised
     * constructor, and register the enum constants.
     */
    private MandelaClass defineClass(ClassDef def) {
        MandelaClass parent = null;
        if (def.parentName() != null && !def.parentName().isEmpty()) {
            Object named = globals.get(def.parentName());
            if (!(named instanceof MandelaClass declared)) {
                throw MandelaError.type("'" + def.name() + "' extends '"
                        + def.parentName() + "', which is not a type in scope at"
                        + " that point in the script");
            }
            parent = declared;
        }
        MandelaClass created = new MandelaClass(def.kind(), def.name(), parent,
                def.fieldNames(), def.minArgs());
        for (Map.Entry<String, CompiledFunction> method : def.methods().entrySet()) {
            created.define(method.getKey(),
                    new MandelaClosure(method.getValue(), NO_CAPTURES, created));
        }
        if (def.constructor() != null) {
            created.define("init", new MandelaClosure(def.constructor(), NO_CAPTURES, created));
        }
        for (int i = 0; i < def.constants().size(); i++) {
            MandelaInstance constant = new MandelaInstance(created);
            constant.set("name", def.constants().get(i));
            constant.set("ordinal", (long) i);
            created.defineConstant(def.constants().get(i), constant);
        }
        return created;
    }

    // -- errors ---------------------------------------------------------------

    /**
     * Turns a thrown value into the error a {@code catch} receives.
     *
     * <p>{@code throw} of an already-built error keeps its kind and message; an
     * instance of a user type deriving from {@code Error} reports its own type name
     * and its {@code message} field; anything else becomes a plain {@code Error}
     * whose text is the value's display form, which is what lets an author write
     * {@code throw "no config file"} during development.</p>
     */
    private static MandelaError toError(Object value, Frame frame) {
        String source = frame.sourceName();
        int line = frame.line();
        if (value instanceof MandelaError error) {
            return (error.sourceName() == null) ? error.at(source, line) : error;
        }
        if (value instanceof MandelaInstance instance) {
            Object message = instance.get("message");
            return new MandelaError(instance.typeName(),
                    (message == null) ? Values.display(instance) : Values.display(message),
                    source, line);
        }
        if (value instanceof MandelaClass declared) {
            return new MandelaError(declared.name(), "the type '" + declared.name()
                    + "' was thrown without being constructed", source, line);
        }
        return new MandelaError(MandelaError.ERROR, Values.display(value), source, line);
    }
}
