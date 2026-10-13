/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5b (artimport): the <loader-data> a .ldr file records.
 *
 * The loader descriptor the 2006 KmzLoader / artimport emitted alongside a
 * deployed model: which class loads it, and the list of deployed textures the
 * model depends on. Pure data -- the asset manager reads it to know what to
 * fetch; no rendering lives here.
 */
package org.jdesktop.wonderland.modules.artimport;

import java.util.List;

/** A parsed {@code .ldr} loader-data document. */
public final class LoaderData {

    private final int version;
    private final String modelLoaderClassname;
    private final List<DeployedTexture> deployedTextures;

    public LoaderData(int version, String modelLoaderClassname,
                      List<DeployedTexture> deployedTextures) {
        this.version = version;
        this.modelLoaderClassname = modelLoaderClassname;
        this.deployedTextures = List.copyOf(deployedTextures);
    }

    public int version() {
        return version;
    }

    /** The fully-qualified loader class name, or null when the file omitted it. */
    public String modelLoaderClassname() {
        return modelLoaderClassname;
    }

    public List<DeployedTexture> deployedTextures() {
        return deployedTextures;
    }

    /** The texture paths, each {@link DeployedTexture#normalizedValue() normalised}. */
    public List<String> texturePaths() {
        return deployedTextures.stream()
                .map(DeployedTexture::normalizedValue)
                .filter(java.util.Objects::nonNull)
                .toList();
    }
}
