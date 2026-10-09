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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * The bundled "Hash Tools" extension: replace the selection with its lowercase
 * hexadecimal MD5, SHA-1 or SHA-256 digest, registered through the public
 * {@link TextEditorExtension} SPI under the {@code Encoding} category. Handy
 * for checksums, cache keys and Spring {@code @ConditionalOnProperty} hashing
 * while editing. Each helper is a pure {@code String -> String} static for
 * headless unit tests; like every bundled transform nothing dirties the
 * document on a no-op and there is no dependency on Swing.
 */
public final class HashTools implements TextEditorExtension {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DocumentContext currentDoc;

    @Override
    public TextEditorManifest manifest() {
        Set<TextEditorPermission> perms = EnumSet.of(
                TextEditorPermission.READ,
                TextEditorPermission.WRITE,
                TextEditorPermission.TOOLBAR
        );
        return new TextEditorManifest(
                "lg3d.hash-tools",
                "Hash Tools",
                "1.0.0",
                "Hash the selection to MD5, SHA-1 or SHA-256 hex",
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
                new ToolbarContribution("hash-md5", "MD5 (Hex)",
                        "Replace the selection with its MD5 hex digest (Ctrl+Alt+5)",
                        () -> applyToSelection(HashTools::md5Hex), "control alt 5"),
                new ToolbarContribution("hash-sha1", "SHA-1 (Hex)",
                        "Replace the selection with its SHA-1 hex digest (Ctrl+Alt+6)",
                        () -> applyToSelection(HashTools::sha1Hex), "control alt 6"),
                new ToolbarContribution("hash-sha256", "SHA-256 (Hex)",
                        "Replace the selection with its SHA-256 hex digest (Ctrl+Alt+7)",
                        () -> applyToSelection(HashTools::sha256Hex), "control alt 7")
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

    /** Lowercase hex MD5 of {@code text} (UTF-8). Null/empty input unchanged. Pure. */
    public static String md5Hex(String text) {
        return digestHex("MD5", text);
    }

    /** Lowercase hex SHA-1 of {@code text} (UTF-8). Null/empty input unchanged. Pure. */
    public static String sha1Hex(String text) {
        return digestHex("SHA-1", text);
    }

    /** Lowercase hex SHA-256 of {@code text} (UTF-8). Null/empty input unchanged. Pure. */
    public static String sha256Hex(String text) {
        return digestHex("SHA-256", text);
    }

    private static String digestHex(String algorithm, String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return toHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships MD5/SHA-1/SHA-256; leave the text untouched if not.
            return text;
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
