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
package org.jdesktop.lg3d.apps.paint;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * File I/O for the Paint app: Open / Save / Save-As through a {@link JFileChooser}
 * backed by {@link ImageIO} for PNG, JPEG, GIF and BMP.
 *
 * <p>JPEG has no alpha channel, so a save to {@code .jpg}/{@code .jpeg} flattens
 * the image onto white first. Loading reads whatever {@code ImageIO} supports and
 * the caller flattens the result into a single background layer (see
 * {@link PaintDocument#fromImage}). Save ensures the chosen file carries an
 * extension matching the selected format so {@code ImageIO} can find a writer.</p>
 */
public final class PaintIO {

    /** The supported formats: display label, canonical ImageIO format, extension. */
    private static final String[][] FORMATS = {
        { "PNG image (*.png)", "png", "png" },
        { "JPEG image (*.jpg)", "jpeg", "jpg" },
        { "GIF image (*.gif)", "gif", "gif" },
        { "BMP image (*.bmp)", "bmp", "bmp" },
    };

    private PaintIO() {
    }

    /**
     * Shows an Open dialog and reads the chosen image. Returns null if the user
     * cancelled or the file could not be decoded (an {@link IOException} is
     * reported by returning null; the caller shows the message from
     * {@link #lastError()}).
     */
    public static BufferedImage chooseAndRead(Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open image");
        for (String[] f : FORMATS) {
            chooser.addChoosableFileFilter(
                    new FileNameExtensionFilter(f[0], f[2]));
        }
        chooser.setAcceptAllFileFilterUsed(true);
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return null;
        }
        try {
            clearError();
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                setError("Unrecognised or unsupported image format:\n" + file.getName());
            }
            return img;
        } catch (IOException e) {
            setError("Could not read " + file.getName() + ":\n" + e.getMessage());
            return null;
        }
    }

    /**
     * Shows a Save dialog and writes {@code image} to the chosen file, picking the
     * format from the selected filter / extension and flattening onto white for
     * JPEG. Returns the written file, or null if the user cancelled or the write
     * failed.
     */
    public static File chooseAndWrite(Component parent, BufferedImage image,
            String suggestedName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save image");
        FileNameExtensionFilter[] filters =
                new FileNameExtensionFilter[FORMATS.length];
        for (int i = 0; i < FORMATS.length; i++) {
            filters[i] = new FileNameExtensionFilter(FORMATS[i][0], FORMATS[i][2]);
            chooser.addChoosableFileFilter(filters[i]);
        }
        chooser.setAcceptAllFileFilterUsed(false);
        if (suggestedName != null && !suggestedName.isEmpty()) {
            chooser.setSelectedFile(new File(suggestedName));
        }
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File file = chooser.getSelectedFile();
        if (file == null) {
            return null;
        }
        String format = formatForFilter(chooser.getFileFilter(), filters);
        file = ensureExtension(file, format);
        try {
            clearError();
            BufferedImage toWrite = image;
            if ("jpeg".equals(format)) {
                toWrite = flattenOntoWhite(image);
            }
            boolean ok = ImageIO.write(toWrite, format, file);
            if (!ok) {
                setError("No writer available for format: " + format);
                return null;
            }
            return file;
        } catch (IOException e) {
            setError("Could not write " + file.getName() + ":\n" + e.getMessage());
            return null;
        }
    }

    /** Composites an ARGB image onto an opaque white RGB image (for JPEG). */
    public static BufferedImage flattenOntoWhite(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static String formatForFilter(
            javax.swing.filechooser.FileFilter selected,
            FileNameExtensionFilter[] filters) {
        for (int i = 0; i < filters.length; i++) {
            if (filters[i] == selected) {
                return FORMATS[i][1];
            }
        }
        // Fall back to the extension of the typed name, else PNG.
        return "png";
    }

    private static File ensureExtension(File file, String format) {
        String ext = extensionForFormat(format);
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith("." + ext)
                || (format.equals("jpeg") && name.endsWith(".jpeg"))) {
            return file;
        }
        return new File(file.getParentFile(), file.getName() + "." + ext);
    }

    private static String extensionForFormat(String format) {
        for (String[] f : FORMATS) {
            if (f[1].equals(format)) {
                return f[2];
            }
        }
        return "png";
    }

    /** The canonical ImageIO format name for a file's extension (default png). */
    public static String formatForFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = (dot < 0) ? "" : name.substring(dot + 1);
        if (ext.equals("jpg") || ext.equals("jpeg")) {
            return "jpeg";
        }
        for (String[] f : FORMATS) {
            if (f[2].equals(ext)) {
                return f[1];
            }
        }
        return "png";
    }

    // ------------------------------------------------------------------
    // Last-error reporting (kept simple; the frame shows it in a dialog)
    // ------------------------------------------------------------------

    private static String lastError;

    private static void setError(String msg) {
        lastError = msg;
    }

    private static void clearError() {
        lastError = null;
    }

    /** The message from the most recent failed read/write, or null. */
    public static String lastError() {
        return lastError;
    }
}
