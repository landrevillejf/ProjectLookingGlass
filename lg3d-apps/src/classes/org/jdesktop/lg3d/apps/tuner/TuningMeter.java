/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.tuner;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.swing.JComponent;

/**
 * The tuner's cent-deviation meter: a horizontal scale from flat (-) through in
 * tune (0) to sharp (+) with a needle that eases toward the current reading and
 * turns green inside the tolerance window.
 *
 * <p>It is a plain {@link JComponent} painted with Java&nbsp;2D (no Synth, no
 * Java&nbsp;3D), so it renders correctly both on a real peer and into the
 * {@code SwingNode} offscreen buffer the 3D desktop uses. The needle geometry is
 * factored into the pure static {@link #needleFraction(float, float)} and
 * {@link #isInTune(float, float)} helpers, which the headless tests assert on
 * without a display.</p>
 */
public class TuningMeter extends JComponent {

    private static final long serialVersionUID = 1L;

    /** Default full-scale range in cents: the needle spans -RANGE..+RANGE. */
    public static final float DEFAULT_RANGE_CENTS = 50f;

    /** Default in-tune half-window in cents (green zone is -TOL..+TOL). */
    public static final float DEFAULT_TOLERANCE_CENTS = 5f;

    // No hardcoded palette: every colour is resolved live from the active
    // look-and-feel through TunerTheme, so the meter matches its host (system
    // L&F in 2D, hosted Metal L&F in the 3D SwingNode).

    /** How fast the displayed needle chases the target each reading (0..1). */
    private static final float EASE = 0.45f;

    private float rangeCents = DEFAULT_RANGE_CENTS;
    private float toleranceCents = DEFAULT_TOLERANCE_CENTS;

    private float targetCents;
    private float displayCents;
    private boolean voiced;
    private boolean inTune;

    /** Builds an idle meter at the default range and tolerance. */
    public TuningMeter() {
        setOpaque(true);
        setBackground(TunerTheme.background());
        setPreferredSize(new Dimension(420, 120));
        // A plain JComponent has no UI delegate to install a font, and getFont()
        // returns null when it has no peer (headless, or painted offscreen into
        // the 3D desktop's SwingNode buffer). Set one explicitly so the captions
        // in paintComponent never NPE.
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    }

    /**
     * The needle position for a deviation, as a fraction of the meter width:
     * 0 at {@code -range}, 0.5 at 0, 1 at {@code +range}, clamped. A non-finite
     * deviation maps to the centre.
     *
     * @param cents the deviation in cents
     * @param range the full-scale range in cents (must be positive)
     * @return the needle fraction in [0, 1]
     */
    public static float needleFraction(float cents, float range) {
        if (range <= 0f || Float.isNaN(cents) || Float.isInfinite(cents)) {
            return 0.5f;
        }
        float fraction = (cents + range) / (2f * range);
        return Math.max(0f, Math.min(1f, fraction));
    }

    /**
     * Whether a deviation is within the in-tune tolerance.
     *
     * @param cents     the deviation in cents
     * @param tolerance the acceptable absolute deviation
     * @return true when {@code |cents| <= |tolerance|}
     */
    public static boolean isInTune(float cents, float tolerance) {
        if (Float.isNaN(cents)) {
            return false;
        }
        return Math.abs(cents) <= Math.abs(tolerance);
    }

    /**
     * Feeds a new reading. The needle eases toward {@code cents}; when
     * {@code voiced} is false the reading is ignored and the meter idles at the
     * centre. Safe to call from the EDT (the panel marshals detections onto it).
     *
     * @param cents  the deviation from the target note in cents
     * @param voiced whether a clear pitch was detected
     */
    public void setReading(float cents, boolean voiced) {
        this.voiced = voiced;
        if (!voiced || Float.isNaN(cents)) {
            // Ease back to centre while there is no clear pitch.
            this.targetCents = 0f;
            this.inTune = false;
        } else {
            this.targetCents = cents;
            this.inTune = isInTune(cents, toleranceCents);
        }
        this.displayCents += (this.targetCents - this.displayCents) * EASE;
        repaint();
    }

    /** Resets the needle to the idle centre (e.g. when capture stops). */
    public void reset() {
        this.voiced = false;
        this.inTune = false;
        this.targetCents = 0f;
        this.displayCents = 0f;
        repaint();
    }

    /** The deviation currently shown by the needle, in cents. */
    public float getDisplayCents() {
        return displayCents;
    }

    /** Whether the last reading was inside the tolerance window. */
    public boolean isInTune() {
        return inTune;
    }

    /** Whether the last reading had a clear pitch. */
    public boolean isVoiced() {
        return voiced;
    }

    /** The full-scale range in cents. */
    public float getRangeCents() {
        return rangeCents;
    }

    /** Sets the full-scale range in cents (clamped to a positive value). */
    public void setRangeCents(float rangeCents) {
        this.rangeCents = (rangeCents <= 0f) ? DEFAULT_RANGE_CENTS : rangeCents;
        repaint();
    }

    /** The in-tune half-window in cents. */
    public float getToleranceCents() {
        return toleranceCents;
    }

    /** Sets the in-tune half-window in cents (clamped to a non-negative value). */
    public void setToleranceCents(float toleranceCents) {
        this.toleranceCents = Math.max(0f, toleranceCents);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            g.setColor(TunerTheme.background());
            g.fillRect(0, 0, w, h);

            int pad = Math.max(18, w / 24);
            int barY = (int) (h * 0.60f);
            int barLeft = pad;
            int barRight = w - pad;
            int barWidth = Math.max(1, barRight - barLeft);

            // In-tune green window centred on zero.
            float tolFraction = Math.min(0.5f, toleranceCents / (2f * rangeCents));
            int centreX = barLeft + barWidth / 2;
            int greenHalf = (int) (barWidth * tolFraction);
            g.setColor(TunerTheme.inTuneWindow());
            g.fill(new RoundRectangle2D.Float(centreX - greenHalf, barY - 16,
                    greenHalf * 2, 32, 8, 8));

            // Scale line.
            g.setColor(TunerTheme.border());
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(barLeft, barY, barRight, barY);

            // Ticks every 10 cents, taller at the centre and the extremes.
            g.setColor(TunerTheme.dimForeground());
            for (int cents = -(int) rangeCents; cents <= (int) rangeCents; cents += 10) {
                int x = barLeft + (int) (needleFraction(cents, rangeCents) * barWidth);
                int half = (cents == 0) ? 12 : (cents % 50 == 0 ? 9 : 5);
                g.setStroke(new BasicStroke(cents == 0 ? 2.4f : 1.4f));
                g.drawLine(x, barY - half, x, barY + half);
            }

            // Flat / sharp captions.
            g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
            g.setColor(TunerTheme.dimForeground());
            FontMetrics fm = g.getFontMetrics();
            g.drawString("flat", barLeft, barY + 26);
            String sharp = "sharp";
            g.drawString(sharp, barRight - fm.stringWidth(sharp), barY + 26);
            String inTuneLabel = "in tune";
            g.drawString(inTuneLabel, centreX - fm.stringWidth(inTuneLabel) / 2,
                    barY - 22);

            // Needle.
            Color needleColor = !voiced ? TunerTheme.idle()
                    : inTune ? TunerTheme.inTune()
                    : Math.abs(displayCents) <= 2f * toleranceCents
                            ? TunerTheme.nearTune() : TunerTheme.outTune();
            int needleX = barLeft + (int) (needleFraction(displayCents, rangeCents) * barWidth);
            g.setColor(needleColor);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(needleX, barY - 20, needleX, barY + 20);
            // Needle head.
            g.fillOval(needleX - 6, barY - 28, 12, 12);
        } finally {
            g.dispose();
        }
    }
}
