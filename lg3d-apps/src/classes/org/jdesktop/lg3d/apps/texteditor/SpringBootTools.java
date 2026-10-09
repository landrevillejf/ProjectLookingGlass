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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Spring Boot Tools" extension: helpers for the configuration
 * idioms a Spring Boot project lives on &mdash; converting between the flat,
 * dot-notation {@code application.properties} and the nested
 * {@code application.yml}, normalising property keys to Spring Boot's canonical
 * kebab-case (relaxed binding), and listing every {@code ${...}} placeholder a
 * document references &mdash; registered through the public
 * {@link TextEditorExtension} SPI under the {@code Spring} category.
 *
 * <p>Both directions are pure {@code String -> String} statics for headless
 * unit tests. The conversions understand comments (preserved), blank lines,
 * {@code key = value} / {@code key: value} pairs, dotted keys, two-space YAML
 * nesting, and YAML block sequences (mapped to the indexed
 * {@code key[0] = value} relaxed-binding form). They are best-effort text
 * transforms, not a YAML/properties parser: exotic constructs (flow maps,
 * multi-line scalars, anchors) pass through under their parent key.</p>
 */
public final class SpringBootTools implements TextEditorExtension {

    /** YAML indent width used by the properties-&gt;YAML renderer. */
    private static final String YAML_INDENT = "  ";

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.spring-boot-tools",
                "Spring Boot Tools",
                "1.0.0",
                "Convert application.properties to application.yml and back,"
                        + " normalise keys to kebab-case, list ${...} placeholders",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Spring";
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("spring-props-to-yaml", "Properties to YAML",
                        "Rewrite this application.properties as nested application.yml (Ctrl+Alt+S)",
                        () -> applyWhole(SpringBootTools::propertiesToYaml), "control alt S"),
                new ToolbarContribution("spring-yaml-to-props", "YAML to Properties",
                        "Flatten this application.yml into dot-notation properties (Ctrl+Alt+V)",
                        () -> applyWhole(SpringBootTools::yamlToProperties), "control alt V"),
                new ToolbarContribution("spring-normalize-keys", "Normalize Keys (Kebab)",
                        "Rewrite every property key in Spring Boot 2+ kebab-case (Ctrl+Alt+8)",
                        () -> applyWhole(SpringBootTools::normalizeKeys), "control alt 8"),
                new ToolbarContribution("spring-list-placeholders", "List ${} Placeholders",
                        "Append a comment block listing every ${...} name referenced (Ctrl+Alt+9)",
                        () -> applyWhole(SpringBootTools::listPlaceholders), "control alt 9")
        );
    }

    /** Runs {@code fn} over the whole document, writing back only when it changes. */
    private void applyWhole(java.util.function.UnaryOperator<String> fn) {
        if (currentDoc == null) {
            return;
        }
        String text = currentDoc.getFullText();
        if (text.isEmpty()) {
            return;
        }
        String replaced = fn.apply(text);
        if (!replaced.equals(text)) {
            currentDoc.setFullText(replaced);
        }
    }

    // ------------------------------------------------------------------
    // Pure transforms (headless-testable)
    // ------------------------------------------------------------------

    /**
     * Converts flat {@code a.b.c = value} properties into nested YAML, building
     * an ordered tree from the dotted keys and rendering it with two-space
     * indentation. Comments (lines starting with {@code #}) and blank lines are
     * preserved at the top level. Pure.
     */
    public static String propertiesToYaml(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = splitLines(text);
        boolean trailingNewline = text.endsWith("\n");

        Map<String, Object> root = new LinkedHashMap<>();
        List<String> output = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                output.add("");
                continue;
            }
            if (line.startsWith("#") || line.startsWith("!")) {
                output.add(line);
                continue;
            }
            int eq = indexOfSeparator(line);
            if (eq < 0) {
                // Not a key/value pair; keep it verbatim so nothing is lost.
                output.add(line);
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (key.isEmpty()) {
                output.add(line);
                continue;
            }
            putPath(root, key.split("\\.", -1), value);
        }

        List<String> rendered = new ArrayList<>();
        renderYaml(root, 0, rendered);

        List<String> merged = new ArrayList<>();
        // Comments/blank lines first, then the tree (the natural header layout).
        for (String o : output) {
            if (o.startsWith("#") || o.startsWith("!") || o.isEmpty()) {
                merged.add(o);
            }
        }
        merged.addAll(rendered);
        return joinLines(merged, trailingNewline);
    }

    /**
     * Flattens nested {@code application.yml} into dot-notation properties. A
     * stack tracks the current key path by indentation; block sequences
     * ({@code - item}) become indexed {@code parent[n]} keys. Comments and blank
     * lines are preserved. Pure.
     */
    public static String yamlToProperties(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = splitLines(text);
        boolean trailingNewline = text.endsWith("\n");

        List<String> out = new ArrayList<>();
        List<Frame> stack = new ArrayList<>();
        Map<String, Integer> seqCounters = new LinkedHashMap<>();

        for (String raw : lines) {
            if (raw.isBlank()) {
                out.add("");
                continue;
            }
            String trimmed = raw.strip();
            if (trimmed.startsWith("#")) {
                out.add(trimmed);
                continue;
            }
            int indent = leadingSpaces(raw);

            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                popTo(stack, indent);
                String parent = pathOf(stack);
                String item = trimmed.equals("-") ? "" : trimmed.substring(2).strip();
                int idx = seqCounters.merge(parent, 1, Integer::sum) - 1;
                String key = parent.isEmpty() ? "[" + idx + "]" : parent + "[" + idx + "]";
                out.add(key + "=" + item);
                continue;
            }

            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                out.add(trimmed); // unparseable; keep it so nothing silently vanishes
                continue;
            }
            String key = trimmed.substring(0, colon).strip();
            String value = trimmed.substring(colon + 1).strip();
            popTo(stack, indent);
            stack.add(new Frame(indent, key));
            if (!value.isEmpty()) {
                out.add(pathOf(stack) + "=" + value);
                stack.remove(stack.size() - 1); // a scalar does not nest children
            }
        }

        // Drop an empty document's stray blank entries only when nothing was parsed.
        return joinLines(out, trailingNewline);
    }

    /**
     * Normalises every Spring Boot {@code key=value} property to the canonical
     * kebab-case form used by Spring Boot 2+ relaxed binding. Underscores,
     * camelCase humps ({@code contextPath} → {@code context-path}) and
     * acronym boundaries ({@code HTTPServer} → {@code http-server}) each become
     * a single hyphen; uppercase folds to lowercase. Values, comments, blank
     * lines and lines with no {@code =} separator are preserved byte-for-byte.
     * Idempotent: normalising an already-normalised document is a no-op. Pure.
     */
    public static String normalizeKeys(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = splitLines(text);
        boolean trailingNewline = text.endsWith("\n");
        List<String> out = new ArrayList<>(lines.size());
        for (String raw : lines) {
            int eq = raw.indexOf('=');
            if (eq <= 0) {
                out.add(raw);
                continue;
            }
            String head = raw.substring(0, eq);
            // Skip indented lines and comment-marker keys so we only touch
            // canonical `key=...` property lines.
            if (Character.isWhitespace(head.charAt(0))) {
                out.add(raw);
                continue;
            }
            String key = head.strip();
            if (key.startsWith("#") || key.startsWith("!")) {
                out.add(raw);
                continue;
            }
            out.add(normalizeKey(key) + raw.substring(eq));
        }
        return joinLines(out, trailingNewline);
    }

    private static String normalizeKey(String key) {
        String[] segs = key.split("\\.", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(normalizeSegment(segs[i]));
        }
        return sb.toString();
    }

    private static String normalizeSegment(String segment) {
        // Split `HTTPServer` -> `HTTP-Server` first so the acronym hump is not
        // treated as one letter, then split `contextPath` -> `context-Path`.
        String s = segment.replaceAll("([A-Z])([A-Z][a-z])", "$1-$2");
        s = s.replaceAll("(?<=[a-z0-9])([A-Z])", "-$1");
        s = s.replace('_', '-');
        s = s.toLowerCase();
        s = s.replaceAll("-+", "-");
        return s.replaceAll("^-+|-+$", "");
    }

    /**
     * Scans every property value for {@code ${...}} placeholder tokens and
     * appends a {@code # Referenced placeholders:} comment block at the end of
     * the document listing them in first-occurrence order (deduplicated;
     * {@code :default} suffixes stripped). If no value uses a placeholder, the
     * document is returned unchanged. The action is idempotent: running it
     * twice is a no-op because the marker comment is detected on re-entry.
     * Nested {@code ${a-${b}}} is followed with a depth counter so the inner
     * token does not close the outer one. Pure.
     */
    public static String listPlaceholders(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<String> lines = splitLines(text);
        boolean trailingNewline = text.endsWith("\n");
        for (String raw : lines) {
            if (raw.startsWith("# Referenced placeholders:")) {
                return text;
            }
        }
        Set<String> placeholders = new LinkedHashSet<>();
        for (String raw : lines) {
            int eq = raw.indexOf('=');
            if (eq < 0) {
                continue;
            }
            collectPlaceholders(raw.substring(eq + 1), placeholders);
        }
        if (placeholders.isEmpty()) {
            return text;
        }
        List<String> out = new ArrayList<>(lines);
        if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
            out.add("");
        }
        out.add("# Referenced placeholders:");
        for (String p : placeholders) {
            out.add("#   " + p);
        }
        return joinLines(out, trailingNewline);
    }

    private static void collectPlaceholders(String value, Set<String> sink) {
        int i = 0;
        while (i < value.length()) {
            int open = value.indexOf("${", i);
            if (open < 0) {
                return;
            }
            int depth = 1;
            int j = open + 2;
            while (j < value.length() && depth > 0) {
                if (j + 1 < value.length()
                        && value.charAt(j) == '$' && value.charAt(j + 1) == '{') {
                    depth++;
                    j += 2;
                    continue;
                }
                if (value.charAt(j) == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                j++;
            }
            if (depth > 0) {
                return; // unterminated `${` — stop cleanly
            }
            String inner = value.substring(open + 2, j);
            int colon = inner.indexOf(':');
            String name = (colon >= 0 ? inner.substring(0, colon) : inner).trim();
            if (!name.isEmpty()) {
                sink.add(name);
            }
            i = j + 1;
        }
    }

    // -- tree helpers ----------------------------------------------------

    /** One indentation-level frame on the YAML key stack. */
    private record Frame(int indent, String segment) { }

    private static void putPath(Map<String, Object> root, String[] segments, String value) {
        Map<String, Object> node = root;
        for (int i = 0; i < segments.length - 1; i++) {
            Object child = node.get(segments[i]);
            if (!(child instanceof Map)) {
                child = new LinkedHashMap<String, Object>();
                node.put(segments[i], child);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) child;
            node = next;
        }
        String leaf = segments[segments.length - 1];
        Object existing = node.get(leaf);
        if (existing instanceof Map) {
            return; // a parent map wins over a same-named scalar
        }
        node.put(leaf, value);
    }

    @SuppressWarnings("unchecked")
    private static void renderYaml(Map<String, Object> node, int depth, List<String> out) {
        String pad = YAML_INDENT.repeat(depth);
        for (Map.Entry<String, Object> e : node.entrySet()) {
            if (e.getValue() instanceof Map) {
                out.add(pad + e.getKey() + ":");
                renderYaml((Map<String, Object>) e.getValue(), depth + 1, out);
            } else {
                out.add(pad + e.getKey() + ": " + e.getValue());
            }
        }
    }

    private static void popTo(List<Frame> stack, int indent) {
        while (!stack.isEmpty() && stack.get(stack.size() - 1).indent() >= indent) {
            stack.remove(stack.size() - 1);
        }
    }

    private static String pathOf(List<Frame> stack) {
        StringBuilder sb = new StringBuilder();
        for (Frame f : stack) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(f.segment());
        }
        return sb.toString();
    }

    /** Index of the first {@code =} or (unquoted) {@code :} that separates key/value. */
    private static int indexOfSeparator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /** Splits into lines, dropping the trailing empty element a final newline yields. */
    private static List<String> splitLines(String text) {
        String body = text.endsWith("\n")
                ? text.substring(0, text.length() - 1) : text;
        return new ArrayList<>(List.of(body.split("\n", -1)));
    }

    private static String joinLines(List<String> lines, boolean trailingNewline) {
        String joined = String.join("\n", lines);
        return trailingNewline ? joined + "\n" : joined;
    }
}
