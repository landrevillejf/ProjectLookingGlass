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
package org.jdesktop.lg3d.utils.system;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A bounded, read-only text-file view for the LFS/BLFS system managers. Several
 * front-ends (the tor / dnscrypt-proxy privacy views, the Servers panel's config
 * view) need to <em>show</em> a root-owned configuration or log file without
 * ever writing it and without letting a large file exhaust memory. This utility
 * is the single implementation of that read: it never spawns a process, never
 * writes, and caps the result to the trailing {@value #DEFAULT_MAX_BYTES} bytes.
 *
 * <p>It is the read half of {@code system-management-contract.md} §3.5: reading
 * a tool's state files read-only is fine for fast display, but any write MUST go
 * through the owning CLI - so this class exposes no write path at all. It never
 * throws: a missing path yields {@link Content#isExists() exists=false}, an
 * unreadable one (a root-only log, say) yields {@link Content#isReadable()
 * readable=false}, and a read error is reported the same honest way rather than
 * as a fake empty view.</p>
 *
 * <p>The logic is pure w.r.t. the process model and unit-testable against a
 * {@code TempDir} ({@link #read(String, int)}, {@link Content#getLineCount()},
 * {@link Content#describe()}).</p>
 */
public final class ConfigFileReader {

    /** Default trailing-byte cap on a read-only view (bounds memory for logs). */
    public static final int DEFAULT_MAX_BYTES = 256 * 1024;

    private ConfigFileReader() {
        // no instances
    }

    /**
     * Reads a text file read-only, bounded to the trailing
     * {@value #DEFAULT_MAX_BYTES} bytes. Never throws.
     *
     * @param path the absolute path to read (null/blank yields not-present)
     * @return an immutable {@link Content}; never null
     */
    public static Content read(String path) {
        return read(path, DEFAULT_MAX_BYTES);
    }

    /**
     * Reads a text file read-only, bounded to the trailing {@code maxBytes}
     * bytes so a large log cannot exhaust memory. Pure w.r.t. the process model
     * (spawns nothing) and never throws: a missing path yields
     * {@code exists=false}, an unreadable one yields {@code readable=false}, and
     * a read error is reported the same way. The GUI never writes these files
     * (§3.5 / §6).
     *
     * @param path     the absolute path to read (null/blank yields not-present)
     * @param maxBytes the trailing-byte cap (a value &lt;= 0 reads nothing)
     * @return an immutable {@link Content}; never null
     */
    public static Content read(String path, int maxBytes) {
        if (path == null || path.isBlank()) {
            return new Content(false, false, "", false, maxBytes);
        }
        Path p;
        try {
            p = Path.of(path);
        } catch (RuntimeException e) {
            return new Content(false, false, "", false, maxBytes);
        }
        try {
            if (!Files.exists(p)) {
                return new Content(false, false, "", false, maxBytes);
            }
            if (!Files.isReadable(p)) {
                return new Content(true, false, "", false, maxBytes);
            }
            long size = Files.size(p);
            if (size <= maxBytes) {
                return new Content(true, true,
                        Files.readString(p, StandardCharsets.UTF_8), false, maxBytes);
            }
            return new Content(true, true, readTail(p, maxBytes), true, maxBytes);
        } catch (IOException | RuntimeException e) {
            // A directory, a vanishing file, an I/O error: honest "unreadable".
            return new Content(true, false, "", false, maxBytes);
        }
    }

    private static String readTail(Path p, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long len = raf.length();
            long start = Math.max(0L, len - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) Math.min((long) maxBytes, len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    /**
     * The result of a read-only file view: whether the file exists, whether the
     * GUI could read it, its (bounded) text and whether that text was truncated
     * to the trailing window.
     */
    public static final class Content {
        private final boolean exists;
        private final boolean readable;
        private final String text;
        private final boolean truncated;
        private final int maxBytes;

        Content(boolean exists, boolean readable, String text, boolean truncated, int maxBytes) {
            this.exists = exists;
            this.readable = readable;
            this.text = (text == null) ? "" : text;
            this.truncated = truncated;
            this.maxBytes = maxBytes;
        }

        /** True if the file exists on disk. */
        public boolean isExists() {
            return exists;
        }

        /** True if the GUI could read the file (exists and permitted). */
        public boolean isReadable() {
            return readable;
        }

        /** The (bounded) file text; empty when unreadable or missing. */
        public String getText() {
            return text;
        }

        /** True when the text is only the trailing window of a larger file. */
        public boolean isTruncated() {
            return truncated;
        }

        /** The number of lines in {@link #getText()} (0 when empty). */
        public int getLineCount() {
            if (text.isEmpty()) {
                return 0;
            }
            int count = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    count++;
                }
            }
            return text.endsWith("\n") ? count : count + 1;
        }

        /** A one-line label for a viewer header; never null. */
        public String describe() {
            if (!exists) {
                return "not present";
            }
            if (!readable) {
                return "present, but not readable without privileges";
            }
            if (truncated) {
                return "showing the last " + (maxBytes / 1024) + " KiB of a larger file";
            }
            return getLineCount() + (getLineCount() == 1 ? " line" : " lines");
        }
    }
}
