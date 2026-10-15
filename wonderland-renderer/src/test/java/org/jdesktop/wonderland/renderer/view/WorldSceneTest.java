/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: the decisions WorldScene makes about where a thing's
 * name goes, what gets a shadow, where a visitor arrives, and which end of a card
 * the picture's top belongs to.
 *
 * None of them need a universe. They are arithmetic on a cell's box, and they
 * are the decisions the 2009 clinic screenshot is made of, so each one is a
 * claim that can be broken by the next person who moves a constant: a tag sized
 * for the room it labels rather than the eye, a pool under a sofa, an entry pose
 * with a head filling the window, a world framed from its own property line.
 *
 * <p>The card mapping is here for the same reason and cost more: the renderer's
 * one texture upload samples a picture's first row at {@code v = 1} -- the
 * convention the shipped COLLADA art is authored for, which is why its wall signs
 * read upright -- so a card that maps {@code v = 0} to its top edge renders its
 * text upside down. Measured off a rendered clinic, every name over every
 * resident's head came back inverted while the walls behind them were right, which
 * is what localises the defect to this array rather than to the upload. The sky's
 * ramp is the other half of that one convention, asserted in {@link SkyArtTest}.</p>
 */
package org.jdesktop.wonderland.renderer.view;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.jdesktop.wonderland.renderer.mesh.TextureSource;
import org.jdesktop.wonderland.renderer.view.WorldScene.Box;
import org.jdesktop.wonderland.renderer.view.WorldScene.Entry;
import org.jdesktop.wonderland.renderer.view.WorldScene.Lighting;
import org.jdesktop.wonderland.renderer.view.WorldScene.Picture;
import org.jdesktop.wonderland.renderer.view.WorldScene.Tag;
import org.jogamp.java3d.Transform3D;
import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Vector3d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.Test;

class WorldSceneTest {

    private static Entry named(String name) {
        return new Entry(name, null, 0f, 0f, 0f, 0f, 1f, 1f, 1f, 0f, null, name);
    }

    private static Box cell(double cx, double cy, double cz,
            double hx, double hy, double hz) {
        return new Box(new Point3d(cx, cy, cz), new Vector3d(hx, hy, hz));
    }

    private static List<Tag> tagOf(String name, Box box) {
        List<Tag> tags = new ArrayList<>();
        WorldScene.collectTag(tags, named(name), box);
        return tags;
    }

    // ---- name tags -------------------------------------------------------

    @Test
    void aPersonIsTaggedOverTheirHeadAtTheSizeTheReferenceReads() {
        List<Tag> tags = tagOf("Waiting Patient", cell(0, 0.95, 0, 0.35, 0.9, 0.2));
        assertThat(tags).hasSize(1);
        Tag tag = tags.get(0);
        assertThat(tag.textHeight()).as("figure text is 17 cm, a sixth of the body")
                .isCloseTo(0.17, within(1e-9));
        assertThat(tag.at().y).as("the card clears the crown by the tag gap")
                .isGreaterThan(0.95 + 0.9);
        assertThat(tag.at().z).as("a person's tag is over them, not in front of them")
                .isEqualTo(0.0);
    }

    @Test
    void aSmallRoomHangsItsNameNearItsCeiling() {
        // A 3 m-tall exam room: its own top is above the ceiling, so a tag there
        // is invisible to the one reader standing inside.
        List<Tag> tags = tagOf("Exam Room 1", cell(-8, 1.5, -12.3, 2.0, 1.5, 2.0));
        assertThat(tags).hasSize(1);
        Tag tag = tags.get(0);
        assertThat(tag.textHeight()).as("a room is named in larger letters than a person")
                .isGreaterThan(0.17);
        assertThat(tag.at().y).as("hung near the ceiling, well below the room's own top")
                .isBetween(2.0, 3.0);
    }

    @Test
    void aHallIsSignedOnTheFarWallRatherThanOverTheCrowd() {
        // 22 x 16 m of carpet: a tag at the centre of that floats in the air in
        // front of everyone standing in it, which is what the first hall frame
        // showed. The far wall is where a doorway sign would be.
        Box hall = cell(0, 1.7, 0, 11.1, 1.7, 8.1);
        Tag tag = tagOf("Outpatient Clinic Hall", hall).get(0);
        assertThat(tag.at().z).as("the sign sits inside the far wall, not on it")
                .isGreaterThan(-8.1)
                .isLessThan(-7.0);
    }

    @Test
    void aFloorAndAnOversizedTileAreNotThingsAnyoneNeedsNamed() {
        assertThat(tagOf("Floor", cell(0, 0.025, -8, 12.0, 0.025, 17.0)))
                .as("a floor is shorter than the shortest thing worth a tag")
                .isEmpty();
        assertThat(tagOf("Terrain", cell(0, 1.5, 0, 120.0, 1.5, 120.0)))
                .as("a 240 m lawn would hang its file name in the middle of every view")
                .isEmpty();
    }

    @Test
    void aCellWithNoNameGetsNoCard() {
        assertThat(tagOf("  ", cell(0, 0.9, 0, 0.35, 0.9, 0.2))).isEmpty();
        List<Tag> tags = new ArrayList<>();
        WorldScene.collectTag(tags, new Entry("x", null, 0, 0, 0), cell(0, 0.9, 0, 0.35, 0.9, 0.2));
        assertThat(tags).as("the app layer decides what is named; a silent cell stays silent")
                .isEmpty();
    }

    // ---- ground shadows --------------------------------------------------

    @Test
    void onlyStandingThingsDarkenTheFloor() {
        List<Box> shaded = new ArrayList<>();
        WorldScene.collectShadow(shaded, cell(0, 0.95, 0, 0.35, 0.9, 0.2));
        assertThat(shaded).as("a resident gets a pool").hasSize(1);

        shaded.clear();
        WorldScene.collectShadow(shaded, cell(0, 1.7, 0, 11.1, 1.7, 8.1));
        assertThat(shaded).as("a room is not standing on the floor, it is the floor's surround")
                .isEmpty();

        shaded.clear();
        WorldScene.collectShadow(shaded, cell(0, 0.025, 0, 12.0, 0.025, 17.0));
        assertThat(shaded).as("a floor has nothing under it to shade").isEmpty();

        shaded.clear();
        WorldScene.collectShadow(shaded, cell(0, 0.55, 0, 4.0, 0.55, 4.0));
        assertThat(shaded).as("a 8 m table is too wide a thing to sit in a footpool")
                .isEmpty();
    }

    // ---- the entry pose --------------------------------------------------

    @Test
    void aWorldWithNobodyInItHasNoEntry() {
        assertThat(WorldScene.crowdEntry(List.of(), cell(0, 1, 0, 5, 1, 5))).isNull();
    }

    @Test
    void aVisitorStandsInFrontOfTheCrowdRatherThanAmongIt() {
        List<Box> figures = List.of(
                cell(-2.0, 0.95, 0.0, 0.35, 0.9, 0.2),
                cell(0.0, 0.95, 1.6, 0.35, 0.9, 0.2),
                cell(2.6, 0.95, -1.2, 0.35, 0.9, 0.2));
        Point3d entry = WorldScene.crowdEntry(figures, cell(0, 1.7, 0, 11.1, 1.7, 8.1));
        assertThat(entry.x).as("centred across the group, not on one of them")
                .isCloseTo(0.2, within(1e-6));
        assertThat(entry.y).as("at their floor, so the eye is a person's height above it")
                .isCloseTo(0.05, within(1e-9));
        assertThat(entry.z).as("five metres back from the nearest body")
                .isCloseTo(1.8 + 5.0, within(1e-6));
    }

    @Test
    void theBackOffNeverPutsTheVisitorThroughTheWallBehindThem() {
        // A crowd that stands near the entrance of a shallow room: backing off five
        // metres walks the camera out of the building, which is a picture of an
        // exterior wall and not of the room it was taken to show.
        List<Box> figures = List.of(cell(0, 0.95, 6.8, 0.35, 0.9, 0.2));
        Point3d entry = WorldScene.crowdEntry(figures, cell(0, 1.7, 0, 11.1, 1.7, 8.1));
        assertThat(entry.z).as("kept inside the hall's near wall")
                .isLessThan(8.1)
                .isGreaterThan(6.8);
    }

    // ---- the shadow quad -------------------------------------------------

    @Test
    void aFlatQuadFacesUpSoAGroundShadowIsSeenFromAboveTheFloor() {
        ColladaMeshData quad = WorldScene.flatQuad(2.0, 1.0);
        assertThat(quad.vertexCount()).as("two triangles, unindexed").isEqualTo(6);
        float[] c = quad.coordinates();
        // The winding is the whole test: reversed, the pool is only visible from
        // under the floor it lies on, which is exactly what the first cut did.
        float ux = c[3] - c[0], uz = c[5] - c[2];
        float vx = c[6] - c[3], vz = c[8] - c[5];
        assertThat(uz * vx - ux * vz)
                .as("the face normal points +Y, at the eye standing on it")
                .isGreaterThan(0f);
        for (int i = 1; i < c.length; i += 3) {
            assertThat(c[i]).as("a ground shadow is flat").isEqualTo(0f);
        }
        assertThat(quad.normals()).containsExactly(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
                0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f);
        float[] uv = quad.texCoords();
        assertThat(uv).as("the card is mapped corner to corner").hasSize(12);
        // The pool is symmetric, so no pixel turns on this; the mapping is pinned
        // because it is the one convention every card in the file shares, and the
        // day someone draws an asymmetric ground decal is the day it matters.
        for (int i = 0; i < 6; i++) {
            assertThat(uv[i * 2 + 1]).as("v at vertex " + i)
                    .isEqualTo(c[i * 3 + 2] < 0f ? 1f : 0f);
        }
    }

    // ---- how a card is mapped --------------------------------------------

    @Test
    void aCardCarriesTheImagesFirstRowAtItsTopEdge() {
        // The convention the whole renderer turns on. The shared upload hands a
        // picture to Java 3D origin-at-upper-left, which samples its first row at
        // v = 1 -- the same convention the shipped COLLADA exports are authored
        // against -- so the top of a hand-written card must be v = 1 or its text
        // comes out upside down. Asserted on the geometry rather than a render
        // because a render needs a display and this needs only the array.
        ColladaMeshData card = WorldScene.tagQuad(2.0, 1.0);
        float[] c = card.coordinates();
        float[] uv = card.texCoords();
        assertThat(c).as("two triangles, unindexed").hasSize(18);
        assertThat(uv).hasSize(12);
        for (int i = 0; i < 6; i++) {
            float y = c[i * 3 + 1];
            float v = uv[i * 2 + 1];
            if (y > 0f) {
                assertThat(v).as("the card's top edge at vertex " + i).isEqualTo(1f);
            } else if (y < 0f) {
                assertThat(v).as("the card's bottom edge at vertex " + i).isEqualTo(0f);
            }
        }
        // And the u runs with x, or the word reads backwards rather than upside-down.
        for (int i = 0; i < 6; i++) {
            assertThat(uv[i * 2]).as("u at vertex " + i)
                    .isEqualTo(c[i * 3] > 0f ? 1f : 0f);
        }
    }

    @Test
    void aCardIsWoundTowardTheWalkerRatherThanIntoTheWall() {
        ColladaMeshData card = WorldScene.tagQuad(2.0, 1.0);
        float[] c = card.coordinates();
        // (v0 -> v1) x (v0 -> v2) must point +Z, the side the eye stands on; this
        // fork culls back faces, so the other winding is a card that is not there.
        float ex = c[3] - c[0], ey = c[4] - c[1];
        float fx = c[6] - c[0], fy = c[7] - c[1];
        assertThat(ex * fy - ey * fx).as("counter-clockwise seen from +Z")
                .isGreaterThan(0f);
        assertThat(card.normals()).containsExactly(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f,
                0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f);
    }

    // ---- the turn a heading is -------------------------------------------

    @Test
    void aHeadingTurnsTheCellRatherThanMerelySeatingIt() {
        // AxisAngle4f's four arguments are (x, y, z, angle), and the call reads
        // like an angle-first API. Passing the heading first is not a compile
        // error, not a throw and not a wrong number anywhere: the axis becomes
        // (yaw, 0, 1) and the angle becomes 0, so the cell is seated where the
        // world put it and never turned. Measured off the clinic's scene graph,
        // that is what every poster and every turned model had been doing -- the
        // east wall's prints, authored a quarter-turn to hang flat against it,
        // drew facing the north wall instead, 8 px of picture at 13 m, which a
        // visitor reads as art that failed to load because it is not there.
        Transform3D quarter = WorldScene.yawTurn((float) -Math.toRadians(90));
        org.jogamp.vecmath.Matrix4d m = new org.jogamp.vecmath.Matrix4d();
        quarter.get(m);
        assertThat(m.m00).as("a quarter-turn is not the identity")
                .isCloseTo(0.0, within(1e-6));
        assertThat(m.m02).as("the angle survives into the matrix")
                .isCloseTo(-1.0, within(1e-6));

        // The two conventions the renderer's own comments state, checked on the
        // vectors rather than on matrix cells: a card's front normal is its local
        // +Z, and a poster on the east wall has to end up facing -X, into the room.
        Vector3d normal = new Vector3d(0f, 0f, 1f);
        quarter.transform(normal);
        assertThat(normal.x).as("the turned face points -X").isCloseTo(-1.0, within(1e-6));
        assertThat(normal.z).as("and no longer +Z").isCloseTo(0.0, within(1e-6));

        Vector3d width = new Vector3d(1f, 0f, 0f);
        quarter.transform(width);
        assertThat(width.z).as("the card's width runs along Z, across the wall")
                .isCloseTo(1.0, within(1e-6));
        assertThat(width.x).as("and not along the sight line of anyone walking in")
                .isCloseTo(0.0, within(1e-6));
    }

    @Test
    void aHeadingOfZeroLeavesTheCellFacingItsWall() {
        // The north cards are authored at yaw 0 and must stay facing +Z; the
        // call sites skip this turn entirely, so the method only has to be
        // honest about what "no turn" means when somebody asks it directly.
        Transform3D straight = WorldScene.yawTurn(0f);
        Vector3d normal = new Vector3d(0f, 0f, 1f);
        straight.transform(normal);
        assertThat(normal.z).as("a card with no heading still faces the walker")
                .isCloseTo(1.0, within(1e-6));
    }

    // ---- what is a name, what is a file ----------------------------------

    @Test
    void anArtReferenceWrittenIntoANameFieldIsNotHungAsAName() {
        // The shipped worlds do this: a cell whose <name> is the file it points at
        // arrives as "PortalToField-Wood.kmz" in letters sized for a room, which is
        // the single most visible way a ported world looks machine-made.
        assertThat(WorldScene.looksLikeFile("OrientationBricks.png")).isTrue();
        assertThat(WorldScene.looksLikeFile("owl_3d_demoworld_013_mainbldg.dae.gz"))
                .isTrue();
        assertThat(WorldScene.looksLikeFile("PortalToField-Wood.kmz")).isTrue();
        assertThat(WorldScene.looksLikeFile("Reception")).isFalse();
        assertThat(WorldScene.looksLikeFile("Exam Room 2")).isFalse();
        assertThat(WorldScene.looksLikeFile("Outpatient Clinic Hall")).isFalse();
    }

    @Test
    void aTrailingDotOrNoPathAtAllIsStillAName() {
        // lastIndexOf('.') must not turn a name ending in punctuation, or a dotted
        // directory with nothing after it, into a file the world refuses to label.
        assertThat(WorldScene.looksLikeFile("Room 3.")).isFalse();
        assertThat(WorldScene.looksLikeFile("...")).isFalse();
        assertThat(WorldScene.looksLikeFile("Reception")).isFalse();
        assertThat(WorldScene.looksLikeFile(" ")).isFalse();
        // A real suffix after a real stem is a file even if the stem has dots in it.
        assertThat(WorldScene.looksLikeFile("logo.v2.png")).isTrue();
    }

    // ---- what stands up --------------------------------------------------

    @Test
    void terrainIsNotAPlaceToParkTheCamera() {
        assertThat(WorldScene.standsUp(cell(0, 1.5, 0, 4.0, 1.5, 3.0)))
                .as("a cabinet stands").isTrue();
        assertThat(WorldScene.standsUp(cell(0, 6.0, 0, 12.0, 6.0, 9.0)))
                .as("a building stands").isTrue();
        assertThat(WorldScene.standsUp(cell(0, 0.95, 0, 0.35, 0.9, 0.2)))
                .as("a body is shorter than the standing threshold -- which is why the "
                        + "crowd a visitor arrives at is found from its shadows, not "
                        + "from this rule")
                .isFalse();
        assertThat(WorldScene.standsUp(cell(0, 0.025, -8, 12.0, 0.025, 17.0)))
                .as("a floor lies down: shorter than the shortest standing thing")
                .isFalse();
        assertThat(WorldScene.standsUp(cell(0, 1.5, 0, 90.0, 1.5, 90.0)))
                .as("a 3 m relief on a 90 m tile is terrain, by the ratio")
                .isFalse();
        assertThat(WorldScene.standsUp(cell(0, 40.0, 0, 530.0, 40.0, 530.0)))
                .as("a 530 m field is terrain whatever it carries, by the spread")
                .isFalse();
    }

    // ---- the subject the camera is seated on -----------------------------

    @Test
    void aSprawlingParcelFramesOneNeighbourhoodRatherThanItsPropertyLine() {
        // The combined world measured: six separate demo areas packed onto one
        // parcel, its standing cells running from x = -2052 to x = +2052. Fitted to
        // that union the eye backed off 2.6 km and the render was a map of a world
        // rather than a world -- the frame a person read as nothing having survived.
        List<Box> standing = new ArrayList<>();
        for (int area = 0; area < 6; area++) {
            double base = -2000.0 + area * 800.0;
            for (int k = 0; k < 4; k++) {
                standing.add(cell(base + k * 12.0, 3.0, (k % 2) * 9.0,
                        5.0, 3.0, 4.0));
            }
        }
        Box everything = null;
        for (Box cell : standing) {
            everything = (everything == null) ? cell : everything.union(cell);
        }
        Box focus = WorldScene.focusOn(standing, everything, List.of());
        assertThat(focus.half().x * 2.0)
                .as("one neighbourhood, not the whole 4 km plot")
                .isLessThan(120.0);
        assertThat(everything.half().x * 2.0).as("the union really is sprawling")
                .isGreaterThan(4000.0);
        // The busiest corner is chosen, and every area here is equally busy, so the
        // answer must be one of them rather than a mean that lands in empty space.
        assertThat(focus.center().x).isBetween(-2005.0, 2005.0);
    }

    @Test
    void aCompactWorldKeepsAllOfIt() {
        // The rule must not cost a normal world anything: a clinic's rooms are all
        // within one neighbourhood, so clustering is a no-op and everything placed
        // stays in the subject.
        List<Box> standing = List.of(
                cell(-8.0, 1.7, -12.0, 2.0, 1.7, 2.0),
                cell(0.0, 1.7, -12.0, 2.0, 1.7, 2.0),
                cell(8.0, 1.7, -4.0, 2.0, 1.7, 2.0),
                cell(-2.0, 1.2, 2.0, 0.8, 1.2, 0.6));
        Box everything = standing.get(0)
                .union(standing.get(1)).union(standing.get(2)).union(standing.get(3));
        Box focus = WorldScene.focusOn(standing, everything, List.of());
        assertThat(focus.center().x).isCloseTo(everything.center().x, within(1e-9));
        assertThat(focus.center().z).isCloseTo(everything.center().z, within(1e-9));
        assertThat(focus.half().x).isCloseTo(everything.half().x, within(1e-9));
    }

    @Test
    void aGroupDecidesTheNeighbourhoodRatherThanTheBusiestCorner() {
        // Two equal clusters far apart, and the residents standing in the far one.
        // "Busiest" cannot choose between them; where the people are can, and that
        // is the difference between arriving in a room and arriving at a lot line.
        List<Box> standing = List.of(
                cell(-600.0, 3.0, 0.0, 6.0, 3.0, 6.0),
                cell(-580.0, 3.0, 0.0, 6.0, 3.0, 6.0),
                cell(600.0, 3.0, 0.0, 6.0, 3.0, 6.0),
                cell(620.0, 3.0, 0.0, 6.0, 3.0, 6.0));
        List<Box> figures = List.of(
                cell(600.0, 0.95, 1.0, 0.35, 0.9, 0.2),
                cell(620.0, 0.95, -1.0, 0.35, 0.9, 0.2));
        Box focus = WorldScene.focusOn(standing, standing.get(0).union(standing.get(3)),
                figures);
        assertThat(focus.center().x).as("the crowd's corner, 1200 m from the other one")
                .isGreaterThan(0.0);
        assertThat(focus.half().x * 2.0).as("and only that corner").isLessThan(60.0);
    }

    @Test
    void nothingStandingAtAllStillLeavesAWorldSomewhere() {
        // A world of floors and lawns has no neighbourhood to pick. It must frame
        // what it has rather than collapsing to a point, or the camera is placed at
        // the origin of a world that is not there.
        Box spread = cell(0, 0.02, 0, 120.0, 0.02, 120.0);
        assertThat(WorldScene.focusOn(List.of(), spread, List.of())).isSameAs(spread);

        Box empty = WorldScene.focusOn(List.of(), null, List.of());
        assertThat(empty.half().x).as("a margin, not a degenerate zero box").isGreaterThan(0.0);
        assertThat(empty.half().y).isGreaterThan(0.0);
        assertThat(empty.half().z).isGreaterThan(0.0);
    }

    // ---- the data a cell arrives as --------------------------------------

    @Test
    void aDegeneratePlacementComponentFallsBackToTheIdentity() {
        // A .dep with a blank <scale> or a NaN heading must draw at its authored
        // size, not collapse to a point or vanish: a missing component is the
        // identity, and the compact constructor is the one place that decides it
        // before any Java 3D matrix is built from the number.
        Entry broken = new Entry("n", null, 0f, 0f, 0f,
                Float.NaN, Float.POSITIVE_INFINITY, 0f, Float.NEGATIVE_INFINITY,
                -1f, null, null);
        assertThat(broken.scaleX()).as("a zero or non-finite scale is 1").isEqualTo(1f);
        assertThat(broken.scaleY()).isEqualTo(1f);
        assertThat(broken.scaleZ()).isEqualTo(1f);
        assertThat(broken.yaw()).as("a non-finite heading is straight ahead").isEqualTo(0f);
        assertThat(broken.footprint()).as("a negative footprint is no extent").isEqualTo(0f);
        assertThat(broken.textures()).as("no source means colour fallbacks only")
                .isSameAs(TextureSource.NONE);
    }

    @Test
    void aSanePlacementComponentIsCarriedThroughUntouched() {
        Entry ok = new Entry("n", null, 1f, 2f, 3f,
                0.5f, 2f, 3f, 4f, 5f, TextureSource.NONE, "Reception");
        assertThat(ok.x()).isEqualTo(1f);
        assertThat(ok.yaw()).isEqualTo(0.5f);
        assertThat(ok.scaleX()).isEqualTo(2f);
        assertThat(ok.footprint()).isEqualTo(5f);
        assertThat(ok.tag()).isEqualTo("Reception");
        // The convenience constructors are the untransformed spelling of the same.
        Entry bare = new Entry("n", null, 1f, 2f, 3f);
        assertThat(bare.yaw()).as("nothing turned").isEqualTo(0f);
        assertThat(bare.scaleX()).as("authored size").isEqualTo(1f);
        assertThat(bare.footprint()).as("no footprint recorded").isEqualTo(0f);
    }

    @Test
    void aPictureWithNoAreaOrNoBytesIsNotWorthBuilding() {
        Picture blank = new Picture("p", null, 0f, 0f, 0f,
                Float.NaN, -5f, Float.POSITIVE_INFINITY, null);
        assertThat(blank.width()).as("a non-finite or negative extent is zero").isEqualTo(0f);
        assertThat(blank.height()).isEqualTo(0f);
        assertThat(blank.yaw()).as("a non-finite heading is straight").isEqualTo(0f);
        assertThat(blank.hasImage()).as("no bytes to show").isFalse();
        assertThat(blank.hasExtent()).as("no area to hang").isFalse();

        Picture poster = new Picture("p", new byte[] {1, 2, 3}, 0f, 0f, 0f,
                0.4f, 2f, 1f, null);
        assertThat(poster.hasImage()).as("bytes to show").isTrue();
        assertThat(poster.hasExtent()).as("an area to hang").isTrue();
    }

    // ---- the opt-in light rig (P0.1) ------------------------------------

    @Test
    void noRigIsTheFaithfulDefaultAndInstallsNothing() {
        assertThat(Lighting.NONE.enabled())
                .as("a shipped world asks for no rig and renders as before")
                .isFalse();
    }

    @Test
    void anEnabledRigKeepsTheColourAndDirectionTheCallerGaveIt() {
        Lighting rig = Lighting.of(new float[] {0.2f, 0.2f, 0.2f},
                new float[] {1f, 0.9f, 0.8f}, new float[] {0f, -1f, 0f});
        assertThat(rig.enabled()).isTrue();
        assertThat(rig.ambientRed()).isCloseTo(0.2f, within(1e-6f));
        assertThat(rig.lightGreen()).as("a warm key light").isCloseTo(0.9f, within(1e-6f));
        assertThat(rig.directionY()).isCloseTo(-1.0f, within(1e-6f));
    }

    @Test
    void aNonUnitSunIsNormalisedWithoutChangingTheWayItShines() {
        Lighting rig = Lighting.of(null, null, new float[] {0f, -10f, 0f});
        double length = Math.sqrt(rig.directionX() * rig.directionX()
                + rig.directionY() * rig.directionY()
                + rig.directionZ() * rig.directionZ());
        assertThat(length).as("the direction is a unit vector").isCloseTo(1.0, within(1e-6));
        assertThat(rig.directionY()).as("still straight down").isCloseTo(-1.0f, within(1e-6f));
    }

    @Test
    void noSunAtAllFallsBackToStraightDownRatherThanADegenerateLight() {
        Lighting rig = Lighting.of(null, null, new float[] {0f, 0f, 0f});
        assertThat(rig.directionY()).as("a zero-length direction is no sun: default down")
                .isCloseTo(-1.0f, within(1e-6f));
    }

    @Test
    void aNaNColorIsReplacedByTheDaytimeDefaultNotPropagated() {
        Lighting rig = Lighting.of(new float[] {Float.NaN, 0f, 0f},
                new float[] {Float.POSITIVE_INFINITY, 0f, 0f}, null);
        assertThat(rig.ambientRed()).as("null/degenerate ambient becomes soft grey")
                .isCloseTo(0.3f, within(1e-6f));
        assertThat(rig.lightRed()).as("degenerate colour becomes white")
                .isCloseTo(1.0f, within(1e-6f));
        assertThat(rig.directionY()).as("null direction is the zenith sun")
                .isCloseTo(-1.0f, within(1e-6f));
    }
}
