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
package org.jdesktop.lg3d.utils.system;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opens files, folders and URIs with the user's preferred applications and
 * moves files to the trash, using the freedesktop.org conventions.
 *
 * <p>Opening delegates to {@code xdg-open}; trashing prefers {@code gio trash}
 * and falls back to a manual move into {@code $XDG_DATA_HOME/Trash} (writing a
 * compliant {@code .trashinfo} sidecar) when gio is absent. Both are optional:
 * if the tools are missing the operations report failure instead of throwing,
 * so the file manager and the dock stacks can show a message.</p>
 *
 * <p>No JNI/JNA; pure JDK plus the standard desktop CLI tools.</p>
 */
public final class Opener {
    private static final Logger logger = Logger.getLogger("lg.system");

    private static final DateTimeFormatter TRASH_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Opener() {
        // no instances
    }

    /** True if {@code xdg-open} is available. */
    public static boolean canOpen() {
        return ProcessRunner.isAvailable("xdg-open");
    }

    /**
     * Opens a local file or directory with the default application
     * ({@code xdg-open <path>}).
     *
     * @return true if xdg-open was launched successfully (exit 0)
     */
    public static boolean open(Path path) {
        if (path == null) {
            return false;
        }
        if (!canOpen()) {
            logger.warning("xdg-open not available; cannot open " + path);
            return false;
        }
        ProcessRunner.Result r = ProcessRunner.run("xdg-open", path.toAbsolutePath().toString());
        if (!r.isSuccess()) {
            logger.log(Level.INFO, "xdg-open failed for {0}: {1}",
                    new Object[] { path, r.getMessage() });
        }
        // xdg-open forks the real app and may return before it is up; a started
        // process with exit 0 is success.
        return r.isSuccess();
    }

    /** Convenience overload for a String path. */
    public static boolean open(String path) {
        return (path == null) ? false : open(Paths.get(path));
    }

    /**
     * Opens a URI (http:, mailto:, ...) with the default handler.
     *
     * @return true if xdg-open was launched successfully
     */
    public static boolean openUri(String uri) {
        if (uri == null || uri.isBlank() || !canOpen()) {
            return false;
        }
        return ProcessRunner.run("xdg-open", uri).isSuccess();
    }

    /**
     * Moves a path to the trash. Prefers {@code gio trash}; otherwise performs
     * a freedesktop-compliant move into {@code $XDG_DATA_HOME/Trash}.
     *
     * @return true if the path no longer exists at its original location
     */
    public static boolean trash(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        if (ProcessRunner.isAvailable("gio")) {
            ProcessRunner.Result r = ProcessRunner.run("gio", "trash", path.toAbsolutePath().toString());
            if (r.isSuccess()) {
                return !Files.exists(path);
            }
            logger.log(Level.FINE, "gio trash failed ({0}); trying manual trash", r.getMessage());
        }
        return trashManually(path);
    }

    /** Convenience overload for a String path. */
    public static boolean trash(String path) {
        return (path == null) ? false : trash(Paths.get(path));
    }

    /**
     * Freedesktop Trash implementation: move the file into
     * {@code <trash>/files} and write {@code <trash>/info/<name>.trashinfo}.
     */
    private static boolean trashManually(Path path) {
        try {
            Path abs = path.toAbsolutePath().normalize();
            Path trashRoot = trashRoot(abs);
            Path filesDir = trashRoot.resolve("files");
            Path infoDir = trashRoot.resolve("info");
            Files.createDirectories(filesDir);
            Files.createDirectories(infoDir);

            String baseName = abs.getFileName().toString();
            Path target = uniqueTarget(filesDir, baseName);
            String targetBase = target.getFileName().toString();

            // Write the .trashinfo sidecar first (spec order).
            String encodedPath = URLEncoder.encode(abs.toString(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            List<String> info = new ArrayList<>(3);
            info.add("[Trash Info]");
            info.add("Path=" + encodedPath);
            info.add("DeletionDate=" + LocalDateTime.now().format(TRASH_DATE));
            Files.write(infoDir.resolve(targetBase + ".trashinfo"),
                    String.join("\n", info).getBytes(StandardCharsets.UTF_8));

            Files.move(abs, target, StandardCopyOption.REPLACE_EXISTING);
            return !Files.exists(abs);
        } catch (IOException | RuntimeException e) {
            logger.log(Level.INFO, "Manual trash failed for " + path, e);
            return false;
        }
    }

    /**
     * Chooses the trash directory for a path: the per-user
     * {@code $XDG_DATA_HOME/Trash} (default {@code ~/.local/share/Trash}). A
     * full implementation would honour per-mount {@code .Trash-$UID} top-level
     * directories, but the home trash works for the common case and is what gio
     * falls back to for non-removable paths.
     */
    private static Path trashRoot(Path abs) {
        String dataHome = System.getenv("XDG_DATA_HOME");
        Path base;
        if (dataHome != null && !dataHome.isBlank()) {
            base = Paths.get(dataHome);
        } else {
            base = Paths.get(System.getProperty("user.home"), ".local", "share");
        }
        return base.resolve("Trash");
    }

    /** Returns a non-colliding target path within {@code dir} for {@code name}. */
    private static Path uniqueTarget(Path dir, String name) {
        Path candidate = dir.resolve(name);
        int i = 1;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "." + i + ext);
            i++;
        }
        return candidate;
    }
}
