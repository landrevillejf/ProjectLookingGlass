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
import org.jdesktop.lg3d.sg.internal.wrapper.ShaderAttributeSetWrapper;
import org.jdesktop.lg3d.sg.internal.wrapper.ShaderAttributeWrapper;

/**
 * j3dwrapper delegate for the {@code sg} facade's ShaderAttributeSet: the bag of
 * uniform attributes handed to a ShaderAppearance. Each raw jogamp attribute
 * carries its facade delegate as user data, so the get/getAll round-trips map
 * the raw objects back to the wrappers the facade expects. See
 * {@link ShaderAttribute} for why this layer exists.
 */
public class ShaderAttributeSet extends NodeComponent
        implements ShaderAttributeSetWrapper {

    public ShaderAttributeSet() {
    }

    public void createWrapped() {
        wrapped = new org.jogamp.java3d.ShaderAttributeSet();
        wrapped.setUserData(this);
    }

    public void put(ShaderAttributeWrapper attr) {
        ((org.jogamp.java3d.ShaderAttributeSet)wrapped).put(
            (org.jogamp.java3d.ShaderAttribute)((ShaderAttribute)attr).wrapped);
    }

    public ShaderAttributeWrapper get(String attrName) {
        org.jogamp.java3d.ShaderAttribute attr
            = ((org.jogamp.java3d.ShaderAttributeSet)wrapped).get(attrName);
        if (attr == null) {
            return null;
        }
        return (ShaderAttributeWrapper)attr.getUserData();
    }

    public void remove(String attrName) {
        ((org.jogamp.java3d.ShaderAttributeSet)wrapped).remove(attrName);
    }

    public void remove(ShaderAttributeWrapper attr) {
        ((org.jogamp.java3d.ShaderAttributeSet)wrapped).remove(
            (org.jogamp.java3d.ShaderAttribute)((ShaderAttribute)attr).wrapped);
    }

    public void clear() {
        ((org.jogamp.java3d.ShaderAttributeSet)wrapped).clear();
    }

    public ShaderAttributeWrapper[] getAll() {
        org.jogamp.java3d.ShaderAttribute[] all
            = ((org.jogamp.java3d.ShaderAttributeSet)wrapped).getAll();
        ShaderAttributeWrapper[] wrappers = new ShaderAttributeWrapper[all.length];
        for (int i = 0; i < all.length; i++) {
            wrappers[i] = (ShaderAttributeWrapper)all[i].getUserData();
        }
        return wrappers;
    }

    public int size() {
        return ((org.jogamp.java3d.ShaderAttributeSet)wrapped).size();
    }
}
