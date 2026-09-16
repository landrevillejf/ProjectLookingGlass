/*
 * In-tree reimplementation of the j3d-contrib-utils NodeChangeProcessor,
 * ported to the Jogamp Java 3D 1.7 API (org.jogamp.java3d). See
 * ProcessNodeInterface.java in this package for the full rationale.
 */
package org.jdesktop.j3d.utils.scenegraph.traverser;

/**
 * Convenience base class for processors that simply modify each matching node.
 * Subclasses implement {@link #changeNode(org.jogamp.java3d.Node)}; the return
 * value controls whether the traversal descends into the node's children.
 */
public abstract class NodeChangeProcessor implements ProcessNodeInterface {

    public boolean processNode(org.jogamp.java3d.Node node) {
        return changeNode(node);
    }

    abstract public boolean changeNode(org.jogamp.java3d.Node node);
}
