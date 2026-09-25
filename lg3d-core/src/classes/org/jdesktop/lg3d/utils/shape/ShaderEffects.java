/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.shape;

import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.sg.GLSLShaderProgram;
import org.jdesktop.lg3d.sg.Shader;
import org.jdesktop.lg3d.sg.ShaderAttributeObject;
import org.jdesktop.lg3d.sg.ShaderAttributeValue;
import org.jdesktop.lg3d.sg.ShaderProgram;
import org.jdesktop.lg3d.sg.SourceCodeShader;
import org.jogamp.java3d.utils.shader.StringIO;

/**
 * The entry point to the native 3D desktop's GPU shader effects: a small
 * factory over the lg3d {@code sg} facade's GLSL plumbing
 * ({@link org.jdesktop.lg3d.sg.ShaderAppearance} /
 * {@link org.jdesktop.lg3d.sg.GLSLShaderProgram} /
 * {@link org.jdesktop.lg3d.sg.SourceCodeShader}) that the port inherited from
 * Java 3D 1.7 but, until now, only one demo app ({@code cdviewer}) ever used.
 *
 * <p>Everything here is <strong>opt-in</strong>: effects are gated behind the
 * {@link #SHADERS_PROPERTY} system property ({@code -Dlg.shaders=true}), which
 * defaults to {@code false} so the desktop renders exactly as it always has
 * through the fixed-function path until the shader effects are turned on and
 * live-verified. Callers must therefore treat a {@code null} return from the
 * builders here as "shaders off / unavailable, fall back to the legacy widget".
 *
 * <p>The GLSL is authored in the legacy 1.20 dialect ({@code varying},
 * {@code gl_FragColor}, {@code gl_ModelViewProjectionMatrix}) because that is
 * what Jogamp Java 3D 1.7.2's {@code GLSLShaderProgram} binds and what the one
 * proven in-tree shader ({@code cdviewer}'s {@code dimple}) uses; modern
 * {@code in}/{@code out} GLSL is not assumed to link. Shader sources live as
 * classpath resources under {@code utils/shape/resources/} (the same
 * {@code src/classes}-as-resource-dir model that ships {@code dimple.vert}).
 */
public final class ShaderEffects {

    /** System property that turns the GPU shader effects on. Defaults off. */
    public static final String SHADERS_PROPERTY = "lg.shaders";

    private static final Logger logger = Logger.getLogger("lg.shaders");

    /** The soft-shadow program's uniform names, in binding order. */
    static final String[] SOFT_SHADOW_UNIFORMS = {
        "uHalfWin", "uSoftNESW", "uAlpha",
    };

    private ShaderEffects() {
        // static factory
    }

    /**
     * Whether the GPU shader effects are enabled. Reads {@code lg.shaders}
     * afresh each call (no caching) so a test or a live toggle is honoured; the
     * value is only consulted when a decoration is built, not per frame.
     */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(SHADERS_PROPERTY, "false"));
    }

    /**
     * Builds the soft-drop-shadow {@link ShaderProgram} from the bundled GLSL,
     * or returns {@code null} when the sources are missing or the program cannot
     * be assembled. Constructing the program object needs no GL context (only
     * rendering one does), so this is safe to call while building a scene graph.
     */
    public static ShaderProgram softShadowProgram() {
        return program("resources/softshadow.vert", "resources/softshadow.frag",
                SOFT_SHADOW_UNIFORMS);
    }

    /**
     * Loads a vertex+fragment GLSL pair from classpath resources into a
     * {@link GLSLShaderProgram} declaring {@code shaderAttrNames} as its
     * uniforms. Returns {@code null} on any failure so callers can fall back to
     * the fixed-function widget rather than throwing mid-scene-graph-build.
     */
    static ShaderProgram program(String vertexResource, String fragmentResource,
            String[] shaderAttrNames) {
        URL vert = ShaderEffects.class.getResource(vertexResource);
        URL frag = ShaderEffects.class.getResource(fragmentResource);
        if (vert == null || frag == null) {
            logger.warning("shader resources not found: " + vertexResource + " / "
                    + fragmentResource + " (vert=" + vert + ", frag=" + frag + ")");
            return null;
        }
        try {
            Shader vs = new SourceCodeShader(Shader.SHADING_LANGUAGE_GLSL,
                    Shader.SHADER_TYPE_VERTEX, StringIO.readFully(vert));
            Shader fs = new SourceCodeShader(Shader.SHADING_LANGUAGE_GLSL,
                    Shader.SHADER_TYPE_FRAGMENT, StringIO.readFully(frag));
            GLSLShaderProgram prog = new GLSLShaderProgram();
            prog.setShaders(new Shader[] { vs, fs });
            prog.setShaderAttrNames(shaderAttrNames);
            prog.setVertexAttrNames(new String[0]);
            return prog;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not assemble the shader program from "
                    + vertexResource + " / " + fragmentResource, t);
            return null;
        }
    }

    /**
     * Creates a shader uniform value. When {@code liveWrite} is set the value
     * gets {@link ShaderAttributeObject#ALLOW_VALUE_WRITE} so it can be updated
     * on a live scene graph (e.g. a window shadow resized in place).
     */
    static ShaderAttributeValue uniform(String name, Object value, boolean liveWrite) {
        ShaderAttributeValue attr = new ShaderAttributeValue(name, value);
        if (liveWrite) {
            attr.setCapability(ShaderAttributeObject.ALLOW_VALUE_WRITE);
        }
        return attr;
    }
}
