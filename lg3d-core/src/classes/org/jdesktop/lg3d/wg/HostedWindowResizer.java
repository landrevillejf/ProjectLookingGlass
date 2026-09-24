/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.wg;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Bridges window-resize requests from the core window chrome
 * ({@code Frame3DWindowDecoration} maximize) to the code that actually owns a
 * hosted window's sizing ({@code TitledSwingWindow} in the app modules).
 *
 * <p>The decoration lives in {@code lg3d-core} while the title bar, spine
 * titles, thumbnail and panel sizing of a hosted Swing window are built by the
 * app-side helper, so the decoration cannot resize them directly. The helper
 * registers a {@link Resizer} per {@link Frame3D} here at construction; the
 * decoration looks it up when the user maximizes, so a hosted window resizes
 * its content at native text size (like a real {@code JFrame}) instead of
 * being uniformly scaled (which magnifies the texture and letterboxes narrow
 * windows). Frames with no registered resizer (pure-3D apps) keep the
 * scale-based maximize.
 *
 * <p>The registry is a {@link WeakHashMap}: entries disappear with their frame,
 * so closed windows never leak.
 */
public final class HostedWindowResizer {

    /**
     * Resizes a hosted window so its whole content area (title bar included)
     * becomes {@code widthPx} x {@code heightPx} native pixels.
     */
    public interface Resizer {
        void resize(int widthPx, int heightPx);
    }

    private static final Map<Frame3D, Resizer> REGISTRY =
            Collections.synchronizedMap(new WeakHashMap<Frame3D, Resizer>());

    private HostedWindowResizer() {
    }

    public static void register(Frame3D frame, Resizer resizer) {
        if (frame != null && resizer != null) {
            REGISTRY.put(frame, resizer);
        }
    }

    public static void unregister(Frame3D frame) {
        if (frame != null) {
            REGISTRY.remove(frame);
        }
    }

    /** True when {@code frame} hosts resizable Swing content. */
    public static boolean isResizable(Frame3D frame) {
        return frame != null && REGISTRY.containsKey(frame);
    }

    /**
     * Resizes {@code frame}'s hosted content to the given pixel size; no-op
     * when the frame registered no resizer.
     */
    public static void resize(Frame3D frame, int widthPx, int heightPx) {
        Resizer r = (frame == null) ? null : REGISTRY.get(frame);
        if (r != null) {
            r.resize(widthPx, heightPx);
        }
    }
}
