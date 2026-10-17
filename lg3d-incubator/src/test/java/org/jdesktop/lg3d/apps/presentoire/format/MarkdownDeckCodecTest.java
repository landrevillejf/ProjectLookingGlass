package org.jdesktop.lg3d.apps.presentoire.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the pure-Java Markdown codec through {@link DeckCodecs}: splitting a
 * single file into slides on thematic breaks and top-level headings, mapping
 * headings / paragraphs / lists / pictures onto the shared model, laying them out
 * inside the square slide, the multi-file "one slide per file" path, and the
 * export-and-re-import round trip. Headless: a text file, a {@code byte[]}
 * picture and the AWT-free model, no rendering.
 */
class MarkdownDeckCodecTest {

    // ------------------------------------------------------------------
    // Splitting one file into slides
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a single .md splits into slides on --- thematic breaks")
    void splitsOnThematicBreaks(@TempDir File dir) throws IOException {
        File md = write(dir, "breaks.md", """
                # One

                first body

                ---

                # Two

                ---

                # Three
                """);
        assertEquals(DeckFormat.MARKDOWN, DeckCodecs.sniff(md), "a .md is known by its extension");
        try (PresentoireModel model = DeckCodecs.open(md)) {
            assertEquals(3, model.getSlideCount());
            assertEquals("One", title(model.getSlide(0)));
            assertEquals("Two", title(model.getSlide(1)));
            assertEquals("Three", title(model.getSlide(2)));
        }
    }

    @Test
    @DisplayName("a single .md also splits on each top-level # heading")
    void splitsOnTopLevelHeadings(@TempDir File dir) throws IOException {
        File md = write(dir, "heads.md", """
                # Alpha
                some text
                # Beta
                more text
                # Gamma
                """);
        try (PresentoireModel model = DeckCodecs.open(md)) {
            assertEquals(3, model.getSlideCount());
            assertEquals("Alpha", title(model.getSlide(0)));
            assertEquals("Beta", title(model.getSlide(1)));
            assertEquals("Gamma", title(model.getSlide(2)));
        }
    }

    // ------------------------------------------------------------------
    // Chunk -> elements
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the first heading is the title and plain lines become body text")
    void mapsHeadingAndParagraphs(@TempDir File dir) throws IOException {
        File md = write(dir, "text.md", """
                # Slide Title

                A paragraph of body text.
                """);
        try (PresentoireModel model = DeckCodecs.open(md)) {
            SlideModel slide = model.getSlide(0);
            List<SlideElement.Text> texts = texts(slide);
            assertEquals(2, texts.size(), "a title block and a paragraph block");
            assertEquals("Slide Title", texts.get(0).text());
            assertEquals(ModelColor.parse("#1A1A1A"), texts.get(0).argb(), "the title ink");
            assertEquals("A paragraph of body text.", texts.get(1).text());
            assertEquals(ModelColor.parse("#333333"), texts.get(1).argb(), "the body ink");
        }
    }

    @Test
    @DisplayName("a sub-heading becomes its own accent-coloured text block")
    void subHeadingsBecomeAccentText(@TempDir File dir) throws IOException {
        File md = write(dir, "sub.md", """
                # Title

                ## Sub-heading
                """);
        try (PresentoireModel model = DeckCodecs.open(md)) {
            List<SlideElement.Text> texts = texts(model.getSlide(0));
            assertEquals(2, texts.size());
            assertEquals("Sub-heading", texts.get(1).text());
            assertEquals(ModelColor.parse("#2F5C9A"), texts.get(1).argb(), "the sub-heading accent");
        }
    }

    @Test
    @DisplayName("bullet items (-, *, +) collect into one list block")
    void mapsBulletList(@TempDir File dir) throws IOException {
        File md = write(dir, "ul.md", """
                # List

                - alpha
                * beta
                + gamma
                """);
        try (PresentoireModel model = DeckCodecs.open(md)) {
            SlideElement.ListElement list = firstList(model.getSlide(0));
            assertNotNull(list, "the bullets became a list");
            assertEquals(List.of("alpha", "beta", "gamma"), list.items());
        }
    }

    @Test
    @DisplayName("numbered items (1. / 2)) collect into one list block")
    void mapsNumberedList(@TempDir File dir) throws IOException {
        File md = write(dir, "ol.md", """
                # Steps

                1. one
                2. two
                3) three
                """);
        try (PresentoireModel model = DeckCodecs.open(md)) {
            SlideElement.ListElement list = firstList(model.getSlide(0));
            assertNotNull(list, "the numbers became a list");
            assertEquals(List.of("one", "two", "three"), list.items());
        }
    }

    @Test
    @DisplayName("inline markup is flattened to plain text")
    void flattensInlineMarkup(@TempDir File dir) throws IOException {
        File md = write(dir, "inline.md",
                "# T\n\n**bold** *it* `code` [t](http://u) ![a](x.png)\n");
        try (PresentoireModel model = DeckCodecs.open(md)) {
            List<SlideElement.Text> texts = texts(model.getSlide(0));
            assertEquals("bold it code t a", texts.get(1).text());
        }
    }

    @Test
    @DisplayName("a fenced code block becomes a body text block, one row per line")
    void fencedCodeBecomesBodyBlock(@TempDir File dir) throws IOException {
        File md = write(dir, "code.md", "# C\n\n```\nline1\nline2\n```\n");
        try (PresentoireModel model = DeckCodecs.open(md)) {
            List<SlideElement.Text> texts = texts(model.getSlide(0));
            assertEquals(2, texts.size(), "a title and a code block");
            assertEquals(List.of("line1", "line2"), List.of(texts.get(1).lines()));
        }
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a picture is read from disk beside the .md into the model")
    void readsImageFromDisk(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF123456, 5, 4);
        Files.write(new File(dir, "pic.png").toPath(), png);
        File md = write(dir, "img.md", "# I\n\n![alt](pic.png)\n");
        try (PresentoireModel model = DeckCodecs.open(md)) {
            SlideElement.Image image = firstImage(model.getSlide(0));
            assertNotNull(image, "the picture imported");
            assertNotNull(model.loadImage(image.src()), "its bytes ride in the in-memory model");
            DeckFixtures.assertImageWithinSlide(image);
        }
    }

    @Test
    @DisplayName("a missing or remote image is skipped without throwing")
    void missingOrRemoteImagesAreSkipped(@TempDir File dir) throws IOException {
        File md = write(dir, "noimg.md",
                "# T\n\n![x](nope.png)\n\n![y](https://example.com/a.png)\n");
        try (PresentoireModel model = DeckCodecs.open(md)) {
            assertEquals(1, model.getSlideCount(), "the slide still parses");
            assertNull(firstImage(model.getSlide(0)), "neither picture was fetched");
            assertEquals("T", title(model.getSlide(0)));
        }
    }

    // ------------------------------------------------------------------
    // Multi-file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("several .md files become one slide each, ordered by name")
    void readAllOneSlidePerFileOrderedByName(@TempDir File dir) throws IOException {
        File second = write(dir, "02-body.md", "# Second\n\ntwo\n");
        File first = write(dir, "01-intro.md", "# First\n\none\n");
        try (PresentoireModel model = DeckCodecs.openMarkdown(new File[] {second, first})) {
            assertEquals(2, model.getSlideCount());
            assertEquals("First", title(model.getSlide(0)), "sorted by name, not by selection order");
            assertEquals("Second", title(model.getSlide(1)));
        }
    }

    @Test
    @DisplayName("a multi-file deck must be all Markdown")
    void readAllRejectsNonMarkdown(@TempDir File dir) throws IOException {
        File md = write(dir, "ok.md", "# T\n");
        File txt = write(dir, "notes.txt", "not markdown");
        assertThrows(IOException.class, () -> DeckCodecs.openMarkdown(new File[] {md, txt}));
    }

    // ------------------------------------------------------------------
    // Edge cases and layout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank file yields no slides")
    void blankFileYieldsNoSlides(@TempDir File dir) throws IOException {
        File md = write(dir, "blank.md", "\n   \n\n");
        try (PresentoireModel model = DeckCodecs.open(md)) {
            assertEquals(0, model.getSlideCount());
        }
    }

    @Test
    @DisplayName("a full slide's elements all land inside the square slide")
    void layoutStaysWithinTheSlide(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF00FF00, 8, 6);
        Files.write(new File(dir, "p.png").toPath(), png);
        File md = write(dir, "full.md", """
                # A reasonably long slide title here

                ## A sub-heading

                A paragraph with enough words that it wraps across more than one
                rendered row inside the body column budget.

                - item one
                - item two
                - item three

                ![pic](p.png)
                """);
        try (PresentoireModel model = DeckCodecs.open(md)) {
            SlideModel slide = model.getSlide(0);
            assertTrue(slide.elements().size() >= 5, "title, sub-head, paragraph, list and image");
            for (SlideElement e : slide.elements()) {
                if (e instanceof SlideElement.Image image) {
                    DeckFixtures.assertImageWithinSlide(image);
                } else {
                    DeckFixtures.assertTextWithinSlide(e);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Export + round trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("export writes headings, --- breaks, list items and image links")
    void exportEmitsMarkdownStructure(@TempDir File dir) throws IOException {
        SlideModel s1 = new SlideModel("a", "", List.of(
                new SlideElement.Text(-0.08f, 0.03f, SlideElement.DEFAULT_Z, 0.16f, 0.03f,
                        ModelColor.parse("#1A1A1A"), "First")));
        SlideModel s2 = new SlideModel("b", "", List.of(
                new SlideElement.Text(-0.08f, 0.03f, SlideElement.DEFAULT_Z, 0.16f, 0.03f,
                        ModelColor.parse("#1A1A1A"), "Second"),
                new SlideElement.ListElement(-0.08f, -0.02f, SlideElement.DEFAULT_Z, 0.16f, 0.05f,
                        ModelColor.BLACK, List.of("x", "y"))));
        File md = new File(dir, "out.md");
        try (PresentoireModel model = PresentoireModel.of(List.of(s1, s2), null, null)) {
            DeckCodecs.export(model, md, DeckFormat.MARKDOWN);
        }
        String text = Files.readString(md.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("# First"), "the first text is a title heading");
        assertTrue(text.contains("---"), "slides are separated by a thematic break");
        assertTrue(text.contains("# Second"), text);
        assertTrue(text.contains("- x"), "list items are bullets");
        assertTrue(text.contains("- y"), text);
    }

    @Test
    @DisplayName("export writes pictures into a sibling .assets folder")
    void exportWritesImageSiblings(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFFFF0000, 3, 3);
        File md = new File(dir, "out.md");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, md, DeckFormat.MARKDOWN);
        }
        File asset = new File(dir, "out.assets/img1.png");
        assertTrue(asset.isFile(), "the picture was written beside the .md");
        String text = Files.readString(md.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("](out.assets/img1.png)"), "and referenced by relative path");
    }

    @Test
    @DisplayName("exporting to .md and re-importing round-trips the content")
    void roundTripsThroughMarkdown(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF0000FF, 4, 4);
        File md = new File(dir, "round.md");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, md, DeckFormat.MARKDOWN);
        }
        try (PresentoireModel read = DeckCodecs.open(md)) {
            assertEquals(1, read.getSlideCount());
            SlideModel slide = read.getSlide(0);
            assertTrue(texts(slide).stream()
                            .anyMatch(t -> t.text().contains(DeckFixtures.TITLE_TEXT)),
                    "the title survived the round trip");
            SlideElement.ListElement list = firstList(slide);
            assertNotNull(list, "the bullet list survived");
            assertEquals(DeckFixtures.BULLETS, list.items());
            SlideElement.Image image = firstImage(slide);
            assertNotNull(image, "the picture survived");
            assertNotNull(read.loadImage(image.src()), "and still decodes from the assets folder");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static File write(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }

    private static String title(SlideModel slide) {
        List<SlideElement.Text> texts = texts(slide);
        return texts.isEmpty() ? null : texts.get(0).text();
    }

    private static List<SlideElement.Text> texts(SlideModel slide) {
        List<SlideElement.Text> found = new ArrayList<>();
        for (SlideElement e : slide.elements()) {
            if (e instanceof SlideElement.Text t) {
                found.add(t);
            }
        }
        return found;
    }

    private static SlideElement.ListElement firstList(SlideModel slide) {
        for (SlideElement e : slide.elements()) {
            if (e instanceof SlideElement.ListElement l) {
                return l;
            }
        }
        return null;
    }

    private static SlideElement.Image firstImage(SlideModel slide) {
        for (SlideElement e : slide.elements()) {
            if (e instanceof SlideElement.Image i) {
                return i;
            }
        }
        return null;
    }
}
