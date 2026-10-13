/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (imageviewer): the load status machine -- EMPTY ->
 * LOADING -> LOADED / ERROR, the blank-source guard, the null-caption
 * normalisation, and the requireSource guard on the transition markers.
 */
package org.jdesktop.wonderland.modules.imageviewer;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageModelTest {

    @Test
    void aFreshModelIsEmpty() {
        ImageModel model = new ImageModel();
        assertThat(model.getStatus()).isEqualTo(ImageModel.Status.EMPTY);
        assertThat(model.isReady()).isFalse();
        assertThat(model.getUri()).isNull();
    }

    @Test
    void settingASourceMovesToLoadingThenLoaded() {
        ImageModel model = new ImageModel();
        model.setImage("resources/images/photo.png", "A photo");

        assertThat(model.getStatus()).isEqualTo(ImageModel.Status.LOADING);
        assertThat(model.getUri()).isEqualTo("resources/images/photo.png");
        assertThat(model.getCaption()).isEqualTo("A photo");
        assertThat(model.isReady()).isFalse();

        model.markLoaded();
        assertThat(model.getStatus()).isEqualTo(ImageModel.Status.LOADED);
        assertThat(model.isReady()).isTrue();
    }

    @Test
    void aFailedResolveMovesToError() {
        ImageModel model = new ImageModel();
        model.setImage("http://unreachable", null);

        assertThat(model.getCaption()).isEmpty(); // null caption normalised
        model.markError();
        assertThat(model.getStatus()).isEqualTo(ImageModel.Status.ERROR);
        assertThat(model.isReady()).isFalse();
    }

    @Test
    void aBlankSourceIsRejected() {
        assertThatThrownBy(() -> new ImageModel().setImage("  ", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageModel().setImage(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transitionsBeforeAnySourceAreRejected() {
        ImageModel model = new ImageModel();
        assertThatThrownBy(model::markLoaded).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(model::markError).isInstanceOf(NullPointerException.class);
    }
}
