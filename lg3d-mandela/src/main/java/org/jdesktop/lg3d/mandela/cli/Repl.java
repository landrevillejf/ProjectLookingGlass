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
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.lang.LangException;
import org.jdesktop.lg3d.mandela.lang.Lexer;
import org.jdesktop.lg3d.mandela.lang.Token;
import org.jdesktop.lg3d.mandela.lang.TokenKind;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * The interactive session: {@code mandela repl}, and the same loop a desktop panel
 * can host.
 *
 * <p>The session is one {@link Runtime} kept across entries, which is the whole
 * point of a REPL &mdash; a {@code let} from the previous line is still a name on
 * the next one, a {@code fun} is still callable, and each entry gets a fresh
 * instruction budget. {@link Runtime#evalEntry} already decides what is worth
 * echoing, so this class only has to decide when an entry is <em>finished</em>.</p>
 *
 * <h2>When a line is not the end of an entry</h2>
 *
 * <p>By counting brackets over the lexer's own tokens. That is the only test that
 * can be right: a brace inside a string or a comment is not a brace, and the lexer
 * has already worked that out. Asking the parser instead would mean reading
 * "unexpected end of input" out of an error message, which is a message a language
 * is free to reword and a REPL would break on.</p>
 *
 * <p>An unterminated string therefore does <em>not</em> open a continuation: the
 * lexer reports it, the session prints the finding and the user is back at the
 * prompt. Write {@code \n} inside a string to build a multi-line value.</p>
 */
final class Repl {

    /** The prompt of a new entry. */
    private static final String PROMPT = "> ";
    /** The prompt while an entry is still open. */
    private static final String MORE = "... ";
    /** The help text, kept with the commands it describes. */
    private static final String HELP = """
              :help              this text
              :vars              the names you have defined
              :type NAME         what kind of value NAME holds
              :call NAME ARGS    call a script function with typed arguments
              :trace             the frames now on the stack
              :clear             start over with an empty session
              :quit              leave (also :exit, and Ctrl-D)
            """;

    private Repl() {
        // Static namespace.
    }

    /**
     * Runs the session until the input ends or the user asks to leave.
     *
     * @param request the command line, for the sandbox and {@code --trace}
     * @param in      the console
     * @param out     where values and replies go
     * @param err     where findings and failures go
     * @return 0 when the session ended with nothing failing, 2 when some entry
     *         failed &mdash; a session reports a typo and keeps going, but it does
     *         not pretend the run was clean
     */
    static int start(MandelaCli.Request request, BufferedReader in,
                     PrintWriter out, PrintWriter err) {
        out.println(Mandela.PROJECT_NAME + " — " + Mandela.LANGUAGE_NAME + " "
                + Mandela.LANGUAGE_VERSION + " session");
        out.println("type :help for the commands, :quit to leave");
        out.print(PROMPT);
        out.flush();

        Session session = new Session(request, out, err);
        StringBuilder entry = new StringBuilder();
        String line;
        while ((line = readLine(in, out)) != null) {
            if (entry.isEmpty() && line.isBlank()) {
                continue;
            }
            String word = line.strip();
            if (entry.isEmpty() && word.startsWith(":")) {
                if (!session.command(word, out, err)) {
                    return session.status;
                }
                out.print(PROMPT);
                out.flush();
                continue;
            }
            if (!entry.isEmpty()) {
                entry.append('\n');
            }
            entry.append(line);
            if (isOpen(entry.toString())) {
                out.print(MORE);
                out.flush();
                continue;
            }
            String source = entry.toString();
            entry.setLength(0);
            session.runOne(source, request, out, err);
            out.print(PROMPT);
            out.flush();
        }
        return session.status;
    }

    /**
     * Reads one line, echoing the prompt a caller asked for on a fresh console.
     *
     * @param in  the input
     * @param out where the prompt goes
     * @return the line, or null at the end of the input
     */
    private static String readLine(BufferedReader in, PrintWriter out) {
        try {
            return in.readLine();
        } catch (java.io.IOException unreadable) {
            out.println("  the console closed: " + unreadable.getMessage());
            return null;
        }
    }

    /**
     * Decides whether an entry is unfinished by counting brackets over its tokens.
     *
     * @param source the entry so far
     * @return true when a delimiter is still open
     */
    static boolean isOpen(String source) {
        int depth;
        try {
            depth = balance(new Lexer(source, "<repl>").tokenize());
        } catch (LangException unreadable) {
            // An unterminated string or a bad character is not an open entry; the
            // run reports it as a finding at the prompt instead of waiting.
            return false;
        }
        return depth > 0;
    }

    /** @return how many delimiters are still open, never negative */
    private static int balance(List<Token> tokens) {
        int depth = 0;
        for (Token token : tokens) {
            TokenKind kind = token.kind();
            if (kind == TokenKind.LBRACE || kind == TokenKind.LBRACKET
                    || kind == TokenKind.LPAREN) {
                depth++;
            } else if (kind == TokenKind.RBRACE || kind == TokenKind.RBRACKET
                    || kind == TokenKind.RPAREN) {
                depth--;
            }
        }
        return Math.max(depth, 0);
    }

    /**
     * The live session: one engine, and the names it started with.
     *
     * <p>Held as an object rather than as locals in {@link #start} because
     * {@code :clear} has to replace the engine and {@code :vars} has to know which
     * names were already there when the user started typing &mdash; reporting the
     * library's hundred-odd globals as the user's own would make the command
     * useless.</p>
     */
    private static final class Session {

        private Runtime runtime;
        private final MandelaCli.Request request;
        private final PrintWriter out;
        private final PrintWriter err;
        private Set<String> baseline;
        private int status;

        Session(MandelaCli.Request request, PrintWriter out, PrintWriter err) {
            this.request = request;
            this.out = out;
            this.err = err;
            start(request, out, err);
        }

        /** Builds the engine and records the names that came with it. */
        private void start(MandelaCli.Request request, PrintWriter out,
                           PrintWriter err) {
            Path root = MandelaCli.sandboxOf(request);
            this.runtime = MandelaCli.engine(request, root, out, err);
            this.baseline = new HashSet<>(runtime.globals().keySet());
        }

        /** Runs one finished entry and echoes what it is worth echoing. */
        void runOne(String source, MandelaCli.Request commandLine,
                    PrintWriter out, PrintWriter err) {
            try {
                Object value = runtime.evalEntry(source, "<repl>");
                if (value != Runtime.REPL_SILENT) {
                    out.println(Values.display(value));
                }
            } catch (MandelaError | LangException failure) {
                MandelaCli.report(failure, runtime, commandLine, err);
                status = 2;
            }
        }

        /** @return true to keep the session open, false when the user left */
        boolean command(String word, PrintWriter out, PrintWriter err) {
            String[] parts = word.split("\\s+", 2);
            String name = parts[0];
            String rest = (parts.length > 1) ? parts[1] : "";
            switch (name) {
                case ":help":
                    out.print(HELP);
                    break;
                case ":quit":
                case ":exit":
                    return false;
                case ":vars":
                    for (Map.Entry<String, Object> entry : userNames().entrySet()) {
                        out.println("  " + entry.getKey() + " : "
                                + Values.typeName(entry.getValue()) + " = "
                                + Values.display(entry.getValue()));
                    }
                    break;
                case ":type":
                    if (rest.isEmpty()) {
                        out.println("  :type needs a name");
                        break;
                    }
                    out.println("  " + rest + " : "
                            + Values.typeName(runtime.get(rest)));
                    break;
                case ":call":
                    call(rest);
                    break;
                case ":trace":
                    List<String> frames = runtime.trace();
                    if (frames.isEmpty()) {
                        out.println("  (nothing on the stack)");
                    }
                    for (String frame : frames) {
                        out.println("  " + frame);
                    }
                    break;
                case ":clear":
                    // A new engine rather than a cleared table: the old one may be
                    // holding a broken name, and a user asking to start over should
                    // not have to guess which names survived.
                    start(request, out, err);
                    out.println("  session cleared");
                    break;
                default:
                    out.println("  unknown command " + name + " — try :help");
                    break;
            }
            return true;
        }

        /**
         * {@code :call NAME arg...} &mdash; the seam a user reaches for when a
         * script defines a function and they want to try it with words.
         *
         * <p>A word that reads as a number is passed as one, and "true"/"false" as
         * a Bool, because {@code :call add 2 3} asking the script to add text to
         * text would be a REPL that fights its user. Everything else stays text,
         * which is what was typed; a script that wants a different form converts it,
         * exactly as it would for a command-line argument.</p>
         */
        private void call(String rest) {
            if (rest.isBlank()) {
                out.println("  :call needs a function name");
                return;
            }
            String[] words = rest.split("\\s+");
            List<Object> args = new ArrayList<>(words.length - 1);
            for (int i = 1; i < words.length; i++) {
                args.add(typed(words[i]));
            }
            try {
                out.println(Values.display(runtime.call(words[0], args.toArray())));
            } catch (MandelaError | LangException failure) {
                MandelaCli.report(failure, runtime, request, err);
                status = 2;
            }
        }

        /** @return the names the user defined, sorted */
        private Map<String, Object> userNames() {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<String, Object> entry : runtime.globals().entrySet()) {
                if (!baseline.contains(entry.getKey())) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
            return out;
        }
    }

    /**
     * Reads one word of a {@code :call} argument list as the value it looks like.
     *
     * <p>Whole numbers become Int, anything with a point or an exponent becomes
     * Double, and a word too wide for a whole number becomes a Double rather than
     * failing &mdash; the user typed a number, and a REPL that answers
     * "that is text" about {@code 99999999999999999999} is only being pedantic.</p>
     *
     * @param word what was typed
     * @return the value to pass
     */
    static Object typed(String word) {
        if ("true".equals(word)) {
            return Boolean.TRUE;
        }
        if ("false".equals(word)) {
            return Boolean.FALSE;
        }
        if ("null".equals(word)) {
            return null;
        }
        try {
            return Long.valueOf(word);
        } catch (NumberFormatException notWhole) {
            // Falls through to the real-number test below.
        }
        try {
            return Double.valueOf(word);
        } catch (NumberFormatException notReal) {
            return word;
        }
    }
}
