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

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Manages and parses changelog files for update history display.
 * Supports Markdown format with automatic parsing and HTML rendering.
 *
 * <p>Two vocabularies are read, because two documents are ever handed to this
 * class:</p>
 * <ul>
 *   <li>the repository's own {@code CHANGELOG.md} — and the per-release
 *       {@code changelog.md} asset the release workflow publishes from a section
 *       of it — which is Keep a Changelog: {@code ## [1.68.0] — 2026-10-11 —
 *       Gradle / JDK 21 modernization} headers over {@code ### Added / Changed /
 *       Removed / Fixed} groups;</li>
 *   <li>the marker vocabulary this class was written against, {@code ## Version
 *       1.2.3 (2026-09-11)} with {@code - [NEW]} / {@code - [FIX]} / {@code -
 *       Breaking change:} bullets, which older release assets still carry.</li>
 * </ul>
 *
 * <p>Reading only the second vocabulary is what made the desktop's
 * <em>What's New</em> show "No changelog available": the header pattern wanted a
 * digit where this repository writes a bracket, so every real section parsed to
 * zero entries and the dialog had nothing to render. The same document already
 * feeds the Help Center's <em>What's New</em> topic (regenerated from
 * {@code CHANGELOG.md} by {@code scripts/mandela/whats-new.mnd}), so both
 * surfaces read one source of truth instead of two hand-kept notes files.</p>
 */
@Slf4j
public class ChangelogManager {

    /** Keep a Changelog header: {@code ## [<version>] — <date> — <description>}. */
    private static final Pattern RELEASE_HEADER_PATTERN =
        Pattern.compile("^##\\s+\\[([^\\]]+)\\](.*)$");

    /** An ISO date anywhere in a header tail: an {@code [Unreleased]} header has none. */
    private static final Pattern DATE_TOKEN_PATTERN =
        Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** The vocabulary older release assets were published in. */
    private static final Pattern LEGACY_HEADER_PATTERN =
        Pattern.compile("^##\\s+(?:Version\\s+)?([\\d.]+)\\s*\\(([^)]+)\\)?");

    /**
     * The three legacy bullet markers. They are matched against the text behind
     * the dash, and they name a bucket outright whichever group holds them.
     */
    private static final Pattern LEGACY_BREAKING_PATTERN =
        Pattern.compile("^(?:Breaking|BREAKING)\\s+(?:change|changes)\\s*:(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_FEATURE_PATTERN =
        Pattern.compile("^(?:\\[NEW\\]|\\[FEATURE\\])\\s*(.*)$");
    private static final Pattern LEGACY_FIX_PATTERN =
        Pattern.compile("^(?:\\[FIX\\]|\\[BUGFIX\\])\\s*(.*)$");

    /**
     * The inline markdown a changelog is written in, for rendering only. A code
     * span is a run of backticks closed by the next run of exactly the same
     * length, so a bullet quoting an unfenced fence writes ` ```mermaid ` and
     * reads as it was typed. Both delimiters are pinned to the start of a run and
     * to nothing but the run, so a longer run reads as the text being quoted
     * rather than as a delimiter, and the unbalanced rest stays as typed.
     */
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern CODE_PATTERN =
        Pattern.compile("(?<!`)(`++)((?:(?!(?<!`)\\1(?!`)).)*?)(?<!`)\\1(?!`)");

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
     * Parse the changelog document into one entry per version header.
     *
     * <p>Both vocabularies named in the class documentation are read, the Keep a
     * Changelog header probed first because it is the one this repository writes.
     * A bullet keeps the raw text its author typed - the getters hand that back
     * unchanged and only the HTML rendering turns markdown spans into tags - and
     * it absorbs the indented lines under it: a wrapped sentence continues with a
     * space, a nested {@code - } item with a semicolon, because the dialog shows
     * a flat list and a sub-bullet dropped on the floor is a change nobody sees.</p>
     *
     * @param content the raw Markdown changelog
     * @return one entry per version header, in document order
     */
    private List<ChangelogEntry> parseMarkdown(String content) {
        List<ChangelogEntry> result = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return result;
        }

        ChangelogEntry current = null;
        String group = "";
        StringBuilder bullet = null;

        for (String line : content.split("\n", -1)) {
            String text = line.trim();

            String version = null;
            String date = "";
            Matcher release = RELEASE_HEADER_PATTERN.matcher(line);
            if (release.find()) {
                version = release.group(1).trim();
                date = firstMatch(DATE_TOKEN_PATTERN, release.group(2));
            } else {
                Matcher legacy = LEGACY_HEADER_PATTERN.matcher(line);
                if (legacy.find()) {
                    version = legacy.group(1).trim();
                    date = legacy.group(2).trim();
                }
            }
            if (version != null) {
                finishBullet(current, group, bullet);
                if (current != null) {
                    result.add(current);
                }
                current = new ChangelogEntry(version, date);
                group = "";
                bullet = null;
                continue;
            }

            // Anything before the first version header is the document's own
            // preamble, not an entry of it.
            if (current == null) {
                continue;
            }

            if (text.startsWith("### ")) {
                finishBullet(current, group, bullet);
                group = text.substring(4).trim();
                bullet = null;
                continue;
            }

            if (text.startsWith("- ")) {
                if (line.startsWith("- ") || bullet == null) {
                    finishBullet(current, group, bullet);
                    bullet = new StringBuilder(text.substring(2).trim());
                } else {
                    bullet.append("; ").append(text.substring(2).trim());
                }
                continue;
            }

            if (bullet != null && line.startsWith(" ") && !text.isEmpty()) {
                bullet.append(' ').append(text);
                continue;
            }

            finishBullet(current, group, bullet);
            bullet = null;
        }

        finishBullet(current, group, bullet);
        if (current != null) {
            result.add(current);
        }
        return result;
    }

    /**
     * File one finished bullet where its group says it belongs: {@code Added} is
     * a feature, {@code Fixed} and {@code Security} are fixes, {@code Deprecated}
     * and {@code Removed} are the changes a reader must act on, and anything else
     * - a group this vocabulary does not name, or no group at all, which is every
     * legacy document - is a plain change. The legacy markers are consulted first
     * so a {@code [FIX]} written inside a {@code ### Added} group still reads as
     * the fix it claims to be.
     */
    private static void finishBullet(ChangelogEntry entry, String group, StringBuilder bullet) {
        if (entry == null || bullet == null) {
            return;
        }
        String text = bullet.toString().trim();
        if (text.isEmpty()) {
            return;
        }

        Matcher breaking = LEGACY_BREAKING_PATTERN.matcher(text);
        if (breaking.find()) {
            entry.addBreakingChange(breaking.group(1).trim());
            return;
        }
        Matcher feature = LEGACY_FEATURE_PATTERN.matcher(text);
        if (feature.find()) {
            entry.addFeature(feature.group(1).trim());
            return;
        }
        Matcher fix = LEGACY_FIX_PATTERN.matcher(text);
        if (fix.find()) {
            entry.addFix(fix.group(1).trim());
            return;
        }

        if ("Added".equalsIgnoreCase(group)) {
            entry.addFeature(text);
        } else if ("Fixed".equalsIgnoreCase(group) || "Security".equalsIgnoreCase(group)) {
            entry.addFix(text);
        } else if ("Deprecated".equalsIgnoreCase(group) || "Removed".equalsIgnoreCase(group)) {
            entry.addBreakingChange(text);
        } else {
            entry.addChange(text);
        }
    }

    /** The first token a pattern matches, or empty text when it matches nothing. */
    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    /** {@code 1.69.0-dev} and {@code 1.69.0-rc.1} are both cut to {@code 1.69.0}. */
    private static String baseVersion(String version) {
        int cut = version.length();
        for (String marker : new String[] {"-", "+"}) {
            int at = version.indexOf(marker);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return version.substring(0, cut);
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * One bullet as HTML: escaped first, then the markdown this changelog is
     * actually written in becomes spans, so a reader sees {@code lg3d.sh} as a
     * code span instead of as two backticks. Links go first, so the label they
     * emit is still plain text for the bold and code passes to pick up, and an
     * unbalanced marker is left exactly as typed rather than swallowing the line.
     */
    private static String inlineHtml(String raw) {
        String out = escapeHtml(raw);
        out = LINK_PATTERN.matcher(out).replaceAll("<a href=\"$2\">$1</a>");
        out = BOLD_PATTERN.matcher(out).replaceAll("<strong>$1</strong>");
        return codeSpans(out);
    }

    /**
     * Code spans, with the padding a quoting author writes taken back out: a span
     * that both starts and ends with a space is holding backticks as its content,
     * and one space on each side is not part of that content.
     */
    private static String codeSpans(String text) {
        Matcher matcher = CODE_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String body = matcher.group(2);
            if (body.length() > 2 && body.startsWith(" ") && body.endsWith(" ")
                && !body.trim().isEmpty()) {
                body = body.substring(1, body.length() - 1);
            }
            matcher.appendReplacement(out,
                Matcher.quoteReplacement("<code>" + body + "</code>"));
        }
        matcher.appendTail(out);
        return out.toString();
    }
    
    /**
     * Get all changelog entries.
     */
    public List<ChangelogEntry> getAllEntries() {
        return Collections.unmodifiableList(entries);
    }
    
    /**
     * Get changelog for a specific version.
     *
     * <p>An exact header wins; failing that, a stored {@code -dev} or {@code -rc.1}
     * suffix answers to the plain version it is a build of, so asking for
     * {@code 1.69.0} still finds the {@code [1.69.0-dev]} section the desktop is
     * about to be updated to. An {@code [Unreleased]} header keeps the literal
     * version {@code Unreleased} - no release number resolves to it, and it is
     * still rendered by {@link #toHtml()}.</p>
     */
    public Optional<ChangelogEntry> getEntry(String version) {
        if (version == null) {
            return Optional.empty();
        }
        Optional<ChangelogEntry> exact = entries.stream()
            .filter(e -> version.equals(e.getVersion()))
            .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        String base = baseVersion(version);
        return entries.stream()
            .filter(e -> base.equals(baseVersion(e.getVersion())))
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
            html.append("<div class='version-header'>Version ").append(escapeHtml(version));
            if (date != null && !date.isEmpty()) {
                html.append(" <span class='date'> (").append(escapeHtml(date)).append(")</span>");
            }
            html.append("</div>");
        
            appendSection(html, "breaking", "⚠️ Breaking Changes", breakingChanges);
            appendSection(html, "feature", "✨ New Features", features);
            appendSection(html, "fix", "🐛 Bug Fixes", fixes);
            appendSection(html, "change", "Changes", changes);
        
            return html.toString();
        }
        
        /** One bucket of the entry as a list, its bullets rendered from markdown. */
        private void appendSection(StringBuilder html, String cssClass, String label, List<String> items) {
            if (items.isEmpty()) {
                return;
            }
            html.append("<div class='").append(cssClass).append("'>").append(label).append(":</div><ul>");
            for (String item : items) {
                html.append("<li class='").append(cssClass).append("'>").append(inlineHtml(item)).append("</li>");
            }
            html.append("</ul>");
        }
    }
}
