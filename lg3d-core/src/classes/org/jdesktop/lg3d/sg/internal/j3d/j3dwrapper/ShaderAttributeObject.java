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
import org.jdesktop.lg3d.sg.internal.wrapper.ShaderAttributeObjectWrapper;

/**
 * j3dwrapper delegate for the {@code sg} facade's abstract ShaderAttributeObject:
 * an explicitly-valued uniform attribute. See {@link ShaderAttribute} for why
 * this layer exists.
 */
public abstract class ShaderAttributeObject extends ShaderAttribute
        implements ShaderAttributeObjectWrapper {

    public static final int ALLOW_VALUE_READ
        = org.jogamp.java3d.ShaderAttributeObject.ALLOW_VALUE_READ;
    public static final int ALLOW_VALUE_WRITE
        = org.jogamp.java3d.ShaderAttributeObject.ALLOW_VALUE_WRITE;

    ShaderAttributeObject() {
    }

    public abstract Object getValue();

    public abstract void setValue(Object value);

    public Class getValueClass() {
        return ((org.jogamp.java3d.ShaderAttributeObject)wrapped).getValueClass();
    }
}
