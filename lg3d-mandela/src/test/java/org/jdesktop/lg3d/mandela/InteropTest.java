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
package org.jdesktop.lg3d.mandela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jdesktop.lg3d.mandela.api.Interop;
import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The host bridge: what a Java or Kotlin value looks like inside a script, and what
 * a script value looks like back out.
 *
 * <p>This is the surface every embedding host writes against &mdash; the Web Browser
 * hands a page's data to a script through it, Espresso exposes its document model
 * through it &mdash; so the conversion rules are asserted one value at a time. The
 * refusals matter as much as the conversions: a bridge that quietly accepted an
 * arbitrary object would hand a script the host's whole object graph, which is the
 * one thing a sandboxed engine may not do.</p>
 *
 * <p>Kotlin is covered by shape rather than by dependency. A Kotlin lambda compiles
 * to a class with exactly one public {@code invoke} method, so {@link Kotlinish}
 * stands in for it; the module does not (and must not) depend on the Kotlin
 * distribution.</p>
 */
class InteropTest {

    /** Converts and displays, so a case reads the way a script author sees it. */
    private static String shown(Object hostValue) {
        return Values.display(Interop.toMandela(hostValue));
    }

    /** Runs one snippet against bound host names. */
    private static Object runWith(String source, Map<String, Object> bindings) {
        Runtime.Builder builder = Scripts.engine();
        bindings.forEach(builder::bind);
        return builder.build().run(source, "interop.mnd");
    }

    @Nested
    @DisplayName("host values that cross in")
    class Inbound {

        @Test
        @DisplayName("scalars keep their kind")
        void scalars() {
            assertEquals(null, Interop.toMandela(null));
            assertEquals(Boolean.TRUE, Interop.toMandela(Boolean.TRUE));
            // A narrowing int would silently lose the fraction, so every integral
            // host number arrives as an Int and every floating one as a Double.
            assertEquals(7L, Interop.toMandela(7));
            assertEquals(7L, Interop.toMandela(7L));
            assertEquals(1.5, Interop.toMandela(1.5f));
            assertEquals(2.25, Interop.toMandela(new BigDecimal("2.25")));
            assertEquals(9L, Interop.toMandela(new BigInteger("9")));
            assertEquals("x", Interop.toMandela('x'));
            assertEquals("sb", Interop.toMandela(new StringBuilder("sb")));
            // An enum crosses as its name, which is what a script can compare and
            // print; crossing the object itself would expose its methods.
            assertEquals("MONDAY", Interop.toMandela(DayOfWeek.MONDAY));
        }

        @Test
        @DisplayName("collections become script collections, element by element")
        void collections() {
            assertInstanceOf(MandelaList.class, Interop.toMandela(List.of(1, 2)));
            assertEquals("[1, 2]", shown(List.of(1, 2)));
            assertEquals("[3, 4]", shown(new int[] {3, 4}));
            assertEquals("[\"a\"]", shown(new String[] {"a"}));
            assertEquals("{\"k\": 1}", shown(Map.of("k", 1)));
            // A Map key is a Str in the script world, so a numeric key crosses as
            // its text and not as an unfindable Int.
            assertEquals("{\"1\": \"one\"}", shown(Map.of(1, "one")));
        }

        @Test
        @DisplayName("a script value already in hand is not copied")
        void alreadyScriptData() {
            MandelaList kept = new MandelaList(List.<Object>of(1L));
            assertSame(kept, Interop.toMandela(kept));
        }

        @Test
        @DisplayName("an object with nothing to say is refused, by name")
        void refusesPlainObjects() {
            MandelaError pojo = assertRaises(new Object());
            assertTrue(pojo.getMessage().contains("java.lang.Object"), pojo.getMessage());
        }

        @Test
        @DisplayName("an ambiguous 'invoke' is refused rather than guessed")
        void refusesOverloads() {
            assertEquals("TypeError", assertRaises(new Overloaded()).kind());
        }

        private MandelaError assertRaises(Object hostValue) {
            try {
                Interop.toMandela(hostValue);
            } catch (MandelaError refusal) {
                return refusal;
            }
            throw new AssertionError(hostValue.getClass().getName() + " crossed, but it"
                    + " should have been refused");
        }
    }

    @Nested
    @DisplayName("script values that cross out")
    class Outbound {

        @Test
        @DisplayName("collections arrive as their java.util peers")
        void collections() {
            Object list = Interop.toJava(new MandelaList(List.<Object>of("a")));
            assertInstanceOf(ArrayList.class, list);
            assertEquals("a", ((List<?>) list).get(0));

            MandelaMap source = new MandelaMap(2);
            source.put("k", 1L);
            assertInstanceOf(LinkedHashMap.class, Interop.toJava(source));
        }

        @Test
        @DisplayName("a requested target type converts, within its width")
        void coercions() {
            assertEquals("5", Interop.toJava(5L, String.class));
            assertEquals(5, Interop.toJava(5L, Integer.class));
            assertEquals(5L, Interop.toJava(5L, Long.class));
            assertEquals(5.0, Interop.toJava(5L, Double.class));
            assertEquals(Boolean.FALSE, Interop.toJava("", Boolean.class));
        }

        @Test
        @DisplayName("a value too wide for the requested int is refused, not truncated")
        void tooWide() {
            try {
                Interop.toJava(5_000_000_000L, Integer.class);
                throw new AssertionError("the narrowing conversion was allowed");
            } catch (MandelaError tooWide) {
                // The message has to tell the host what to ask for instead, or the
                // caller is left reading a stack trace with no fix in it.
                assertTrue(tooWide.getMessage().contains("long"), tooWide.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("callable host values")
    class Callables {

        @Test
        @DisplayName("the functional helpers bind by arity")
        void helpers() {
            assertEquals(6L, runWith("k(3)", Map.of("k",
                    Interop.fn1("tax", v -> ((Long) v) * 2))));
            assertEquals(6L, runWith("sum(1, 2, 3)", Map.of("sum",
                    Interop.fn("sum", a -> (Long) a[0] + (Long) a[1] + (Long) a[2]))));
            assertEquals(42L, runWith("next()", Map.of("next",
                    Interop.supplier("next", () -> 42L))));
            String[] seen = new String[1];
            runWith("say(\"hi\")", Map.of("say",
                    Interop.consumer("say", v -> seen[0] = Values.display(v))));
            assertEquals("hi", seen[0]);
        }

        @Test
        @DisplayName("java.util.function objects cross without an adapter")
        void jdkFunctions() {
            assertEquals(4L, runWith("f(3)", Map.of("f", Interop.toMandela(
                    (java.util.function.Function<Object, Object>) v -> (Long) v + 1L))));
            assertEquals(8L, runWith("f()", Map.of("f", Interop.toMandela(
                    (Supplier<Object>) () -> 8L))));
            String[] seen = new String[1];
            runWith("f(1)", Map.of("f", Interop.toMandela(
                    (Consumer<Object>) v -> seen[0] = Values.display(v))));
            assertEquals("1", seen[0]);
        }

        @Test
        @DisplayName("a Kotlin lambda's shape is callable from a script")
        void kotlinShape() {
            Object crossed = Interop.toMandela(new Kotlinish());
            assertInstanceOf(Callable.class, crossed);
            assertEquals("k:x", runWith("f(\"x\")", Map.of("f", crossed)));
        }

        @Test
        @DisplayName("a host failure keeps its own kind instead of becoming a crash")
        void failuresCrossBack() {
            Callable thrower = (Callable) Interop.toMandela(new Thrower());
            MandelaError through = assertThrowsMandela(() ->
                    runWith("f(1)", Map.of("f", thrower)));
            assertEquals("ValueError", through.kind());
        }

        private MandelaError assertThrowsMandela(Runnable body) {
            try {
                body.run();
            } catch (MandelaError raised) {
                return raised;
            }
            throw new AssertionError("the host failure did not reach the caller");
        }
    }

    @Test
    @DisplayName("the bridge is reached through the facade, not only through Runtime")
    void facadeSharesTheBridge() {
        // Interop and the engine must be one rule, not two: a host that converts by
        // hand has to get the answer a binding would have given.
        assertEquals(Mandela.eval("k(20) + 1", Map.of("k",
                Interop.fn1("k", v -> (Long) v))), 21L);
    }

    /** A Kotlin lambda's shape: one public {@code invoke} method. */
    static final class Kotlinish {
        public Object invoke(Object value) {
            return "k:" + Values.display(value);
        }
    }

    /** Ambiguous on purpose: an overloaded {@code invoke} must not be guessed. */
    static final class Overloaded {
        public Object invoke() {
            return null;
        }

        public Object invoke(Object a, Object b) {
            return null;
        }
    }

    /** A host function that fails the way the language does. */
    static final class Thrower {
        public Object invoke(Object value) {
            throw MandelaError.value("nope from the host");
        }
    }
}
