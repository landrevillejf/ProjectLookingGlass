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

import java.util.Arrays;
import java.util.Map;

/**
 * Turns compiled bodies into text: the tool behind {@code mandela dump}, the
 * editor's structure view and every bug report about an instruction stream.
 *
 * <p>The format is deliberately plain &mdash; one instruction per line with its
 * byte offset, its opcode, its decoded operand and the source line it came from
 * &mdash; so a diff of two dumps reads as a diff of code. Nested bodies are
 * printed after their parent, because a function's constants hold the lambdas it
 * declares and a {@link Op#DEFINE_CLASS} operand holds the whole class shape.</p>
 *
 * <p>Nothing here is used by the machine. It reads the same arrays the interpreter
 * does and changes none of them.</p>
 */
public final class Disassembler {

    private final StringBuilder out = new StringBuilder();

    private int section;

    private Disassembler() { }

    /**
     * Disassembles one body and everything reachable from its constant pool.
     *
     * @param function the body to print
     * @return the dump
     */
    public static String dump(CompiledFunction function) {
        Disassembler d = new Disassembler();
        d.function(function, "");
        return d.out.toString();
    }

    /**
     * Disassembles only the instruction stream, without the frame header; the
     * form a debugger prints next to the source.
     *
     * @param function the body to print
     * @return the dump
     */
    public static String codeOnly(CompiledFunction function) {
        Disassembler d = new Disassembler();
        d.instructions(function, "");
        return d.out.toString();
    }

    // -- bodies --------------------------------------------------------------

    private void function(CompiledFunction function, String indent) {
        section++;
        line(indent + "function " + label(function) + " {" );
        line(indent + "  name=" + quote(function.name()) + "  source="
                + function.sourceName() + ":" + function.line());
        line(indent + "  params=" + function.arity() + " required=" + function.minArgs()
                + " slots=" + function.localsCount() + " upvalues="
                + function.upvalueCount() + " method=" + function.isMethod()
                + " boxed=" + Arrays.toString(function.boxedSlots()));
        instructions(function, indent + "  ");
        // The bodies this one can build, printed in constant-pool order so the
        // NEW_FUNCTION / NEW_CLOSURE operand reads as the section it points at.
        for (Object constant : function.constants()) {
            if (constant instanceof CompiledFunction inner) {
                function(inner, indent);
            } else if (constant instanceof ClassDef declared) {
                classDef(declared, indent);
            }
        }
        line(indent + "}");
    }

    private void instructions(CompiledFunction function, String indent) {
        int[] code = function.code();
        Object[] constants = function.constants();
        for (int ip = 0; ip + 1 < code.length; ip += 2) {
            Op op = Op.byId(code[ip]);
            int arg = code[ip + 1];
            line(String.format("%s%4d  %-15s %-6d %-3s %s   :%d", indent, ip, op,
                    arg, op.operand(), operandText(op, arg, constants),
                    function.lineAt(ip)));
        }
    }

    /**
     * Renders an operand the way the opcode means it, so a dump is readable
     * without cross-referencing the constant pool by hand.
     */
    private String operandText(Op op, int arg, Object[] constants) {
        switch (op.operand()) {
            case "const":
                if (op == Op.NEW_CLOSURE) {
                    return "";
                }
                return describe(at(constants, arg));
            case "name":
            case "name<<8|argc":
                Object named = at(constants, op.operand().contains("|") ? (arg >> 8) : arg);
                return (named == null ? "?" : String.valueOf(named))
                        + (op.operand().contains("|") ? " /" + (arg & 0xFF) + " args" : "");
            default:
                return "";
        }
    }

    private static Object at(Object[] constants, int index) {
        return (index >= 0 && index < constants.length) ? constants[index] : null;
    }

    private String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof CompiledFunction function) {
            return "<" + label(function) + ">";
        }
        if (value instanceof ClassDef declared) {
            return "<class " + declared.name() + ">";
        }
        return String.valueOf(value);
    }

    /** @return a short unique-ish handle for a body, since lambdas are unnamed */
    private String label(CompiledFunction function) {
        String name = function.name().isEmpty() ? "lambda" : function.name();
        return name + "@" + function.sourceName() + ":" + function.line() + "#" + section;
    }

    // -- classes -------------------------------------------------------------

    private void classDef(ClassDef def, String indent) {
        section++;
        line(indent + "class " + def.name() + " {" );
        line(indent + "  kind=" + def.kind() + " extends="
                + (def.parentName() == null || def.parentName().isEmpty()
                        ? "-" : def.parentName())
                + " fields=" + def.fieldNames() + " required=" + def.minArgs());
        if (!def.constants().isEmpty()) {
            line(indent + "  constants=" + def.constants());
        }
        for (Map.Entry<String, CompiledFunction> method : def.methods().entrySet()) {
            line(indent + "  method " + method.getKey());
            instructions(method.getValue(), indent + "    ");
        }
        if (def.constructor() != null) {
            line(indent + "  init");
            instructions(def.constructor(), indent + "    ");
        }
        line(indent + "}");
    }

    // -- output --------------------------------------------------------------

    private void line(String text) {
        out.append(text).append('\n');
    }

    private static String quote(String text) {
        return "\"" + text.replace("\n", "\\n") + "\"";
    }
}
