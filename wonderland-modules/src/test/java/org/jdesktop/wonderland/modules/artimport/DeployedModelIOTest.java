/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5b (artimport): the .dep / .ldr readers.
 * Drives DeployedModelIO across the full deployed-model record and its tolerant
 * gaps -- absent blocks read as null/zero, a non-numeric version/coord falls back
 * to 0 rather than throwing -- and the two hardening guarantees (malformed XML
 * and a DOCTYPE raise DeployedModelException). Also pins normalizeRelative.
 */
package org.jdesktop.wonderland.modules.artimport;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployedModelIOTest {

    private static final DeployedModelIO IO = new DeployedModelIO();

    @Test
    void aFullDeployedModelIsRead() {
        String dep = "<deployed-model>"
                + "<version>1</version>"
                + "<modelURL>wla://m/models/untitled.dae.gz</modelURL>"
                + "<loaderDataURL>wla://m/m.ldr</loaderDataURL>"
                + "<modelBGScale><x>0.0254</x><y>0.0254</y><z>0.0254</z></modelBGScale>"
                + "<modelBGTranslation><x>0</x><y>-0.025</y><z>0</z></modelBGTranslation>"
                + "<modelBGRotation><x>-1</x><y>0</y><z>0</z><angle>1.5707964</angle></modelBGRotation>"
                + "<modelBounds><boundsType>BOX</boundsType>"
                + "<center_x>1</center_x><center_y>2</center_y><center_z>3</center_z>"
                + "<dimension_x>100</dimension_x><dimension_y>2</dimension_y><dimension_z>300</dimension_z>"
                + "</modelBounds>"
                + "<modelLoaderClassname>org.jdesktop.KmzLoader</modelLoaderClassname>"
                + "</deployed-model>";

        DeployedModel model = IO.parseDeployedModel(dep);

        assertThat(model.version()).isEqualTo(1);
        assertThat(model.modelURL()).isEqualTo("wla://m/models/untitled.dae.gz");
        assertThat(model.loaderDataURL()).isEqualTo("wla://m/m.ldr");
        assertThat(model.backgroundScale().x).isEqualTo(0.0254f);
        assertThat(model.backgroundTranslation().y).isEqualTo(-0.025f);
        assertThat(model.backgroundRotationAxis().x).isEqualTo(-1f);
        assertThat(model.backgroundRotationAngle()).isEqualTo(1.5707964f);
        assertThat(model.modelLoaderClassname()).isEqualTo("org.jdesktop.KmzLoader");
        assertThat(model.bounds().type()).isEqualTo("BOX");
        assertThat(model.bounds().center().z).isEqualTo(3f);
        assertThat(model.bounds().longestDimension()).isEqualTo(300f);
    }

    @Test
    void anEmptyDeployedModelToleratesEveryAbsentBlock() {
        DeployedModel model = IO.parseDeployedModel("<deployed-model/>");

        assertThat(model.version()).isZero();
        assertThat(model.modelURL()).isNull();
        assertThat(model.loaderDataURL()).isNull();
        assertThat(model.backgroundScale()).isNull();
        assertThat(model.backgroundTranslation()).isNull();
        assertThat(model.backgroundRotationAxis()).isNull();
        assertThat(model.backgroundRotationAngle()).isZero(); // no rotation element
        assertThat(model.bounds()).isNull();
        assertThat(model.modelLoaderClassname()).isNull();
    }

    @Test
    void aPartialCoordinateBlockReadsMissingAxesAsZeroAndBadNumbersAsFallbacks() {
        String dep = "<deployed-model><version>nope</version>"
                + "<modelBGScale><x>5</x></modelBGScale>"
                + "<modelBounds><center_x>bad</center_x></modelBounds>"
                + "</deployed-model>";
        DeployedModel model = IO.parseDeployedModel(dep);

        assertThat(model.version()).isZero();                 // non-numeric -> 0
        assertThat(model.backgroundScale().x).isEqualTo(5f);
        assertThat(model.backgroundScale().y).isZero();       // absent axis -> 0
        assertThat(model.bounds().center().x).isZero();       // non-numeric coord -> 0
    }

    @Test
    void anEmptyBoundsDimensionHasNoLongestSide() {
        DeployedModel model = IO.parseDeployedModel(
                "<deployed-model><modelBounds><boundsType>SPHERE</boundsType></modelBounds>"
                + "</deployed-model>");
        assertThat(model.bounds().type()).isEqualTo("SPHERE");
        assertThat(model.bounds().dimension()).isNotNull();
        assertThat(model.bounds().longestDimension()).isZero();
    }

    @Test
    void aLoaderDataEnumeratesItsDeployedTextures() {
        String ldr = "<loader-data><version>1</version>"
                + "<modelLoaderClassname>org.jdesktop.KmzLoader</modelLoaderClassname>"
                + "<deployedTextures value=\"./untitled/texture0.png\" key=\"./untitled/texture0.png\"/>"
                + "<deployedTextures/>"
                + "</loader-data>";
        LoaderData data = IO.parseLoaderData(ldr);

        assertThat(data.version()).isEqualTo(1);
        assertThat(data.modelLoaderClassname()).isEqualTo("org.jdesktop.KmzLoader");
        assertThat(data.deployedTextures()).hasSize(2);
        assertThat(data.deployedTextures().get(0).normalizedValue())
                .isEqualTo("untitled/texture0.png");
        assertThat(data.deployedTextures().get(1).value()).isNull(); // absent attrs -> null
        // the null-valued entry is filtered out of the path list
        assertThat(data.texturePaths()).containsExactly("untitled/texture0.png");
    }

    @Test
    void anEmptyLoaderDataIsValid() {
        LoaderData data = IO.parseLoaderData("<loader-data/>");
        assertThat(data.version()).isZero();
        assertThat(data.modelLoaderClassname()).isNull();
        assertThat(data.deployedTextures()).isEmpty();
        assertThat(data.texturePaths()).isEmpty();
    }

    @Test
    void malformedXmlAndDoctypeAreRefused() {
        assertThatThrownBy(() -> IO.parseDeployedModel("<deployed-model>"))
                .isInstanceOf(DeployedModelIO.DeployedModelException.class)
                .hasMessageContaining("malformed deployed-model xml");
        assertThatThrownBy(() -> IO.parseLoaderData(
                "<!DOCTYPE x [<!ENTITY e \"y\">]><loader-data/>"))
                .isInstanceOf(DeployedModelIO.DeployedModelException.class);
    }

    @Test
    void normalizeRelativeStripsTheDotSlashPrefix() {
        assertThat(DeployedModelIO.normalizeRelative(null)).isNull();
        assertThat(DeployedModelIO.normalizeRelative("./a/b.png")).isEqualTo("a/b.png");
        assertThat(DeployedModelIO.normalizeRelative("a/b.png")).isEqualTo("a/b.png");
    }

    @Test
    void aDeployedTextureKeepsItsValueAndKey() {
        DeployedTexture texture = new DeployedTexture("./t.png", "K");
        assertThat(texture.value()).isEqualTo("./t.png");
        assertThat(texture.key()).isEqualTo("K");
        assertThat(texture.normalizedValue()).isEqualTo("t.png");
    }
}
