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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every rule {@link org.jdesktop.lg3d.mandela.values.Operators} holds for the
 * values the machine can hand it.
 *
 * <p>The operator table in the language guide is written from that class, so the
 * table is only trustworthy if each row is answered here: the container each
 * operator accepts, the type it answers with, and the error it raises when the
 * question makes no sense. Written as two matrices rather than one test per
 * operator because the interesting part is the shape the two share &mdash; a list,
 * a map, a range, a string, an instance and a class all answer the same three
 * verbs {@code []}, {@code []=} and {@code .length}, and each has its own answer
 * for a key of the wrong type.</p>
 */
class OperatorRulesTest {

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @MethodSource("answers")
    @DisplayName("an operator answers the way the guide's table says")
    void answers(String source, String expected) {
        assertEquals(expected, Scripts.display(source), source);
    }

    /**
     * @return programs and the value each must answer with
     */
    static Stream<Arguments> answers() {
        return Stream.of(
                // additive on containers: a copy, never a mutation of either side
                Arguments.of("let a = [1, 2]\nlet b = [3]\nlet c = a + b\nc", "[1, 2, 3]"),
                Arguments.of("let a = [1, 2]\nlet b = [3]\nlet c = a + b\na", "[1, 2]"),
                Arguments.of("let a = [1, 2, 3]\nlet b = [2]\na - b", "[1, 3]"),
                Arguments.of("let a = [1, 2, 3, 2]\nlet b = [2]\na - b", "[1, 3]"),
                Arguments.of("let x = {\"a\": 1}\nlet y = {\"b\": 2}\nlet z = x + y\nz.length", "2"),
                Arguments.of("let x = {\"a\": 1}\nlet y = {\"b\": 2}\nlet z = x + y\nz[\"a\"]", "1"),
                Arguments.of("let x = {\"a\": 1}\nlet y = {\"b\": 2}\nlet z = x + y\nx.length", "1"),
                // text repeated by an Int, from either side
                Arguments.of("\"ab\" * 3", "ababab"),
                Arguments.of("3 * \"ab\"", "ababab"),
                // either side may be the text, and one time is the text itself
                Arguments.of("1 * \"a\"", "a"),
                Arguments.of("\"ab\" * 0", ""),
                Arguments.of("\"\" * 5", ""),
                // division answers in the type the arithmetic deserves
                Arguments.of("7 / 1", "7"),
                Arguments.of("7 / -1", "-7"),
                Arguments.of("6 / 3", "2"),
                Arguments.of("7 / 2", "3.5"),
                Arguments.of("-7 / 2", "-3.5"),
                Arguments.of("7.5 % 2", "1.5"),
                Arguments.of("7 % 2.5", "2.0"),
                Arguments.of("2.5 % 2", "0.5"),
                // power: exact while it fits, promoted past it, inverted below one
                Arguments.of("2.5 ** 2", "6.25"),
                Arguments.of("2 ** -1", "0.5"),
                Arguments.of("2 ** 0", "1"),
                Arguments.of("let d = 2.5\n-d", "-2.5"),
                Arguments.of("let l = [1, 2, 3]\nl[-1]", "3"),
                Arguments.of("let l = [1, 2, 3]\nl[-3]", "1"),
                // a range indexed answers membership, not an element
                Arguments.of("(0..10)[7]", "true"),
                Arguments.of("(0..10)[70]", "false"),
                Arguments.of("(0..10)[\"seven\"]", "false"),
                // a map keyed by text answers null where the name is not
                Arguments.of("let m = {\"a\": 1}\nm[\"b\"]", "null"),
                // strings count code units and answer one as text
                Arguments.of("\"abc\"[0]", "a"),
                Arguments.of("\"abc\"[-1]", "c"),
                Arguments.of("\"abc\".length", "3"),
                Arguments.of("let l = [1, 2]\nl.length", "2"),
                // `a..b` includes b, `a..<b` does not: the answer a reader gets from
                // the machine, which is what the guide has to say.
                Arguments.of("let r = (1..5)\nr.length", "5"),
                Arguments.of("let r = (1..<5)\nr.length", "4"),
                // length() as a function reaches the containers a member cannot
                Arguments.of("length([1, 2])", "2"),
                Arguments.of("length({\"a\": 1, \"b\": 2})", "2"),
                Arguments.of("length(\"abc\")", "3"),
                Arguments.of("length(1..5)", "5"),
                Arguments.of("class Point { var x = 1\n  var y = 2 }\nlength(Point())", "2"),
                Arguments.of("enum Color { RED, BLUE }\nlength(Color)", "2"),
                // membership asks a different question than indexing
                Arguments.of("1 in [1.0]", "true"),
                // A map is keyed by text, so a number asked of it is the text that
                // number displays as -- the same answer m[2] and m["2"] give.
                Arguments.of("2 in {\"2\": 9}", "true"),
                Arguments.of("1 in {\"2\": 9}", "false"),
                Arguments.of("\"2\" in {\"2\": 9}", "true"),
                Arguments.of("\"ell\" in \"hello\"", "true"),
                Arguments.of("2.5 in 1..5", "false"),
                Arguments.of("2 in 1..5", "true"),
                // element assignment reaches into the container it is given
                Arguments.of("let l = [1, 2]\nl[0] = 9\nl", "[9, 2]"),
                Arguments.of("let l = [1, 2]\nl[-1] = 9\nl", "[1, 9]"),
                Arguments.of("let m = {}\nm[\"k\"] = 1\nm[\"k\"]", "1"),
                Arguments.of("let m = {\"a\": {\"b\": 1}}\nm[\"a\"][\"b\"]", "1"),
                // an instance answers its fields by name, read and written
                Arguments.of("class Point { var x = 1 }\nlet p = Point()\np[\"x\"]", "1"),
                Arguments.of("class Point { var x = 1 }\nlet p = Point()\np[\"x\"] = 5\np.x", "5"),
                // an enum is a class of constants, indexable by name
                Arguments.of("enum Color { RED, BLUE }\nColor[\"RED\"].ordinal", "0"),
                Arguments.of("enum Color { RED, BLUE }\nColor[\"BLUE\"].name", "BLUE"),
                Arguments.of("enum Color { RED, BLUE }\nColor[\"BLUE\"].ordinal", "1"));
    }

    @ParameterizedTest(name = "[{index}] {0} is a {1}")
    @MethodSource("refused")
    @DisplayName("a question that makes no sense is refused with the words to fix it")
    void refused(String source, String kind, String fragment) {
        MandelaError raised = Scripts.failure(source);
        assertEquals(kind, raised.kind(), source + " -> " + raised.getMessage());
        assertTrue(raised.getMessage().contains(fragment),
                source + " -> " + raised.getMessage() + " (expected '" + fragment + "')");
    }

    /**
     * @return programs that must fail, with the error name and a fragment of the
     *         message that makes the mistake fixable
     */
    static Stream<Arguments> refused() {
        return Stream.of(
                // '+' is strict about mixing text with anything else
                Arguments.of("1 + \"a\"", "TypeError", "cannot mix"),
                Arguments.of("\"a\" + 1", "TypeError", "cannot mix"),
                Arguments.of("let l = [1]\nl + 1", "TypeError", "cannot add Int to List"),
                // the arithmetic operators say which one they were asked to do
                Arguments.of("\"a\" - 1", "TypeError", "works on numbers"),
                // '*' hands a text operand to the repeat before it asks for numbers,
                // so the only way to reach its numeric refusal is a container.
                Arguments.of("let l = [1]\nl * 2", "TypeError", "cannot apply '*'"),
                Arguments.of("let l = [1]\nl - 1", "TypeError", "cannot apply '-'"),
                Arguments.of("1 / \"a\"", "TypeError", "works on numbers"),
                Arguments.of("let l = [1]\nl % 2", "TypeError", "cannot apply '%'"),
                Arguments.of("let l = [1]\nl ** 2", "TypeError", "cannot apply '**'"),
                Arguments.of("\"ab\" * 2.5", "TypeError", "cannot repeat a string by Double"),
                Arguments.of("\"ab\" * -1", "RangeError", "cannot repeat a string -1 times"),
                Arguments.of("\"x\" * 3000000000", "LimitError", "larger than a Java string"),
                Arguments.of("let l = [1]\n-l", "TypeError", "cannot negate List"),
                // division and remainder refuse infinity and NaN silence
                Arguments.of("1 / 0", "RangeError", "division by zero"),
                Arguments.of("1 / 0.0", "RangeError", "division by zero"),
                Arguments.of("1 % 0", "RangeError", "remainder by zero"),
                Arguments.of("1.5 % 0.0", "RangeError", "remainder by zero"),
                // indexing: the container decides what a key may be
                Arguments.of("let n: Str = null\nn[0]", "NullError", "cannot index a null"),
                Arguments.of("let l = [1, 2]\nl[\"a\"]", "TypeError", "must be an Int"),
                Arguments.of("let l = [1, 2]\nl[2]", "IndexError", "outside a list of length 2"),
                Arguments.of("let l = [1, 2]\nl[-3]", "IndexError", "outside a list of length 2"),
                Arguments.of("let s = \"ab\"\ns[\"a\"]", "TypeError", "must be an Int"),
                Arguments.of("let s = \"ab\"\ns[2]", "IndexError", "outside a string of length 2"),
                Arguments.of("class Point { var x = 1 }\nPoint()[\"y\"]", "KeyError", "no field named"),
                Arguments.of("enum Color { RED }\nColor[\"GREEN\"]", "KeyError", "no constant named"),
                Arguments.of("let n = 5\nn[0]", "TypeError", "cannot be indexed"),
                // 'in' only searches the containers that hold things
                Arguments.of("1 in 5", "TypeError", "holds nothing to search"),
                // element assignment into something that has no elements
                Arguments.of("let l = [1]\nl[\"a\"] = 1", "TypeError", "must be an Int"),
                Arguments.of("let l = [1]\nl[3] = 1", "IndexError", "outside a list of length 1"),
                Arguments.of("let s = \"ab\"\ns[0] = \"c\"", "TypeError", "cannot assign into Str"),
                Arguments.of("class Point { var x = 1 }\nPoint()[\"y\"] = 1",
                        "KeyError", "no field named"),
                // .length is a property of containers, not of every value
                Arguments.of("let n = 5\nlength(n)", "TypeError", "has no length"));
    }
}
