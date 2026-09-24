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
package org.jdesktop.lg3d.dbmanager.jdbc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports a {@link QueryResult} to RFC 4180 CSV: a header row of column labels
 * followed by the data rows, with fields quoted and internal quotes doubled only
 * when they contain a comma, quote, CR or LF. SQL NULLs become empty fields.
 */
public final class CsvExporter {

    /**
     * Renders a result as a CSV string.
     *
     * @param result the query result to export
     * @return the CSV text (never {@code null})
     */
    public String toCsv(QueryResult result) {
        StringBuilder sb = new StringBuilder();
        List<String> header = new ArrayList<>();
        for (ColumnMeta c : result.getColumns()) {
            header.add(c.name());
        }
        appendRow(sb, header);
        for (List<Object> row : result.getRows()) {
            List<String> cells = new ArrayList<>(row.size());
            for (Object v : row) {
                cells.add(v == null ? "" : String.valueOf(v));
            }
            appendRow(sb, cells);
        }
        return sb.toString();
    }

    /**
     * Writes a result to a file as UTF-8 CSV.
     *
     * @param result the query result to export
     * @param file   the destination path
     * @throws IOException when the file cannot be written
     */
    public void write(QueryResult result, Path file) throws IOException {
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.write(toCsv(result));
        }
    }

    private static void appendRow(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /** Quotes a field only when required by RFC 4180. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
