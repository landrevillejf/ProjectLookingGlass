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

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.Json;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * JSON in and out, as the language promises it.
 *
 * <p>{@code rt.Json} is the format every host needs &mdash; a Web Browser page
 * hands a script a JSON message, an app reads an HTTP body as one, and a panel
 * persists its settings as one. It was the one shipped module with no suite of
 * its own, so every branch below is a claim about a parser and a printer that a
 * script depends on and nothing was checking.</p>
 *
 * <p>Documents are <em>bound</em> as a host binding rather than pasted into the
 * snippet. A test whose expected text is {@code "\"a\\\\\\\\b\""} has two layers
 * of quoting between it and the byte under test, and a mistake in one of them is
 * invisible; one string in Java's notation, reaching the script as a page's
 * payload would, keeps the case readable and is the more faithful shape besides.</p>
 *
 * <p>Strictness is the point of several cases. The reader's own javadoc says it
 * "stays strict on purpose" because a document two parsers disagree about is a
 * bug nobody can find later &mdash; so each form RFC 8259 forbids must be refused
 * here, and each refusal must be one that {@code json.isValid} agrees with, since
 * a validator that disagrees with its own parser is worse than no validator.</p>
 */
class JsonTest {

    // -- reading -------------------------------------------------------------

    /**
     * Runs one snippet against a document delivered the way a host delivers one.
     *
     * @param document the JSON text, bound as {@code doc}
     * @param program  the snippet, which may read {@code doc}
     * @return the answer in the language's own notation
     */
    private static String answer(String document, String program) {
        return Values.display(engine(document).run("use std.json\n" + program, "json.mnd"));
    }

    /**
     * @param document the JSON text to hand the script
     * @return an engine that has already run the setup for one program
     */
    private static Runtime engine(String document) {
        return Mandela.engine(Scripts.TINY).bind("doc", document).build();
    }

    static Stream<Arguments> reads() {
        return Stream.of(
                // The type the document spells is the type the script receives: a
                // JSON integer is an Int, and only a fraction or an exponent makes
                // a Double, so a count read off an API never arrives as 3.0.
                Arguments.of("1", "typeOf(json.parse(doc))", "Int"),
                Arguments.of("1.0", "typeOf(json.parse(doc))", "Double"),
                Arguments.of("1e3", "typeOf(json.parse(doc))", "Double"),
                Arguments.of("\"1\"", "typeOf(json.parse(doc))", "Str"),
                Arguments.of("true", "typeOf(json.parse(doc))", "Bool"),
                Arguments.of("false", "typeOf(json.parse(doc))", "Bool"),
                Arguments.of("null", "json.parse(doc)", "null"),
                Arguments.of("1", "json.parse(doc)", "1"),
                Arguments.of("-1", "json.parse(doc)", "-1"),
                Arguments.of("0", "json.parse(doc)", "0"),
                Arguments.of("-0", "json.parse(doc)", "0"),
                // The whole 64-bit range survives; past it the table says a Double
                // rather than a wrong long, and the guide tells a producer to send
                // an exact big integer as text.
                Arguments.of("9223372036854775807", "json.parse(doc)", "9223372036854775807"),
                Arguments.of("99999999999999999999", "typeOf(json.parse(doc))", "Double"),
                Arguments.of("1.5", "json.parse(doc) * 2", "3.0"),
                Arguments.of("1E+3", "json.parse(doc)", "1000.0"),
                Arguments.of("1e-3", "json.parse(doc)", "0.001"),
                Arguments.of("-2.5e2", "json.parse(doc)", "-250.0"),
                Arguments.of("\"3.5\"", "double(json.parse(doc)) + 0.5", "4.0"),
                // Containers, and the indexing a script expects from them.
                Arguments.of("[1, 2, 3]", "json.parse(doc)[1]", "2"),
                Arguments.of("[1, 2, 3]", "json.parse(doc).length", "3"),
                Arguments.of("[]", "json.parse(doc).length", "0"),
                Arguments.of("[[1, 2], [3]]", "json.parse(doc)[1][0]", "3"),
                Arguments.of("{}", "json.parse(doc).length", "0"),
                Arguments.of("{\"k\": [1, 2]}", "json.parse(doc).k[1]", "2"),
                Arguments.of("{\"a\": {\"b\": {\"c\": 7}}}", "json.parse(doc).a.b.c", "7"),
                Arguments.of("{\"a\": null}", "json.parse(doc).has(\"a\")", "true"),
                // A key that spells a member name still reads as a key.
                Arguments.of("{\"length\": 9}", "json.parse(doc).length", "9"),
                // The four whitespace characters RFC 8259 allows, and nothing else.
                Arguments.of("  [ 1 , 2 ]  ", "json.parse(doc).sum()", "3"),
                Arguments.of("\t\n\r [1]\t\n\r ", "json.parse(doc).length", "1"),
                Arguments.of("[\n  1,\n  2\n]", "json.parse(doc).sum()", "3"),
                Arguments.of("{\"a\" : 1 , \"b\" : 2 }", "json.parse(doc).a + json.parse(doc).b", "3"),
                // Text, including the escapes a document really carries.
                Arguments.of("\"hello\"", "json.parse(doc).upper()", "HELLO"),
                Arguments.of("\"\"", "json.parse(doc).length", "0"),
                Arguments.of("\"\\u00e9\"", "json.parse(doc).length", "1"),
                Arguments.of("\"caf\\u00e9\"", "json.parse(doc).length", "4"));
    }

    @ParameterizedTest(name = "''{0}'' -> {1}")
    @MethodSource
    @DisplayName("a document answers in the type it spells")
    void reads(String document, String program, String expected) {
        assertEquals(expected, answer(document, program), document + " " + program);
    }

    @Test
    @DisplayName("every JSON escape expands to the one character it names")
    void escapes() {
        String document = "[\"a\\\"b\", \"c\\\\d\", \"e\\/f\", \"g\\bh\", \"i\\fj\","
                + " \"k\\nl\", \"m\\ro\", \"p\\tq\", \"\\u0041\"]";
        assertEquals("3,3,3,3,3,3,3,3,1", answer(document,
                "json.parse(doc).map({ s -> s.length }).join(\",\")"));
    }

    @Test
    @DisplayName("a round trip through the reader and the writer keeps every escape")
    void escapesRoundTrip() {
        String document = "[\"a\\\"b\", \"c\\\\d\", \"k\\nl\", \"p\\tq\", \"\\u0041\"]";
        // encode() of what parse() read must be a document the reader accepts again,
        // with every string the same length it was: the escape table cannot have a
        // hole in it in either direction.
        String encoded = answer(document, "json.encode(json.parse(doc))");
        assertEquals("[\"a\\\"b\",\"c\\\\d\",\"k\\nl\",\"p\\tq\",\"A\"]", encoded);
        assertEquals("3,3,3,3,1", answer(encoded,
                "json.parse(doc).map({ s -> s.length }).join(\",\")"));
    }

    static Stream<Arguments> refuses() {
        return Stream.of(
                // Nothing, or half of something.
                Arguments.of("", "ends in the middle of a value"),
                Arguments.of("  ", "ends in the middle of a value"),
                Arguments.of("[", "ends in the middle of a value"),
                Arguments.of("[1", "expected ',' or ']' in an array"),
                Arguments.of("[1,", "ends in the middle of a value"),
                Arguments.of("[1 2]", "expected ',' or ']' in an array"),
                Arguments.of("{", "the object is not closed"),
                Arguments.of("{\"a\"", "expected ':' after the key a"),
                Arguments.of("{\"a\": 1", "expected ',' or '}' in an object"),
                Arguments.of("{\"a\": 1 \"b\": 2}", "expected ',' or '}' in an object"),
                // The words are whole or not at all: `tru` is not true, and a
                // document that continues after its first value is not one value.
                Arguments.of("tru", "expected 'true'"),
                Arguments.of("fals", "expected 'false'"),
                Arguments.of("nul", "expected 'null'"),
                Arguments.of("1 2", "ends after the first value"),
                Arguments.of("xyz", "expected a value"),
                Arguments.of("+1", "expected a value"),
                Arguments.of("-", "expected a value"),
                // Trailing commas are the form JavaScript writes and JSON forbids.
                Arguments.of("[1,]", "expected a value"),
                Arguments.of("{\"a\":1,}", "an object key must be a quoted string"),
                // Keys are quoted, always.
                Arguments.of("{a:1}", "an object key must be a quoted string"),
                Arguments.of("{1:2}", "an object key must be a quoted string"),
                // Strings.
                Arguments.of("\"abc", "the string is not closed"),
                Arguments.of("\"abc\\", "the escape is cut off"),
                Arguments.of("\"a\tb\"", "raw control character"),
                Arguments.of("\"\\q\"", "is not a JSON escape"),
                Arguments.of("\"\\u12\"", "needs four hex digits"),
                Arguments.of("\"\\uZZZZ\"", "is not a \\uXXXX code point"),
                // Numbers: RFC 8259 allows no leading zero, no dangling point and
                // no infinity or NaN, whatever a JavaScript producer may send. An
                // exponent has to be followed by digits, in either spelling.
                Arguments.of("1.", "ends after the first value"),
                Arguments.of("1.x", "ends after the first value"),
                Arguments.of("1e", "is not a number"),
                Arguments.of("1E", "is not a number"),
                Arguments.of("1e+", "is not a number"),
                Arguments.of("1e3x", "ends after the first value"),
                Arguments.of("01", "leading zero"),
                Arguments.of("-01", "leading zero"),
                Arguments.of("[01]", "leading zero"),
                Arguments.of("NaN", "expected a value"),
                Arguments.of("Infinity", "expected a value"),
                Arguments.of("[1, NaN]", "expected a value"),
                // Comments are not JSON either, in either of their two forms.
                Arguments.of("// one", "expected a value"),
                Arguments.of("/* one */ 1", "expected a value"));
    }

    @ParameterizedTest(name = "''{0}''")
    @MethodSource
    @DisplayName("a document that is not JSON is refused, naming what is wrong")
    void refuses(String document, String fragment) {
        MandelaError raised = Scripts.failure("use std.json\njson.parse(doc)", engineAsBuilder(document));
        assertEquals("JsonError", raised.kind(), document + " -> " + raised.getMessage());
        assertTrue(raised.getMessage().contains(fragment),
                document + " -> " + raised.getMessage());
        // The validator and the parser cannot disagree, or a script that checks
        // before parsing still dies on the parse.
        assertEquals("false", answer(document, "json.isValid(doc)"), document);
    }

    /**
     * @param document the JSON text to hand the script
     * @return a builder over it, for the cases that expect a failure
     */
    private static Runtime.Builder engineAsBuilder(String document) {
        return Mandela.engine(Scripts.TINY).bind("doc", document);
    }

    @Test
    @DisplayName("a refusal says which line and column it came from")
    void position() {
        MandelaError raised = Scripts.failure("use std.json\njson.parse(doc)",
                engineAsBuilder("[1,\n  2,\n  x\n]"));
        // The 'x' is the third character of the third line, so a reader looking at
        // an API payload gets the position in the document rather than an offset.
        assertTrue(raised.getMessage().contains("at 3:3"), raised.getMessage());
    }

    @Test
    @DisplayName("nesting is capped on both sides of the boundary")
    void depthIsCapped() {
        // One level under the cap parses; one over it is refused rather than
        // allowed to walk off the end of the Java stack. The scalar at the bottom is
        // what makes the depth exact: an empty innermost array never asks for a value.
        assertEquals("true", answer(nested(Json.MAX_DEPTH), "json.isValid(doc)"));
        MandelaError tooDeep = Scripts.failure("use std.json\njson.parse(doc)",
                engineAsBuilder(nested(Json.MAX_DEPTH + 1)));
        assertTrue(tooDeep.getMessage().contains("nests deeper than 256"),
                tooDeep.getMessage());
        // The writer has the same ceiling, and a script can build the shape that
        // needs it only by pointing a list at itself.
        MandelaError cycle = Scripts.failure(
                "use std.json\nvar l = []\nl.push(l)\njson.encode(l)");
        assertEquals("ValueError", cycle.kind(), cycle.getMessage());
        assertTrue(cycle.getMessage().contains("self-referential"), cycle.getMessage());
        // A document that deep writes back out, so the cap is a guard and not a
        // limit on what a legitimate payload can carry.
        assertEquals("true", answer(nested(4),
                "json.encode(json.parse(doc)) == json.encode(json.parse(doc))"));
    }

    /**
     * @param levels how deep to nest
     * @return a document of that many open arrays, one value and that many closes
     */
    private static String nested(int levels) {
        return "[".repeat(levels) + "1" + "]".repeat(levels);
    }

    // -- writing -------------------------------------------------------------

    static Stream<Arguments> writes() {
        return Stream.of(
                Arguments.of("json.encode(null)", "null"),
                Arguments.of("json.encode(true)", "true"),
                Arguments.of("json.encode(false)", "false"),
                Arguments.of("json.encode(42)", "42"),
                Arguments.of("json.encode(-7)", "-7"),
                Arguments.of("json.encode(1.5)", "1.5"),
                // A whole Double keeps its point: JSON has one number type, and a
                // reader that sees 2 may hand back an integer the author did not mean.
                Arguments.of("json.encode(2.0)", "2.0"),
                Arguments.of("json.encode(0.1)", "0.1"),
                Arguments.of("json.encode(\"a\")", "\"a\""),
                Arguments.of("json.encode([])", "[]"),
                Arguments.of("json.encode([1, 2])", "[1,2]"),
                Arguments.of("json.encode([[1], []])", "[[1],[]]"),
                Arguments.of("json.encode({})", "{}"),
                Arguments.of("json.encode({\"a\": 1})", "{\"a\":1}"),
                Arguments.of("json.encode({\"a\": 1, \"b\": [2, null]})",
                        "{\"a\":1,\"b\":[2,null]}"),
                Arguments.of("json.encode([1, \"a\", null, true, {\"k\": 2.5}])",
                        "[1,\"a\",null,true,{\"k\":2.5}]"),
                // Text that needs escaping, quoted the way JSON says.
                Arguments.of("json.encode(\"a\\\"b\")", "\"a\\\"b\""),
                Arguments.of("json.encode(\"a\\\\b\")", "\"a\\\\b\""),
                Arguments.of("json.encode(\"a\\nb\")", "\"a\\nb\""),
                Arguments.of("json.encode(\"a\\tb\")", "\"a\\tb\""),
                Arguments.of("json.encode(\"a\\rb\")", "\"a\\rb\""),
                Arguments.of("json.encode(\"a b\")", "\"a b\""),
                // A key is escaped like any other text.
                Arguments.of("json.encode({\"a\\\"b\": 1})", "{\"a\\\"b\":1}"),
                // The module round-trips its own answer.
                Arguments.of("json.encode(json.parse(\"{\\\"k\\\":[1,2]}\"))",
                        "{\"k\":[1,2]}"),
                Arguments.of("json.isValid(json.encode([1, 2]))", "true"),
                // stringify is the debugging form: the same text where JSON reaches,
                Arguments.of("json.stringify({\"a\": [1, 2.5, null]})",
                        "{\"a\":[1,2.5,null]}"),
                Arguments.of("json.stringify(1.5)", "1.5"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource
    @DisplayName("a value writes as the document it means")
    void writes(String program, String expected) {
        assertEquals(expected, Scripts.display("use std.json\n" + program), program);
    }

    @Test
    @DisplayName("a number JSON cannot hold is refused by encode and quoted by stringify")
    void nonFiniteNumbers() {
        // math.acos(2) is a NaN and math.log(0) is -Infinity: both are Java doubles
        // the language can reach, and neither has a JSON form, so encode says so
        // instead of writing a bare NaN that no parser will read back.
        String nan = "use std.json\nuse std.math\njson.encode(math.acos(2))";
        MandelaError raised = Scripts.failure(nan);
        assertEquals("ValueError", raised.kind(), raised.getMessage());
        assertTrue(raised.getMessage().contains("json cannot hold NaN"),
                raised.getMessage());
        assertEquals("\"-Infinity\"", Scripts.display(
                "use std.json\nuse std.math\njson.stringify(math.log(0))"));
        assertEquals("\"NaN\"", Scripts.display(
                "use std.json\nuse std.math\njson.stringify(math.acos(2))"));
    }

    @Test
    @DisplayName("what has no JSON form is refused in one and readable in the other")
    void valuesWithNoJsonForm() {
        // A function and an instance of a script class are the two values a map
        // really does end up holding, and the strict form must refuse rather than
        // invent a rendering the reader could not restore.
        MandelaError function = Scripts.failure(
                "use std.json\njson.encode({ x -> x })");
        assertEquals("TypeError", function.kind(), function.getMessage());
        assertTrue(function.getMessage().contains("json cannot encode a"),
                function.getMessage());
        MandelaError instance = Scripts.failure(
                "use std.json\ntype Row(a: Int)\njson.encode(Row(1))");
        assertEquals("TypeError", instance.kind(), instance.getMessage());
        assertTrue(instance.getMessage().contains("cannot encode a"),
                instance.getMessage());
        // The lenient form keeps the line readable, which is what a log line is for.
        String shown = Scripts.display("use std.json\ntype Row(a: Int)"
                + "\njson.stringify(Row(1))");
        assertEquals("\"Row(a: 1)\"", shown);
    }

    // -- the same table the machine applies ---------------------------------

    @Test
    @DisplayName("a Java value handed to the writer crosses the table the machine uses")
    void hostValuesAreWidened() {
        // encode() and stringify() are public Java entry points, so a host may call
        // them with whatever Java has: a String length, a float off a sensor, a
        // java.util.Map of its own. Each of those is the Mandela value the embedding
        // table names, and the writer must see it as that value -- not refuse a
        // float it happily accepts as a double, and not special-case Integer alone.
        assertEquals("3", Json.encode("abc".length()));
        assertEquals("7", Json.encode((short) 7));
        assertEquals("7", Json.encode((byte) 7));
        assertEquals("1.5", Json.encode(1.5f));
        assertEquals("[1,2]", Json.encode(List.of(1, 2)));
        assertEquals("[1,2]", Json.encode(new int[] {1, 2}));
        assertEquals("{\"k\":\"v\"}", Json.stringify(Map.of("k", "v")));
        // A value with no Mandela form is still refused, with its class named.
        MandelaError refused = Scripts.failure(() -> Json.encode(new Object()));
        assertEquals("TypeError", refused.kind(), refused.getMessage());
        assertTrue(refused.getMessage().contains("Host"), refused.getMessage());
        assertTrue(Json.stringify(new Object()).startsWith("\"java.lang.Object"),
                Json.stringify(new Object()));
    }

    @Test
    @DisplayName("an exponent is written the way RFC 8259 spells one")
    void exponentCarriesItsSign() {
        // A double wide enough for the exponent form is the one place the writer's
        // number rendering is Java's rather than JSON's: Java prints 1.0E16 and
        // RFC 8259 requires a sign on the exponent, so a strict parser elsewhere
        // rejects a document this one wrote. The writer fixes the spelling rather
        // than leaving two parsers to disagree about it.
        assertEquals("1.0E+16", Json.encode(1e16));
        assertEquals("1.0E+20", Json.encode(1e20));
        assertEquals("-1.0E+16", Json.encode(-1e16));
        // The forms already legal need no change, and the round trip holds.
        assertEquals("1.0E-7", Json.encode(1e-7));
        assertEquals("1.0E+16", Json.stringify(Json.parse("1.0E+16")));
        assertEquals(1.0e16, (Double) Json.parse("1.0E+16"), 0.0d);
    }

    @Test
    @DisplayName("text that needs an escape writes the way the reader reads it back")
    void controlText() {
        // The two escaping paths in the writer must agree with the reader's table:
        // a name that carries a backspace, a form feed or any other control
        // character survives the trip out and back, and a document holding it is
        // one a strict parser will accept rather than trip over.
        String awkward = "a\bb\fc\u0001d\u001fe";
        String document = Json.encode(awkward);
        assertEquals("\"a\\bb\\fc\\u0001d\\u001fe\"", document);
        assertEquals(awkward, Json.parse(document));
        assertEquals(true, Json.canParse(document));
        // The same text reached the writer from a script, and reaches the log line
        // the lenient form writes.
        assertEquals(document, Json.stringify(awkward));
    }

    @Test
    @DisplayName("reading nothing is a refusal, not a null pointer")
    void nullText() {
        MandelaError raised = Scripts.failure(() -> Json.parse(null));
        assertEquals("JsonError", raised.kind(), raised.getMessage());
        assertTrue(raised.getMessage().contains("cannot read JSON from nothing"),
                raised.getMessage());
        assertEquals(false, Json.canParse(null));
        assertEquals(true, Json.canParse("1"));
    }
}
