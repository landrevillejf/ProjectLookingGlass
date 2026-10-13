/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5b (artimport): one deployed texture reference.
 *
 * The <loader-data> the .ldr file carries records each texture as a
 * value/key pair (the 2006 artimport wrote the path twice). This is that pair --
 * pure data, so the asset manager can enumerate a model's texture dependencies
 * and resolve each against the world without any rendering.
 */
package org.jdesktop.wonderland.modules.artimport;

/** A deployed-texture entry (a value path and a lookup key). */
public final class DeployedTexture {

    private final String value;
    private final String key;

    public DeployedTexture(String value, String key) {
        this.value = value;
        this.key = key;
    }

    /** The texture path as written (usually {@code ./untitled/texture0.png}). */
    public String value() {
        return value;
    }

    /** The lookup key (the 2006 loader used the same string as the value). */
    public String key() {
        return key;
    }

    /**
     * Normalise a leading {@code ./} away so the path matches the {@code
     * untitled/texture0.png} key the Collada <image> init_from uses. A null
     * yields null.
     */
    public String normalizedValue() {
        return DeployedModelIO.normalizeRelative(value);
    }
}
