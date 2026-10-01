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
package org.jdesktop.lg3d.apps.videoconference;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * The pre-call camera preview surface shown in the lobby.
 *
 * <p>When a {@link CameraCapture} backend is present it paints the live frames
 * the backend delivers (letter-boxed to keep the aspect ratio). When it is not
 * &mdash; the default, since the project ships no native capture library
 * &mdash; it paints an honest, gently animated "camera preview unavailable"
 * placeholder that also carries a status line explaining where the real video
 * will appear (in the launched meeting window).</p>
 *
 * <p>The component is plain Swing with hand painting only (no Synth widgets), so
 * it renders correctly into a {@code SwingNode} offscreen buffer in the 3D
 * desktop and into an MDI frame in the 2D desktop. The placeholder pulse runs on
 * a {@link Timer} that is started on {@link #addNotify()} and stopped on
 * {@link #removeNotify()} / {@link #dispose()}, so it never leaks a timer when
 * the window closes.</p>
 */
public class CameraPreview extends JPanel implements CameraCapture.FrameListener {

    /** Preferred 16:9 preview size in native pixels. */
    public static final int WIDTH_PX = 480;
    public static final int HEIGHT_PX = 270;

    private static final Color BG_TOP = new Color(0x1B, 0x22, 0x2B);
    private static final Color BG_BOTTOM = new Color(0x10, 0x15, 0x1B);
    private static final Color PLACEHOLDER_TEXT = new Color(0x9A, 0xA7, 0xB4);
    private static final Color ACCENT = new Color(0x4C, 0xAF, 0x50);

    private static final int PULSE_INTERVAL_MS = 60;

    private volatile BufferedImage frame;
    private volatile String statusText = "Camera preview unavailable \u2014 video will appear in the meeting window";
    private volatile boolean videoEnabled = true;

    private CameraCapture capture;
    private String deviceId;

    private final Timer pulseTimer;
    private float phase;

    /** Creates the preview with no backend attached. */
    public CameraPreview() {
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setOpaque(true);
        setBackground(BG_BOTTOM);
        pulseTimer = new Timer(PULSE_INTERVAL_MS, e -> {
            phase += 0.12f;
            if (phase > (float) (Math.PI * 2)) {
                phase -= (float) (Math.PI * 2);
            }
            repaint();
        });
        pulseTimer.setRepeats(true);
    }

    /**
     * Attaches a capture backend and starts previewing.
     *
     * @param capture  the backend (may be null / unavailable)
     * @param deviceId the device to open, or null for the backend default
     */
    public void attach(CameraCapture capture, String deviceId) {
        detach();
        this.capture = capture;
        this.deviceId = deviceId;
        if (capture != null && capture.isAvailable()) {
            capture.addFrameListener(this);
            boolean started = capture.start(deviceId);
            statusText = started
                    ? "Live preview \u2014 " + capture.backendName()
                    : "Camera busy or unavailable";
        } else {
            statusText = "Camera preview unavailable \u2014 video will appear in the meeting window";
        }
        repaint();
    }

    /** Stops and detaches the current backend, if any. */
    public void detach() {
        if (capture != null) {
            capture.removeFrameListener(this);
            capture.stop();
            capture = null;
        }
        frame = null;
    }

    /** @return the backend currently attached (may be null). */
    public CameraCapture getCapture() {
        return capture;
    }

    /** @return true when a live frame has been received and will be painted. */
    public boolean hasLiveFrame() {
        return frame != null && videoEnabled;
    }

    /** Toggles whether the local video is shown (mirrors the meeting's camera-off state). */
    public void setVideoEnabled(boolean enabled) {
        this.videoEnabled = enabled;
        repaint();
    }

    /** @return true when the local preview is being shown. */
    public boolean isVideoEnabled() {
        return videoEnabled;
    }

    /** Overrides the placeholder status line. */
    public void setStatusText(String text) {
        this.statusText = (text == null) ? "" : text;
        repaint();
    }

    /** @return the current status line. */
    public String getStatusText() {
        return statusText;
    }

    @Override
    public void onFrame(BufferedImage newFrame) {
        this.frame = newFrame;
        repaint();
    }

    /** Releases the backend and stops the pulse timer. */
    public void dispose() {
        pulseTimer.stop();
        detach();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        pulseTimer.start();
    }

    @Override
    public void removeNotify() {
        pulseTimer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        int w = getWidth();
        int h = getHeight();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g2.setPaint(new GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
        g2.fillRect(0, 0, w, h);

        BufferedImage f = frame;
        if (f != null && videoEnabled && f.getWidth() > 0 && f.getHeight() > 0) {
            drawLetterBoxed(g2, f, w, h);
        } else {
            drawPlaceholder(g2, w, h);
        }
        g2.dispose();
    }

    private void drawLetterBoxed(Graphics2D g2, BufferedImage img, int w, int h) {
        double scale = Math.min((double) w / img.getWidth(), (double) h / img.getHeight());
        int dw = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int dh = Math.max(1, (int) Math.round(img.getHeight() * scale));
        int dx = (w - dw) / 2;
        int dy = (h - dh) / 2;
        g2.drawImage(img, dx, dy, dw, dh, null);
    }

    private void drawPlaceholder(Graphics2D g2, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;

        // Pulsing rings behind a camera glyph, animated only while showing the
        // placeholder so the surface reads as "live" even with no backend.
        float pulse = (float) (0.5 + 0.5 * Math.sin(phase));
        for (int i = 0; i < 3; i++) {
            int r = 34 + i * 16 + (int) (pulse * 8);
            int alpha = (int) (60 * (1.0 - i * 0.28) * (0.5 + 0.5 * pulse));
            g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(),
                    Math.max(0, Math.min(255, alpha))));
            g2.setStroke(new BasicStroke(2f));
            g2.drawOval(cx - r, cy - r - 12, r * 2, r * 2);
        }

        drawCameraGlyph(g2, cx, cy - 12, 54);

        // Status line, wrapped to the component width.
        Font f = getFont();
        if (f == null) {
            f = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            setFont(f);
        }
        FontMetrics fm = getFontMetrics(f.deriveFont(Font.PLAIN, 12f));
        g2.setColor(PLACEHOLDER_TEXT);
        g2.setFont(f.deriveFont(Font.PLAIN, 12f));
        String msg = (statusText == null) ? "" : statusText;
        int maxW = Math.max(20, w - 24);
        int y = cy + 46;
        for (String line : wrap(msg, fm, maxW)) {
            int tw = fm.stringWidth(line);
            g2.drawString(line, cx - tw / 2, y);
            y += fm.getHeight();
        }

        // A small "video off" hint when the user toggled the camera off.
        if (!videoEnabled) {
            g2.setColor(new Color(0xE5, 0x53, 0x4B));
            g2.fill(new RoundRectangle2D.Double(cx - 46, 10, 92, 22, 10, 10));
            g2.setColor(Color.WHITE);
            g2.setFont(f.deriveFont(Font.BOLD, 11f));
            String off = "CAMERA OFF";
            int tw = g2.getFontMetrics().stringWidth(off);
            g2.drawString(off, cx - tw / 2, 25);
        }
    }

    /** Draws a simple video-camera glyph (body + lens triangle) centred at x,y. */
    private static void drawCameraGlyph(Graphics2D g2, int x, int y, int size) {
        int bw = (int) (size * 0.62);
        int bh = (int) (size * 0.46);
        int bx = x - bw / 2 - (int) (size * 0.06);
        int by = y - bh / 2;
        g2.setColor(PLACEHOLDER_TEXT);
        g2.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 8, 8));
        // Lens hood triangle on the right.
        int tx = bx + bw;
        int th = (int) (bh * 0.6);
        g2.fillPolygon(
                new int[]{tx + 2, tx + (int) (size * 0.22), tx + (int) (size * 0.22)},
                new int[]{y, y - th / 2, y + th / 2}, 3);
    }

    private static java.util.List<String> wrap(String text, FontMetrics fm, int maxW) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = (cur.length() == 0) ? word : cur + " " + word;
            if (fm.stringWidth(trial) <= maxW || cur.length() == 0) {
                cur.setLength(0);
                cur.append(trial);
            } else {
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        return lines;
    }
}
