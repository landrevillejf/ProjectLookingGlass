package com.protonmail.landrevillejf.swingide.update.changelog;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Manages and parses changelog files for update history display.
 * Supports Markdown format with automatic parsing and HTML rendering.
 */
@Slf4j
public class ChangelogManager {
    
    private final Path changelogPath;
    private List<ChangelogEntry> entries;
    
    public ChangelogManager(Path changelogPath) {
        this(changelogPath, null);
    }
    
    private ChangelogManager(Path changelogPath, String inlineContent) {
        this.changelogPath = changelogPath;
        this.entries = new ArrayList<>();
        if (inlineContent != null) {
            this.entries = parseMarkdown(inlineContent);
        } else {
            loadChangelog();
        }
    }
    
    /**
     * Build a manager from Markdown content already fetched, typically the remote
     * changelog downloaded through {@code UpdateRepository.fetchChangelog}.
     *
     * @param markdown the raw Markdown changelog
     * @return a manager holding the parsed entries
     */
    public static ChangelogManager fromMarkdown(String markdown) {
        return new ChangelogManager(null, markdown == null ? "" : markdown);
    }
    
    /**
     * Load and parse changelog from file.
     */
    private void loadChangelog() {
        if (changelogPath == null) {
            log.debug("No changelog file configured");
            return;
        }
        if (!Files.exists(changelogPath)) {
            log.warn("Changelog file not found: {}", changelogPath);
            return;
        }
        
        try {
            String content = Files.readString(changelogPath);
            entries = parseMarkdown(content);
            log.info("Loaded {} changelog entries", entries.size());
        } catch (IOException e) {
            log.error("Failed to load changelog", e);
        }
    }
    
    /**
     * Parse Markdown changelog format.
     * Expected format:
     * ## Version X.Y.Z (YYYY-MM-DD)
     * - Feature/Fix description
     * - Another feature/fix
     * - Breaking change: Description
     */
    private List<ChangelogEntry> parseMarkdown(String content) {
        List<ChangelogEntry> result = new ArrayList<>();
        Pattern versionPattern = Pattern.compile("##\\s+(?:Version\\s+)?([\\d.]+)\\s*\\(([^)]+)\\)?");
        Pattern breakingPattern = Pattern.compile("^\\s*-\\s*(?:Breaking|BREAKING)\\s+(?:change|changes)\\s*:(.*)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        Pattern featurePattern = Pattern.compile("^\\s*-\\s*(?:\\[NEW\\]|\\[FEATURE\\])\\s*(.*)$", Pattern.MULTILINE);
        Pattern fixPattern = Pattern.compile("^\\s*-\\s*(?:\\[FIX\\]|\\[BUGFIX\\])\\s*(.*)$", Pattern.MULTILINE);
        
        String[] lines = content.split("\n");
        ChangelogEntry currentEntry = null;
        
        for (String line : lines) {
            Matcher versionMatcher = versionPattern.matcher(line);
            if (versionMatcher.find()) {
                if (currentEntry != null) {
                    result.add(currentEntry);
                }
                String version = versionMatcher.group(1);
                String date = versionMatcher.group(2);
                currentEntry = new ChangelogEntry(version, date);
                continue;
            }
            
            if (currentEntry == null) {
                continue;
            }
            
            Matcher breakingMatcher = breakingPattern.matcher(line);
            if (breakingMatcher.find()) {
                String description = breakingMatcher.group(1).trim();
                currentEntry.addBreakingChange(description);
                continue;
            }
            
            Matcher featureMatcher = featurePattern.matcher(line);
            if (featureMatcher.find()) {
                String description = featureMatcher.group(1).trim();
                currentEntry.addFeature(description);
                continue;
            }
            
            Matcher fixMatcher = fixPattern.matcher(line);
            if (fixMatcher.find()) {
                String description = fixMatcher.group(1).trim();
                currentEntry.addFix(description);
                continue;
            }
            
            String trimmed = line.trim();
            if (trimmed.startsWith("-") && currentEntry != null && !trimmed.isEmpty()) {
                String description = trimmed.substring(1).trim();
                if (!description.isEmpty()) {
                    currentEntry.addChange(description);
                }
            }
        }
        
        if (currentEntry != null) {
            result.add(currentEntry);
        }
        
        return result;
    }
    
    /**
     * Get all changelog entries.
     */
    public List<ChangelogEntry> getAllEntries() {
        return Collections.unmodifiableList(entries);
    }
    
    /**
     * Get changelog for a specific version.
     */
    public Optional<ChangelogEntry> getEntry(String version) {
        return entries.stream()
            .filter(e -> e.getVersion().equals(version))
            .findFirst();
    }
    
    /**
     * Get the most recent changelog entry.
     */
    public Optional<ChangelogEntry> getLatestEntry() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }
    
    /**
     * Get changelog entries since a specific version.
     */
    public List<ChangelogEntry> getEntriesSince(String version) {
        int startIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getVersion().equals(version)) {
                startIndex = i;
                break;
            }
        }
        
        if (startIndex <= 0) {
            return Collections.emptyList();
        }
        
        return List.copyOf(entries.subList(0, startIndex));
    }
    
    /**
     * Get latest N entries.
     */
    public List<ChangelogEntry> getLatestEntries(int count) {
        return entries.stream()
            .limit(count)
            .toList();
    }
    
    /**
     * Convert changelog to HTML.
     */
    public String toHtml() {
        StringBuilder body = new StringBuilder();
        for (ChangelogEntry entry : entries) {
            body.append(entry.toHtml());
        }
        return wrapHtml(body.toString());
    }
    
    /**
     * Convert a single version entry to a displayable HTML page.
     *
     * @param version the version to render
     * @return the HTML document, empty when the version is unknown
     */
    public Optional<String> getEntryHtml(String version) {
        return getEntry(version).map(entry -> wrapHtml(entry.toHtml()));
    }
    
    private String wrapHtml(String body) {
        return "<html><head><style>"
            + "body { font-family: Arial, sans-serif; margin: 20px; }"
            + ".version-header { font-size: 18px; font-weight: bold; color: #333; margin-top: 20px; margin-bottom: 10px; }"
            + ".date { color: #666; font-size: 12px; }"
            + ".breaking { color: #cc0000; font-weight: bold; }"
            + ".feature { color: #00cc00; }"
            + ".fix { color: #0066cc; }"
            + ".change { color: #333; }"
            + "ul { margin: 5px 0; padding-left: 20px; }"
            + "li { margin: 3px 0; }"
            + "</style></head><body>"
            + body
            + "</body></html>";
    }
    
    /**
     * Reload changelog from file.
     */
    public void reload() {
        loadChangelog();
    }
    
    /**
     * Data class for a changelog entry (version).
     */
    public static class ChangelogEntry {
        private final String version;
        private final String date;
        private final List<String> changes;
        private final List<String> features;
        private final List<String> fixes;
        private final List<String> breakingChanges;
        
        public ChangelogEntry(String version, String date) {
            this.version = version;
            this.date = date;
            this.changes = new ArrayList<>();
            this.features = new ArrayList<>();
            this.fixes = new ArrayList<>();
            this.breakingChanges = new ArrayList<>();
        }
        
        public void addChange(String change) { changes.add(change); }
        public void addFeature(String feature) { features.add(feature); }
        public void addFix(String fix) { fixes.add(fix); }
        public void addBreakingChange(String breaking) { breakingChanges.add(breaking); }
        
        public String getVersion() { return version; }
        public String getDate() { return date; }
        public List<String> getChanges() { return Collections.unmodifiableList(changes); }
        public List<String> getFeatures() { return Collections.unmodifiableList(features); }
        public List<String> getFixes() { return Collections.unmodifiableList(fixes); }
        public List<String> getBreakingChanges() { return Collections.unmodifiableList(breakingChanges); }
        
        public String toHtml() {
            StringBuilder html = new StringBuilder();
            html.append("<div class='version-header'>Version ").append(version)
                .append(" <span class='date'>(").append(date).append(")</span></div>");
            
            if (!breakingChanges.isEmpty()) {
                html.append("<div class='breaking'>⚠️ Breaking Changes:</div><ul>");
                breakingChanges.forEach(bc -> html.append("<li class='breaking'>").append(bc).append("</li>"));
                html.append("</ul>");
            }
            
            if (!features.isEmpty()) {
                html.append("<div class='feature'>✨ New Features:</div><ul>");
                features.forEach(f -> html.append("<li class='feature'>").append(f).append("</li>"));
                html.append("</ul>");
            }
            
            if (!fixes.isEmpty()) {
                html.append("<div class='fix'>🐛 Bug Fixes:</div><ul>");
                fixes.forEach(f -> html.append("<li class='fix'>").append(f).append("</li>"));
                html.append("</ul>");
            }
            
            if (!changes.isEmpty()) {
                html.append("<div class='change'>Changes:</div><ul>");
                changes.forEach(c -> html.append("<li class='change'>").append(c).append("</li>"));
                html.append("</ul>");
            }
            
            return html.toString();
        }
    }
}
