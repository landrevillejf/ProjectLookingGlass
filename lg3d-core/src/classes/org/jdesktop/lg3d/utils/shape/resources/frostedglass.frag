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
 * Frosted-glass rounded panel - fragment stage.
 *
 * Evaluates the exact signed distance from the fragment to a rounded rectangle
 * (half-extents uHalfWin, corner radius uRadius). Fragments outside the rounded
 * silhouette are discarded; the boundary is anti-aliased over a +/-uAa band so
 * the corners are smooth at any zoom (the 2006 GlassyPanel instead tessellated
 * a beveled box with hard, aliasing edges). Just inside the border a frosted
 * band (uEdge wide) lightens the tint and lifts its opacity, fading to the
 * clear glass toward the centre - the "frosted-glass edge" look.
 *
 * The AA band is driven by fwidth(d) - the screen-space derivative of the
 * distance field, i.e. ~1 pixel - so the silhouette stays smooth at any zoom or
 * panel size; uAa is a world-space floor for degenerate near-zero-derivative
 * fragments. (Jogamp's desktop GLSL accepts fwidth; were the standard-derivatives
 * path ever unavailable the program would fall back to the uAa floor.)
 *
 * Uniforms (bound by org.jdesktop.lg3d.utils.shape.ShaderEffects):
 *   uHalfWin vec2   panel half-extents in the quad's local units
 *   uRadius  float  rounded-corner radius (clamped to min(halfW, halfH))
 *   uEdge    float  frosted band width measured inward from the border
 *   uAa      float  anti-aliasing half-width across the silhouette
 *   uTint    vec4   rgb glass tint, w base opacity of the clear centre
 */
varying vec2 vPos;

uniform vec2  uHalfWin;
uniform float uRadius;
uniform float uEdge;
uniform float uAa;
uniform vec4  uTint;

// Signed distance to a rounded rect centred at the origin (<0 inside).
// NB: the half-extent param is named 'he', not 'half' - 'half' is a reserved
// word in GLSL and using it fails to compile on Jogamp's GLSL implementation.
float sdRoundRect(vec2 p, vec2 he, float r) {
    vec2 q = abs(p) - he + r;
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

void main(void) {
    float d = sdRoundRect(vPos, uHalfWin, uRadius);

    // Screen-space AA: fwidth(d) ~= one pixel of distance change, so the
    // silhouette ramps over ~1px at any zoom; uAa is a world-space floor.
    float aa = max(fwidth(d), uAa);

    // 1 well inside the silhouette, 0 well outside, smooth across +/-aa.
    float coverage = 1.0 - smoothstep(-aa, aa, d);
    if (coverage <= 0.001) {
        discard;
    }

    // Frost: strongest at the border (d ~ 0), gone uEdge inside (d ~ -uEdge).
    float frost = 1.0 - smoothstep(0.0, max(uEdge, 1e-5), -d);

    vec3  rgb   = mix(uTint.rgb, vec3(1.0), 0.55 * frost);
    float alpha = uTint.w * coverage * (0.55 + 0.45 * frost);
    gl_FragColor = vec4(rgb, alpha);
}
