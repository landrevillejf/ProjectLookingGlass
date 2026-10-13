/*
 * wonderland-modules M5a (colladaloader): the COLLADA 1.4.1 DOM parser.
 * Drives the reader across every reachable branch on synthetic bytes -- the
 * full library set, the transform/instance inventory, the effect/transparent/
 * sampler folding, the tolerant numeric coercion (a bad token contributes zero,
 * never an abort) -- and the two hardening guarantees: malformed XML and a
 * DOCTYPE both raise ColladaParseException rather than half-parsing.
 */
package org.jdesktop.wonderland.modules.collada;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ColladaParserTest {

    private static final ColladaParser PARSER = new ColladaParser();

    private static String wrap(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\""
                + " version=\"1.4.1\">" + body + "</COLLADA>";
    }

    @Test
    void anEmptyDocumentYieldsAnInventoryOfNothingWithADefaultAsset() {
        ColladaDocument doc = PARSER.parse(wrap(""));

        assertThat(doc.geometryCount()).isZero();
        assertThat(doc.materialCount()).isZero();
        assertThat(doc.imageCount()).isZero();
        assertThat(doc.rootVisualScene()).isNull();
        assertThat(doc.worldMatrices()).isEmpty();
        assertThat(doc.texturePaths()).isEmpty();
        assertThat(doc.asset().upAxis()).isEqualTo("Y_UP"); // the null-asset default
        assertThat(doc.asset().unitMeter()).isEqualTo(1.0);
    }

    @Test
    void malformedXmlIsAWellFormednessFailure() {
        assertThatThrownBy(() -> PARSER.parse(wrap("<unclosed>")))
                .isInstanceOf(ColladaParser.ColladaParseException.class)
                .hasMessageContaining("not well-formed");
    }

    @Test
    void aDoctypeIsRefused() {
        String dtd = "<!DOCTYPE COLLADA [ <!ENTITY x \"boom\"> ]>";
        String xml = "<?xml version=\"1.0\"?>" + dtd + "<COLLADA version=\"1.4.1\"/>";
        assertThatThrownBy(() -> PARSER.parse(xml))
                .isInstanceOf(ColladaParser.ColladaParseException.class);
    }

    @Test
    void theAssetHeaderIsRead() {
        String body = "<asset><contributor><authoring_tool>SketchUp</authoring_tool></contributor>"
                + "<created>2010-11-29</created><modified>2010-11-30</modified>"
                + "<unit meter=\"0.0254\" name=\"inch\"/><up_axis>Z_UP</up_axis></asset>";
        ColladaAsset asset = PARSER.parse(wrap(body)).asset();

        assertThat(asset.authoringTool()).isEqualTo("SketchUp");
        assertThat(asset.created()).isEqualTo("2010-11-29");
        assertThat(asset.modified()).isEqualTo("2010-11-30");
        assertThat(asset.unitMeter()).isEqualTo(0.0254);
        assertThat(asset.upAxis()).isEqualTo("Z_UP");
    }

    @Test
    void anAssetWithDefaultsAnEmptyContributorToleratesTheGaps() {
        // a non-numeric meter falls back to 1.0; a contributor without a tool -> null
        String body = "<asset><contributor></contributor><unit meter=\"wide\"/></asset>";
        ColladaAsset asset = PARSER.parse(wrap(body)).asset();

        assertThat(asset.unitMeter()).isEqualTo(1.0);
        assertThat(asset.authoringTool()).isNull();
        assertThat(asset.upAxis()).isEqualTo("Y_UP");
        assertThat(asset.created()).isNull();
    }

    @Test
    void aFullLibraryDocumentIsReadEndToEnd() {
        String body = "<library_visual_scenes>"
                + "  <visual_scene id=\"Scene\" name=\"sc\">"
                + "    <node id=\"N1\" name=\"root\" type=\"NODE\">"
                + "      <matrix sid=\"transform\">1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1</matrix>"
                + "      <translate sid=\"t\">1 2 3</translate>"
                + "      <rotate sid=\"r\">0 0 1 90</rotate>"
                + "      <scale sid=\"s\">2 2 2</scale>"
                + "      <uniform_scale sid=\"u\">3</uniform_scale>"
                + "      <lookat sid=\"l\">0 0 10 0 0 0 0 1 0</lookat>"
                + "      <instance_geometry url=\"#Geo\">"
                + "        <bind_material><technique_common>"
                + "          <instance_material symbol=\"MAT\" target=\"#Mat\">"
                + "            <bind_vertex_input semantic=\"UVSET0\" input_semantic=\"TEXCOORD\"/>"
                + "          </instance_material>"
                + "          <instance_material symbol=\"NOP\" target=\"#Mat2\"/>"
                + "        </technique_common></bind_material>"
                + "      </instance_geometry>"
                + "      <instance_node url=\"#Sub\"/>"
                + "      <node id=\"child\"><translate>0 1 0</translate></node>"
                + "    </node>"
                + "  </visual_scene>"
                + "</library_visual_scenes>"
                + "<library_geometries><geometry id=\"Geo\" name=\"g\"><mesh>"
                + "  <source id=\"pos-src\"><float_array id=\"A\" count=\"6\">0 0 0 1 1 1</float_array>"
                + "    <technique_common><accessor count=\"2\" source=\"#A\" stride=\"3\"/></technique_common>"
                + "  </source>"
                + "  <source id=\"nrm-src\"><float_array id=\"B\" count=\"6\">0 0 -1 0 0 -1</float_array>"
                + "    <technique_common><accessor source=\"#B\" stride=\"3\"><count>2</count></accessor></technique_common>"
                + "  </source>"
                + "  <vertices id=\"V\"><input semantic=\"POSITION\" source=\"#pos-src\"/></vertices>"
                + "  <triangles count=\"1\" material=\"MAT\">"
                + "    <input offset=\"0\" semantic=\"VERTEX\" source=\"#V\"/>"
                + "    <input offset=\"1\" semantic=\"TEXCOORD\" source=\"#pos-src\"/>"
                + "    <p>0 0 1 1</p></triangles>"
                + "  <polylist count=\"0\"><input semantic=\"A\" source=\"#x\"/></polylist>"
                + "</mesh></geometry></library_geometries>"
                + "<library_materials><material id=\"Mat\" name=\"m\">"
                + "  <instance_effect url=\"#Eff\"/></material></library_materials>"
                + "<library_effects><effect id=\"Eff\" name=\"e\"><profile_COMMON>"
                + "  <technique sid=\"COMMON\"><lambert>"
                + "    <diffuse><color>1 0 0 1</color></diffuse>"
                + "    <transparent><color>1 1 1 0.5</color></transparent>"
                + "    <texture texture=\"Img\" texcoord=\"UV\"/>"
                + "  </lambert></technique>"
                + "</profile_COMMON></effect></library_effects>"
                + "<library_images><image id=\"Img\"><init_from>untitled/tex.png</init_from></image></library_images>"
                + "<scene><instance_visual_scene url=\"#Scene\"/></scene>";

        ColladaDocument doc = PARSER.parse(wrap(body));

        assertThat(doc.rootVisualScene().id()).isEqualTo("Scene");
        assertThat(doc.geometryCount()).isEqualTo(1);
        assertThat(doc.materialCount()).isEqualTo(1);
        assertThat(doc.imageCount()).isEqualTo(1);
        assertThat(doc.geometryIds()).containsExactly("Geo");

        ColladaGeometry geometry = doc.geometry("Geo");
        assertThat(geometry.name()).isEqualTo("g");
        assertThat(geometry.mesh().sources()).hasSize(2);
        assertThat(geometry.mesh().vertices().id()).isEqualTo("V");
        assertThat(geometry.mesh().primitives()).extracting(ColladaGeometry.Primitive::kind)
                .containsExactly("triangles", "polylist");
        // the triangles bind VERTEX/TEXCOORD (not a direct POSITION input), so the
        // headless positions() walk yields nothing here -- the deferred M6 renderer
        // chases the <vertices> indirection; ColladaGeometryTest covers the walk.
        assertThat(geometry.mesh().primitives().get(0).positions(geometry.mesh())).isEmpty();

        assertThat(doc.material("Mat").instanceEffectUrl()).isEqualTo("Eff");
        ColladaEffect effect = doc.effect("Eff");
        assertThat(effect.technique()).isEqualTo("lambert");
        assertThat(effect.diffuseColor()).containsExactly(1.0, 0.0, 0.0, 1.0);
        assertThat(effect.transparency()).isEqualTo(0.5);
        assertThat(effect.samplers().values()).containsOnly("Img");
        assertThat(doc.image("Img").normalizedPath()).isEqualTo("untitled/tex.png");
        assertThat(doc.texturePaths()).containsExactly("untitled/tex.png");

        ColladaNode root = doc.rootVisualScene().nodes().get(0);
        assertThat(root.transforms()).hasSize(6);
        assertThat(root.nodeInstanceUrls()).containsExactly("Sub");
        assertThat(root.children()).hasSize(1);
        assertThat(root.geometries().get(0).url()).isEqualTo("Geo");
        assertThat(root.geometries().get(0).materials().get(0).target()).isEqualTo("Mat");
        assertThat(root.geometries().get(0).materials().get(0).texcoords())
                .containsExactly("TEXCOORD");
        assertThat(root.geometries().get(0).materials().get(1).texcoords()).isEmpty();
        assertThat(doc.worldMatrices()).hasSize(2); // root + its child
    }

    @Test
    void aTechniqueWithoutADiffuseColourOrSamplersStillParses() {
        String body = "<library_effects><effect id=\"E\"><profile_COMMON>"
                + "  <technique_common><extra/><phong>"
                + "    <transparent><float>0.25</float></transparent>"
                + "  </phong></technique_common>"
                + "</profile_COMMON></effect></library_effects>";
        ColladaEffect effect = PARSER.parse(wrap(body)).effect("E");

        assertThat(effect.technique()).isEqualTo("phong");
        assertThat(effect.diffuseColor()).isNull();
        assertThat(effect.transparency()).isEqualTo(0.25);
        assertThat(effect.samplers()).isEmpty();
    }

    @Test
    void anEffectWithoutAProfileCommonReadsAsBare() {
        String body = "<library_effects><effect id=\"E\"><unknown/></effect></library_effects>";
        ColladaEffect effect = PARSER.parse(wrap(body)).effect("E");

        assertThat(effect.technique()).isNull();
        assertThat(effect.diffuseColor()).isNull();
        assertThat(effect.transparency()).isEqualTo(1.0);
        assertThat(effect.samplers()).isEmpty();
    }

    @Test
    void transparencyWithTooFewComponentsDefaultsToOpaque() {
        String body = "<library_effects><effect id=\"E\"><profile_COMMON>"
                + "  <technique_common><constant>"
                + "    <transparent><color>1 1 1</color></transparent>" // length 3 -> ignored
                + "  </constant></technique_common>"
                + "</profile_COMMON></effect></library_effects>";
        assertThat(PARSER.parse(wrap(body)).effect("E").transparency()).isEqualTo(1.0);
    }

    @Test
    void aRootSceneIdThatIsAbsentFallsBackToTheOnlyScene() {
        String body = "<library_visual_scenes><visual_scene id=\"Only\">"
                + "<node id=\"n\"><translate>1 0 0</translate></node>"
                + "</visual_scene></library_visual_scenes>"
                + "<scene/>"; // scene present but no instance_visual_scene
        ColladaDocument doc = PARSER.parse(wrap(body));

        assertThat(doc.rootVisualScene().id()).isEqualTo("Only");
    }

    @Test
    void aSourceWithoutAFloatArrayOrAccessorIsAnEmptyStrideOne() {
        String body = "<library_geometries><geometry id=\"G\"><mesh>"
                + "  <source id=\"s\"><other/></source>"
                + "  <triangles count=\"x\"><p>1 a 3</p></triangles>"
                + "</mesh></geometry></library_geometries>";
        ColladaGeometry geometry = PARSER.parse(wrap(body)).geometry("G");

        ColladaGeometry.Source source = geometry.mesh().sources().get(0);
        assertThat(source.data()).isEmpty();
        assertThat(source.stride()).isEqualTo(1);
        assertThat(source.count()).isZero();

        ColladaGeometry.Primitive triangles = geometry.mesh().primitives().get(0);
        assertThat(triangles.count()).isZero();            // "x" -> fallback 0
        assertThat(triangles.indices()).containsExactly(1, 0, 3); // "a" -> fallback 0
    }

    @Test
    void aGeometryWithoutAMeshIsAnEmptyStructure() {
        String body = "<library_geometries><geometry id=\"G\"/></library_geometries>";
        assertThat(PARSER.parse(wrap(body)).geometry("G").mesh().primitives()).isEmpty();
    }

    @Test
    void matrixValuesFoldToTheColumnMajorTransform() {
        String body = "<library_visual_scenes><visual_scene id=\"S\">"
                + "<node id=\"n\"><matrix>1 0 0 0 0 1 0 0 0 0 1 0 5 6 7 1</matrix></node>"
                + "</visual_scene></library_visual_scenes>";
        ColladaNode node = PARSER.parse(wrap(body)).rootVisualScene().nodes().get(0);

        assertThat(node.localMatrix().getElement(0, 3)).isCloseTo(5.0, within(1e-9));
        assertThat(node.localMatrix().getElement(1, 3)).isCloseTo(6.0, within(1e-9));
        assertThat(node.localMatrix().getElement(2, 3)).isCloseTo(7.0, within(1e-9));
    }

    @Test
    void theTolerantReaderAbsorbsSparseAndNonNumericAuthoringQuirks() {
        // A kitchen-sink export that leans on every tolerant-coercion branch the
        // reader must survive rather than abort on: a <unit> with no meter
        // attribute (default 1.0), a blank numeric array (empty double[]), a
        // non-numeric token in a rotate (dropped), a <source> whose accessor is a
        // direct child -- with and without a <count> child -- and an
        // <instance_geometry> with no url (stripHash(null) -> null, never thrown).
        String body = "<asset><unit name=\"inch\"/></asset>"
                + "<library_visual_scenes><visual_scene id=\"S\">"
                + "  <node id=\"n\">"
                + "    <translate></translate>"
                + "    <rotate>0 0 1 zzz</rotate>"
                + "    <instance_geometry/>"
                + "  </node>"
                + "</visual_scene></library_visual_scenes>"
                + "<library_geometries><geometry id=\"G\" name=\"g\"><mesh>"
                + "  <source id=\"A\"><float_array>1 2 3</float_array>"
                + "    <accessor source=\"#A\" stride=\"3\"><count>1</count></accessor>"
                + "  </source>"
                + "  <source id=\"B\"><accessor source=\"#B\" stride=\"5\"/></source>"
                + "</mesh></geometry></library_geometries>"
                + "<scene><instance_visual_scene url=\"#S\"/></scene>";

        ColladaDocument doc = PARSER.parse(wrap(body));

        assertThat(doc.asset().unitMeter()).isEqualTo(1.0); // missing meter -> default
        assertThat(doc.geometry("G").mesh().sources().get(0).stride()).isEqualTo(3);
        assertThat(doc.geometry("G").mesh().sources().get(0).count()).isEqualTo(1);
        assertThat(doc.geometry("G").mesh().sources().get(1).stride()).isEqualTo(5);
        assertThat(doc.geometry("G").mesh().sources().get(1).count()).isZero();

        ColladaNode node = doc.rootVisualScene().nodes().get(0);
        assertThat(node.transforms()).hasSize(2);
        assertThat(node.transforms().get(0).values()).isEmpty();                   // blank -> empty
        assertThat(node.transforms().get(1).values()).containsExactly(0.0, 0.0, 1.0); // 'zzz' dropped
        assertThat(node.geometries().get(0).url()).isNull();                       // url-less instance
    }
}
