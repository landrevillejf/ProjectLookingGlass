/*
 * Compatibility shim for reading legacy J3F scene graph files with the Jogamp
 * Java 3D 1.7 runtime. See the sibling state shim under
 * com/sun/j3d/utils/scenegraph/io/state/javax/media/j3d for the full rationale.
 *
 * When a node type is not present in a J3F file's symbol table, the Java 3D
 * scene-graph reader stores the node's class name inline and instantiates it via
 * Class.forName(name).newInstance(). Files written by the original Sun Java 3D
 * therefore embed legacy names such as "javax.media.j3d.AmbientLight", which do
 * not exist in the Jogamp distribution (renamed to org.jogamp.java3d).
 *
 * This class re-exposes the Jogamp AmbientLight under the legacy javax.media.j3d
 * name so those files can still be deserialised. It is a pure subclass: the node
 * behaves exactly like org.jogamp.java3d.AmbientLight, and the reader populates
 * it through the inherited state.
 *
 * Note: javax.media.j3d was never a JDK-internal package (it belonged to the
 * standalone Java 3D distribution), so defining it here does not clash with any
 * platform module.
 */
package javax.media.j3d;

/**
 * Legacy-named alias for {@link org.jogamp.java3d.AmbientLight}.
 */
public class AmbientLight extends org.jogamp.java3d.AmbientLight {

    public AmbientLight() {
        super();
    }
}
