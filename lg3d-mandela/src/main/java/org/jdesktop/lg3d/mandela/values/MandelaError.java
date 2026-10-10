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
 * A Mandela error: a value in the script and the throwable that unwinds the
 * machine, in one object.
 *
 * <p>Scripts see it as an ordinary value &mdash; {@code err is Err},
 * {@code err.message}, {@code str(err)} &mdash; and {@code try / catch} binds it
 * to the clause name. It is also a {@link RuntimeException} because that is how
 * the language reaches out of a deep expression, through a host function, and
 * back into the interpreter loop without checking a return code at every step.
 * The two roles do not leak: a host that lets a {@code MandelaError} escape a
 * {@code HostFunction} gets the same object the script threw, and a
 * {@code catch} in the script sees a Java exception raised by the host wrapped
 * as a {@code RuntimeError} with the original as its cause.</p>
 *
 * <p>{@link #kind()} is the discriminator scripts branch on, and the name the
 * {@code throw} statement and the standard library use: {@code ValueError},
 * {@code KeyError}, {@code TypeError}, {@code IndexError}, {@code RangeError},
 * {@code PermissionError}, {@code LimitError}, {@code IOError},
 * {@code RuntimeError}.</p>
 */
public final class MandelaError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The generic kind a script's own {@code throw} produces. */
    public static final String ERROR = "Error";

    private final String kind;
    private final String sourceName;
    private final int line;

    /**
     * Creates an error value.
     *
     * @param kind       the discriminator, normally one of the constants above
     * @param message    the human-readable text; never null
     * @param sourceName the script that raised it, or null when unknown
     * @param line       the 1-based line, or 0 when unknown
     */
    public MandelaError(String kind, String message, String sourceName, int line) {
        super(message);
        this.kind = (kind == null || kind.isBlank()) ? ERROR : kind;
        this.sourceName = sourceName;
        this.line = Math.max(0, line);
    }

    /** An {@code Error} with the given message. */
    public static MandelaError of(String message) {
        return new MandelaError(ERROR, message, null, 0);
    }

    /** An error of the given kind and message. */
    public static MandelaError of(String kind, String message) {
        return new MandelaError(kind, message, null, 0);
    }

    /** A {@code TypeError}: an operator or call got a value of the wrong type. */
    public static MandelaError type(String message) {
        return new MandelaError("TypeError", message, null, 0);
    }

    /** A {@code ValueError}: the type is right, the content is not. */
    public static MandelaError value(String message) {
        return new MandelaError("ValueError", message, null, 0);
    }

    /** A {@code KeyError}: a map has no such key. */
    public static MandelaError key(String message) {
        return new MandelaError("KeyError", message, null, 0);
    }

    /** An {@code IndexError}: a list or string index is out of range. */
    public static MandelaError index(String message) {
        return new MandelaError("IndexError", message, null, 0);
    }

    /** A {@code RangeError}: a numeric range or size is not usable. */
    public static MandelaError range(String message) {
        return new MandelaError("RangeError", message, null, 0);
    }

    /** A {@code PermissionError}: the sandbox refused a capability. */
    public static MandelaError permission(String message) {
        return new MandelaError("PermissionError", message, null, 0);
    }

    /** A {@code LimitError}: a configured ceiling was hit (steps, depth, output). */
    public static MandelaError limit(String message) {
        return new MandelaError("LimitError", message, null, 0);
    }

    /** An {@code IOError}: the filesystem or network said no. */
    public static MandelaError io(String message) {
        return new MandelaError("IOError", message, null, 0);
    }

    /** Wraps a Java exception the script caused, keeping it as the cause. */
    public static MandelaError runtime(String message, Throwable cause) {
        MandelaError error = new MandelaError("RuntimeError", message, null, 0);
        if (cause != null) {
            error.initCause(cause);
        }
        return error;
    }

    /** @return the discriminator a script tests with {@code err.kind} */
    public String kind() {
        return kind;
    }

    /** @return the script name that raised this, or null when unknown */
    public String sourceName() {
        return sourceName;
    }

    /** @return the 1-based line, or 0 when unknown */
    public int line() {
        return line;
    }

    /**
     * @param atSource the script name to attribute
     * @param atLine   the line to attribute
     * @return a copy carrying the position, for the throw site inside the machine
     */
    public MandelaError at(String atSource, int atLine) {
        MandelaError copy = new MandelaError(kind, getMessage(), atSource, atLine);
        copy.initCause(getCause());
        return copy;
    }

    /** @return {@code Kind: message}, the form {@code str(err)} and the CLI print */
    @Override
    public String toString() {
        String where = (sourceName == null) ? "" : " (at " + sourceName
                + (line > 0 ? ":" + line : "") + ")";
        return kind + ": " + getMessage() + where;
    }
}
