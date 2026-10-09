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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Base64 Tools" extension: encode / decode the selection as
 * Base64, registered through the public {@link TextEditorExtension} SPI under
 * the {@code Encoding} category. Like every bundled transform it is a pure
 * {@code String -> String} function exposed as a static for headless unit
 * tests, never dirties the document on a no-op, and depends on neither Swing
 * nor the panel internals.
 */
public final class Base64Tools implements TextEditorExtension {

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.base64-tools",
                "Base64 Tools",
                "1.0.0",
                "Encode and decode the selection as Base64",
                "Project Looking Glass",
                perms
        );
    }

    @Override
    public String category() {
        return "Encoding";
    }

    @Override
    public void onDocumentOpened(DocumentContext doc) {
        this.currentDoc = doc;
    }

    @Override
    public List<ToolbarContribution> toolbarContributions() {
        return List.of(
                new ToolbarContribution("base64-encode", "Encode Base64",
                        "Base64-encode the selection (Ctrl+Alt+Q)",
                        () -> applyToSelection(Base64Tools::encode), "control alt Q"),
                new ToolbarContribution("base64-decode", "Decode Base64",
                        "Base64-decode the selection (leaves malformed input as-is) (Ctrl+Alt+G)",
                        () -> applyToSelection(Base64Tools::decode), "control alt G")
        );
    }

    /** Runs {@code fn} over the selection, replacing it only when it changes. */
    private void applyToSelection(UnaryOperator<String> fn) {
        if (currentDoc == null) {
            return;
        }
        String selection = currentDoc.getSelectedText();
        if (selection.isEmpty()) {
            return;
        }
        String converted = fn.apply(selection);
        if (!converted.equals(selection)) {
            currentDoc.replaceSelection(converted);
        }
    }

    // ------------------------------------------------------------------
    // Pure transforms (headless-testable)
    // ------------------------------------------------------------------

    /**
     * Base64-encodes {@code text} as UTF-8 (basic alphabet, no line breaks).
     * Null or empty input is returned unchanged. Pure.
     */
    public static String encode(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Base64.getEncoder()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Base64-decodes {@code text} (MIME decoder, so embedded line breaks are
     * tolerated) back to a UTF-8 string. Malformed input is returned unchanged
     * rather than throwing. Null or empty input is returned unchanged. Pure.
     */
    public static String decode(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            return new String(Base64.getMimeDecoder().decode(text),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
