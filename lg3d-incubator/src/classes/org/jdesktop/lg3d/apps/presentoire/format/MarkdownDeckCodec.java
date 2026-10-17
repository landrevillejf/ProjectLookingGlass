/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.presentoire.format;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;

/**
 * Reads and writes slide decks authored as plain Markdown, in pure Java - no
 * Markdown library, just a line scanner and the same AWT-free model every other
 * codec hands the renderers.
 *
 * <p><strong>Import.</strong> A single {@code .md} file is split into slides on
 * the two boundaries the popular slide dialects (Marp, Pandoc, reveal.js) share:
 * a <em>thematic break</em> line ({@code ---}, and the CommonMark {@code ***} /
 * {@code ___} forms) and a <em>top-level</em> {@code # } heading. A whole
 * <em>folder or chosen set</em> of {@code .md} files, read through
 * {@link #readAll(File[])}, instead becomes one slide per file, ordered by name.
 * Within a slide, the first {@code # } heading is the title, {@code ## }..{@code
 * ###### } are sub-headings, a run of plain lines is a paragraph, a run of
 * {@code -}/{@code *}/{@code +}/{@code 1.} items is a bullet list, and a line
 * that is just {@code ![alt](path)} is a picture read from disk beside the
 * {@code .md}. Inline markup ({@code **bold**}, {@code *it*}, {@code `code`},
 * {@code [text](url)}) is flattened to plain text, since the Presentoire
 * vocabulary carries no runs.</p>
 *
 * <p>Because Markdown has no page geometry, this codec lays each slide out
 * itself: a top-down vertical flow in the document's own {@code -0.1 .. 0.1}
 * slide units, honouring {@code SlideRenderer}'s bottom-left anchoring for text
 * and lists and centre anchoring for images, and scaled to fit so nothing
 * overflows the square. Paragraphs are word-wrapped to a column budget (the
 * renderer draws each line on one row and shrinks it to fit, never wrapping),
 * while list items stay on one row each, exactly as the built-in deck does.</p>
 *
 * <p><strong>Export.</strong> A model is written back to {@code .md}: slides
 * separated by {@code ---}, a slide's first text block as its {@code # } title,
 * sub-headed text as {@code ## }, other text as paragraphs, list items as
 * {@code - } bullets, and every picture written once into a sibling
 * {@code <name>.assets/} folder and referenced by relative path - so a deck
 * round-trips. Colours do not survive (Markdown has none); text, structure and
 * pictures do.</p>
 *
 * <p><strong>Fidelity.</strong> As for the office codecs, only text, bullet
 * lists and pictures survive. Tables, reference-style links and images, nested
 * lists (flattened to one level), an image embedded mid-paragraph (reduced to
 * its alt text), remote {@code http(s):} / {@code data:} images (never fetched)
 * and syntax highlighting are all dropped.</p>
 */
final class MarkdownDeckCodec {

    // ------------------------------------------------------------------
    // Slide geometry (slide units; a slide spans -0.1 .. 0.1, Y grows up)
    // ------------------------------------------------------------------

    private static final float SLIDE_HALF = PresentoireModel.SLIDE_HALF;
    private static final float MARGIN_Y = 0.014f;
    private static final float MARGIN_X = 0.012f;
    private static final float TOP = SLIDE_HALF - MARGIN_Y;              //  0.086
    private static final float BOTTOM = -SLIDE_HALF + MARGIN_Y;          // -0.086
    private static final float SPAN = TOP - BOTTOM;                      //  0.172
    private static final float LEFT = -SLIDE_HALF + MARGIN_X;            // -0.088
    private static final float TEXT_W = 2f * (SLIDE_HALF - MARGIN_X);    //  0.176
    private static final float GAP = 0.010f;

    /** Per-row heights, before the fit-to-slide scale is applied. */
    private static final float TITLE_LINE_H = 0.024f;
    private static final float SUBHEAD_LINE_H = 0.020f;
    private static final float BODY_LINE_H = 0.019f;
    private static final float ITEM_H = 0.021f;
    private static final float CODE_LINE_H = 0.017f;

    /** Word-wrap column budgets (the renderer fits each row to the box width). */
    private static final int TITLE_COLS = 40;
    private static final int SUBHEAD_COLS = 48;
    private static final int BODY_COLS = 60;

    /** The image envelope an imported picture is aspect-fit inside, and its fallback box. */
    private static final float IMG_MAX_W = 0.15f;
    private static final float IMG_MAX_H = 0.10f;
    private static final float IMG_DEF_W = 0.12f;
    private static final float IMG_DEF_H = 0.06f;

    /** The palette mirrors the built-in {@code LOOKING_GLASS_XML} deck. */
    private static final int TITLE_ARGB = ModelColor.parse("#1A1A1A");
    private static final int SUBHEAD_ARGB = ModelColor.parse("#2F5C9A");
    private static final int BODY_ARGB = ModelColor.parse("#333333");

    // ------------------------------------------------------------------
    // Line patterns
    // ------------------------------------------------------------------

    private static final Pattern THEMATIC_BREAK =
            Pattern.compile("^\\s{0,3}(-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern H1 = Pattern.compile("^#\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UL_ITEM = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern OL_ITEM = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
    private static final Pattern IMAGE_LINE =
            Pattern.compile("^\\s*!\\[([^\\]]*)\\]\\(([^)]+)\\)\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^\\s*>\\s?(.*)$");

    // ------------------------------------------------------------------
    // Inline patterns (markup flattened to plain text)
    // ------------------------------------------------------------------

    private static final Pattern INLINE_IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]*)`");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern BOLD =
            Pattern.compile("\\*\\*(.+?)\\*\\*|(?<![A-Za-z0-9])__(.+?)__(?![A-Za-z0-9])");
    private static final Pattern ITALIC =
            Pattern.compile("\\*(.+?)\\*|(?<![A-Za-z0-9])_(.+?)_(?![A-Za-z0-9])");

    private MarkdownDeckCodec() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Reads a single Markdown file, splitting it into slides on thematic breaks
     * and top-level headings.
     *
     * @param file the {@code .md} to read
     * @return an in-memory model; a picture named by the slides rides in its map
     * @throws IOException if the file cannot be read
     */
    static PresentoireModel read(File file) throws IOException {
        String text = normalize(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        File baseDir = file.getAbsoluteFile().getParentFile();
        List<Section> sections = new ArrayList<>();
        for (String chunk : splitSlides(text)) {
            sections.add(new Section(chunk, baseDir));
        }
        return build(sections);
    }

    /**
     * Reads a set of Markdown files as one deck, one slide per file, ordered by
     * file name (case-insensitive). Each file's whole text is a single slide - it
     * is <em>not</em> split on {@code ---} - and its pictures resolve against its
     * own directory.
     *
     * @param files the {@code .md} files to read; every one must be Markdown
     * @return an in-memory model carrying every slide's pictures
     * @throws IOException if the set is empty, a file is not Markdown, or one
     *         cannot be read
     */
    static PresentoireModel readAll(File[] files) throws IOException {
        if (files == null || files.length == 0) {
            throw new IOException("No Markdown files to open");
        }
        File[] sorted = files.clone();
        Arrays.sort(sorted, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        List<Section> sections = new ArrayList<>();
        for (File file : sorted) {
            if (DeckFormat.fromExtension(file.getName()) != DeckFormat.MARKDOWN) {
                throw new IOException("Multi-file decks must all be Markdown: " + file.getName());
            }
            String text = normalize(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            sections.add(new Section(text, file.getAbsoluteFile().getParentFile()));
        }
        return build(sections);
    }

    /** Turns each section into one slide, sharing a single image map across them. */
    private static PresentoireModel build(List<Section> sections) {
        List<SlideModel> slides = new ArrayList<>();
        Map<String, byte[]> images = new LinkedHashMap<>();
        for (Section section : sections) {
            SlideModel slide = slideFrom(section, slides.size() + 1, images);
            if (slide != null) {
                slides.add(slide);
            }
        }
        return PresentoireModel.of(slides, null, images);
    }

    /**
     * Splits one file's text into slide chunks. A new chunk starts at a thematic
     * break, or at a top-level {@code # } heading once the current chunk already
     * holds content; blank chunks are dropped, so an empty file yields none.
     */
    private static List<String> splitSlides(String text) {
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (THEMATIC_BREAK.matcher(line).matches()) {
                flush(chunks, current);
            } else if (H1.matcher(line).find() && hasContent(current)) {
                flush(chunks, current);
                current.add(line);
            } else {
                current.add(line);
            }
        }
        flush(chunks, current);
        return chunks;
    }

    private static void flush(List<String> chunks, List<String> current) {
        if (hasContent(current)) {
            chunks.add(String.join("\n", current));
        }
        current.clear();
    }

    private static boolean hasContent(List<String> lines) {
        for (String line : lines) {
            if (!line.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Parses and lays out one section's text; returns null when it holds no blocks. */
    private static SlideModel slideFrom(Section section, int index, Map<String, byte[]> images) {
        int[] counter = {images.size()};
        List<Block> blocks = parseBlocks(section.text, section.baseDir, images, counter);
        if (blocks.isEmpty()) {
            return null;
        }
        float scale = fitScale(blocks);
        List<SlideElement> elements = new ArrayList<>();
        float cursor = TOP;
        String id = null;
        for (Block block : blocks) {
            float h = block.naturalHeight() * scale;
            float bottom = cursor - h;
            if (block.kind == BlockKind.IMAGE) {
                float w = block.imgW * scale;
                elements.add(new SlideElement.Image(0f, bottom + h / 2f,
                        SlideElement.DEFAULT_Z, w, h, block.src));
            } else {
                elements.add(toElement(block, bottom, h));
                if (block.kind == BlockKind.TITLE && id == null) {
                    id = slug(String.join(" ", block.rows));
                }
            }
            cursor = bottom - GAP * scale;
        }
        if (id == null || id.isEmpty()) {
            id = "slide-" + index;
        }
        return new SlideModel(id, "", elements);
    }

    /** The uniform scale that fits every block plus its gaps inside the slide span. */
    private static float fitScale(List<Block> blocks) {
        float total = 0f;
        for (Block block : blocks) {
            total += block.naturalHeight();
        }
        float gaps = GAP * Math.max(0, blocks.size() - 1);
        return Math.min(1f, SPAN / (total + gaps));
    }

    private static SlideElement toElement(Block block, float bottom, float h) {
        if (block.kind == BlockKind.LIST) {
            return new SlideElement.ListElement(LEFT, bottom, SlideElement.DEFAULT_Z,
                    TEXT_W, h, block.argb, Arrays.asList(block.rows));
        }
        return new SlideElement.Text(LEFT, bottom, SlideElement.DEFAULT_Z,
                TEXT_W, h, block.argb, String.join("\n", block.rows));
    }

    /** Scans a chunk's lines into title / sub-heading / paragraph / list / image / code blocks. */
    private static List<Block> parseBlocks(String chunk, File baseDir,
            Map<String, byte[]> images, int[] counter) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = chunk.split("\n", -1);
        boolean titleTaken = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank() || THEMATIC_BREAK.matcher(line).matches()) {
                i++;
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                i = readCode(lines, i, fence.group(1), blocks);
                continue;
            }
            Matcher image = IMAGE_LINE.matcher(line);
            if (image.matches()) {
                Block picture = imageBlock(image.group(2).trim(), baseDir, images, counter);
                if (picture != null) {
                    blocks.add(picture);
                }
                i++;
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.find()) {
                int level = heading.group(1).length();
                String text = flatten(heading.group(2));
                if (!text.isEmpty()) {
                    if (level == 1 && !titleTaken) {
                        titleTaken = true;
                        blocks.add(new Block(BlockKind.TITLE, wrap(text, TITLE_COLS), TITLE_ARGB));
                    } else {
                        blocks.add(new Block(BlockKind.SUBHEAD,
                                wrap(text, SUBHEAD_COLS), SUBHEAD_ARGB));
                    }
                }
                i++;
                continue;
            }
            if (UL_ITEM.matcher(line).find() || OL_ITEM.matcher(line).find()) {
                i = readList(lines, i, blocks);
                continue;
            }
            i = readParagraph(lines, i, blocks);
        }
        return blocks;
    }

    /** Collects a fenced code block's body (unwrapped, one row per source line). */
    private static int readCode(String[] lines, int start, String marker, List<Block> blocks) {
        List<String> code = new ArrayList<>();
        int i = start + 1;
        while (i < lines.length && !lines[i].trim().startsWith(marker)) {
            code.add(lines[i]);
            i++;
        }
        i++; // step over the closing fence, when there is one
        if (!code.isEmpty()) {
            blocks.add(new Block(BlockKind.CODE, code.toArray(new String[0]), BODY_ARGB));
        }
        return i;
    }

    /** Collects a run of bullet or numbered items into one flat list block. */
    private static int readList(String[] lines, int start, List<Block> blocks) {
        List<String> items = new ArrayList<>();
        int i = start;
        while (i < lines.length) {
            String line = lines[i];
            Matcher ul = UL_ITEM.matcher(line);
            Matcher ol = OL_ITEM.matcher(line);
            String item;
            if (ul.find()) {
                item = ul.group(1);
            } else if (ol.find()) {
                item = ol.group(1);
            } else if (!line.isBlank() && !items.isEmpty()) {
                // a wrapped continuation of the previous item
                int last = items.size() - 1;
                items.set(last, items.get(last) + " " + flatten(line));
                i++;
                continue;
            } else {
                break;
            }
            String flat = flatten(item);
            if (!flat.isEmpty()) {
                items.add(flat);
            }
            i++;
        }
        if (!items.isEmpty()) {
            blocks.add(new Block(BlockKind.LIST, items.toArray(new String[0]), BODY_ARGB));
        }
        return i;
    }

    /** Joins a run of plain lines into one paragraph, word-wrapped to the body budget. */
    private static int readParagraph(String[] lines, int start, List<Block> blocks) {
        List<String> para = new ArrayList<>();
        int i = start;
        while (i < lines.length && isParagraphLine(lines[i])) {
            Matcher quote = BLOCKQUOTE.matcher(lines[i]);
            para.add(quote.find() ? quote.group(1).trim() : lines[i].trim());
            i++;
        }
        String joined = flatten(String.join(" ", para));
        if (!joined.isEmpty()) {
            blocks.add(new Block(BlockKind.PARA, wrap(joined, BODY_COLS), BODY_ARGB));
        }
        return i;
    }

    private static boolean isParagraphLine(String line) {
        return !line.isBlank()
                && !THEMATIC_BREAK.matcher(line).matches()
                && !HEADING.matcher(line).find()
                && !UL_ITEM.matcher(line).find()
                && !OL_ITEM.matcher(line).find()
                && !IMAGE_LINE.matcher(line).matches()
                && !FENCE.matcher(line).find();
    }

    /** Reads a picture from disk beside the {@code .md}; a missing or remote one is skipped. */
    private static Block imageBlock(String path, File baseDir,
            Map<String, byte[]> images, int[] counter) {
        String filePart = stripTitleAndBrackets(path);
        File file = resolve(baseDir, filePart);
        if (file == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (IOException | RuntimeException e) {
            return null; // an unreadable picture is skipped, never fatal
        }
        if (bytes.length == 0) {
            return null;
        }
        String src = "img/" + (++counter[0]) + "." + DeckImages.extension(filePart);
        images.put(src, bytes);
        float[] box = aspectBox(bytes);
        return new Block(src, box[0], box[1]);
    }

    /** Drops an optional {@code "title"} and surrounding {@code <...>} from an image target. */
    private static String stripTitleAndBrackets(String path) {
        String p = path.trim();
        int space = p.indexOf(' ');
        if (space > 0) {
            p = p.substring(0, space);
        }
        if (p.length() > 1 && p.startsWith("<") && p.endsWith(">")) {
            p = p.substring(1, p.length() - 1);
        }
        return p.replace("%20", " ");
    }

    /** Resolves a relative image path against the {@code .md}'s directory; null if absent or remote. */
    private static File resolve(File baseDir, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        if (path.contains("://") || path.startsWith("//") || path.startsWith("data:")) {
            return null; // remote or inline-data images are never fetched
        }
        File file = new File(path);
        if (file.isAbsolute()) {
            return file.isFile() ? file : null;
        }
        File resolved = (baseDir == null) ? file : new File(baseDir, path);
        return resolved.isFile() ? resolved : null;
    }

    /** Fits a picture inside the image envelope, preserving its aspect ratio. */
    private static float[] aspectBox(byte[] bytes) {
        int w = 0;
        int h = 0;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null) {
                w = img.getWidth();
                h = img.getHeight();
            }
        } catch (IOException | RuntimeException e) {
            // fall through to the default box
        }
        if (w <= 0 || h <= 0) {
            return new float[] {IMG_DEF_W, IMG_DEF_H};
        }
        float scale = Math.min(IMG_MAX_W / w, IMG_MAX_H / h);
        return new float[] {w * scale, h * scale};
    }

    // ------------------------------------------------------------------
    // Inline text handling
    // ------------------------------------------------------------------

    /** Strips inline Markdown markup, leaving the plain text the model can carry. */
    private static String flatten(String text) {
        if (text == null) {
            return "";
        }
        String out = text.trim();
        if (out.isEmpty()) {
            return "";
        }
        if (out.length() >= 2 && out.charAt(0) == '\\' && isEscapable(out.charAt(1))) {
            out = out.substring(1); // undo the export's leading-control-character escape
        }
        out = INLINE_IMAGE.matcher(out).replaceAll(MarkdownDeckCodec::replacement);
        out = INLINE_LINK.matcher(out).replaceAll(MarkdownDeckCodec::replacement);
        out = CODE_SPAN.matcher(out).replaceAll(MarkdownDeckCodec::replacement);
        out = STRIKE.matcher(out).replaceAll(MarkdownDeckCodec::replacement);
        out = BOLD.matcher(out).replaceAll(MarkdownDeckCodec::replacement);
        out = ITALIC.matcher(out).replaceAll(MarkdownDeckCodec::replacement);
        return out.trim();
    }

    /** The first non-null capture group of a match, quoted for {@code replaceAll}. */
    private static String replacement(MatchResult match) {
        for (int g = 1; g <= match.groupCount(); g++) {
            if (match.group(g) != null) {
                return Matcher.quoteReplacement(match.group(g));
            }
        }
        return Matcher.quoteReplacement(match.group());
    }

    private static boolean isEscapable(char c) {
        return Character.isDigit(c) || "#>-+*.!)".indexOf(c) >= 0;
    }

    /** Greedy word wrap to {@code cols}; a word longer than {@code cols} keeps its own row. */
    private static String[] wrap(String text, int cols) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (line.length() == 0) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= cols) {
                line.append(' ').append(word);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out.isEmpty() ? new String[] {""} : out.toArray(new String[0]);
    }

    private static String slug(String text) {
        String s = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("^-+", "").replaceAll("-+$", "");
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /**
     * Writes a model to a Markdown deck. Pictures are written once each into a
     * sibling {@code <name>.assets/} folder and referenced by relative path, so
     * the file re-imports.
     *
     * @param model  the deck to write; not closed by this call
     * @param target the {@code .md} file to create or overwrite
     * @throws IOException if the file or an image cannot be written
     */
    static void write(PresentoireModel model, File target) throws IOException {
        File assetsDir = new File(target.getAbsoluteFile().getParentFile(),
                baseName(target.getName()) + ".assets");
        Map<String, String> written = new LinkedHashMap<>();
        int[] counter = {0};
        StringBuilder md = new StringBuilder();
        boolean first = true;
        for (SlideModel slide : model.getSlides()) {
            if (!first) {
                md.append("\n---\n\n");
            }
            first = false;
            appendSlide(md, slide, model, assetsDir, written, counter);
        }
        Files.writeString(target.toPath(), md.toString(), StandardCharsets.UTF_8);
    }

    private static void appendSlide(StringBuilder md, SlideModel slide, PresentoireModel model,
            File assetsDir, Map<String, String> written, int[] counter) throws IOException {
        List<String> blocks = new ArrayList<>();
        boolean titleEmitted = false;
        for (SlideElement element : slide.elements()) {
            if (element instanceof SlideElement.Text text) {
                String body = String.join(" ", text.lines()).trim();
                if (body.isEmpty()) {
                    continue;
                }
                if (!titleEmitted) {
                    blocks.add("# " + body);
                    titleEmitted = true;
                } else if (text.argb() == SUBHEAD_ARGB) {
                    blocks.add("## " + body);
                } else {
                    blocks.add(escapeLeading(body));
                }
            } else if (element instanceof SlideElement.ListElement list) {
                StringBuilder items = new StringBuilder();
                for (String item : list.items()) {
                    if (items.length() > 0) {
                        items.append('\n');
                    }
                    items.append("- ").append(item.trim());
                }
                if (items.length() > 0) {
                    blocks.add(items.toString());
                }
            } else if (element instanceof SlideElement.Image image) {
                String rel = writeImage(model, image.src(), assetsDir, written, counter);
                if (rel != null) {
                    blocks.add("![](" + rel + ")");
                }
            }
        }
        md.append(String.join("\n\n", blocks)).append('\n');
    }

    /** Writes one picture into the assets folder (once per {@code src}); returns its relative path. */
    private static String writeImage(PresentoireModel model, String src, File assetsDir,
            Map<String, String> written, int[] counter) throws IOException {
        if (src == null || src.isEmpty()) {
            return null;
        }
        String existing = written.get(src);
        if (existing != null) {
            return existing;
        }
        byte[] bytes = DeckImages.bytes(model, src);
        if (bytes == null) {
            return null;
        }
        if (!assetsDir.isDirectory() && !assetsDir.mkdirs()) {
            return null; // no assets folder, so skip the picture rather than fail the export
        }
        String name = "img" + (++counter[0]) + "." + DeckImages.extension(src);
        Files.write(new File(assetsDir, name).toPath(), bytes);
        String rel = assetsDir.getName() + "/" + name;
        written.put(src, rel);
        return rel;
    }

    /** Backslash-escapes a leading Markdown control character so it re-imports as text. */
    private static String escapeLeading(String body) {
        if (body.isEmpty()) {
            return body;
        }
        char c = body.charAt(0);
        if (c == '#' || c == '>' || c == '-' || c == '+' || c == '*') {
            return "\\" + body;
        }
        return body.matches("^\\d+[.)]\\s.*$") ? "\\" + body : body;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    // ------------------------------------------------------------------
    // Intermediate values
    // ------------------------------------------------------------------

    /** One slide's worth of Markdown text plus the directory its image paths resolve against. */
    private static final class Section {
        final String text;
        final File baseDir;

        Section(String text, File baseDir) {
            this.text = text;
            this.baseDir = baseDir;
        }
    }

    private enum BlockKind {
        TITLE, SUBHEAD, PARA, LIST, CODE, IMAGE
    }

    /** A laid-out block: text rows (or list items) with a colour, or an image with its box. */
    private static final class Block {
        final BlockKind kind;
        final String[] rows;
        final int argb;
        final String src;
        final float imgW;
        final float imgH;

        Block(BlockKind kind, String[] rows, int argb) {
            this.kind = kind;
            this.rows = rows;
            this.argb = argb;
            this.src = null;
            this.imgW = 0f;
            this.imgH = 0f;
        }

        Block(String src, float imgW, float imgH) {
            this.kind = BlockKind.IMAGE;
            this.rows = null;
            this.argb = 0;
            this.src = src;
            this.imgW = imgW;
            this.imgH = imgH;
        }

        float naturalHeight() {
            return switch (kind) {
                case TITLE -> rows.length * TITLE_LINE_H;
                case SUBHEAD -> rows.length * SUBHEAD_LINE_H;
                case CODE -> rows.length * CODE_LINE_H;
                case PARA -> rows.length * BODY_LINE_H;
                case LIST -> rows.length * ITEM_H;
                case IMAGE -> imgH;
            };
        }
    }
}
