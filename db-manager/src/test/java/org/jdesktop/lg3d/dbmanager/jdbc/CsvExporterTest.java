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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers RFC 4180 {@link CsvExporter}: the header row, CRLF line endings,
 * quoting/escaping of fields containing a comma, quote, CR or LF, NULL as an
 * empty field and writing to a UTF-8 file.
 */
class CsvExporterTest {

    private static QueryResult result(List<String> header, List<List<Object>> rows) {
        List<ColumnMeta> cols = new java.util.ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            cols.add(new ColumnMeta(header.get(i), "VARCHAR", Types.VARCHAR,
                    true, "java.lang.String", i + 1));
        }
        return QueryResult.ofResultSet("SELECT", cols, rows, false, 0L);
    }

    @Test
    @DisplayName("header and rows render with CRLF separators")
    void headerAndRows() {
        String csv = new CsvExporter().toCsv(
                result(List.of("a", "b"), List.of(List.of("1", "2"), List.of("3", "4"))));
        assertThat(csv).isEqualTo("a,b\r\n1,2\r\n3,4\r\n");
    }

    @Test
    @DisplayName("a field with a comma, quote or newline is quoted and escaped")
    void quotingAndEscaping() {
        assertThat(CsvExporter.escape("plain")).isEqualTo("plain");
        assertThat(CsvExporter.escape("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvExporter.escape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvExporter.escape("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(CsvExporter.escape("carriage\rreturn")).isEqualTo("\"carriage\rreturn\"");
        assertThat(CsvExporter.escape(null)).isEmpty();
    }

    @Test
    @DisplayName("SQL NULL becomes an empty field")
    void nullBecomesEmpty() {
        List<Object> row = Arrays.asList("x", null, "z");
        String csv = new CsvExporter().toCsv(result(List.of("a", "b", "c"), List.of(row)));
        assertThat(csv).isEqualTo("a,b,c\r\nx,,z\r\n");
    }

    @Test
    @DisplayName("an empty result still emits the header row")
    void emptyResultHasHeader() {
        String csv = new CsvExporter().toCsv(result(List.of("a", "b"), List.of()));
        assertThat(csv).isEqualTo("a,b\r\n");
    }

    @Test
    @DisplayName("write produces a UTF-8 file matching toCsv")
    void writeToFile(@TempDir Path dir) throws IOException {
        QueryResult r = result(List.of("name", "note"),
                List.of(List.of("café", "has,comma")));
        Path file = dir.resolve("out.csv");
        new CsvExporter().write(r, file);

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).isEqualTo(new CsvExporter().toCsv(r));
        assertThat(written).contains("café").contains("\"has,comma\"");
    }
}
