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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispatcher's promises, asked in the language rather than in Java.
 *
 * <p>{@link LanguageTest} and {@link ValuesTest} exercise the machine on the paths
 * a working program takes. This suite takes the other branch of every {@code if} the
 * dispatcher lives on: the call that lands on a value, the construction that counts
 * its arguments wrong, the {@code super} with nothing under it, the pattern that
 * reads a field that is not there, the assignment onto an immutable record, the
 * throw of something that is not an error. Each of those is a sentence the author
 * will read when the program fails, so the tests assert the whole message: a
 * diagnostic that names the wrong side is a defect the same size as a wrong result,
 * and it is the one nobody notices until a script is blamed for a machine bug.</p>
 *
 * <p>One case is deliberately <em>not</em> an error path: {@code expr ?? fallback}
 * with a left value that is present. The compiler emits a conditional jump for it
 * and the taken side of that jump is the whole of what {@code ??} means &mdash; a
 * suite that only ever coalesces a {@code null} tests the fallback, not the
 * operator.</p>
 */
class MachineDispatchTest {

    /** One declaration of every shape the dispatcher has to tell apart. */
    private static final String DECLARATIONS = "type P(x: Int)\n"
            + "class Row(a: Int, b: Int = 9)\n"
            + "class Full(a: Int = 5, b: Int = 9)\n"
            + "class Box { var v = 1\n  fun tag() { return \"t\" } }\n"
            + "enum Colour { Red, Blue }\n";

    // -- the jumps an ordinary program takes ----------------------------------

    @Test
    @DisplayName("?? keeps a left value that is merely present, not one that is truthy")
    void coalescingKeepsWhatIsThere() {
        assertEquals("1", Scripts.display("1 ?? 2"));
        assertEquals("fb", Scripts.display("null ?? \"fb\""));
        // The test that matters is the falsy-but-present value: `??` is not `||`.
        assertEquals("0", Scripts.display("0 ?? 9"));
        assertEquals("false", Scripts.display("false ?? true"));
        assertEquals("", Scripts.display("\"\" ?? \"x\""));
        // And a present left value is not evaluated twice, so the right side stays
        // untouched rather than merely unused.
        assertEquals("0", Scripts.display("var hits = 0\n"
                + "fun side() -> Int { hits = hits + 1\n  return 2 }\n"
                + "1 ?? side()\nhits"));
    }

    @Test
    @DisplayName("a list pattern is measured before it is bound")
    void aListPatternIsCheckedByLength() {
        assertEquals("2", Scripts.display("match [1, 2] {\n"
                + "  [a, b, c] => \"too many\",\n  [a, b] => \"2\",\n  _ => \"none\"\n}"));
        assertEquals("too many", Scripts.display("match [1, 2, 3] {\n"
                + "  [a, b] => \"2\",\n  [a, b, c] => \"too many\",\n  _ => \"none\"\n}"));
        assertEquals("1", Scripts.display("match [7] {\n  [a] => \"1\",\n  _ => \"none\"\n}"));
    }

    @Test
    @DisplayName("a match with no arm that fits is an error naming the value")
    void anUnmatchedPatternIsAnError() {
        MandelaError raised = Scripts.failure("match 3 { 1 => \"a\" }");
        assertEquals("MatchError", raised.kind());
        assertEquals("no pattern matched 3", raised.getMessage());
        // The value is reported in its repr, so a string arm that missed is
        // readable rather than confused with the word it spells.
        assertEquals("no pattern matched \"three\"",
                Scripts.failure("match \"three\" { \"one\" => 1 }").getMessage());
        assertEquals("no pattern matched [1, 2]",
                Scripts.failure("match [1, 2] { [a] => 1 }").getMessage());
    }

    // -- construction, counted the way the declaration says -------------------

    @Test
    @DisplayName("an enum cannot be constructed: its constants are already built")
    void constructingAnEnumIsRefused() {
        MandelaError raised = Scripts.failure(DECLARATIONS + "Colour(1)");
        assertEquals("ValueError", raised.kind());
        assertEquals("'Colour' is an enum; its constants are already built, so it"
                + " cannot be constructed", raised.getMessage());
        // A constant read by name is the way an enum is used, and it is the same
        // value every time.
        assertEquals("true", Scripts.display(DECLARATIONS + "Colour.Red == Colour.Red"));
        assertEquals("Blue", Scripts.display(DECLARATIONS + "str(Colour.Blue.name)"));
        // A name the enum does not have is refused on the type, not silently null.
        assertEquals("TypeError", Scripts.failure(DECLARATIONS + "Colour.Green").kind());
    }

    @Test
    @DisplayName("a construction counts arguments against the declaration")
    void constructionCountsArguments() {
        // A declaration with no default states one number, and the message says it
        // once rather than as a range.
        assertEquals("'P' takes 1 argument(s); the call gave 2",
                Scripts.failure(DECLARATIONS + "P(1, 2)").getMessage());
        assertEquals("'P' takes 1 argument(s); the call gave 0",
                Scripts.failure(DECLARATIONS + "P()").getMessage());
        // With a default the two numbers differ, and the reader has to learn both.
        assertEquals("'Row' takes 2 argument(s) (as few as 1 with defaults);"
                + " the call gave 3",
                Scripts.failure(DECLARATIONS + "Row(1, 2, 3)").getMessage());
        assertEquals("'Row' takes 2 argument(s) (as few as 1 with defaults);"
                + " the call gave 0",
                Scripts.failure(DECLARATIONS + "Row()").getMessage());
        // A field-less class takes nothing, which is the same sentence as any other.
        assertEquals("'Box' takes 0 argument(s); the call gave 1",
                Scripts.failure(DECLARATIONS + "Box(1)").getMessage());
        // A `var` field is storage the body initialises, not a slot a caller fills,
        // so the count a construction states is the initialiser's own -- and the
        // type is named, never the internal 'init' the author never wrote.
        assertEquals("'Counter' takes 0 argument(s); the call gave 1",
                Scripts.failure("class Counter { var total = 0\n  fun bump()"
                        + " { total = total + 1 } }\nCounter(1)").getMessage());
        assertEquals("'Boxy' takes 1 argument(s); the call gave 2",
                Scripts.failure("class Boxy(w: Int) { var hits = 0 }\n"
                        + "Boxy(3, 4)").getMessage());
        assertEquals("3", Scripts.display("class Boxy(w: Int) { var hits = 0\n"
                + "  fun touch() -> Int { hits = hits + 1\n  hits * w } }\nBoxy(3).touch()"));
    }

    @Test
    @DisplayName("a named argument has to name a declared field, once")
    void namedArgumentsMatchTheDeclaredFields() {
        MandelaError unknown = Scripts.failure(DECLARATIONS + "P(z: 1)");
        assertEquals("KeyError", unknown.kind());
        assertEquals("'P' has no field named 'z'; the declared fields are [x]",
                unknown.getMessage());
        MandelaError twice = Scripts.failure("type Q(x: Int, y: Int)\nQ(x: 1, x: 2)");
        assertEquals("ValueError", twice.kind());
        assertEquals("'x' was given twice", twice.getMessage());
        // Names may arrive in any order, and one that lands past the ones already
        // given widens the positional list so the earlier field keeps its default.
        // The display is always in declaration order, which is the order the type
        // declares and the order a pattern reads the parts back in.
        assertEquals("Q(x: 1, y: 2)", Scripts.display("type Q(x: Int = 5, y: Int = 9)\n"
                + "str(Q(y: 2, x: 1))"));
        assertEquals("Q(x: 5, y: 2)", Scripts.display("type Q(x: Int = 5, y: Int = 9)\n"
                + "str(Q(y: 2))"));
    }

    @Test
    @DisplayName("a named call cannot skip a field that has no default")
    void namedArgumentsMustCoverTheRequiredFields() {
        // Positionally this is refused: `Row()` says "as few as 1". Naming only the
        // later field used to widen the list with an absent hole, which let the
        // arity check pass and handed back an instance whose required field was the
        // machine's internal absent marker -- a value no script can name or compare.
        MandelaError raised = Scripts.failure(DECLARATIONS + "Row(b: 1)");
        assertEquals("ValueError", raised.kind());
        assertEquals("'Row' has no value for 'a', which is required and has no"
                + " default; a named call cannot skip it", raised.getMessage());
        // The same call is fine once the skipped field has a default to fall back on.
        assertEquals("Full(a: 5, b: 1)", Scripts.display(DECLARATIONS + "str(Full(b: 1))"));
    }

    @Test
    @DisplayName("names belong to a declaration, and a host function takes none")
    void namedArgumentsAreOnlyForDeclarations() {
        assertEquals("TypeError", Scripts.failure(DECLARATIONS
                + "fun f(a) { return a }\nf(x: 1)").kind());
        MandelaError raised = Scripts.failure("fun f(a) { return a }\nf(a: 1)");
        assertEquals("named arguments are only accepted when constructing a 'class'"
                + " or a 'type'", raised.getMessage());
        // A host function declares positions, not names, so a labelled call is the
        // author's mistake and says which function was involved.
        MandelaError atHost = Scripts.failure("host(x: 1)",
                Map.of("host", HostFunction.of("host", 1, args -> args.get(0))));
        assertEquals("TypeError", atHost.kind());
        assertEquals("the host function 'host' cannot be called with named arguments",
                atHost.getMessage());
    }

    // -- calls that land on something that is not a function ------------------

    @Test
    @DisplayName("calling a value says what may be called")
    void callingAValueIsRefused() {
        assertEquals("TypeError", Scripts.failure("let x = 1\nx(2)").kind());
        assertEquals("cannot call a Int; only a function, a method or a declared"
                + " type can be called", Scripts.failure("let x = 1\nx(2)").getMessage());
        assertEquals("cannot call a Str; only a function, a method or a declared"
                + " type can be called", Scripts.failure("\"ab\"(1)").getMessage());
        assertEquals("cannot call a Null; only a function, a method or a declared"
                + " type can be called", Scripts.failure("null(1)").getMessage());
    }

    @Test
    @DisplayName("a member that holds a value refuses the arguments it was given")
    void aValueMemberCannotBeCalled() {
        assertEquals("'v' on a Box is a value, not a function; it cannot take 1"
                + " argument(s)",
                Scripts.failure(DECLARATIONS + "Box().v(2)").getMessage());
        // A built-in member that reads as a value is the same case, and says so with
        // the receiver's own type name.
        assertEquals("'length' on a List is a value, not a function; it cannot take 1"
                + " argument(s)", Scripts.failure("[1, 2].length(1)").getMessage());
        // With no arguments the same read is a value, which is what makes the
        // refusal about the call rather than about the name.
        assertEquals("1", Scripts.display(DECLARATIONS + "Box().v"));
        assertEquals("2", Scripts.display("[1, 2].length"));
    }

    @Test
    @DisplayName("a host function is called with exactly the arguments it declares")
    void hostArityIsCheckedBeforeTheHostRuns() {
        HostFunction one = HostFunction.of("one", 1, args -> args.get(0));
        assertEquals("2", Scripts.display("one(2)", Map.of("one", one)));
        MandelaError raised = Scripts.failure("one(1, 2)", Map.of("one", one));
        assertEquals("ValueError", raised.kind());
        assertEquals("'one' takes 1 argument(s); the call gave 2", raised.getMessage());
        assertEquals("'one' takes 1 argument(s); the call gave 0",
                Scripts.failure("one()", Map.of("one", one)).getMessage());
    }

    // -- members read off a declaration --------------------------------------

    @Test
    @DisplayName("a method read off the type is refused, not handed back unbound")
    void aMethodOnTheTypeItselfIsNotAValue() {
        MandelaError raised = Scripts.failure(DECLARATIONS + "Box.tag");
        assertEquals("TypeError", raised.kind());
        assertEquals("'Box.tag' is a method; call it on an instance, not on the type",
                raised.getMessage());
        // On an instance the same read is a bound callback.
        assertEquals("t", Scripts.display(DECLARATIONS + "Box().tag()"));
        // A name neither field nor method reaches the built-in members of a Class.
        assertEquals("TypeError", Scripts.failure(DECLARATIONS + "Box.nope").kind());
    }

    @Test
    @DisplayName("an error value carries the four fields its report is made of")
    void errorsAreValuesWithFields() {
        // A `try` is a statement, so the fields are read into a name the script can
        // hand back -- which is also how a handler reports what it caught.
        assertEquals("Error|boom", Scripts.display("var out = \"\"\n"
                + "try { error(\"boom\") } catch (e) { out = e.kind + \"|\" + e.message }\n"
                + "out"));
        assertEquals("MyKind|boom", Scripts.display("var out = \"\"\n"
                + "try { error(\"boom\", \"MyKind\") } catch (e)"
                + " { out = e.kind + \"|\" + e.message }\nout"));
        // The location is the raise site, and the source is the name the host gave.
        assertEquals("true", Scripts.display("var ok = false\n"
                + "try { error(\"boom\") } catch (e)"
                + " { ok = e.line == 2 && e.source == \"test.mnd\" }\nok"));
        // A member that is not one of the four is refused on the type name, which is
        // how an author finds the field they meant.
        assertEquals("TypeError",
                Scripts.failure("try { error(\"boom\") } catch (e) { e.nope }").kind());
    }

    @Test
    @DisplayName("throw takes a value, and what it is decides the kind")
    void throwingShapesTheError() {
        // A string is the developer shortcut the guide promises.
        assertEquals("Error|no config file", Scripts.display("var out = \"\"\n"
                + "try { throw \"no config file\" } catch (e)"
                + " { out = e.kind + \"|\" + e.message }\nout"));
        // An instance is an error whose kind is its type, and its `message` field is
        // what the report shows.
        assertEquals("Boom|bang", Scripts.display("class Boom { var message = \"bang\" }\n"
                + "var out = \"\"\ntry { throw Boom() } catch (e)"
                + " { out = e.kind + \"|\" + e.message }\nout"));
        // With no message field the whole display form is used rather than null.
        assertEquals("Hole|Hole(v: 1)", Scripts.display("class Hole { var v = 1 }\n"
                + "var out = \"\"\ntry { throw Hole() } catch (e)"
                + " { out = e.kind + \"|\" + e.message }\nout"));
        // Throwing the declaration itself is a mistake the machine can name.
        assertEquals("Colour|the type 'Colour' was thrown without being constructed",
                Scripts.display("enum Colour { Red }\nvar out = \"\"\ntry { throw Colour }"
                        + " catch (e) { out = e.kind + \"|\" + e.message }\nout"));
        // A number, a list: anything becomes an Error carrying its display form.
        assertEquals("Error|[1, 2]", Scripts.display("var out = \"\"\n"
                + "try { throw [1, 2] } catch (e) { out = e.kind + \"|\" + e.message }\nout"));
    }

    @Test
    @DisplayName("a rethrown error keeps the place it first failed")
    void rethrowingKeepsTheOriginalSite() {
        // Line 3 raised it; line 7 only passed it on. Reporting line 7 would blame
        // the handler for the failure it is relaying.
        assertEquals("3|3", Scripts.display("var out = \"\"\ntry {\n  error(\"first\")\n"
                + "} catch (e) {\n  let original = e.line\n  try {\n    throw e\n  }"
                + " catch (f) {\n    out = str(original) + \"|\" + str(f.line)\n  }\n}\nout"));
        // A fresh throw at a later line does get its own location.
        assertEquals("5", Scripts.display("var out = 0\ntry {\n  let x = 1\n  try {\n"
                + "    error(\"second\")\n  } catch (e) {\n    out = e.line\n  }\n}"
                + " catch (outer) { out = 0 }\nout"));
    }

    // -- writes the machine has to refuse -------------------------------------

    @Test
    @DisplayName("a record, a declaration and a container all refuse a member write")
    void writesToWhatCannotBeWritten() {
        MandelaError record = Scripts.failure("type Pt(x: Int)\nlet p = Pt(1)\np.x = 2");
        assertEquals("ValueError", record.kind());
        assertEquals("'Pt' is a 'type' record and cannot be changed; build a new one"
                + " instead", record.getMessage());
        MandelaError declaration = Scripts.failure(DECLARATIONS + "Box.v = 2");
        assertEquals("TypeError", declaration.kind());
        assertEquals("the members of the type 'Box' are not writable; change an"
                + " instance", declaration.getMessage());
        assertEquals("cannot assign to 'foo' on a List",
                Scripts.failure("[].foo = 1").getMessage());
        assertEquals("cannot assign to 'foo' on a Str",
                Scripts.failure("\"ab\".foo = 1").getMessage());
        // A class instance, by contrast, is writable -- the guard is about the kind.
        assertEquals("2", Scripts.display("class Mut { var v = 1 }\nlet m = Mut()\n"
                + "m.v = 2\nm.v"));
    }

    @Test
    @DisplayName("extends is checked where the declaration is read, not at the call")
    void extendingSomethingThatIsNotAType() {
        MandelaError raised = Scripts.failure("let nope = 1\nclass C extends nope { }\n1");
        assertEquals("TypeError", raised.kind());
        assertEquals("'C' extends 'nope', which is not a type in scope at that point"
                + " in the script", raised.getMessage());
        // A name that is nothing at all is the same refusal.
        assertEquals("'D' extends 'missing', which is not a type in scope at that"
                + " point in the script",
                Scripts.failure("class D extends missing { }\n1").getMessage());
        // The positive control: the same shape with a real base is accepted.
        assertEquals("base:k", Scripts.display("class Base(tag: Str) { fun describe()"
                + " { return \"base:\" + tag } }\nclass K(tag: Str) extends Base { }\n"
                + "K(\"k\").describe()"));
    }

    @Test
    @DisplayName("super needs a base class, and the base class needs the method")
    void superSearchesOneLevelAndSaysWhenItFindsNothing() {
        MandelaError noBase = Scripts.failure("class A { fun f() { return super.g() } }\nA().f()");
        assertEquals("ValueError", noBase.kind());
        assertEquals("'super' has no base class to search here ('f' is a method of a"
                + " type that extends nothing)", noBase.getMessage());
        MandelaError noMethod = Scripts.failure("class B { }\nclass C extends B {"
                + " fun f() { return super.nope() } }\nC().f()");
        assertEquals("TypeError", noMethod.kind());
        assertEquals("'B' has no method named 'nope' for 'super' to reach",
                noMethod.getMessage());
    }

    @Test
    @DisplayName("a type pattern reads the fields in the order they were declared")
    void aTypePatternReadsFieldsByPosition() {
        assertEquals("1", Scripts.display("type Pt(x: Int)\nmatch Pt(1) { Pt(a) => a,"
                + " _ => 0 }"));
        assertEquals("3", Scripts.display("type Pt(x: Int, y: Int)\nmatch Pt(1, 3) {"
                + " Pt(a, b) => b, _ => 0 }"));
        // A pattern asking for more parts than the type declares is refused by the
        // machine, which knows both numbers, rather than binding a null.
        MandelaError raised = Scripts.failure("type Pt(x: Int)\nmatch Pt(1) {"
                + " Pt(a, b) => 1, _ => 0 }");
        assertEquals("TypeError", raised.kind());
        assertEquals("'Pt' declares 1 field(s), so it has no field number 1",
                raised.getMessage());
        // A value that is not an instance never reaches the read: the arm's type test
        // settles it first, and the wildcard takes over.
        assertEquals("0", Scripts.display("type Pt(x: Int)\nmatch 5 { Pt(a) => 1, _ => 0 }"));
    }

    @Test
    @DisplayName("a call that is fine leaves nothing on the operand stack")
    void anOrdinaryCallRestoresTheStack() {
        // Nested script calls push the result back onto the caller's stack; a leak
        // here would only show up as a later, unrelated failure.
        assertEquals("11", Scripts.display("fun twice(x: Int) { return x * 2 }\n"
                + "fun addOne(x: Int) { return twice(x) + 1 }\naddOne(5)"));
        assertEquals("[1, 2, 3]", Scripts.display("fun id(x) { return x }\n"
                + "[id(1), id(2), id(3)]"));
        // The same through a host boundary, which crosses the stack twice.
        HostFunction echo = HostFunction.of("echo", 1, args -> args.get(0));
        assertEquals("7", Scripts.display("echo(7)", Map.of("echo", echo)));
        assertEquals("7", Scripts.display("fun wrap(x) { echo(x) }\nwrap(7)",
                Map.of("echo", echo)));
    }

    @Test
    @DisplayName("a run that finishes reports no frames and no failure trace")
    void aCleanRunLeavesNoTrace() {
        Runtime runtime = Scripts.engine().build();
        runtime.run("fun b() { return 1 }\nfun a() { return b() }\na()", "test.mnd");
        assertTrue(runtime.trace().isEmpty(), "the chain is empty once the run returns");
        assertTrue(runtime.failureTrace().isEmpty(), "nothing escaped, so nothing to report");
        assertNull(runtime.get("missing"), "a name the script never set reads back null");
        // An uncaught failure keeps the frames it unwound through, outermost first.
        MandelaError raised = Scripts.failure("fun deep() { return 1 / 0 }\n"
                + "fun middle() { return deep() }\nmiddle()", runtime);
        assertEquals("RangeError", raised.kind());
        assertEquals("[<script> (test.mnd:3), middle (test.mnd:2), deep (test.mnd:1)]",
                runtime.failureTrace().toString());
    }
}
