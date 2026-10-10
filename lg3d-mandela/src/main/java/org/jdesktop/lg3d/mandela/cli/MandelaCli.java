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
package org.jdesktop.lg3d.mandela.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.code.CompiledFunction;
import org.jdesktop.lg3d.mandela.code.Disassembler;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.lang.LangException;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * The {@code mandela} command: run a program, check one, evaluate a one-liner,
 * start a session, look at the instructions.
 *
 * <p>The CLI is a thin shell over {@link Runtime} &mdash; it owns no language
 * behaviour of its own, which is the only way the two stay honest with each other.
 * What it does own is the two decisions a command line has to make and a library
 * must not: <em>what the script is allowed to do</em>, and <em>what came out of the
 * run</em>.</p>
 *
 * <h2>The sandbox decision</h2>
 *
 * <p>{@code mandela run FILE} gives the program the desktop shape: it may print,
 * and it may read and write files under the directory its own file sits in &mdash;
 * nothing else. It gets no environment, no network, no process and no JVM, because
 * a script author asking for {@code println} has not asked for those, and a
 * language that hands them out silently is a language whose sandbox nobody can
 * reason about. {@code --sandbox DIR} moves the root, and {@code --unrestricted}
 * is the documented way to say "this script is mine and I want it to have the
 * host's rights". The flag is loud on purpose.</p>
 *
 * <h2>Exit codes</h2>
 *
 * <p>Fixed, so a build script or a desktop launcher can branch on them without
 * parsing prose: 0 the program ran, 1 the command line was wrong, 2 the program
 * failed, 3 a file could not be read, 4 {@code check} found blocking problems.
 * A compile error is a program failure (2), not a usage error (1): the author of
 * {@code mandela run broken.mnd} gets the same code whether the mistake is a
 * missing brace or a division by zero, which is what a Makefile expects.</p>
 */
public final class MandelaCli {

    /** The program ran and produced no error. */
    public static final int OK = 0;
    /** The command line itself was wrong; no program was started. */
    public static final int USAGE = 1;
    /** The program ran and failed, at compile time or at run time. */
    public static final int PROGRAM_FAILED = 2;
    /** A named file was missing, unreadable or a directory. */
    public static final int NOT_READABLE = 3;
    /** {@code check} found at least one blocking diagnostic. */
    public static final int FINDINGS = 4;

    /** The whole help text, kept in one piece so {@code --help} and a typo agree. */
    static final String USAGE_TEXT = """
            usage: mandela <command> [options] [args]

            commands:
              run FILE [args...]   run a program (its directory is the file sandbox)
              check FILE...        compile without running; print every finding
              eval PROGRAM         run one program from the command line
              repl                 start an interactive session
              dump FILE            print the instructions the compiler produced
              about                print the version and the module table
              help                 print this text

            options for run, eval, repl and dump:
              -s, --sandbox DIR    confine file access to DIR instead of the script's
                                   directory
              -u, --unrestricted   give the program the host's full rights; only for
                                   code you wrote and trust
              -t, --trace          print the script's frame trace when it fails

            examples:
              mandela run hello.mnd Ada
              mandela eval 'println("2^10 = " + (2 ** 10))'
              mandela check src/*.mnd
              mandela repl
            """;

    private MandelaCli() {
        // Static entry point only.
    }

    /** @param argv the process arguments */
    public static void main(String[] argv) {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8), false);
        PrintWriter err = new PrintWriter(
                new OutputStreamWriter(System.err, StandardCharsets.UTF_8), false);
        int code = execute(argv, in, out, err);
        out.flush();
        err.flush();
        System.exit(code);
    }

    /**
     * Runs one command line, writing where it is told to.
     *
     * <p>Separated from {@link #main} and given the streams as arguments because a
     * test and the desktop's own launcher both need the behaviour without a
     * {@code System.exit}: the desktop embeds this class to offer a Mandela console
     * panel, and a panel that killed the JVM when the user typed {@code about}
     * would be a poor panel.</p>
     *
     * @param argv the arguments, without the program name
     * @param in   the console input, used by {@code repl}
     * @param out  the standard output
     * @param err  the standard error, where every failure is reported
     * @return the process exit code, one of the constants above
     */
    public static int execute(String[] argv, BufferedReader in,
                              PrintWriter out, PrintWriter err) {
        Request request;
        try {
            request = Request.parse(argv);
        } catch (IllegalArgumentException wrong) {
            err.println("mandela: " + wrong.getMessage());
            err.println(USAGE_TEXT);
            return USAGE;
        }
        if (request.help || request.command.isEmpty()) {
            out.print(USAGE_TEXT);
            return OK;
        }
        if ("version".equals(request.command) || "--version".equals(request.command)) {
            out.println("mandela " + Mandela.IMPLEMENTATION_VERSION + " ("
                    + Mandela.PROJECT_NAME + ", language "
                    + Mandela.LANGUAGE_VERSION + ")");
            return OK;
        }
        return switch (request.command) {
            case "run" -> run(request, out, err);
            case "check" -> check(request, err);
            case "eval" -> evaluate(request, out, err);
            case "dump" -> dump(request, out, err);
            case "about" -> {
                about(out);
                yield OK;
            }
            case "repl" -> Repl.start(request, in, out, err);
            default -> {
                err.println("mandela: unknown command '" + request.command + "'");
                err.println(USAGE_TEXT);
                yield USAGE;
            }
        };
    }

    // -- the commands ---------------------------------------------------------

    /** {@code mandela run FILE [args...]} */
    private static int run(Request request, PrintWriter out, PrintWriter err) {
        if (request.files.isEmpty()) {
            err.println("mandela: run needs a file");
            return USAGE;
        }
        Path file = Paths.get(request.files.get(0));
        String text = read(file, err);
        if (text == null) {
            return NOT_READABLE;
        }
        Runtime runtime = engine(request, sandboxFor(request, file), out, err);
        runtime.bind("args", scriptArgs(request.files.subList(1, request.files.size())));
        try {
            runtime.run(text, file.toString());
            return OK;
        } catch (MandelaError | LangException failure) {
            report(failure, runtime, request, err);
            return PROGRAM_FAILED;
        }
    }

    /** {@code mandela check FILE...} &mdash; every finding, and a code to branch on. */
    private static int check(Request request, PrintWriter err) {
        if (request.files.isEmpty()) {
            err.println("mandela: check needs at least one file");
            return USAGE;
        }
        // Checking is compile-only, so the console sandbox is the honest one: a
        // program that is never run must not be given a file root it will use.
        Runtime runtime = Mandela.engine().build();
        runtime.bind("args", scriptArgs(List.of()));
        int blocked = 0;
        int warned = 0;
        for (String name : request.files) {
            Path file = Paths.get(name);
            String text = read(file, err);
            if (text == null) {
                return NOT_READABLE;
            }
            List<Diagnostic> found;
            try {
                found = runtime.check(text, file.toString());
            } catch (LangException failure) {
                // check() is documented never to raise, so getting here means the
                // compiler failed outside the lenient path; report it as a finding
                // rather than losing the file the author was working on.
                found = List.of(failure.toDiagnostic());
            }
            for (Diagnostic one : found) {
                err.println(one + (one.rule().isEmpty() ? "" : " [" + one.rule() + "]"));
                if (one.isError()) {
                    blocked++;
                } else {
                    warned++;
                }
            }
        }
        if (blocked > 0) {
            err.println("mandela: " + blocked + " error(s), " + warned + " warning(s)");
            return FINDINGS;
        }
        return OK;
    }

    /** {@code mandela eval PROGRAM} &mdash; the one-liner, echoing its value. */
    private static int evaluate(Request request, PrintWriter out, PrintWriter err) {
        if (request.files.isEmpty()) {
            err.println("mandela: eval needs a program");
            return USAGE;
        }
        String text = String.join(" ", request.files);
        Runtime runtime = engine(request, sandboxOf(request), out, err);
        try {
            Object value = runtime.evalEntry(text, Runtime.STRING_SOURCE);
            if (value != Runtime.REPL_SILENT) {
                out.println(Values.display(value));
            }
            return OK;
        } catch (MandelaError | LangException failure) {
            report(failure, runtime, request, err);
            return PROGRAM_FAILED;
        }
    }

    /** {@code mandela dump FILE} &mdash; the compiled instructions, for learning. */
    private static int dump(Request request, PrintWriter out, PrintWriter err) {
        if (request.files.isEmpty()) {
            err.println("mandela: dump needs a file");
            return USAGE;
        }
        Path file = Paths.get(request.files.get(0));
        String text = read(file, err);
        if (text == null) {
            return NOT_READABLE;
        }
        Runtime runtime = engine(request, sandboxFor(request, file), out, err);
        try {
            CompiledFunction body = runtime.compile(text, file.toString());
            out.print(Disassembler.dump(body));
            return OK;
        } catch (MandelaError | LangException failure) {
            report(failure, runtime, request, err);
            return PROGRAM_FAILED;
        }
    }

    /** {@code mandela about} &mdash; what this jar is, in the host's own words. */
    @SuppressWarnings("unchecked")
    private static void about(PrintWriter out) {
        Map<String, Object> info = Mandela.about();
        out.println(info.get("project") + " — " + info.get("language") + " "
                + info.get("version"));
        out.println("files: *." + info.get("extension")
                + "   engines: " + String.join(", ", (List<String>) info.get("names")));
        out.println("modules: " + String.join(", ", (List<String>) info.get("modules")));
        out.println("embedding: org.jdesktop.lg3d.mandela.api.Mandela (Java/Kotlin)"
                + " or javax.script name 'mandela'");
    }

    // -- shared plumbing ------------------------------------------------------

    /**
     * Builds the engine one run gets.
     *
     * @param request the parsed command line
     * @param root    the directory file access is confined to, or null
     * @param out     where the script's {@code print} goes
     * @param err     where the script's {@code eprintln} goes
     * @return a runtime, ready to run one program
     */
    static Runtime engine(Request request, Path root, PrintWriter out, PrintWriter err) {
        Runtime.Builder builder;
        if (request.unrestricted) {
            builder = Mandela.engine(Capabilities.open());
        } else if (root != null) {
            builder = Mandela.application(root);
        } else {
            builder = Mandela.engine();
        }
        // The application builder already points at System.out; a command whose
        // output was handed to a panel or a test must go there instead, so the
        // sinks are always the caller's.
        Runtime runtime = builder.output(out::print).error(err::print).build();
        // `args` exists on every engine, not only on the one that was given words:
        // a script that reads its arguments must also compile, check and dump
        // without them, and an "unknown name 'args'" in a lint run would be a
        // finding the author never wrote.
        runtime.bind("args", scriptArgs(List.of()));
        return runtime;
    }

    /** @return the sandbox for an option, or null when none was asked for */
    static Path sandboxOf(Request request) {
        return (request.sandbox == null) ? null : Paths.get(request.sandbox);
    }

    /**
     * The directory a run may touch.
     *
     * <p>An explicit {@code --sandbox} wins; otherwise a file gets the directory it
     * sits in and a program typed on the command line gets nothing, which is the
     * console sandbox &mdash; {@code mandela eval} has no reason to be able to open a
     * file and should not acquire one because it was convenient to implement.</p>
     *
     * @param request the command line
     * @param file    the program's file, or null for a snippet
     * @return the root, or null
     */
    static Path sandboxFor(Request request, Path file) {
        Path asked = sandboxOf(request);
        if (asked != null) {
            return asked;
        }
        return (file == null) ? null : rootOf(file);
    }

    /** @return the directory a script file sits in, always absolute */
    static Path rootOf(Path file) {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        return (parent != null) ? parent : Paths.get("").toAbsolutePath();
    }

    /**
     * The command line's extra words, as the script sees them.
     *
     * <p>{@code args} is a list of text, in order, and a script indexes it; it is
     * not a {@code java.util} list because the language's own list is what a script
     * can call methods on.</p>
     *
     * @param words the words after the file name
     * @return the value bound to {@code args}
     */
    static MandelaList scriptArgs(List<String> words) {
        MandelaList out = new MandelaList(words.size());
        for (String word : words) {
            out.add(word);
        }
        return out;
    }

    /**
     * Reads a program, or reports why it could not be read and answers null.
     *
     * @param file what to read
     * @param err  where to say so
     * @return the text, or null
     */
    static String read(Path file, PrintWriter err) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException problem) {
            err.println("mandela: cannot read '" + file + "': " + problem.getMessage());
            return null;
        }
    }

    /**
     * Says what went wrong, once, in the shape a terminal and an editor both parse.
     *
     * <p>A script failure is printed as {@code file:line: Kind: message} because
     * that is what an editor's run panel matches a squiggle against, and the
     * trace &mdash; when asked for &mdash; comes after it rather than before, so the
     * first line a human reads is always the reason.</p>
     *
     * @param failure what the machine or the compiler raised
     * @param runtime the engine, for its trace
     * @param request the command line, for {@code --trace}
     * @param err     where to write
     */
    static void report(Throwable failure, Runtime runtime, Request request,
                       PrintWriter err) {
        if (failure instanceof MandelaError raised) {
            String at = (raised.sourceName() == null) ? ""
                    : raised.sourceName() + ":" + raised.line() + ": ";
            err.println(at + raised.kind() + ": " + rootMessage(raised));
        } else if (failure instanceof LangException wrong) {
            Diagnostic one = wrong.toDiagnostic();
            err.println(one + (one.rule().isEmpty() ? "" : " [" + one.rule() + "]"));
        } else {
            err.println("mandela: " + failure);
        }
        if (request != null && request.trace && runtime != null) {
            // The failure has already unwound by the time this prints, so the live
            // frame chain is empty; the machine kept the frames it ran through.
            List<String> frames = runtime.failureTrace();
            if (!frames.isEmpty()) {
                err.println("  trace (innermost last):");
                for (String frame : frames) {
                    err.println("    " + frame);
                }
            }
        }
    }

    /**
     * The message without the position a nested {@link MandelaError} already added.
     *
     * <p>{@code at(...)} prefixes the text with {@code file:line:} when a module
     * frame is reported, and printing that twice in a row makes a two-line module
     * failure look like a three-line one.</p>
     *
     * @param failure the raised error
     * @return the message with any leading position removed
     */
    private static String rootMessage(MandelaError failure) {
        String bare = failure.getMessage();
        if (bare == null) {
            return "";
        }
        int colon = bare.indexOf(":");
        if (colon > 0 && bare.indexOf(':', colon + 1) > 0) {
            // Looks like "file:line: text"; the caller already printed the position.
            int space = bare.indexOf(' ', colon + 1);
            if (space > colon && space < bare.length() - 1) {
                return bare.substring(space + 1).trim();
            }
        }
        return bare;
    }

    // -- the parsed command line ----------------------------------------------

    /**
     * One parsed command line: a command, a sandbox decision and the words.
     *
     * @param command     the verb, or "" for nothing to do
     * @param files       the positional words: a file, the files, the program, or
     *                    the script's own arguments
     * @param sandbox     the {@code --sandbox} directory, or null
     * @param unrestricted whether the host's full rights were asked for
     * @param trace       whether a frame trace was asked for
     * @param help        whether the usage text is the whole answer
     */
    record Request(String command, List<String> files, String sandbox,
                   boolean unrestricted, boolean trace, boolean help) {

        /**
         * Parses a command line.
         *
         * <p>Options are recognised anywhere after the command, because a person
         * types {@code mandela run -t x.mnd} and {@code mandela run x.mnd -t} in
         * about equal measure, and there is no reason to make one of them a usage
         * error. Everything that is not an option is a word: for {@code run} the
         * first is the file and the rest are the program's {@code args}, for
         * {@code eval} they are the program.</p>
         *
         * @param argv the arguments
         * @return the request
         * @throws IllegalArgumentException when a flag is unknown or starved
         */
        static Request parse(String[] argv) {
            String command = "";
            String sandbox = null;
            boolean unrestricted = false;
            boolean trace = false;
            boolean help = false;
            List<String> words = new ArrayList<>();
            for (int i = 0; i < argv.length; i++) {
                String at = argv[i];
                switch (at) {
                    case "-h":
                    case "--help":
                        help = true;
                        break;
                    case "-u":
                    case "--unrestricted":
                        unrestricted = true;
                        break;
                    case "-t":
                    case "--trace":
                        trace = true;
                        break;
                    case "-s":
                    case "--sandbox":
                        if (i + 1 >= argv.length) {
                            throw new IllegalArgumentException(
                                    "'" + at + "' needs a directory");
                        }
                        sandbox = argv[++i];
                        break;
                    default:
                        if (at.startsWith("--sandbox=")) {
                            sandbox = at.substring("--sandbox=".length());
                        } else if (at.startsWith("-")) {
                            throw new IllegalArgumentException("unknown option '" + at
                                    + "'; try 'mandela help'");
                        } else if (command.isEmpty()) {
                            command = at;
                        } else {
                            words.add(at);
                        }
                        break;
                }
            }
            return new Request(command, List.copyOf(words), sandbox, unrestricted,
                    trace, help);
        }
    }
}
