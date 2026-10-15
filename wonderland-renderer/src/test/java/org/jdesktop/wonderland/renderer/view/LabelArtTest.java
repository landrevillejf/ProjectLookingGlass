/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: the name-tag raster.
 *
 * What is worth testing here is the polarity, because that is the one thing a
 * later "make it prettier" change gets wrong. The 2009 clinic's tags are dark
 * letters ringed in white, and the ring is not decoration: a clinic's walls are
 * white and its directory board is black, and only dark-on-white-ring reads on
 * both. Swap the two and the tags vanish on exactly the surface the walker is
 * facing when they enter.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.Test;

class LabelArtTest {

    private static BufferedImage raster(LabelArt.Image image) throws Exception {
        assertThat(image).isNotNull();
        assertThat(LabelArt.hasAlpha(image.png()))
                .as("a card with no alpha is a white placard pasted over the room")
                .isTrue();
        return ImageIO.read(new ByteArrayInputStream(image.png()));
    }

    /** Count the opaque pixels of a colour family: this raster is small and few. */
    private static int countDark(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) > 200
                        && (argb & 0xFFFFFF) < 0x303030) {
                    n++;
                }
            }
        }
        return n;
    }

    private static int countLight(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) > 200
                        && (argb & 0xFFFFFF) > 0xD0D0D0) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    void anUnnamedCellGetsNoCardRatherThanAnEmptyOne() {
        assertThat(LabelArt.of(null)).isNull();
        assertThat(LabelArt.of("")).isNull();
        assertThat(LabelArt.of("   ")).isNull();
    }

    @Test
    void theLettersAreDarkAndRingedInLight() throws Exception {
        BufferedImage img = raster(LabelArt.of("Waiting Patient"));
        assertThat(countDark(img)).as("the ink").isGreaterThan(0);
        assertThat(countLight(img)).as("the halo, which is what survives on a black board")
                .isGreaterThan(0);
    }

    @Test
    void aLongerNameIsAWiderCardAtTheSameHeight() throws Exception {
        LabelArt.Image shortName = LabelArt.of("Ann");
        LabelArt.Image longName = LabelArt.of("Waiting Patient");
        assertThat(longName.height()).as("one line is one line tall").isEqualTo(shortName.height());
        assertThat(longName.aspect())
                .as("and the card widens with the text, so the metre height stays honest")
                .isGreaterThan(shortName.aspect());
    }

    @Test
    void aBoardCarriesEveryLineItLists() throws Exception {
        LabelArt.Image one = LabelArt.of("Exam Room 1");
        LabelArt.Image list = LabelArt.of("Exam Room 1\nExam Room 2\nObservation Room");
        assertThat(list.height()).as("three lines are three times the leading")
                .isGreaterThan(one.height() * 2);
        BufferedImage img = raster(list);
        assertThat(countDark(img)).isGreaterThan(0);
    }

    @Test
    void widthForMetersKeepsTheRasterProportionsAtTheTaggedHeight() throws Exception {
        LabelArt.Image image = LabelArt.of("Receptionist");
        double metres = 0.17;
        assertThat(LabelArt.widthForMeters(image, metres))
                .isCloseTo(image.aspect() * metres, within(1e-9));
    }
}
