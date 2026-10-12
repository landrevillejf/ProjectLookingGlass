/*
 * In-tree replacement for jMonkeyEngine's com.jme.scene.state.MaterialState.
 *
 * In the original this was a live jME render state; MaterialJME only uses it as a
 * carrier (it copies its own colours onto a passed-in state). Reimplemented here as
 * that value holder; the real render-state plumbing belongs to the client renderer
 * (milestone M2).
 */
package com.jme.scene.state;

import com.jme.renderer.ColorRGBA;

public class MaterialState {

    private ColorRGBA ambient = new ColorRGBA(0.0F, 0.0F, 0.0F, 1.0F);
    private ColorRGBA diffuse = new ColorRGBA(0.0F, 0.0F, 0.0F, 1.0F);
    private ColorRGBA specular = new ColorRGBA(0.0F, 0.0F, 0.0F, 1.0F);
    private ColorRGBA emissive = new ColorRGBA(0.0F, 0.0F, 0.0F, 1.0F);
    private float shininess;

    public void setAmbient(ColorRGBA ambient) {
        this.ambient = ambient;
    }

    public ColorRGBA getAmbient() {
        return ambient;
    }

    public void setDiffuse(ColorRGBA diffuse) {
        this.diffuse = diffuse;
    }

    public ColorRGBA getDiffuse() {
        return diffuse;
    }

    public void setSpecular(ColorRGBA specular) {
        this.specular = specular;
    }

    public ColorRGBA getSpecular() {
        return specular;
    }

    public void setEmissive(ColorRGBA emissive) {
        this.emissive = emissive;
    }

    public ColorRGBA getEmissive() {
        return emissive;
    }

    public void setShininess(float shininess) {
        this.shininess = shininess;
    }

    public float getShininess() {
        return shininess;
    }
}
