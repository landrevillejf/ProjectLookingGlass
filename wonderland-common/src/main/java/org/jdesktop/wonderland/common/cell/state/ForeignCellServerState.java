/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-common (cell states): a cell whose type this port does not implement.
 *
 * A stock Open Wonderland world is not made only of models. The combined world's
 * 41 cells include four posters, a PDF viewer, a whiteboard and a remote
 * application window; the meeting complex has the same again. Each of those is a
 * module with its own <xml-root>-cell and its own server state, and this port
 * implements none of their renderers -- so before this class existed, the world
 * loader unmarshalled one, JAXB reported an unknown root, the loader caught it
 * and the cell was GONE. Nine of forty-one objects in the combined world and
 * four of eleven in the meeting complex simply did not appear, and a person
 * looking at the result saw an empty field with two posts in it and concluded --
 * correctly -- that the world had been scrapped.
 *
 * The loss was never the missing renderer. It was that "I cannot draw this" was
 * allowed to mean "this is not in the world". A poster's picture, a document's
 * page and a window's contents are all art the world module already ships and
 * the asset store already serves; the only thing missing was a place to stand
 * while looking at them. So this state keeps everything the world said about the
 * cell -- its name, its placement, the type it was written as, and the asset it
 * displays -- and gives any downstream reader the same CellServerState interface
 * a model cell gets. A cell that cannot be drawn is then a cell drawn as its
 * picture, and a cell whose picture cannot be drawn is a named marker at the
 * right coordinates. Neither is a cell deleted from the world.
 *
 * It is deliberately not a @ServerState and carries no JAXB annotations of its
 * own: nothing marshals one. {@link ForeignCellReader} in the asset module fills
 * it by reading the cell file's own XML, which is the only way to recover the
 * half of a file JAXB refuses to open at all.
 */
package org.jdesktop.wonderland.common.cell.state;

/**
 * The server state of a cell whose module is not ported, holding what the world
 * file itself says about it: name, placement and the asset it shows.
 */
public class ForeignCellServerState extends CellServerState {

    /**
     * The element the world file used as its root, e.g. {@code image-viewer-cell}.
     * Kept because it is the difference between "an object here" and "a poster
     * here": a renderer that can draw a picture looks for exactly this name, and
     * a cell of an unknown type stays a named marker.
     */
    private String cellType;

    /**
     * The asset the cell displays, in the world's own {@code wla://} spelling, or
     * null when the cell names none. A poster, a PDF page and a remote window are
     * all "show me this file at this place", so one field covers them; which one
     * it is stays in {@link #cellType}.
     */
    private String assetURI;

    /**
     * How wide a picture's pixel is in the world, in metres, as the cell itself
     * records it. Every GUI cell in the shipped worlds writes
     * {@code <pixelScaleX>0.01</pixelScaleX>}, and the two viewers that do write
     * a size agree with it: the combined world's PDF screen is 640x480 px and the
     * brainstorming pad's whiteboard 800x600 px, which at one centimetre per pixel
     * are the 6.4x4.8 m and 8x6 m planes standing in those fields. A poster's
     * image-viewer cell writes no size at all -- its extent is its picture's own
     * pixels times this -- so the scale is the one number that has to survive the
     * read, and 0.01 is the value the format uses when it says nothing.
     */
    public static final double DEFAULT_PIXEL_SCALE = 0.01;

    private double pixelScaleX = DEFAULT_PIXEL_SCALE;
    private double pixelScaleY = DEFAULT_PIXEL_SCALE;

    /**
     * The picture's own size in pixels as the cell recorded it, or 0 when it
     * recorded none. A document and a whiteboard know theirs ({@code
     * <preferredWidth>}); a poster's is whatever its image turns out to be, which
     * is a fact only a reader holding the bytes has. Zero is therefore "ask the
     * picture", not "no size".
     */
    private double pixelWidth;
    private double pixelHeight;

    public ForeignCellServerState() {
    }

    @Override
    public String getServerClassName() {
        return ForeignCellServerState.class.getName();
    }

    public String getCellType() {
        return cellType;
    }

    public void setCellType(String cellType) {
        this.cellType = cellType;
    }

    public String getAssetURI() {
        return assetURI;
    }

    public void setAssetURI(String assetURI) {
        this.assetURI = assetURI;
    }

    public double getPixelScaleX() {
        return pixelScaleX;
    }

    public void setPixelScaleX(double pixelScaleX) {
        this.pixelScaleX = pixelScaleX;
    }

    public double getPixelScaleY() {
        return pixelScaleY;
    }

    public void setPixelScaleY(double pixelScaleY) {
        this.pixelScaleY = pixelScaleY;
    }

    public double getPixelWidth() {
        return pixelWidth;
    }

    public double getPixelHeight() {
        return pixelHeight;
    }

    /**
     * Record the picture's size in pixels. A value the world did not write, or
     * wrote as something not positive, leaves the stored size at 0 -- which the
     * renderer reads as "measure the picture itself" rather than as a plane with
     * no area, and a degenerate quad is one of the things Java 3D refuses.
     */
    public void setPixelSize(double width, double height) {
        this.pixelWidth = (width > 0.0 && Double.isFinite(width)) ? width : 0.0;
        this.pixelHeight = (height > 0.0 && Double.isFinite(height)) ? height : 0.0;
    }
}
