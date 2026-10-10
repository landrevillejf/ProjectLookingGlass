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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "TODO &amp; Task scan" extension (Espresso Phase 6): sweeps the
 * current document for {@code TODO} / {@code FIXME} / {@code NOTE} / {@code HACK}
 * comment markers, reports them as info-level diagnostics (gutter markers plus
 * the Problems panel) and lets the user step between them with a "next" jump.
 *
 * <p><b>Non-mutating.</b> The scan only reads; it never edits the document. It is
 * gated on {@link TextEditorPermission#DIAGNOSE} (for reporting) and
 * {@link TextEditorPermission#READ} (for the document text and the go-to jump),
 * and carries no {@code WRITE}.</p>
 *
 * <p><b>Testability.</b> The whole scanner is a pure {@code String -> List}
 * function, and the "next" rotation is a pure index helper, so both are unit
 * tested headless without a panel; the extension body only wires them to the
 * {@link EditorContext} capability facade.</p>
 */
public final class TodoScanTools implements TextEditorExtension {

    /** The recognised task tags; matched case-insensitively at a word start. */
    static final List<String> TAGS = List.of("TODO", "FIXME", "NOTE", "HACK");

    /** {@code TAG[: ] rest-of-line}, matched case-insensitively after a comment cue. */
    private static final Pattern MARKER = Pattern.compile(
            "(?i)\\b(TODO|FIXME|NOTE|HACK)\\b[:\\s]*(.*)");

    private EditorContext editor;
    private DocumentContext currentDoc;

    /** The most recent scan, so "next" rotates through it without re-scanning. */
    private List<Item> lastItems = List.of();
    private int cursor = -1;

    /** Public no-arg constructor: required by the {@link java.util.ServiceLoader} SPI. */
    public TodoScanTools() {
    }

    /**
     * One task marker found in the document.
     *
     * @param line the 1-based line it appears on
     * @param tag  the matched tag, upper-case
     * @param text the trimmed remainder of the line after the tag
     */
    public record Item(int line, String tag, String text) { }

    // -- SPI -------------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        return new TextEditorManifest(
                "lg3d.todo",
                "TODO & Task Scan",
                "1.0.0",
                "Find TODO/FIXME/NOTE/HACK markers, list them and jump between them",
                "Project Looking Glass",
                EnumSet.of(
                        TextEditorPermission.READ,
                        TextEditorPermission.DIAGNOSE,
                        TextEditorPermission.TOOLBAR)
        );
    }

    @Override
    public String category() {
        return "Project";
    }

    @Override
    public void onEditorStarted(EditorContext ctx) {
        this.editor = ctx;
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public void onDocumentChanged(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("scan-todo", "Scan TODOs",
                        "Find every task marker in this document",
                        this::scan, null,
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.LIST)),
                new ToolbarContribution("next-todo", "Next TODO",
                        "Jump to the next task marker",
                        this::next, "control alt D",
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.PLAY))
        );
    }

    // -- wiring ----------------------------------------------------------------

    private void scan() {
        EditorContext ctx = editor;
        DocumentContext doc = currentDoc;
        if (ctx == null || doc == null) {
            return;
        }
        lastItems = scanMarkers(doc.getFullText());
        cursor = -1;
        List<Diagnostic> diagnostics = new ArrayList<>(lastItems.size());
        for (Item it : lastItems) {
            diagnostics.add(Diagnostic.of(doc.getFilePath(), it.line(), 1,
                    Diagnostic.Kind.INFO, it.tag()
                    + (it.text().isEmpty() ? "" : ": " + it.text())));
        }
        ctx.reportDiagnostics(doc.getFilePath(), diagnostics);
        ctx.showMessage(lastItems.isEmpty()
                ? "No TODO/FIXME/NOTE/HACK markers"
                : lastItems.size() + " task marker(s) found");
    }

    private void next() {
        EditorContext ctx = editor;
        DocumentContext doc = currentDoc;
        if (ctx == null || doc == null) {
            return;
        }
        if (lastItems.isEmpty()) {
            lastItems = scanMarkers(doc.getFullText());
        }
        int target = nextLine(lastItems, doc.getCaretLine());
        if (target <= 0) {
            ctx.showMessage("No task markers to jump to");
            return;
        }
        ctx.navigateTo(doc.getFilePath(), target);
    }

    // -- pure engine (headless-testable) ---------------------------------------

    /**
     * Scans {@code text} line by line for task markers.
     *
     * @param text the document text (may be null/empty)
     * @return the markers found, in file order; empty when there are none
     */
    public static List<Item> scanMarkers(String text) {
        List<Item> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = MARKER.matcher(lines[i]);
            if (m.find()) {
                out.add(new Item(i + 1, m.group(1).toUpperCase(Locale.ROOT),
                        m.group(2).trim()));
            }
        }
        return out;
    }

    /**
     * @param items      the scan result (file order)
     * @param currentLine the 1-based caret line
     * @return the line of the first marker strictly after {@code currentLine},
     *         wrapping to the first marker when past the last; {@code 0} when the
     *         list is empty
     */
    public static int nextLine(List<Item> items, int currentLine) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        for (Item it : items) {
            if (it.line() > currentLine) {
                return it.line();
            }
        }
        return items.get(0).line();
    }
}
