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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * The rule that decides what a Java (or Kotlin) object means when a host hands it
 * to a script.
 *
 * <p>One rule, one place, because it is applied at three doors: {@link Runtime}
 * uses it for {@code bind} and {@code call}, {@code api.Interop} uses it for the
 * documented bridge, and a {@code javax.script} host reaches it through the engine.
 * If those three disagreed, the same {@code List} would be a list in one snippet
 * and an error in another, which is the kind of thing a language never recovers
 * from.</p>
 *
 * <h2>What crosses</h2>
 *
 * <ol>
 *   <li>a value the language already understands, unchanged &mdash; identity is the
 *       point, so a host function can mutate the list its script is holding;</li>
 *   <li>the ordinary Java shapes {@link Values#fromHost} widens: numbers, text,
 *       collections, arrays, maps;</li>
 *   <li>an enum, as its name;</li>
 *   <li>a function-shaped object &mdash; one public {@code invoke} method (a Kotlin
 *       lambda, a Groovy closure) or one abstract method (a
 *       {@code java.util.function} type, any single-abstract-method adapter)
 *       &mdash; as a {@link HostFunction};</li>
 *   <li>nothing else.</li>
 * </ol>
 *
 * <p>The last row is the load-bearing decision, and it is a security decision
 * rather than a purist one. A host <em>could</em> wrap an arbitrary object and let
 * a script read its fields, and some scripting languages do; that is how a script
 * ends up at {@code System.out.getClass().getProtectionDomain()} and how a
 * {@link Capabilities} check is quietly bypassed. Here a Java object reaches a
 * script only as data, as a name, or as one method the host declared &mdash; and
 * calling that method is the whole of what the script may do with it. No field,
 * no other method, no class object.</p>
 *
 * <p>The reflection in this class is also deliberately narrow in a second way: it
 * looks only at public methods of public types, and falls back to
 * {@link Method#setAccessible(boolean)} solely so a lambda whose class is
 * package-private (which is what Kotlin emits) can be called through the public
 * interface it implements.</p>
 */
public final class HostValues {

    private HostValues() {
        // Static namespace.
    }

    /**
     * Converts a host value into the Mandela value a script reads.
     *
     * @param value the host value; may be null
     * @return the same value when it already is one, or its Mandela form
     * @throws MandelaError when there is no honest Mandela form for it
     */
    public static Object toMandela(Object value) {
        if (value == null) {
            return null;
        }
        if (Values.isMandelaValue(value)) {
            return value;
        }
        Object widened = Values.fromHost(value);
        if (widened != value) {
            return widened;
        }
        if (value instanceof Enum<?> named) {
            // The constants belong to the host's type, not to the language; a name
            // is what a script compares and prints.
            return named.name();
        }
        Callable asFunction = tryCallable(value);
        if (asFunction != null) {
            return asFunction;
        }
        throw MandelaError.type("there is no Mandela form for a "
                + value.getClass().getName() + "; convert it in the host, or bind a"
                + " host function that returns something the script can use");
    }

    /**
     * Converts a value for a named position: a global, an argument.
     *
     * <p>The name is in the message because these are the two places a host gets
     * the conversion wrong, and both are the host's own code. The rule is
     * {@link #toMandela} itself &mdash; only the wording of the refusal differs.</p>
     *
     * @param what  the name or position, for the message
     * @param value the candidate
     * @return the value the machine can run on
     * @throws MandelaError when the value has no Mandela form
     */
    public static Object hostValue(String what, Object value) {
        try {
            return toMandela(value);
        } catch (MandelaError refused) {
            throw MandelaError.type("'" + what + "' cannot be given to a script as a "
                    + (value == null ? "null" : value.getClass().getName())
                    + "; a script reads numbers, text, lists, maps, enum names and"
                    + " lambdas, so pass one of those or bind a host function that"
                    + " returns what the script needs");
        }
    }

    /**
     * Converts each argument a host is passing into a script.
     *
     * @param name the function's name, used in the message
     * @param args the arguments, in any host form
     * @return the arguments in Mandela form
     */
    public static Object[] hostArgs(String name, Object[] args) {
        if (args == null || args.length == 0) {
            return new Object[0];
        }
        Object[] argv = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            argv[i] = hostValue(name + " argument " + (i + 1), args[i]);
        }
        return argv;
    }

    /**
     * Turns anything that looks like a function into a script callable, or answers
     * null when the value is not function-shaped.
     *
     * <p>Total by construction: a caller uses this to test a candidate and keeps
     * its own fallback, which is what lets {@code api.Interop} refuse in its own
     * words while sharing the same detection.</p>
     *
     * @param value the candidate
     * @return the callable, or null
     */
    public static Callable tryCallable(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Callable callable) {
            return callable;
        }
        Method found = functionalMethod(value.getClass());
        if (found == null) {
            return null;
        }
        Method invoke = throughPublicType(found, value.getClass());
        int arity = invoke.isVarArgs() ? -1 : invoke.getParameterCount();
        String label = value.getClass().getSimpleName();
        return HostFunction.of(label, arity, args -> {
            Object[] argv = new Object[Math.min(args.size(), invoke.getParameterCount())];
            for (int i = 0; i < argv.length; i++) {
                argv[i] = forParameter(invoke.getParameterTypes()[i], args.get(i));
            }
            try {
                return toMandela(invoke.invoke(value, argv));
            } catch (java.lang.reflect.InvocationTargetException through) {
                // The script's own exception type survives the reflection hop, so a
                // try/catch in the script still matches what the host threw.
                Throwable cause = through.getCause();
                if (cause instanceof MandelaError error) {
                    throw error;
                }
                throw MandelaError.runtime("'" + label + "' failed: "
                        + (cause == null ? through.getMessage() : cause.getMessage()),
                        cause == null ? through : cause);
            } catch (IllegalAccessException | IllegalArgumentException shaped) {
                throw MandelaError.type("this host cannot call a "
                        + value.getClass().getName() + ": " + shaped.getMessage());
            }
        });
    }

    /**
     * @param type the class to inspect
     * @return the one method that makes this object a function, or null when
     *         nothing does
     */
    private static Method functionalMethod(Class<?> type) {
        // A closure first: an `invoke` the class exposes is unambiguous whatever the
        // interfaces say, and this is the shape Kotlin and Groovy compile to.
        Method invoke = null;
        for (Method method : type.getMethods()) {
            if (!"invoke".equals(method.getName())
                    || !Modifier.isPublic(method.getModifiers())
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (invoke != null) {
                // Overloaded invoke means the arity is ambiguous, and guessing which
                // one the script meant is how a host function gets wrong silently.
                return null;
            }
            invoke = method;
        }
        if (invoke != null) {
            return invoke;
        }
        // Then a single-abstract-method object. Only public interfaces count, so a
        // POJO that happens to implement one non-public interface is still a POJO.
        Map<String, Method> abstracts = new LinkedHashMap<>();
        for (Class<?> face : type.getInterfaces()) {
            if (!Modifier.isPublic(face.getModifiers())) {
                continue;
            }
            for (Method method : face.getMethods()) {
                if (!Modifier.isAbstract(method.getModifiers())
                        || Modifier.isStatic(method.getModifiers())
                        || isObjectName(method.getName())) {
                    continue;
                }
                abstracts.put(method.getName()
                        + java.util.Arrays.toString(method.getParameterTypes()), method);
            }
        }
        return (abstracts.size() == 1) ? abstracts.values().iterator().next() : null;
    }

    /** The three methods every object has, which never make it a function. */
    private static boolean isObjectName(String name) {
        return "equals".equals(name) || "hashCode".equals(name) || "toString".equals(name);
    }

    /**
     * Chooses which form of a script argument to hand to one declared parameter.
     *
     * <p>A script list is not a {@code java.util.List}, so a lambda declared for
     * {@code List} has to be given the copy. A lambda declared for {@code Object}
     * must not be: it is the generic shape every Kotlin and Java callback answers
     * with, and passing it a copy would quietly break the promise that a host
     * function can mutate the collection its script is holding. So the copy is used
     * only when the declared type actually asks for one the script value cannot
     * satisfy, and the script value stays itself everywhere else.</p>
     *
     * @param declared the parameter's type
     * @param value    the script's argument
     * @return the argument in whichever form that parameter can take
     */
    private static Object forParameter(Class<?> declared, Object value) {
        if (declared == Object.class || declared.isInstance(value)) {
            return value;
        }
        Object plain = Values.toHost(value);
        return declared.isInstance(plain) ? plain : value;
    }

    /**
     * Finds a way to actually call the method reflection permits.
     *
     * <p>A lambda object is usually an instance of a class nobody can see &mdash;
     * Kotlin emits package-private classes, and a Java method reference is a private
     * synthetic one &mdash; so calling the method as the concrete class declares it
     * throws {@link IllegalAccessException} even though the method is public. The
     * honest fix is to call it through a public supertype, which is where a
     * {@code kotlin.jvm.functions.Function1} or a {@code java.util.function.Function}
     * is visible; the virtual dispatch still lands on the lambda's own body. Only
     * when no public supertype declares the method does this fall back to breaking
     * encapsulation, and a refusal there is reported by the call itself rather than
     * guessed around.</p>
     *
     * @param found the method as the concrete class exposes it
     * @param type  the concrete class
     * @return a method the caller may invoke
     */
    private static Method throughPublicType(Method found, Class<?> type) {
        if (Modifier.isPublic(type.getModifiers())) {
            return found;
        }
        for (Class<?> face : visibleTypes(type)) {
            try {
                return face.getMethod(found.getName(), found.getParameterTypes());
            } catch (NoSuchMethodException notThere) {
                // This supertype does not declare it; the next one might.
            }
        }
        try {
            found.setAccessible(true);
        } catch (RuntimeException blocked) {
            // A named module that will not open its package: the call below will
            // say so, which is truer than pretending the method does not exist.
        }
        return found;
    }

    /** @return every public type the value's class could be dispatched through */
    private static List<Class<?>> visibleTypes(Class<?> type) {
        List<Class<?>> out = new ArrayList<>();
        java.util.ArrayDeque<Class<?>> pending = new java.util.ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> at = pending.poll();
            if (!seen.add(at)) {
                continue;
            }
            for (Class<?> face : at.getInterfaces()) {
                if (Modifier.isPublic(face.getModifiers())) {
                    out.add(face);
                }
                pending.add(face);
            }
            Class<?> parent = at.getSuperclass();
            if (parent != null && parent != Object.class
                    && Modifier.isPublic(parent.getModifiers())) {
                out.add(parent);
                pending.add(parent);
            }
        }
        return out;
    }
}
