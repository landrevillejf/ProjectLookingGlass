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
package org.jdesktop.lg3d.displayserver.crash;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The desktop's own copy of every report: the spool under {@code ~/.lg3d/crash-reports}.
 *
 * <p>Because this tool files nothing by itself - the reporter clicks Submit in the
 * browser, and may not: the desktop may be mid-crash, the network may be down, the
 * user may be reading a movie scene and dismiss the dialog - the report has to
 * exist <em>before</em> the tracker does. The spool is what makes the pipeline
 * reliable: a report the user did not send is still on disk, listed in the Bug
 * Reporter's archive, one click from being sent a week later. It is also the
 * answer to the URL size limit, since a prefill link cannot carry a full trace.</p>
 *
 * <p>Three properties, each with a reason:</p>
 *
 * <ul>
 *   <li><b>Grouped by signature, not by time.</b> One bug seen fifty times is one
 *   file whose count says fifty. A per-crash file would need a human to diff fifty
 *   near-identical documents to learn what a counter states in one digit.</li>
 *   <li><b>Bounded.</b> A crash loop is a program that fails continuously, and an
 *   unbounded spool turns that bug into a second problem - a full home directory -
 *   on the machine that already has one. Both a file count and a byte total are
 *   enforced, oldest-first, and an evicted record drops its whole directory.</li>
 *   <li><b>Written atomically.</b> The file is staged as {@code *.part} and moved
 *   into place, because the writer is precisely the thread that just died. A half
 *   written report is worse than none: it reads as a corrupt submission and burns
 *   the maintainer's time on a file that cannot be trusted.</li>
 * </ul>
 *
 * <p>Failures here are logged and swallowed. The spool is a convenience on the
 * error path; refusing to save a report must never be the reason a desktop stops
 * showing its crash dialog.</p>
 */
public final class CrashStore {

    /** Where the spool lives by default, relative to the user's home. */
    private static final String SPOOL_DIRECTORY = ".lg3d/crash-reports";

    /** The machine-readable record inside the signature directory. */
    private static final String RECORD_FILE = "record.json";

    /** The human-readable report inside the signature directory. */
    private static final String MARKDOWN_FILE = "report.md";

    /** Files are staged under this suffix and renamed into place. */
    private static final String STAGING_SUFFIX = ".part";

    /** The default ceiling on stored reports. */
    public static final int DEFAULT_MAX_REPORTS = 50;

    /** The default ceiling on spool size, in bytes (8 MiB). */
    public static final long DEFAULT_MAX_BYTES = 8L * 1024 * 1024;

    private static final Logger LOG = Logger.getLogger(CrashStore.class.getName());

    private final Path root;
    private final int maxReports;
    private final long maxBytes;

    /**
     * Opens a spool, creating the directory on first use.
     *
     * @param root       the spool directory, never {@code null}
     * @param maxReports the file-count ceiling, at least 1
     * @param maxBytes   the byte ceiling, at least one report's worth
     */
    public CrashStore(Path root, int maxReports, long maxBytes) {
        this.root = root;
        this.maxReports = Math.max(1, maxReports);
        this.maxBytes = Math.max(1024L, maxBytes);
    }

    /**
     * The spool at the default location and limits, honouring the
     * {@code lg.crashreport.dir} override that lets a test, a kiosk image or a
     * bug-hunt session point the archive somewhere else without a rebuild.
     *
     * @return the shared spool
     */
    public static CrashStore defaultStore() {
        String override = System.getProperty("lg.crashreport.dir");
        Path dir = (override == null || override.isBlank())
                ? Paths.get(System.getProperty("user.home", "."), SPOOL_DIRECTORY)
                : Paths.get(override.trim());
        int maxReports = intProperty("lg.crashreport.maxReports", DEFAULT_MAX_REPORTS);
        long maxBytes = longProperty("lg.crashreport.maxBytes", DEFAULT_MAX_BYTES);
        return new CrashStore(dir, maxReports, maxBytes);
    }

    /**
     * Saves a report, grouping it under its signature.
     *
     * @param report the already-redacted report
     * @return the outcome, including whether this was a first sighting or the
     *         ninth of the same bug
     */
    public Stored store(CrashReport report) {
        Path directory = root.resolve(safeName(report.signature()));
        try {
            Files.createDirectories(directory);
            Map<String, String> record = readRecord(directory);
            int occurrences = report.occurrences();
            if (record != null) {
                // The stored count wins over what the caller brought, and the two
                // are added rather than maxed: the in-memory tally counts this
                // session, the file counts every session, and a report that has
                // been seen three times before and twice now has been seen five.
                occurrences = parseInt(record.get("occurrences"), 0) + report.occurrences();
            }
            Map<String, String> merged = new LinkedHashMap<>();
            if (record != null) {
                merged.putAll(record);
            }
            merged.put("signature", report.signature());
            merged.put("lastId", report.id());
            merged.put("occurrences", String.valueOf(occurrences));
            merged.put("firstSeen", (record == null)
                    ? report.occurredAt().toString() : record.getOrDefault("firstSeen",
                            report.occurredAt().toString()));
            merged.put("lastSeen", report.occurredAt().toString());
            merged.put("kind", report.kind().name());
            merged.put("severity", report.severity().name());
            merged.put("title", report.title());
            // Which component caught it - CrashWatcher, LogHandler, the Bug Reporter
            // panel. The Markdown footer says so for a human, and the archive needs
            // it as a field, because a list of stored crashes that all read "spool"
            // cannot tell an uncaught exception from a SEVERE log record.
            merged.put("reporter", report.reporter());
            // The first file's name is kept so a maintainer who was quoted a path
            // in a reply still finds the document after the bug recurs.
            if (record == null || !record.containsKey("markdown")) {
                merged.put("markdown", MARKDOWN_FILE);
            }
            writeAtomically(directory.resolve(RECORD_FILE), toJson(merged));
            // The Markdown is rewritten from the newest occurrence: the body the
            // maintainer reads must carry the current environment block and the
            // accumulated count, not the ones from the first crash of the run. The
            // count is written through the report's own builder rather than patched
            // into the finished text, because the document's header, its footer and
            // record.json are three places the same number appears - a regex over
            // one of them left the other two telling a different story whenever the
            // incoming report was not the first sighting.
            CrashReport accumulated = report.toBuilder().occurrences(occurrences).build();
            writeAtomically(directory.resolve(MARKDOWN_FILE), accumulated.toMarkdown());
            prune();
            return new Stored(directory.resolve(MARKDOWN_FILE), occurrences, record != null);
        } catch (IOException | UncheckedIOException e) {
            // Reported once per signature so a full disk during a crash loop does
            // not add a second exception to every log line already being written.
            LOG.log(Level.WARNING, "the crash report could not be spooled to " + directory
                    + "; the report is still shown to the user", e);
            return new Stored(null, report.occurrences(), false);
        }
    }

    /**
     * @return every stored report, newest first, skipping unreadable ones
     */
    public List<CrashReport> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<CrashReport> out = new ArrayList<>();
        try (var children = Files.list(root)) {
            List<Path> directories = children.filter(Files::isDirectory).toList();
            for (Path directory : directories) {
                read(directory).ifPresent(out::add);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "the report spool at " + root + " could not be listed", e);
            return List.of();
        }
        out.sort(Comparator.comparing(CrashReport::occurredAt).reversed());
        return out;
    }

    /**
     * Reads one report back, so an archived crash can be re-filed verbatim after a
     * restart. The signature is restored from the record rather than recomputed:
     * a rebuilt {@code Throwable} would sign on the frames the spool kept, which is
     * the right answer, but the file already states the identity the previous run
     * chose and contradicting it would open a second issue for one bug.
     *
     * @param signature the grouping key, as printed in a report footer
     * @return the report, empty when nothing is stored under that key
     */
    public Optional<CrashReport> find(String signature) {
        if (signature == null || signature.isBlank()) {
            return Optional.empty();
        }
        return read(root.resolve(safeName(signature)));
    }

    /**
     * Removes one report and its directory, for "I filed it" or "forget this".
     *
     * @param signature the grouping key
     * @return true when something was deleted
     */
    public boolean delete(String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        Path directory = root.resolve(safeName(signature));
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var walk = Files.walk(directory)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "the stored report " + directory + " could not be deleted", e);
            return false;
        }
    }

    /** @return the spool directory, for the sentence that tells a user where to look */
    public Path root() {
        return root;
    }

    /**
     * The result of a save.
     *
     * @param markdown    the file holding the full report, {@code null} when the
     *                    spool could not be written
     * @param occurrences the total sightings of this signature across all sessions
     * @param duplicate   true when the signature was already on disk
     */
    public record Stored(Path markdown, int occurrences, boolean duplicate) {
    }

    private Optional<CrashReport> read(Path directory) {
        Map<String, String> record = readRecord(directory);
        if (record == null) {
            return Optional.empty();
        }
        try {
            String markdown = Files.readString(
                    directory.resolve(MARKDOWN_FILE), StandardCharsets.UTF_8);
            String caughtBy = record.get("reporter");
            return Optional.of(CrashReport.builder()
                    .id(record.getOrDefault("lastId", "spooled"))
                    .signature(record.get("signature"))
                    .kind(CrashReport.Kind.valueOf(
                            record.getOrDefault("kind", CrashReport.Kind.SEVERE.name())))
                    .severity(CrashReport.Severity.valueOf(record.getOrDefault(
                            "severity", CrashReport.Severity.MAJOR.name())))
                    .occurredAt(Instant.parse(record.getOrDefault("lastSeen",
                            Instant.EPOCH.toString())))
                    .occurrences(parseInt(record.get("occurrences"), 1))
                    // The stored document is already redacted; the builder's scrub
                    // is idempotent, so re-reading it cannot double-mask a value.
                    .message(messageOf(markdown))
                    .stack(stackOf(markdown))
                    // The component that caught it, when the record says; an older
                    // archive, or a report whose own field was empty, is only known
                    // to have come from the spool.
                    .reporter((caughtBy == null || caughtBy.isBlank())
                            ? "spool" : caughtBy)
                    .build());
        } catch (IOException | RuntimeException e) {
            // One unreadable report must not blank the archive list.
            LOG.log(Level.WARNING, "the stored report in " + directory
                    + " is unreadable and was skipped", e);
            return Optional.empty();
        }
    }

    private Map<String, String> readRecord(Path directory) {
        Path file = directory.resolve(RECORD_FILE);
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            return fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "the record " + file + " is not readable and was ignored", e);
            return null;
        }
    }

    /**
     * Enforces both ceilings, oldest first.
     *
     * <p>Ordered by modification time rather than by {@code firstSeen}: a bug that
     * keeps recurring is actively useful, and evicting the newest activity to keep
     * an old file nobody will read again would empty the spool of exactly the
     * reports the next crash dialog depends on.</p>
     *
     * <p>The newest entry is never evicted, even when it alone is over the byte
     * ceiling: a budget that discards the report it just saved is not a budget but
     * a delete, and the crash dialog quotes that path.</p>
     */
    private void prune() throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> directories;
        try (var children = Files.list(root)) {
            directories = children.filter(Files::isDirectory).toList();
        }
        if (directories.size() <= maxReports && totalSize(directories) <= maxBytes) {
            return;
        }
        List<Path> ordered = new ArrayList<>(directories);
        ordered.sort(Comparator.comparingLong(CrashStore::lastModified));
        while (ordered.size() > 1
                && (ordered.size() > maxReports || totalSize(ordered) > maxBytes)) {
            Path oldest = ordered.remove(0);
            deleteQuietly(oldest);
        }
    }

    private long totalSize(List<Path> directories) {
        long total = 0L;
        for (Path directory : directories) {
            try (var walk = Files.walk(directory)) {
                total += walk.filter(Files::isRegularFile)
                        .mapToLong(CrashStore::sizeOf).sum();
            } catch (IOException e) {
                // An unreadable directory counts as nothing: pruning is allowed to
                // be slightly over budget, never to throw on the error path.
                continue;
            }
        }
        return total;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long lastModified(Path directory) {
        try {
            return Files.getLastModifiedTime(directory).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void deleteQuietly(Path directory) {
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "the evicted report " + directory + " could not be deleted", e);
        }
    }

    /**
     * Stages then moves, so a reader never sees a half-written file. A filesystem
     * without rename support (a home on some network mounts) falls back to a plain
     * copy: the report survives, only the atomicity is lost.
     */
    private void writeAtomically(Path target, String content) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + STAGING_SUFFIX);
        Files.writeString(staging, content, StandardCharsets.UTF_8);
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * A signature used as a path component must not be able to escape the spool.
     * The generator emits hex, but a hand-edited or corrupted record could carry
     * {@code ../}, so the character whitelist - not a blacklist - decides.
     */
    private static String safeName(String signature) {
        if (signature == null || signature.isBlank()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder(signature.length());
        for (int i = 0; i < Math.min(signature.length(), 64); i++) {
            char c = signature.charAt(i);
            sb.append(((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-') ? c : '_');
        }
        return sb.toString();
    }

    /**
     * Writes the flat record. Hand-rolled rather than a library: {@code lg3d-core}
     * carries no JSON dependency, the format is a single level of strings, and a
     * spool format whose parser lives in another artifact is a spool that cannot be
     * read after a partial installation.
     */
    private static String toJson(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("  \"").append(escape(entry.getKey())).append("\": \"")
              .append(escape(entry.getValue())).append('"');
        }
        return sb.append("\n}\n").toString();
    }

    /**
     * Reads the flat record. Tolerant by design: an unexpected key is kept, a
     * missing one is skipped, and anything unparseable returns {@code null} so the
     * caller treats the report as absent instead of failing the save of a new one.
     */
    private static Map<String, String> fromJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < json.length()) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = json.indexOf('"', keyStart + 1);
            int valueStart = json.indexOf('"', keyEnd + 1);
            if (keyEnd < 0 || valueStart < 0) {
                break;
            }
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) {
                break;
            }
            out.put(unescape(json.substring(keyStart + 1, keyEnd)),
                    unescape(json.substring(valueStart + 1, valueEnd)));
            i = valueEnd + 1;
        }
        return out;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** The message line of a stored report: the sentence after {@code ### Error}. */
    private static String messageOf(String markdown) {
        String marker = "### Error\n\n";
        int at = markdown.indexOf(marker);
        if (at < 0) {
            return "";
        }
        String rest = markdown.substring(at + marker.length());
        int end = rest.indexOf("\n");
        String line = (end < 0) ? rest : rest.substring(0, end);
        return line.startsWith("`") && line.endsWith("`") && line.length() > 1
                ? line.substring(1, line.length() - 1) : line;
    }

    /** The fenced trace of a stored report, empty when it carried none. */
    private static String stackOf(String markdown) {
        int fence = markdown.indexOf("```\n");
        if (fence < 0) {
            return "";
        }
        int end = markdown.indexOf("\n```", fence + 4);
        return (end < 0) ? markdown.substring(fence + 4) : markdown.substring(fence + 4, end);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return (value == null) ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intProperty(String name, int fallback) {
        int parsed = parseInt(System.getProperty(name), fallback);
        return parsed > 0 ? parsed : fallback;
    }

    private static long longProperty(String name, long fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
