/*
 * wonderland-modules M5a (colladaloader): the scene node + world-matrix walk.
 * Pins document-order transform concatenation and the parent*child world walk
 * the 2006 loader baked into each Shape3D, plus the instance_geometry /
 * instance_material / instance_node inventory.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.ArrayList;
import java.util.List;
import org.jogamp.vecmath.Matrix4d;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ColladaNodeTest {

    private static ColladaNode node(String id, List<ColladaTransform> transforms,
                                    List<ColladaNode> children) {
        return new ColladaNode(id, id, "NODE", transforms, children,
                List.of(), List.of());
    }

    private static ColladaTransform translate(double x, double y, double z) {
        return new ColladaTransform(ColladaTransform.Kind.TRANSLATE, "t",
                new double[]{x, y, z});
    }

    @Test
    void aNodeWithoutTransformsIsIdentity() {
        ColladaNode leaf = node("leaf", List.of(), List.of());
        Matrix4d identity = new Matrix4d();
        identity.setIdentity();
        assertThat(leaf.localMatrix()).isEqualTo(identity);
        assertThat(leaf.id()).isEqualTo("leaf");
        assertThat(leaf.name()).isEqualTo("leaf");
        assertThat(leaf.type()).isEqualTo("NODE");
    }

    @Test
    void transformsConcatenateInDocumentOrder() {
        // translate (1,0,0) then translate (0,2,0) -> net (1,2,0)
        ColladaNode node = node("n",
                List.of(translate(1, 0, 0), translate(0, 2, 0)), List.of());
        Matrix4d m = node.localMatrix();
        assertThat(m.getElement(0, 3)).isCloseTo(1.0, within(1e-9));
        assertThat(m.getElement(1, 3)).isCloseTo(2.0, within(1e-9));
    }

    @Test
    void theWorldWalkComposesParentAndChild() {
        ColladaNode child = node("child", List.of(translate(0, 5, 0)), List.of());
        ColladaNode root = node("root", List.of(translate(10, 0, 0)), List.of(child));

        List<ColladaNode.WorldMatrix> out = new ArrayList<>();
        root.collectWorldMatrices(null, out); // null parent seeds identity

        assertThat(out).hasSize(2);
        assertThat(out.get(0).node().id()).isEqualTo("root");
        assertThat(out.get(1).node().id()).isEqualTo("child");
        // child world = root(10,0,0) * child(0,5,0) = (10,5,0)
        Matrix4d childWorld = out.get(1).matrix();
        assertThat(childWorld.getElement(0, 3)).isCloseTo(10.0, within(1e-9));
        assertThat(childWorld.getElement(1, 3)).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void instanceGeometryAndMaterialBindingsAreRecorded() {
        ColladaNode.InstanceMaterial material = new ColladaNode.InstanceMaterial(
                "MAT0", "effectTarget", List.of("TEXCOORD"));
        ColladaNode.InstanceGeometry geometry = new ColladaNode.InstanceGeometry(
                "geomTarget", List.of(material));
        ColladaNode node = new ColladaNode("n", "n", "NODE", List.of(), List.of(),
                List.of(geometry), List.of("otherNode"));

        assertThat(node.geometries()).containsExactly(geometry);
        assertThat(node.nodeInstanceUrls()).containsExactly("otherNode");
        assertThat(geometry.url()).isEqualTo("geomTarget");
        assertThat(material.symbol()).isEqualTo("MAT0");
        assertThat(material.target()).isEqualTo("effectTarget");
        assertThat(material.texcoords()).containsExactly("TEXCOORD");
    }
}
