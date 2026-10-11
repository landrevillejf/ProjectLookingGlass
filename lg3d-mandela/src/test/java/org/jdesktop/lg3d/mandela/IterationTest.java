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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.values.MandelaClass;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaInstance;
import org.jdesktop.lg3d.mandela.values.MandelaIterator;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.MandelaRange;
import org.jdesktop.lg3d.mandela.values.Ref;
import org.jdesktop.lg3d.mandela.values.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The cursor a {@code for} loop drives, and what each container hands it.
 *
 * <p>One class answers for six shapes, so the interesting surface is the table:
 * which source produces which {@code key} and which {@code value} per trip, what
 * a source that cannot be iterated is told, and what the machine does after the
 * last element. Both halves are pinned here &mdash; the cursor at the Java level,
 * because a host that reads one directly gets this exact behaviour, and the
 * {@code for} statement at the script level, because that is where the rule an
 * author learns lives.</p>
 *
 * <p>The one claim that needs saying out loud: a single-name loop over a map binds
 * the <em>value</em>, not the key. Every container hands {@code for (x in …)} its
 * values, so a map is not the exception; the pair spellings are how the name or
 * the index beside a value is reached.</p>
 */
class IterationTest {

    /** The declarations the script cases below run against. */
    private static final String TYPES = "type P(x: Int, y: Int)\nenum E { A, B }\n";

    /**
     * Drains a cursor and records every trip as {@code key=value}, in the
     * language's own notation so an index and a one-character string differ.
     */
    private static List<String> drain(Object value) {
        MandelaIterator cursor = MandelaIterator.of(value);
        List<String> trips = new ArrayList<>();
        while (cursor.advance()) {
            trips.add(Values.repr(cursor.key()) + "=" + Values.repr(cursor.value()));
        }
        return trips;
    }

    /** Runs a {@code for} statement and returns the list its body visited. */
    private static String loop(String header, String body) {
        return Scripts.display(TYPES + "var out = []\nfor " + header
                + " { out" + body + " }\nout");
    }

    // -- one trip per container ---------------------------------------------

    static Stream<Arguments> cursors() {
        MandelaList list = new MandelaList();
        list.add(1L);
        list.add("a");
        MandelaMap pairs = new MandelaMap();
        pairs.put("a", 1L);
        pairs.put("b", "x");
        return Stream.of(
                Arguments.of(list, "[0=1, 1=\"a\"]"),
                Arguments.of(new MandelaList(), "[]"),
                // A list, a range and a string are walked by position, so their key
                // is an Int; a map, an object and an enum are named, so theirs is a
                // Str. That is the whole difference the pair spellings expose.
                Arguments.of(pairs, "[\"a\"=1, \"b\"=\"x\"]"),
                Arguments.of(new MandelaRange(1, 5, false, 2), "[0=1, 1=3, 2=5]"),
                Arguments.of(new MandelaRange(1, 5, true, 1), "[0=1, 1=2, 2=3, 3=4]"),
                Arguments.of(new MandelaRange(8, 0, true, -1),
                        "[0=8, 1=7, 2=6, 3=5, 4=4, 5=3, 6=2, 7=1]"),
                Arguments.of("abc", "[0=\"a\", 1=\"b\", 2=\"c\"]"),
                Arguments.of("", "[]"),
                Arguments.of(Scripts.run(TYPES + "P(7, 8)"), "[\"x\"=7, \"y\"=8]"),
                Arguments.of(Scripts.run(TYPES + "E"),
                        "[\"A\"=E(name: \"A\", ordinal: 0), "
                                + "\"B\"=E(name: \"B\", ordinal: 1)]"),
                // A class instance iterates its assigned fields, so an object with
                // none is the empty sequence rather than a refusal.
                Arguments.of(Scripts.run("class C() { }\nC()"), "[]"),
                Arguments.of(null, "[]"));
    }

    @ParameterizedTest(name = "a cursor over {0}")
    @MethodSource("cursors")
    @DisplayName("every iterable hands the cursor a key and a value")
    void walksEveryContainer(Object source, String expected) {
        assertEquals(expected, drain(source).toString());
    }

    // -- what cannot be walked ----------------------------------------------

    static Stream<Arguments> refuses() {
        return Stream.of(
                Arguments.of(1L, "Int"),
                Arguments.of(1.5d, "Double"),
                Arguments.of(Boolean.TRUE, "Bool"),
                Arguments.of(new Ref(1L), "Ref"),
                Arguments.of(MandelaError.of("boom"), "Err"),
                Arguments.of(Values.ABSENT, "Absent"));
    }

    @ParameterizedTest(name = "a {1} is refused by name")
    @MethodSource("refuses")
    @DisplayName("a value that is not a sequence says which ones are")
    void refusesWhatIsNotIterable(Object source, String name) {
        MandelaError raised = assertCursorRefused(source);
        assertEquals("TypeError", raised.kind());
        assertEquals(name + " cannot be iterated; use a List, Map, Range, Str"
                + " or an enum", raised.getMessage());
    }

    private static MandelaError assertCursorRefused(Object source) {
        try {
            MandelaIterator.of(source);
            throw new AssertionError("expected the cursor to refuse");
        } catch (MandelaError raised) {
            return raised;
        }
    }

    @Test
    @DisplayName("a function is not a sequence, and a script hears the same words")
    void refusesACallableFromBothSides() {
        MandelaError raised = assertCursorRefused(Scripts.run("let f = { x -> x }\nf"));
        assertEquals("Fun cannot be iterated; use a List, Map, Range, Str or an enum",
                raised.getMessage());
        assertEquals("Int cannot be iterated; use a List, Map, Range, Str or an enum",
                Scripts.failure("for (x in 1) { x }").getMessage());
        assertEquals("Double cannot be iterated; use a List, Map, Range, Str"
                + " or an enum", Scripts.failure("for (x in 1.5) { x }").getMessage());
    }

    @Test
    @DisplayName("iterating null is the empty sequence, not an error")
    void nullIteratesAsNothing() {
        assertFalse(MandelaIterator.of(null).advance());
        assertEquals("[]", loop("(v in null)", ".add(v)"));
        // The rule exists so a script can loop a name it is not sure was bound;
        // the guard a language like Java forces on the author is not needed here.
        assertEquals("0", Scripts.display("var n = 0\nfor (x in null) { n = n + 1 }\nn"));
    }

    // -- the cursor's own clock ---------------------------------------------

    @Test
    @DisplayName("the current element survives a peek and dies with the walk")
    void advanceThenPeek() {
        MandelaList list = new MandelaList();
        list.add(1L);
        list.add(2L);
        MandelaIterator cursor = MandelaIterator.of(list);
        assertEquals("<iter List 0>", cursor.toString());
        assertTrue(cursor.advance());
        assertEquals(1L, cursor.value());
        // Peek twice: the same element, because a destructuring body reads the pair
        // in two steps and the cursor has no look-back to afford.
        assertEquals(1L, cursor.value());
        assertEquals(0L, cursor.key());
        assertEquals("<iter List 1>", cursor.toString());
        assertTrue(cursor.advance());
        assertEquals(2L, cursor.value());
        assertFalse(cursor.advance());
        // The finished cursor clears its element, so a body that reads after the
        // loop ends cannot see the last value a second time.
        assertNull(cursor.value());
        assertNull(cursor.key());
        assertFalse(cursor.advance(), "a drained cursor stays drained");
    }

    @Test
    @DisplayName("the cursor walks the container as it is, not as it was")
    void aMutationIsSeenByTheLoop() {
        // Appending inside the loop grows the trip count: the cursor indexes the
        // live list rather than a snapshot, which is what a java.util.Iterator does.
        MandelaList list = new MandelaList();
        list.add(1L);
        MandelaIterator cursor = MandelaIterator.of(list);
        assertTrue(cursor.advance());
        assertEquals(1L, cursor.value());
        list.add(2L);
        assertTrue(cursor.advance());
        assertEquals(2L, cursor.value());
        assertFalse(cursor.advance());
        assertEquals("[1, 2]", loop("(x in [1, 2])", ".add(x)"));
    }

    // -- the rule an author reads -------------------------------------------

    @Test
    @DisplayName("a single-name loop always binds the value, a map included")
    void theLoopBindsValues() {
        assertEquals("[1, 2]", loop("(v in {\"a\": 1, \"b\": 2})", ".add(v)"));
        assertEquals("[7, 8]", loop("(v in P(7, 8))", ".add(v)"),
                "an object loop reads its field values");
        assertEquals("[\"a\", \"b\", \"c\"]", loop("(c in \"abc\")", ".add(c)"));
        assertEquals("[1, 2, 3]", loop("(i in 1..3)", ".add(i)"));
        assertEquals("[\"A\", \"B\"]", loop("(c in E)", ".add(c.name)"));
    }

    @Test
    @DisplayName("the pair spellings bind the name or the index beside it")
    void thePairSpellingsBindBoth() {
        assertEquals("[\"a=1\", \"b=2\"]",
                loop("[k, v] in {\"a\": 1, \"b\": 2}", ".add(\"${k}=${v}\")"));
        assertEquals("[\"a=1\", \"b=2\"]",
                loop("k, v in {\"a\": 1, \"b\": 2}", ".add(\"${k}=${v}\")"));
        assertEquals("[\"0=9\", \"1=8\"]",
                loop("[k, v] in [9, 8]", ".add(\"${k}=${v}\")"));
        assertEquals("[\"0=1\", \"1=3\", \"2=5\"]",
                loop("[k, v] in 1..5 step 2", ".add(\"${k}=${v}\")"));
        assertEquals("[\"x=7\", \"y=8\"]",
                loop("[k, v] in P(7, 8)", ".add(\"${k}=${v}\")"));
        assertEquals("[\"A\", \"B\"]", loop("[k, v] in E", ".add(k)"));
    }

    // -- a range is the sequence the cursor trusts --------------------------

    static Stream<Arguments> counts() {
        return Stream.of(
                // The count is the distance between the bounds plus the unit the
                // inclusive end itself occupies, measured along the stride. Reading
                // that direction off the bounds instead of the stride is what cuts
                // the last value off every range that counts down: 8..0 step -1 is
                // nine numbers, not seven, because the walk ends at 0.
                Arguments.of("8..0 step -1", 9L),
                Arguments.of("8..0 step -2", 5L),
                Arguments.of("5..1 step -1", 5L),
                Arguments.of("0..8 step 2", 5L),
                Arguments.of("0..10 step 2", 6L),
                Arguments.of("1..5", 5L),
                Arguments.of("1..1", 1L),
                Arguments.of("2..2 step -1", 1L),
                Arguments.of("0..0 step -1", 1L),
                Arguments.of("0..<10 step 2", 5L),
                Arguments.of("1..<5", 4L),
                Arguments.of("8..<1 step -1", 7L),
                // A bound that coincides with the start is one value when it is
                // included and none when it is not, whichever way the stride points.
                Arguments.of("1..<1", 0L),
                // A stride that points away from the bound is empty, in both
                // directions: the rule that made 10..1 run zero times.
                Arguments.of("1..0", 0L),
                Arguments.of("10..1", 0L),
                Arguments.of("0..1 step -1", 0L),
                Arguments.of("1..0 step -1", 2L),
                Arguments.of("10..1 step -3", 4L));
    }

    @ParameterizedTest(name = "{0} produces {1} value(s)")
    @MethodSource("counts")
    @DisplayName("an inclusive range owes its end one more step")
    void countsTheEndInBothDirections(String source, long expected) {
        MandelaRange range = (MandelaRange) Scripts.run(source);
        assertEquals(expected, range.size());
        assertEquals(expected == 0L, range.isEmpty());
        assertEquals(String.valueOf(expected), Scripts.display("(" + source + ").length"));
        // And the walk, the count and the membership test are one answer: a value
        // is in the range exactly when the cursor would hand it out.
        List<Object> walked = new ArrayList<>();
        MandelaIterator cursor = MandelaIterator.of(range);
        while (cursor.advance()) {
            walked.add(cursor.value());
        }
        assertEquals(expected, (long) walked.size());
        assertEquals(String.valueOf(expected),
                Scripts.display("(" + source + ").toList().length"));
        for (long candidate = -2; candidate <= 12; candidate++) {
            assertEquals(walked.contains(candidate), range.contains(candidate),
                    "membership differs from the walk at " + candidate + " in " + range);
        }
    }

    @Test
    @DisplayName("a script counting down gets every value the bounds name")
    void countingDownIsWhatTheGuideSays() {
        assertEquals("[8, 7, 6, 5, 4, 3, 2, 1, 0]",
                Scripts.display("(8..0 step -1).toList()"));
        assertEquals("[8, 6, 4, 2, 0]", Scripts.display("(8..0 step -2).toList()"));
        assertEquals("[5, 4, 3, 2, 1]", Scripts.display("(5..1 step -1).toList()"));
        assertEquals("15", Scripts.display("(5..1 step -1).sum()"));
        assertEquals("9", Scripts.display("(8..0 step -1).length"));
        assertEquals("0", Scripts.display("(8..0 step -1).last"));
        assertEquals("0", Scripts.display("(8..0 step -2).last"));
        assertEquals("[0, 2, 4, 6, 8]", Scripts.display("(8..0 step -2).reversed()"));
        assertEquals("true", Scripts.display("0 in (8..0 step -1)"));
        assertEquals("true", Scripts.display("0 in (8..0 step -2)"));
        assertEquals("false", Scripts.display("1 in (8..0 step -2)"));
        assertEquals("true", Scripts.display("2 in (2..2 step -1)"));
        assertEquals("[8, 6, 4, 2, 0]", loop("i in 8..0 step -2", ".add(i)"));
        assertEquals("[5, 4, 3, 2, 1]", loop("i in 5..1 step -1", ".add(i)"));
    }

    @Test
    @DisplayName("the last value is the one the count stops on")
    void lastIsTheValueAtTheCount() {
        assertEquals(0L, new MandelaRange(8, 0, false, -1).last());
        assertEquals(0L, new MandelaRange(8, 0, false, -2).last());
        assertEquals(8L, new MandelaRange(0, 8, false, 2).last());
        assertEquals(10L, new MandelaRange(0, 10, false, 2).last());
        assertEquals(2L, new MandelaRange(2, 2, false, -1).last());
        assertEquals(1L, new MandelaRange(1, 1, false, -1).last());
        // An empty range reports where it would have started, which is what lets a
        // caller print it without a guard.
        assertEquals(10L, new MandelaRange(10, 1, false, 1).last());
        assertTrue(new MandelaRange(10, 1, false, 1).isEmpty());
    }

    @Test
    @DisplayName("bounds wider than a long are counted as wide, never as negative")
    void theWidestRangesAreStillCounted() {
        assertEquals(Long.MAX_VALUE, new MandelaRange(0, Long.MAX_VALUE, false, 1).size());
        assertEquals(Long.MAX_VALUE,
                new MandelaRange(Long.MIN_VALUE, Long.MAX_VALUE, false, 1).size());
        assertEquals(Long.MAX_VALUE,
                new MandelaRange(0, Long.MIN_VALUE, false, -1).size());
        // The sentinel is about the distance, not the inclusive flag: an exclusive
        // end only removes one value from a span a long cannot name, so the answer
        // stays "more than a count". The variant whose distance would need
        // MIN_VALUE - 1 is caught one line earlier, by the subtraction guard, and
        // answers the same way.
        assertEquals(Long.MAX_VALUE, new MandelaRange(0, Long.MIN_VALUE, true, -1).size());
        assertEquals(Long.MAX_VALUE, new MandelaRange(1, Long.MIN_VALUE, true, -1).size());
        assertTrue(new MandelaRange(0, Long.MAX_VALUE, false, 1).size() > 0L);
        assertEquals(2L, new MandelaRange(Long.MAX_VALUE - 1, Long.MAX_VALUE, false, 1)
                .size());
        assertEquals(Long.MAX_VALUE, new MandelaRange(0, Long.MAX_VALUE, true, 1).size());
        // Only a stride of 1 overruns the count itself; every wider stride fits, and
        // the unmeasurable sentinel must not be handed back for a range that is
        // exactly countable. Half of Long.MAX_VALUE, rounded up, plus the 0.
        assertEquals(Long.MAX_VALUE / 2 + 1L,
                new MandelaRange(0, Long.MAX_VALUE, false, 2).size());
    }

    @Test
    @DisplayName("a map the script built iterates in the order it was written")
    void insertionOrderIsTheWalkOrder() {
        MandelaMap built = (MandelaMap) Scripts.run("let m = {}\nm[\"z\"] = 1\n"
                + "m[\"a\"] = 2\nm");
        assertEquals("[\"z\"=1, \"a\"=2]", drain(built).toString());
        built.remove("z");
        assertEquals("[\"a\"=2]", drain(built).toString());
    }

    @Test
    @DisplayName("the cursor reads the live container, so a removal shifts the walk")
    void aMapIsWalkedByItsCurrentKeys() {
        MandelaMap pairs = new MandelaMap();
        pairs.put("a", 1L);
        pairs.put("b", 2L);
        MandelaIterator cursor = MandelaIterator.of(pairs);
        assertTrue(cursor.advance());
        assertEquals("a", cursor.key());
        pairs.remove("a");
        pairs.put("c", 3L);
        // The key list is re-read on every trip and the index does not know a key
        // went away, so the walk resumes at the second name it has now: ["b", "c"]
        // is entered at "c". That is what a java.util.Iterator over a live view
        // does too, and the cursor makes no promise the container cannot keep.
        assertTrue(cursor.advance());
        assertEquals("c", cursor.key());
        assertFalse(cursor.advance());
    }

    @Test
    @DisplayName("a class value with no constants is an empty walk")
    void aTypeIsNotASequenceOfConstants() {
        MandelaClass declared = (MandelaClass) Scripts.run(TYPES + "E");
        assertEquals(2, declared.constants().size());
        MandelaClass empty = (MandelaClass) Scripts.run("class C() { }\nC");
        assertEquals(0, empty.constants().size());
        assertEquals("[]", drain(empty).toString());
        MandelaClass record = (MandelaClass) Scripts.run(TYPES + "P");
        assertEquals("[]", drain(record).toString());
    }

    @Test
    @DisplayName("an instance created by a host iterates the fields it was given")
    void anInstanceWithoutADeclarationStillWalks() {
        MandelaClass point = (MandelaClass) Scripts.run(TYPES + "P");
        MandelaInstance made = new MandelaInstance(point);
        made.set("x", 1L);
        assertEquals("[\"x\"=1]", drain(made).toString());
        made.set("y", "b");
        assertEquals("[\"x\"=1, \"y\"=\"b\"]", drain(made).toString());
    }
}
