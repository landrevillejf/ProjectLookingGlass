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
package org.jdesktop.lg3d.utils.prefs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.logging.Logger;

/**
 * The desktop's file-type -&gt; application associations: which application
 * opens which kind of file (for example, the PDF Viewer opens {@code .pdf}).
 *
 * <p>An association maps a <em>type key</em> to a <em>handler command</em>. The
 * type key is either an extension ({@code ext:pdf}) or a MIME type
 * ({@code mime:application/pdf}); the handler command uses the same vocabulary
 * as the start-menu descriptors, so it may name one of the desktop's own
 * applications ({@code java org.jdesktop.lg3d.apps.pdfviewer.PdfViewer}), a
 * conventional Swing app ({@code swingapp ...}) or an external executable
 * ({@code evince}). A handler may embed the token {@code %f}, expanded to the
 * file being opened; when it is absent the path is appended as a final
 * argument (see {@link #expand(String, Path)}).</p>
 *
 * <p>Values are persisted through {@link LgPreferencesHelper} (i.e.
 * {@code java.util.prefs}), one preference key per association, so a command
 * containing {@code ;} or {@code =} cannot corrupt the store. The instance is a
 * lazily-created singleton ({@link #get()}); reads hit the backing node each
 * time so every caller sees the latest associations without an explicit
 * reload.</p>
 *
 * <p>{@link org.jdesktop.lg3d.utils.system.Opener#open(Path)} consults this
 * model before falling back to {@code xdg-open}, so a configured association
 * takes effect everywhere a file is opened (the file manager, the desktop
 * folders, the dock stacks).</p>
 */
public final class FileAssociations {

    private static final Logger logger = Logger.getLogger("lg.utils");

    /** Preference-key prefix for a stored association. */
    private static final String KEY_PREFIX = "assoc.";

    /** Extension type-key prefix. */
    public static final String EXT_PREFIX = "ext:";

    /** MIME type-key prefix. */
    public static final String MIME_PREFIX = "mime:";

    /** The token in a handler command replaced by the file being opened. */
    public static final String FILE_TOKEN = "%f";

    /**
     * A file type the "Default Applications" panel offers out of the box. The
     * key is the canonical extension key ({@code ext:pdf}); {@code label} is the
     * human name and {@code mime} the MIME type used as a fallback association
     * key and for detection when the OS has no MIME database.
     */
    public static final class Type {
        private final String key;
        private final String label;
        private final String extension;
        private final String mime;

        Type(String extension, String label, String mime) {
            this.extension = extension;
            this.key = extensionKey(extension);
            this.label = label;
            this.mime = mime;
        }

        /** The canonical extension type key ({@code ext:<ext>}). */
        public String key() {
            return key;
        }

        /** The human-readable type name (e.g. "PDF document"). */
        public String label() {
            return label;
        }

        /** The bare, lower-case extension without a dot (e.g. "pdf"). */
        public String extension() {
            return extension;
        }

        /** The MIME type (e.g. "application/pdf"). */
        public String mime() {
            return mime;
        }

        @Override
        public String toString() {
            return label + " (." + extension + ")";
        }
    }

    /** The curated list of common types, in display order. */
    private static final List<Type> COMMON_TYPES;

    /** Extension -&gt; MIME fallback for the common types. */
    private static final Map<String, String> EXT_TO_MIME;

    static {
        List<Type> types = new ArrayList<>();
        // Documents
        types.add(new Type("pdf", "PDF document", "application/pdf"));
        types.add(new Type("txt", "Plain text", "text/plain"));
        types.add(new Type("md", "Markdown document", "text/markdown"));
        types.add(new Type("rtf", "Rich text document", "application/rtf"));
        types.add(new Type("odt", "OpenDocument text", "application/vnd.oasis.opendocument.text"));
        types.add(new Type("doc", "Word document", "application/msword"));
        types.add(new Type("docx", "Word document (OOXML)", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        types.add(new Type("csv", "CSV spreadsheet", "text/csv"));
        types.add(new Type("ods", "OpenDocument spreadsheet", "application/vnd.oasis.opendocument.spreadsheet"));
        types.add(new Type("xls", "Excel spreadsheet", "application/vnd.ms-excel"));
        types.add(new Type("odp", "OpenDocument presentation", "application/vnd.oasis.opendocument.presentation"));
        // Images
        types.add(new Type("png", "PNG image", "image/png"));
        types.add(new Type("jpg", "JPEG image", "image/jpeg"));
        types.add(new Type("jpeg", "JPEG image", "image/jpeg"));
        types.add(new Type("gif", "GIF image", "image/gif"));
        types.add(new Type("bmp", "BMP image", "image/bmp"));
        types.add(new Type("svg", "SVG image", "image/svg+xml"));
        types.add(new Type("webp", "WebP image", "image/webp"));
        // Audio
        types.add(new Type("mp3", "MP3 audio", "audio/mpeg"));
        types.add(new Type("wav", "WAV audio", "audio/x-wav"));
        types.add(new Type("flac", "FLAC audio", "audio/flac"));
        types.add(new Type("ogg", "Ogg audio", "audio/ogg"));
        // Video
        types.add(new Type("mp4", "MP4 video", "video/mp4"));
        types.add(new Type("mkv", "Matroska video", "video/x-matroska"));
        types.add(new Type("avi", "AVI video", "video/x-msvideo"));
        types.add(new Type("webm", "WebM video", "video/webm"));
        // Web / code
        types.add(new Type("html", "HTML page", "text/html"));
        types.add(new Type("htm", "HTML page", "text/html"));
        types.add(new Type("xml", "XML document", "application/xml"));
        types.add(new Type("json", "JSON document", "application/json"));
        types.add(new Type("java", "Java source", "text/x-java-source"));
        types.add(new Type("py", "Python source", "text/x-python"));
        types.add(new Type("sh", "Shell script", "application/x-sh"));
        types.add(new Type("js", "JavaScript source", "text/javascript"));
        // Archives
        types.add(new Type("zip", "ZIP archive", "application/zip"));
        types.add(new Type("tar", "Tape archive", "application/x-tar"));
        types.add(new Type("gz", "Gzip archive", "application/gzip"));
        types.add(new Type("7z", "7-Zip archive", "application/x-7z-compressed"));
        COMMON_TYPES = Collections.unmodifiableList(types);

        Map<String, String> mimes = new LinkedHashMap<>();
        for (Type t : types) {
            mimes.putIfAbsent(t.extension(), t.mime());
        }
        EXT_TO_MIME = Collections.unmodifiableMap(mimes);
    }

    private static volatile FileAssociations instance;

    private final Preferences prefs;

    private FileAssociations() {
        this.prefs = LgPreferencesHelper.userNodeForPackage(FileAssociations.class);
    }

    /** The shared instance. */
    public static FileAssociations get() {
        FileAssociations a = instance;
        if (a == null) {
            synchronized (FileAssociations.class) {
                a = instance;
                if (a == null) {
                    a = new FileAssociations();
                    instance = a;
                }
            }
        }
        return a;
    }

    // ------------------------------------------------------------------
    // Type vocabulary (pure, unit-testable)
    // ------------------------------------------------------------------

    /** The curated common types, in display order. Never null. */
    public static List<Type> commonTypes() {
        return COMMON_TYPES;
    }

    /**
     * The canonical extension type key for an extension or filename: {@code pdf},
     * {@code .pdf} and {@code report.pdf} all yield {@code ext:pdf}. A
     * null/blank input, or one with no usable extension, yields null.
     */
    public static String extensionKey(String extension) {
        if (extension == null) {
            return null;
        }
        String t = extension.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        // Strip any directory portion.
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0) {
            t = t.substring(slash + 1);
        }
        // A leading dot is part of the ".pdf" spelling, not a dotfile marker.
        while (t.startsWith(".")) {
            t = t.substring(1);
        }
        // A remaining interior dot means a filename: keep the final segment.
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        return t.isEmpty() ? null : EXT_PREFIX + t;
    }

    /** The canonical MIME type key: {@code application/pdf} -&gt; {@code mime:application/pdf}. */
    public static String mimeKey(String mime) {
        if (mime == null) {
            return null;
        }
        String m = mime.trim().toLowerCase(Locale.ROOT);
        return m.isEmpty() ? null : MIME_PREFIX + m;
    }

    /**
     * Normalizes any accepted spelling of a type into its canonical key:
     * {@code pdf}, {@code .pdf} and {@code ext:pdf} all yield {@code ext:pdf};
     * {@code application/pdf} and {@code mime:application/pdf} both yield
     * {@code mime:application/pdf}. A null/blank input yields null.
     */
    public static String normalizeTypeKey(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            return null;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith(EXT_PREFIX) || lower.startsWith(MIME_PREFIX)) {
            return lower;
        }
        if (lower.indexOf('/') >= 0) {
            return mimeKey(lower);
        }
        return extensionKey(lower);
    }

    /**
     * The bare, lower-case extension of a filename or path, without the leading
     * dot, or null when there is none (a dotfile such as {@code .bashrc} has no
     * extension). Pure so it can be unit-tested.
     */
    public static String bareExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            // No dot, a leading dot (dotfile) or a trailing dot: no extension.
            return null;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.isEmpty() ? null : ext;
    }

    /**
     * The extension type key of {@code path}, or null when it has none. A dotfile
     * such as {@code .bashrc} has no extension (see {@link #bareExtension}).
     */
    public static String extensionKeyOf(Path path) {
        if (path == null) {
            return null;
        }
        Path name = path.getFileName();
        String ext = bareExtension(name == null ? path.toString() : name.toString());
        return (ext == null) ? null : EXT_PREFIX + ext;
    }

    /**
     * The MIME type of {@code path}: the OS content-type probe first, then the
     * built-in extension fallback (so detection still works on a system with no
     * MIME database, and headless). Null when unknown.
     */
    public static String mimeTypeOf(Path path) {
        if (path == null) {
            return null;
        }
        try {
            String probed = Files.probeContentType(path);
            if (probed != null && !probed.isBlank()) {
                return probed.toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            // probeContentType is best-effort; fall through to the extension map.
            logger.fine("MIME probe failed for " + path + ": " + e);
        }
        String ext = bareExtension(path.getFileName() == null
                ? path.toString() : path.getFileName().toString());
        return (ext == null) ? null : EXT_TO_MIME.get(ext);
    }

    /**
     * Expands a handler command into an argv vector for {@code path}. Each
     * whitespace-separated token containing {@code %f} has it replaced by the
     * absolute path (as its own argument, so spaces are safe); when no token
     * contains {@code %f} the absolute path is appended as a final argument. A
     * null/blank command yields an empty vector. Pure so it can be unit-tested.
     */
    public static List<String> expand(String command, Path path) {
        List<String> argv = new ArrayList<>();
        if (command == null || command.trim().isEmpty()) {
            return argv;
        }
        String abs = (path == null) ? "" : path.toAbsolutePath().toString();
        boolean substituted = false;
        for (String token : command.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.contains(FILE_TOKEN)) {
                argv.add(token.replace(FILE_TOKEN, abs));
                substituted = true;
            } else {
                argv.add(token);
            }
        }
        if (!substituted && path != null) {
            argv.add(abs);
        }
        return argv;
    }

    // ------------------------------------------------------------------
    // Persisted associations
    // ------------------------------------------------------------------

    /**
     * The handler command for {@code path}: the extension association first,
     * then the MIME association, else null (no explicit association, so the
     * caller falls back to the system default).
     */
    public String handlerFor(Path path) {
        String extKey = extensionKeyOf(path);
        if (extKey != null) {
            String h = handlerForType(extKey);
            if (h != null) {
                return h;
            }
        }
        String mime = mimeTypeOf(path);
        if (mime != null) {
            String h = handlerForType(mimeKey(mime));
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    /** The handler command stored for a canonical type key, or null. */
    public String handlerForType(String typeKey) {
        String key = normalizeTypeKey(typeKey);
        if (key == null) {
            return null;
        }
        String value = prefs.get(KEY_PREFIX + key, null);
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Sets (or, when {@code command} is null/blank, removes) the handler for a
     * type key and flushes the change so every caller sees it immediately.
     */
    public void setHandler(String typeKey, String command) {
        String key = normalizeTypeKey(typeKey);
        if (key == null) {
            return;
        }
        if (command == null || command.isBlank()) {
            removeHandler(key);
            return;
        }
        prefs.put(KEY_PREFIX + key, command.trim());
        flush();
    }

    /** Removes the handler for a type key and flushes the change. */
    public void removeHandler(String typeKey) {
        String key = normalizeTypeKey(typeKey);
        if (key == null) {
            return;
        }
        prefs.remove(KEY_PREFIX + key);
        flush();
    }

    /**
     * Every stored association as canonical-type-key -&gt; command, in a stable
     * (sorted) order. Never null.
     */
    public Map<String, String> handlers() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String k : prefs.keys()) {
                if (k.startsWith(KEY_PREFIX)) {
                    String value = prefs.get(k, null);
                    if (value != null && !value.isBlank()) {
                        out.put(k.substring(KEY_PREFIX.length()), value);
                    }
                }
            }
        } catch (BackingStoreException e) {
            logger.warning("Could not enumerate file associations: " + e);
        }
        List<String> keys = new ArrayList<>(out.keySet());
        Collections.sort(keys);
        Map<String, String> sorted = new LinkedHashMap<>();
        for (String k : keys) {
            sorted.put(k, out.get(k));
        }
        return sorted;
    }

    /** Removes every stored association. */
    public void clear() {
        try {
            for (String k : prefs.keys()) {
                if (k.startsWith(KEY_PREFIX)) {
                    prefs.remove(k);
                }
            }
        } catch (BackingStoreException e) {
            logger.warning("Could not clear file associations: " + e);
        }
        flush();
    }

    private void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            logger.warning("Failed to persist file associations: " + e);
        }
    }

    /**
     * The human label for a canonical type key: the curated common-type name
     * when known, else a tidied form of the key itself ({@code ext:foo} -&gt;
     * "foo", {@code mime:application/pdf} -&gt; "application/pdf"). Pure.
     */
    public static String labelFor(String typeKey) {
        String key = normalizeTypeKey(typeKey);
        if (key == null) {
            return "";
        }
        for (Type t : COMMON_TYPES) {
            if (t.key().equals(key)) {
                return t.label();
            }
        }
        if (key.startsWith(EXT_PREFIX)) {
            return key.substring(EXT_PREFIX.length());
        }
        if (key.startsWith(MIME_PREFIX)) {
            return key.substring(MIME_PREFIX.length());
        }
        return key;
    }
}
