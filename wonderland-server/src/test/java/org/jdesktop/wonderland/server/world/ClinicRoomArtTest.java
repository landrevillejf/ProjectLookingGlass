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
 *
 * <p>Loadable is not the same as visible. The raster keeps the face whose
 * geometric normal points at the eye, so the winding of a hand-edited index list
 * decides whether a surface exists at all: the ceiling and its light panels were
 * wound face-up and a person standing in the hall saw sky over the wall, with
 * every test here green because they asked what the shell contains and never
 * which way it looks. {@link #everySurfaceFacesThePersonStandingInside()} is the
 * question that was missing.
 */
package org.jdesktop.wonderland.server.world;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static java.lang.Math.abs;
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

    @Test
    void everySurfaceFacesThePersonStandingInside() {
        // The shell is a unit cube, so "which way does it look" is a sign on one
        // component of the cross product of a triangle's edges. Walls and dado are
        // boxes with a quad on each side of every face, so they must cover all
        // four horizontal directions; a missing one is a wall you see through
        // from inside the room.
        ColladaGeometry.Mesh mesh = mesh();
        ColladaGeometry.Source positions = mesh.sourceById("clinic-room-positions");
        assertThat(positions.data()).as("the vertices the faces are wound from").isNotEmpty();
        Set<String> horizontals = new HashSet<>();
        Map<String, Integer> seen = new HashMap<>();
        for (ColladaGeometry.Primitive p : mesh.primitives()) {
            String material = p.material();
            int stride = p.stride();
            long[] index = p.indices();
            for (int t = 0; t * 3 * stride + 2 * stride < index.length; t++) {
                double[] n = normal(p, positions, t);
                if (n == null) {
                    continue; // degenerate: no facing to assert
                }
                seen.merge(material, 1, Integer::sum);
                switch (material) {
                    case "carpet":
                        assertThat(n[1])
                                .as("the floor is seen from above, by whoever stands on it")
                                .isGreaterThan(0f);
                        break;
                    case "ceiling":
                    case "light":
                        assertThat(n[1])
                                .as("'%s' is seen from below, by a person inside the room;"
                                        + " wound face-up it is culled and the hall has no"
                                        + " ceiling", material)
                                .isLessThan(0f);
                        break;
                    default:
                        horizontals.add(direction(n));
                        break;
                }
            }
        }
        // A material that answered nothing proves nothing, and an empty vertex
        // walk used to be exactly how this file stayed green without a ceiling.
        assertThat(seen.keySet())
                .as("every surface of the shell was wound-checked")
                .contains("carpet", "wall", "dado", "ceiling", "light");
        assertThat(horizontals)
                .as("every wall and dado face is drawn on both sides, so the hall reads"
                        + " from inside and from the map view")
                .containsExactlyInAnyOrder("+x", "-x", "+z", "-z");
    }

    /**
     * The unit geometric normal of triangle {@code t} of {@code p}, from the order
     * its three vertices are listed in. That order is the winding, and the winding
     * is the only thing that says which side of the face the raster keeps.
     */
    private static double[] normal(ColladaGeometry.Primitive p,
            ColladaGeometry.Source positions, int t) {
        double[] a = vertex(p, positions, t, 0);
        double[] b = vertex(p, positions, t, 1);
        double[] c = vertex(p, positions, t, 2);
        double[] u = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double[] v = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        double[] n = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2],
            u[0] * v[1] - u[1] * v[0]};
        double longest = abs(n[0]);
        longest = Math.max(longest, abs(n[1]));
        longest = Math.max(longest, abs(n[2]));
        if (longest < 1e-9) {
            return null;
        }
        return new double[] {n[0] / longest, n[1] / longest, n[2] / longest};
    }

    /** The {@code k}th vertex of triangle {@code t}, as xyz in model space. */
    private static double[] vertex(ColladaGeometry.Primitive p,
            ColladaGeometry.Source positions, int t, int k) {
        // A <p> list packs one group of stride() tokens per vertex, the VERTEX
        // input at offset 0; the facet count is triangles, not vertices.
        long at = (long) (t * 3 + k) * p.stride();
        int vertex = (int) p.indices()[(int) at];
        double[] data = positions.data();
        int index = vertex * positions.stride();
        return new double[] {data[index], data[index + 1], data[index + 2]};
    }

    /** The axis a mostly-horizontal normal points along, signed. */
    private static String direction(double[] n) {
        return abs(n[0]) > abs(n[2]) ? (n[0] > 0 ? "+x" : "-x") : (n[2] > 0 ? "+z" : "-z");
    }
}
