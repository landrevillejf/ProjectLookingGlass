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
import org.jdesktop.lg3d.sg.internal.wrapper.ShaderAttributeWrapper;

/**
 * j3dwrapper delegate for the {@code sg} facade's abstract ShaderAttribute:
 * the base of the uniform-attribute wrapper hierarchy. The facade declared this
 * layer (and its ShaderAttributeSet / ShaderAttributeValue leaves) but never
 * shipped the j3dwrapper implementations, so a facade shader could compile a
 * GLSL program yet had no way to pass uniform values - which is why the one
 * in-tree shader (cdviewer's dimple) had its uniforms commented out and baked
 * as constants. These delegates complete that plumbing.
 */
public abstract class ShaderAttribute extends NodeComponent
        implements ShaderAttributeWrapper {

    ShaderAttribute() {
    }

    public String getAttributeName() {
        return ((org.jogamp.java3d.ShaderAttribute)wrapped).getAttributeName();
    }
}
