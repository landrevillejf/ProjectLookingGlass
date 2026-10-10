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
 * Marker for every Mandela value that can be called: a script closure, a bound
 * method, or a function the host contributed.
 *
 * <p>The virtual machine dispatches calls through this interface, which is what
 * lets a Java (or Kotlin) lambda sit in the same global table as a script
 * function and be indistinguishable to the author: {@code println} and
 * {@code upper} are natives, {@code fib} is a closure, and user code calls both
 * identically.</p>
 *
 * @see MandelaClosure
 * @see org.jdesktop.lg3d.mandela.rt.HostFunction
 */
public interface Callable {

    /** @return the name used in diagnostics and {@code str()} output */
    String name();

    /** @return the number of declared parameters, -1 when it varies */
    int arity();

    /** @return true when the callable accepts more arguments than {@link #arity()} */
    default boolean isVariadic() {
        return false;
    }
}
