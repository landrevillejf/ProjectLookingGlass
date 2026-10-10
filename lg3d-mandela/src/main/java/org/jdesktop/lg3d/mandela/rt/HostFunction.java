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
package org.jdesktop.lg3d.mandela.rt;

import java.util.List;

import org.jdesktop.lg3d.mandela.values.Callable;

/**
 * A Java or Kotlin function published into a Mandela global table.
 *
 * <p>This is the whole of the host boundary. A script sees a
 * {@link Callable} exactly like the one its own {@code fun} declarations produce,
 * so {@code println} and {@code fib} are called the same way; the machine only
 * needs one extra branch to tell a host function from a closure.</p>
 *
 * <p>Implementations are usually a method reference or a Kotlin lambda, which is
 * what makes the language "compatible with Java / Kotlin" in practice rather than
 * in name only:</p>
 *
 * <pre>{@code
 * // Java
 * HostFunction.of("upper", 1, args -> String.valueOf(args.get(0)).toUpperCase())
 *
 * // Kotlin
 * HostFunction.of("upper", 1) { (it[0] as String).uppercase() }
 * }</pre>
 *
 * <p>A host function may throw. Anything other than a
 * {@link org.jdesktop.lg3d.mandela.values.MandelaError} is wrapped as a
 * {@code HostError} by the machine, so script code can still catch it with
 * {@code try} / {@code catch} and the interpreter never unwinds past a
 * {@code catch} the author wrote.</p>
 */
public interface HostFunction extends Callable {

    /**
     * Runs the function.
     *
     * @param args the evaluated arguments, in order; a variadic host function
     *             receives what the call site passed
     * @return the value to push for the call, possibly null
     */
    Object apply(List<Object> args);

    @Override
    default String name() {
        return "host";
    }

    /**
     * {@inheritDoc}
     *
     * <p>A negative arity marks a variadic function, which the machine then
     * argument-count-checks loosely.</p>
     */
    @Override
    default int arity() {
        return -1;
    }

    @Override
    default boolean isVariadic() {
        return arity() < 0;
    }

    /**
     * Builds a function from a name, a declared arity and a body.
     *
     * @param name  the published name, used in diagnostics
     * @param arity the parameter count, or -1 when it varies
     * @param body  the implementation
     * @return a callable the host can install as a global
     */
    static HostFunction of(String name, int arity, Body body) {
        return new Adaptor(name, arity, body);
    }

    /** The lambda shape {@link #of(String, int, Body)} accepts. */
    interface Body {
        /** @param args the evaluated arguments, in order
         *  @return the result value */
        Object run(List<Object> args);
    }

    /** The immutable implementation {@link #of} returns. */
    final class Adaptor implements HostFunction {

        private final String name;
        private final int arity;
        private final Body body;

        private Adaptor(String name, int arity, Body body) {
            this.name = (name == null) ? "host" : name;
            this.arity = arity;
            this.body = java.util.Objects.requireNonNull(body, "body");
        }

        @Override
        public Object apply(List<Object> args) {
            return body.run(args);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int arity() {
            return arity;
        }

        @Override
        public String toString() {
            return "<host fun " + name + "/" + arity + ">";
        }
    }
}
