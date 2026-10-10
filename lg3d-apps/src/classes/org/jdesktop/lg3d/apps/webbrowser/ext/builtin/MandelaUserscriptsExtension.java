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
package org.jdesktop.lg3d.apps.webbrowser.ext.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.webbrowser.ext.BrowserContext;
import org.jdesktop.lg3d.apps.webbrowser.ext.BrowserExtension;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionManifest;
import org.jdesktop.lg3d.apps.webbrowser.ext.PageContext;
import org.jdesktop.lg3d.apps.webbrowser.ext.Permission;
import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.HostFunction;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * Mandela userscripts: the Web Browser's side of the desktop scripting language.
 * Every {@code .mnd} file in {@code ~/.lg3d/webbrowser/userscripts} runs against
 * each page that commits, and whatever the script asks to be injected is handed
 * to the page. The user writes a script in the language the desktop speaks rather
 * than in a JavaScript dialect the browser happens to accept.
 *
 * <p><b>The boundary is the point.</b> A userscript is third-party code that the
 * browser executes on the user's behalf, so it runs in
 * {@link Capabilities#webPage()} &mdash; the language's strictest profile: no
 * filesystem, no sockets, no JVM types, a five-million instruction budget and a
 * shallow frame limit. The script's only windows onto the desktop are the names
 * this class binds: {@code page} (a map of {@code url} and {@code title}),
 * {@code inject(js)} (queue JavaScript for the page) and {@code note(msg)} (write
 * to the browser log) &mdash; plus its console, since the web-page profile withholds
 * the stdout grant and {@code println} is therefore re-bound here to land in the
 * same log. Nothing else is reachable, which is what lets this feature
 * ship without a consent dialog beyond the existing
 * {@link Permission#CONTENT_SCRIPT} gate.</p>
 *
 * <p><b>Scripts are host metadata, not grammar.</b> The {@code // @match} header
 * is read here, by the host, exactly as a browser reads a Tampermonkey block.
 * Mandela itself has no opinion about it: strip the header and the file is still
 * a valid Mandela program. That keeps the language free of browser vocabulary.</p>
 *
 * <p><b>A crashing script cannot take the page with it.</b> Each script runs in
 * its own engine (so no globals leak between two authors' code), its failure is
 * logged by name, and the other scripts still run. JavaScript a script asked for
 * before it died is still injected, because the script did ask for it &mdash;
 * half a page change is what the author wrote before the crash.</p>
 *
 * <p>Set {@code -Dlg3d.webbrowser.userscripts} to point the feature at another directory; the
 * browser's own directory is the default, and a missing directory is simply no
 * scripts (it is not an error, and it is not created behind the user's back).</p>
 */
public final class MandelaUserscriptsExtension implements BrowserExtension {

    /** The id in the extension manager and in the services file. */
    static final String ID = "lg3d.mandela.userscripts";

    /** The system property that moves the userscript directory. */
    static final String DIR_PROPERTY = "lg3d.webbrowser.userscripts";

    /** The extension file name, which doubles as the log prefix. */
    static final String FILE_SUFFIX = ".mnd";

    private static final ExtensionManifest MANIFEST = new ExtensionManifest(
            ID,
            "Mandela Userscripts",
            "1.0.0",
            "Runs the Mandela scripts in ~/.lg3d/webbrowser/userscripts against"
                    + " every loaded page and injects what they ask for.",
            "Project Looking Glass",
            Set.of(Permission.CONTENT_SCRIPT));

    /** One script: where it came from, what it is called and what it says. */
    record UserScript(Path file, String name, String source) { }

    private volatile BrowserContext browser;

    @Override
    public ExtensionManifest manifest() {
        return MANIFEST;
    }

    @Override
    public void onBrowserStarted(BrowserContext ctx) {
        this.browser = ctx;
    }

    @Override
    public void onBrowserStopping(BrowserContext ctx) {
        this.browser = null;
    }

    /**
     * Runs every applicable userscript against the page that just committed.
     * Discovery and execution are skipped entirely when CONTENT_SCRIPT was not
     * granted: a script that cannot deliver anything should not be run at all.
     */
    @Override
    public void onPageLoaded(PageContext page) {
        BrowserContext ctx = browser;
        if (page == null || ctx == null || !ctx.has(Permission.CONTENT_SCRIPT)) {
            return;
        }
        List<String> injected = new ArrayList<>();
        Consumer<String> note = message -> ctx.log("[mandela] " + message);
        for (UserScript script : discover(userscriptsDir())) {
            if (!appliesTo(script.source(), page.getUrl())) {
                continue;
            }
            String failure = run(script.source(), script.name(), page.getUrl(),
                    page.getTitle(), injected::add, note);
            if (!failure.isEmpty()) {
                note.accept(script.name() + ": " + failure);
            }
        }
        for (String js : injected) {
            page.executeScript(js);
        }
    }

    // -- discovery -----------------------------------------------------------

    /**
     * @return the directory userscripts are read from, never null
     */
    static Path userscriptsDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        return Path.of(System.getProperty("user.home", "."),
                ".lg3d", "webbrowser", "userscripts");
    }

    /**
     * Reads the {@code .mnd} files in {@code dir}, in name order so two authors'
     * scripts run in a predictable sequence.
     *
     * <p>A directory that does not exist, a file that cannot be read or a name
     * that is not a plain file all reduce to "not there": the browser must open
     * the page whether or not the user has any scripts.</p>
     *
     * @param dir the userscript directory, or null for none
     * @return the scripts, empty when there is nothing to run
     */
    static List<UserScript> discover(Path dir) {
        if (dir == null) {
            return List.of();
        }
        List<UserScript> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(MandelaUserscriptsExtension::isUserscript)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(file -> {
                        try {
                            String source = Files.readString(file);
                            found.add(new UserScript(file, nameOf(source, file), source));
                        } catch (IOException | RuntimeException unreadable) {
                            // Skipped silently: there is no status line to write
                            // to this early, and the next page load retries.
                        }
                    });
        } catch (IOException | SecurityException absent) {
            return List.of();
        }
        return List.copyOf(found);
    }

    private static boolean isUserscript(Path file) {
        try {
            return Files.isRegularFile(file)
                    && file.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(FILE_SUFFIX);
        } catch (SecurityException refused) {
            return false;
        }
    }

    // -- header metadata -----------------------------------------------------

    /**
     * Parses the leading {@code // @key value} block of a userscript.
     *
     * <p>Scanning stops at the first line that is neither blank nor a comment, so
     * a stray {@code // @match} deep in the body is a comment and not a directive
     * &mdash; the same rule every userscript manager applies.</p>
     *
     * @param source the whole file
     * @return the directives, keyed lower-case, in the order they appeared
     */
    static Map<String, List<String>> headerOf(String source) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        for (String raw : source.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#!")) {
                continue;
            }
            if (!line.startsWith("//")) {
                break;
            }
            String body = line.substring(2).strip();
            if (!body.startsWith("@")) {
                continue;
            }
            int space = body.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String key = body.substring(1, space).toLowerCase(Locale.ROOT);
            String value = body.substring(space + 1).strip();
            if (key.isEmpty() || value.isEmpty()) {
                continue;
            }
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    /** @return the script's declared name, or its file name when it declares none. */
    static String nameOf(String source, Path file) {
        List<String> names = headerOf(source).get("name");
        if (names != null && !names.isEmpty()) {
            return names.get(0);
        }
        return (file == null) ? "script" + FILE_SUFFIX : file.getFileName().toString();
    }

    /**
     * @param source the userscript text
     * @param url    the page that just loaded
     * @return true when the script should run on {@code url}: no {@code @match}
     *         at all means the author asked for every page
     */
    static boolean appliesTo(String source, String url) {
        List<String> patterns = headerOf(source).get("match");
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        if (url == null || url.isBlank()) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(pattern, url)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches one glob against one URL. {@code *} is any run of characters and
     * {@code ?} exactly one; everything else, including the dots a domain is made
     * of, is literal. An exact URL is an exact match.
     *
     * @param glob the {@code @match} pattern
     * @param url  the page URL
     * @return true when the pattern covers the URL
     */
    static boolean matches(String glob, String url) {
        if (glob == null || glob.isBlank() || url == null) {
            return false;
        }
        String g = glob.strip();
        String u = url.strip();
        if (g.equals(u)) {
            return true;
        }
        try {
            return Pattern.compile(toRegex(g)).matcher(u).matches();
        } catch (RuntimeException badPattern) {
            return false;
        }
    }

    /** Translates one {@code @match} glob into an anchored regular expression. */
    static String toRegex(String glob) {
        StringBuilder out = new StringBuilder(glob.length() + 8);
        out.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> out.append(".*");
                case '?' -> out.append('.');
                default -> {
                    if ("\\.[]{}()<>=+-!$^|".indexOf(c) >= 0) {
                        out.append('\\');
                    }
                    out.append(c);
                }
            }
        }
        return out.append('$').toString();
    }

    // -- execution -----------------------------------------------------------

    /**
     * Runs one script against one page.
     *
     * <p>The engine is built per call and never shared: two userscripts must not
     * see each other's globals, and one script must not see the page that ran
     * before. The capability profile is {@link Capabilities#webPage()}, which is
     * the reason this method can be handed arbitrary text from disk at all.</p>
     *
     * @param source the script text
     * @param name   the name failures and notes are labelled with
     * @param url    the committed page URL, bound as {@code page.url}
     * @param title  the committed page title, bound as {@code page.title}
     * @param inject receives each JavaScript snippet the script asked for
     * @param note   receives each message the script logged
     * @return why the script failed, or {@code ""} when it ran to completion
     */
    static String run(String source, String name, String url, String title,
                      Consumer<String> inject, Consumer<String> note) {
        List<String> pending = new ArrayList<>();
        Consumer<String> collect = js -> {
            if (js != null && !js.isBlank()) {
                pending.add(js);
            }
        };
        try {
            Mandela.engine()
                    .capabilities(Capabilities.webPage())
                    .bind("page", Map.of("url", text(url), "title", text(title)))
                    .bind("inject", collect)
                    .bind("note", (Consumer<String>) message -> {
                        if (message != null) {
                            note.accept(message);
                        }
                    })
                    // The page profile has no stdout grant, so the console names
                    // are bound rather than granted: an author writes println and
                    // gets the browser log, and no path to the launching terminal
                    // is opened anywhere.
                    .bind("println", console("println", note, ""))
                    .bind("print", console("print", note, ""))
                    .bind("eprintln", console("eprintln", note, "! "))
                    .output(line -> echo(note, line))
                    .error(line -> echo(note, line))
                    .build()
                    .run(source, name);
            return "";
        } catch (RuntimeException failed) {
            String message = String.valueOf(failed.getMessage());
            return message.endsWith("\n") ? message.substring(0, message.length() - 1)
                    : message;
        } finally {
            // Deliberately in the finally: injections the script asked for before
            // it died were still asked for.
            for (String js : pending) {
                inject.accept(js);
            }
        }
    }

    /**
     * One console function, routed to the host log the way the language's own
     * {@code println} joins its arguments (displayed, space separated).
     *
     * @param name   the name the script calls
     * @param note   where the line goes
     * @param prefix what marks an error line in the log
     * @return the callable the engine binds under {@code name}
     */
    private static HostFunction console(String name, Consumer<String> note, String prefix) {
        return HostFunction.of(name, -1, args -> {
            StringBuilder line = new StringBuilder(prefix);
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    line.append(' ');
                }
                line.append(Values.display(args.get(i)));
            }
            note.accept(line.toString());
            return null;
        });
    }

    /** One console line from a script, without the trailing newline the log adds. */
    private static void echo(Consumer<String> note, String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        note.accept(line.endsWith("\n")
                ? line.substring(0, line.length() - 1) : line);
    }

    private static String text(String value) {
        return (value == null) ? "" : value;
    }
}
