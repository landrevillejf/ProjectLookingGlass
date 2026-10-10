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

import java.util.List;
import java.util.Map;

import org.jdesktop.lg3d.mandela.values.MandelaClass;

/**
 * The description of a declared type, carried as one constant-pool entry.
 *
 * <p>A {@code class} / {@code type} / {@code enum} declaration compiles to a
 * single {@link Op#DEFINE_CLASS} whose operand points at one of these. Putting
 * the whole shape in one immutable record keeps the instruction stream dense and
 * leaves the VM with one short routine to build a {@link MandelaClass}: resolve
 * the base by name, install the methods, register the enum constants, then run
 * the synthesised constructor per instance.</p>
 *
 * <p>The compiler does the hard part. Fields with initialisers, {@code init}
 * blocks and the {@code extends} call are sequenced into {@link #constructor()}:
 * one function whose slot 0 is {@code this} and whose slots 1..n are the
 * declared parameters. That is why there is no separate field or initialiser list
 * here &mdash; the machine should not have to re-derive an ordering the compiler
 * already settled.</p>
 *
 * @param name        the declared type name
 * @param kind        CLASS, TYPE or ENUM
 * @param parentName  the {@code extends} base name, or null
 * @param fieldNames  constructor parameter and field names, in declaration order
 * @param minArgs     the fewest arguments a construction accepts
 * @param methods     method name to compiled body, in declaration order
 * @param constructor the synthesised initialiser, or null when there is none
 * @param constants   enum constant names in order, empty for class and type
 * @param sourceName  the script the declaration came from
 */
public record ClassDef(String name, MandelaClass.Kind kind, String parentName,
                       List<String> fieldNames, int minArgs,
                       Map<String, CompiledFunction> methods,
                       CompiledFunction constructor, List<String> constants,
                       String sourceName) {

    public ClassDef {
        fieldNames = List.copyOf(fieldNames);
        // Insertion order matters: an editor lists methods the way they were written.
        methods = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(methods));
        constants = List.copyOf(constants);
    }

    /** @return true when this describes an {@code enum} declaration */
    public boolean isEnum() {
        return kind == MandelaClass.Kind.ENUM;
    }

    /** @return true when this describes a {@code type} value record */
    public boolean isValue() {
        return kind == MandelaClass.Kind.TYPE;
    }
}
