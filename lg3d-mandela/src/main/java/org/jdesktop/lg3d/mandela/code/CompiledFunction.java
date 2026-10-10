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
package org.jdesktop.lg3d.mandela.code;

import java.util.ArrayList;
import java.util.List;

/**
 * One compiled function: the instruction array, the constant pool, and the
 * shape of the frame it needs.
 *
 * <p>Everything the machine must know before it runs a body is settled at
 * compile time &mdash; how many slots, which of them are boxed cells, how many
 * captures the closure takes, what the minimum argument count is once defaults
 * are counted. The interpreter loop therefore contains no analysis, only
 * fetch/decode/execute.</p>
 *
 * <p>{@link Builder} is the compiler's scratch space; a built function is
 * immutable and shared, since one {@code CompiledFunction} backs every closure
 * made from the same {@code fun} declaration.</p>
 */
public final class CompiledFunction {

    private final String name;
    private final String sourceName;
    private final int line;
    private final int arity;
    private final int minArgs;
    private final int localsCount;
    private final int upvalueCount;
    private final int[] boxedSlots;
    private final boolean isMethod;
    private final int[] code;
    private final int[] lines;
    private final Object[] constants;

    private CompiledFunction(Builder b) {
        this.name = b.name;
        this.sourceName = b.sourceName;
        this.line = b.line;
        this.arity = b.arity;
        this.minArgs = b.minArgs;
        this.localsCount = b.localsCount;
        this.upvalueCount = b.upvalueCount;
        this.isMethod = b.isMethod;
        this.boxedSlots = b.boxedSlots.stream().mapToInt(Integer::intValue).toArray();
        this.code = new int[b.code.size()];
        for (int i = 0; i < b.code.size(); i++) {
            this.code[i] = b.code.get(i);
        }
        this.lines = new int[b.lines.size()];
        for (int i = 0; i < b.lines.size(); i++) {
            this.lines[i] = b.lines.get(i);
        }
        this.constants = b.constants.toArray();
    }

    /** @return the declared name, empty for a lambda */
    public String name() {
        return name;
    }

    /** @return the script this body came from */
    public String sourceName() {
        return sourceName;
    }

    /** @return the 1-based line of the declaration */
    public int line() {
        return line;
    }

    /** @return the declared parameter count */
    public int arity() {
        return arity;
    }

    /** @return the fewest arguments a call may pass once defaults are counted */
    public int minArgs() {
        return minArgs;
    }

    /** @return true when a call may pass anywhere between {@link #minArgs()} and {@link #arity()} */
    public boolean acceptsAnyArgs() {
        return arity != minArgs;
    }

    /** @return the number of local slots a frame needs, parameter slots included */
    public int localsCount() {
        return localsCount;
    }

    /** @return how many captured cells a closure of this body must supply */
    public int upvalueCount() {
        return upvalueCount;
    }

    /**
     * @return true when this body is a method or constructor, whose frame carries
     *         {@code this} in slot 0 and the base-class view in slot 1
     */
    public boolean isMethod() {
        return isMethod;
    }

    /** @return the slot indices that hold {@link org.jdesktop.lg3d.mandela.values.Ref} cells */
    public int[] boxedSlots() {
        return boxedSlots;
    }

    /** @return the instruction stream, opcode and operand interleaved */
    public int[] code() {
        return code;
    }

    /** @return the constant pool */
    public Object[] constants() {
        return constants;
    }

    /**
     * @param ip an instruction index (the opcode word)
     * @return the 1-based source line that produced it, or 0 when unknown
     */
    public int lineAt(int ip) {
        return (ip >= 0 && ip < lines.length) ? lines[ip] : 0;
    }

    /** @return the total instruction count, in words */
    public int size() {
        return code.length;
    }

    @Override
    public String toString() {
        return "<fun " + (name.isEmpty() ? "lambda" : name) + " " + arity
                + "/" + localsCount + " locals " + code.length / 2 + " instr>";
    }

    /**
     * Assembles one function body.
     *
     * <p>The builder owns the two things a compiler needs to patch: constant
     * indices (deduplicated, so {@code "x" == "x"} costs one pool slot) and jump
     * targets, which are written as absolute instruction indices once the end of
     * the construct is known.</p>
     */
    public static final class Builder {

        private final String name;
        private final String sourceName;
        private final int line;
        private final List<Integer> code = new ArrayList<>();
        private final List<Integer> lines = new ArrayList<>();
        private final List<Object> constants = new ArrayList<>();
        private final List<Integer> boxedSlots = new ArrayList<>();

        private boolean isMethod;
        private int arity;
        private int minArgs;
        private int localsCount;
        private int upvalueCount;

        /** Creates an empty body for the given declaration. */
        public Builder(String name, String sourceName, int line) {
            this.name = (name == null) ? "" : name;
            this.sourceName = (sourceName == null) ? "<script>" : sourceName;
            this.line = Math.max(0, line);
        }

        /** Appends an instruction with no operand. */
        public void emit(Op op, int lineNo) {
            emit(op, 0, lineNo);
        }

        /** Appends an instruction with an operand. */
        public void emit(Op op, int operand, int lineNo) {
            code.add(op.ordinal());
            code.add(operand);
            lines.add(lineNo);
            lines.add(0);
        }

        /** @return the current write position, usable as a jump target */
        public int position() {
            return code.size();
        }

        /** Rewrites the operand of the instruction at {@code ip}. */
        public void patch(int ip, int target) {
            code.set(ip + 1, target);
        }

        /** @return the operand of the instruction at {@code ip} */
        public int operandAt(int ip) {
            return code.get(ip + 1);
        }

        /** @return the index of {@code value} in the constant pool, adding it when new */
        public int constant(Object value) {
            for (int i = 0; i < constants.size(); i++) {
                if (java.util.Objects.equals(constants.get(i), value)) {
                    return i;
                }
            }
            constants.add(value);
            return constants.size() - 1;
        }

        /** @return the number of constant-pool entries so far, for debugging */
        public int constantCount() {
            return constants.size();
        }

        /** Marks the body as a method or constructor frame. */
        public void markMethod() {
            this.isMethod = true;
        }

        /** Records the declared parameter counts. */
        public void params(int declared, int required) {
            this.arity = declared;
            this.minArgs = Math.min(declared, required);
        }

        /** Records how many slots and captures the frame needs. */
        public void frame(int slots, int captures) {
            this.localsCount = slots;
            this.upvalueCount = captures;
        }

        /** Marks a slot as holding a captured variable's cell. */
        public void box(int slot) {
            if (!boxedSlots.contains(slot)) {
                boxedSlots.add(slot);
            }
        }

        /**
         * Boxes {@code slot} for the frame and converts every read and write of
         * it emitted so far into the cell form.
         *
         * <p>Capture is discovered later than the declaration that causes it --
         * the closure that captures {@code i} is compiled after the {@code let i}
         * that introduces it -- so the instruction already in the stream has to
         * change. Rewriting is safe because only the two local-access opcodes
         * carry a slot operand.</p>
         */
        public void boxSlot(int slot) {
            box(slot);
            int plainGet = Op.GET_LOCAL.ordinal();
            int boxedGet = Op.GET_LOCAL_BOX.ordinal();
            int plainSet = Op.SET_LOCAL.ordinal();
            int boxedSet = Op.SET_LOCAL_BOX.ordinal();
            for (int i = 0; i < code.size(); i += 2) {
                if (code.get(i + 1) != slot) {
                    continue;
                }
                int op = code.get(i);
                if (op == plainGet) {
                    code.set(i, boxedGet);
                } else if (op == plainSet) {
                    code.set(i, boxedSet);
                }
            }
        }

        /** @return the frame slot count */
        public int locals() {
            return localsCount;
        }

        /** @return the body assembled so far */
        public CompiledFunction build() {
            return new CompiledFunction(this);
        }
    }
}
