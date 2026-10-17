package org.jdesktop.lg3d.apps.presentoire.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the AWT-free Presentoire model seam: the pure {@link ModelColor} parser,
 * the {@link PresentoireModel} XML/ZIP reader (both the built-in demo and a real
 * archive round-trip), the immutable {@link SlideModel}/{@link SlideElement}
 * values, and the JDK 21 fix that a JavaScript {@code <transition>} parses to
 * data instead of throwing. Every case is headless - no display, no Java 3D.
 */
class PresentoireModelTest {

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // ModelColor
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ModelColor parses names, #RRGGBB and #RRGGBBAA, degrading to black")
    void colorParsing() {
        assertEquals(ModelColor.BLACK, ModelColor.parse(null));
        assertEquals(ModelColor.BLACK, ModelColor.parse("   "));
        assertEquals(ModelColor.BLACK, ModelColor.parse("not-a-color"));
        assertEquals(ModelColor.WHITE, ModelColor.parse("white"));
        assertEquals(ModelColor.WHITE, ModelColor.parse("WHITE"));
        assertEquals(ModelColor.RED, ModelColor.parse("red"));
        assertEquals(0xFF808080, ModelColor.parse("gray"));
        assertEquals(0xFF808080, ModelColor.parse("grey"));

        assertEquals(0xFFFFFFFF, ModelColor.parse("#FFFFFF"));
        assertEquals(0xFF1A1A1A, ModelColor.parse("#1A1A1A"));
        // #RRGGBBAA: alpha is the LAST byte in the Presentoire format.
        assertEquals(0x80FF0000, ModelColor.parse("#FF000080"));
        // A malformed hex string degrades to opaque black rather than throwing.
        assertEquals(ModelColor.BLACK, ModelColor.parse("#ZZZZZZ"));
        assertEquals(ModelColor.BLACK, ModelColor.parse("#12345"));
    }

    @Test
    @DisplayName("ModelColor channel accessors round-trip a packed ARGB int")
    void colorChannels() {
        int argb = ModelColor.argb(0x11, 0x22, 0x33, 0x44);
        assertEquals(0x11, ModelColor.alpha(argb));
        assertEquals(0x22, ModelColor.red(argb));
        assertEquals(0x33, ModelColor.green(argb));
        assertEquals(0x44, ModelColor.blue(argb));
        assertEquals(1.0f, ModelColor.alphaFloat(ModelColor.WHITE), 1e-6f);
        assertEquals(1.0f, ModelColor.redFloat(ModelColor.RED), 1e-6f);
        assertEquals(0.0f, ModelColor.greenFloat(ModelColor.RED), 1e-6f);
        // The /255 fix: a mid channel is exactly 0.5, not the original /254 drift.
        assertEquals(128 / 255f, ModelColor.redFloat(ModelColor.parse("#808080")), 1e-6f);
    }

    @Test
    @DisplayName("ModelColor.toHex formats opaque #RRGGBB and translucent #RRGGBBAA")
    void colorToHex() {
        // A fully opaque colour drops the alpha byte.
        assertEquals("#FF0000", ModelColor.toHex(ModelColor.RED));
        assertEquals("#FFFFFF", ModelColor.toHex(ModelColor.WHITE));
        assertEquals("#000000", ModelColor.toHex(ModelColor.BLACK));
        assertEquals("#1A1A1A", ModelColor.toHex(0xFF1A1A1A));
        // A translucent colour keeps the alpha-last byte the parser reads back.
        assertEquals("#FF000080", ModelColor.toHex(0x80FF0000));
        assertEquals("#1A1A1A80", ModelColor.toHex(0x801A1A1A));
        // toHex is the exact inverse of parse for both forms.
        assertEquals(0xFF1A1A1A, ModelColor.parse(ModelColor.toHex(0xFF1A1A1A)));
        assertEquals(0x80FF0000, ModelColor.parse(ModelColor.toHex(0x80FF0000)));
    }

    // ------------------------------------------------------------------
    // Built-in demo deck
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the built-in demo deck is the complete Looking Glass 3D/2D tour")
    void demoDeck() {
        try (PresentoireModel model = PresentoireModel.demo()) {
            assertEquals(16, model.getSlideCount(), "the LG deck is a full tour");
            assertFalse(model.hasArchive(), "the demo holds no ZIP");
            assertNull(model.loadImage("anything.png"), "no archive means no image");
            assertNull(model.openEntry("anything"));

            // The tour covers both desktops: some slide names 3D, another 2D.
            String all = allText(model);
            assertTrue(all.contains("3D"), "the deck presents the 3D desktop");
            assertTrue(all.contains("2D"), "and the 2D/Swing desktop");
            assertTrue(all.contains("scene graph"), "the 3D scene graph is described");
            assertTrue(all.contains("JDesktopPane"), "the 2D MDI desktop is described");

            // The closing slide carries this port's attribution.
            assertTrue(all.contains("Jean-Francois Landreville"),
                    "the attribution slide names the porter");

            // The deck opens on its title and closes on the credits.
            assertEquals("title", model.getSlide(0).id());
            assertEquals("credits", model.getSlide(model.getSlideCount() - 1).id());
            assertFalse(model.getSlide(0).hasTransition());
        }
    }

    /** Concatenates every text block and bullet item in the deck, for scanning. */
    private static String allText(PresentoireModel model) {
        StringBuilder sb = new StringBuilder();
        for (SlideModel slide : model.getSlides()) {
            for (SlideElement element : slide.elements()) {
                if (element instanceof SlideElement.Text text) {
                    sb.append(text.text()).append('\n');
                } else if (element instanceof SlideElement.ListElement list) {
                    for (String item : list.items()) {
                        sb.append(item).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("a SlideElement.Text keeps trailing empty lines and never null text")
    void textLines() {
        SlideElement.Text text =
                new SlideElement.Text(0f, 0f, SlideElement.DEFAULT_Z, 0.1f, 0.1f,
                        ModelColor.BLACK, "a\nb\n");
        assertEquals(3, text.lines().length, "-1 split keeps the trailing empty line");
        assertEquals(SlideElement.Kind.TEXT, text.kind());
        assertEquals(0.01f, text.z(), 1e-6f);
        // A null src normalises to empty and the kind is IMAGE.
        SlideElement.Image image = new SlideElement.Image(0f, 0f, 0f, 0f, 0f, null);
        assertEquals("", image.src());
        assertEquals(SlideElement.Kind.IMAGE, image.kind());
        // A null text normalises to empty.
        assertEquals("", new SlideElement.Text(0f, 0f, 0f, 0f, 0f, 0, null).text());
    }

    // ------------------------------------------------------------------
    // Parsing a supplied deck
    // ------------------------------------------------------------------

    @Test
    @DisplayName("open(stream) parses slides, elements and geometry from XML")
    void parsesSuppliedXml() throws IOException {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <document>
                  <slide id="s1" transition="rotation">
                    <text width="0.10" height="0.02" x="-0.05" y="0.04" color="#FF0000">Red</text>
                    <img width="0.05" height="0.05" x="0.0" y="0.0" src="pic.png"/>
                    <ul width="0.10" height="0.06" x="-0.05" y="0.0" color="blue">
                      <li>one</li>
                      <li>two</li>
                    </ul>
                    <unknown/>
                  </slide>
                </document>
                """;
        try (PresentoireModel model = PresentoireModel.open(stream(xml))) {
            assertEquals(1, model.getSlideCount());
            SlideModel slide = model.getSlide(0);
            assertEquals("s1", slide.id());
            assertTrue(slide.hasTransition());
            assertEquals("rotation", slide.transition());
            // The <unknown/> node is dropped; text, img and ul survive.
            assertEquals(3, slide.elements().size());

            SlideElement.Text text = (SlideElement.Text) slide.elements().get(0);
            assertEquals("Red", text.text());
            assertEquals(ModelColor.RED, text.argb());
            assertEquals(-0.05f, text.x(), 1e-6f);
            assertEquals(SlideElement.DEFAULT_Z, text.z(), 1e-6f, "z defaults to 0.01");

            SlideElement.Image img = (SlideElement.Image) slide.elements().get(1);
            assertEquals("pic.png", img.src());

            SlideElement.ListElement ul = (SlideElement.ListElement) slide.elements().get(2);
            assertEquals(ModelColor.BLUE, ul.argb());
            assertEquals(List.of("one", "two"), ul.items());
        }
    }

    @Test
    @DisplayName("a colorless element defaults to black and a missing attr to 0")
    void defaultsAreSafe() throws IOException {
        String xml = "<document><slide><text>plain</text></slide></document>";
        try (PresentoireModel model = PresentoireModel.open(stream(xml))) {
            SlideElement.Text text = (SlideElement.Text) model.getSlide(0).elements().get(0);
            assertEquals(ModelColor.BLACK, text.argb());
            assertEquals(0f, text.width(), 1e-6f);
            assertEquals("plain", text.text());
        }
    }

    @Test
    @DisplayName("a JavaScript <transition> parses to data on JDK 21 instead of throwing")
    void scriptTransitionParsesWithoutAnEngine() throws IOException {
        // This is the exact shape that made the 2006 original throw a
        // RuntimeException at parse time on any JVM without Nashorn (JDK 15+).
        String xml = """
                <document>
                  <transition id="fade" language="JavaScript">
                    function before(a,b,c){} function after(a,b,c){}
                  </transition>
                  <slide id="s" transition="fade"><text>x</text></slide>
                </document>
                """;
        try (PresentoireModel model = PresentoireModel.open(stream(xml))) {
            ScriptTransitionModel fade = model.getScriptTransition("fade");
            assertNotNull(fade, "the deck opens; the script is parsed, not executed");
            assertEquals("fade", fade.id());
            assertEquals("JavaScript", fade.language());
            assertTrue(fade.code().contains("function before"));
            assertNull(model.getScriptTransition("nope"));
            assertNull(model.getScriptTransition(null));
        }
    }

    @Test
    @DisplayName("a <transition> with no id is ignored; the record normalises nulls")
    void scriptTransitionEdgeCases() {
        assertEquals("", new ScriptTransitionModel(null, null, null).id());
        assertEquals("", new ScriptTransitionModel(null, null, null).language());
        assertEquals("", new ScriptTransitionModel(null, null, null).code());
    }

    // ------------------------------------------------------------------
    // Malformed / hostile input
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a non-document root, bad XML or a doctype all fail as one IOException")
    void malformedDecksThrowIoException() {
        assertThrows(IOException.class,
                () -> PresentoireModel.open(stream("<not-a-document/>")));
        assertThrows(IOException.class,
                () -> PresentoireModel.open(stream("this is not xml at all")));
        assertThrows(IOException.class,
                () -> PresentoireModel.open(stream("<document></wrong>")));
        // XXE hardening: a doctype declaration is refused outright.
        assertThrows(IOException.class, () -> PresentoireModel.open(stream(
                "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE document [<!ENTITY x \"y\">]>"
                + "<document><slide><text>&x;</text></slide></document>")));
    }

    // ------------------------------------------------------------------
    // Real archive round-trip (images + entries)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("open(File) reads a real .zip deck and resolves its images")
    void archiveRoundTrip(@TempDir File dir) throws IOException {
        File deck = new File(dir, "deck.zip");
        BufferedImage red = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        red.setRGB(0, 0, 0xFFFF0000);
        String content = """
                <document>
                  <slide id="pic">
                    <img width="0.1" height="0.1" x="0" y="0" src="img/red.png"/>
                  </slide>
                </document>
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(deck.toPath()))) {
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("img/red.png"));
            ImageIO.write(red, "png", zip);
            zip.closeEntry();
        }

        try (PresentoireModel model = PresentoireModel.open(deck)) {
            assertTrue(model.hasArchive());
            assertEquals(1, model.getSlideCount());
            BufferedImage loaded = model.loadImage("img/red.png");
            assertNotNull(loaded);
            assertEquals(2, loaded.getWidth());
            assertEquals(0xFFFF0000, loaded.getRGB(0, 0));
            // A missing image degrades to null, never throws.
            assertNull(model.loadImage("img/absent.png"));
            assertNotNull(model.openEntry("content.xml"));
            model.openEntry("content.xml").close();
            assertNull(model.openEntry("nope"));
        }
    }

    @Test
    @DisplayName("a .zip without content.xml is rejected and its handle closed")
    void archiveWithoutContentXml(@TempDir File dir) throws IOException {
        File deck = new File(dir, "empty.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(deck.toPath()))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("no deck here".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> PresentoireModel.open(deck));
    }

    // ------------------------------------------------------------------
    // In-memory model (imported / synthesized decks)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("of(...) builds an in-memory model whose images resolve with no ZIP")
    void ofCarriesInlineImages() throws IOException {
        byte[] png = solidPng(0xFFFF0000, 2, 2);
        SlideElement.Image img = new SlideElement.Image(
                0f, 0f, SlideElement.DEFAULT_Z, 0.1f, 0.1f, "img/red.png");
        SlideModel slide = new SlideModel("pic", "", List.of(img));
        Map<String, byte[]> images = new LinkedHashMap<>();
        images.put("img/red.png", png);

        try (PresentoireModel model = PresentoireModel.of(List.of(slide), null, images)) {
            assertFalse(model.hasArchive(), "an in-memory model holds no ZIP");
            assertEquals(1, model.getSlideCount());
            assertEquals(List.of("pic"), model.getSlideIds());
            // The picture resolves from the in-memory map, exactly as from a ZIP.
            BufferedImage loaded = model.loadImage("img/red.png");
            assertNotNull(loaded, "the inline image resolves from the map");
            assertEquals(2, loaded.getWidth());
            assertEquals(0xFFFF0000, loaded.getRGB(0, 0));
            assertNotNull(model.openEntry("img/red.png"));
            model.openEntry("img/red.png").close();
            // An unknown src still degrades to null, never throws.
            assertNull(model.loadImage("img/absent.png"));
            assertNull(model.openEntry("img/absent.png"));
        }
    }

    @Test
    @DisplayName("of(...) tolerates null scripts and images, and copies its slides")
    void ofIsDefensive() {
        List<SlideModel> slides = new java.util.ArrayList<>();
        slides.add(new SlideModel("only", "", List.of()));
        try (PresentoireModel model = PresentoireModel.of(slides, null, null)) {
            assertEquals(1, model.getSlideCount());
            assertNull(model.getScriptTransition("anything"));
            assertNull(model.loadImage("img/none.png"), "no images means no picture");
            // Mutating the caller's list afterwards cannot change the model.
            slides.add(new SlideModel("late", "", List.of()));
            assertEquals(1, model.getSlideCount(), "the slides are copied at build time");
        }
    }

    /** Encodes a solid-colour PNG and returns its bytes, for an in-memory image. */
    private static byte[] solidPng(int argb, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
