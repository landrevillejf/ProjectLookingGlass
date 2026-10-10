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
package org.jdesktop.lg3d.mandela.rt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.jdesktop.lg3d.mandela.rt.Capabilities.Grant;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaList;

/**
 * The file gateway {@code std.fs} talks through.
 *
 * <p>Two reasons this is a class of its own rather than a handful of calls inside
 * {@link StandardLibrary}. First, the library must stay usable on a host with no
 * files at all &mdash; a Web Browser page gets {@code null} here and every
 * {@code fs} entry refuses before touching a path. Second, every operation needs
 * the same three steps in the same order, and folding them into each method body
 * is exactly how a check gets dropped on the floor:</p>
 *
 * <ol>
 *   <li>assert the capability ({@link Grant#FILE_READ} or
 *       {@link Grant#FILE_WRITE}) for the direction the call moves data;</li>
 *   <li>resolve the script's path through {@link Capabilities#resolve}, which is
 *       what confines {@code "../"} and absolute paths to the granted root;</li>
 *   <li>do the work, converting any {@link IOException} into a script-catchable
 *       {@code IOError} so a missing file is a value a {@code try} can handle
 *       rather than a Java stack trace escaping the interpreter.</li>
 * </ol>
 *
 * <p>Text is always UTF-8. There is no charset argument on purpose: a script that
 * needs to move bytes should get a byte-oriented API from its host, and silently
 * guessing a platform encoding is how data gets corrupted in a way nobody notices
 * for a year. Files are read whole &mdash; the desktop use case is a config file
 * or a note, not a 4&nbsp;GB video &mdash; and a host that needs a size cap should
 * pass one to {@link #Files(java.nio.file.Path, long)}.</p>
 *
 * <p>Path names are a deliberate Java-21 spelling of {@code java.nio.file.Files};
 * the JDK class is always written out in full inside this file to keep the two
 * apart.</p>
 */
public final class Files {

    /** The directory the host rooted this gateway at, or null when unconfined. */
    private final java.nio.file.Path root;

    /** Largest file {@link #read} will return, in bytes; &le;0 means unbounded. */
    private final long maxReadBytes;

    /**
     * Builds a gateway over a root directory.
     *
     * @param root the directory absolute paths are relative to; may be null to
     *             mean "whatever the process can see", which only makes sense
     *             together with capabilities that already confine the run
     */
    public Files(java.nio.file.Path root) {
        this(root, 0L);
    }

    /**
     * Builds a gateway with a read cap.
     *
     * @param root          the rooted directory, or null
     * @param maxReadBytes  the largest file {@link #read} returns, or &le;0 for
     *                      no limit
     */
    public Files(java.nio.file.Path root, long maxReadBytes) {
        this.root = (root == null) ? null : root.toAbsolutePath().normalize();
        this.maxReadBytes = maxReadBytes;
    }

    /** @return the rooted directory, or null when unconfined */
    public java.nio.file.Path root() {
        return root;
    }

    /**
     * A gateway rooted at the current directory, the shape a CLI uses.
     *
     * @return a new gateway
     */
    public static Files local() {
        return new Files(java.nio.file.Path.of("").toAbsolutePath());
    }

    // -- reads ---------------------------------------------------------------

    /**
     * Reads a whole file as UTF-8 text.
     *
     * @param caps the run's capabilities
     * @param path the script-supplied path
     * @return the text
     */
    public String read(Capabilities caps, String path) {
        caps.require(Grant.FILE_READ, "read a file");
        java.nio.file.Path at = caps.resolve(path, "read");
        try {
            if (maxReadBytes > 0 && java.nio.file.Files.size(at) > maxReadBytes) {
                throw MandelaError.limit("'" + path + "' is larger than the "
                        + maxReadBytes + " byte limit this host set for fs.read");
            }
            return new String(java.nio.file.Files.readAllBytes(at), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw io("reading '" + path + "'", failure);
        }
    }

    /**
     * Reads a file as a list of lines.
     *
     * @param caps the run's capabilities
     * @param path the script-supplied path
     * @return one Str per line, without the terminator
     */
    public MandelaList readLines(Capabilities caps, String path) {
        caps.require(Grant.FILE_READ, "read a file");
        java.nio.file.Path at = caps.resolve(path, "read");
        try {
            List<String> lines = java.nio.file.Files.readAllLines(at, StandardCharsets.UTF_8);
            MandelaList out = new MandelaList(lines.size());
            for (String line : lines) {
                out.add(line);
            }
            return out;
        } catch (IOException failure) {
            throw io("reading '" + path + "'", failure);
        }
    }

    /**
     * @param caps the run's capabilities
     * @param path the script-supplied path
     * @return true when something lives at the path
     */
    public boolean exists(Capabilities caps, String path) {
        caps.require(Grant.FILE_READ, "test a path");
        return java.nio.file.Files.exists(caps.resolve(path, "check"));
    }

    /**
     * @param caps the run's capabilities
     * @param path the script-supplied path
     * @return true when the path is a directory
     */
    public boolean isDirectory(Capabilities caps, String path) {
        caps.require(Grant.FILE_READ, "test a path");
        return java.nio.file.Files.isDirectory(caps.resolve(path, "check"));
    }

    /**
     * Lists a directory, names only, sorted so a script gets a stable result.
     *
     * @param caps the run's capabilities
     * @param path the directory to list
     * @return the entry names, relative to {@code path}
     */
    public MandelaList list(Capabilities caps, String path) {
        caps.require(Grant.FILE_READ, "list a directory");
        java.nio.file.Path at = caps.resolve(path.isEmpty() ? "." : path, "list");
        try (Stream<java.nio.file.Path> entries = java.nio.file.Files.list(at)) {
            MandelaList out = new MandelaList(16);
            entries.sorted().forEach(entry -> out.add(entry.getFileName().toString()));
            return out;
        } catch (IOException failure) {
            throw io("listing '" + path + "'", failure);
        }
    }

    /**
     * @param caps the run's capabilities
     * @param path the script-supplied path
     * @return the size in bytes
     */
    public long size(Capabilities caps, String path) {
        caps.require(Grant.FILE_READ, "stat a file");
        try {
            return java.nio.file.Files.size(caps.resolve(path, "stat"));
        } catch (IOException failure) {
            throw io("stat-ing '" + path + "'", failure);
        }
    }

    /**
     * Reads the last-modified time.
     *
     * @param caps the run's capabilities
     * @param path the script-supplied path
     * @return milliseconds since the epoch
     */
    public long modifiedAt(Capabilities caps, String path) {
        caps.require(Grant.FILE_READ, "stat a file");
        try {
            return java.nio.file.Files.getLastModifiedTime(caps.resolve(path, "stat"))
                    .toMillis();
        } catch (IOException failure) {
            throw io("stat-ing '" + path + "'", failure);
        }
    }

    // -- writes --------------------------------------------------------------

    /**
     * Writes a file, replacing it when it exists.
     *
     * @param caps the run's capabilities
     * @param path the script-supplied path
     * @param text the whole new content
     */
    public void write(Capabilities caps, String path, String text) {
        caps.require(Grant.FILE_WRITE, "write a file");
        java.nio.file.Path at = caps.resolve(path, "write");
        try {
            java.nio.file.Files.write(at, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw io("writing '" + path + "'", failure);
        }
    }

    /**
     * Adds text to the end of a file, creating it when needed.
     *
     * @param caps the run's capabilities
     * @param path the script-supplied path
     * @param text the text to append
     */
    public void append(Capabilities caps, String path, String text) {
        caps.require(Grant.FILE_WRITE, "append to a file");
        java.nio.file.Path at = caps.resolve(path, "append");
        try {
            java.nio.file.Files.write(at, text.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw io("appending to '" + path + "'", failure);
        }
    }

    /**
     * Creates a directory and any missing parents.
     *
     * @param caps the run's capabilities
     * @param path the directory to create
     */
    public void makeDirectory(Capabilities caps, String path) {
        caps.require(Grant.FILE_WRITE, "create a directory");
        try {
            java.nio.file.Files.createDirectories(caps.resolve(path, "mkdir"));
        } catch (IOException failure) {
            throw io("creating '" + path + "'", failure);
        }
    }

    /**
     * Deletes a file, or an empty directory.
     *
     * <p>There is no recursive delete in the language. A script asking to remove a
     * whole tree is asking for a walk it can write itself out of {@code fs.list},
     * and an accidental {@code fs.delete(".")} should not be able to erase a
     * granted directory in one call.</p>
     *
     * @param caps the run's capabilities
     * @param path the thing to remove
     * @return true when something was deleted
     */
    public boolean delete(Capabilities caps, String path) {
        caps.require(Grant.FILE_WRITE, "delete a file");
        java.nio.file.Path at = caps.resolve(path, "delete");
        try {
            return java.nio.file.Files.deleteIfExists(at);
        } catch (IOException failure) {
            throw io("deleting '" + path + "'", failure);
        }
    }

    // -- shared ---------------------------------------------------------------

    private static MandelaError io(String what, IOException failure) {
        return MandelaError.io("fs: failed " + what + ": " + failure.getMessage());
    }
}
