/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into individual statements on semicolons, ignoring
 * separators that appear inside string literals, quoted identifiers or comments.
 *
 * <p>This is deliberately a lightweight scanner (not a SQL parser): it handles
 * the common single-quote strings, double-quote identifiers, backtick
 * identifiers, {@code --} line comments and {@code /* *}{@code /} block comments
 * that show up in hand-written scripts, which is enough to run a multi-statement
 * buffer one statement at a time.</p>
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {
    }

    /**
     * Splits a script into non-empty, trimmed statements.
     *
     * @param script the SQL text; {@code null} yields an empty list
     * @return the statements in order, without trailing semicolons
     */
    public static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        int i = 0;
        int n = script.length();
        while (i < n) {
            char c = script.charAt(i);
            // Line comment: -- to end of line.
            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                int end = script.indexOf('\n', i);
                if (end < 0) {
                    break;
                }
                current.append(script, i, end + 1);
                i = end + 1;
                continue;
            }
            // Block comment: /* ... */ (kept verbatim so positions stay sane).
            if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                int stop = (end < 0) ? n : end + 2;
                current.append(script, i, stop);
                i = stop;
                continue;
            }
            // Quoted literal / identifier: consume through the matching quote,
            // honoring a doubled quote as an escape.
            if (c == '\'' || c == '"' || c == '`') {
                int stop = endOfQuoted(script, i, c);
                current.append(script, i, stop);
                i = stop;
                continue;
            }
            if (c == ';') {
                addIfNotBlank(statements, current);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        addIfNotBlank(statements, current);
        return statements;
    }

    /**
     * Returns the index just past the quoted region starting at {@code start}.
     * Handles doubled-quote escapes; an unterminated quote runs to end of text.
     */
    private static int endOfQuoted(String s, int start, char quote) {
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == quote) {
                if (i + 1 < n && s.charAt(i + 1) == quote) {
                    i += 2; // escaped quote
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static void addIfNotBlank(List<String> out, StringBuilder sb) {
        String stmt = sb.toString().trim();
        if (!stmt.isEmpty()) {
            out.add(stmt);
        }
    }
}
