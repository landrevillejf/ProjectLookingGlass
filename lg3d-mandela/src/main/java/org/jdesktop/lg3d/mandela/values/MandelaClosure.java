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

import org.jdesktop.lg3d.mandela.code.CompiledFunction;

/**
 * A script function value: a compiled body plus the variables it closed over.
 *
 * <p>Two pieces of state travel together. {@link #upvalues()} holds one
 * {@link Ref} per captured variable &mdash; the same cell the defining frame
 * writes to, which is why a closure sees later updates and can make them &mdash;
 * and {@link #boundThis()} is non-null only for a method taken off an instance,
 * so {@code obj.method} produces a callable that still knows its receiver.</p>
 *
 * <p>Closures are immutable. Binding {@code this} produces a new small object
 * that shares the function and the upvalue array, so passing a method as a
 * callback costs one allocation and no copying.</p>
 *
 * <p>{@link #ownerClass()} is the third piece, and it is only set for a method:
 * the class that declared the body. {@code super} must be resolved from the
 * <em>declaring</em> class rather than from the runtime class of the receiver,
 * otherwise a three-level hierarchy re-enters the method it just tried to call
 * up through. The machine reads it when it fills a method frame's slot 1.</p>
 */
public final class MandelaClosure implements Callable {

    private final CompiledFunction function;
    private final Object[] upvalues;
    private final Object boundThis;
    private final MandelaClass owner;

    /** Creates an unbound closure over {@code upvals}. */
    public MandelaClosure(CompiledFunction function, Object[] upvals) {
        this(function, upvals, null, null);
    }

    /**
     * Creates the closure the machine installs for a declared method.
     *
     * @param function the method body, compiled with the method frame layout
     * @param upvals   never empty: a method may not capture, so this is
     *                 {@code null} or an empty array
     * @param declaring the class that declared the body, for {@code super}
     */
    public MandelaClosure(CompiledFunction function, Object[] upvals, MandelaClass declaring) {
        this(function, upvals, null, declaring);
    }

    private MandelaClosure(CompiledFunction function, Object[] upvals, Object receiver,
                           MandelaClass declaring) {
        this.function = function;
        this.upvalues = (upvals == null) ? new Object[0] : upvals;
        this.boundThis = receiver;
        this.owner = declaring;
    }

    /** @return the compiled body */
    public CompiledFunction function() {
        return function;
    }

    /** @return the captured cells, indexed exactly as the compiler numbered them */
    public Object[] upvalues() {
        return upvalues;
    }

    /** @return the receiver for a bound method, or null */
    public Object boundThis() {
        return boundThis;
    }

    /** @return the class that declared this method, or null for a plain function */
    public MandelaClass ownerClass() {
        return owner;
    }

    /** @return true when this closure is a method already attached to a receiver */
    public boolean isBound() {
        return boundThis != null;
    }

    /**
     * @param receiver the instance to attach
     * @return a closure that calls this body with {@code this} set to the receiver
     */
    public MandelaClosure bindTo(Object receiver) {
        return new MandelaClosure(function, upvalues, receiver, owner);
    }

    @Override
    public String name() {
        return function.name();
    }

    @Override
    public int arity() {
        return function.arity();
    }

    @Override
    public boolean isVariadic() {
        return function.acceptsAnyArgs();
    }

    @Override
    public String toString() {
        String label = function.name().isEmpty() ? "lambda" : function.name();
        return "<fun " + label + "/" + function.arity()
                + (isBound() ? " bound" : "") + ">";
    }
}
