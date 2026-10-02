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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.jdesktop.lg3d.sg.Node;
import org.jdesktop.lg3d.sg.Texture;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;

/**
 * The default {@link WindowThumbnailSource}: it hands out the live
 * {@code Texture2D} a window's {@link SwingNode} already paints into, so a
 * carousel card shows the real, updating window content for Swing-to-node
 * windows (the great majority of desktop apps).
 *
 * <p>For each frame it locates the first {@code SwingNode} in the window's
 * subtree and registers a {@link SwingNode.TextureListener}. The listener fires
 * synchronously with the current texture on registration and again whenever the
 * node recreates its texture (first capture, resize), so the cached texture
 * stays live; content-only repaints update the same {@code Texture2D} in place
 * and need no callback. Windows with no {@code SwingNode} (pure-3D apps) return
 * {@code null} and the card falls back to a titled glass panel.</p>
 *
 * <p>This class touches the live scene graph and so is not headless-constructible;
 * it is kept thin, and every subtree walk is guarded so a torn-down frame cannot
 * break the switcher. The carousel logic that consumes it is tested against a
 * fake {@link WindowThumbnailSource} instead.</p>
 */
public final class SwingNodeThumbnailSource implements WindowThumbnailSource {

    /**
     * Per-frame record of the {@link SwingNode} found, the listener registered on
     * it (so it can be removed) and the latest texture the listener reported. A
     * one-element array breaks the listener/binding construction cycle: the
     * listener closes over the binding and writes {@code texture[0]}.
     */
    private static final class Binding {
        final SwingNode node;
        final Texture2D[] texture = new Texture2D[1];
        SwingNode.TextureListener listener;

        Binding(SwingNode node) {
            this.node = node;
        }
    }

    private final Map<Frame3D, Binding> bindings = new HashMap<>();

    @Override
    public Texture textureFor(Frame3D frame) {
        if (frame == null) {
            return null;
        }
        try {
            Binding binding = bindings.get(frame);
            if (binding == null) {
                SwingNode node = findSwingNode(frame);
                if (node == null) {
                    return null;
                }
                binding = new Binding(node);
                final Binding b = binding;
                SwingNode.TextureListener listener = tex -> b.texture[0] = tex;
                binding.listener = listener;
                node.addTextureListener(listener);
                bindings.put(frame, binding);
            }
            return binding.texture[0];
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void release(Frame3D frame) {
        if (frame == null) {
            return;
        }
        Binding binding = bindings.remove(frame);
        if (binding == null) {
            return;
        }
        try {
            binding.node.removeTextureListener(binding.listener);
        } catch (Throwable t) {
            // The node may already be disposed; nothing to release.
        }
    }

    /** Releases every registered listener; called when the switcher is torn down. */
    public void releaseAll() {
        for (Frame3D frame : new ArrayList<>(bindings.keySet())) {
            release(frame);
        }
    }

    /**
     * Depth-first search of the window subtree for the first {@link SwingNode}.
     * {@code getAllChildren()} yields facade {@link Node}s, so a child that is a
     * {@link Component3D} is recursed into.
     */
    private static SwingNode findSwingNode(Component3D root) {
        Enumeration children = root.getAllChildren();
        while (children.hasMoreElements()) {
            Object child = children.nextElement();
            if (child instanceof SwingNode swingNode) {
                return swingNode;
            }
            if (child instanceof Component3D comp) {
                SwingNode found = findSwingNode(comp);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
