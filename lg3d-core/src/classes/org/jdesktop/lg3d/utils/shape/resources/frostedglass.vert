/*
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
 *
 * Frosted-glass rounded panel - vertex stage.
 *
 * Legacy GLSL 1.20 dialect on purpose: Jogamp Java 3D 1.7.2's GLSLShaderProgram
 * binds the fixed-function built-ins (gl_Vertex, gl_ModelViewProjectionMatrix),
 * and the one proven in-tree example (cdviewer's dimple shader) uses the same
 * dialect. Modern "in/out" GLSL is not assumed to link here.
 *
 * Passes the vertex's object-space XY to the fragment stage, where the exact
 * signed distance to a rounded rectangle is evaluated. The quad spans the full
 * panel rect; the corner rounding and the frosted edge are computed per
 * fragment, so the silhouette stays resolution-independent and the AA band is
 * a constant world width rather than a baked tessellation.
 */
varying vec2 vPos;

void main(void) {
    vPos = gl_Vertex.xy;
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
}
