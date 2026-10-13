/*
 * wonderland-modules M5b (artimport): the deployed-model value contract.
 * Pins the defensive vector copies (a null block stays null) and the bounding
 * volume's longest-side helper, including the null-dimension case.
 */
package org.jdesktop.wonderland.modules.artimport;

import java.util.List;
import org.jogamp.vecmath.Vector3f;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DeployedModelTest {

    @Test
    void nullVectorBlocksCopyToNull() {
        DeployedModel model = new DeployedModel(2, "url", "ldr", null, null, null, 0f, null, "cls");

        assertThat(model.backgroundScale()).isNull();
        assertThat(model.backgroundTranslation()).isNull();
        assertThat(model.backgroundRotationAxis()).isNull();
        assertThat(model.bounds()).isNull();
        assertThat(model.version()).isEqualTo(2);
        assertThat(model.modelURL()).isEqualTo("url");
    }

    @Test
    void vectorsAreCopiedDefensively() {
        Vector3f scale = new Vector3f(1, 2, 3);
        DeployedModel model = new DeployedModel(1, null, null, scale, null, null, 0f, null, null);

        model.backgroundScale().x = 99; // mutating the copy must not touch the field
        assertThat(model.backgroundScale().x).isEqualTo(1f);
    }

    @Test
    void boundsLongestSideIsTheMaxAxisOrNullToZero() {
        DeployedModel.Bounds box = new DeployedModel.Bounds("BOX",
                new Vector3f(0, 0, 0), new Vector3f(10, 30, 20));
        assertThat(box.longestDimension()).isEqualTo(30f);
        assertThat(box.center()).isNotNull();

        assertThat(new DeployedModel.Bounds("SPHERE", null, null).longestDimension()).isZero();
        assertThat(new DeployedModel.Bounds("SPHERE", null, null).center()).isNull();
        assertThat(new DeployedModel.Bounds("SPHERE", null, null).dimension()).isNull();
    }

    @Test
    void loaderDataCopiesItsTextureList() {
        LoaderData data = new LoaderData(1, "cls",
                List.of(new DeployedTexture("./a.png", "a"), new DeployedTexture(null, "b")));
        assertThat(data.version()).isEqualTo(1);
        assertThat(data.modelLoaderClassname()).isEqualTo("cls");
        assertThat(data.deployedTextures()).hasSize(2);
        assertThat(data.texturePaths()).containsExactly("a.png");
    }
}
