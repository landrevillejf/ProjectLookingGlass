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

package org.jdesktop.lg3d.sg.internal.j3d.j3dwrapper;
import org.jdesktop.lg3d.sg.internal.wrapper.ShaderAttributeValueWrapper;

/**
 * j3dwrapper delegate for the {@code sg} facade's ShaderAttributeValue: a single
 * (attrName, value) uniform. The raw jogamp ShaderAttributeValue requires both
 * the name and an initial value in its constructor, so - exactly like
 * {@link SourceCodeShader} - {@code wrapped} is built in this constructor (which
 * has the args) and {@link #createWrapped()} is a no-op (it runs from the base
 * constructor, before the args exist).
 */
public class ShaderAttributeValue extends ShaderAttributeObject
        implements ShaderAttributeValueWrapper {

    public ShaderAttributeValue(String attrName, Object value) {
        wrapped = new org.jogamp.java3d.ShaderAttributeValue(attrName, value);
        wrapped.setUserData(this);
    }

    public Object getValue() {
        return ((org.jogamp.java3d.ShaderAttributeValue)wrapped).getValue();
    }

    public void setValue(Object value) {
        ((org.jogamp.java3d.ShaderAttributeValue)wrapped).setValue(value);
    }

    public void createWrapped() {
    }
}
