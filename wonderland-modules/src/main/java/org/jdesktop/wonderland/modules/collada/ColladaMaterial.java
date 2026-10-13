/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): a COLLADA <material>.
 *
 * A material instance-binds one effect; the loader records that binding so a
 * geometry's instance_material target can be chased to the effect and then to
 * its texture images. The material's own render State is built at M6.
 */
package org.jdesktop.wonderland.modules.collada;

/** A {@code <library_materials>/<material>} element. */
public final class ColladaMaterial {

    private final String id;
    private final String name;
    private final String instanceEffectUrl;

    /**
     * @param instanceEffectUrl the referenced effect id (hash stripped), or null
     */
    public ColladaMaterial(String id, String name, String instanceEffectUrl) {
        this.id = id;
        this.name = name;
        this.instanceEffectUrl = instanceEffectUrl;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String instanceEffectUrl() {
        return instanceEffectUrl;
    }
}
