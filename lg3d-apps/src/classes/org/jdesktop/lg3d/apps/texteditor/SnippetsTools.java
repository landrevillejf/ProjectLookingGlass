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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Insert &amp; Snippets" extension (Espresso Phase 6): one-tap
 * insertion of the small boilerplate people constantly retype &mdash; a UUID, a
 * timestamp in three flavours, an ISO date, a lorem-ipsum line and a random hex
 * colour. Each snippet is a toolbar button (with a distinct glyph, exercising the
 * SPI's new optional {@link ToolbarContribution#getIcon() icon}) and, when the
 * caret is on a {@code ${name}} run, is published as an inline completion so the
 * new {@link CompletionPopup} offers it while the user types.
 *
 * <p><b>Testability.</b> Every value is produced by a pure static taking an
 * {@link Instant} (the no-arg wrappers read the clock), so UUID shape,
 * timestamp formatting, date spelling and the token filter are all unit-testable
 * headless without touching the panel or waiting on a timer.</p>
 */
public final class SnippetsTools implements TextEditorExtension {

    /** The snippet names, in the order offered by completion. */
    static final List<String> NAMES = List.of(
            "uuid", "date", "iso", "rfc", "epoch", "lorem", "color");

    /** A single lorem-ipsum sentence used for the {@code lorem} snippet. */
    static final String LOREM =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, "
                    + "sed do eiusmod tempor incididunt ut labore et dolore "
                    + "magna aliqua.";

    private EditorContext editor;
    private DocumentContext currentDoc;

    /** Public no-arg constructor: required by the {@link java.util.ServiceLoader} SPI. */
    public SnippetsTools() {
    }

    // -- SPI -------------------------------------------------------------------

    @Override
    public TextEditorManifest manifest() {
        return new TextEditorManifest(
                "lg3d.snippets",
                "Insert & Snippets",
                "1.0.0",
                "One-tap UUID, timestamp, date, lorem and hex-colour insertion "
                        + "with inline ${snippet} completion",
                "Project Looking Glass",
                EnumSet.of(
                        TextEditorPermission.READ,
                        TextEditorPermission.WRITE,
                        TextEditorPermission.TOOLBAR)
        );
    }

    @Override
    public String category() {
        return "Insert";
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
        publishFor(doc);
    }

    @Override
    public void onDocumentSaved(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("ins-uuid", "Insert UUID",
                        "Insert a randomly-generated unique identifier",
                        () -> insert("uuid", null), null,
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.HASH)),
                new ToolbarContribution("ins-timestamp", "Insert Timestamp",
                        "Insert the current time in ISO-8601 format",
                        () -> insert("iso", null), null,
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.CLOCK)),
                new ToolbarContribution("ins-date", "Insert Date",
                        "Insert today's date (ISO yyyy-MM-dd)",
                        () -> insert("date", null), null,
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.CLOCK)),
                new ToolbarContribution("ins-lorem", "Insert Lorem Line",
                        "Insert a lorem-ipsum filler sentence",
                        () -> insert("lorem", null), null,
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.LIST)),
                new ToolbarContribution("ins-color", "Insert Hex Colour",
                        "Insert a random #RRGGBB colour",
                        () -> insert("color", null), null,
                        EditorGlyphs.toolbarIcon(EditorGlyphs.Glyph.PALETTE))
        );
    }

    // -- wiring ----------------------------------------------------------------

    private void insert(String name, String ignored) {
        DocumentContext doc = currentDoc;
        if (doc == null) {
            return;
        }
        doc.replaceSelection(expand(name, Instant.now()));
    }

    /**
     * Publishes the {@code ${name}} snippet tokens matching the identifier run
     * just before the caret (when that run starts a known snippet name); an empty
     * publish otherwise clears the offer so unrelated typing is never hijacked.
     */
    private void publishFor(DocumentContext doc) {
        EditorContext ctx = editor;
        if (ctx == null || doc == null) {
            return;
        }
        String prefix = prefixAt(doc.getFullText(), doc.getCaretOffset());
        ctx.publishCompletions(doc.getFilePath(), matching(prefix));
    }

    /** @return the identifier run immediately before {@code caret}. */
    static String prefixAt(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int i = Math.min(caret, text.length());
        int start = i;
        while (i > 0 && (Character.isLetterOrDigit(text.charAt(i - 1))
                || text.charAt(i - 1) == '_')) {
            i--;
        }
        return text.substring(i, start);
    }

    // -- pure engine (headless-testable) ---------------------------------------

    /**
     * @param prefix the identifier run before the caret (may be empty)
     * @return the {@code ${name}} tokens whose name starts with {@code prefix}
     *         (case-insensitive); empty when the prefix carries no snippet signal
     */
    static List<String> matching(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String name : NAMES) {
            if (name.startsWith(p) && name.length() > p.length()) {
                out.add("${" + name + "}");
            }
        }
        return out;
    }

    /** @return the {@code ${name}} token list for every known snippet. */
    static List<String> tokens() {
        List<String> out = new ArrayList<>(NAMES.size());
        for (String name : NAMES) {
            out.add("${" + name + "}");
        }
        return out;
    }

    /**
     * Expands {@code name} to its text, evaluated at {@code now}. Unknown names
     * expand to the empty string so a stale completion never corrupts a document.
     *
     * @param name the snippet name (case-insensitive)
     * @param now  the instant timestamps are read from
     * @return the text to insert
     */
    public static String expand(String name, Instant now) {
        if (name == null) {
            return "";
        }
        Instant t = (now == null) ? Instant.now() : now;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "uuid" -> uuid();
            case "date" -> isoDate(t);
            case "iso" -> isoTimestamp(t);
            case "rfc" -> rfcTimestamp(t);
            case "epoch" -> Long.toString(t.getEpochSecond());
            case "lorem" -> LOREM;
            case "color", "colour" -> hexColor();
            default -> "";
        };
    }

    /** @return a fresh random unique identifier. */
    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** @return {@code now} in UTC as an ISO-8601 instant, e.g. {@code ...Z}. */
    public static String isoTimestamp(Instant now) {
        return DateTimeFormatter.ISO_INSTANT.format(now);
    }

    /** @return {@code now} formatted as an RFC-1123 date-time (UTC). */
    public static String rfcTimestamp(Instant now) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    /** @return {@code now} as an ISO calendar date in UTC ({@code yyyy-MM-dd}). */
    public static String isoDate(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
    }

    /** @return a random {@code #RRGGBB} colour. */
    public static String hexColor() {
        int rgb = ThreadLocalRandom.current().nextInt(0x1000000);
        return String.format(Locale.ROOT, "#%06X", rgb);
    }
}
