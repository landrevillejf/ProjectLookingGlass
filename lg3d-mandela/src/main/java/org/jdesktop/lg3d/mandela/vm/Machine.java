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
package org.jdesktop.lg3d.mandela.vm;

import java.util.List;

import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.values.Callable;

/**
 * The services a script can reach from inside the machine.
 *
 * <p>The built-in method table (<code>upper()</code>, <code>map&nbsp;{&nbsp;}</code>,
 * <code>json.encode()</code>) needs both to call back into script code and to ask
 * the host for a side effect, and it must not know whether the host is the CLI,
 * an Espresso panel or a web page. This interface is that seam: the
 * {@link VM} implements it, the standard library consumes it, and tests can
 * supply a fake that records calls instead of performing them.</p>
 */
public interface Machine {

    /**
     * Calls a script or host callable from native code.
     *
     * @param target   the value to call
     * @param receiver the value {@code this} refers to, or null
     * @param args     the arguments, already converted to Mandela values
     * @return the value the call produced
     */
    Object invoke(Callable target, Object receiver, Object[] args);

    /**
     * Looks a global up the way a script would.
     *
     * @param name the global name
     * @return its value, or null when the host never installed it
     */
    Object global(String name);

    /** @return the sandbox the host handed this run */
    Capabilities capabilities();

    /**
     * Writes characters to the script's own console channel, with no terminator.
     *
     * <p>Never a direct {@code System.out}: a page script's output belongs to the
     * browser console, and an Espresso panel's belongs to its Problems view.</p>
     *
     * @param text the characters to emit
     */
    void write(String text);

    /**
     * Writes one whole line to the console channel, terminator included.
     *
     * @param text the line, without its terminator
     */
    void writeLine(String text);

    /**
     * Writes one whole line to the error channel, terminator included.
     *
     * @param text the line, without its terminator
     */
    void writeError(String text);

    /**
     * Loads another Mandela source file as a module.
     *
     * @param path the literal path from the {@code import} statement
     * @return a map of the module's exported names
     */
    Object importModule(String path);

    /**
     * Installs a standard-library module's names into the global table.
     *
     * @param path the dotted name after {@code std.}
     * @return the number of names installed, for diagnostics
     */
    int useModule(String path);

    /** @return the values currently on the operand stack, bottom first, for dumps */
    List<Object> snapshotStack();
}
