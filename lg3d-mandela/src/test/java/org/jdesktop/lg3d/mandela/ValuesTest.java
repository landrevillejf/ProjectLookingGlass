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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.values.MandelaClass;
import org.jdesktop.lg3d.mandela.values.MandelaClosure;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaInstance;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.MandelaRange;
import org.jdesktop.lg3d.mandela.values.Ref;
import org.jdesktop.lg3d.mandela.values.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The value semantics of the language, tested where they live: {@code Values} is
 * the single place that names a value, decides whether it is true, prints it,
 * compares it, converts it and widens it across the host boundary.
 *
 * <p>Every other suite reaches these through a script, which is right for a
 * language reference but leaves the half of the surface a <em>host</em> calls
 * unexamined: {@code Interop} converts with {@link Values#toHost}, a bound
 * function arrives through {@link Values#fromHost}, and an embedding panel that
 * wants to know whether a value is usable asks {@link Values#typeName}. Those are
 * public Java methods with a published contract, so they are pinned here at the
 * Java level; the cases a script can also reach are asserted both ways, because a
 * rule that only holds when called through the machine is a rule the two
 * disagree about.</p>
 */
class ValuesTest {

    /** A domain object with a readable {@code toString}, for the Host cases. */
    private static final Object DOMAIN = new Object() {
        @Override
        public String toString() {
            return "domain";
        }
    };

    // -- names ---------------------------------------------------------------

    static Stream<Arguments> names() {
        MandelaList list = new MandelaList(List.<Object>of(1L));
        MandelaMap map = new MandelaMap();
        map.put("k", 1L);
        return Stream.of(
                Arguments.of(null, "Null"),
                Arguments.of(Boolean.TRUE, "Bool"),
                Arguments.of(1L, "Int"),
                Arguments.of(1.5d, "Double"),
                Arguments.of("a", "Str"),
                Arguments.of(list, "List"),
                Arguments.of(map, "Map"),
                Arguments.of(new MandelaRange(1, 3, false, 1), "Range"),
                Arguments.of(Values.ABSENT, "Absent"),
                Arguments.of(new Ref(1L), "Ref"),
                Arguments.of(MandelaError.of("boom"), "Err"),
                Arguments.of(DOMAIN, "Host"));
    }

    @ParameterizedTest(name = "{1} for {0}")
    @MethodSource("names")
    @DisplayName("every value has one type name")
    void namesEveryValue(Object value, String expected) {
        assertEquals(expected, Values.typeName(value));
        assertEquals(!"Host".equals(expected), Values.isMandelaValue(value));
    }

    @Test
    @DisplayName("a declared value answers with its own name, not with Obj")
    void declaredNamesComeFromTheClass() {
        MandelaInstance point = (MandelaInstance) Scripts.run(
                "type Point(x: Int, y: Int)\nPoint(1, 2)");
        MandelaClass declared = (MandelaClass) Scripts.run(
                "type Point(x: Int, y: Int)\nPoint");
        assertEquals("Point", Values.typeName(point));
        assertEquals("Class", Values.typeName(declared));
        assertEquals("Point", point.typeName());
        // The same answer a script reads, so a host and a script never disagree
        // about what a value is called.
        assertEquals("Point", Scripts.display("type Point(x: Int)\n"
                + "let p = Point(1)\ntypeOf(p)"));
        assertEquals("Class", Scripts.display("type Point(x: Int)\ntypeOf(Point)"));
        assertEquals("Fun", Scripts.display("typeOf({ x -> x })"));
        assertEquals("Null", Scripts.display("typeOf(null)"));
    }

    /** A closure the cursor and the callable cases share. */
    private static MandelaClosure lambda() {
        // A brace at the start of a statement is a block, so the lambda has to be
        // introduced by a declaration before it can be the program's answer.
        return (MandelaClosure) Scripts.run("let f = { x -> x }\nf");
    }

    // -- the `is` vocabulary -------------------------------------------------

    static Stream<Arguments> typeTests() {
        MandelaInstance point = (MandelaInstance) Scripts.run(
                "type Point(x: Int, y: Int)\nPoint(1, 2)");
        return Stream.of(
                Arguments.of(null, "Null", true),
                Arguments.of(null, "Any", true),
                Arguments.of(1L, "Any", true),
                Arguments.of("a", "Any", true),
                Arguments.of(1L, "Int", true),
                Arguments.of(1L, "Bool", false),
                Arguments.of(1L, "Num", true),
                Arguments.of(1.5d, "Num", true),
                Arguments.of(1L, "Double", false),
                Arguments.of(1.5d, "Int", false),
                Arguments.of(true, "Bool", true),
                Arguments.of("a", "Str", true),
                Arguments.of(new MandelaList(), "List", true),
                Arguments.of(new MandelaMap(), "Map", true),
                Arguments.of(new MandelaRange(1, 2, false, 1), "Range", true),
                Arguments.of(point, "Obj", true),
                Arguments.of(point, "Point", true),
                Arguments.of(point, "Other", false),
                Arguments.of(MandelaError.of("boom"), "Err", true),
                Arguments.of(Values.ABSENT, "Any", true),
                Arguments.of(new Ref(1L), "Any", true),
                Arguments.of(DOMAIN, "Any", true),
                // The false half of every name, and the Double arm's true half. A
                // BooleanTrueReturn on any one case arm makes every value answer true
                // to that name, which only a value of a different type catches; the
                // Double arm is mutated the other way (BooleanFalseReturn), so it is
                // the one name that needs a value that really is a Double.
                Arguments.of(1L, "Null", false),
                Arguments.of(1.5d, "Double", true),
                Arguments.of("a", "Num", false),
                Arguments.of(1L, "List", false),
                Arguments.of(1L, "Map", false),
                Arguments.of(1L, "Range", false),
                Arguments.of(1L, "Fun", false),
                Arguments.of(1L, "Err", false));
    }

    @ParameterizedTest(name = "{2} for {0} tested as {1}")
    @MethodSource("typeTests")
    @DisplayName("the builtin type names test what their names say")
    void testsEveryBuiltinName(Object value, String name, boolean expected) {
        assertEquals(expected, Values.isType(value, name));
    }

    @Test
    @DisplayName("a Fun name covers every callable the machine can run")
    void funsAreCallableValues() {
        assertTrue(Values.isType(lambda(), "Fun"));
        // A host function is a Fun as much as a closure is, which is what lets a
        // script pass one to `.map`.
        assertTrue(Values.isType(HostFunction.of("hostSide", 1, args -> null), "Fun"));
        assertEquals("Fun", Values.typeName(HostFunction.of("hostSide", 1,
                args -> null)));
    }

    @Test
    @DisplayName("`is` against a declared class also accepts a subclass")
    void subclassIsAnInstanceOfItsBase() {
        // One run, so the three values share the one class table: a class is
        // per-run, and an instance of another run's `Counter` is not this one's.
        MandelaList pair = (MandelaList) Scripts.run(
                "class Counter(start: Int = 0) { var total = start }\n"
                        + "class Meter() extends Counter { }\n"
                        + "[Meter(), Counter, Meter]");
        MandelaInstance meter = (MandelaInstance) pair.get(0);
        MandelaClass counter = (MandelaClass) pair.get(1);
        MandelaClass meterClass = (MandelaClass) pair.get(2);
        // The exact declared name is what Values.isType answers; the subclass walk
        // is the separate, named rule, and the machine consults it second.
        assertFalse(Values.isType(meter, "Counter"));
        assertTrue(Values.isType(meter, "Meter"));
        assertTrue(Values.isInstanceOf(meter, counter));
        assertTrue(Values.isInstanceOf(meter, meterClass));
        // A class value is not an instance of anything, not even of itself: `is`
        // tests objects, and the metaclass name is reached as `is Class`.
        assertFalse(Values.isInstanceOf(counter, counter));
        assertEquals("true", Scripts.display(
                "class Counter(start: Int = 0) { var total = start }\n"
                        + "class Meter() extends Counter { }\nMeter() is Counter"));
    }

    // -- truth ---------------------------------------------------------------

    static Stream<Arguments> truth() {
        MandelaList one = new MandelaList();
        one.add(1L);
        return Stream.of(
                Arguments.of(null, false),
                Arguments.of(Boolean.FALSE, false),
                Arguments.of(Boolean.TRUE, true),
                Arguments.of(0L, false),
                Arguments.of(1L, true),
                Arguments.of(-1L, true),
                Arguments.of(0.0d, false),
                Arguments.of(-0.0d, false),
                Arguments.of(0.5d, true),
                Arguments.of("", false),
                Arguments.of("0", true),
                Arguments.of(new MandelaList(), true),
                Arguments.of(one, true),
                Arguments.of(new MandelaMap(), true),
                Arguments.of(new MandelaRange(1, 2, false, 1), true),
                Arguments.of(Values.ABSENT, true));
    }

    @ParameterizedTest(name = "truthy({0}) is {1}")
    @MethodSource("truth")
    @DisplayName("the truth rule refuses the scalar zeros only")
    void truthsEveryValue(Object value, boolean expected) {
        assertEquals(expected, Values.truthy(value));
    }

    @Test
    @DisplayName("a script's `if` answers what the Java helper says")
    void truthIsWhatAScriptSees() {
        assertEquals("false", Scripts.display("if (0) { true } else { false }"));
        assertEquals("false", Scripts.display("if (0.0) { true } else { false }"));
        assertEquals("false", Scripts.display("if (\"\") { true } else { false }"));
        assertEquals("true", Scripts.display("if ([]) { true } else { false }"));
        assertEquals("true", Scripts.display("let m = {}\nif (m) { true } else { false }"));
        assertEquals("true", Scripts.display("if (\"0\") { true } else { false }"));
        assertEquals("true", Scripts.display("not 0"));
        // An empty range is a value, not an absence of one: the rule is about
        // scalars, and a collection is always true because a caller cannot tell an
        // empty one from a missing one.
        assertEquals("true", Scripts.display("if (1..0) { true } else { false }"));
    }

    // -- printing ------------------------------------------------------------

    static Stream<Arguments> displays() {
        MandelaMap map = new MandelaMap();
        map.put("a", 1L);
        map.put("b", "x");
        MandelaList nested = new MandelaList();
        nested.add(1L);
        nested.add(map);
        MandelaList list = new MandelaList();
        list.add("a");
        list.add(null);
        list.add(1.5d);
        list.add(nested);
        MandelaInstance point = (MandelaInstance) Scripts.run(
                "type Point(x: Int, y: Int)\nPoint(1, 2)");
        MandelaClass declared = (MandelaClass) Scripts.run("type Point(x: Int)\nPoint");
        MandelaClass enumerated = (MandelaClass) Scripts.run("enum Status { Idle }\nStatus");
        return Stream.of(
                Arguments.of(null, "null"),
                Arguments.of("plain", "plain"),
                Arguments.of(1L, "1"),
                Arguments.of(1.5d, "1.5"),
                Arguments.of(true, "true"),
                Arguments.of(list, "[\"a\", null, 1.5, [1, {\"a\": 1, \"b\": \"x\"}]]"),
                Arguments.of(map, "{\"a\": 1, \"b\": \"x\"}"),
                Arguments.of(new MandelaRange(1, 5, false, 2), "1..5 step 2"),
                Arguments.of(new MandelaRange(1, 5, true, 1), "1..<5"),
                Arguments.of(point, "Point(x: 1, y: 2)"),
                Arguments.of(declared, "<type Point>"),
                Arguments.of(enumerated, "<enum Status>"),
                Arguments.of(new Ref("boxed"), "\"boxed\""),
                Arguments.of(MandelaError.of("ValueError", "bad"), "ValueError: bad"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("displays")
    @DisplayName("a value prints as something that can be pasted back")
    void printsEveryValue(Object value, String expected) {
        assertEquals(expected, Values.display(value));
        // The two forms differ for exactly one type: a string prints as its own
        // text for a reader and arrives quoted when it has to be read back.
        assertEquals(value instanceof String ? "\"" + value + "\"" : expected,
                Values.repr(value));
    }

    @Test
    @DisplayName("a printed value reads the same in a script as in Java")
    void printingIsTheSameFromBothSides() {
        assertEquals("<type Point>", Scripts.display(
                "type Point(x: Int)\nstr(Point)"));
        assertEquals("Point(x: 1, y: 2)", Scripts.display(
                "type Point(x: Int, y: Int)\nstr(Point(1, 2))"));
        assertEquals("<enum Status>", Scripts.display("enum Status { Idle }\nstr(Status)"));
        assertEquals("[1, \"a\"]", Scripts.display("str([1, \"a\"])"));
        assertEquals("Error: bad", Scripts.display("var out = \"\"\n"
                + "try { error(\"bad\") } catch e { out = str(e) }\nout"));
    }

    @Test
    @DisplayName("a quoted string escapes what a parser would otherwise lose")
    void quotesTheCharactersThatMatter() {
        assertEquals("\"a\\\"b\"", Values.repr("a\"b"));
        assertEquals("\"a\\\\b\"", Values.repr("a\\b"));
        assertEquals("\"a\\nb\"", Values.repr("a\nb"));
        assertEquals("\"\\r\"", Values.repr("\r"));
        assertEquals("\"\\t\"", Values.repr("\t"));
        // A control character stays visible rather than printing an invisible byte
        // a reader cannot copy back.
        assertEquals("\"\\u0001\"", Values.repr("\u0001"));
        assertEquals("\"\\u001f\"", Values.repr("\u001f"));
        // A space and anything above 0x20 is written as itself.
        assertEquals("\" a~\"", Values.repr(" a~"));
    }

    @Test
    @DisplayName("a structure that refers to itself is cut rather than recursed")
    void printingStopsAtTheDepthCeiling() {
        MandelaList loop = new MandelaList();
        loop.add(1L);
        loop.add(loop);
        String printed = Values.repr(loop);
        assertTrue(printed.startsWith("[1, [1, [1,"), printed);
        assertTrue(printed.endsWith("]]]]]]]]]]]]]]]]"), printed);
        assertTrue(printed.contains("..."), printed);
        // The ceiling counts, so a legitimate structure deeper than it prints as
        // the same cut rather than as a stack overflow.
        assertEquals(16, printed.split("\\[", -1).length - 1);
    }

    @Test
    @DisplayName("a map or an instance that refers to itself is cut too")
    void printingStopsAtTheDepthCeilingForMapsAndInstances() {
        // The list is not the only arm that deepens: a map value and an instance
        // field each recurse at depth + 1, and a cycle in either would recurse
        // forever -- a stack overflow, not a printed "..." -- if that increment were
        // bent the other way.
        MandelaMap loop = new MandelaMap();
        loop.put("self", loop);
        String mapText = Values.repr(loop);
        assertTrue(mapText.contains("..."), mapText);

        MandelaInstance node = (MandelaInstance) Scripts.run(
                "class Node { var next = null }\nlet n = Node()\nn.next = n\nn");
        String nodeText = Values.repr(node);
        assertTrue(nodeText.contains("..."), nodeText);
    }

    static Stream<Arguments> doubles() {
        return Stream.of(
                Arguments.of(1.5d, "1.5"),
                Arguments.of(2.0d, "2.0"),
                Arguments.of(-2.0d, "-2.0"),
                Arguments.of(0.1d, "0.1"),
                Arguments.of(0.0d, "0.0"),
                // The sign of a negative zero is not carried: BigDecimal spells it
                // 0.0, and 0.0 == -0.0, so no comparison can tell the two apart.
                Arguments.of(-0.0d, "0.0"),
                Arguments.of(1e-6d, "0.000001"),
                Arguments.of(1e-7d, "1.0E-7"),
                Arguments.of(1e15d, "1000000000000000.0"),
                Arguments.of(1e16d, "1.0E16"),
                Arguments.of(1e300d, "1.0E300"),
                Arguments.of(Double.POSITIVE_INFINITY, "Inf"),
                Arguments.of(Double.NEGATIVE_INFINITY, "-Inf"),
                Arguments.of(Double.NaN, "NaN"));
    }

    @ParameterizedTest(name = "{0} prints as {1}")
    @MethodSource("doubles")
    @DisplayName("a Double prints in the notation an author would write")
    void formatsDoubles(double value, String expected) {
        assertEquals(expected, Values.formatDouble(value));
        assertEquals(expected, Values.display(value));
    }

    @Test
    @DisplayName("an overflowing literal prints Inf instead of dying")
    void infinityIsReachableFromAScript() {
        assertEquals("Inf", Scripts.display("str(1e400)"));
        assertEquals("Inf", Scripts.display("str(1e308 * 10)"));
        assertEquals("-Inf", Scripts.display("str(0.0 - 1e400)"));
    }

    // -- the boundary from Java into the language ----------------------------

    static Stream<Arguments> hostValues() {
        MandelaList already = new MandelaList(List.<Object>of(1L));
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("n", 3);
        mixed.put("nested", List.of(1, 2.5f));
        return Stream.of(
                Arguments.of(null, "Null", "null"),
                Arguments.of(7, "Int", "7"),
                Arguments.of((byte) 7, "Int", "7"),
                Arguments.of((short) 7, "Int", "7"),
                Arguments.of(7L, "Int", "7"),
                Arguments.of(1.5f, "Double", "1.5"),
                Arguments.of(1.5d, "Double", "1.5"),
                Arguments.of(new BigDecimal("2.5"), "Double", "2.5"),
                Arguments.of(BigInteger.valueOf(9L), "Int", "9"),
                // A BigInteger past the 64-bit range has no Int form, and the table
                // answers a Double rather than a silently wrapped long.
                Arguments.of(new BigInteger("99999999999999999999"), "Double", "1.0E20"),
                Arguments.of('x', "Str", "x"),
                Arguments.of(new StringBuilder("hi"), "Str", "hi"),
                Arguments.of(List.of("a", "b"), "List", "[\"a\", \"b\"]"),
                Arguments.of(new int[] {1, 2, 3}, "List", "[1, 2, 3]"),
                Arguments.of(new String[] {"a", null}, "List", "[\"a\", null]"),
                Arguments.of(mixed, "Map", "{\"n\": 3, \"nested\": [1, 2.5]}"),
                Arguments.of(new MandelaList(), "List", "[]"),
                Arguments.of(new MandelaMap(), "Map", "{}"),
                Arguments.of(new MandelaRange(1, 2, false, 1), "Range", "1..2"),
                Arguments.of(lambda(), "Fun", "<fun lambda/1>"),
                Arguments.of(MandelaError.of("boom"), "Err", "Error: boom"),
                // The enum is the only row the table does not convert: an enum is a
                // domain type, and `rt.HostValues` is where naming one is decided.
                Arguments.of(Thread.State.BLOCKED, "Host", "BLOCKED"),
                Arguments.of(already, "List", "[1]"),
                Arguments.of(DOMAIN, "Host", "domain"));
    }

    @ParameterizedTest(name = "{0} arrives as a {1}")
    @MethodSource("hostValues")
    @DisplayName("an ordinary Java value widens into the value the machine runs")
    void widensHostValues(Object value, String expectedName, String expected) {
        Object widened = Values.fromHost(value);
        assertEquals(expectedName, Values.typeName(widened));
        assertEquals(expected, Values.display(widened));
        assertEquals(!"Host".equals(expectedName), Values.isMandelaValue(widened));
    }

    @Test
    @DisplayName("a value that already is Mandela's is not copied")
    void hostValuesAlreadyInFormPassThrough() {
        MandelaList already = new MandelaList(List.<Object>of(1L));
        assertSame(already, Values.fromHost(already));
        assertEquals(already, Values.fromHost(already));
        assertSame(DOMAIN, Values.fromHost(DOMAIN),
                "a domain object must arrive unchanged so the caller can refuse it");
        assertEquals("Host", Values.typeName(Values.fromHost(DOMAIN)));
    }

    @Test
    @DisplayName("a host map's keys become the names a script reads")
    void hostMapKeysAreNamed() {
        Map<Object, Object> keyed = new HashMap<>();
        keyed.put("text", 1L);
        keyed.put(7, "number");
        keyed.put(Thread.State.BLOCKED, "enum");
        MandelaMap widened = (MandelaMap) Values.fromHost(keyed);
        assertEquals("1", Values.display(widened.get("text")));
        assertEquals("number", Values.display(widened.get("7")));
        assertEquals("enum", Values.display(widened.get("BLOCKED")));
    }

    @Test
    @DisplayName("a host map with a null key is refused, not guessed at")
    void refusesAHostMapWithANullKey() {
        Map<Object, Object> keyed = new HashMap<>();
        keyed.put(null, 1L);
        MandelaError refused = assertThrowsError(() -> Values.fromHost(keyed));
        assertEquals("ValueError", refused.kind());
        assertTrue(refused.getMessage().contains("null key"), refused.getMessage());
    }

    // -- the boundary from the language into Java ----------------------------

    @Test
    @DisplayName("a script value converts into the plain Java form a host can hold")
    void convertsValuesToHostForms() {
        MandelaList list = new MandelaList();
        list.add(1L);
        list.add(new MandelaList(List.<Object>of("deep")));
        Object javaList = Values.toHost(list);
        assertEquals(new ArrayList<>(List.of(1L, new ArrayList<>(List.of("deep")))),
                javaList);

        MandelaMap map = new MandelaMap();
        map.put("a", 1L);
        assertEquals(new LinkedHashMap<>(Map.of("a", 1L)), Values.toHost(map));

        MandelaInstance point = (MandelaInstance) Scripts.run(
                "type Point(x: Int, y: Int)\nPoint(1, 2)");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) Values.toHost(point);
        assertEquals(Map.of("x", 1L, "y", 2L), fields);
    }

    @Test
    @DisplayName("the Java copies are deep, so a host cannot keep a live value")
    void hostCopiesAreIndependent() {
        MandelaList inner = new MandelaList(List.<Object>of(1L));
        MandelaList outer = new MandelaList(List.<Object>of(inner));
        @SuppressWarnings("unchecked")
        List<Object> copied = (List<Object>) Values.toHost(outer);
        inner.add(2L);
        assertEquals(1, ((List<?>) copied.get(0)).size());
        copied.add("host");
        assertEquals(1, outer.size());
    }

    static Stream<Arguments> hostPassthrough() {
        return Stream.of(
                Arguments.of("a", "a"),
                Arguments.of(1L, "1"),
                Arguments.of(1.5d, "1.5"),
                Arguments.of(true, "true"),
                Arguments.of(null, "null"),
                Arguments.of(new MandelaRange(1, 3, false, 1), "1..3"));
    }

    @ParameterizedTest(name = "{0} crosses as itself")
    @MethodSource("hostPassthrough")
    @DisplayName("scalars and ranges have no plainer Java form, so none is invented")
    void passesThroughWhatAlreadyIsJava(Object value, String expected) {
        assertEquals(expected, Values.display(Values.toHost(value)));
    }

    @Test
    @DisplayName("a function and an error are passed as themselves")
    void passesCallablesAndErrorsThrough() {
        MandelaClosure fun = lambda();
        assertSame(fun, Values.toHost(fun));
        MandelaError error = MandelaError.of("boom");
        assertSame(error, Values.toHost(error));
    }

    // -- equality and order --------------------------------------------------

    @Test
    @DisplayName("two values are equal when the language says so, at every depth")
    void equalityIsMandelaEquality() {
        assertTrue(Values.equal(null, null));
        assertFalse(Values.equal(null, 1L));
        assertFalse(Values.equal(1L, null));
        // The mixed-number rule the guide carries, applied inside a container too.
        assertTrue(Values.equal(1L, 1.0d));
        assertTrue(Values.equal(1.0d, 1L));
        assertTrue(Values.equal(new MandelaList(List.<Object>of(1L)),
                new MandelaList(List.<Object>of(1.0d))));
        assertTrue(Values.equal(new MandelaList(List.<Object>of(
                        new MandelaList(List.<Object>of(1L)))),
                new MandelaList(List.<Object>of(
                        new MandelaList(List.<Object>of(1.0d))))));
        MandelaMap intMap = new MandelaMap();
        intMap.put("a", 1L);
        MandelaMap doubleMap = new MandelaMap();
        doubleMap.put("a", 1.0d);
        assertTrue(Values.equal(intMap, doubleMap));
        assertFalse(Values.equal(intMap, new MandelaMap()));
        assertFalse(Values.equal(new MandelaList(List.<Object>of(1L)),
                new MandelaList(List.<Object>of(1L, 2L))));
        // A range is a value, so 1..3 and 1..3 step 1 are the same range while a
        // different stride is not.
        assertTrue(Values.equal(new MandelaRange(1, 3, false, 1),
                new MandelaRange(1, 3, false, 1)));
        assertFalse(Values.equal(new MandelaRange(1, 3, false, 1),
                new MandelaRange(1, 3, false, 2)));
        // Two Ints too large for the boxed-Long cache are distinct objects, so
        // equality reaches the Long.equals arm instead of the `left == right`
        // shortcut; a BooleanFalseReturn there would call two equal big Ints apart.
        assertTrue(Values.equal(4_000_000_000L, 4_000_000_000L));
        // The mixed-number arm's two returns each need the case the other cannot
        // supply: a NaN against a finite number is false (catching a BooleanTrue on
        // the NaN arm), and two different finite numbers are false (catching a
        // BooleanTrue on the `l == r` arm).
        assertFalse(Values.equal(Double.NaN, 1.0d));
        assertFalse(Values.equal(1.0d, 2.0d));
        assertFalse(Values.equal(1L, 2.0d));
    }

    @Test
    @DisplayName("a type instance is equal by value and a class instance by identity")
    void equalityFollowsTheDeclarationKind() {
        assertEquals("true", Scripts.display("type P(x: Int)\nP(1) == P(1)"));
        assertEquals("true", Scripts.display("type P(x: Int)\nP(1) == P(1.0)"));
        assertEquals("false", Scripts.display("class C(x: Int)\nC(1) == C(1)"));
        assertEquals("true", Scripts.display("enum E { A }\nE.A == E.A"));
        assertEquals("false", Scripts.display("enum E { A, B }\nE.A == E.B"));
        // One run per declaration: a record is only equal to a twin made by the
        // same class, and a class is declared once per run.
        MandelaList records = (MandelaList) Scripts.run(
                "type P(x: Int)\n[P(1), P(1.0), P(2)]");
        MandelaInstance record = (MandelaInstance) records.get(0);
        MandelaInstance twin = (MandelaInstance) records.get(1);
        MandelaInstance third = (MandelaInstance) records.get(2);
        assertEquals(record, twin);
        assertEquals(record.hashCode(), twin.hashCode(),
                "two records the language calls equal must hash the same");
        assertNotEquals(record, third);
        MandelaList windows = (MandelaList) Scripts.run(
                "class C(x: Int)\n[C(1), C(1)]");
        MandelaInstance window = (MandelaInstance) windows.get(0);
        MandelaInstance other = (MandelaInstance) windows.get(1);
        assertFalse(window.equals(other));
        assertFalse(Values.equal(window, other));
        assertEquals(window, window);
        assertEquals(window.hashCode(), window.hashCode());
    }

    @Test
    @DisplayName("equal containers hash equal, so a host can put them in a set")
    void hashCodeAgreesWithEquality() {
        MandelaList ints = new MandelaList(List.<Object>of(1L, "a"));
        MandelaList doubles = new MandelaList(List.<Object>of(1.0d, "a"));
        assertTrue(Values.equal(ints, doubles));
        assertEquals(ints.hashCode(), doubles.hashCode());
        MandelaMap one = new MandelaMap();
        one.put("a", 1L);
        one.put("b", 2L);
        MandelaMap other = new MandelaMap();
        other.put("b", 2.0d);
        other.put("a", 1.0d);
        assertTrue(Values.equal(one, other));
        assertEquals(one.hashCode(), other.hashCode(),
                "a map's hash cannot depend on the order its keys were written");
        // A value that is not equal may share a hash, but the two must not be
        // confused for each other by a set.
        assertNotEquals(ints, new MandelaList(List.<Object>of("a", 1L)));
        List<Object> asList = new ArrayList<>(List.of(1L, "a"));
        assertEquals(ints, new MandelaList(asList));
        assertEquals(ints.hashCode(), new MandelaList(asList).hashCode());
    }

    @Test
    @DisplayName("hash follows the documented formula, not merely agreement")
    void hashFollowsTheDocumentedFormula() {
        // Agreement between two equal values is necessary but not sufficient: a bent
        // multiplier or a return flattened to 0 keeps equal values equal, so each arm
        // is pinned to the number the documented formula produces. The expected side
        // is computed from the JDK primitives directly, never by calling the method
        // under test on the same input.
        // A number hashes through its double form, so an Int and its Double agree.
        assertEquals(Double.hashCode(1.0d), Values.hash(1L));
        assertEquals(Double.hashCode(1.5d), Values.hash(1.5d));
        // An empty list is the seed 1; a PrimitiveReturn would flatten it to 0.
        assertEquals(1, Values.hash(new MandelaList()));
        // out = 31 * out + hash(item), folded over the items in order.
        assertEquals(31 * (31 * 1 + Double.hashCode(1.0d)) + Double.hashCode(2.0d),
                Values.hash(new MandelaList(List.<Object>of(1L, 2L))));
        // An empty map is 0, so a non-empty one is what pins the map arm:
        // out += key.hashCode() + 31 * hash(value).
        MandelaMap map = new MandelaMap();
        map.put("a", 1L);
        assertEquals("a".hashCode() + 31 * Double.hashCode(1.0d), Values.hash(map));
        // Anything else keeps the hash the JDK gives it; a PrimitiveReturn to 0 would
        // erase it.
        assertEquals("a".hashCode(), Values.hash("a"));
        assertEquals(Boolean.TRUE.hashCode(), Values.hash(true));
    }

    @Test
    @DisplayName("an instance hashes by identity or by value, per its kind")
    void instanceHashFollowsTheDeclarationKind() {
        // A class instance is identical to itself, so its hash is its identity --
        // never 0, which is what a PrimitiveReturn on that arm would give.
        MandelaInstance window = (MandelaInstance) Scripts.run("class C(x: Int)\nC(1)");
        assertEquals(System.identityHashCode(window), Values.hash(window));
        // A type instance is equal by value, so its hash folds its owner's identity
        // with its fields: identityHashCode(owner) * 31 + hash(fields).
        MandelaInstance record = (MandelaInstance) Scripts.run("type P(x: Int)\nP(1)");
        assertEquals(System.identityHashCode(record.owner()) * 31
                        + Values.hash(record.fields()),
                Values.hash(record));
    }

    @Test
    @DisplayName("a list and a map holding the same numbers are not equal")
    void containersOfDifferentShapesDiffer() {
        MandelaList list = new MandelaList(List.<Object>of(1L));
        MandelaMap map = new MandelaMap();
        map.put("0", 1L);
        assertFalse(Values.equal(list, map));
        assertFalse(Values.equal(list, "a"));
        assertFalse(Values.equal(map, DOMAIN));
    }

    @Test
    @DisplayName("the same rule reaches a script through ==, in and distinct")
    void structuralEqualityIsWhatAScriptReads() {
        assertEquals("true", Scripts.display("[1] == [1.0]"));
        assertEquals("true", Scripts.display("[[1]] == [[1.0]]"));
        assertEquals("true", Scripts.display("let m = {\"a\": 1}\nm == {\"a\": 1.0}"));
        assertEquals("true", Scripts.display("[1] in [[1.0]]"));
        assertEquals("1", Scripts.display("[[1], [1.0]].distinct().length"));
        assertEquals("true", Scripts.display("use std.json\njson.parse(\"[1.0]\") == [1]"));
        assertEquals("false", Scripts.display("[1, 2] == [2, 1]"));
    }

    static Stream<Arguments> orders() {
        return Stream.of(
                Arguments.of("1 < 2", "true"),
                Arguments.of("2 < 1", "false"),
                Arguments.of("1 <= 1.0", "true"),
                Arguments.of("1.0 >= 1", "true"),
                Arguments.of("\"a\" < \"b\"", "true"),
                Arguments.of("\"B\" < \"a\"", "true"),
                Arguments.of("\"a\" >= \"a\"", "true"),
                Arguments.of("true < false", "TypeError"),
                Arguments.of("[1] < [2]", "TypeError"),
                Arguments.of("1 < \"a\"", "TypeError"),
                Arguments.of("null < 1", "TypeError"),
                Arguments.of("(1..2) < (1..3)", "TypeError"));
    }

    @ParameterizedTest(name = "{0} is {1}")
    @MethodSource("orders")
    @DisplayName("order is defined between numbers and between strings only")
    void ordersOnlyNumbersAndStrings(String source, String expected) {
        if ("TypeError".equals(expected)) {
            MandelaError refused = Scripts.failure(source);
            assertEquals("TypeError", refused.kind());
            assertTrue(refused.getMessage().contains("cannot order"), refused.getMessage());
        } else {
            assertEquals(expected, Scripts.display(source));
        }
    }

    @Test
    @DisplayName("the ordering helper answers the comparison the script makes")
    void compareIsTheSameWalk() {
        assertTrue(Values.compare(1L, 2L) < 0);
        assertTrue(Values.compare(2L, 1L) > 0);
        assertEquals(0, Values.compare(1L, 1.0d));
        assertTrue(Values.compare("a", "b") < 0);
        MandelaError refused = assertThrowsError(() -> Values.compare(1L, "a"));
        assertEquals("TypeError", refused.kind());
        assertEquals("cannot order a Int against a Str; use <, <=, >, >= only on"
                + " numbers or strings", refused.getMessage());
        // A NaN ranks last, so against a finite number Double.compare returns a
        // non-zero sign; a PrimitiveReturn flattening that arm to 0 would make a NaN
        // and a number compare equal.
        assertTrue(Values.compare(Double.NaN, 1.0d) > 0);
        assertTrue(Values.compare(1.0d, Double.NaN) < 0);
    }

    @Test
    @DisplayName("what two numbers answer as equal, they never answer as ordered")
    void equalityAndOrderAgreeAboutNumbers() {
        // Multiplying by -1 is how a script gets a negative zero; it prints as
        // "0.0", so it must also compare as 0.0 rather than as the next number
        // down. Java's Double.equals and Double.compare both refuse that, which is
        // why the walk spells the numbers out instead of delegating.
        assertEquals("true", Scripts.display("let z = 0.0 * -1\nz == 0.0"));
        assertEquals("false", Scripts.display("let z = 0.0 * -1\nz < 0.0"));
        assertTrue(Values.equal(-0.0d, 0.0d));
        assertEquals(0, Values.compare(-0.0d, 0.0d));
        // Two Ints that round to the same Double are still different values, so an
        // Int comparison cannot go through a double.
        assertEquals("true", Scripts.display(
                "9223372036854775807 > 9223372036854775806"));
        assertEquals("false", Scripts.display(
                "9223372036854775807 == 9223372036854775806"));
        assertTrue(Values.compare(9007199254740993L, 9007199254740992L) > 0);
        assertFalse(Values.equal(9007199254740993L, 9007199254740992L));
        // A NaN is only reachable through the host or a library refusal; the engine
        // keeps it equal to itself and last in an order, which is what lets a host
        // test for it without a second predicate.
        assertTrue(Values.equal(Double.NaN, Double.NaN));
        assertEquals(0, Values.compare(Double.NaN, Double.NaN));
    }

    // -- the numeric coercions ----------------------------------------------

    @Test
    @DisplayName("a value asked for as a number is widened, parsed or refused")
    void coercesToNumbers() {
        assertTrue(Values.isNum(1L));
        assertTrue(Values.isNum(1.5d));
        assertFalse(Values.isNum("1"));
        assertTrue(Values.isInt(1L));
        assertFalse(Values.isInt(1.5d));
        assertEquals(7L, Values.asLong(7L));
        assertEquals(0L, Values.asLong(1.5d), "asLong is the quiet Int read");
        assertEquals(0L, Values.asLong("x"));
        assertEquals(1.0d, Values.asDouble(true));
        assertEquals(0.0d, Values.asDouble(false));
        assertEquals(2.5d, Values.asDouble("2.5"));
        assertEquals(1.0d, Values.asDouble(1L));
        assertEquals(1.5d, Values.asDouble(1.5d));
        assertEquals(1L, Values.asInt(1.9d), "a Double truncates toward zero");
        assertEquals(-1L, Values.asInt(-1.9d));
        assertEquals(12L, Values.asInt(" 12 "));
        assertEquals(1L, Values.asInt("1.5"), "a fractional text is read and cut");
        assertEquals(1L, Values.asInt(true));
        assertEquals(9.0d, Values.asDouble("9"), "a numeric string is parsed");
        MandelaError notNumber = assertThrowsError(
                () -> Values.asDouble(new MandelaList()));
        assertEquals("TypeError", notNumber.kind());
        assertEquals("cannot use a List as a number", notNumber.getMessage());
        MandelaError notInt = assertThrowsError(() -> Values.asInt(new Ref(1L)));
        assertEquals("cannot use a Ref as an integer", notInt.getMessage());
    }

    @Test
    @DisplayName("the two parsers answer null rather than raising on what is not a number")
    void parsesNumbersLoosely() {
        assertEquals(12L, Values.parseLong(" 12 "));
        assertEquals(-3L, Values.parseLong("-3"));
        assertNull(Values.parseLong("1.5"));
        assertNull(Values.parseLong(""));
        assertNull(Values.parseLong("x"));
        assertEquals(1.5d, Values.parseNumber("1.5"));
        assertEquals(12.0d, Values.parseNumber("12"));
        assertNull(Values.parseNumber("x"));
        assertNull(Values.parseNumber("NaNx"));
    }

    // -- conversion (`as`) ---------------------------------------------------

    static Stream<Arguments> conversions() {
        MandelaInstance point = (MandelaInstance) Scripts.run(
                "type Point(x: Int, y: Int)\nPoint(1, 2)");
        MandelaClass declared = (MandelaClass) Scripts.run(
                "type Point(x: Int, y: Int)\nPoint");
        return Stream.of(
                Arguments.of(1L, "Str", "1"),
                Arguments.of("a", "String", "a"),
                Arguments.of(1.5d, "Str", "1.5"),
                Arguments.of(null, "Str", "null"),
                Arguments.of(true, "Str", "true"),
                // `as Any` is a checked cast to the top type: the value must arrive
                // as it was, because a script that asks for "any" asked for nothing.
                Arguments.of(1L, "Any", "1"),
                Arguments.of(1.5d, "Any", "1.5"),
                Arguments.of("x", "Any", "x"),
                Arguments.of(true, "Any", "true"),
                Arguments.of(null, "Any", "null"),
                Arguments.of(point, "Any", "Point(x: 1, y: 2)"),
                Arguments.of(1L, "Bool", "true"),
                Arguments.of(0L, "Bool", "false"),
                Arguments.of("", "Bool", "false"),
                Arguments.of(new MandelaList(), "Bool", "true"),
                Arguments.of("12", "Int", "12"),
                Arguments.of("1.5", "Int", "1"),
                Arguments.of(1.9d, "Int", "1"),
                Arguments.of(true, "Int", "1"),
                Arguments.of(1L, "Double", "1.0"),
                Arguments.of(true, "Double", "1.0"),
                Arguments.of("2.5", "Double", "2.5"),
                Arguments.of(1L, "Num", "1.0"),
                Arguments.of(1L, "Number", "1.0"),
                Arguments.of(1L, "Null", "null"),
                Arguments.of(point, "Point", "Point(x: 1, y: 2)"),
                Arguments.of(declared, "Point", "<type Point>"));
    }

    @ParameterizedTest(name = "{1} of {0} is {2}")
    @MethodSource("conversions")
    @DisplayName("every conversion the language names answers the type named")
    void convertsToEveryNamedType(Object value, String name, String expected) {
        assertEquals(expected, Values.display(Values.convert(value, name)));
    }

    static Stream<Arguments> refusedConversions() {
        return Stream.of(
                Arguments.of("x", "Int", "cannot use a Str as an integer"),
                Arguments.of("x", "Double", "cannot use a Str as a number"),
                Arguments.of(1L, "List", "cannot convert a Int to List"),
                Arguments.of(1L, "Map", "cannot convert a Int to Map"),
                Arguments.of(1L, "Point", "cannot convert a Int to Point"),
                Arguments.of(new MandelaList(), "Point",
                        "cannot convert a List to Point"));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("refusedConversions")
    @DisplayName("a conversion that does not exist says so by name")
    void refusesConversionsThatDoNotExist(Object value, String name, String message) {
        MandelaError refused = assertThrowsError(() -> Values.convert(value, name));
        assertEquals("TypeError", refused.kind());
        assertEquals(message, refused.getMessage());
    }

    @Test
    @DisplayName("`as Any` leaves the value alone in a script too")
    void asAnyIsTheIdentityConversion() {
        assertEquals("Int", Scripts.display("typeOf(1 as Any)"));
        assertEquals("Double", Scripts.display("typeOf(1.5 as Any)"));
        assertEquals("List", Scripts.display("typeOf([1] as Any)"));
        assertEquals("Bool", Scripts.display("typeOf(true as Any)"));
        assertEquals("true", Scripts.display("(1 as Any) + 1 == 2"));
        assertEquals("Str", Scripts.display("typeOf(1 as Str)"));
        assertEquals("Null", Scripts.display("typeOf(1 as Null)"));
    }

    @Test
    @DisplayName("a conversion to a declared class checks and a wrong one refuses")
    void convertsToADeclaredClass() {
        assertEquals("true", Scripts.display("type Point(x: Int)\nPoint(1) as Point"
                + " == Point(1)"));
        MandelaError refused = Scripts.failure("type Point(x: Int)\n1 as Point");
        assertEquals("TypeError", refused.kind());
        assertTrue(refused.getMessage().contains("to Point"), refused.getMessage());
    }

    // -- helpers -------------------------------------------------------------

    /**
     * Runs Java that the language would refuse, and returns the refusal.
     *
     * @param work the call under test
     * @return the error it raised
     */
    private static MandelaError assertThrowsError(Executable work) {
        return org.junit.jupiter.api.Assertions.assertThrows(MandelaError.class, work,
                "expected the value helper to refuse");
    }
}
