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

import org.jdesktop.lg3d.mandela.code.CompiledFunction;
import org.jdesktop.lg3d.mandela.values.MandelaClosure;

/**
 * One activation of a Mandela body.
 *
 * <p>A frame owns its slots (parameters, locals and the two machine slots of a
 * method), its capture cells, its instruction pointer, the {@code try} handlers
 * currently installed and the {@code defer} bodies still owed. The operand stack
 * is <em>not</em> per frame: frames share the machine's stack and record where
 * theirs starts, which is what makes an unwind a single assignment to the stack
 * pointer instead of a per-frame cleanup.</p>
 *
 * <p>Frames are chained rather than kept in a collection, so a stack trace is a
 * walk and a call is one allocation. Nothing here is thread-safe by design; one
 * {@link VM} runs one script on one thread.</p>
 */
public final class Frame {

    /** The body being executed. */
    final CompiledFunction function;

    /** Parameters, locals and temporaries. A captured local holds a {@code Ref}. */
    final Object[] slots;

    /** The capture cells supplied by the enclosing closures. */
    final Object[] upvalues;

    /** The closure being run, or null for a bare function or the script frame. */
    final MandelaClosure closure;

    /** The activation to come back to, or null at the bottom. */
    final Frame caller;

    /** Where this frame's operand values start on the machine's stack. */
    final int stackBase;

    /** The source line of the call that made this frame, for stack traces. */
    final int callLine;

    /** The next instruction word to fetch. */
    int ip;

    /** How many {@code try} handlers are installed in this frame. */
    int handlers;

    /** The catch target of each installed handler. */
    int[] handlerTargets = new int[2];

    /** The stack depth each installed handler restores. */
    int[] handlerBases = new int[2];

    /** The {@code defer} bodies still owed, innermost-last filled first. */
    MandelaClosure[] defers = new MandelaClosure[2];

    /** How many defer bodies are recorded. */
    int deferCount;

    /** Frames between here and the bottom, for the depth limit and traces. */
    final int depth;

    Frame(CompiledFunction function, Object[] slots, Object[] upvalues,
          MandelaClosure closure, Frame caller, int stackBase, int callLine) {
        this.function = function;
        this.slots = slots;
        this.upvalues = upvalues;
        this.closure = closure;
        this.caller = caller;
        this.stackBase = stackBase;
        this.callLine = callLine;
        this.depth = (caller == null) ? 1 : caller.depth + 1;
    }

    /** @return the function name, empty for a lambda */
    public String name() {
        return function.name();
    }

    /** @return the script this body came from */
    public String sourceName() {
        return function.sourceName();
    }

    /** @return the line the interpreter is about to execute */
    public int line() {
        return function.lineAt(ip - 2);
    }

    /** @return the number of frames below and including this one */
    public int depth() {
        return depth;
    }

    /** @return true when the frame owes at least one {@code defer} body */
    public boolean hasDefers() {
        return deferCount > 0;
    }

    /** Records one more {@code defer} body, growing the small array on demand. */
    void addDefer(MandelaClosure thunk) {
        if (deferCount == defers.length) {
            MandelaClosure[] bigger = new MandelaClosure[defers.length * 2];
            System.arraycopy(defers, 0, bigger, 0, defers.length);
            defers = bigger;
        }
        defers[deferCount++] = thunk;
    }

    /** Installs a {@code try} handler at {@code target}, restoring {@code base} on catch. */
    void pushHandler(int target, int base) {
        if (handlers == handlerTargets.length) {
            int[] biggerTarget = new int[handlerTargets.length * 2];
            int[] biggerBase = new int[handlerBases.length * 2];
            System.arraycopy(handlerTargets, 0, biggerTarget, 0, handlerTargets.length);
            System.arraycopy(handlerBases, 0, biggerBase, 0, handlerBases.length);
            handlerTargets = biggerTarget;
            handlerBases = biggerBase;
        }
        handlerTargets[handlers] = target;
        handlerBases[handlers] = base;
        handlers++;
    }

    /** Drops the innermost handler and returns its catch target, or -1 when none. */
    int popHandlerTarget() {
        if (handlers == 0) {
            return -1;
        }
        handlers--;
        int target = handlerTargets[handlers];
        handlerTargets[handlers] = 0;
        return target;
    }

    /**
     * The stack depth the innermost <em>installed</em> handler restores.
     *
     * <p>Read it before {@link #popHandlerTarget()}: the two arrays are indexed
     * together, and the target getter decrements the count, so asking afterwards
     * would name the enclosing {@code try}'s depth.</p>
     *
     * @return the depth to reset the operand stack to, or -1 when no handler is live
     */
    int peekHandlerBase() {
        return (handlers == 0) ? -1 : handlerBases[handlers - 1];
    }

    /** Erases every slot so a long-lived stack does not pin values the script dropped. */
    void clear() {
        java.util.Arrays.fill(slots, null);
        for (int i = 0; i < deferCount; i++) {
            defers[i] = null;
        }
        deferCount = 0;
    }

    @Override
    public String toString() {
        return "frame[" + (function.name().isEmpty() ? "lambda" : function.name())
                + " @" + ip + " depth " + depth + "]";
    }
}
