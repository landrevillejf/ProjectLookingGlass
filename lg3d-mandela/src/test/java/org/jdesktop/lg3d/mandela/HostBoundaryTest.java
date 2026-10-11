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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a script receives when Java answers.
 *
 * <p>The embedding guide carries one table for values crossing from the host, and
 * a script reaches that table through two doors: a name the host binds, and the
 * result of calling a host function. Both must widen, because a host writes
 * {@code HostFunction.of("count", 1, args -> text.length())} in perfectly good
 * faith &mdash; {@code length()} answers an {@code int} &mdash; and an unconverted
 * {@code Integer} in the machine is worse than a crash: {@code count("abc") == 3}
 * is false, {@code 1 + count("abc")} is a TypeError and {@code typeOf(...)} calls
 * it {@code Host}, so the script is told its own arithmetic is wrong.</p>
 *
 * <p>The last cases are the refusal, which is the other half of the same table. A
 * domain object has no Mandela form and the language says so with the class named
 * rather than handing the object over for a script to poke at.</p>
 */
class HostBoundaryTest {

    /** A Mandela list a host may hand back, to prove the boundary leaves it alone. */
    private static final MandelaList SAMPLE = new MandelaList(List.<Object>of(1L, 2L));

    /**
     * Runs one program on an engine carrying the given host names.
     *
     * @param source the program
     * @param names  the host shapes to bind, each name followed by its shape
     * @return the answer in the language's own notation
     */
    private static String shown(String source, Object... names) {
        Runtime.Builder engine = Mandela.engine(Scripts.TINY);
        for (int i = 0; i < names.length; i += 2) {
            engine.bind((String) names[i], host((String) names[i + 1]));
        }
        return Values.display(engine.build().run(source, "host.mnd"));
    }

    /**
     * @param shape what the host was asked to answer with, as a one-word description
     * @return a host function returning that shape, built the way an embedder writes
     *         one: a plain {@code HostFunction.of} whose body returns whatever Java
     *         returns
     */
    private static Object host(String shape) {
        return switch (shape) {
            // 'int' on purpose: this is the idiomatic Java answer, autoboxed here
            // by the compiler rather than by the author's choice.
            case "count" -> HostFunction.of("count", 1,
                    args -> ((String) args.get(0)).length());
            case "ratio" -> HostFunction.of("ratio", 0, args -> 1.5f);
            case "byte" -> HostFunction.of("byte", 0, args -> (byte) 7);
            case "short" -> HostFunction.of("short", 0, args -> (short) 7);
            case "exact" -> HostFunction.of("exact", 0,
                    args -> new BigDecimal("2.5"));
            case "whole" -> HostFunction.of("whole", 0,
                    args -> BigInteger.valueOf(9L));
            case "huge" -> HostFunction.of("huge", 0,
                    args -> new BigInteger("99999999999999999999"));
            case "letter" -> HostFunction.of("letter", 0, args -> 'x');
            case "chars" -> HostFunction.of("chars", 0, args -> new StringBuilder("hi"));
            case "names" -> HostFunction.of("names", 0,
                    args -> List.of("a", "b", "c"));
            case "numbers" -> HostFunction.of("numbers", 0, args -> new int[] {1, 2, 3});
            case "pairs" -> HostFunction.of("pairs", 0, args -> Map.of("k", "v"));
            case "state" -> HostFunction.of("state", 0, args -> Thread.State.BLOCKED);
            case "domain" -> HostFunction.of("domain", 0, args -> new Object());
            case "list" -> HostFunction.of("list", 0, args -> SAMPLE);
            default -> throw new IllegalStateException("no such host shape: " + shape);
        };
    }

    @Test
    @DisplayName("an int from Java arrives as an Int the script can do arithmetic with")
    void integerResultIsAnInt() {
        assertEquals("Int", shown("typeOf(count(\"abc\"))", "count", "count"));
        assertEquals("3", shown("count(\"abc\")", "count", "count"));
        // The equality that silently failed while the value stayed an Integer:
        // java.lang.Integer.equals(Long) is false, so a script comparing an answer
        // to a literal was told the two were different.
        assertEquals("true", shown("count(\"abc\") == 3", "count", "count"));
        assertEquals("4", shown("count(\"abc\") + 1", "count", "count"));
        assertEquals("true", shown("count(\"abc\").isWhole()", "count", "count"));
        assertEquals("3", shown("[\"a\", \"bb\"].map({ s -> count(s) }).sum()",
                "count", "count"));
    }

    @Test
    @DisplayName("the narrow and wide Java number types answer in the type named")
    void numberTypesWiden() {
        assertEquals("Double", shown("typeOf(ratio())", "ratio", "ratio"));
        assertEquals("3.0", shown("ratio() * 2", "ratio", "ratio"));
        assertEquals("7", shown("byte()", "byte", "byte"));
        assertEquals("7", shown("short()", "short", "short"));
        assertEquals("Int", shown("typeOf(whole())", "whole", "whole"));
        assertEquals("2.5", shown("exact()", "exact", "exact"));
        // A BigInteger past the 64-bit range is the one number the language cannot
        // hold as an Int, and the table says a Double rather than a wrong long.
        assertEquals("Double", shown("typeOf(huge())", "huge", "huge"));
    }

    @Test
    @DisplayName("text-like Java values arrive as Str")
    void textWidens() {
        assertEquals("Str", shown("typeOf(letter())", "letter", "letter"));
        assertEquals("x", shown("letter()", "letter", "letter"));
        assertEquals("hi", shown("chars()", "chars", "chars"));
        // An enum is the host's type, not the language's: the name is what a script
        // can compare and print.
        assertEquals("BLOCKED", shown("state()", "state", "state"));
    }

    @Test
    @DisplayName("a java.util collection or array arrives as a List the script can walk")
    void collectionsWiden() {
        assertEquals("List", shown("typeOf(names())", "names", "names"));
        assertEquals("3", shown("names().length", "names", "names"));
        assertEquals("b", shown("names()[1]", "names", "names"));
        assertEquals("6", shown("numbers().sum()", "numbers", "numbers"));
        assertEquals("v", shown("pairs()[\"k\"]", "pairs", "pairs"));
        assertEquals("Map", shown("typeOf(pairs())", "pairs", "pairs"));
    }

    @Test
    @DisplayName("a value that already is Mandela's crosses unchanged")
    void mandelaValuesPassThrough() {
        Runtime engine = Mandela.engine(Scripts.TINY)
                .bind("list", host("list"))
                .build();
        assertSame(SAMPLE, engine.run("list()", "host.mnd"),
                "a Mandela list must not be copied at the boundary");
        assertEquals("2", Values.display(engine.run("list().length", "host.mnd")));
    }

    @Test
    @DisplayName("a domain object is refused with its class named")
    void domainObjectIsRefused() {
        MandelaError refused = Scripts.failure("domain()",
                Mandela.engine(Scripts.TINY).bind("domain", host("domain")));
        assertEquals("TypeError", refused.kind());
        assertTrue(refused.getMessage().contains("java.lang.Object"), refused.getMessage());
        // The refusal is the script's own failure, so it carries the line that asked
        // for it, and it is a Mandela error like any other, so a `try` the author
        // wrote still catches it: a host that hands back something unusable cannot
        // take the script's error handling away from it.
        assertTrue(refused.line() > 0, refused.getMessage());
        assertEquals("caught", shown("""
                var answer = "none"
                try {
                  domain()
                } catch err {
                  answer = "caught"
                }
                answer""", "domain", "domain"));
    }

    @Test
    @DisplayName("a script can ask a host function what it is")
    void hostFunctionsReflect() {
        // The members are how a userscript or a REPL inspects what the host gave it,
        // and they read from the same HostFunction the call went through.
        assertEquals("count", shown("count(\"a\"); count.name", "count", "count"));
        assertEquals("1", shown("count(\"a\"); count.arity", "count", "count"));
        assertEquals("ratio", shown("ratio.name", "ratio", "ratio"));
        // A name the library installed is a host function too, and the console ones
        // are variadic, which is the -1 the machine checks loosely against.
        assertEquals("-1", Scripts.display("println.arity"));
        assertEquals("println", Scripts.display("println.name"));
    }
}
