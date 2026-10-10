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
package org.jdesktop.lg3d.mandela.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.rt.HostValues;
import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * The bridge between Java (and Kotlin) values and Mandela values.
 *
 * <p>A scripting language is only useful inside a JVM host if crossing the
 * boundary is a method call rather than a ritual. This class makes the crossing
 * two calls &mdash; {@link #toMandela} on the way in, {@link #toJava} on the way
 * out &mdash; and both are total: they either produce a value a script can use or
 * throw a {@link MandelaError} that names the type that did not fit, because the
 * failure a host sees at the boundary is the only one it will ever see.</p>
 *
 * <h2>The mapping</h2>
 *
 * <table border="1">
 *   <caption>Java / Kotlin to Mandela</caption>
 *   <tr><th>Java / Kotlin</th><th>Mandela</th></tr>
 *   <tr><td>{@code null}</td><td>{@code Null}</td></tr>
 *   <tr><td>{@code Boolean}</td><td>{@code Bool}</td></tr>
 *   <tr><td>{@code Byte/Short/Integer/Long}</td><td>{@code Int} (a {@code long})</td></tr>
 *   <tr><td>{@code Float/Double/BigDecimal}</td><td>{@code Double}</td></tr>
 *   <tr><td>{@code String / Character / CharSequence}</td><td>{@code Str}</td></tr>
 *   <tr><td>any {@code Collection}</td><td>{@code List}, elements converted</td></tr>
 *   <tr><td>an array</td><td>{@code List}, elements converted</td></tr>
 *   <tr><td>a {@code Map} with string-ish keys</td><td>{@code Map}, values converted</td></tr>
 *   <tr><td>a {@link Callable} (script function, host function)</td><td>unchanged</td></tr>
 *   <tr><td>anything with one public {@code invoke} method &mdash; a Kotlin lambda,
 *       a Groovy closure &mdash; or one abstract method &mdash; a
 *       {@code java.util.function} type, any single-abstract-method object</td>
 *       <td>a {@link HostFunction} that calls it</td></tr>
 *   <tr><td>anything else</td><td>refused, with the class name in the message</td></tr>
 * </table>
 *
 * <p>Refusing the last row is the load-bearing decision. A host could wrap an
 * arbitrary object and let a script poke at its fields, and some languages do;
 * that is how a script ends up reading {@code System.out.getClass()
 * .getProtectionDomain()} and how a sandbox is quietly bypassed. Here a Java
 * object reaches a script only through a {@link HostFunction} the host wrote, or
 * through a conversion the host asked for by name &mdash; and both are visible in
 * the host's own source, which is what a reviewer and
 * {@link Capabilities#allowsJvm} can reason about.</p>
 *
 * <h2>Kotlin</h2>
 *
 * <p>Kotlin needs no special case. A Kotlin {@code fun} reference or lambda
 * compiles to a {@code kotlin.jvm.functions.FunctionN} whose single abstract
 * method is {@code invoke}, so the reflective row above covers it, and Kotlin's
 * {@code List}/{@code Map} are {@code java.util} types by the time they reach the
 * JVM. A Kotlin host writes:</p>
 *
 * <pre>{@code
 * val engine = Mandela.engine().bind("greet") { name: String -> "hi $name" }.build()
 * engine.run("greet(\"world\")")
 * }</pre>
 *
 * <p>&hellip;because {@code Function1} arrives as something with an
 * {@code invoke}, and {@code toMandela} turns the returned {@code String} into a
 * {@code Str} on the way back. Java hosts get the same courtesy through
 * {@code java.util.function}: a {@code Function}, {@code Predicate}, {@code
 * Consumer}, {@code Supplier} or {@code UnaryOperator} is a single-abstract-method
 * object, so {@code bind("upper", String::toUpperCase)} works as written, and
 * {@link #fn}, {@link #fn1} and friends remain the way to name a function the
 * script should see.</p>
 */
public final class Interop {

    private Interop() {
        // Static namespace.
    }

    // -- in ------------------------------------------------------------------

    /**
     * Converts a Java or Kotlin value into the Mandela value a script reads.
     *
     * <p>The rule lives in {@link HostValues}, because the runtime applies exactly
     * the same one when a host calls {@code bind} or {@code call}: two rules that
     * could disagree would make the same {@code List} a list in one snippet and an
     * error in the next. This is the name a host writes, and the one the embedding
     * documentation quotes.</p>
     *
     * @param value the host value; may be null
     * @return the same value when it already is one, or its Mandela form
     * @throws MandelaError when there is no honest Mandela form for it
     */
    public static Object toMandela(Object value) {
        return HostValues.toMandela(value);
    }

    /**
     * Converts several values at once, the shape a host function's argument list
     * arrives in.
     *
     * @param values the host values
     * @return the Mandela values, in order
     */
    public static List<Object> toMandelaAll(Object... values) {
        List<Object> out = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            for (Object value : values) {
                out.add(toMandela(value));
            }
        }
        return out;
    }

    // -- out -----------------------------------------------------------------

    /**
     * Converts a Mandela value back into the plain Java form.
     *
     * <p>Lists become {@code ArrayList}s and maps become {@code LinkedHashMap}s, so
     * a host can hold onto the result after the script is finished with it;
     * everything else is already a Java object. Use
     * {@link #toJava(Object, Class)} when the host wants a specific type.</p>
     *
     * @param value the value a script produced
     * @return the Java form
     */
    public static Object toJava(Object value) {
        return Values.toHost(value);
    }

    /**
     * Converts a Mandela value and asks for a particular Java type.
     *
     * <p>Four targets cover what a host normally wants; anything else is a cast,
     * and a wrong cast says so instead of quietly returning a String.</p>
     *
     * @param <T>   the requested type
     * @param value the script's value
     * @param type  {@code String.class}, {@code Long.class}/{@code Integer.class},
     *              {@code Double.class}, {@code Boolean.class}, a List or a Map
     * @return the converted value, or null when {@code value} is null
     * @throws MandelaError when the value has no form of the requested type
     */
    @SuppressWarnings("unchecked")
    public static <T> T toJava(Object value, Class<T> type) {
        Object plain = toJava(value);
        if (plain == null) {
            return null;
        }
        if (type == Object.class) {
            return (T) plain;
        }
        if (type == String.class) {
            return (T) Values.display(plain);
        }
        if (type == Boolean.class || type == boolean.class) {
            return (T) Boolean.valueOf(Values.truthy(plain));
        }
        if (type == Long.class || type == long.class) {
            return (T) Long.valueOf(Values.asLong(plain));
        }
        if (type == Integer.class || type == int.class) {
            // The language has one whole-number type and it is wider than an int, so
            // a value that does not fit is refused rather than silently truncated.
            try {
                return (T) Integer.valueOf(Math.toIntExact(Values.asInt(plain)));
            } catch (ArithmeticException tooWide) {
                throw MandelaError.value("'" + Values.display(plain)
                        + "' does not fit in an int; ask for a long instead");
            }
        }
        if (type == Double.class || type == double.class) {
            return (T) Double.valueOf(Values.asDouble(plain));
        }
        if (List.class.isAssignableFrom(type) && plain instanceof List) {
            return (T) plain;
        }
        if (Map.class.isAssignableFrom(type) && plain instanceof Map) {
            return (T) plain;
        }
        if (type.isInstance(plain)) {
            return type.cast(plain);
        }
        throw MandelaError.type("a " + Values.typeName(value) + " has no "
                + type.getSimpleName() + " form in this host");
    }

    // -- host functions -------------------------------------------------------

    /**
     * Wraps a Java or Kotlin lambda as a script callable.
     *
     * <p>The arguments arrive already converted, and whatever the lambda returns is
     * converted on the way out, so a host writes
     * {@code bind("upper", (Function<String, String> String::toUpperCase))} and the
     * script sees a function from Str to Str.</p>
     *
     * @param name the name the script calls it by, used in diagnostics
     * @param body the implementation, in Java values
     * @return a callable to bind on a {@link org.jdesktop.lg3d.mandela.rt.Runtime}
     */
    public static Callable fn(String name, Function<Object[], Object> body) {
        return HostFunction.of(name, -1, args ->
                Interop.toMandela(body.apply(args.toArray())));
    }

    /** A one-argument host function, the common shape. */
    public static Callable fn1(String name, Function<Object, Object> body) {
        return HostFunction.of(name, 1, args -> Interop.toMandela(body.apply(args.get(0))));
    }

    /** A host function that takes a value and returns nothing useful. */
    public static Callable consumer(String name, Consumer<Object> body) {
        return HostFunction.of(name, 1, args -> {
            body.accept(args.get(0));
            return null;
        });
    }

    /** A host function that takes nothing and produces a value. */
    public static Callable supplier(String name, Supplier<Object> body) {
        return HostFunction.of(name, 0,
                args -> Interop.toMandela(body.get()));
    }

    /**
     * Turns anything that looks like a function into a script callable: a
     * {@link Callable} as-is, and otherwise any object that answers to exactly one
     * method &mdash; a public {@code invoke} (which is what a Kotlin lambda, a
     * Kotlin method reference and a Groovy closure look like once compiled), or a
     * single abstract method reached through the public interfaces it implements
     * (which is what every {@code java.util.function} type and every
     * single-abstract-method adapter looks like).
     *
     * @param value the candidate
     * @return the callable, or null when the value is not function-shaped
     */
    public static Callable tryFunctional(Object value) {
        return HostValues.tryCallable(value);
    }
}
