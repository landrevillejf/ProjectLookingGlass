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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Spring Boot Tools" extension: converts between the two
 * {@code application} configuration formats a Spring Boot project uses &mdash;
 * the flat, dot-notation {@code application.properties} and the nested
 * {@code application.yml} &mdash; registered through the public
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
                "Convert application.properties to application.yml and back",
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
                        () -> applyWhole(SpringBootTools::yamlToProperties), "control alt V")
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
