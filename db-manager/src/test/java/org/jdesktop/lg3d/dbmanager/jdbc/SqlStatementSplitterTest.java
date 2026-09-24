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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link SqlStatementSplitter} scanner: splitting on statement
 * separators while ignoring semicolons inside strings, quoted identifiers and
 * comments, dropping blank statements and tolerating null/empty/unterminated
 * input.
 */
class SqlStatementSplitterTest {

    @Test
    @DisplayName("null and empty yield no statements")
    void nullAndEmpty() {
        assertThat(SqlStatementSplitter.split(null)).isEmpty();
        assertThat(SqlStatementSplitter.split("")).isEmpty();
        assertThat(SqlStatementSplitter.split("   ;  ;  ")).isEmpty();
    }

    @Test
    @DisplayName("a single statement is returned trimmed, without a trailing semicolon")
    void singleStatement() {
        assertThat(SqlStatementSplitter.split("  SELECT 1 ;  "))
                .containsExactly("SELECT 1");
        assertThat(SqlStatementSplitter.split("SELECT 1"))
                .containsExactly("SELECT 1");
    }

    @Test
    @DisplayName("multiple statements split in order")
    void multipleStatements() {
        List<String> out = SqlStatementSplitter.split(
                "CREATE TABLE t(a INT); INSERT INTO t VALUES (1); SELECT * FROM t;");
        assertThat(out).containsExactly(
                "CREATE TABLE t(a INT)",
                "INSERT INTO t VALUES (1)",
                "SELECT * FROM t");
    }

    @Test
    @DisplayName("a semicolon inside a string literal does not split")
    void semicolonInsideString() {
        List<String> out = SqlStatementSplitter.split(
                "INSERT INTO t VALUES ('a;b'); SELECT 2;");
        assertThat(out).containsExactly("INSERT INTO t VALUES ('a;b')", "SELECT 2");
    }

    @Test
    @DisplayName("a doubled quote is an escaped quote, not a string end")
    void escapedQuote() {
        List<String> out = SqlStatementSplitter.split(
                "SELECT 'it''s; here'; SELECT 2;");
        assertThat(out).containsExactly("SELECT 'it''s; here'", "SELECT 2");
    }

    @Test
    @DisplayName("semicolons inside double-quote and backtick identifiers are ignored")
    void quotedIdentifiers() {
        assertThat(SqlStatementSplitter.split("SELECT \"we;ird\" FROM t;"))
                .containsExactly("SELECT \"we;ird\" FROM t");
        assertThat(SqlStatementSplitter.split("SELECT `we;ird` FROM t;"))
                .containsExactly("SELECT `we;ird` FROM t");
    }

    @Test
    @DisplayName("a semicolon in a line comment does not split")
    void lineComment() {
        List<String> out = SqlStatementSplitter.split(
                "-- note; still a comment\nSELECT 1;");
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).contains("SELECT 1");
    }

    @Test
    @DisplayName("a semicolon in a block comment does not split")
    void blockComment() {
        List<String> out = SqlStatementSplitter.split(
                "/* a; b */ SELECT 1; SELECT 2;");
        assertThat(out).hasSize(2);
    }

    @Test
    @DisplayName("an unterminated quote or comment runs to end of text")
    void unterminated() {
        assertThat(SqlStatementSplitter.split("SELECT 'oops")).hasSize(1);
        assertThat(SqlStatementSplitter.split("SELECT /* oops")).hasSize(1);
        // A dangling line comment with no newline is dropped as blank.
        assertThat(SqlStatementSplitter.split("-- just a comment")).isEmpty();
    }
}
