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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jdesktop.lg3d.mandela.lang.LangException;
import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.Operators;
import org.jdesktop.lg3d.mandela.values.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The language's own behaviour, end to end: lexer, parser, compiler and machine
 * together, the way a program uses them.
 *
 * <p>Every case here is written as source and read as a displayed value, because
 * that is the contract a script author sees. The suite is the reason the four
 * stages may not be refactored independently: a change that makes
 * {@code "ab" * 2} or {@code for [k, v] in map} answer differently fails here, and
 * no unit test of the compiler alone would notice.</p>
 */
class LanguageTest {

    @Test
    @DisplayName("scalars, arithmetic and string interpolation")
    void scalarsAndInterpolation() {
        assertEquals("n=7 d=2.5 sum=9.5",
                Scripts.display("let n = 7\nlet d = 2.5\n\"n=${n} d=${d}"
                        + " sum=${n + d}\""));
        assertEquals("7", Scripts.display("1 + 2 * 3"));
        assertEquals("9", Scripts.display("(1 + 2) * 3"));
        assertEquals("1024", Scripts.display("2 ** 10"));
        // '/' is true division and answers in the type the arithmetic deserves: exact
        // between integers that divide, a Double otherwise. There is no integer-only
        // division operator to misread, and math.floor() is the documented way down.
        assertEquals("2", Scripts.display("6 / 3"));
        assertEquals("2.3333333333333335", Scripts.display("7 / 3"));
        assertEquals("1", Scripts.display("7 % 3"));
        assertEquals("-7", Scripts.display("- 7"));
        assertEquals("true true true false",
                Scripts.display("let a = 3 < 5\nlet b = \"x\" != \"y\"\n"
                        + "let c = a and b\nlet d = not c\n\"${a} ${b} ${c} ${d}\""));
    }

    @Test
    @DisplayName("functions: recursion, defaults, closures, first-class values")
    void functions() {
        assertEquals("610", Scripts.display("fun fib(k: Int) -> Int {\n"
                + "  if k < 2 { return k }\n"
                + "  return fib(k - 1) + fib(k - 2)\n}\nfib(15)"));
        assertEquals("3", Scripts.display("fun counter() -> Fun {\n"
                + "  var total = 0\n"
                + "  return { -> total += 1; total }\n}\nlet bump = counter()\n"
                + "bump()\nbump()\nbump()"));
        assertEquals("42", Scripts.display("fun make(base: Int) -> Fun {\n"
                + "  return { mul -> base * mul }\n}\nmake(6)(7)"));
        assertEquals("hi, ada | yo, ada", Scripts.display(
                "fun greet(who: Str, hello: Str = \"hi\") -> Str {"
                        + " \"${hello}, ${who}\" }\n"
                        + "greet(\"ada\") + \" | \" + greet(\"ada\", \"yo\")"));
        // A function value is a value: it can be bound, passed and called again.
        assertEquals("[2, 4, 6]", Scripts.display("fun twice(v: Int) -> Int { v * 2 }\n"
                + "let f = twice\n[1, 2, 3].map(f)"));
    }

    @Test
    @DisplayName("a trailing lambda is the last argument, and a block can still be a body")
    void trailingLambdas() {
        // The two spellings of the same call, which is the whole point of the form:
        // a callback reads as a block instead of as an argument buried in brackets.
        assertEquals("[2, 4, 6]", Scripts.display("[1, 2, 3].map { v -> v * 2 }"));
        assertEquals("[2, 4, 6]", Scripts.display("[1, 2, 3].map({ v -> v * 2 })"));
        assertEquals("0:7 1:8", Scripts.display("[7, 8].map { x, i -> \"${i}:${x}\" }.join(\" \")"));
        // The lambda is appended as the LAST argument, so a call that already takes
        // one argument keeps its ordinary order.
        assertEquals("30", Scripts.display("fun apply(n: Int, f: Fun) -> Int { f(n) }\n"
                + "apply(3) { x -> x * 10 }"));
        assertEquals("20,30", Scripts.display("[1, 2, 3] |> .filter { x -> x > 1 }"
                + " |> .map { x -> x * 10 } |> .join(\",\")"));
        assertEquals("2", Scripts.display("let m = {\"a\": 1}\n"
                + "m.mapValues { v -> v + 1 }.get(\"a\")"));
        // The same call must not steal the block of a loop or the arms of a match:
        // the first is a body and the second uses the arm arrow, which is not a
        // lambda header in this position.
        assertEquals("3", Scripts.display("fun items() -> List { [1, 2] }\nvar out = 0\n"
                + "for x in items() { out += x }\nout"));
        assertEquals("b", Scripts.display("fun f(x: Int) -> Int { x }\n"
                + "match f(2) { 1 => \"a\", _ => \"b\" }"));
        // A block body whose lambda is nested one level down is still a body.
        assertEquals("[3, 0, 2]", Scripts.display("[3, 1, 2].map { x -> if x > 1 { x } else { 0 } }"));
    }

    @Test
    @DisplayName("loops: ranges, break, continue, destructuring headers")
    void loops() {
        assertEquals("55", Scripts.display("var sum = 0\nfor i in 1..10 { sum += i }\nsum"));
        assertEquals("01", Scripts.display("var out = \"\"\nfor i in 0..<4 {\n"
                + "  if i == 2 { break }\n  out = out + str(i)\n}\nout"));
        assertEquals("134", Scripts.display("var out = \"\"\nfor x in [1, 2, 3, 4] {\n"
                + "  if x == 2 { continue }\n  out = out + str(x)\n}\nout"));
        assertEquals("a1b2", Scripts.display("var out = \"\"\n"
                + "for [k, v] in {\"a\": 1, \"b\": 2} { out = out + k + str(v) }\nout"));
        assertEquals("5", Scripts.display("var n = 0\nwhile n < 5 { n += 1 }\nn"));
        assertEquals("02468", Scripts.display("var out = \"\"\n"
                + "for i in 0..8 step 2 { out = out + str(i) }\nout"));
        // A stride walks downward too, which is the only way to count down: an
        // inclusive range whose bounds run backward is empty, by the same rule that
        // made 'for i in 10..1' run zero times before strides existed.
        assertEquals("8642", Scripts.display("var out = \"\"\n"
                + "for i in 8..0 step -2 { out = out + str(i) }\nout"));
        assertEquals("", Scripts.display("var out = \"\"\n"
                + "for i in 10..1 { out = out + str(i) }\nout"));
    }

    @Test
    @DisplayName("collections: literals, indexing, concatenation, repetition")
    void collections() {
        assertEquals("3 1 3 1", Scripts.display("let l = [1, 2, 3]\nlet m = {\"k\": 1}\n"
                + "\"${length(l)} ${l[0]} ${l[-1]} ${m[\"k\"]}\""));
        assertEquals("abcd|[1, 2]|abab", Scripts.display(
                "\"ab\" + \"cd\" + \"|\" + str([1] + [2]) + \"|\" + \"ab\" * 2"));
        assertEquals("3", Scripts.display("[1, 2, 3].length"));
        assertEquals("{\"a\": 1}", Scripts.display("let m = {\"a\": 1}\nm"));
    }

    @Test
    @DisplayName("a range is a value: stride, bounds, membership, closed-form sum")
    void ranges() {
        assertEquals("0..8 step 2", Scripts.display("0..8 step 2"));
        assertEquals("[0, 2, 4, 6, 8]", Scripts.display("(0..8 step 2).toList()"));
        // A downward stride stops below its bound the way an upward one stops above
        // it: 8, 6, 4, 2 -- 0 is never produced because the count is ceil(7 / 2).
        assertEquals("[8, 6, 4, 2]", Scripts.display("(8..0 step -2).toList()"));
        assertEquals("[2, 4, 6, 8]", Scripts.display("(8..0 step -2).reversed()"));
        // The count is ceil((to - from + 1) / stride), so an inclusive range that
        // does not land exactly on its bound still yields the last step below it.
        assertEquals("6", Scripts.display("(0..10 step 2).length"));
        assertEquals("5", Scripts.display("(0..<10 step 2).length"));
        assertEquals("10", Scripts.display("(0..10 step 2).last"));
        assertEquals("0", Scripts.display("(0..10 step 2).first"));
        assertEquals("2", Scripts.display("(0..10 step 2).step"));
        assertEquals("0", Scripts.display("(10..1).length"));
        assertEquals("true false", Scripts.display("let r = 0..10 step 2\n"
                + "\"${6 in r} ${3 in r}\""));
        assertEquals("30", Scripts.display("(0..10 step 2).sum()"));
        // A stride of 0 would never reach the bound, so it is refused rather than
        // turned into an endless loop -- the same decision as a division by zero.
        assertEquals("RangeError", Scripts.failure("for i in 0..8 step 0 { i }").kind());
    }

    @Test
    @DisplayName("'in' answers membership for every container")
    void membership() {
        assertEquals("true false", Scripts.display("\"${2 in [1, 2, 3]} ${5 in [1, 2, 3]}\""));
        // Membership uses the language's equality, so the numeric answer matches
        // '==' rather than Java's Long.equals(Double), which would be false.
        assertEquals("true", Scripts.display("1.0 in [1]"));
        assertEquals("true false", Scripts.display("let m = {\"a\": 1}\n"
                + "\"${\"a\" in m} ${1 in m}\""));
        assertEquals("true false", Scripts.display(
                "\"${\"lo\" in \"hello\"} ${\"LO\" in \"hello\"}\""));
        assertEquals("true false", Scripts.display("let r = 0..10 step 2\n"
                + "\"${6 in r} ${3 in r}\""));
        assertEquals("true false", Scripts.display(
                "\"${9 not in [1, 2]} ${1 not in [1, 9]}\""));
        // 'in' is a question about containers, so the wrong side is a TypeError and
        // not a silent false.
        assertEquals("TypeError", Scripts.failure("1 in 5").kind());
    }

    @Test
    @DisplayName("classes: fields, methods, inheritance, named construction")
    void classes() {
        assertEquals("shape:tri", Scripts.display("class Shape(name: Str) {\n"
                + "  fun describe() -> Str { \"shape:${name}\" }\n}\n"
                + "Shape(\"tri\").describe()"));
        assertEquals("derived(base:x)", Scripts.display("class Base(tag: Str) {\n"
                + "  fun label() -> Str { \"base:${tag}\" }\n}\n"
                + "class Derived(tag: Str) extends Base {\n"
                + "  fun label() -> Str { \"derived(\" + super.label() + \")\" }\n}\n"
                + "Derived(\"x\").label()"));
        assertEquals("6", Scripts.display("class Box(w: Int) {\n"
                + "  var hits = 0\n"
                + "  fun touch() -> Int { hits += 1; hits * w }\n}\n"
                + "let b = Box(3)\nb.touch()\nb.touch()"));
        assertEquals("2", Scripts.display("class Row(a: Int, b: Int = 9)\n"
                + "Row(b: 2, a: 1).b"));
        // A subclass reaches its base's members by bare name, the way Kotlin allows;
        // the compiler walks the declared hierarchy so `total` and `bump` resolve
        // without spelling `this.total`.
        assertEquals("5", Scripts.display("class Counter {\n  var total = 0\n"
                + "  fun bump(n: Int) -> Int { total = total + n; total }\n}\n"
                + "class Meter() extends Counter {\n  fun read() -> Int { total }\n}\n"
                + "let m = Meter()\nm.bump(5)\nm.read()"));
        assertEquals("5", Scripts.display("class Counter {\n  var total = 0\n"
                + "  fun bump(n: Int) -> Int { total = total + n; total }\n}\n"
                + "class Meter() extends Counter {\n  fun twice() -> Int { bump(2); bump(3) }\n}\n"
                + "Meter().twice()"));
        // Two levels up still counts, and a write to an inherited field stores on the
        // same member the base constructor initialised.
        assertEquals("3", Scripts.display("class A { var v = 1\n"
                + "  fun get() -> Int { v }\n}\n"
                + "class B() extends A { var w = 2 }\n"
                + "class C() extends B { fun both() -> Int { get() + w } }\n"
                + "C().both()"));
        assertEquals("7", Scripts.display("class A { var v = 1 }\n"
                + "class B() extends A { fun set(n: Int) { v = n } }\n"
                + "let b = B()\nb.set(7)\nb.v"));
        // Own members win over inherited ones, so a bare call inside an overriding
        // class is a send to the override -- and a class that merely inherits the
        // name still reaches the base's answer through its own inherited method.
        assertEquals("[\"child\", \"base\"]", Scripts.display("class A {\n"
                + "  fun name() -> Str { \"base\" }\n}\n"
                + "class B() extends A { fun name() -> Str { \"child\" } }\n"
                + "class C() extends A { fun ask() -> Str { name() } }\n"
                + "[B().name(), C().ask()]"));
        assertEquals("true", Scripts.display("type Point(x: Int, y: Int)\n"
                + "Point(1, 2) == Point(1, 2)"));
        assertEquals("RED 0", Scripts.display("enum Colour { RED, GREEN, BLUE }\n"
                + "\"${Colour.RED.name} ${Colour.RED.ordinal}\""));
    }

    @Test
    @DisplayName("errors: try, catch by kind, finally, defer")
    void errors() {
        assertEquals("body;caught:boom;done", Scripts.display("var log = \"\"\ntry {\n"
                + "  log = log + \"body;\"\n  throw \"boom\"\n} catch e: Error {\n"
                + "  log = log + \"caught:${e.message};\"\n} finally {\n"
                + "  log = log + \"done\"\n}\nlog"));
        assertEquals("TypeError", Scripts.display("var got = \"\"\ntry {\n"
                + "  1 + \"x\"\n} catch err {\n  got = err.kind\n}\ngot"));
        assertEquals("tf", Scripts.display("var log = \"\"\nfor i in 1..2 {\n"
                + "  try {\n    log = log + \"t\"\n    break\n"
                + "  } finally {\n    log = log + \"f\"\n  }\n}\nlog"));
        assertEquals("now", Scripts.display("fun worker() -> Str {\n"
                + "  defer \"later\"\n  return \"now\"\n}\nworker()"));
    }

    @Test
    @DisplayName("patterns: match with guards, destructuring, optional chaining")
    void patterns() {
        assertEquals("ace,high,low:4", Scripts.display("fun rank(v: Int) -> Str {\n"
                + "  match v {\n    1 => \"ace\",\n    n if n > 8 => \"high\",\n"
                + "    _ => \"low:${v}\"\n  }\n}\n"
                + "rank(1) + \",\" + rank(11) + \",\" + rank(4)"));
        assertEquals("78", Scripts.display("let [first, second] = [7, 8]\n"
                + "\"${first}${second}\""));
        assertEquals("7", Scripts.display("type Pair(a: Int, b: Int)\n"
                + "let Pair(x, y) = Pair(3, 4)\nx + y"));
        assertEquals("anon null", Scripts.display("let missing: Str = null\n"
                + "let name = missing ?? \"anon\"\nlet len = missing?.length\n"
                + "\"${name} ${len}\""));
        assertEquals("true 42 false", Scripts.display("let v: Any = 42\n"
                + "\"${v is Int} ${v as Str} ${v is Str}\""));
    }

    @Test
    @DisplayName("a host function is called like a script one, and may call back")
    void hostFunctions() {
        Map<String, Object> bindings = Map.of(
                "str", (HostFunction) a -> Values.display(a.get(0)),
                "length", (HostFunction) a -> Operators.length(a.get(0)),
                "shout", (HostFunction) a ->
                        String.valueOf(a.get(0)).toUpperCase() + "!");
        assertEquals("HEY!", Scripts.run("shout(\"hey\")", bindings));

        // The hard direction: a host function that receives script closures and
        // calls them, which is what an event loop and a UI panel have to do.
        Runtime.Builder builder = Scripts.engine();
        builder.bind("twice", HostFunction.of("twice", 1, items -> {
            StringBuilder out = new StringBuilder();
            Runtime self = builder.build();
            for (Object item : ((MandelaList) items.get(0)).items()) {
                out.append(self.machine().invoke((Callable) item, null, new Object[] {}));
            }
            return out.toString();
        }));
        assertEquals("246", builder.build()
                .run("let fs = [{ -> 2 }, { -> 4 }, { -> 6 }]\ntwice(fs)", "test.mnd"));
    }

    @Test
    @DisplayName("failures carry the kind a script can catch")
    void failures() {
        MandelaError type = Scripts.failure("1 + \"x\"");
        assertEquals("TypeError", type.kind());

        MandelaError runaway = Scripts.failure("var i = 0\nloop { i += 1 }");
        assertEquals("LimitError", runaway.kind());

        MandelaError deep = Scripts.failure("fun boom() -> Int { boom() }\nboom()");
        assertEquals("LimitError", deep.kind());

        assertEquals("TypeError", Scripts.failure("class Empty()\nEmpty().oops()").kind());

        LangException absent = assertThrows(LangException.class,
                () -> Scripts.run("noSuchThing + 1"));
        assertTrue(absent.getMessage().contains("noSuchThing"), absent.getMessage());

        // A base the compiler has not seen -- a host-registered class, a forward
        // reference -- contributes no names, so the honest 'unknown name' is kept
        // rather than guessing; spelling the receiver is the documented escape hatch.
        LangException hostParent = assertThrows(LangException.class,
                () -> Scripts.run("class F extends Missing {\n"
                        + "  fun go() -> Int { inheritedName }\n}\n1"));
        assertTrue(hostParent.getMessage().contains("inheritedName"),
                hostParent.getMessage());
    }

    @Test
    @DisplayName("a division by zero is an error, never an infinity")
    void division() {
        assertEquals("RangeError", Scripts.failure("1 / 0").kind());
        assertEquals("RangeError", Scripts.failure("1 % 0").kind());
        // A real division by zero is the same decision, not IEEE silence: a script
        // that prints inf has no way to notice it went wrong.
        assertEquals("RangeError", Scripts.failure("1.0 / 0.0").kind());
    }

    @Test
    @DisplayName("statements separated by ';' are one line, not one program")
    void separators() {
        assertEquals("3", Scripts.display("let a = 1; let b = 2; a + b"));
        assertEquals("[\"a\", \"b\"]", Scripts.display("let l = [\"a\", \"b\"]\nl"));
        // A program's last expression is its answer, and 'return' stays inside a
        // function so a script cannot read as if it were a callable.
        assertEquals("SyntaxError", Scripts.failure("return 1").kind());
    }
}
