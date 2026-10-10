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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.webbrowser.ext.BrowserContext;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionManifest;
import org.jdesktop.lg3d.apps.webbrowser.ext.PageContext;
import org.jdesktop.lg3d.apps.webbrowser.ext.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link MandelaUserscriptsExtension}: the {@code @match}
 * header grammar, glob matching, directory discovery, and the real language
 * execution path &mdash; including that a userscript runs in the
 * {@code webPage()} profile, so its only reach is the three names the host binds
 * and a bounded instruction count. The JavaFX engine is never touched; page
 * injection is observed through {@link PageContext}'s script runner.
 */
class MandelaUserscriptsExtensionTest {

    @TempDir
    Path scripts;

    @AfterEach
    void clearOverride() {
        System.clearProperty(MandelaUserscriptsExtension.DIR_PROPERTY);
    }

    private final MandelaUserscriptsExtension ext = new MandelaUserscriptsExtension();

    // -- identity ------------------------------------------------------------

    @Test
    @DisplayName("the extension asks only for the content-script gate")
    void manifestIsMinimal() {
        ExtensionManifest m = ext.manifest();
        assertEquals(MandelaUserscriptsExtension.ID, m.getId());
        assertEquals("Mandela Userscripts", m.getName());
        assertEquals("Project Looking Glass", m.getAuthor());
        assertEquals(Set.of(Permission.CONTENT_SCRIPT), m.getPermissions());
    }

    // -- header --------------------------------------------------------------

    @Test
    @DisplayName("the header block yields its directives, repeatable ones included")
    void parsesHeaderDirectives() {
        String source = """
                #!/usr/bin/env mandela
                // ==MandelaUserScript==
                // @name        Two hosts
                // @match       *://*.example.com/*
                // @match       *://example.org/*
                // @match-      ignored, this key is unknown but still recorded
                // a plain comment
                // ==/MandelaUserScript==
                println(1)
                """;
        var header = MandelaUserscriptsExtension.headerOf(source);
        assertEquals(List.of("Two hosts"), header.get("name"));
        assertEquals(List.of("*://*.example.com/*", "*://example.org/*"),
                header.get("match"));
        assertTrue(header.containsKey("match-"));
    }

    @Test
    @DisplayName("a directive below the code is a comment, not a directive")
    void headerStopsAtTheCode() {
        String source = "println(1)\n// @match *://evil.example/*\n";
        assertFalse(MandelaUserscriptsExtension.headerOf(source).containsKey("match"));
        assertTrue(MandelaUserscriptsExtension.appliesTo(source, "https://anywhere.test/"));
    }

    @Test
    @DisplayName("the declared name wins over the file name")
    void namesTheScript() {
        Path file = Path.of("/home/user/.lg3d/webbrowser/userscripts/x.mnd");
        assertEquals("Hide banners", MandelaUserscriptsExtension
                .nameOf("// @name Hide banners\nprintln(1)", file));
        assertEquals("x.mnd", MandelaUserscriptsExtension.nameOf("println(1)", file));
        assertEquals("script.mnd",
                MandelaUserscriptsExtension.nameOf("println(1)", null));
    }

    // -- matching ------------------------------------------------------------

    @Test
    @DisplayName("no @match means every page, on purpose")
    void unanchoredScriptAppliesEverywhere() {
        assertTrue(MandelaUserscriptsExtension.appliesTo("println(1)", "https://a.test/"));
        assertTrue(MandelaUserscriptsExtension.appliesTo("println(1)", null));
    }

    @Test
    @DisplayName("an @match list decides page by page")
    void matchedScriptAppliesOnlyWhereTold() {
        String source = "// @match *://*.example.com/*\n// @match https://docs.test/guide\n";
        assertTrue(MandelaUserscriptsExtension.appliesTo(source, "https://www.example.com/x"));
        assertTrue(MandelaUserscriptsExtension.appliesTo(source, "http://api.example.com/"));
        assertTrue(MandelaUserscriptsExtension.appliesTo(source, "https://docs.test/guide"));
        assertFalse(MandelaUserscriptsExtension.appliesTo(source, "https://example.com.evil.test/"));
        assertFalse(MandelaUserscriptsExtension.appliesTo(source, "https://docs.test/other"));
        assertFalse(MandelaUserscriptsExtension.appliesTo(source, ""));
    }

    @Test
    @DisplayName("globs escape the dots a domain is made of")
    void dotsAreLiteral() {
        assertFalse(MandelaUserscriptsExtension.matches("https://a.com/*", "https://axcom/1"));
        assertTrue(MandelaUserscriptsExtension.matches("https://a.com/*", "https://a.com/1"));
        assertTrue(MandelaUserscriptsExtension.matches("https://a.co?/x", "https://a.com/x"));
        assertFalse(MandelaUserscriptsExtension.matches("https://a.co?/x", "https://a.con/x/y"));
    }

    @Test
    @DisplayName("a blank or null pattern matches nothing, not everything")
    void degeneratePatternsRefuse() {
        assertFalse(MandelaUserscriptsExtension.matches(null, "https://a.test/"));
        assertFalse(MandelaUserscriptsExtension.matches("   ", "https://a.test/"));
        assertFalse(MandelaUserscriptsExtension.matches("https://a.test/*", null));
    }

    // -- discovery -----------------------------------------------------------

    @Test
    @DisplayName("the userscript directory honours the override property")
    void directoryIsOverridable() throws IOException {
        System.setProperty(MandelaUserscriptsExtension.DIR_PROPERTY, scripts.toString());
        assertEquals(scripts, MandelaUserscriptsExtension.userscriptsDir());

        Files.writeString(scripts.resolve("a.mnd"), "// @name A\nprintln(1)");
        Files.writeString(scripts.resolve("b.txt"), "not a script");
        Files.createDirectory(scripts.resolve("nested"));
        Files.writeString(scripts.resolve("nested/c.mnd"), "println(2)");
        Files.writeString(scripts.resolve("nested/notes.txt"), "ignored");

        List<MandelaUserscriptsExtension.UserScript> found
                = MandelaUserscriptsExtension.discover(MandelaUserscriptsExtension.userscriptsDir());
        assertEquals(1, found.size(), "only top-level .mnd files are userscripts");
        assertEquals("a.mnd", found.get(0).file().getFileName().toString());
        assertEquals("A", found.get(0).name());
        assertEquals("// @name A\nprintln(1)", found.get(0).source());
    }

    @Test
    @DisplayName("scripts run in name order so two authors' code has a stable sequence")
    void discoveryIsOrderedByName() throws IOException {
        Files.writeString(scripts.resolve("zz.mnd"), "println(3)");
        Files.writeString(scripts.resolve("aa.mnd"), "println(1)");
        Files.writeString(scripts.resolve("mm.mnd"), "println(2)");
        List<String> names = MandelaUserscriptsExtension.discover(scripts).stream()
                .map(s -> s.file().getFileName().toString()).toList();
        assertEquals(List.of("aa.mnd", "mm.mnd", "zz.mnd"), names);
    }

    @Test
    @DisplayName("a missing or null directory is no scripts, not a failure")
    void discoveryToleratesAnAbsentDirectory() {
        assertTrue(MandelaUserscriptsExtension.discover(null).isEmpty());
        assertTrue(MandelaUserscriptsExtension
                .discover(scripts.resolve("does-not-exist")).isEmpty());
        assertTrue(MandelaUserscriptsExtension.discover(scripts.resolve("a.mnd")).isEmpty());
    }

    @Test
    @DisplayName("the default directory is under the user's home")
    void defaultDirectoryIsTheBrowserS() {
        System.clearProperty(MandelaUserscriptsExtension.DIR_PROPERTY);
        Path dir = MandelaUserscriptsExtension.userscriptsDir();
        assertTrue(dir.endsWith(Path.of(".lg3d", "webbrowser", "userscripts")), dir.toString());
    }

    // -- execution -----------------------------------------------------------

    @Test
    @DisplayName("a script sees the page and asks for JavaScript")
    void scriptInjectsForThePage() {
        List<String> injected = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String failure = MandelaUserscriptsExtension.run("""
                if (page.url.startsWith("http://")) {
                    inject("location.reload()")
                    note("upgraded " + page.title)
                }
                inject("console.log(1)")
                """, "upgrade.mnd", "http://example.com/", "Example",
                injected::add, notes::add);

        assertEquals("", failure, failure);
        assertEquals(List.of("location.reload()", "console.log(1)"), injected);
        assertEquals(List.of("upgraded Example"), notes);
    }

    @Test
    @DisplayName("println from a script goes to the browser log, not the terminal")
    void scriptConsoleIsTheHostLog() {
        List<String> notes = new ArrayList<>();
        String failure = MandelaUserscriptsExtension.run(
                "println(\"hello\", \"world\")\neprintln(\"oops\")", "p.mnd",
                "https://a.test/", "A", js -> { }, notes::add);
        assertEquals("", failure, failure);
        assertEquals(List.of("hello world", "! oops"), notes);
    }

    @Test
    @DisplayName("a script that fails still injects what it asked for, and says why")
    void partialWorkSurvivesAFailure() {
        List<String> injected = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        String failure = MandelaUserscriptsExtension.run("""
                inject("first()")
                error("this script gave up")
                inject("second()")
                """, "half.mnd", "https://a.test/", "A", injected::add, notes::add);

        assertEquals(List.of("first()"), injected);
        assertTrue(failure.contains("this script gave up"), failure);
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    @DisplayName("a script that does not compile injects nothing")
    void brokenScriptIsRejected() {
        List<String> injected = new ArrayList<>();
        String failure = MandelaUserscriptsExtension.run(
                "// @name broken\nlet x = 1 +\n", "broken.mnd", "https://a.test/", "A",
                injected::add, msg -> { });
        assertFalse(failure.isEmpty());
        assertTrue(failure.contains("broken.mnd"), failure);
        assertTrue(injected.isEmpty(), injected.toString());
    }

    @Test
    @DisplayName("a userscript cannot read the filesystem")
    void sandboxKeepsTheDiskOutOfReach() {
        List<String> injected = new ArrayList<>();
        String failure = MandelaUserscriptsExtension.run("""
                use std.fs
                inject(fs.read("/etc/hostname"))
                """, "snoop.mnd", "https://a.test/", "A", injected::add, msg -> { });
        assertTrue(injected.isEmpty(), injected.toString());
        assertFalse(failure.isEmpty(), "the read must be refused");
    }

    @Test
    @DisplayName("a userscript cannot reach JVM types")
    void sandboxKeepsTheJvmOutOfReach() {
        List<String> injected = new ArrayList<>();
        String failure = MandelaUserscriptsExtension.run(
                "inject(str(java.lang.Runtime))", "escape.mnd", "https://a.test/", "A",
                injected::add, msg -> { });
        assertTrue(injected.isEmpty(), injected.toString());
        assertFalse(failure.isEmpty(), "the class reference must be refused");
    }

    @Test
    @DisplayName("a runaway script is cut off by the instruction budget")
    void scriptsAreBounded() {
        // webPage() allows five million instructions; without that guard this test
        // would never finish, which is exactly the browser freeze it prevents.
        String failure = MandelaUserscriptsExtension.run(
                "var i = 0\nloop {\n  i = i + 1\n}\n", "spin.mnd",
                "https://a.test/", "A", js -> { }, msg -> { });
        assertTrue(failure.contains("instructions"), failure);
    }

    @Test
    @DisplayName("one script cannot see another script's globals")
    void scriptsDoNotShareState() {
        MandelaUserscriptsExtension.run("let secret = \"leaked\"\n", "a.mnd",
                "https://a.test/", "A", js -> { }, msg -> { });
        String failure = MandelaUserscriptsExtension.run("inject(secret)\n", "b.mnd",
                "https://a.test/", "A", js -> { }, msg -> { });
        assertFalse(failure.isEmpty(), "each script gets a fresh engine");
    }

    // -- wiring --------------------------------------------------------------

    /** The broker hands a granted context and a page whose runner records. */
    private static final class Harness {
        final List<String> logs = new ArrayList<>();
        final List<String> pageScripts = new ArrayList<>();
        final List<String> pages = new ArrayList<>();

        BrowserContext ctx(Permission... granted) {
            Set<Permission> set = EnumSet.noneOf(Permission.class);
            set.addAll(List.of(granted));
            return new BrowserContext(set, url -> pages.add("open:" + url),
                    url -> pages.add("nav:" + url), logs::add);
        }

        PageContext page(String url, String title) {
            return new PageContext(url, title, pageScripts::add);
        }
    }

    @Test
    @DisplayName("a loaded page runs the scripts that match it")
    void pageLoadRunsMatchingScripts() throws IOException {
        System.setProperty(MandelaUserscriptsExtension.DIR_PROPERTY, scripts.toString());
        Files.writeString(scripts.resolve("example.mnd"), """
                // @name Example
                // @match *://*.example.com/*
                inject("document.title = '" + page.title + "'")
                note("ran on " + page.url)
                """);
        Files.writeString(scripts.resolve("other.mnd"), """
                // @match *://*.other.test/*
                inject("never()")
                """);

        Harness h = new Harness();
        ext.onBrowserStarted(h.ctx(Permission.CONTENT_SCRIPT));
        ext.onPageLoaded(h.page("https://www.example.com/article", "Hello"));

        assertEquals(List.of("document.title = 'Hello'"), h.pageScripts);
        assertTrue(h.logs.contains("[mandela] ran on https://www.example.com/article"),
                h.logs.toString());
    }

    @Test
    @DisplayName("without CONTENT_SCRIPT no script runs and nothing is injected")
    void pageLoadWithoutPermissionDoesNothing() throws IOException {
        System.setProperty(MandelaUserscriptsExtension.DIR_PROPERTY, scripts.toString());
        Files.writeString(scripts.resolve("boom.mnd"), "inject(\"boom()\")\n");

        Harness h = new Harness();
        ext.onBrowserStarted(h.ctx());   // enabled, but granted nothing
        ext.onPageLoaded(h.page("https://www.example.com/", "Hello"));
        assertTrue(h.pageScripts.isEmpty(), h.pageScripts.toString());
        assertTrue(h.logs.isEmpty(), h.logs.toString());

        // A failing script is reported by name and does not stop the others.
        Files.delete(scripts.resolve("boom.mnd"));   // it matched every page
        ext.onBrowserStarted(h.ctx(Permission.CONTENT_SCRIPT));
        Files.writeString(scripts.resolve("bad.mnd"), "// @name Bad\nnosuchname + 1\n");
        Files.writeString(scripts.resolve("good.mnd"), "// @name Good\ninject(\"ok()\")\n");
        ext.onPageLoaded(h.page("https://www.example.com/", "Hello"));
        assertEquals(List.of("ok()"), h.pageScripts);
        assertTrue(h.logs.stream().anyMatch(l -> l.contains("Bad:")), h.logs.toString());
    }

    @Test
    @DisplayName("hooks without a browser or a page are inert")
    void hooksTolerateMissingPeers() {
        ext.onBrowserStopping(null);
        ext.onPageLoaded(null);
        ext.onPageLoaded(new PageContext("https://a.test/", "A", js -> { }));
        // No onBrowserStarted happened, so there is nothing to inject into.
    }
}
