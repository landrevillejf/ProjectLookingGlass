/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Java Completion" extension (Espresso Phase 4): dependency-free
 * code completion for the current Java document. Candidates come from three
 * cheap, always-available sources — (a) the Phase-2 AST outline of the file
 * itself (types, methods, fields), (b) the public members of imported and
 * {@code java.lang} types via plain reflection, and (c) the Java keyword set —
 * filtered by the identifier prefix at the caret or, after {@code recv.}, by
 * the receiver's declared type. Presentation is the in-panel Completions
 * strip (a {@code JList}; no popups, so the editor stays offscreen-safe);
 * accepting (Enter/Tab on the strip, or double-click) replaces the identifier
 * run at the caret through {@link EditorTab#insertCompletion(String)}.
 *
 * <p><b>Documented limitation.</b> This is deliberately <em>shallow</em>
 * completion: no javac-internal scope analysis, no inference, no Kotlin
 * (out of scope). Types that cannot be resolved by simple name through the
 * file's imports (plus a few {@code java.lang} spellings) contribute nothing,
 * which keeps the feature fast and can never corrupt the document. Deep
 * completion was rejected for reliability.</p>
 *
 * <p><b>Testability.</b> Every decision — prefix extraction, receiver typing,
 * import resolution, reflection member listing, ranking — is a pure static;
 * the extension body only wires those to the SPI. No test needs the panel.</p>
 */
public final class JavaCompletionTools implements TextEditorExtension {

    /** Candidates shown per invocation; keeps the strip scannable. */
    static final int MAX_CANDIDATES = 50;

    /** All Java keywords, including the three restricted literals. */
    static final List<String> KEYWORDS = List.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "record", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw",
            "throws", "transient", "try", "var", "void", "volatile", "while",
            "true", "false", "null");

    /** {@code import [static] a.b.C;} — group 1 flags static, 2 the name. */
    private static final Pattern IMPORT =
            Pattern.compile("^\\s*import\\s+(static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[\\w$*]+)+)\\s*;");

    /** A declaration of {@code recv}: {@code Type recv =/;/)/,} anywhere. */
    private static Pattern declaration(String receiver) {
        return Pattern.compile("([A-Za-z][\\w.]*)(?:<[^>]*>)?\\s+"
                + Pattern.quote(receiver) + "\\s*[=;,)]");
    }

    /** Per-JVM cache of reflected members; a typo'd name caches the miss. */
    private static final Map<String, List<String>> MEMBER_CACHE =
            new ConcurrentHashMap<>();

    /** Types {@code java.lang} exports that programmers spell without imports. */
    private static final List<String> JAVA_LANG_FALLBACK = List.of(
            "java.lang.String", "java.lang.Object", "java.lang.Integer",
            "java.lang.Long", "java.lang.Double", "java.lang.Boolean",
            "java.lang.Math", "java.lang.System", "java.lang.Thread",
            "java.lang.Exception", "java.lang.RuntimeException");

    private EditorContext editor;
    private DocumentContext currentDoc;

    /** Public no-arg constructor: required by the {@link java.util.ServiceLoader} SPI. */
    public JavaCompletionTools() {
    }

    // -- SPI -------------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        return new TextEditorManifest(
                "lg3d.java-completion",
                "Java Completion",
                "1.0.0",
                "Dependency-free completion: this file's symbols, imported-type "
                        + "members via reflection, and keywords, in the Completions strip",
                "Project Looking Glass",
                java.util.EnumSet.of(
                        TextEditorPermission.READ,
                        TextEditorPermission.TOOLBAR)
        );
    }

    @Override
    public String category() {
        return "Java/Kotlin";
    }

    @Override
    public void onEditorStarted(EditorContext ctx) {
        this.editor = ctx;
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
        refresh(doc);
    }

    @Override
    public void onDocumentChanged(DocumentContext doc) {
        this.currentDoc = doc;
        refresh(doc);
    }

    @Override
    public void onDocumentSaved(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(new ToolbarContribution("complete-code", "List Completions",
                "Compute completion candidates at the caret and show them in "
                        + "the Completions strip",
                this::completeAction));
    }

    // -- wiring ----------------------------------------------------------------

    private void refresh(DocumentContext doc) {
        EditorContext ctx = editor;
        if (ctx == null || doc == null) {
            return;
        }
        ctx.publishCompletions(doc.getFilePath(), candidatesFor(doc));
    }

    private void completeAction() {
        EditorContext ctx = editor;
        DocumentContext doc = currentDoc;
        if (ctx == null || doc == null) {
            return;
        }
        List<String> candidates = candidatesFor(doc);
        ctx.publishCompletions(doc.getFilePath(), candidates);
        ctx.showMessage(candidates.isEmpty()
                ? "No completions at the caret"
                : candidates.size() + " completion(s) in the Completions strip");
    }

    private static List<String> candidatesFor(DocumentContext doc) {
        return completions(doc.getFullText(), doc.getCaretOffset(), doc.getFileName());
    }

    // -- pure engine (headless-testable) ------------------------------------------

    /**
     * The whole decision: {@code .after-the-dot} member completion, or
     * identifier-prefix completion over this file's symbols, imported-type
     * members and keywords. Kotlin and non-source files complete to nothing.
     */
    static List<String> completions(String source, int caret, String fileName) {
        if (source == null || source.isEmpty() || caret < 0 || caret > source.length()) {
            return List.of();
        }
        if (JavaDiagnosticsTools.detectKind(fileName, source)
                != JavaDiagnosticsTools.Lang.JAVA) {
            return List.of();
        }
        int at = Math.min(caret, source.length());

        // Receiver context: the character before the caret (or before the
        // just-typed partial) is a dot owned by this expression.
        String prefix = prefixAt(source, at);
        int dotIndex = at - prefix.length() - 1;
        if (dotIndex >= 0 && source.charAt(dotIndex) == '.') {
            String receiver = prefixAt(source, dotIndex);
            if (receiver.isEmpty()) {
                return List.of(); // array index or number literal: nothing safe
            }
            List<String> pool = new ArrayList<>();
            pool.addAll(membersOfReceiver(source, receiver));
            return rank("", pool);
        }

        Set<String> pool = new LinkedHashSet<>();
        for (org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol s
                : JavaStructureTools.outlineJava(source, fileName)) {
            String name = s.name().strip().replace("()", "");
            if (!name.isEmpty() && isWordChar(name.charAt(0))) {
                pool.add(name);
            }
        }
        if (prefix.length() >= 2) {
            // imported-type members only once the prefix carries signal
            for (String fqn : importCandidates(source, prefix)) {
                pool.addAll(members(fqn));
            }
        }
        pool.addAll(KEYWORDS);
        return rank(prefix, pool);
    }

    /** @return the identifier run immediately before {@code caret}. */
    static String prefixAt(String source, int caret) {
        int start = Math.min(caret, source.length());
        int i = start;
        while (i > 0 && isWordChar(source.charAt(i - 1))) {
            i--;
        }
        return source.substring(i, start);
    }

    /**
     * @return the public member names (methods + fields) of the declared type
     *         of {@code receiver}: a capitalized receiver is its own type,
     *         otherwise the last {@code Type receiver} declaration wins (a
     *         fully-qualified declaration resolves directly); unresolved
     *         receivers contribute nothing.
     */
    static List<String> membersOfReceiver(String source, String receiver) {
        String typeName;
        if (Character.isUpperCase(receiver.charAt(0))) {
            typeName = receiver;
        } else {
            typeName = declaredTypeOf(source, receiver);
        }
        if (typeName == null) {
            return List.of();
        }
        String fqn = typeName.contains(".")
                ? (loadable(typeName) ? typeName : null)
                : resolveType(source, typeName);
        return (fqn == null) ? List.of() : members(fqn);
    }

    /** Finds {@code Type receiver} (fields, locals, parameters) in the source. */
    static String declaredTypeOf(String source, String receiver) {
        Matcher m = declaration(receiver).matcher(source);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /** @return {@code simple -> fully-qualified name} for the file's imports. */
    static Map<String, String> importsOf(String source) {
        Map<String, String> map = new TreeMap<>();
        for (String line : source.split("\n")) {
            Matcher m = IMPORT.matcher(line);
            if (m.matches()) {
                String fqn = m.group(2);
                if (fqn.endsWith(".*")) {
                    map.put("*", fqn.substring(0, fqn.length() - 2)); // drops the ".*"
                } else {
                    int dot = fqn.lastIndexOf('.');
                    map.put(fqn.substring(dot + 1), fqn);
                }
            }
        }
        return map;
    }

    /**
     * Resolves a simple type name through the imports (exact, then wildcard
     * packages, then {@code java.lang} spellings).
     *
     * @return the FQN when loadable, or null when unresolvable
     */
    static String resolveType(String source, String simpleName) {
        Map<String, String> imports = importsOf(source);
        String direct = imports.get(simpleName);
        if (direct != null) {
            return loadable(direct) ? direct : null;
        }
        String pkg = imports.get("*");
        if (pkg != null) {
            String candidate = pkg + "." + simpleName;
            if (loadable(candidate)) {
                return candidate;
            }
        }
        for (String fallback : JAVA_LANG_FALLBACK) {
            if (fallback.endsWith("." + simpleName) && loadable(fallback)) {
                return fallback;
            }
        }
        if (loadable(simpleName)) { // same-package or fully-qualified already
            return simpleName;
        }
        return null;
    }

    /** FQNs of the imports whose members could match {@code prefix}. */
    private static List<String> importCandidates(String source, String prefix) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : importsOf(source).entrySet()) {
            if (!"*".equals(e.getKey()) && loadable(e.getValue())) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    /** @return public method + field names of {@code fqn}, cached, sorted. */
    static List<String> members(String fqn) {
        return MEMBER_CACHE.computeIfAbsent(fqn, JavaCompletionTools::reflectMembers);
    }

    private static List<String> reflectMembers(String fqn) {
        try {
            Class<?> type = Class.forName(fqn, false,
                    JavaCompletionTools.class.getClassLoader());
            Set<String> names = new LinkedHashSet<>();
            for (Method m : type.getMethods()) {
                names.add(m.getName());
            }
            for (Field f : type.getFields()) {
                names.add(f.getName());
            }
            List<String> out = new ArrayList<>(names);
            java.util.Collections.sort(out);
            return out;
        } catch (Throwable t) {
            // ClassNotFoundException, LinkageError, reflection on odd types:
            // an unresolved name simply contributes nothing.
            return List.of();
        }
    }

    private static boolean loadable(String fqn) {
        try {
            Class.forName(fqn, false, JavaCompletionTools.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Prefix ranking: exact-start matches first (case-sensitive, then
     * insensitive), then contains-matches, alphabetically within each bucket;
     * empty prefix keeps the pool order. Capped at {@link #MAX_CANDIDATES}.
     */
    static List<String> rank(String prefix, Collection<String> pool) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (prefix == null || prefix.isEmpty()) {
            for (String s : pool) {
                if (seen.add(s)) {
                    out.add(s);
                }
            }
        } else {
            String lower = prefix.toLowerCase(java.util.Locale.ROOT);
            for (String s : pool) { // case-sensitive starts first
                if (seen.add(s) && s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            List<String> rest = new ArrayList<>();
            for (String s : pool) {
                if (!out.contains(s) && s.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                    rest.add(s);
                }
            }
            java.util.Collections.sort(rest);
            out.addAll(rest);
            List<String> contains = new ArrayList<>();
            for (String s : pool) {
                if (!out.contains(s)
                        && s.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
                    contains.add(s);
                }
            }
            java.util.Collections.sort(contains);
            out.addAll(contains);
        }
        return (out.size() <= MAX_CANDIDATES) ? out : new ArrayList<>(out.subList(0, MAX_CANDIDATES));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
