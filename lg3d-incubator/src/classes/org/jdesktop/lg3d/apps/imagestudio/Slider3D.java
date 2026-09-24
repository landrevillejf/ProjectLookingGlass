/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.imagestudio;

import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.Transform3D;
import org.jdesktop.lg3d.sg.TransformGroup;
import org.jdesktop.lg3d.sg.utils.transparency.TransparencyOrderedGroup;
import org.jdesktop.lg3d.utils.action.ActionBooleanFloat3;
import org.jdesktop.lg3d.utils.action.ActionFloat3;
import org.jdesktop.lg3d.utils.eventadapter.MouseDraggedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MousePressedEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * A horizontal 3D slider: a glassy track with a draggable knob, a name label
 * and a live value label. Dragging maps the pointer's local X across the track
 * to a value in {@code [min, max]} and notifies the {@link Listener}.
 *
 * <p>Interaction uses {@link MousePressedEventAdapter} (which reports both
 * press and release with the local intersection point) and
 * {@link MouseDraggedEventAdapter} (local drag point). Both deliver coordinates
 * in this component's local space, whose origin is the slider centre, so the
 * track spans {@code [-trackW/2, +trackW/2]}.</p>
 *
 * <p>The slider is inert until {@link #configure} arms it with a parameter
 * range; {@link #setIdle} greys it out again. It owns no editing semantics --
 * the toolbar decides how a value maps to an operation.</p>
 */
public class Slider3D extends Component3D {

    /** Notified whenever the knob moves to a new value. */
    public interface Listener {
        void sliderAdjusted(float value);
    }

    private final float width;
    private final float height;
    private final float trackW;
    private final float trackH;
    private final float trackY;
    private final float textH;

    private float min = 0.0f;
    private float max = 1.0f;
    private float value = 0.0f;
    private String fmt = "%.2f";
    private boolean intFmt = false;
    private boolean armed = false;

    private Listener listener;

    private final GlassyText2D nameText;
    private final GlassyText2D valueText;
    private final GlassyPanel trackPanel;
    private final TransformGroup knobTg;
    private final Transform3D knobXform = new Transform3D();

    public Slider3D(float width, float height) {
        this.width = width;
        this.height = height;
        this.textH = Math.max(0.0022f, height * 0.19f);
        this.trackW = width * 0.90f;
        this.trackH = Math.max(0.0016f, height * 0.13f);
        this.trackY = -height * 0.20f;

        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();

        // Track.
        trackPanel = new GlassyPanel(trackW, trackH, 0.002f,
                Ui3D.appearance(Ui3D.TRACK_OFF));
        trackPanel.setCapability(Shape3D.ALLOW_APPEARANCE_WRITE);
        tog.addChild(Ui3D.at(trackPanel, 0.0f, trackY, 0.0f));

        // Knob (moved by rewriting its TransformGroup).
        float knobW = Math.max(0.0018f, trackH * 0.8f);
        float knobH = trackH * 2.8f;
        GlassyPanel knob = new GlassyPanel(knobW, knobH, 0.003f,
                Ui3D.appearance(Ui3D.KNOB));
        knobTg = new TransformGroup();
        knobTg.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
        knobTg.addChild(knob);
        tog.addChild(knobTg);

        // Name (left) and value (right) labels along the top.
        float labelY = height * 0.5f - textH * 0.6f;
        nameText = Ui3D.makeText("No parameter", width * 0.55f, textH,
                Ui3D.TEXT_DIM, GlassyText2D.Alignment.LEFT);
        tog.addChild(Ui3D.at(nameText, -width * 0.46f, labelY - textH * 0.5f, 0.001f));
        valueText = Ui3D.makeText("", width * 0.34f, textH,
                Ui3D.TEXT_ACCENT, GlassyText2D.Alignment.RIGHT);
        tog.addChild(Ui3D.at(valueText, width * 0.46f, labelY - textH * 0.5f, 0.001f));

        addChild(tog);
        setCursor(Cursor3D.SMALL_CURSOR);

        // Press starts an adjustment; drag continues it. Release is ignored --
        // the toolbar commits the armed operation when it is deselected.
        addListener(new MousePressedEventAdapter(new ActionBooleanFloat3() {
            public void performAction(LgEventSource source, boolean pressed,
                    float x, float y, float z) {
                if (pressed) {
                    setFromLocalX(x);
                }
            }
        }));
        addListener(new MouseDraggedEventAdapter(new ActionFloat3() {
            public void performAction(LgEventSource source, float x, float y, float z) {
                setFromLocalX(x);
            }
        }));

        updateKnob();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Arm the slider for a named parameter range and set its initial value. */
    public void configure(String name, float min, float max, float value,
            String fmt, boolean intFmt) {
        this.min = Math.min(min, max);
        this.max = Math.max(min, max);
        this.fmt = (fmt != null) ? fmt : "%.2f";
        this.intFmt = intFmt;
        this.armed = true;
        nameText.setText(name);
        trackPanel.setAppearance(Ui3D.appearance(Ui3D.TRACK));
        setValue(value);
    }

    /** Disarm and grey out the slider. */
    public void setIdle() {
        armed = false;
        nameText.setText("No parameter");
        valueText.setText("");
        trackPanel.setAppearance(Ui3D.appearance(Ui3D.TRACK_OFF));
    }

    public boolean isArmed() {
        return armed;
    }

    public float getValue() {
        return value;
    }

    /** Set the value programmatically (updates knob + label, no listener fire). */
    public void setValue(float v) {
        value = clamp(v, min, max);
        updateKnob();
        updateValueText();
    }

    private void setFromLocalX(float x) {
        if (!armed) {
            return;
        }
        float t = (x + trackW * 0.5f) / trackW;
        t = clamp(t, 0.0f, 1.0f);
        float v = min + t * (max - min);
        setValue(v);
        if (listener != null) {
            listener.sliderAdjusted(value);
        }
    }

    private void updateKnob() {
        float span = (max - min);
        float t = (span <= 0.0f) ? 0.0f : (value - min) / span;
        float x = -trackW * 0.5f + clamp(t, 0.0f, 1.0f) * trackW;
        knobXform.setTranslation(new Vector3f(x, trackY, 0.0035f));
        knobTg.setTransform(knobXform);
    }

    private void updateValueText() {
        String s = intFmt
                ? String.format(fmt, Integer.valueOf(Math.round(value)))
                : String.format(fmt, Float.valueOf(value));
        valueText.setText(s);
    }

    private static float clamp(float v, float lo, float hi) {
        return (v < lo) ? lo : ((v > hi) ? hi : v);
    }
}
