package org.jdesktop.lg3d.apps.remoteviewer.utilities;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Iterator;

/**
 * ImageUtility.java
 * @author benbac
 */

public class ImageUtility {

    static {ImageIO.setUseCache(false);}

    public static BufferedImage read(InputStream in) throws IOException {
        BufferedImage image = ImageIO.read(in);
        if (image == null) {
            throw new IOException("Read fails");
        }
        return image;
    }
 
    public static BufferedImage read(byte[] bytes) {
        try {
            return read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    } 

    public static void write(BufferedImage image, 
            float quality, OutputStream out) throws IOException {
        Iterator writers = ImageIO.getImageWritersBySuffix("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No writers found");
        }
        ImageWriter writer = (ImageWriter) writers.next();
        ImageOutputStream ios = ImageIO.createImageOutputStream(out);
        writer.setOutput(ios);
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (quality >= 0) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        writer.write(null, new IIOImage(image, null, null), param);
        ios.close();
        writer.dispose();
    }

    public static byte[] toByteArray(BufferedImage image, float quality) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (quality != -1)
                write(image, quality, out);       // write with compression
            else
                ImageIO.write(image, "png", out); // write without compression
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static BufferedImage compress(BufferedImage image, float quality) {
        return read(toByteArray(image, quality));
    }
}
