/*
 * In-tree replacement for jMonkeyEngine's com.jme.renderer.ColorRGBA.
 *
 * A plain four-component colour carried by MaterialJME. Values are floats in
 * [0,1]; the surface is the fields plus the constructor the material builder uses.
 */
package com.jme.renderer;

import java.io.Serializable;

public class ColorRGBA implements Serializable, Cloneable {

    public float r;
    public float g;
    public float b;
    public float a;

    public ColorRGBA() {
    }

    public ColorRGBA(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    public ColorRGBA set(float r, float g, float b, float a) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        return this;
    }

    public ColorRGBA set(ColorRGBA c) {
        return set(c.r, c.g, c.b, c.a);
    }

    @Override
    public Object clone() {
        return new ColorRGBA(r, g, b, a);
    }

    @Override
    public String toString() {
        return "(" + r + ", " + g + ", " + b + ", " + a + ")";
    }
}
