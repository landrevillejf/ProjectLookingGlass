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
 * Soft rectangular drop shadow - fragment stage.
 *
 * Evaluates the exact signed distance from the fragment to the window rect and
 * ramps the shadow alpha from uAlpha at the rect edge down to 0 across a
 * per-side penumbra (uSoftNESW), so the soft edge reaches full transparency
 * exactly at the outer quad boundary with no hard cutoff. This replaces the
 * 2006 baked per-vertex-alpha ring (RectShadow) with a smooth, resolution-
 * independent GPU falloff. Verified by an offscreen Java3D probe.
 *
 * Uniforms (bound by org.jdesktop.lg3d.utils.shape.ShaderEffects):
 *   uHalfWin  vec2  window half-extents in the quad's local units
 *   uSoftNESW vec4  penumbra width per side: +y (north), +x (east),
 *                   -y (south), -x (west) - the shadow margins
 *   uAlpha    float peak shadow opacity at the window edge
 */
varying vec2 vPos;

uniform vec2  uHalfWin;
uniform vec4  uSoftNESW;
uniform float uAlpha;

void main(void) {
    // Per-axis overshoot beyond the window rect (>0 outside, <0 inside).
    vec2 d = abs(vPos) - uHalfWin;

    // Penumbra width for the side this fragment lies on.
    float softX = (vPos.x >= 0.0) ? uSoftNESW.y : uSoftNESW.w; // east : west
    float softY = (vPos.y >= 0.0) ? uSoftNESW.x : uSoftNESW.z; // north : south

    // Normalise the overshoot by its side's penumbra so 0 is the rect edge and
    // 1 is the outer quad boundary; combine the axes for rounded corners.
    vec2 q = max(d, vec2(0.0)) / vec2(max(softX, 1e-5), max(softY, 1e-5));
    float t = length(q);

    float a = uAlpha * (1.0 - smoothstep(0.0, 1.0, t));
    gl_FragColor = vec4(0.0, 0.0, 0.0, a);
}
