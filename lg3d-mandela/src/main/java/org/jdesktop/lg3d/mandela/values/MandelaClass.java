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
package org.jdesktop.lg3d.mandela.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A declared type: the runtime object behind {@code class}, {@code type} and
 * {@code enum}.
 *
 * <p>All three share one representation because the machine treats them the
 * same way &mdash; a name, an optional base, a set of methods, and a rule for
 * making values:</p>
 * <ul>
 *   <li>{@link Kind#CLASS} &mdash; mutable instances, identity equality,
 *       inheritance, field initialisers and {@code init} blocks.</li>
 *   <li>{@link Kind#TYPE} &mdash; a value record. Construction is positional or
 *       by name, equality is structural, and there is no inheritance.</li>
 *   <li>{@link Kind#ENUM} &mdash; a closed set of constants, created once when
 *       the declaration runs and reachable only as {@code Name.CONSTANT};
 *       equality is identity by construction.</li>
 * </ul>
 *
 * <p>A class is also {@link Callable}: {@code Point(1, 2)} is a call, so the
 * compiler emits the same instruction for constructing a value as for invoking a
 * function, and the VM branches on the callee's kind.</p>
 */
public final class MandelaClass implements Callable {

    /** Which kind of declared type this is. */
    public enum Kind {
        /** A mutable reference type with inheritance. */
        CLASS,
        /** An immutable, structurally equal value record. */
        TYPE,
        /** A fixed set of named constants. */
        ENUM
    }

    private final Kind kind;
    private final String name;
    private final MandelaClass parent;
    private final List<String> fieldNames;
    private final Map<String, Callable> methods = new LinkedHashMap<>();
    private final Map<String, MandelaInstance> constants = new LinkedHashMap<>();
    private final int minArgs;

    /**
     * Creates a declaration.
     *
     * @param kind       CLASS, TYPE or ENUM
     * @param name       the declared name, used in diagnostics and {@code str()}
     * @param parent     the base class, or null
     * @param fieldNames the constructor parameter / field names, in order
     * @param minArgs    the fewest arguments a construction accepts (after defaults)
     */
    public MandelaClass(Kind kind, String name, MandelaClass parent,
                        List<String> fieldNames, int minArgs) {
        this.kind = kind;
        this.name = name;
        this.parent = parent;
        this.fieldNames = List.copyOf(fieldNames);
        this.minArgs = Math.max(0, minArgs);
    }

    /** @return the declaration kind */
    public Kind kind() {
        return kind;
    }

    @Override
    public String name() {
        return name;
    }

    /** @return the base class, or null when this type inherits from nothing */
    public MandelaClass parent() {
        return parent;
    }

    /** @return the declared field names, in declaration order */
    public List<String> fieldNames() {
        return fieldNames;
    }

    /** @return the declared methods (never the inherited ones) */
    public Map<String, Callable> methods() {
        return methods;
    }

    /** @return the enum constants by name, empty for a class or type */
    public Map<String, MandelaInstance> constants() {
        return constants;
    }

    /** Installs a method. Redeclaring a name replaces the earlier one. */
    public void define(String methodName, Callable body) {
        methods.put(methodName, body);
    }

    /** Registers an enum constant. */
    public void defineConstant(String constantName, MandelaInstance value) {
        constants.put(constantName, value);
    }

    /**
     * Looks a method up along the inheritance chain.
     *
     * @param methodName the name to find
     * @return the implementation, or null when neither this class nor a base has it
     */
    public Callable lookup(String methodName) {
        MandelaClass at = this;
        while (at != null) {
            Callable found = at.methods.get(methodName);
            if (found != null) {
                return found;
            }
            at = at.parent;
        }
        return null;
    }

    /**
     * @param candidate the name to test
     * @return true when this class or a base declares a field of that name
     */
    public boolean declaresField(String candidate) {
        return fieldNames.contains(candidate);
    }

    /** @return the number of constructor parameters, including defaulted ones */
    public int declaredArity() {
        return fieldNames.size();
    }

    @Override
    public int arity() {
        return minArgs;
    }

    @Override
    public boolean isVariadic() {
        return fieldNames.size() != minArgs;
    }

    /** @return true when {@code other} is this class or one of its subclasses */
    public boolean isSubclassOf(MandelaClass other) {
        MandelaClass at = this;
        while (at != null) {
            if (at == other) {
                return true;
            }
            at = at.parent;
        }
        return false;
    }

    /** @return every method name, inherited ones included, without duplicates */
    public List<String> visibleMethodNames() {
        List<String> out = new ArrayList<>();
        MandelaClass at = this;
        while (at != null) {
            for (String key : at.methods.keySet()) {
                if (!out.contains(key)) {
                    out.add(key);
                }
            }
            at = at.parent;
        }
        return out;
    }

    @Override
    public String toString() {
        return "<" + name + ">";
    }
}
