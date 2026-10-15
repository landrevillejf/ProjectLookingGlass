/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (assetmgr): recovering a cell whose type JAXB refuses to open.
 *
 * Every stock world writes some of its cells with a root this port has no class
 * for -- posters, a PDF viewer, a whiteboard, a remote window, the environment --
 * and each one used to vanish without a trace, which is how a world with plenty
 * in it rendered as an empty field. ForeignCellReader is the second path: it does
 * not know the type, it recovers the three things every cell has (name, placement,
 * asset). These cases pin the recovery and, more importantly, the refusals that
 * keep a bad file from poisoning the world: a non-cell root, a nameless cell, an
 * element that only mentions "uri" without being a reference, a zero-length
 * rotation axis that would otherwise yield a quaternion of NaNs, and a "NaN" in a
 * scale that would otherwise collapse a picture to a degenerate quad.
 */
package org.jdesktop.wonderland.modules.asset;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.cell.state.ForeignCellServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.Test;

class ForeignCellReaderTest {

    private static final float EPS = 1e-4f;

    private static PositionComponentServerState position(ForeignCellServerState state) {
        return (PositionComponentServerState)
                state.getComponentServerState(PositionComponentServerState.class);
    }

    // ----------------------------------------------------------- refusals ----
    @Test
    void nothingToReadIsNothingToRecover() {
        assertThat(ForeignCellReader.read(null)).isNull();
        assertThat(ForeignCellReader.read("")).isNull();
        assertThat(ForeignCellReader.read("   \n  ")).isNull();
    }

    @Test
    void malformedXmlIsSkippedNotThrown() {
        assertThat(ForeignCellReader.read("<poster-cell><name>oops")).isNull();
    }

    @Test
    void aRootThatIsNotACellIsNotInventedIntoAnObject() {
        // module.xml / requires.xml share the store; a <name> alone is not a cell.
        assertThat(ForeignCellReader.read(
                "<module><name>Not A Cell</name></module>")).isNull();
    }

    @Test
    void aCellWithNoNameHasNoIdentityAndIsDropped() {
        assertThat(ForeignCellReader.read("<poster-cell></poster-cell>")).isNull();
        assertThat(ForeignCellReader.read("<poster-cell><name>  </name></poster-cell>"))
                .isNull();
    }

    // ----------------------------------------------------------- recovery ----
    @Test
    void aPosterIsRecoveredWithItsNameTypeAndImage() {
        String xml = "<poster-cell><name>Lobby Poster</name>"
                + "<image-uri>wla://M/art/poster.png</image-uri></poster-cell>";
        ForeignCellServerState state = ForeignCellReader.read(xml);

        assertThat(state).isNotNull();
        assertThat(state.getName()).isEqualTo("Lobby Poster");
        assertThat(state.getCellType()).isEqualTo("poster-cell");
        assertThat(state.getAssetURI()).isEqualTo("wla://M/art/poster.png");
    }

    @Test
    void aWhiteboardRootIsRecognisedEvenThoughCellComesFirst() {
        // <whiteboard-cell-svg>: the suffix is not at the end, and insisting it was
        // silently dropped every whiteboard in the shipped worlds.
        String xml = "<whiteboard-cell-svg><name>Ideas</name></whiteboard-cell-svg>";
        assertThat(ForeignCellReader.read(xml)).isNotNull();
    }

    @Test
    void aDocumentUriIsMatchedRegardlessOfCapitalisation() {
        // A viewer writes <documentURI> (capital UR-I); the match is case-insensitive.
        String xml = "<pdfviewer-cell><name>Manual</name>"
                + "<documentURI>http://x/manual.pdf</documentURI></pdfviewer-cell>";
        assertThat(ForeignCellReader.read(xml).getAssetURI())
                .isEqualTo("http://x/manual.pdf");
    }

    @Test
    void anElementThatOnlyMentionsUriIsNotAReference() {
        // A shared-state <key>document-uri</key> carries the literal words, not a
        // scheme; a whiteboard carries its SVG markup. Neither is fetchable.
        String keyed = "<x-cell><name>N</name><shared-state-component>"
                + "<key>document-uri</key></shared-state-component></x-cell>";
        assertThat(ForeignCellReader.read(keyed).getAssetURI()).isNull();

        String svg = "<whiteboard-cell-svg><name>N</name><svgDocumentURI>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\"/></svgDocumentURI>"
                + "</whiteboard-cell-svg>";
        assertThat(ForeignCellReader.read(svg).getAssetURI()).isNull();
    }

    // ---------------------------------------------------------- placement ----
    @Test
    void translationScaleRotationAndBoundsAreRead() {
        String xml = "<model-cell><name>Placed</name><position-component>"
                + "<translation><x>1</x><y>2</y><z>3</z></translation>"
                + "<scale><x>2</x><y>2</y><z>2</z></scale>"
                + "<rotation><x>0</x><y>1</y><z>0</z><angle>1.5707963</angle></rotation>"
                + "<bounds><type>BOX</type><x>4</x><y>5</y><z>6</z></bounds>"
                + "</position-component></model-cell>";
        ForeignCellServerState state = ForeignCellReader.read(xml);
        PositionComponentServerState p = position(state);

        assertThat(p).isNotNull();
        assertThat(p.getTranslation().x).isCloseTo(1f, within(EPS));
        assertThat(p.getTranslation().z).isCloseTo(3f, within(EPS));
        assertThat(p.getScaling().x).isCloseTo(2f, within(EPS));
        assertThat(p.getBounds().type)
                .isEqualTo(PositionComponentServerState.Bounds.BoundsType.BOX);
        assertThat(p.getBounds().x).isEqualTo(4.0);
        // a real turn about +Y: the quaternion is unit length, not NaN
        Quaternion q = p.getRotation();
        double length = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
        assertThat(length).isCloseTo(1.0, within(1e-3));
    }

    @Test
    void aCellWithNoPositionComponentIsStillRecovered() {
        ForeignCellServerState state = ForeignCellReader.read(
                "<poster-cell><name>Nowhere</name></poster-cell>");
        assertThat(state).isNotNull();
        assertThat(position(state)).isNull();
    }

    @Test
    void aZeroLengthAxisIsNoRotationNotAQuaternionOfNaNs() {
        // fromAngleAxis divides by the axis length; a zero axis would produce NaNs
        // that Java 3D refuses at attach time and takes the whole branch down.
        String xml = "<model-cell><name>N</name><position-component>"
                + "<rotation><x>0</x><y>0</y><z>0</z><angle>1.0</angle></rotation>"
                + "</position-component></model-cell>";
        Quaternion q = position(ForeignCellReader.read(xml)).getRotation();
        assertThat(q.x).isZero();
        assertThat(q.y).isZero();
        assertThat(q.w).isCloseTo(1f, within(EPS));
    }

    @Test
    void aNonFiniteNumberFallsBackInsteadOfCollapsingTheQuad() {
        // Double.parseDouble accepts "NaN"/"Infinity"; either in a scale is a
        // degenerate matrix. The finite guard turns them back into 0.
        String xml = "<model-cell><name>N</name><position-component>"
                + "<scale><x>NaN</x><y>Infinity</y><z>2</z></scale>"
                + "</position-component></model-cell>";
        Vector3f scale = position(ForeignCellReader.read(xml)).getScaling();
        assertThat(scale.x).isZero();
        assertThat(scale.y).isZero();
        assertThat(scale.z).isCloseTo(2f, within(EPS));
    }

    @Test
    void anUnparseableNumberFallsBackInsteadOfThrowing() {
        // A word where a coordinate belongs is a NumberFormatException the reader
        // must swallow to the fallback -- a bad file drops one object, not the load.
        String xml = "<model-cell><name>N</name><position-component>"
                + "<translation><x>left</x><y>2</y><z>3</z></translation>"
                + "</position-component></model-cell>";
        Vector3f t = position(ForeignCellReader.read(xml)).getTranslation();
        assertThat(t.x).isZero();
        assertThat(t.y).isCloseTo(2f, within(EPS));
        assertThat(t.z).isCloseTo(3f, within(EPS));
    }

    @Test
    void anUnknownBoundsHintKeepsTheDefaultShape() {
        String xml = "<model-cell><name>N</name><position-component>"
                + "<bounds><type>HEXAHEDRON</type><x>1</x><y>1</y><z>1</z></bounds>"
                + "</position-component></model-cell>";
        PositionComponentServerState p = position(ForeignCellReader.read(xml));
        assertThat(p.getBounds().type)
                .isEqualTo(PositionComponentServerState.Bounds.BoundsType.SPHERE);
    }

    @Test
    void boundsWithoutATypeElementKeepTheDefaultShape() {
        // A <bounds> that records only an extent and no <type>: the type guard's
        // absent branch, which must leave the SPHERE default rather than fail.
        String xml = "<model-cell><name>N</name><position-component>"
                + "<bounds><x>2</x><y>3</y><z>4</z></bounds>"
                + "</position-component></model-cell>";
        PositionComponentServerState.Bounds b =
                position(ForeignCellReader.read(xml)).getBounds();
        assertThat(b.type).isEqualTo(PositionComponentServerState.Bounds.BoundsType.SPHERE);
        assertThat(b.x).isEqualTo(2.0);
    }

    @Test
    void aTurnAboutANonVerticalAxisIsStillAUnitQuaternion() {
        // The zero-axis guard short-circuits on x first; a rotation authored about
        // +X (x non-zero) exercises that short-circuit and must not be mistaken for
        // the degenerate all-zero case.
        String xml = "<model-cell><name>N</name><position-component>"
                + "<rotation><x>1</x><y>0</y><z>0</z><angle>0.5</angle></rotation>"
                + "</position-component></model-cell>";
        Quaternion q = position(ForeignCellReader.read(xml)).getRotation();
        double length = Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
        assertThat(length).isCloseTo(1.0, within(1e-3));
        assertThat(q.x).isCloseTo((float) Math.sin(0.25), within(1e-3f));
    }

    // ------------------------------------------------------------- misc ----
    @Test
    void aByteOrderMarkAtTheFrontDoesNotBreakTheProlog() {
        String xml = "\uFEFF<poster-cell><name>BOM</name></poster-cell>";
        assertThat(ForeignCellReader.read(xml)).isNotNull();
        assertThat(ForeignCellReader.read(xml).getName()).isEqualTo("BOM");
    }

    @Test
    void pixelScaleAndPreferredSizeAreCarriedThrough() {
        String xml = "<poster-cell><name>P</name>"
                + "<pixelScaleX>0.02</pixelScaleX><pixelScaleY>0.03</pixelScaleY>"
                + "<preferredWidth>640</preferredWidth>"
                + "<preferredHeight>480</preferredHeight></poster-cell>";
        ForeignCellServerState state = ForeignCellReader.read(xml);
        assertThat(state.getPixelScaleX()).isEqualTo(0.02);
        assertThat(state.getPixelScaleY()).isEqualTo(0.03);
        assertThat(state.getPixelWidth()).isEqualTo(640.0);
        assertThat(state.getPixelHeight()).isEqualTo(480.0);
    }

    @Test
    void absentPixelScaleFallsBackToTheDefault() {
        ForeignCellServerState state = ForeignCellReader.read(
                "<poster-cell><name>P</name></poster-cell>");
        assertThat(state.getPixelScaleX())
                .isEqualTo(ForeignCellServerState.DEFAULT_PIXEL_SCALE);
    }
}
