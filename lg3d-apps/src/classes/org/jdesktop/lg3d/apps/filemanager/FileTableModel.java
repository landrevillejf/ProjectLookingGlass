/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.apps.filemanager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;
import org.jdesktop.lg3d.utils.system.ProcessService;

/**
 * Table model listing the entries of one directory as rows with
 * Name / Size / Type / Modified columns. Directories sort before files.
 */
public class FileTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"Name", "Size", "Type", "Modified"};

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());

    private final List<Path> rows = new ArrayList<>();
    private Path directory;

    /** Points the model at a directory and reloads its entries. */
    public void setDirectory(Path dir) {
        this.directory = dir;
        reload();
    }

    public Path getDirectory() {
        return directory;
    }

    /** Re-reads the current directory from disk. */
    public void reload() {
        rows.clear();
        if (directory != null && Files.isDirectory(directory)) {
            try (var ds = Files.newDirectoryStream(directory)) {
                for (Path p : ds) {
                    rows.add(p);
                }
            } catch (IOException | RuntimeException e) {
                rows.clear();
            }
        }
        rows.sort((a, b) -> {
            boolean da = Files.isDirectory(a);
            boolean db = Files.isDirectory(b);
            if (da != db) {
                return da ? -1 : 1;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(name(a), name(b));
        });
        fireTableDataChanged();
    }

    public Path getFileAt(int row) {
        return (row >= 0 && row < rows.size()) ? rows.get(row) : null;
    }

    /** All rows (the directory's entries), in display order. */
    public List<Path> getAll() {
        return new ArrayList<>(rows);
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return (column >= 0 && column < COLUMNS.length) ? COLUMNS[column] : "";
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Path p = getFileAt(rowIndex);
        if (p == null) {
            return "";
        }
        boolean dir = Files.isDirectory(p);
        switch (columnIndex) {
            case 0:
                return name(p);
            case 1:
                return dir ? "" : ProcessService.formatBytes(size(p));
            case 2:
                return dir ? "Folder" : type(p);
            case 3:
                return modified(p);
            default:
                return "";
        }
    }

    // ------------------------------------------------------------------

    private static String name(Path p) {
        Path n = p.getFileName();
        return (n != null) ? n.toString() : p.toString();
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    private static String type(Path p) {
        String n = name(p);
        int dot = n.lastIndexOf('.');
        if (dot > 0 && dot < n.length() - 1) {
            return n.substring(dot + 1).toUpperCase();
        }
        return "File";
    }

    private static String modified(Path p) {
        try {
            return DATE.format(Instant.ofEpochMilli(
                    Files.getLastModifiedTime(p).toMillis()));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
