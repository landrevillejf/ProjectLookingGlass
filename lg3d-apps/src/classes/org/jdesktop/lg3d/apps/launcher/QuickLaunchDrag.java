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
package org.jdesktop.lg3d.apps.launcher;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DragGestureEvent;
import java.awt.dnd.DragSource;
import java.util.function.Supplier;
import javax.swing.JComponent;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;

/**
 * Drag-and-drop glue that lets a launcher built in the {@link LauncherFrame} be
 * dragged straight onto the 2D desktop's taskbar quick-launch strip. The frame
 * is the drag source; the strip (lg3d-core, {@code Desktop2DTaskbar}) is the
 * drop target, and both share one flavour,
 * {@link Desktop2D#QUICK_LAUNCH_FLAVOR}, carrying a
 * {@link Desktop2D.QuickLaunchItem} by reference (they run in the same JVM).
 *
 * <p>The payload building and the transferable are kept here, separate from the
 * NetBeans-generated frame, so they can be unit-tested headless.</p>
 */
final class QuickLaunchDrag {

    /** Icon URL scheme used by {@code .lgcfg} descriptors. */
    private static final String RESOURCE_SCHEME = "resource:///";

    private QuickLaunchDrag() {
        // no instances
    }

    /**
     * Builds the dragged payload from the launcher form's fields, or {@code null}
     * when there is nothing to launch yet (a blank command). The name falls back
     * to the command, and a {@code resource:///} icon URL is reduced to the
     * classpath path the quick-launch model stores; an absolute file path is
     * passed through (the strip resolves its icon by name regardless).
     */
    static Desktop2D.QuickLaunchItem item(final String name, final String command,
                                          final String iconPath) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String cmd = command.trim();
        String nm = (name == null || name.isBlank()) ? cmd : name.trim();
        return new Desktop2D.QuickLaunchItem(nm, cmd, normalizeIcon(iconPath));
    }

    /** Strips the {@code resource:///} scheme; any other path is returned as-is. */
    static String normalizeIcon(final String iconPath) {
        if (iconPath == null || iconPath.isBlank()) {
            return null;
        }
        String path = iconPath.trim();
        return path.startsWith(RESOURCE_SCHEME)
                ? path.substring(RESOURCE_SCHEME.length()) : path;
    }

    /** Wraps {@code item} in the transferable the taskbar strip accepts. */
    static Transferable transferable(final Desktop2D.QuickLaunchItem item) {
        return new QuickLaunchTransferable(item);
    }

    /**
     * Installs a drag gesture on {@code source} that exports the launcher the
     * {@code supplier} builds at drag time. A supplier returning {@code null}
     * (no command yet) starts no drag, so the source stays a plain click target.
     */
    static void install(final JComponent source,
                        final Supplier<Desktop2D.QuickLaunchItem> supplier) {
        DragSource dragSource = DragSource.getDefaultDragSource();
        dragSource.createDefaultDragGestureRecognizer(source,
                DnDConstants.ACTION_COPY_OR_MOVE,
                (DragGestureEvent dge) -> startDrag(dge, supplier));
    }

    private static void startDrag(final DragGestureEvent dge,
                                  final Supplier<Desktop2D.QuickLaunchItem> supplier) {
        Desktop2D.QuickLaunchItem item = supplier.get();
        if (item == null) {
            return;
        }
        dge.startDrag(DragSource.DefaultMoveDrop, transferable(item));
    }

    /**
     * Carries a {@link Desktop2D.QuickLaunchItem} under
     * {@link Desktop2D#QUICK_LAUNCH_FLAVOR} (a same-JVM reference), plus the
     * launch command as plain text so an ordinary text target can still read it.
     */
    private static final class QuickLaunchTransferable implements Transferable {

        private final Desktop2D.QuickLaunchItem item;

        QuickLaunchTransferable(final Desktop2D.QuickLaunchItem item) {
            this.item = item;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {
                Desktop2D.QUICK_LAUNCH_FLAVOR, DataFlavor.stringFlavor };
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            return Desktop2D.QUICK_LAUNCH_FLAVOR.equals(flavor)
                    || DataFlavor.stringFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(final DataFlavor flavor)
                throws UnsupportedFlavorException {
            if (Desktop2D.QUICK_LAUNCH_FLAVOR.equals(flavor)) {
                return item;
            }
            if (DataFlavor.stringFlavor.equals(flavor)) {
                return (item == null) ? "" : item.command();
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }
}
