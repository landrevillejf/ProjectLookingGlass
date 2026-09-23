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
}
