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
package org.jdesktop.lg3d.mandela.values;

/**
 * A mutable cell holding one variable's value.
 *
 * <p>A local that some nested function reads lives in a {@code Ref} rather than
 * directly in the frame's slot array, so the closure and the defining frame see
 * the same storage: a counter incremented by the outer function is visible
 * through the closure, and a counter incremented by the closure is visible
 * outside. Boxing happens at compile time, when the compiler learns that a slot
 * is captured, and the cell is created once when the frame is built.</p>
 *
 * <p><b>Why eager rather than deferred.</b> Classic implementations copy a local
 * into a cell only when a closure is created, which forces the interpreter to
 * walk the open-upvalue list on every scope exit. Mandela boxes at frame entry
 * instead: one extra allocation for the rare captured variable, no traversal, no
 * window where the same variable is reachable through two places. Slots that are
 * never captured stay plain values, so the cost is paid only by functions that
 * actually close over their locals.</p>
 *
 * <p>Not thread-safe, like every other Mandela value. A script that needs to
 * share state across threads copies it.</p>
 */
public final class Ref {

    /** The boxed variable's current value. */
    public Object value;

    /** Boxes {@code initialValue}. */
    public Ref(Object initialValue) {
        this.value = initialValue;
    }

    /** Boxes {@code null} (Mandela's {@code null} literal). */
    public static Ref empty() {
        return new Ref(null);
    }

    /** @return the value, cast to {@code Long} by the caller when it knows the type */
    public Object get() {
        return value;
    }

    /** Stores a new value. */
    public void set(Object newValue) {
        this.value = newValue;
    }

    /** @return the display form of the boxed value, for debug dumps */
    @Override
    public String toString() {
        return "@" + Values.display(value);
    }
}
