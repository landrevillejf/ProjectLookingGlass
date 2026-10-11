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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What happens to an {@code Int} at the edge of the 64-bit range.
 *
 * <p>The guide promises a script never rolls over: an integer operation that would
 * leave the range is promoted to {@code Double} rather than wrapping to a negative
 * number, and never raises. That promise is what distinguishes a desktop script that
 * counts to a billion and a half from one that silently starts again at minus a
 * billion and a half, so it is worth pinning as its own suite rather than as a
 * footnote in the arithmetic tests.</p>
 *
 * <p>Every case is checked from both sides of the operator where the language allows
 * it. A promoted answer that happens to agree with its left operand is exactly the
 * bug these tests were written for, and only the mirror-image case can see it.</p>
 */
class ArithmeticOverflowTest {

    /** {@code 2**63 - 1}, the largest {@code Int} the machine has. */
    private static final String MAX = "9223372036854775807";

    /** {@code 2**62}, the largest {@code Int} whose double is still exact. */
    private static final String HALF = "4611686018427387904";

    /** {@code 2**63}: the answer a promotion of {@link #MAX} must land on. */
    private static final String TOO_BIG = "9.223372036854776E18";

    @Test
    @DisplayName("an addition past the range promotes, from either side")
    void additionPromotes() {
        assertEquals(TOO_BIG, Scripts.display(MAX + " + 1"));
        assertEquals(TOO_BIG, Scripts.display("1 + " + MAX));
        // The double answer carries the sum, not one of the operands it was built from.
        assertEquals("-" + TOO_BIG, Scripts.display("0 - " + MAX + " - 10"));
    }

    @Test
    @DisplayName("a subtraction past the range promotes, from either side")
    void subtractionPromotes() {
        assertEquals(TOO_BIG, Scripts.display(MAX + " - (-2)"));
        assertEquals("-" + TOO_BIG, Scripts.display("0 - " + MAX + " - 2"));
        // One short of the promotion: the bottom of the range is still an exact Int,
        // so the guard cannot be accused of giving up on integers too early.
        assertEquals("-9223372036854775808", Scripts.display("0 - 1 - " + MAX));
    }

    @Test
    @DisplayName("a multiplication past the range promotes, from either side")
    void multiplicationPromotes() {
        assertEquals(TOO_BIG, Scripts.display(HALF + " * 2"));
        assertEquals(TOO_BIG, Scripts.display("2 * " + HALF));
        // 2**64 - 2: the double answer carries the product of both operands, which is
        // the case that a promotion returning its left operand could not hide behind.
        assertEquals("1.8446744073709552E19", Scripts.display(MAX + " * 2"));
        assertEquals("1.8446744073709552E19", Scripts.display("2 * " + MAX));
    }

    @Test
    @DisplayName("a power past the range promotes instead of raising")
    void powerPromotes() {
        // 2 ** 62 still fits, so the exact integer path must not be given up on
        // carelessly: the language keeps answering Int while an Int is available.
        assertEquals(HALF, Scripts.display("2 ** 62"));
        assertEquals(TOO_BIG, Scripts.display("2 ** 63"));
        assertEquals("1.0E20", Scripts.display("10 ** 20"));
        // Past the loop's guard the answer was always a double; both paths agree.
        assertEquals(Scripts.display("2.0 ** 100"), Scripts.display("2 ** 100"));
    }

    @Test
    @DisplayName("the promoted answer is a Double, and the exact one is still an Int")
    void promotionChangesTheType() {
        assertInstanceOf(Double.class, Scripts.run(MAX + " + 1"));
        assertInstanceOf(Long.class, Scripts.run(MAX + " - 1"));
        assertInstanceOf(Double.class, Scripts.run("2 ** 63"));
        assertInstanceOf(Long.class, Scripts.run("2 ** 62"));
    }

    @Test
    @DisplayName("negating the most negative Int promotes too")
    void negationPromotes() {
        // -9223372036854775808 is one past the largest Int, so no literal spells it;
        // a script builds it with arithmetic and then asks for its opposite.
        assertEquals(TOO_BIG, Scripts.display("-(0 - " + MAX + " - 1)"));
    }

    @Test
    @DisplayName("division keeps its own rules at the range edge")
    void divisionIsUnchanged() {
        assertEquals("2", Scripts.display("6 / 3"));
        assertEquals("3.5", Scripts.display("7 / 2"));
        assertEquals("-" + MAX, Scripts.display(MAX + " / -1"));
        // The most negative Int divided by -1 cannot be an Int, so it promotes.
        assertEquals(TOO_BIG, Scripts.display("(0 - " + MAX + " - 1) / -1"));
    }
}
