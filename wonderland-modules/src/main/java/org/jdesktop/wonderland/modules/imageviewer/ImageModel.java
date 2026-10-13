/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (imageviewer): the headless image-viewer model.
 *
 * Tracks the image a viewer cell displays -- its source URI, caption and load
 * status -- with no Java 3D texture or Swing icon yet. The renderer that turns
 * a LOADED image into a textured quad is the deferred half (M6). Keeping the
 * status machine here lets the load lifecycle be tested headless.
 */
package org.jdesktop.wonderland.modules.imageviewer;

import java.util.Objects;

/** The state a viewer cell holds about the image it shows. */
public final class ImageModel {

    /** The load lifecycle the viewer walks through. */
    public enum Status {
        /** No image source set. */
        EMPTY,
        /** A source is set and its bytes are being fetched. */
        LOADING,
        /** The image resolved and is ready to render. */
        LOADED,
        /** The source failed to resolve. */
        ERROR
    }

    private String uri;
    private String caption = "";
    private Status status = Status.EMPTY;

    /**
     * Set the image source, moving the model to LOADING. A blank source is
     * rejected; the model stays EMPTY until a real one is supplied.
     *
     * @param imageUri non-blank source URI
     * @param imageCaption human-readable caption (null becomes empty)
     */
    public void setImage(String imageUri, String imageCaption) {
        if (imageUri == null || imageUri.trim().isEmpty()) {
            throw new IllegalArgumentException("image uri must not be blank");
        }
        this.uri = imageUri;
        this.caption = imageCaption == null ? "" : imageCaption;
        this.status = Status.LOADING;
    }

    /** Mark the current source resolved. */
    public void markLoaded() {
        requireSource();
        this.status = Status.LOADED;
    }

    /** Mark the current source failed. */
    public void markError() {
        requireSource();
        this.status = Status.ERROR;
    }

    public String getUri() {
        return uri;
    }

    public String getCaption() {
        return caption;
    }

    public Status getStatus() {
        return status;
    }

    /** Whether the image is ready for the (deferred) renderer to paint. */
    public boolean isReady() {
        return status == Status.LOADED;
    }

    private void requireSource() {
        Objects.requireNonNull(uri, "no image source has been set");
    }
}
