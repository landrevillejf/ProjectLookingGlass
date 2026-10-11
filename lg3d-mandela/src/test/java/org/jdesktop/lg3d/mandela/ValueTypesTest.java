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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaClass;
import org.jdesktop.lg3d.mandela.values.MandelaClosure;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaInstance;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.MandelaRange;
import org.jdesktop.lg3d.mandela.values.Operators;
import org.jdesktop.lg3d.mandela.values.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The value types themselves, seen from the side that has to hold them: the Java
 * surface a host and the machine share.
 *
 * <p>A script only ever touches these objects through an operator or a {@code for}
 * loop, so the promises that are easy to lose are the ones written for the caller
 * on the other side of the boundary &mdash; what a {@link Callable} says about
 * itself when a host reflects on it, what a class says about its constructor, what
 * a wrapped Java exception keeps, and where a container says <em>no</em> in words.
 * Each test here is a line of {@code values} that a script cannot reach, not a
 * second way of asking something {@link ValuesTest} and {@link IterationTest}
 * already answer.</p>
 */
class ValueTypesTest {

    /** One declaration of every kind, so identity comparisons stay inside a run. */
    private static final String DECLARATIONS = "type P(x: Int, y: Int)\n"
            + "class Row(a: Int, b: Int = 9)\n"
            + "class Base() { var tag = \"b\" }\n"
            + "class Kid() extends Base { }\n"
            + "class Solo() { var tag = \"s\" }\n"
            + "enum E { A, B }\n";

    // -- what a callable reports about itself ---------------------------------

    @Test
    @DisplayName("a method read without a call is a real bound callback")
    void aBoundMethodIsAValue() {
        Runtime runtime = Scripts.engine().build();
        MandelaClosure bound = (MandelaClosure) runtime.run("class Box(n: Int) {\n"
                + "  var extra = 2\n"
                + "  fun read() -> Int { n + extra }\n}\n"
                + "let b = Box(5)\nb.read", "test.mnd");
        assertTrue(bound.isBound());
        MandelaInstance receiver = (MandelaInstance) bound.boundThis();
        assertEquals("Box", receiver.typeName());
        // Reading the method does not lose the declaring class, which is what a
        // later `super` inside the body has to resolve against.
        assertSame(receiver.owner(), bound.ownerClass());
        assertEquals("Box", bound.ownerClass().name());
        assertEquals("<fun read/0 bound>", Values.display(bound));
        // And the host can simply call it: the receiver travels with the value.
        assertEquals(7L, runtime.machine().invoke(bound, null, new Object[] {}));
    }

    @Test
    @DisplayName("a closure answers the Callable interface the machine dispatches on")
    void aClosureNamesItself() {
        MandelaClosure plain = (MandelaClosure) Scripts.run("fun label() -> Int { 1 }\nlabel");
        Callable asCallable = plain;
        // The name is the one the author wrote, because it is what a diagnostic
        // would print and what str() shows.
        assertEquals("label", asCallable.name());
        assertEquals(0, asCallable.arity());
        assertFalse(asCallable.isVariadic());
        assertFalse(plain.isBound(), "a plain function has no receiver attached");
        assertNull(plain.boundThis());
        assertNull(plain.ownerClass(), "and nothing declared it");
        assertEquals(0, plain.upvalues().length, "nothing was captured to carry");
        assertEquals("<fun label/0>", Values.display(plain));
        // A lambda with one parameter says the same thing about a different shape.
        Callable lambda = (Callable) Scripts.run("let f = { x -> x }\nf");
        assertEquals("", lambda.name(), "a lambda has no name of its own");
        assertEquals(1, lambda.arity());
        assertEquals("<fun lambda/1>", Values.display(lambda));
    }

    @Test
    @DisplayName("a host callable that is neither a closure nor a native is refused by name")
    void aForeignCallableIsNotVariadicAndNotCallable() {
        // The interface's own default: a host that implements Callable and says
        // nothing about variadic behaviour is taken at its word -- it takes what
        // it declares and no more.
        Callable foreign = new Callable() {
            @Override
            public String name() {
                return "host";
            }

            @Override
            public int arity() {
                return 1;
            }
        };
        assertFalse(foreign.isVariadic(), "the interface default is 'exactly arity()'");
        MandelaError raised = assertThrows(MandelaError.class, () -> Scripts.engine()
                .build().machine().invoke(foreign, null, new Object[] { 1L }));
        // The machine dispatches on the concrete value, not on the marker, so a
        // Callable it cannot enter is a host bug and is named as one.
        assertEquals("TypeError", raised.kind());
        assertEquals("'host' is not callable by this host", raised.getMessage());
    }

    // -- what a class reports about its declaration ---------------------------

    @Test
    @DisplayName("a declared type knows its constructor shape")
    void aClassReportsItsConstructor() {
        MandelaClass pair = (MandelaClass) Scripts.run(DECLARATIONS + "P");
        assertEquals(MandelaClass.Kind.TYPE, pair.kind());
        assertEquals("P", pair.name());
        assertEquals(List.of("x", "y"), pair.fieldNames());
        assertNull(pair.parent(), "a type has no inheritance");
        assertEquals(2, pair.declaredArity());
        // Both numbers agree, so a construction is either exact or wrong; the
        // Callable view says the same thing a host reflection API has to answer.
        assertEquals(2, pair.arity());
        assertFalse(pair.isVariadic());
        assertEquals("<P>", pair.toString());

        MandelaClass row = (MandelaClass) Scripts.run(DECLARATIONS + "Row");
        assertEquals(MandelaClass.Kind.CLASS, row.kind());
        assertEquals(List.of("a", "b"), row.fieldNames());
        assertEquals(2, row.declaredArity(), "two parameters were declared");
        assertEquals(1, row.arity(), "one is enough, the other has a default");
        // The two disagree only for a declaration with a default, and that is the
        // whole of what isVariadic() means for a constructor.
        assertTrue(row.isVariadic());

        MandelaClass status = (MandelaClass) Scripts.run(DECLARATIONS + "E");
        assertEquals(MandelaClass.Kind.ENUM, status.kind());
        assertEquals(List.of("A", "B"), new ArrayList<>(status.constants().keySet()));
        // An enum constant carries the two fields every one of them has, but the
        // declaration itself takes no arguments: there is no way to call `E(...)`,
        // so the two numbers disagree and isVariadic() is the honest answer.
        assertEquals(0, status.arity());
        assertEquals(List.of("name", "ordinal"), status.fieldNames());
        assertEquals(2, status.declaredArity());
        assertTrue(status.isVariadic());
    }

    @Test
    @DisplayName("subclasshood is a walk up, and it says no just as clearly")
    void inheritanceIsOneDirectional() {
        MandelaList types = (MandelaList) Scripts.run(DECLARATIONS + "[Base, Kid, Solo]");
        MandelaClass base = (MandelaClass) types.get(0);
        MandelaClass kid = (MandelaClass) types.get(1);
        MandelaClass solo = (MandelaClass) types.get(2);
        assertSame(base, kid.parent());
        assertNull(solo.parent());
        assertTrue(kid.isSubclassOf(base), "a class is under the one it extends");
        assertTrue(kid.isSubclassOf(kid), "and under itself -- the walk starts at this");
        assertFalse(base.isSubclassOf(kid), "never the other way");
        assertFalse(solo.isSubclassOf(base), "an unrelated class is not under anything");
        assertFalse(solo.isSubclassOf(kid));
    }

    @Test
    @DisplayName("a class value is a Class, an instance an Obj, and neither equals a number")
    void classAndInstanceAreDifferentValues() {
        assertEquals("true", Scripts.display(DECLARATIONS + "P is Class"));
        assertEquals("true", Scripts.display(DECLARATIONS + "P(1, 2) is Obj"));
        assertEquals("false", Scripts.display(DECLARATIONS + "P is Obj"));
        assertEquals("false", Scripts.display(DECLARATIONS + "P(1, 2) is Class"));
        assertEquals("Class", Scripts.display(DECLARATIONS + "typeOf(P)"));
        assertEquals("P", Scripts.display(DECLARATIONS + "typeOf(P(1, 2))"));
        // The declaration is not an instance of anything, so an instance's equality
        // has to refuse the value outright rather than compare fields it does not have.
        MandelaInstance twin = (MandelaInstance) Scripts.run(DECLARATIONS + "P(1, 2)");
        assertEquals("false", Scripts.display(DECLARATIONS + "let p = P(1, 2)\n"
                + "let one = 1\n\"${p == one}\""));
        assertFalse(twin.equals(1L));
        assertFalse(twin.equals("x"));
        assertFalse(twin.equals(Scripts.run(DECLARATIONS + "P")));
        assertTrue(twin.equals(twin));
    }

    @Test
    @DisplayName("an instance of a class is never equal to a twin of the same fields")
    void identityIsReservedForClasses() {
        // A type is structural; a class is a thing. Both answers are the same code
        // path, so the second one needs saying out loud: `kind() != TYPE` is what
        // makes two identically-filled windows two windows.
        MandelaInstance made = (MandelaInstance) Scripts.run(DECLARATIONS + "Base()");
        MandelaInstance other = (MandelaInstance) Scripts.run(DECLARATIONS + "Base()");
        assertTrue(made.equals(made));
        assertFalse(made.equals(other), "two runs, so not even the same declaration");
        assertEquals(Values.hash(made), made.hashCode());
        assertEquals("b", made.get("tag"));
        assertFalse(made.has("missing"));
        assertEquals(1, made.size());
    }

    // -- containers at the Java boundary --------------------------------------

    @Test
    @DisplayName("a map is equal to itself and a list walks like a Java collection")
    void containersAnswerTheJavaSideToo() {
        MandelaMap pairs = (MandelaMap) Scripts.run("let m = {\"a\": 1, \"b\": 2}\nm");
        assertTrue(pairs.equals(pairs), "identity is an answer, not a fallthrough");
        MandelaList list = (MandelaList) Scripts.run("[1, \"a\", null]");
        List<Object> walked = new ArrayList<>();
        for (Object item : list) {
            walked.add(item);
        }
        // The java.util view is the one a host that joins a script's list to its
        // own collection uses, so it hands out the elements in order and nothing else.
        assertEquals(Arrays.asList(1L, "a", null), walked);
        assertEquals(3, list.size());
        assertFalse(list.isEmpty());
    }

    @Test
    @DisplayName("a bad position and a zero stride say why in the caller's words")
    void refusalsAreSpecific() {
        MandelaList list = (MandelaList) Scripts.run("[1, 2, 3]");
        IndexOutOfBoundsException gone = assertThrows(IndexOutOfBoundsException.class,
                () -> list.get(5));
        assertEquals("index 5 is out of bounds for a list of 3", gone.getMessage());
        assertThrows(IndexOutOfBoundsException.class, () -> list.set(-9, 1L));
        assertThrows(IndexOutOfBoundsException.class, () -> list.removeAt(9));

        IllegalArgumentException noStride = assertThrows(IllegalArgumentException.class,
                () -> new MandelaRange(0, 8, false, 0L));
        // A step of 0 is the one range that can never end, so it is refused where
        // the value is built rather than where it is walked.
        assertEquals("a range step must not be zero", noStride.getMessage());
    }

    @Test
    @DisplayName("the range factories name the two forms a host is likely to want")
    void rangeFactoriesBuildRanges() {
        assertEquals(new MandelaRange(1, 3, false, 1L), MandelaRange.inclusive(1, 3));
        assertEquals(new MandelaRange(1, 3, true, 1L), MandelaRange.exclusive(1, 3));
        assertEquals(new MandelaRange(0, 8, false, 2L),
                MandelaRange.strided(0, 8, false, 2L));
        assertEquals("1..3", MandelaRange.inclusive(1, 3).toString());
        assertEquals("1..<3", MandelaRange.exclusive(1, 3).toString());
        assertEquals("0..8 step 2", MandelaRange.strided(0, 8, false, 2L).toString());
        assertEquals(3L, MandelaRange.inclusive(1, 3).size());
        assertEquals(2L, MandelaRange.exclusive(1, 3).size());
    }

    // -- numbers and hashes at the boundary -----------------------------------

    @Test
    @DisplayName("two doubles divide as doubles and never land on the integer path")
    void realDivisionIsReal() {
        // The integer branch above refuses 1/0 and keeps 4/2 a whole; this is the
        // other half of the rule, where the answer is a Double even when it is exact.
        assertEquals(0.5d, Operators.div(2.0d, 4.0d));
        assertEquals(1.0d, Operators.div(4.0d, 4L));
        assertEquals("0.5", Scripts.display("(2.0 / 4.0) * 1"));
        MandelaError zero = assertThrows(MandelaError.class,
                () -> Operators.div(1.0d, 0.0d));
        assertEquals("RangeError", zero.kind());
        assertEquals("division by zero", zero.getMessage());
    }

    @Test
    @DisplayName("the canonical hash has an answer for every value, including null")
    void hashesCoverTheWholeDomain() {
        assertEquals(0, Values.hash(null));
        assertEquals(Values.hash(1L), Values.hash(1.0d), "equal values, equal hashes");
        MandelaList list = (MandelaList) Scripts.run("[1, 2]");
        MandelaList twin = (MandelaList) Scripts.run("[1, 2]");
        assertEquals(Values.hash(list), Values.hash(twin));
        MandelaMap pairs = (MandelaMap) Scripts.run("let m = {\"a\": 1, \"b\": 2}\nm");
        MandelaMap reordered = (MandelaMap) Scripts.run("let m = {\"b\": 2, \"a\": 1}\nm");
        assertEquals(Values.hash(pairs), Values.hash(reordered),
                "insertion order is not part of the answer");
        assertEquals(Values.hash(Values.ABSENT), Values.hash(Values.ABSENT));
    }

    // -- a Java exception the script caused -----------------------------------

    @Test
    @DisplayName("a host exception is wrapped with its cause still attached")
    void aHostExceptionKeepsItsStory() {
        IllegalStateException boom = new IllegalStateException("the panel is gone");
        MandelaError wrapped = MandelaError.runtime("'ui.read' failed: the panel is gone",
                boom);
        assertEquals("RuntimeError", wrapped.kind());
        assertSame(boom, wrapped.getCause(), "the stack trace a host needs is the Java one");
        // The other constructor shape is for a failure the host describes but has no
        // throwable for; the cause stays null instead of becoming a placeholder.
        assertNull(MandelaError.runtime("no cause", null).getCause());
        // And a script that calls a host like that hears the same kind, which is the
        // discriminator its `catch` branches on -- and the machine says which name
        // it was that threw.
        MandelaError raised = assertThrows(MandelaError.class, () -> Scripts.run("boom()",
                Map.of("boom", HostFunction.of("boom", 0, args -> {
                    throw boom;
                }))));
        assertEquals("RuntimeError", raised.kind());
        assertSame(boom, raised.getCause());
        assertEquals("the host function 'boom' failed: the panel is gone",
                raised.getMessage());
    }

    @Test
    @DisplayName("an error carries its position to the copy a throw site makes")
    void anErrorCopyKeepsTheCause() {
        MandelaError wrapped = MandelaError.runtime("it failed",
                new IllegalArgumentException("inner"));
        MandelaError at = wrapped.at("script.mnd", 12);
        assertEquals("RuntimeError", at.kind());
        assertEquals("it failed", at.getMessage());
        assertEquals("script.mnd", at.sourceName());
        assertEquals(12, at.line());
        assertSame(wrapped.getCause(), at.getCause(), "a copy that loses the cause"
                + " loses the only part a host can debug");
        assertEquals("RuntimeError: it failed (at script.mnd:12)", at.toString());
        // A position the machine does not know stays unknown rather than guessed.
        assertEquals("RuntimeError: it failed", wrapped.toString());
        assertEquals(0, wrapped.line());
    }
}
