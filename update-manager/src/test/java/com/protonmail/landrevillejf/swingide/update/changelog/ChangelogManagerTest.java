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
package com.protonmail.landrevillejf.swingide.update.changelog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ChangelogManagerTest {
    
    private ChangelogManager changelogManager;
    
    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path changelogFile = tempDir.resolve("CHANGELOG.md");
        String content = """
            ## Version 1.2.3 (2026-09-11)
            - [NEW] System tray notifications
            - [FIX] Fixed update verification
            - Breaking change: Update API changed
            
            ## Version 1.2.2 (2026-09-01)
            - [FIX] Memory leak in scheduler
            """;
        
        Files.writeString(changelogFile, content);
        changelogManager = new ChangelogManager(changelogFile);
    }
    
    @Test
    void testChangelogManagerInitialization() {
        assertThat(changelogManager).isNotNull();
    }
    
    @Test
    void testGetAllEntries() {
        List<ChangelogManager.ChangelogEntry> entries = changelogManager.getAllEntries();
        
        assertThat(entries).isNotNull();
        assertThat(entries).isNotEmpty();
        assertThat(entries.get(0).getVersion()).isEqualTo("1.2.3");
    }
    
    @Test
    void testGetEntryByVersion() {
        var entry = changelogManager.getEntry("1.2.3");
        
        assertThat(entry).isPresent();
        assertThat(entry.get().getVersion()).isEqualTo("1.2.3");
    }
    
    @Test
    void testToHtml() {
        String html = changelogManager.toHtml();
        
        assertThat(html).isNotNull();
        assertThat(html).contains("1.2.3");
        assertThat(html).contains("html");
    }
    
    @Test
    void testEntriesAreClassified() {
        ChangelogManager.ChangelogEntry entry = changelogManager.getEntry("1.2.3").orElseThrow();
        
        assertThat(entry.getDate()).isEqualTo("2026-09-11");
        assertThat(entry.getFeatures()).containsExactly("System tray notifications");
        assertThat(entry.getFixes()).containsExactly("Fixed update verification");
        assertThat(entry.getBreakingChanges()).containsExactly("Update API changed");
    }
    
    @Test
    void testToHtmlRendersEverySection() {
        String html = changelogManager.toHtml();
        
        assertThat(html).startsWith("<html>").endsWith("</html>");
        assertThat(html).contains("Breaking Changes").contains("New Features").contains("Bug Fixes");
        assertThat(html).contains("1.2.2");
    }
    
    @Test
    void testGetLatestEntry() {
        assertThat(changelogManager.getLatestEntry())
            .isPresent()
            .get()
            .extracting(ChangelogManager.ChangelogEntry::getVersion)
            .isEqualTo("1.2.3");
    }
    
    @Test
    void testGetEntryHtmlForKnownVersion() {
        var html = changelogManager.getEntryHtml("1.2.2");
        
        assertThat(html).isPresent();
        assertThat(html.get()).startsWith("<html>").endsWith("</html>");
        assertThat(html.get()).contains("1.2.2").contains("Memory leak in scheduler");
        assertThat(html.get()).doesNotContain("System tray notifications");
    }
    
    @Test
    void testGetEntryHtmlForUnknownVersion() {
        assertThat(changelogManager.getEntryHtml("9.9.9")).isEmpty();
    }
    
    @Test
    void testGetEntriesSince() {
        assertThat(changelogManager.getEntriesSince("1.2.3")).isEmpty();
        assertThat(changelogManager.getEntriesSince("1.2.2"))
            .extracting(ChangelogManager.ChangelogEntry::getVersion)
            .containsExactly("1.2.3");
        assertThat(changelogManager.getEntriesSince("unknown")).isEmpty();
    }
    
    @Test
    void testGetLatestEntries() {
        assertThat(changelogManager.getLatestEntries(1))
            .extracting(ChangelogManager.ChangelogEntry::getVersion)
            .containsExactly("1.2.3");
        assertThat(changelogManager.getLatestEntries(10)).hasSize(2);
    }
    
    @Test
    void testGetAllEntriesIsImmutable() {
        List<ChangelogManager.ChangelogEntry> entries = changelogManager.getAllEntries();
        
        assertThatThrownBy(() -> entries.add(new ChangelogManager.ChangelogEntry("9.9.9", "today")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
    
    @Test
    void testFromMarkdownParsesInlineContent() {
        ChangelogManager manager = ChangelogManager.fromMarkdown("""
            ## Version 2.0.0 (2026-01-01)
            - [NEW] Remote changelog rendering
            """);
        
        assertThat(manager.getAllEntries()).hasSize(1);
        assertThat(manager.getEntryHtml("2.0.0"))
            .isPresent()
            .get()
            .asString()
            .contains("Remote changelog rendering");
    }
    
    @Test
    void testFromMarkdownHandlesNullAndEmpty() {
        assertThat(ChangelogManager.fromMarkdown(null).getAllEntries()).isEmpty();
        assertThat(ChangelogManager.fromMarkdown("").getAllEntries()).isEmpty();
        assertThat(ChangelogManager.fromMarkdown(null).getLatestEntry()).isEmpty();
    }
    
    @Test
    void testMissingFileYieldsNoEntries(@TempDir Path tempDir) {
        ChangelogManager manager = new ChangelogManager(tempDir.resolve("missing.md"));
        
        assertThat(manager.getAllEntries()).isEmpty();
        assertThat(manager.getLatestEntry()).isEmpty();
        assertThat(manager.toHtml()).startsWith("<html>");
    }
    
    @Test
    void testReloadPicksUpNewContent(@TempDir Path tempDir) throws Exception {
        Path changelogFile = tempDir.resolve("CHANGELOG.md");
        Files.writeString(changelogFile, "## Version 3.0.0 (2026-02-02)\n- [NEW] First entry\n");
        
        ChangelogManager manager = new ChangelogManager(changelogFile);
        assertThat(manager.getAllEntries()).hasSize(1);
        
        Files.writeString(changelogFile, """
            ## Version 3.1.0 (2026-03-03)
            - [NEW] Second entry
            
            ## Version 3.0.0 (2026-02-02)
            - [NEW] First entry
            """);
        manager.reload();
        
        assertThat(manager.getAllEntries()).hasSize(2);
        assertThat(manager.getLatestEntry())
            .isPresent()
            .get()
            .extracting(ChangelogManager.ChangelogEntry::getVersion)
            .isEqualTo("3.1.0");
    }

    // -----------------------------------------------------------------------
    // The vocabulary the repository actually writes.
    //
    // CHANGELOG.md - and the per-release changelog.md asset published from one of
    // its sections - is Keep a Changelog, while the fixture above is the marker
    // vocabulary this class was originally written against. Reading only the
    // markers is what made the desktop's What's New answer "No changelog
    // available": the header pattern wanted a digit where the changelog writes a
    // bracket, so every real section parsed to zero entries. The excerpt below is
    // a faithful copy of the shipped format, inline rather than read from the
    // repository, because a module's tests must not depend on its neighbour's
    // working tree.
    // -----------------------------------------------------------------------

    private static final String KEEP_A_CHANGELOG = """
        # Changelog

        All notable changes to this **modernization port** of Project Looking Glass are
        documented here.

        ## [Unreleased] — 1.69.0-dev — Gradle / JDK 21 modernization

        ### Added
        - **Wonderland reaches the desktop — the M6c2 integration, in 2D and 3D
          (`wonderland-desktop` + `lg3d-apps` + `lg3d-core`)** — the milestone this
          port had been reporting as "awaits re-confirmation".

        ## [1.68.0] — 2026-10-11 — Gradle / JDK 21 modernization

        ### Fixed
        - **Mermaid diagrams now render on the Pages documentation site** (`documentation/`) —
          the portal linked its cards straight to the `.md` files, and GitHub Pages
          serves a `.md` as plain text.
        - **Espresso tabs are closable from the tab header** (`lg3d-apps`) — every
          editor tab now carries a small **×** button on its title strip.

        ### Added
        - **Screen saver for the 2D desktop** (`lg3d-core`, `lg3d-apps`) — an idle
          screen saver, configurable in **Control Center → Screen Saver**.
        - **`html-links.mnd` — a fourth Mandela gate** (`scripts/mandela`) — see
          [the guide](https://keepachangelog.com/).

        ### Removed
        - **The JMF capture path** — the unrecoverable `javax.media` dependency is gone.

        ### Known non-fatal runtime messages
        - A `HeadlessException` on a CI run is expected.
        """;

    @Test
    void testKeepAChangelogSectionsAreParsed() {
        ChangelogManager manager = ChangelogManager.fromMarkdown(KEEP_A_CHANGELOG);

        assertThat(manager.getAllEntries()).isNotEmpty();
        assertThat(manager.getAllEntries())
            .extracting(ChangelogManager.ChangelogEntry::getVersion)
            .containsExactly("Unreleased", "1.68.0");
        assertThat(manager.getEntry("1.68.0"))
            .isPresent()
            .get()
            .extracting(ChangelogManager.ChangelogEntry::getDate)
            .isEqualTo("2026-10-11");
    }

    @Test
    void testKeepAChangelogGroupsFillTheBuckets() {
        ChangelogManager.ChangelogEntry entry =
            ChangelogManager.fromMarkdown(KEEP_A_CHANGELOG).getEntry("1.68.0").orElseThrow();

        assertThat(entry.getFixes()).hasSize(2);
        assertThat(entry.getFeatures()).hasSize(2);
        assertThat(entry.getBreakingChanges())
            .anyMatch(item -> item.contains("The JMF capture path"));
        assertThat(entry.getChanges())
            .anyMatch(item -> item.contains("HeadlessException"));
    }

    @Test
    void testUnreleasedSectionIsNeitherRenderedNorResolved() {
        ChangelogManager manager = ChangelogManager.fromMarkdown(KEEP_A_CHANGELOG);

        // The header names itself Unreleased, so no release number resolves to it -
        // a user must not be shown work that has not shipped as their own upgrade -
        // and the "what's new" listing drops it for the same reason: unreleased work
        // is not new in any build the reader is running. The shipped release below it
        // is still rendered, and the raw data still carries both halves.
        assertThat(manager.getEntry("1.69.0-dev")).isEmpty();
        assertThat(manager.getEntry("1.69.0")).isEmpty();
        assertThat(manager.toHtml()).doesNotContain("Unreleased").contains("1.68.0");
        assertThat(manager.getAllEntries())
            .extracting(ChangelogManager.ChangelogEntry::getVersion)
            .contains("Unreleased");
    }

    @Test
    void testABulletKeepsItsWrappedNarrative() {
        ChangelogManager.ChangelogEntry entry =
            ChangelogManager.fromMarkdown(KEEP_A_CHANGELOG).getEntry("1.68.0").orElseThrow();

        // Keep a Changelog wraps a bullet at two spaces; dropping those lines would
        // hand the reader a headline and nothing that explains it.
        assertThat(entry.getFixes().get(0))
            .contains("Mermaid diagrams now render")
            .contains("serves a `.md` as plain text");
    }

    @Test
    void testAnestedBulletBelongsToItsParent() {
        ChangelogManager manager = ChangelogManager.fromMarkdown("""
            ## [1.71.0] — 2026-11-05 — Gradle / JDK 21 modernization

            ### Added
            - **A parent bullet** — the change itself
              - the first detail of that change
              - and its second detail
            """);

        assertThat(manager.getAllEntries()).hasSize(1);
        ChangelogManager.ChangelogEntry entry = manager.getEntry("1.71.0").orElseThrow();
        assertThat(entry.getFeatures()).hasSize(1);
        assertThat(entry.getFeatures().get(0))
            .contains("first detail")
            .contains("second detail");
    }

    @Test
    void testInlineMarkdownIsRenderedAsSpans() {
        String html = ChangelogManager.fromMarkdown(KEEP_A_CHANGELOG).getEntryHtml("1.68.0")
            .orElseThrow();

        assertThat(html)
            .contains("<strong>Screen saver for the 2D desktop</strong>")
            .contains("<code>html-links.mnd</code>")
            .contains("<a href=\"https://keepachangelog.com/\">the guide</a>");
        assertThat(html).doesNotContain("**").doesNotContain("`");
    }

    @Test
    void testMarkupIsEscapedInTheHtmlAndKeptInTheGetters() {
        ChangelogManager manager = ChangelogManager.fromMarkdown("""
            ## [1.72.0] — 2026-11-06 — Gradle / JDK 21 modernization

            ### Fixed
            - A bullet quoting <script>alert("x")</script> renders as text
            """);

        ChangelogManager.ChangelogEntry entry = manager.getEntry("1.72.0").orElseThrow();
        assertThat(entry.getFixes().get(0)).contains("<script>");

        String html = manager.getEntryHtml("1.72.0").orElseThrow();
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void testAPreleaseHeaderAnswersToItsPlainVersion() {
        ChangelogManager manager = ChangelogManager.fromMarkdown("""
            ## [1.69.0-rc.1] — 2026-10-20 — Gradle / JDK 21 modernization

            ### Fixed
            - A release-candidate fix
            """);

        // The desktop compares release numbers, so the suffix is matched both ways:
        // the header keeps it verbatim, and the base version still finds it.
        assertThat(manager.getEntry("1.69.0-rc.1")).isPresent();
        assertThat(manager.getEntry("1.69.0")).isPresent();
        assertThat(manager.getEntry("1.68.0")).isEmpty();
    }

    @Test
    void testAQuotedFenceDoesNotOpenACodeSpan() {
        ChangelogManager manager = ChangelogManager.fromMarkdown("""
            ## [1.74.0] — 2026-11-08 — Gradle / JDK 21 modernization

            ### Fixed
            - The portal served an unfenced ` ```mermaid ` fence as plain text
            """);

        // The changelog quotes markdown at itself. Closing a span on any backtick
        // turns the three that are being quoted into a delimiter and renders half
        // the paragraph in a monospace block, so a run only opens and only closes
        // a run of exactly its own length.
        String html = manager.getEntryHtml("1.74.0").orElseThrow();
        assertThat(html)
            .contains("<code>```mermaid</code> fence as plain text")
            .doesNotContain("<code> fence as plain text");
    }

    @Test
    void testCodeSpanPaddingIsNotContent() {
        ChangelogManager manager = ChangelogManager.fromMarkdown("""
            ## [1.75.0] — 2026-11-09 — Gradle / JDK 21 modernization

            ### Fixed
            - A space on each side is how a writer puts a backtick inside ` code `
            """);

        assertThat(manager.getEntryHtml("1.75.0").orElseThrow()).contains("<code>code</code>");
    }

    @Test
    void testLegacyMarkersWinInsideAGroup() {
        ChangelogManager manager = ChangelogManager.fromMarkdown("""
            ## [1.73.0] — 2026-11-07 — Gradle / JDK 21 modernization

            ### Added
            - [FIX] A fix written in the old vocabulary
            """);

        ChangelogManager.ChangelogEntry entry = manager.getEntry("1.73.0").orElseThrow();
        assertThat(entry.getFixes()).containsExactly("A fix written in the old vocabulary");
        assertThat(entry.getFeatures()).isEmpty();
    }

    @Test
    void testBothVocabulariesCoexistInOneDocument() {
        ChangelogManager manager = ChangelogManager.fromMarkdown("""
            ## [1.68.0] — 2026-10-11 — Gradle / JDK 21 modernization

            ### Added
            - **A section in the new vocabulary** — its narrative

            ## Version 1.2.3 (2026-09-11)
            - [NEW] System tray notifications
            """);

        assertThat(manager.getAllEntries())
            .extracting(ChangelogManager.ChangelogEntry::getVersion)
            .containsExactly("1.68.0", "1.2.3");
        assertThat(manager.getEntry("1.68.0").orElseThrow().getFeatures()).hasSize(1);
        assertThat(manager.getEntry("1.2.3").orElseThrow().getFeatures())
            .containsExactly("System tray notifications");
    }
}
