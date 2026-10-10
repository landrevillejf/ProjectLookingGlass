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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdesktop.lg3d.mandela.lang.Keywords;

/**
 * The built-in language catalogue of the Advanced Text Editor: the immutable
 * list of {@link Language} definitions and the extension-based lookup used to
 * pick one when a file is opened. A file with an unknown extension (or no
 * extension at all) falls back to {@link #PLAIN}, which colourises nothing
 * &mdash; the editor never guesses wrong in a damaging way.
 *
 * <p>Every other entry spells out its keywords, because those languages are
 * external and their word lists are data to the editor. <b>Mandela</b> is the
 * desktop's own language and takes its keywords from the language jar itself
 * ({@link Keywords}), so the parser and the editor cannot disagree about what a
 * keyword is: when the grammar gains a word, the colouring follows in the same
 * build.</p>
 */
public final class Languages {

    /** The fallback language: no keywords, no comments, no strings. */
    public static final Language PLAIN = new Language("Plain Text",
            setOf("log", "text"), Collections.emptySet(),
            null, null, null, "", false, false);

    private static final List<Language> ALL;
    private static final Map<String, Language> BY_EXTENSION;
    private static final Map<String, Language> BY_NAME;

    static {
        List<Language> all = Arrays.asList(
                PLAIN,
                new Language("Java",
                        setOf("java", "gradle", "groovy", "kt", "kts", "scala"),
                        setOf("abstract", "assert", "boolean", "break", "byte",
                                "case", "catch", "char", "class", "const",
                                "continue", "default", "do", "double", "else",
                                "enum", "extends", "final", "finally", "float",
                                "for", "goto", "if", "implements", "import",
                                "instanceof", "int", "interface", "long",
                                "native", "new", "package", "private",
                                "protected", "public", "record", "return",
                                "sealed", "short", "static", "strictfp",
                                "super", "switch", "synchronized", "this",
                                "throw", "throws", "transient", "try", "var",
                                "void", "volatile", "while", "yield",
                                "String", "Integer", "Long", "Double", "Float",
                                "Boolean", "Object", "Override", "true",
                                "false", "null"),
                        "//", "/*", "*/", "\"'", false, false),
                // Mandela: the desktop's own scripting language. Its keyword set is
                // read from the language rather than copied, and both the reserved
                // and the contextual words are coloured because the parser reads
                // both as words in some position. No preprocessor flag: the only
                // `#` the language knows is a shebang, and `#!` is a comment.
                new Language("Mandela",
                        setOf("mnd"),
                        Keywords.ALL,
                        "//", "/*", "*/", "\"'", false, false),
                new Language("JavaScript",
                        setOf("js", "mjs", "cjs", "jsx"),
                        setOf("async", "await", "break", "case", "catch",
                                "class", "const", "continue", "debugger",
                                "default", "delete", "do", "else", "export",
                                "extends", "finally", "for", "function", "if",
                                "import", "in", "instanceof", "let", "new",
                                "of", "return", "static", "super", "switch",
                                "this", "throw", "try", "typeof", "var",
                                "void", "while", "with", "yield", "true",
                                "false", "null", "undefined"),
                        "//", "/*", "*/", "\"'`", false, false),
                new Language("TypeScript",
                        setOf("ts", "tsx"),
                        setOf("abstract", "any", "as", "async", "await",
                                "boolean", "break", "case", "catch", "class",
                                "const", "continue", "declare", "default",
                                "delete", "do", "else", "enum", "export",
                                "extends", "finally", "for", "from",
                                "function", "if", "implements", "import", "in",
                                "instanceof", "interface", "let", "module",
                                "namespace", "new", "number", "of", "private",
                                "protected", "public", "readonly", "return",
                                "static", "string", "super", "switch", "this",
                                "throw", "try", "type", "typeof", "var",
                                "void", "while", "true", "false", "null",
                                "undefined"),
                        "//", "/*", "*/", "\"'`", false, false),
                new Language("Python",
                        setOf("py", "pyw", "pyi"),
                        setOf("and", "as", "assert", "async", "await",
                                "break", "class", "continue", "def", "del",
                                "elif", "else", "except", "False", "finally",
                                "for", "from", "global", "if", "import", "in",
                                "is", "lambda", "None", "nonlocal", "not",
                                "or", "pass", "raise", "return", "True",
                                "try", "while", "with", "yield", "self"),
                        "#", null, null, "\"'", false, false),
                new Language("C / C++",
                        setOf("c", "h", "cpp", "cc", "cxx", "hpp", "hh"),
                        setOf("auto", "bool", "break", "case", "catch",
                                "char", "class", "const", "constexpr",
                                "continue", "default", "delete", "do",
                                "double", "else", "enum", "explicit",
                                "extern", "false", "float", "for", "friend",
                                "goto", "if", "include", "inline", "int",
                                "long", "namespace", "new", "noexcept",
                                "nullptr", "operator", "private", "protected",
                                "public", "register", "return", "short",
                                "signed", "sizeof", "static", "struct",
                                "switch", "template", "this", "throw", "true",
                                "try", "typedef", "typename", "union",
                                "unsigned", "using", "virtual", "void",
                                "volatile", "while"),
                        "//", "/*", "*/", "\"'", true, false),
                new Language("C#",
                        setOf("cs"),
                        setOf("abstract", "as", "base", "bool", "break",
                                "byte", "case", "catch", "char", "checked",
                                "class", "const", "continue", "decimal",
                                "default", "delegate", "do", "double", "else",
                                "enum", "event", "explicit", "extern",
                                "false", "finally", "fixed", "float", "for",
                                "foreach", "goto", "if", "implicit", "in",
                                "int", "interface", "internal", "is", "lock",
                                "long", "namespace", "new", "null", "object",
                                "operator", "out", "override", "params",
                                "private", "protected", "public", "readonly",
                                "ref", "return", "sbyte", "sealed", "short",
                                "sizeof", "stackalloc", "static", "string",
                                "struct", "switch", "this", "throw", "true",
                                "try", "typeof", "uint", "ulong", "unchecked",
                                "unsafe", "ushort", "using", "var", "virtual",
                                "void", "volatile", "while"),
                        "//", "/*", "*/", "\"'", false, false),
                new Language("Go",
                        setOf("go", "mod"),
                        setOf("break", "case", "chan", "const", "continue",
                                "default", "defer", "else", "fallthrough",
                                "for", "func", "go", "goto", "if", "import",
                                "interface", "map", "package", "range",
                                "return", "select", "struct", "switch",
                                "type", "var", "true", "false", "nil",
                                "string", "int", "int64", "float64", "bool",
                                "byte", "error"),
                        "//", "/*", "*/", "\"'`", false, false),
                new Language("XML",
                        setOf("xml", "xsd", "xsl", "svg", "pom", "lgcfg",
                                "jhm", "hs"),
                        Collections.emptySet(),
                        null, "<!--", "-->", "\"'", false, true),
                new Language("HTML",
                        setOf("html", "htm", "xhtml"),
                        Collections.emptySet(),
                        null, "<!--", "-->", "\"'", false, true),
                new Language("CSS",
                        setOf("css", "scss", "less"),
                        setOf("import", "media", "charset", "supports",
                                "keyframes", "font-face", "important"),
                        null, "/*", "*/", "\"'", false, false),
                new Language("JSON",
                        setOf("json", "json5", "ipynb", "lg3d-theme"),
                        setOf("true", "false", "null"),
                        null, null, null, "\"", false, false),
                new Language("YAML",
                        setOf("yml", "yaml"),
                        setOf("true", "false", "null", "yes", "no", "on",
                                "off"),
                        "#", null, null, "\"'", false, false),
                new Language("SQL",
                        setOf("sql", "ddl", "dml"),
                        setOf("ADD", "ALL", "ALTER", "AND", "AS", "ASC",
                                "BEGIN", "BETWEEN", "BY", "CASE", "CHECK",
                                "COLUMN", "COMMIT", "CONSTRAINT", "CREATE",
                                "DATABASE", "DEFAULT", "DELETE", "DESC",
                                "DISTINCT", "DROP", "ELSE", "END", "EXISTS",
                                "FOREIGN", "FROM", "GROUP", "HAVING", "IN",
                                "INDEX", "INNER", "INSERT", "INTO", "IS",
                                "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "NOT",
                                "NULL", "ON", "OR", "ORDER", "OUTER",
                                "PRIMARY", "REFERENCES", "RIGHT", "ROLLBACK",
                                "SELECT", "SET", "TABLE", "THEN", "TO",
                                "TRANSACTION", "UNION", "UNIQUE", "UPDATE",
                                "VALUES", "VIEW", "WHEN", "WHERE"),
                        "--", "/*", "*/", "\"'`", false, false),
                new Language("Shell",
                        setOf("sh", "bash", "zsh", "ksh", "profile"),
                        setOf("case", "do", "done", "elif", "else", "esac",
                                "eval", "exec", "exit", "export", "fi",
                                "for", "function", "if", "in", "local",
                                "read", "readonly", "return", "select",
                                "set", "shift", "then", "time", "until",
                                "while"),
                        "#", null, null, "\"'`", false, false),
                new Language("Properties",
                        setOf("properties", "ini", "cfg", "conf", "toml",
                                "desktop"),
                        Collections.emptySet(),
                        "#", null, null, "\"'", false, false),
                new Language("Markdown",
                        setOf("md", "markdown", "mdown"),
                        Collections.emptySet(),
                        null, "<!--", "-->", "\"'`", false, false));

        ALL = Collections.unmodifiableList(all);

        Map<String, Language> byExt = new LinkedHashMap<>();
        Map<String, Language> byName = new LinkedHashMap<>();
        // Later languages must not steal an extension from an earlier, more
        // specific one (e.g. ".h" belongs to C / C++, not to Shell).
        for (Language lang : all) {
            for (String ext : lang.getExtensions()) {
                byExt.putIfAbsent(ext, lang);
            }
            byName.putIfAbsent(lang.getName().toLowerCase(Locale.ROOT), lang);
        }
        BY_EXTENSION = Collections.unmodifiableMap(byExt);
        BY_NAME = Collections.unmodifiableMap(byName);
    }

    private Languages() {
        // Static catalogue.
    }

    /** Every built-in language, in catalogue order, starting with Plain Text. */
    public static List<Language> all() {
        return ALL;
    }

    /**
     * The language registered for a bare, lower-case extension, or
     * {@link #PLAIN} when none is.
     */
    public static Language forExtension(String extension) {
        if (extension == null) {
            return PLAIN;
        }
        String ext = extension.trim().toLowerCase(Locale.ROOT);
        if (ext.startsWith(".")) {
            ext = ext.substring(1);
        }
        Language lang = BY_EXTENSION.get(ext);
        return (lang != null) ? lang : PLAIN;
    }

    /**
     * The language for a file name or path: its extension decides, anything
     * unknown yields {@link #PLAIN}. Dotfiles without an extension (e.g.
     * {@code .gitignore}) are plain text.
     */
    public static Language forFileName(String fileName) {
        if (fileName == null) {
            return PLAIN;
        }
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot <= 0 || dot == base.length() - 1) {
            return PLAIN;
        }
        return forExtension(base.substring(dot + 1));
    }

    /** The language with the given display name (case-insensitive), or null. */
    public static Language forName(String name) {
        if (name == null) {
            return null;
        }
        return BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }
}
