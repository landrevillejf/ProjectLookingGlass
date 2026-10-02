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
package org.jdesktop.lg3d.apps.audioplayer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JPanel;

/**
 * A Swing "cover-flow" carousel of album sleeves - the 2D counterpart of the 3D
 * {@code CDViewer} carousel, so the music library can be browsed by album cover
 * in <em>both</em> desktops (the 2D/Swing desktop has no Java 3D). The centre
 * sleeve is drawn large and square, its neighbours progressively smaller and
 * sheared to either side; the mouse wheel revolves the strip, a single click
 * selects (fires {@link #setOnSelect}), and a double click plays (fires
 * {@link #setOnPlay}).
 *
 * <p>Covers are the cached image files resolved by {@link AlbumIndex.Album};
 * when an album has none (or it cannot be read) a generated placeholder sleeve
 * carries the album title, so the strip is never blank. Images are decoded
 * lazily on paint and cached by path. Nothing is loaded or painted at
 * construction, so the component builds and is asserted on headless - only the
 * model wiring ({@link #setAlbums}, {@link #revolve}, the callbacks) is exercised
 * there.</p>
 */
public class AlbumCoverFlow extends JPanel {

    private static final long serialVersionUID = 1L;

    /** The edge length of the centre sleeve in pixels. */
    static final int COVER_SIZE = 150;
    /** How many sleeves are visible to each side of the centre. */
    static final int SIDE_COUNT = 3;

    private final List<AlbumIndex.Album> albums = new ArrayList<>();
    private final Map<String, BufferedImage> coverCache = new HashMap<>();
    private int center;

    private Consumer<AlbumIndex.Album> onSelect;
    private Consumer<AlbumIndex.Album> onPlay;

    /** Builds an empty cover-flow strip. */
    public AlbumCoverFlow() {
        setOpaque(true);
        setBackground(new Color(0x1B1F27));
        setPreferredSize(new Dimension(640, COVER_SIZE + 90));
        addMouseWheelListener(e -> revolve(e.getWheelRotation()));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                AlbumIndex.Album hit = albumAt(e.getX(), e.getY());
                if (hit == null) {
                    return;
                }
                if (e.getClickCount() == 2 && onPlay != null) {
                    onPlay.accept(hit);
                } else if (onSelect != null) {
                    onSelect.accept(hit);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /**
     * Replaces the displayed albums and resets the carousel to the first.
     *
     * @param albums the albums to show; null clears the strip
     */
    public void setAlbums(List<AlbumIndex.Album> albums) {
        this.albums.clear();
        if (albums != null) {
            for (AlbumIndex.Album a : albums) {
                if (a != null) {
                    this.albums.add(a);
                }
            }
        }
        this.center = 0;
        repaint();
    }

    /** @return an unmodifiable snapshot of the displayed albums. */
    public List<AlbumIndex.Album> getAlbums() {
        return List.copyOf(albums);
    }

    /** @return the number of albums shown. */
    public int albumCount() {
        return albums.size();
    }

    /** @return true when no albums are shown. */
    public boolean isEmpty() {
        return albums.isEmpty();
    }

    /** @return the index of the centre (selected) album, or -1 when empty. */
    public int getCenter() {
        return albums.isEmpty() ? -1 : center;
    }

    /** The centre album, or null when the strip is empty. */
    public AlbumIndex.Album selectedAlbum() {
        return albums.isEmpty() ? null : albums.get(center);
    }

    /**
     * Selects a specific album index (clamped), if present.
     *
     * @param index the album index
     */
    public void setCenter(int index) {
        if (!albums.isEmpty()) {
            this.center = Math.max(0, Math.min(index, albums.size() - 1));
            repaint();
        }
    }

    /**
     * Revolves the strip by {@code steps} sleeves (positive = forward), wrapping
     * around the ends.
     *
     * @param steps the number of sleeves to advance
     */
    public void revolve(int steps) {
        if (albums.isEmpty() || steps == 0) {
            return;
        }
        int n = albums.size();
        center = ((center + steps) % n + n) % n;
        repaint();
    }

    /** Sets the single-click selection callback. */
    public void setOnSelect(Consumer<AlbumIndex.Album> onSelect) {
        this.onSelect = onSelect;
    }

    /** Sets the double-click play callback. */
    public void setOnPlay(Consumer<AlbumIndex.Album> onPlay) {
        this.onPlay = onPlay;
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (albums.isEmpty()) {
                drawMessage(g2, "No albums yet - rip a CD or add music.");
                return;
            }
            int w = getWidth();
            int h = getHeight();
            int cy = h / 2;
            // Centre sleeve, then neighbours receding to each side.
            drawSleeve(g2, albums.get(center), w / 2, cy, COVER_SIZE, 1.0f, 0.0f);
            for (int d = 1; d <= SIDE_COUNT; d++) {
                int leftIdx = ((center - d) % albums.size() + albums.size()) % albums.size();
                int rightIdx = (center + d) % albums.size();
                float scale = 1.0f - 0.14f * d;
                int size = Math.round(COVER_SIZE * scale);
                int dx = COVER_SIZE / 2 + 26 * d + size / 2;
                float shear = 0.22f * d;
                drawSleeve(g2, albums.get(leftIdx), w / 2 - dx, cy, size, scale, -shear);
                drawSleeve(g2, albums.get(rightIdx), w / 2 + dx, cy, size, scale, shear);
            }
            AlbumIndex.Album sel = albums.get(center);
            drawCaption(g2, sel.label(), w / 2, cy + COVER_SIZE / 2 + 24);
        } finally {
            g2.dispose();
        }
    }

    private void drawSleeve(Graphics2D g2, AlbumIndex.Album album, int cx, int cy,
                            int size, float scale, float shear) {
        BufferedImage img = imageFor(album, size);
        AffineTransform save = g2.getTransform();
        AffineTransform at = new AffineTransform();
        at.translate(cx, cy);
        at.shear(shear, 0);
        at.scale(scale, scale);
        at.translate(-size / 2.0, -size / 2.0);
        g2.transform(at);
        g2.drawImage(img, 0, 0, size, size, null);
        g2.setColor(new Color(0, 0, 0, 90));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRect(0, 0, size, size);
        g2.setTransform(save);
    }

    private void drawCaption(Graphics2D g2, String text, int cx, int y) {
        g2.setFont(getFont().deriveFont(Font.BOLD, 14f));
        g2.setColor(Color.WHITE);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, cx - fm.stringWidth(text) / 2, y);
    }

    private void drawMessage(Graphics2D g2, String text) {
        g2.setFont(getFont().deriveFont(Font.ITALIC, 14f));
        g2.setColor(new Color(0x99A0AC));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, (getWidth() - fm.stringWidth(text)) / 2, getHeight() / 2);
    }

    /**
     * The sleeve image for an album: its cached cover file when readable, else a
     * generated placeholder. Decoded lazily and cached by path.
     */
    BufferedImage imageFor(AlbumIndex.Album album, int size) {
        String key = album.coverPath.isBlank()
                ? ("gen:" + album.label() + ":" + size)
                : (album.coverPath + ":" + size);
        BufferedImage cached = coverCache.get(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage img = null;
        if (!album.coverPath.isBlank()) {
            try {
                File f = new File(album.coverPath);
                if (f.isFile()) {
                    BufferedImage src = ImageIO.read(f);
                    if (src != null) {
                        img = scale(src, size);
                    }
                }
            } catch (RuntimeException | java.io.IOException e) {
                img = null;
            }
        }
        if (img == null) {
            img = placeholder(album, size);
        }
        coverCache.put(key, img);
        return img;
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /** A generated gradient sleeve carrying the album title. */
    private static BufferedImage placeholder(AlbumIndex.Album album, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int hash = album.label().hashCode();
        Color top = Color.getHSBColor(Math.abs(hash % 360) / 360f, 0.55f, 0.55f);
        Color bottom = Color.getHSBColor(Math.abs((hash / 7) % 360) / 360f, 0.6f, 0.28f);
        g.setPaint(new GradientPaint(0, 0, top, size, size, bottom));
        g.fillRect(0, 0, size, size);
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(Font.BOLD, Math.max(11f, size / 12f)));
        FontMetrics fm = g.getFontMetrics();
        String title = album.title;
        if (fm.stringWidth(title) > size - 12) {
            title = clip(title, fm, size - 12);
        }
        g.drawString(title, (size - fm.stringWidth(title)) / 2, size / 2);
        if (!album.artist.isBlank()) {
            g.setFont(g.getFont().deriveFont(Font.PLAIN, Math.max(9f, size / 16f)));
            fm = g.getFontMetrics();
            String artist = album.artist;
            if (fm.stringWidth(artist) > size - 12) {
                artist = clip(artist, fm, size - 12);
            }
            g.setColor(new Color(255, 255, 255, 200));
            g.drawString(artist, (size - fm.stringWidth(artist)) / 2, size / 2 + fm.getHeight());
        }
        g.dispose();
        return out;
    }

    private static String clip(String s, FontMetrics fm, int maxWidth) {
        String out = s;
        while (out.length() > 1 && fm.stringWidth(out + "\u2026") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "\u2026";
    }

    /**
     * Hit-tests a click to an album by re-deriving each sleeve's drawn bounds.
     * The centre sleeve is checked first, then the nearer side sleeves.
     */
    AlbumIndex.Album albumAt(int x, int y) {
        if (albums.isEmpty()) {
            return null;
        }
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        if (within(x, y, cx, cy, COVER_SIZE)) {
            return albums.get(center);
        }
        for (int d = 1; d <= SIDE_COUNT; d++) {
            float scale = 1.0f - 0.14f * d;
            int size = Math.round(COVER_SIZE * scale);
            int dx = COVER_SIZE / 2 + 26 * d + size / 2;
            int leftIdx = ((center - d) % albums.size() + albums.size()) % albums.size();
            int rightIdx = (center + d) % albums.size();
            if (within(x, y, cx - dx, cy, size)) {
                return albums.get(leftIdx);
            }
            if (within(x, y, cx + dx, cy, size)) {
                return albums.get(rightIdx);
            }
        }
        return null;
    }

    private static boolean within(int x, int y, int cx, int cy, int size) {
        return Math.abs(x - cx) <= size / 2 && Math.abs(y - cy) <= size / 2;
    }
}
