/*
 * In-tree reimplementation of the j3d-contrib-utils traverser interface,
 * ported to the Jogamp Java 3D 1.7 API (org.jogamp.java3d).
 *
 * The original class shipped as a binary in lg3d-core/ext/j3d-contrib-utils.jar
 * compiled against the legacy javax.media.j3d packages; it is reproduced here
 * so the SDK builds against Jogamp without the stale binary. The package name
 * is kept identical so existing imports across lg3d-core are unaffected.
 */
package org.jdesktop.j3d.utils.scenegraph.traverser;

/**
 * Callback interface invoked by {@link TreeScan} for every matching node
 * encountered while traversing a Java 3D scene graph.
 */
public interface ProcessNodeInterface {

    /**
     * Called for each node whose class matches the search criteria.
     *
     * @param node the matching scene graph node
     * @return {@code false} to stop the scan descending into this node's
     *         children; {@code true} to continue.
     */
    public boolean processNode(org.jogamp.java3d.Node node);
}
