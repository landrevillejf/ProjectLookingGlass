/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (whiteboard): the surface model and its stroke value
 * type. Pins add/undo/clear, the drawing-order snapshot, the bounding box across
 * every point of every stroke, and the Stroke guards (>= 2 points, positive
 * width, defensive copy).
 */
package org.jdesktop.wonderland.modules.whiteboard;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class WhiteboardModelTest {

    private static Stroke stroke(int rgb, double x1, double y1, double x2, double y2) {
        return new Stroke(
                List.of(new Stroke.Point(x1, y1), new Stroke.Point(x2, y2)), rgb, 2f);
    }

    @Test
    void addUndoAndClearDriveTheDrawingOrder() {
        WhiteboardModel model = new WhiteboardModel();
        Stroke a = stroke(0xFF0000, 0, 0, 1, 1);
        Stroke b = stroke(0x00FF00, 2, 2, 3, 3);
        model.addStroke(a);
        model.addStroke(b);

        assertThat(model.strokeCount()).isEqualTo(2);
        assertThat(model.strokes()).containsExactly(a, b);

        assertThat(model.undoLast()).isSameAs(b);
        assertThat(model.strokes()).containsExactly(a);

        model.clear();
        assertThat(model.strokeCount()).isZero();
        assertThat(model.undoLast()).isNull();
    }

    @Test
    void addingNullIsRejected() {
        assertThatThrownBy(() -> new WhiteboardModel().addStroke(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsSpanEveryPointOfEveryStroke() {
        WhiteboardModel model = new WhiteboardModel();
        assertThat(model.bounds()).isNull(); // empty

        model.addStroke(stroke(0, -1, 5, 3, 2));
        model.addStroke(stroke(0, 4, -2, 1, 0));

        double[] bounds = model.bounds();
        assertThat(bounds).hasSize(4);
        assertThat(bounds[0]).isCloseTo(-1.0, within(1e-9)); // minX
        assertThat(bounds[1]).isCloseTo(-2.0, within(1e-9)); // minY
        assertThat(bounds[2]).isCloseTo(4.0, within(1e-9));  // maxX
        assertThat(bounds[3]).isCloseTo(5.0, within(1e-9));  // maxY
    }

    @Test
    void aStrokeRejectsTooFewPointsOrANonPositiveWidth() {
        List<Stroke.Point> one = List.of(new Stroke.Point(0, 0));
        assertThatThrownBy(() -> new Stroke(one, 0, 1f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Stroke(null, 0, 1f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strokeWidthMustBePositiveAndPointsAreCopiedDefensively() {
        assertThatThrownBy(() ->
                new Stroke(List.of(new Stroke.Point(0, 0), new Stroke.Point(1, 1)), 0, 0f))
                .isInstanceOf(IllegalArgumentException.class);

        List<Stroke.Point> source = new java.util.ArrayList<>();
        source.add(new Stroke.Point(0, 0));
        source.add(new Stroke.Point(1, 1));
        Stroke stroke = new Stroke(source, 0xABCDEF, 3f);
        source.add(new Stroke.Point(2, 2)); // mutating the caller's list must not leak

        assertThat(stroke.getPoints()).hasSize(2);
        assertThat(stroke.getRgb()).isEqualTo(0xABCDEF);
        assertThat(stroke.getWidth()).isEqualTo(3f);
        assertThat(stroke.getPoints().get(0).toString()).isEqualTo("(0.0, 0.0)");
    }
}
