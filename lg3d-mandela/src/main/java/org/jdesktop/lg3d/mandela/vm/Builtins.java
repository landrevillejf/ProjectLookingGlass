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

import org.jdesktop.lg3d.mandela.values.MandelaMap;

/**
 * The part of a Mandela runtime that the machine knows nothing about.
 *
 * <p>The interpreter's job is names, frames and the operand stack. Everything
 * that gives the language its usefulness &mdash; {@code upper()}, {@code join()},
 * {@code json.encode()}, reading a file, asking the host for a widget &mdash;
 * lives in the standard library, and the library differs per host: the CLI has
 * real files and a terminal, a Web Browser page has neither, an Espresso panel
 * has its own console. So the machine never hard-codes a built-in name; when a
 * member is not a declared field or method it asks this table, and when the
 * table answers null it raises the {@code AttributeError}-style error the
 * author sees.</p>
 *
 * <p>One implementation is supplied by
 * {@link org.jdesktop.lg3d.mandela.rt.Runtime} and is injected before the first
 * run. Tests can inject a table with a single member in it, which is what keeps
 * the interpreter testable without the standard library present.</p>
 *
 * @see Machine
 * @see org.jdesktop.lg3d.mandela.rt.HostFunction
 */
public interface Builtins {

    /**
     * Resolves a member on a value that has no declared members of its own.
     *
     * <p>Implementations must be cheap: this runs on the member-access path of
     * every {@code "list".join(", ")}-style expression. The returned callable
     * already knows its receiver, so the machine just calls it with the
     * arguments the call site gave.</p>
     *
     * <p>The running {@link Machine} is handed over rather than stored: a library
     * function that calls a script callback ({@code map}, {@code sort} with a
     * comparator, a widget's handler) has to re-enter the machine that is
     * executing it, and one library table is shared by every machine a host
     * creates.</p>
     *
     * @param machine  the interpreter that is running this expression
     * @param receiver the value whose member is being read
     * @param name     the member name
     * @return a value, or a {@code Callable} to call, or null when there is none
     */
    Object member(Machine machine, Object receiver, String name);

    /**
     * Loads a sibling Mandela source file for {@code import}.
     *
     * <p>The machine holds no filesystem knowledge &mdash; a page host has no
     * files at all &mdash; so the request goes to the host, which is also the
     * only party allowed to decide whether the path is reachable.</p>
     *
     * @param machine the interpreter running the statement
     * @param path the literal path from the statement
     * @return a map of the module's exported names
     */
    MandelaMap importModule(Machine machine, String path);

    /**
     * Lists the names a {@code use std.<name>} declaration installs.
     *
     * @param machine the interpreter running the statement
     * @param module the dotted name after {@code std.}
     * @return the names and their values, never null
     */
    MandelaMap stdlibNames(Machine machine, String module);
}
