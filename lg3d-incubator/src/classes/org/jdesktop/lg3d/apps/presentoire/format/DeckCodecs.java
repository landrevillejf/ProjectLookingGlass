/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.presentoire.format;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.w3c.dom.Document;

/**
 * The one entry point the panel and the 3D app use to open and save a slide
 * deck, whatever its format.
 *
 * <p>{@link #open(File)} sniffs a file's real format from its magic bytes - not
 * its extension, which is so often wrong - and delegates to the matching codec;
 * {@link #export(PresentoireModel, File, DeckFormat)} does the reverse. Both
 * desktops call these, so an imported PowerPoint or LibreOffice deck reaches the
 * very same AWT-free {@link PresentoireModel} a native {@code .zip} does and is
 * rendered identically by the 3D scene graph and the 2D panel.</p>
 *
 * <p>Sniffing order, cheapest and most specific first: an OLE2 compound file
 * ({@code D0CF11E0A1B11AE1}) is a legacy {@code .ppt}; a ZIP ({@code PK\x03\x04})
 * is then told apart by its contents - {@code ppt/presentation.xml} is a
 * {@code .pptx}, a {@code mimetype} entry naming the OpenDocument presentation
 * media type is an {@code .odp}, and a {@code content.xml} with a
 * {@code <document>} root is Presentoire's own. A ZIP matching none of those, or
 * a file that is neither OLE2 nor ZIP, falls back to the extension; if that too
 * names nothing, {@link #open} reports an {@link IOException} rather than
 * guessing, so the caller can keep the deck it already has open.</p>
 */
public final class DeckCodecs {

    /** The OpenDocument presentation media type an {@code .odp} mimetype entry carries. */
    static final String ODP_MIMETYPE = "application/vnd.oasis.opendocument.presentation";

    /** The OLE2 compound-document magic that opens a legacy {@code .ppt}. */
    private static final byte[] OLE2_MAGIC = {
            (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1
    };

    /** The local-file-header magic that opens every ZIP-based format. */
    private static final byte[] ZIP_MAGIC = {
            (byte) 0x50, (byte) 0x4B, (byte) 0x03, (byte) 0x04
    };

    private DeckCodecs() {
        // no instances
    }

    /**
     * Opens a deck in any supported format, sniffing which one it is.
     *
     * @param file the deck to read
     * @return the parsed model; a ZIP- or image-backed model the caller must
     *         {@link PresentoireModel#close() close} when done
     * @throws IOException if the file is not a readable, recognised deck
     */
    public static PresentoireModel open(File file) throws IOException {
        return read(file, sniff(file));
    }

    /**
     * Opens a deck, forcing the format instead of sniffing it. Handy when the
     * caller already knows the format (a test, or a Save As round trip).
     *
     * @param file   the deck to read
     * @param format the format to read it as
     * @return the parsed model
     * @throws IOException if the file cannot be read as that format
     */
    public static PresentoireModel open(File file, DeckFormat format) throws IOException {
        return read(file, format);
    }

    /**
     * Opens several Markdown files as one deck, one slide per file, ordered by
     * file name. This is the multi-file half of Markdown import; a single file is
     * opened with {@link #open(File)}, which splits it on {@code ---} and
     * top-level headings instead. Every file must be Markdown.
     *
     * @param files the {@code .md} files to read
     * @return the parsed model
     * @throws IOException if the set is empty, a file is not Markdown, or one
     *         cannot be read
     */
    public static PresentoireModel openMarkdown(File[] files) throws IOException {
        return MarkdownDeckCodec.readAll(files);
    }

    /**
     * Saves a model to a file in the given format.
     *
     * @param model  the deck to write; not closed by this call
     * @param target the file to create or overwrite
     * @param format the format to write
     * @throws IOException if the model cannot be written as that format
     */
    public static void export(PresentoireModel model, File target, DeckFormat format)
            throws IOException {
        switch (format) {
            case NATIVE:
                NativeDeckCodec.write(model, target);
                break;
            case PPTX:
                PptxDeckCodec.write(model, target);
                break;
            case PPT:
                PptDeckCodec.write(model, target);
                break;
            case ODP:
                OdpDeckCodec.write(model, target);
                break;
            case MARKDOWN:
                MarkdownDeckCodec.write(model, target);
                break;
            default:
                throw new IOException("Unsupported export format: " + format);
        }
    }

    /**
     * Detects a file's deck format from its contents, falling back to its
     * extension.
     *
     * @param file the file to inspect
     * @return the detected format
     * @throws IOException if the file cannot be read or matches no known format
     */
    public static DeckFormat sniff(File file) throws IOException {
        byte[] header = new byte[8];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.readNBytes(header, 0, header.length);
        }
        if (read >= OLE2_MAGIC.length && startsWith(header, OLE2_MAGIC)) {
            return DeckFormat.PPT;
        }
        if (read >= ZIP_MAGIC.length && startsWith(header, ZIP_MAGIC)) {
            DeckFormat zipped = sniffZip(file);
            if (zipped != null) {
                return zipped;
            }
        }
        DeckFormat byExtension = DeckFormat.fromExtension(file.getName());
        if (byExtension != null) {
            return byExtension;
        }
        throw new IOException("Not a recognised slide deck: " + file.getName());
    }

    /**
     * Resolves the format a Save As dialog should default to for a target file:
     * its extension if that names a format, else {@link DeckFormat#NATIVE}.
     *
     * @param file the intended target
     * @return the format to write, never null
     */
    public static DeckFormat formatFor(File file) {
        DeckFormat byExtension = DeckFormat.fromExtension(file.getName());
        return (byExtension == null) ? DeckFormat.NATIVE : byExtension;
    }

    private static PresentoireModel read(File file, DeckFormat format) throws IOException {
        switch (format) {
            case NATIVE:
                return NativeDeckCodec.read(file);
            case PPTX:
                return PptxDeckCodec.read(file);
            case PPT:
                return PptDeckCodec.read(file);
            case ODP:
                return OdpDeckCodec.read(file);
            case MARKDOWN:
                return MarkdownDeckCodec.read(file);
            default:
                throw new IOException("Unsupported import format: " + format);
        }
    }

    /** Inspects a ZIP's entries; returns null when it is not a deck we know. */
    private static DeckFormat sniffZip(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry("ppt/presentation.xml") != null) {
                return DeckFormat.PPTX;
            }
            ZipEntry mimetype = zip.getEntry("mimetype");
            if (mimetype != null && isOdpMimetype(zip, mimetype)) {
                return DeckFormat.ODP;
            }
            ZipEntry content = zip.getEntry(PresentoireModel.CONTENT_ENTRY);
            if (content != null && hasDocumentRoot(zip, content)) {
                return DeckFormat.NATIVE;
            }
        } catch (RuntimeException e) {
            // a corrupt or hostile ZIP is simply "not a deck we recognise"
            return null;
        }
        return null;
    }

    private static boolean isOdpMimetype(ZipFile zip, ZipEntry mimetype) {
        try (InputStream in = zip.getInputStream(mimetype)) {
            String type = new String(in.readNBytes(256), StandardCharsets.UTF_8).trim();
            return ODP_MIMETYPE.equals(type);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean hasDocumentRoot(ZipFile zip, ZipEntry content) {
        try (InputStream in = zip.getInputStream(content)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            try {
                factory.setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // parser without this hardening flag; the root check still applies
            }
            Document document = factory.newDocumentBuilder().parse(in);
            return document.getDocumentElement() != null
                    && "document".equals(document.getDocumentElement().getNodeName());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
