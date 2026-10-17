package org.jdesktop.lg3d.apps.presentoire.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.SlideElement;
import org.jdesktop.lg3d.apps.presentoire.model.SlideModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the pure-Java {@code .odp} (OpenDocument Presentation) codec through
 * {@link DeckCodecs}: importing a hand-written Impress package, exporting a model
 * and reading it back, and the ODF structural rule that {@code mimetype} is the
 * first entry and is stored uncompressed. Import maps a {@code draw:text-box} to a
 * {@link SlideElement.Text} (or a {@code text:list} to a
 * {@link SlideElement.ListElement}) and a {@code draw:image} to a
 * {@link SlideElement.Image}. Headless: a ZIP and a DOM, no rendering.
 */
class OdpDeckCodecTest {

    @Test
    @DisplayName("importing a hand-written .odp maps its text box, list and image")
    void importsAHandWrittenOdp(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF123456, 3, 3);
        File odp = new File(dir, "hand.odp");
        buildMinimalOdp(odp, png);

        assertEquals(DeckFormat.ODP, DeckCodecs.sniff(odp), "the mimetype entry names it");
        try (PresentoireModel model = DeckCodecs.open(odp)) {
            assertEquals(1, model.getSlideCount());
            SlideModel slide = model.getSlide(0);
            assertEquals("Page1", slide.id());

            SlideElement.Text text = null;
            SlideElement.ListElement list = null;
            SlideElement.Image image = null;
            for (SlideElement element : slide.elements()) {
                if (element instanceof SlideElement.Text t) {
                    text = t;
                } else if (element instanceof SlideElement.ListElement l) {
                    list = l;
                } else if (element instanceof SlideElement.Image i) {
                    image = i;
                }
            }

            assertNotNull(text, "the text box imported");
            assertEquals("Impress title", text.text());
            assertNotNull(list, "the text:list imported as a list");
            assertEquals(java.util.List.of("one", "two"), list.items());
            assertNotNull(image, "the draw:image imported");
            BufferedImage loaded = model.loadImage(image.src());
            assertNotNull(loaded, "and its bytes ride in the in-memory model");

            DeckFixtures.assertTextWithinSlide(text);
            DeckFixtures.assertTextWithinSlide(list);
            DeckFixtures.assertImageWithinSlide(image);
        }
    }

    @Test
    @DisplayName("exporting a model to .odp and re-importing round-trips its content")
    void exportThenImportRoundTrips(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFFFF0000, 3, 3);
        File odp = new File(dir, "round.odp");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, odp, DeckFormat.ODP);
        }
        try (PresentoireModel read = DeckCodecs.open(odp)) {
            assertEquals(1, read.getSlideCount());
            boolean sawTitle = false;
            boolean sawList = false;
            boolean sawImage = false;
            for (SlideElement element : read.getSlide(0).elements()) {
                if (element instanceof SlideElement.Text t
                        && DeckFixtures.TITLE_TEXT.equals(t.text())) {
                    sawTitle = true;
                    assertEquals(DeckFixtures.TITLE_ARGB, t.argb(),
                            "the opaque colour survives via fo:color");
                } else if (element instanceof SlideElement.ListElement l) {
                    sawList = DeckFixtures.BULLETS.equals(l.items());
                } else if (element instanceof SlideElement.Image i) {
                    sawImage = read.loadImage(i.src()) != null;
                }
            }
            assertTrue(sawTitle, "the title survived the round trip");
            assertTrue(sawList, "the bullet list survived");
            assertTrue(sawImage, "the picture survived and still decodes");
        }
    }

    @Test
    @DisplayName("an exported .odp stores mimetype first and uncompressed, plus a manifest")
    void exportWritesMimetypeFirstUncompressed(@TempDir File dir) throws IOException {
        byte[] png = DeckFixtures.solidPng(0xFF00FF00, 2, 2);
        File odp = new File(dir, "structure.odp");
        try (PresentoireModel model = DeckFixtures.sampleModel(png)) {
            DeckCodecs.export(model, odp, DeckFormat.ODP);
        }

        // The ODF spec: mimetype is the very first entry and is STORED.
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(odp.toPath()))) {
            ZipEntry first = zin.getNextEntry();
            assertNotNull(first);
            assertEquals("mimetype", first.getName(), "mimetype is the first entry");
            assertEquals(ZipEntry.STORED, first.getMethod(), "and it is stored, not deflated");
            assertEquals(DeckCodecs.ODP_MIMETYPE,
                    new String(zin.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (ZipFile zip = new ZipFile(odp)) {
            assertNotNull(zip.getEntry("META-INF/manifest.xml"), "a manifest is present");
            assertNotNull(zip.getEntry(PresentoireModel.CONTENT_ENTRY), "and content.xml");
            assertNotNull(zip.getEntry("Pictures/1.png"), "the picture is in the package");
        }
    }

    @Test
    @DisplayName("a ZIP whose content.xml is not OpenDocument is rejected as an .odp")
    void rejectsNonOdpContent(@TempDir File dir) throws IOException {
        File notOdp = new File(dir, "not-odp.odp");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(notOdp.toPath()), StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(PresentoireModel.CONTENT_ENTRY));
            zip.write("<document><slide/></document>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        // The root is <document>, not <office:document-content>, so reading fails.
        assertThrows(IOException.class, () -> DeckCodecs.open(notOdp, DeckFormat.ODP));
    }

    // ------------------------------------------------------------------
    // A minimal, valid .odp built by hand (no ODF library), as Impress writes it
    // ------------------------------------------------------------------

    private static void buildMinimalOdp(File file, byte[] png) throws IOException {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content \
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" \
                xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" \
                xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" \
                xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" \
                xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" \
                xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" \
                xmlns:xlink="http://www.w3.org/1999/xlink" office:version="1.2">
                  <office:body>
                    <office:presentation>
                      <draw:page draw:name="Page1">
                        <draw:frame svg:x="2cm" svg:y="2cm" svg:width="20cm" svg:height="3cm">
                          <draw:text-box><text:p>Impress title</text:p></draw:text-box>
                        </draw:frame>
                        <draw:frame svg:x="2cm" svg:y="6cm" svg:width="20cm" svg:height="8cm">
                          <draw:text-box>
                            <text:list>
                              <text:list-item><text:p>one</text:p></text:list-item>
                              <text:list-item><text:p>two</text:p></text:list-item>
                            </text:list>
                          </draw:text-box>
                        </draw:frame>
                        <draw:frame svg:x="4cm" svg:y="15cm" svg:width="5cm" svg:height="4cm">
                          <draw:image xlink:href="Pictures/1.png"/>
                        </draw:frame>
                      </draw:page>
                    </office:presentation>
                  </office:body>
                </office:document-content>
                """;
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            putStored(zip, "mimetype",
                    DeckCodecs.ODP_MIMETYPE.getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry(PresentoireModel.CONTENT_ENTRY));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Pictures/1.png"));
            zip.write(png);
            zip.closeEntry();
        }
    }

    /** Writes an entry STORED (uncompressed) with its CRC, as the ODF spec requires. */
    private static void putStored(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
