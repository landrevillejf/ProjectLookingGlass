/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server (clinic art): the shipped room shell is hand-edited XML, so
 * its own arithmetic is the thing most likely to be wrong.
 *
 * The clinic's walls, dado, ceiling and light panels are authored as one unit
 * cube stretched per room, and the vertex list, the accessor count and every
 * <triangles> index were edited by hand. Those three numbers disagreeing is not
 * a rendering subtlety: the parser drops the geometry and the world loses every
 * room in it at once, which is exactly what one stray "--" inside a comment did
 * the first time the ceiling was added. The frames prove the art looks right;
 * this proves it is still loadable tomorrow.
 */
package org.jdesktop.wonderland.server.world;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaGeometry;
import org.jdesktop.wonderland.modules.collada.ColladaParser;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ClinicRoomArtTest {

    private static final String ROOM_MODEL =
            "/wonderland-outpatient-clinic/clinic-room.kmz/models/clinic-room.dae";

    private static ColladaGeometry.Mesh mesh() {
        try (InputStream in = ClinicRoomArtTest.class.getResourceAsStream(ROOM_MODEL)) {
            assertThat(in).as("the room shell the whole clinic is built from").isNotNull();
            ColladaDocument doc = new ColladaParser().parse(in);
            assertThat(doc.geometryCount()).as("one mesh, placed many times").isEqualTo(1);
            return doc.geometry(doc.geometryIds().iterator().next()).mesh();
        } catch (Exception ex) {
            throw new AssertionError("the shipped clinic room does not parse", ex);
        }
    }

    @Test
    void theAccessorCountAgreesWithTheFloatArrayItDescribes() {
        ColladaGeometry.Mesh mesh = mesh();
        ColladaGeometry.Source positions = mesh.sourceById("clinic-room-positions");
        assertThat(positions).isNotNull();
        assertThat(positions.data().length)
                .as("count x stride must equal the floats, or the parser reads off the end")
                .isEqualTo((int) (positions.count() * positions.stride()));
    }

    @Test
    void noTriangleIndexPointsPastTheLastVertex() {
        ColladaGeometry.Mesh mesh = mesh();
        long vertices = mesh.sourceById("clinic-room-positions").count();
        for (ColladaGeometry.Primitive p : mesh.primitives()) {
            for (long index : p.indices()) {
                assertThat(index).as("<p> of material " + p.material()).isLessThan(vertices);
            }
        }
    }

    @Test
    void theShellCarriesEverySurfaceAHallNeeds() {
        Set<String> materials = new HashSet<>();
        for (ColladaGeometry.Primitive p : mesh().primitives()) {
            materials.add(p.material());
        }
        assertThat(materials).as("a room without a ceiling is a plinth, and a room without a"
                + " floor is a drawing of one").contains("carpet", "wall", "dado", "ceiling", "light");
    }

    @Test
    void theDadoIsDrawnOnBothSidesOfTheWall() {
        // The band sits proud of the plaster it trims, so the outer one is hidden
        // behind the wall to the person standing inside. Two dado primitives means
        // the blue waist-line reads from the map view and from the hall.
        long bands = mesh().primitives().stream()
                .filter(p -> "dado".equals(p.material()))
                .count();
        assertThat(bands).as("the outer and the inner band").isEqualTo(2);
    }

    @Test
    void theCeilingCarriesAGridOfPanelsRatherThanOneSheet() {
        ColladaGeometry.Mesh mesh = mesh();
        ColladaGeometry.Primitive lights = mesh.primitives().stream()
                .filter(p -> "light".equals(p.material()))
                .findFirst().orElseThrow();
        assertThat(lights.count())
                .as("a fixture stretched to a 22 m hall is a glowing ceiling, not a light")
                .isGreaterThanOrEqualTo(6);
    }
}
