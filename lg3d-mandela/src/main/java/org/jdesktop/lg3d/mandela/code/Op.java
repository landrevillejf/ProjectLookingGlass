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

/**
 * The Mandela bytecode: 66 instructions of a two-int-per-slot stack machine.
 *
 * <p>Every instruction occupies two entries of a {@code int[]}: the opcode and
 * one operand. A wider operand (a constant index, a jump target, an argument
 * count) always fits in one {@code int}, so the fetch is {@code code[ip]} and
 * {@code code[ip + 1]} with {@code ip += 2} &mdash; no varint decoding in the
 * hot loop, and the disassembler is a two-line walk.</p>
 *
 * <p>Operands are absolute code indices for jumps and constant-pool indices for
 * literals, which keeps the VM free of arithmetic on the fly. Instructions that
 * the compiler could just as well have inlined (compound assignment, string
 * concatenation chains) are inlined by the compiler, so the VM stays small and
 * each opcode has one obvious job.</p>
 *
 * <p>Ordering of the group comments is the order the reference table in the
 * language guide prints them, so the two stay in step.</p>
 */
public enum Op {

    // -- literals and the stack --------------------------------------------

    /** Push {@code constants[arg]}. */
    LOAD_CONST,
    /** Drop the top value. */
    POP,
    /** Duplicate the top value. */
    DUP,
    /** Push the enclosing instance ({@code this}); arg is the slot holding it. */
    LOAD_THIS,

    // -- variables ----------------------------------------------------------

    /** Push the local in slot {@code arg}. */
    GET_LOCAL,
    /** Pop and store into the local in slot {@code arg}. */
    SET_LOCAL,
    /** Push the value of the captured local in slot {@code arg} (a {@code Ref}). */
    GET_LOCAL_BOX,
    /** Pop and store into the captured local in slot {@code arg}. */
    SET_LOCAL_BOX,
    /** Push the value of captured upvalue {@code arg}. */
    GET_UPVALUE,
    /** Pop and store into captured upvalue {@code arg}. */
    SET_UPVALUE,
    /** Push the {@code Ref} of a captured local, to hand to a new closure. */
    CAPTURE_LOCAL,
    /** Push upvalue {@code arg}'s {@code Ref} unchanged, to re-capture it. */
    CAPTURE_UPVALUE,
    /** Push the global named by constant {@code arg}, or null when unset. */
    GET_GLOBAL,
    /** Pop and bind the global named by constant {@code arg}. */
    SET_GLOBAL,
    /**
     * Pop and declare a global, recording the name in the module's export list
     * when the declaration site said {@code export}.
     */
    DEFINE_GLOBAL,

    // -- arithmetic ---------------------------------------------------------

    /** {@code +}: number addition or string / list concatenation. */
    ADD,
    /**
     * Join two values as text, rendering each with its display form. Interpolation
     * compiles to this and not to {@code ADD}, so {@code "${n}"} accepts any value
     * while {@code "a" + n} stays the type error it should be.
     */
    CONCAT,
    /** {@code -}. */
    SUB,
    /** {@code *}: number product or string repeat. */
    MUL,
    /** {@code /}: true division, always a Double unless both operands are Int. */
    DIV,
    /** {@code %}: remainder. */
    MOD,
    /** {@code **}: power. */
    POW,
    /** Numeric negation. */
    NEG,
    /** Logical negation of truthiness. */
    NOT,

    // -- comparison and tests ----------------------------------------------

    /** {@code ==} structural equality. */
    EQ,
    /** {@code !=}. */
    NE,
    /** {@code <}. */
    LT,
    /** {@code <=}. */
    LE,
    /** {@code >}. */
    GT,
    /** {@code >=}. */
    GE,
    /** {@code is}: push true when the value answers to type name {@code arg}. */
    IS_TYPE,
    /** {@code value in container}: push the Bool membership answer. */
    CONTAINS,
    /** Convert the top value to type name {@code arg} ({@code as}). */
    AS_TYPE,

    // -- jumps --------------------------------------------------------------

    /** Jump to {@code arg}. */
    JMP,
    /** Pop and jump to {@code arg} when the value is false. */
    JMP_IF_FALSE,
    /** Pop and jump to {@code arg} when the value is true. */
    JMP_IF_TRUE,
    /** Pop and jump to {@code arg} when the value is not null. */
    JMP_IF_NOT_NULL,
    /** Pop and jump to {@code arg} when the value is null. */
    JMP_IF_NULL,

    // -- functions ----------------------------------------------------------

    /** Push true when the top value is an argument the caller left unfilled. */
    IS_ABSENT,
    /** Push a closure over function constant {@code arg} with no captures. */
    NEW_FUNCTION,
    /**
     * Build a closure over function constant {@code arg >> 8}, consuming the
     * {@code arg & 0xff} capture cells just pushed.
     */
    NEW_CLOSURE,
    /** Call the value under {@code arg} arguments. */
    CALL,
    /**
     * Call with named arguments: the names array ({@code String[]}) sits under
     * the callee on the stack, so the layout is {@code names, callee, args...}.
     * Only construction of a {@code class} or {@code type} accepts names.
     */
    CALL_KW,
    /** Return the top value, running pending {@code defer} bodies first. */
    RETURN,
    /** Return without a value (null). */
    RETURN_NULL,

    // -- collections --------------------------------------------------------

    /** Build a list from the {@code arg} values just pushed, in order. */
    MAKE_LIST,
    /** Build a map from the {@code arg} key/value pairs just pushed. */
    MAKE_MAP,
    /**
     * Build the range {@code from..to step stride} from the three values just
     * pushed; arg 1 marks the exclusive {@code ..<} form.
     */
    MAKE_RANGE,
    /** Index: push {@code container[index]}. */
    GET_INDEX,
    /** Assign: {@code container[index] = value} for the three pushed values. */
    SET_INDEX,
    /** Push {@code target.name(args)} for the packed name / argument-count operand. */
    CALL_METHOD,
    /** Push {@code target.name} for the constant name {@code arg}. */
    GET_MEMBER,
    /**
     * Push the declared field at position {@code arg} of an instance. Destructuring
     * a {@code Type(a, b)} pattern reads by position, because the names written in
     * the pattern are the new bindings, not the type's fields.
     */
    GET_FIELD,
    /** Assign {@code target.name = value}. */
    SET_MEMBER,
    /**
     * Fill one field of a value under construction: {@code target.name = value}
     * with the record guard off. Only a synthesised initialiser emits this, which
     * is what lets a {@code type} record be built field by field and stay
     * unchangeable afterwards.
     */
    INIT_FIELD,
    /** Length of a string, list, map, range or instance. */
    LENGTH,

    // -- iteration ----------------------------------------------------------

    /** Replace the top value with an iterator over it (list, map, range, string). */
    NEW_ITER,
    /** Push true when the iterator on top of the stack has another element. */
    ITER_NEXT,
    /** Push the iterator's next element, leaving the iterator on the stack. */
    ITER_VALUE,
    /** Push a map iterator's next key then its value, leaving it on the stack. */
    ITER_KV,

    // -- classes ------------------------------------------------------------

    /** Create the class described by constant {@code arg} and push it. */
    DEFINE_CLASS,
    /** {@code super.name(args)}: search above {@code this}'s own class. */
    SUPER_CALL,

    // -- errors, control and housekeeping -----------------------------------

    /** Throw the top value as a Mandela error. */
    THROW,
    /**
     * Pop a value and push true when it is a list of exactly {@code arg}
     * elements. The test is destructive: the compiler keeps the sequence in a
     * slot, so the bindings reload the parts from there and only the failure
     * path needs the value back.
     */
    MATCH_LIST,
    /** Pop the value, wrap it in a {@code MatchError} and throw it. */
    ERROR_NO_MATCH,
    /** Install the catch handler at {@code arg} and record the stack depth. */
    ENTER_TRY,
    /** Remove the innermost handler, taken normally. */
    LEAVE_TRY,
    /** Drop {@code arg} handlers left over by a break or continue. */
    POP_HANDLERS,
    /** Push the top closure onto the frame's defer list. */
    ADD_DEFER,
    /** Run and clear the frame's defer list (unwinding an error). */
    RUN_DEFERS,
    /** Stop the machine and hand {@code arg}'s value back to the host. */
    HALT,

    /** Assert-free no-op kept for the debugger and for padding. */
    NOOP;

    /** Total opcode count, used to size lookup tables. */
    public static final int COUNT = values().length;

    /** @return the opcode with the given id */
    public static Op byId(int id) {
        if (id < 0 || id >= COUNT) {
            throw new IllegalStateException("unknown opcode " + id);
        }
        return values()[id];
    }

    /** @return the instruction's second word: its operand's meaning, for dumps */
    public String operand() {
        switch (this) {
            case LOAD_CONST:
            case NEW_FUNCTION:
            case NEW_CLOSURE:
            case DEFINE_CLASS:
                return "const";
            case IS_ABSENT:
                return "-";
            case GET_LOCAL:
            case SET_LOCAL:
            case GET_LOCAL_BOX:
            case SET_LOCAL_BOX:
            case CAPTURE_LOCAL:
            case LOAD_THIS:
                return "slot";
            case GET_UPVALUE:
            case SET_UPVALUE:
            case CAPTURE_UPVALUE:
                return "upvalue";
            case GET_GLOBAL:
            case SET_GLOBAL:
            case DEFINE_GLOBAL:
            case GET_MEMBER:
            case SET_MEMBER:
            case INIT_FIELD:
            case IS_TYPE:
            case AS_TYPE:
                return "name";
            case CALL_METHOD:
            case SUPER_CALL:
                return "name<<8|argc";
            case JMP:
            case JMP_IF_FALSE:
            case JMP_IF_TRUE:
            case JMP_IF_NOT_NULL:
            case JMP_IF_NULL:
            case ENTER_TRY:
                return "target";
            case CALL:
            case MAKE_LIST:
            case MAKE_MAP:
            case MATCH_LIST:
            case POP_HANDLERS:
                return "count";
            case NEW_ITER:
            case ITER_NEXT:
            case ITER_VALUE:
            case ITER_KV:
            case GET_FIELD:
            case ERROR_NO_MATCH:
                return "-";
            default:
                return "-";
        }
    }
}
