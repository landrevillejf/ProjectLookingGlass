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
package org.jdesktop.lg3d.ftpclient.net;

/**
 * Pure helpers for the {@code /}-separated absolute paths both FTP and SFTP use,
 * independent of the local platform's file separator. Keeping them here (rather
 * than inline in each adapter) means the parent/basename/join logic is written
 * once and unit-tested once.
 */
public final class RemotePaths {

    /** The remote path separator (both FTP and SFTP use {@code /}). */
    public static final String SEPARATOR = "/";

    private RemotePaths() {
    }

    /** @return {@code true} when the path starts with {@code /}. */
    public static boolean isAbsolute(String path) {
        return path != null && path.startsWith(SEPARATOR);
    }

    /**
     * Normalizes a remote path: forces a leading {@code /}, collapses duplicate
     * separators and strips a trailing {@code /} (except on the root).
     *
     * @param path the raw path
     * @return the normalized absolute path; {@code "/"} for a null/blank input
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return SEPARATOR;
        }
        String p = path.trim();
        if (!p.startsWith(SEPARATOR)) {
            p = SEPARATOR + p;
        }
        // Collapse runs of separators.
        StringBuilder sb = new StringBuilder(p.length());
        boolean prevSlash = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '/') {
                if (!prevSlash) {
                    sb.append('/');
                }
                prevSlash = true;
            } else {
                sb.append(c);
                prevSlash = false;
            }
        }
        String out = sb.toString();
        if (out.length() > 1 && out.endsWith(SEPARATOR)) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isEmpty() ? SEPARATOR : out;
    }

    /**
     * The base name of a path (the last segment).
     *
     * @param path the remote path
     * @return the trailing segment, or {@code ""} for the root
     */
    public static String baseName(String path) {
        String p = normalize(path);
        if (p.equals(SEPARATOR)) {
            return "";
        }
        int idx = p.lastIndexOf('/');
        return p.substring(idx + 1);
    }

    /**
     * The parent directory of a path.
     *
     * @param path the remote path
     * @return the parent, or {@code "/"} when the path is at the root
     */
    public static String parent(String path) {
        String p = normalize(path);
        if (p.equals(SEPARATOR)) {
            return SEPARATOR;
        }
        int idx = p.lastIndexOf('/');
        return (idx <= 0) ? SEPARATOR : p.substring(0, idx);
    }

    /**
     * Joins a directory and a child name with a single separator.
     *
     * @param dir  the directory (may be null/blank for the root)
     * @param name the child name (a leading separator is stripped)
     * @return the joined absolute path
     */
    public static String join(String dir, String name) {
        String child = (name == null) ? "" : name.trim();
        while (child.startsWith(SEPARATOR)) {
            child = child.substring(1);
        }
        String d = normalize(dir);
        if (child.isEmpty()) {
            return d;
        }
        return d.equals(SEPARATOR) ? SEPARATOR + child : d + SEPARATOR + child;
    }
}
