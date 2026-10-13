/*
 * wonderland-modules M5a (colladaloader): a COLLADA <visual_scene>.
 *
 * A named bundle of top-level nodes. The document's {@code <scene>} points at one
 * visual scene as the root; walking it yields the node/world-matrix inventory.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.List;

/** A {@code <library_visual_scenes>/<visual_scene>} element. */
public final class ColladaVisualScene {

    private final String id;
    private final String name;
    private final List<ColladaNode> nodes;

    public ColladaVisualScene(String id, String name, List<ColladaNode> nodes) {
        this.id = id;
        this.name = name;
        this.nodes = List.copyOf(nodes);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<ColladaNode> nodes() {
        return nodes;
    }
}
