/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (assetmgr): the MTGame scene reader, unit-tested headless.
 *
 * This is the interpreter for the one shipped art format that is not a COLLADA
 * document -- the meeting complex's OrientationWorld.mtg, whose failure to read
 * is what left that whole world (and the same scene embedded in combined) empty.
 * Four things are pinned here because each was, at some point in this port, a
 * live bug or is the load-bearing contract the fold rests on:
 *   - an Instance line's fields (the .dae file is the token AFTER the ten
 *     transform numbers, not among them -- an off-by-one there resolved every
 *     instance to "art/1.0" and folded nothing);
 *   - a zero rotation angle is the identity, and a non-zero one is a real spin
 *     about Y (reading the four orientation numbers as a raw quaternion turned
 *     the axis-first (0,1,0,0) into a spurious 180-degree flip of every object);
 *   - two source files that reuse the same library ids do not collide, because
 *     each is folded under its own namespace;
 *   - a gzip instance file inflates by its magic bytes just as the .dae path does.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.jogamp.vecmath.Matrix4d;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MtgSceneReaderTest {

    private static final String MODULE = "M";

    /**
     * The minimal COLLADA every synthetic Instance points at: one triangle mesh
     * in one visual-scene node. Small on purpose -- the reader's job is the fold
     * (indexing, placement, namespacing), not the mesh, so the geometry only has
     * to be parseable for a placed node to appear.
     */
    private static final String DAE =
            "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\" version=\"1.4.1\">"
            + "<library_geometries><geometry id=\"g\"><mesh><source id=\"s\">"
            + "<float_array id=\"a\" count=\"9\">0 0 0 1 0 0 0 1 0</float_array>"
            + "<technique_common><accessor source=\"#a\" count=\"3\" stride=\"3\">"
            + "<param name=\"X\" type=\"float\"/><param name=\"Y\" type=\"float\"/>"
            + "<param name=\"Z\" type=\"float\"/></accessor></technique_common></source>"
            + "<triangles count=\"1\"><p>0 1 2</p></triangles>"
            + "</mesh></geometry></library_geometries>"
            + "<library_visual_scenes><visual_scene id=\"vs\"><node id=\"n\">"
            + "<instance_geometry url=\"#g\"/></node></visual_scene></library_visual_scenes>"
            + "</COLLADA>";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(raw);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A store whose open always fails, to reach the reader's I/O-fault catch. */
    private static final class ThrowingStore extends MapStore {
        @Override
        public Optional<InputStream> open(String module, String path) {
            throw new IllegalStateException("simulated store fault");
        }
    }

    @Test
    void anEmptyOrNullSceneYieldsNoDocument() {
        MapStore store = new MapStore();
        assertThat(new MtgSceneReader().read(store, MODULE, "o.mtg", null)).isNull();
        assertThat(new MtgSceneReader().read(store, MODULE, "o.mtg", "")).isNull();
    }

    @Test
    void aMalformedOrTruncatedInstanceLineIsSkippedNotFatal() {
        // One bad author line costs one object, never the scene: a record whose
        // transform is not numeric, and one that stops short of the file token, are
        // both dropped by the parser's null answer while the well-formed line folds.
        String mtg = "Instance Bad org.jdesktop.mtgame.ColladaInstance "
                + "x y z 0 1 0 0 1 1 1 terrain.dae\n"
                + "Instance Short org.jdesktop.mtgame.ColladaInstance 0 0 0 0 1\n"
                + "Instance Good org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        assertThat(nodeNamed(doc, "Good")).isNotNull();
        assertThat(nodeNamed(doc, "Bad")).isNull();
        assertThat(nodeNamed(doc, "Short")).isNull();
    }

    @Test
    void aMissingInstanceFileDropsThatInstanceNotTheScene() {
        String mtg = "Instance Gone org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 absent.dae\n"
                + "Instance Here org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        assertThat(nodeNamed(doc, "Here")).isNotNull();
        assertThat(nodeNamed(doc, "Gone")).isNull();
    }

    @Test
    void aCorruptInstanceFileDropsThatInstanceNotTheScene() {
        // A gzip stream that cannot inflate (or COLLADA the parser rejects) is caught
        // per-file; the rest of the scene still folds rather than aborting the world.
        String mtg = "Instance Corrupt org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 bad.dae.gz\n"
                + "Instance Good org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";
        MapStore store = new MapStore()
                .put("bad.dae.gz", new byte[]{0x1f, (byte) 0x8b, 0x00, 0x00, 0x00})
                .put("terrain.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        assertThat(nodeNamed(doc, "Good")).isNotNull();
        assertThat(nodeNamed(doc, "Corrupt")).isNull();
    }

    @Test
    void aZeroLengthRotationAxisCarriesNoRotate() {
        // A degenerate axis (all three components zero) has no direction to turn by;
        // the reader omits the rotate rather than dividing by zero, keeping translation.
        String mtg = "Instance Flat org.jdesktop.mtgame.ColladaInstance "
                + "4 5 6 0 0 0 1.5 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        Matrix4d m = worldOf(doc, "Flat");
        assertThat(m.getElement(0, 3)).isEqualTo(4.0);
        assertThat(rotationFromIdentity(m)).isLessThan(1e-9);
    }

    @Test
    void aScaleIsCarriedIntoThePlacedTransform() {
        // The tree wall is authored at 1.4 across to stretch it; the scale must reach
        // the transform (and a non-unit scale is what makes the <scale> element appear).
        String mtg = "Instance Wall org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1.4 1.0 1.4 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        assertThat(worldOf(doc, "Wall").getElement(0, 0)).isEqualTo(1.4);
    }

    @Test
    void everyLibraryReferenceIsRewrittenUnderTheNamespace() {
        // The merge is only correct if a material's effect ref, an effect's sampler
        // image ref and the image id itself are all re-keyed to the same prefixed
        // vocabulary the libraries now carry -- a bare (un-prefixed) reference would
        // dangle at render time once two source files are folded together.
        String dae =
                "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\" version=\"1.4.1\">"
                + "<library_effects><effect id=\"effA\"><profile_COMMON>"
                + "<technique_common><lambert><diffuse><texture texture=\"s0\"/>"
                + "</diffuse></lambert></technique_common></profile_COMMON></effect></library_effects>"
                + "<library_materials><material id=\"matA\"><instance_effect url=\"#effA\"/>"
                + "</material></library_materials>"
                + "<library_images><image id=\"imgA\"><init_from>./a.png</init_from></image>"
                + "</library_images>"
                + "<library_geometries><geometry id=\"g\"><mesh><source id=\"s\">"
                + "<float_array id=\"a\" count=\"9\">0 0 0 1 0 0 0 1 0</float_array>"
                + "<technique_common><accessor source=\"#a\" count=\"3\" stride=\"3\">"
                + "<param name=\"X\" type=\"float\"/></accessor></technique_common></source>"
                + "<triangles count=\"1\" material=\"m\"><p>0 1 2</p></triangles>"
                + "</mesh></geometry></library_geometries>"
                + "<library_visual_scenes><visual_scene id=\"vs\"><node id=\"n\">"
                + "<instance_geometry url=\"#g\"><bind_material><technique_common>"
                + "<instance_material symbol=\"m\" target=\"#matA\"/>"
                + "</technique_common></bind_material></instance_geometry></node>"
                + "</visual_scene></library_visual_scenes></COLLADA>";
        String mtg = "Instance Terrain org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(dae));
        ColladaDocument doc = read(mtg, store);

        assertThat(doc.materialIds()).anyMatch(id -> id.startsWith("terrain__")
                && id.endsWith("matA"));
        assertThat(doc.effectIds()).anyMatch(id -> id.startsWith("terrain__")
                && id.endsWith("effA"));
        assertThat(doc.imageIds()).anyMatch(id -> id.startsWith("terrain__")
                && id.endsWith("imgA"));
    }

    @Test
    void aStoreReadFaultDropsTheInstanceRatherThanThrowing() {
        // The reader degrades a store fault to a missing file, so one unreadable asset
        // costs its own instance and never aborts the whole scene read.
        String mtg = "Instance Terrain org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";
        assertThat(read(mtg, new ThrowingStore())).isNull();
    }

    /** A store whose single module is a map of path -> bytes. */
    private static class MapStore implements WorldStore {
        private final Map<String, byte[]> files = new HashMap<>();

        MapStore put(String path, byte[] content) {
            files.put(MODULE + "/" + path, content);
            return this;
        }

        @Override
        public Optional<InputStream> open(String module, String path) {
            byte[] content = files.get(module + "/" + path);
            return content == null ? Optional.empty()
                    : Optional.of(new ByteArrayInputStream(content));
        }

        @Override
        public boolean present(String module, String path) {
            return files.containsKey(module + "/" + path);
        }

        @Override
        public List<String> listAssets(String module, String suffix) {
            return List.of();
        }
    }

    private static ColladaDocument read(String mtgText, WorldStore store) {
        return new MtgSceneReader().read(store, MODULE, "OrientationWorld.mtg", mtgText);
    }

    /** The placed node an Instance produced, found by the author's own name. */
    private static ColladaNode nodeNamed(ColladaDocument doc, String name) {
        for (ColladaNode.WorldMatrix wm : doc.worldMatrices()) {
            if (name.equals(wm.node().name())) {
                return wm.node();
            }
        }
        return null;
    }

    private static Matrix4d worldOf(ColladaDocument doc, String name) {
        for (ColladaNode.WorldMatrix wm : doc.worldMatrices()) {
            if (name.equals(wm.node().name())) {
                return wm.matrix();
            }
        }
        throw new AssertionError("no placed node named " + name);
    }

    /** How far the 3x3 rotation part of {@code m} sits from the identity. */
    private static double rotationFromIdentity(Matrix4d m) {
        double sum = 0;
        double[][] id = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                double d = m.getElement(r, c) - id[r][c];
                sum += d * d;
            }
        }
        return Math.sqrt(sum);
    }

    @Test
    void anInstanceFileTokenAfterTheNumbersIsResolvedAgainstTheSceneDir() {
        // The off-by-one this guards: the .dae name is the twelfth token (index 13),
        // not among the ten transform numbers. Reading a number as the file made every
        // instance resolve to a missing "art/1.0" and the world folded to nothing.
        String mtg = "Instance Terrain org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        assertThat(doc).isNotNull();
        assertThat(doc.geometryIds())
                .as("the one geometry, folded under the terrain namespace")
                .anyMatch(id -> id.startsWith("terrain__"));
    }

    @Test
    void aZeroRotationAngleIsIdentityAndTranslationIsKept() {
        // (0,1,0,0) is an axis-first record whose angle is zero -- the ground, the
        // building and every shelter. It must land upright; a raw-quaternion reading
        // of these four numbers flips the whole complex upside down about Y.
        String mtg = "Instance Ground org.jdesktop.mtgame.ColladaInstance "
                + "10 20 30 0 1 0 0 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        Matrix4d m = worldOf(doc, "Ground");
        assertThat(m.getElement(0, 3)).isEqualTo(10.0);
        assertThat(m.getElement(1, 3)).isEqualTo(20.0);
        assertThat(m.getElement(2, 3)).isEqualTo(30.0);
        assertThat(rotationFromIdentity(m))
                .as("no rotation at all: the 3x3 is the identity")
                .isLessThan(1e-9);
    }

    @Test
    void aNonZeroAngleIsARealSpinAboutY() {
        // The props carry a per-object yaw (the fourth number). A right angle must
        // actually turn the object, proving the reader is not silently identity-ing
        // the rotated instances the way the zero-angle case must stay unturned.
        String mtg = "Instance Prop org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 1.5707963 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        assertThat(rotationFromIdentity(worldOf(doc, "Prop")))
                .as("a 90-degree yaw is far from the identity")
                .isGreaterThan(1.0);
    }

    @Test
    void twoSourceFilesSharingLibraryIdsAreFoldedWithoutColliding() {
        // Every Google-Earth / SketchUp export reuses "g"; the two files here both
        // carry it. Namespacing keeps both geometries and drops both instances; a
        // single shared key map would overwrite one and silently lose half the world.
        String mtg = "Instance A org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 a.dae\n"
                + "Instance B org.jdesktop.mtgame.ColladaInstance "
                + "5 0 0 0 1 0 0 1 1 1 b.dae\n";
        MapStore store = new MapStore()
                .put("a.dae", bytes(DAE))
                .put("b.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        assertThat(nodeNamed(doc, "A")).isNotNull();
        assertThat(nodeNamed(doc, "B")).isNotNull();
        assertThat(doc.geometryIds())
                .anyMatch(id -> id.startsWith("a__"))
                .anyMatch(id -> id.startsWith("b__"));
    }

    @Test
    void aGzippedInstanceFileInflatesByItsMagicBytes() {
        // The meeting complex ships its .dae.gz; the reader sniffs the gzip magic the
        // same way the COLLADA path does, so a compressed instance folds as easily.
        String mtg = "Instance Terrain org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae.gz\n";
        MapStore store = new MapStore().put("terrain.dae.gz", gzip(bytes(DAE)));
        ColladaDocument doc = read(mtg, store);

        assertThat(doc).isNotNull();
        assertThat(doc.geometryIds()).anyMatch(id -> id.startsWith("terrain__"));
    }

    @Test
    void aSceneNamingNoColladaInstanceYieldsNoDocument() {
        // A water instance and a comment line carry no .dae to fold; a scene made only
        // of those is the "nothing readable" answer the loader turns into its error
        // rather than an empty document that renders a blank world.
        String mtg = "# a comment\n"
                + "Instance Water org.jdesktop.mtgame.JMEWaterInstance "
                + "0 -1 0 0 1 0 0 1 1 1 100 100 Terrain\n";
        MapStore store = new MapStore();

        assertThat(read(mtg, store)).isNull();
    }

    @Test
    void theSameFileReusedByManyInstancesIsParsedOnceAndPlacedManyTimes() {
        // The complex repeats shelters and trees from one .dae. Every instance still
        // lands as its own placed node (each at its own transform); the shared parse
        // is a cache, not a collapse -- one object per Instance record, always.
        String mtg = "Instance S0 org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 shelter.dae\n"
                + "Instance S1 org.jdesktop.mtgame.ColladaInstance "
                + "8 0 0 0 1 0 0 1 1 1 shelter.dae\n"
                + "Instance S2 org.jdesktop.mtgame.ColladaInstance "
                + "0 0 6 0 1 0 0 1 1 1 shelter.dae\n";
        MapStore store = new MapStore().put("shelter.dae", bytes(DAE));
        ColladaDocument doc = read(mtg, store);

        assertThat(nodeNamed(doc, "S0")).isNotNull();
        assertThat(nodeNamed(doc, "S1")).isNotNull();
        assertThat(nodeNamed(doc, "S2")).isNotNull();
        assertThat(worldOf(doc, "S1").getElement(0, 3)).isEqualTo(8.0);
        assertThat(worldOf(doc, "S2").getElement(2, 3)).isEqualTo(6.0);
    }

    @Test
    void aNestedNodeAndItsInstanceNodeAreBothRewrittenUnderTheNamespace() {
        // The fold prefixes a whole node tree, not just a scene's top level: a real
        // export nests geometry under a child node and cross-links nodes with an
        // <instance_node>. A material carrying no <instance_effect> exercises the
        // null-reference branch of the re-keying (nothing to prefix stays null).
        String dae =
                "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\" version=\"1.4.1\">"
                + "<library_materials><material id=\"matA\" name=\"Mat A\"/></library_materials>"
                + "<library_geometries><geometry id=\"g\"><mesh><source id=\"s\">"
                + "<float_array id=\"a\" count=\"9\">0 0 0 1 0 0 0 1 0</float_array>"
                + "<technique_common><accessor source=\"#a\" count=\"3\" stride=\"3\">"
                + "<param name=\"X\" type=\"float\"/></accessor></technique_common></source>"
                + "<triangles count=\"1\"><p>0 1 2</p></triangles>"
                + "</mesh></geometry></library_geometries>"
                + "<library_visual_scenes><visual_scene id=\"vs\"><node id=\"root\" name=\"Root\">"
                + "<node id=\"leaf\"><instance_geometry url=\"#g\"/></node>"
                + "<instance_node url=\"#root\"/></node>"
                + "</visual_scene></library_visual_scenes></COLLADA>";
        String mtg = "Instance Terrain org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(dae));
        ColladaDocument doc = read(mtg, store);

        assertThat(doc).isNotNull();
        assertThat(nodeNamed(doc, "Terrain")).isNotNull();
        assertThat(doc.materialIds()).anyMatch(id -> id.startsWith("terrain__"));
    }

    @Test
    void aSourceFileWithNoVisualSceneDropsThatInstanceNotTheScene() {
        // Geometry with nowhere to be placed (no <visual_scene>) cannot render, so the
        // reader treats it as unusable and folds nothing -- the whole scene is empty
        // only if EVERY instance file is like this, which the null answer records.
        String dae =
                "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\" version=\"1.4.1\">"
                + "<library_geometries><geometry id=\"g\"><mesh/></geometry>"
                + "</library_geometries></COLLADA>";
        String mtg = "Instance Terrain org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";
        MapStore store = new MapStore().put("terrain.dae", bytes(dae));

        assertThat(read(mtg, store)).isNull();
    }

    @Test
    void aStreamThatFailsMidReadDropsTheInstanceRatherThanThrowing() {
        // A present-but-unreadable asset (the store opens it, then the bytes fault) is
        // the same "this file is unusable" answer as a missing one; the reader's read
        // catch turns the IOException into a dropped instance, not an aborted world.
        String mtg = "Instance Terrain org.jdesktop.mtgame.ColladaInstance "
                + "0 0 0 0 1 0 0 1 1 1 terrain.dae\n";

        assertThat(read(mtg, new FaultyStreamStore())).isNull();
    }

    /** A store whose asset opens fine but throws IOException as it is read. */
    private static final class FaultyStreamStore implements WorldStore {
        @Override
        public Optional<InputStream> open(String module, String path) {
            return Optional.of(new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("simulated mid-read fault");
                }
            });
        }

        @Override
        public boolean present(String module, String path) {
            return true;
        }

        @Override
        public List<String> listAssets(String module, String suffix) {
            return List.of();
        }
    }
}
