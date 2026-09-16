/*
 * In-tree reimplementation of the j3d-contrib-utils TreeScan scene-graph
 * traverser, ported to the Jogamp Java 3D 1.7 API (org.jogamp.java3d).
 * See ProcessNodeInterface.java in this package for the full rationale.
 *
 * The traversal logic mirrors the original contrib utility (and the LG3D
 * wrapper-scenegraph equivalent in org.jdesktop.lg3d.sg.utils.traverser), but
 * operates directly on raw Java 3D nodes. Note that as of Java 3D 1.6 the
 * Group.getAllChildren() accessor returns an Iterator rather than an
 * Enumeration, which is reflected below.
 */
package org.jdesktop.j3d.utils.scenegraph.traverser;

import java.util.BitSet;
import java.util.HashSet;
import java.util.Iterator;

import org.jogamp.java3d.Group;
import org.jogamp.java3d.Node;
import org.jogamp.java3d.Switch;

public class TreeScan extends Object {

    private static HashSet visitedSharedGroups = null;

    /**
     * Traverse the scene graph starting at {@code treeRoot}, invoking
     * {@code processor} for every node assignable to {@code nodeClass}.
     *
     * @param treeRoot                  root of the scene graph to search
     * @param nodeClass                 the class of node(s) to search for
     * @param processor                 callback invoked for each match
     * @param onlyEnabledSwitchChildren when true only recurse into enabled
     *                                  {@link Switch} children
     * @param sharedGroupsOnce          when true only process shared groups
     *                                  once regardless of how many Links
     *                                  reference them
     * @throws org.jogamp.java3d.CapabilityNotSetException if a live/compiled
     *         group lacks the ALLOW_CHILDREN_READ capability
     */
    public static void findNode(Node treeRoot, Class nodeClass,
            ProcessNodeInterface processor, boolean onlyEnabledSwitchChildren,
            boolean sharedGroupsOnce)
            throws org.jogamp.java3d.CapabilityNotSetException {

        Class[] nodeClasses = new Class[] { nodeClass };

        findNode(treeRoot, nodeClasses, processor,
                onlyEnabledSwitchChildren, sharedGroupsOnce);
    }

    /**
     * Traverse the scene graph starting at {@code treeRoot}, invoking
     * {@code processor} for every node assignable to any of {@code nodeClasses}.
     *
     * @throws org.jogamp.java3d.CapabilityNotSetException if a live/compiled
     *         group lacks the ALLOW_CHILDREN_READ capability
     */
    public static void findNode(Node treeRoot, Class[] nodeClasses,
            ProcessNodeInterface processor, boolean onlyEnabledSwitchChildren,
            boolean sharedGroupsOnce)
            throws org.jogamp.java3d.CapabilityNotSetException {

        if (sharedGroupsOnce && visitedSharedGroups == null)
            visitedSharedGroups = new HashSet();

        actualFindNode(treeRoot, nodeClasses, processor,
                onlyEnabledSwitchChildren, sharedGroupsOnce);

        if (sharedGroupsOnce)
            visitedSharedGroups.clear();
    }

    /**
     * Convenience method to return a Class given its fully-qualified name
     * without forcing callers to handle ClassNotFoundException.
     */
    public static Class getClass(String str) {
        try {
            return Class.forName(str);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException("BAD CLASS " + str);
        }
    }

    private static void actualFindNode(Node treeRoot, Class[] nodeClasses,
            ProcessNodeInterface processor, boolean onlyEnabledSwitchChildren,
            boolean sharedGroupsOnce)
            throws org.jogamp.java3d.CapabilityNotSetException {

        boolean doChildren = true;

        if (treeRoot == null)
            return;

        for (int i = 0; i < nodeClasses.length; i++)
            if (nodeClasses[i].isAssignableFrom(treeRoot.getClass())) {
                doChildren = processor.processNode(treeRoot);
                i = nodeClasses.length;
            }

        if (!doChildren)
            return;

        if (onlyEnabledSwitchChildren && treeRoot instanceof Switch) {
            int whichChild = ((Switch) treeRoot).getWhichChild();

            if (whichChild == Switch.CHILD_ALL) {
                Iterator<Node> e = ((Group) treeRoot).getAllChildren();
                while (e.hasNext())
                    actualFindNode(e.next(), nodeClasses, processor,
                            onlyEnabledSwitchChildren, sharedGroupsOnce);
            } else if (whichChild == Switch.CHILD_MASK) {
                BitSet set = ((Switch) treeRoot).getChildMask();
                for (int s = 0; s < set.length(); s++) {
                    if (set.get(s))
                        actualFindNode(((Switch) treeRoot).getChild(s),
                                nodeClasses, processor,
                                onlyEnabledSwitchChildren, sharedGroupsOnce);
                }
            } else if (whichChild == Switch.CHILD_NONE) {
                // Do nothing
            } else
                actualFindNode(((Switch) treeRoot).currentChild(), nodeClasses,
                        processor, onlyEnabledSwitchChildren, sharedGroupsOnce);
        } else if (treeRoot instanceof Group) {
            Iterator<Node> e = ((Group) treeRoot).getAllChildren();
            while (e != null && e.hasNext())
                actualFindNode(e.next(), nodeClasses, processor,
                        onlyEnabledSwitchChildren, sharedGroupsOnce);
        }
    }
}
