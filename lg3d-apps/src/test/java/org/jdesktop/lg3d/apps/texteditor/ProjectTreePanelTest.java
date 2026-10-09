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
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the west {@link ProjectTreePanel}: the pure
 * project-root walk-up, lazy child loading on expansion, ordering, hidden
 * filtering, and the double-click-to-open callback.
 */
class ProjectTreePanelTest {

    // -- projectRootFor (pure) ---------------------------------------------

    @Test
    @DisplayName("projectRootFor walks up to the nearest marker directory")
    void rootWithMarker(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        Path src = Files.createDirectories(tmp.resolve("src/main"));
        Path file = src.resolve("Foo.java");
        Files.writeString(file, "public class Foo { }\n");
        assertEquals(tmp, ProjectTreePanel.projectRootFor(file));
    }

    @Test
    @DisplayName("without a marker the parent directory is the root")
    void rootFallsBackToParent(@TempDir Path tmp) throws Exception {
        Path deep = Files.createDirectories(tmp.resolve("a/b/c"));
        Path file = deep.resolve("note.txt");
        Files.writeString(file, "x\n");
        assertEquals(deep, ProjectTreePanel.projectRootFor(file));
        assertNull(ProjectTreePanel.projectRootFor(null));
        // A bare name with no parent resolves to the working directory.
        assertEquals(Path.of("").toAbsolutePath(),
                ProjectTreePanel.projectRootFor(Path.of("x.txt")));
    }

    @Test
    @DisplayName("the walk-up stops at MAX_WALK_UP levels")
    void rootWalkIsBounded(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve(".git"));
        Path deep = tmp;
        for (int i = 0; i < ProjectTreePanel.MAX_WALK_UP + 2; i++) {
            deep = Files.createDirectory(deep.resolve("d" + i));
        }
        // Too far from the marker: the file's own directory is reported.
        assertEquals(deep, ProjectTreePanel.projectRootFor(deep.resolve("F.java")));
    }

    // -- lazy tree -----------------------------------------------------------

    private static List<String> childNames(DefaultMutableTreeNode node) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            Object uo = ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject();
            out.add(uo instanceof Path p ? p.getFileName().toString() : String.valueOf(uo));
        }
        return out;
    }

    @Test
    @DisplayName("setRootPath lazily loads dirs-before-files, hidden skipped")
    void lazyLoad(@TempDir Path tmp) throws Exception {
        Files.createDirectory(tmp.resolve("betaDir"));
        Files.createDirectory(tmp.resolve("AlphaDir"));
        Files.writeString(tmp.resolve("b.txt"), "b");
        Files.writeString(tmp.resolve("A.java"), "a");
        Files.writeString(tmp.resolve(".hidden"), "h");

        ProjectTreePanel panel = new ProjectTreePanel();
        panel.setRootPath(tmp);
        DefaultMutableTreeNode root =
                (DefaultMutableTreeNode) panel.tree().getModel().getRoot();
        // The expansion triggered by setRootPath swapped in the real children.
        assertEquals(List.of("AlphaDir", "betaDir", "A.java", "b.txt"),
                childNames(root), "directories first, then files, alphabetical");
        assertEquals(tmp, panel.rootPath());
        // Re-rooting on the same directory is a no-op.
        panel.setRootPath(tmp);
        assertEquals(tmp, panel.rootPath());
    }

    @Test
    @DisplayName("double-clicking a file node routes it to onFileChosen")
    void openSelectedFile(@TempDir Path tmp) throws Exception {
        Path sub = Files.createDirectories(tmp.resolve("sub"));
        Path file = sub.resolve("note.txt");
        Files.writeString(file, "x");
        List<Path> opened = new ArrayList<>();
        ProjectTreePanel panel = new ProjectTreePanel();
        panel.setOnFileChosen(opened::add);
        panel.setRootPath(tmp);

        assertTrue(panel.revealAndOpen(file), "reveals, selects and opens");
        assertEquals(List.of(file.toAbsolutePath().normalize()), opened);

        opened.clear();
        assertFalse(panel.revealAndOpen(tmp.resolve("missing.txt")));
        assertFalse(panel.revealAndOpen(sub));   // a directory never opens
        assertFalse(panel.revealAndOpen(null));
        panel.setOnFileChosen(null);             // null-safe reset
    }

    @Test
    @DisplayName("files outside the current root are refused")
    void outsideRoot(@TempDir Path tmp) throws Exception {
        ProjectTreePanel panel = new ProjectTreePanel();
        assertFalse(panel.revealAndOpen(Path.of("/etc/hosts")));
        panel.setRootPath(tmp);
        Files.writeString(tmp.resolve("in.txt"), "x");
        assertFalse(panel.revealAndOpen(tmp.resolve("in.txt").getParent()
                .resolve("nope.txt")));
    }
}
