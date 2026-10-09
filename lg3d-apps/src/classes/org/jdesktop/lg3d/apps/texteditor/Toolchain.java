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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package-private toolchain helper behind {@link JvmBuildTools}: command
 * builders, tool discovery, source inspection and process execution for the
 * compile / run / debug actions. Every method except {@link #execute} and
 * {@link #stageSource} is a pure {@code String}/{@code Path} function, so the
 * whole class is headless-testable; the extension injects a {@code Runner}
 * seam over {@code execute} in tests and never blocks the EDT in production.
 */
final class Toolchain {

    /** One finished tool run: exit code plus merged stdout+stderr. */
    record Result(int exitCode, String output) {

        /** @return true when the process exited 0. */
        boolean success() {
            return exitCode == 0;
        }
    }

    /** Marker line that opens a LEGACY trailing output block; tool is lowercase. */
    private static final Pattern BLOCK_START =
            Pattern.compile("^// ---- [a-z0-9-]+ output ----$");

    /** First Java type declaration in a source file (class/record/enum/interface). */
    private static final Pattern JAVA_TYPE =
            Pattern.compile("\\b(?:class|record|enum|interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /** A thrown exception/error heading, anywhere in a trace line. */
    private static final Pattern EXCEPTION_HEAD =
            Pattern.compile("[\\w.$]*(?:Exception|Error|Throwable)(?::[^\\n]*)?");

    private Toolchain() {
    }

    // ------------------------------------------------------------------
    // Command builders (pure)
    // ------------------------------------------------------------------

    /** {@code javac -d <classes> <source>}. */
    static List<String> javacCommand(Path javac, Path source, Path classesDir) {
        return List.of(javac.toString(), "-d", classesDir.toString(), source.toString());
    }

    /**
     * The JDK 11+ single-file source launcher: {@code java [<source>]};
     * {@code assertions} adds {@code -ea} (the debug run).
     */
    static List<String> sourceRunCommand(Path java, Path source, boolean assertions) {
        return assertions
                ? List.of(java.toString(), "-ea", source.toString())
                : List.of(java.toString(), source.toString());
    }

    /** {@code kotlinc <source> -d <classes>}. */
    static List<String> kotlincCommand(Path kotlinc, Path source, Path classesDir) {
        return List.of(kotlinc.toString(), source.toString(), "-d", classesDir.toString());
    }

    /** {@code kotlin -cp <classes> <mainClass>} (the Kotlin run launcher). */
    static List<String> kotlinRunCommand(Path kotlin, Path classesDir, String mainClass) {
        return List.of(kotlin.toString(), "-cp", classesDir.toString(), mainClass);
    }

    // ------------------------------------------------------------------
    // Source inspection (pure)
    // ------------------------------------------------------------------

    /**
     * @return the first declared Java type name in the source (best-effort:
     *         the first {@code class|record|enum|interface} keyword followed by
     *         an identifier, which is the source launcher's main class), or
     *         null when the document declares none
     */
    static String deriveClassName(String source) {
        if (source == null) {
            return null;
        }
        Matcher m = JAVA_TYPE.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    /**
     * @param fileName a Kotlin file name such as {@code Foo.kt}
     * @return the JVM main class the Kotlin compiler generates for top-level
     *         functions ({@code FooKt}); blank/unknown names fall back to
     *         {@code MainKt}
     */
    static String kotlinMainClass(String fileName) {
        String stem = (fileName == null) ? "" : fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) {
            stem = stem.substring(0, dot);
        }
        stem = stem.replaceAll("[^A-Za-z0-9_]", "");
        if (stem.isEmpty()) {
            stem = "Main";
        }
        return stem + "Kt";
    }

    /**
     * @param fileName the simple source file name ({@code Foo.java})
     * @param output   merged process output containing stack traces
     * @return the line number of the first frame that points into that file,
     *         matching both plain ({@code Foo.java:42}) and qualified
     *         ({@code com.acme.Foo.java:42}) forms, empty when none
     */
    static OptionalInt firstFrameLine(String fileName, String output) {
        if (fileName == null || fileName.isEmpty() || output == null) {
            return OptionalInt.empty();
        }
        Matcher m = Pattern
                .compile("\\((?:[\\w.$]+\\.)?" + Pattern.quote(fileName) + ":(\\d+)\\)")
                .matcher(output);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /**
     * @param output merged process output
     * @return the qualified exception heading of the first stack trace in the
     *         output (e.g. {@code "java.lang.IllegalStateException: boom"}),
     *         correctly picking it out of a line that starts with
     *         {@code Exception in thread "main" ...}; empty when none
     */
    static String firstExceptionMessage(String output) {
        if (output == null) {
            return "";
        }
        for (String line : output.split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("at ") || t.startsWith("Caused by:")) {
                continue;
            }
            Matcher m = EXCEPTION_HEAD.matcher(t);
            while (m.find()) {
                String candidate = m.group();
                // A bare "Exception"/"Error" word (like the thread prefix) is
                // not a heading; a qualified name or a message is.
                if (candidate.contains(":") || candidate.contains(".")) {
                    return candidate;
                }
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // Legacy output-block migration (pure)
    // ------------------------------------------------------------------

    /**
     * Migration helper: the first JVM Build Tools releases wrote tool output
     * into a trailing {@code // ---- <tool> output ----} comment block before
     * the south {@link OutputConsole} existed. Documents still carrying such a
     * block get it stripped on the next build action.
     *
     * @param source the document text
     * @return the text with any trailing output block (marker line through end
     *         of document) removed; the input is returned unchanged when no
     *         block is present
     */
    static String removeTrailingBlock(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        String[] lines = source.split("\n", -1);
        int start = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (BLOCK_START.matcher(lines[i]).matches()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return source;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < start; i++) {
            sb.append(lines[i]).append('\n');
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1); // drop the blank separator with the block
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Tool discovery
    // ------------------------------------------------------------------

    /** @return {@code <java.home>/bin/<name>} when it is executable. */
    static Optional<Path> jdkTool(String name) {
        Path p = Path.of(System.getProperty("java.home", ""), "bin", name);
        return Files.isExecutable(p) ? Optional.of(p) : Optional.empty();
    }

    /** @return the first executable {@code name} found on {@code pathEnv}. */
    static Optional<Path> findOnPath(String name, String pathEnv) {
        if (name == null || pathEnv == null || pathEnv.isEmpty()) {
            return Optional.empty();
        }
        for (String dir : pathEnv.split(Pattern.quote(System.getProperty("path.separator", ":")))) {
            if (dir.isBlank()) {
                continue;
            }
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code kotlin} launcher next to {@code kotlinc}; falls back to the
     * {@code kotlinc} path itself when the sibling launcher is not installed.
     */
    static Path kotlinLauncher(Path kotlinc) {
        Path bin = kotlinc.toAbsolutePath().getParent();
        Path sibling = (bin == null) ? null : bin.resolve("kotlin");
        if (sibling != null && Files.isExecutable(sibling)) {
            return sibling;
        }
        return kotlinc;
    }

    // ------------------------------------------------------------------
    // Process execution (never called on the EDT; tests inject a seam)
    // ------------------------------------------------------------------

    /**
     * Writes {@code text} to {@code <tmp>/<fileName>} in a fresh temp working
     * directory, so a build always runs against the document's <em>current</em>
     * text and never touches the user's file on disk.
     */
    static Path stageSource(String text, String fileName) throws IOException {
        Path dir = Files.createTempDirectory("lg3d-jvm-");
        Files.writeString(dir.resolve(fileName), text, StandardCharsets.UTF_8);
        return dir;
    }

    /**
     * Runs {@code command} in {@code workDir}, merging stderr into stdout, and
     * waits up to {@code timeoutMs}; on timeout the process is force-killed and
     * exit code 124 is reported (a missing executable reports 127). Blocks the
     * calling thread — production callers go through the extension's virtual
     * thread runner, tests through the injected fake.
     */
    static Result execute(List<String> command, Path workDir, long timeoutMs) {
        Process p;
        try {
            p = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ioe) {
            return new Result(127, "Cannot run " + command.get(0) + ": " + ioe.getMessage());
        }
        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (var in = p.getInputStream()) {
                sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // The stream closing at process exit is the normal end of data.
            }
        }, "lg3d-toolchain-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
            reader.join(2_000);
        } catch (InterruptedException ie) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(130, "Interrupted");
        }
        String out = sink.toString();
        if (!finished) {
            return new Result(124, out + "\n[timed out after "
                    + (timeoutMs / 1000) + "s — killed]");
        }
        return new Result(p.exitValue(), out);
    }
}
