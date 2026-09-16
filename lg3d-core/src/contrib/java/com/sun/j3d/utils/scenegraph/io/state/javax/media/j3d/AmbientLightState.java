/*
 * Compatibility shim for reading legacy J3F scene graph files with the Jogamp
 * Java 3D 1.7 runtime.
 *
 * Files written by the original Sun Java 3D SceneGraphFileWriter embed the
 * fully-qualified state-class name of every node type, e.g.
 * "com.sun.j3d.utils.scenegraph.io.state.javax.media.j3d.AmbientLightState".
 * Jogamp's reader resolves that name verbatim via Class.forName, but the Jogamp
 * distribution only ships the renamed
 * "org.jogamp.java3d.utils.scenegraph.io.state.org.jogamp.java3d.AmbientLightState".
 *
 * This class re-exposes the Jogamp AmbientLight state under the legacy name so
 * that older .j3f files (such as the LG3D pinguin background model) can still be
 * deserialised. It simply delegates to the Jogamp implementation; the node it
 * produces is a org.jogamp.java3d.AmbientLight.
 *
 * Note: com.sun.j3d was never a JDK-internal package (it belonged to the
 * standalone Java 3D distribution), so defining it here does not clash with any
 * platform module.
 */
package com.sun.j3d.utils.scenegraph.io.state.javax.media.j3d;

import org.jogamp.java3d.utils.scenegraph.io.retained.Controller;
import org.jogamp.java3d.utils.scenegraph.io.retained.SymbolTableData;

/**
 * Legacy-named alias for Jogamp's
 * {@link org.jogamp.java3d.utils.scenegraph.io.state.org.jogamp.java3d.AmbientLightState}.
 */
public class AmbientLightState extends
        org.jogamp.java3d.utils.scenegraph.io.state.org.jogamp.java3d.AmbientLightState {

    public AmbientLightState(SymbolTableData symbol, Controller control) {
        super(symbol, control);
    }
}
