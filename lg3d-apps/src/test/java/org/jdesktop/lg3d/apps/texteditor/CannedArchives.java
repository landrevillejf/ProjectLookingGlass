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
package org.jdesktop.lg3d.apps.texteditor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Canned archive builders for the provisioning tests. A fake {@link Downloader}
 * has to write real bytes that {@link Archives} can actually unpack, and no test
 * may touch the network, so these emit a minimal-but-valid {@code .tar.gz} JDK
 * and {@code .zip} build-tool distribution entirely in memory. The tar writer is
 * deliberately a plain ustar emitter, independent of the reader under test, so a
 * round-trip failure points at one side or the other.
 */
final class CannedArchives {

    private CannedArchives() {
    }

    /**
     * Writes a gzip'd tar holding one JDK home: {@code <home>/bin/java} (0755,
     * so it extracts executable) and {@code <home>/release} carrying
     * {@code JAVA_VERSION}.
     */
    static void writeJdkTarGz(Path dest, String home, String version) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        tarDir(body, home + "/");
        tarDir(body, home + "/bin/");
        tarFile(body, home + "/bin/java",
                "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8), 0755);
        tarFile(body, home + "/release",
                ("JAVA_VERSION=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8), 0644);
        tarEnd(body);
        try (OutputStream fileOut = Files.newOutputStream(dest);
             GZIPOutputStream gz = new GZIPOutputStream(fileOut)) {
            gz.write(body.toByteArray());
        }
    }

    /**
     * Writes a zip holding one JDK home: {@code <home>/bin/java.exe} and
     * {@code <home>/release}. This is the Windows counterpart of
     * {@link #writeJdkTarGz} - {@link JdkRegistry#download} names its archive
     * {@code .zip} on Windows, so the fake downloader must match that shape.
     */
    static void writeJdkZip(Path dest, String home, String version) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dest))) {
            out.putNextEntry(new ZipEntry(home + "/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(home + "/bin/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(home + "/bin/java.exe"));
            out.write("MZ".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(home + "/release"));
            out.write(("JAVA_VERSION=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /**
     * Writes a zip holding one tool distribution: {@code <dir>/bin/<launcher>}
     * and a {@code <dir>/LICENSE}, shaped like a real Gradle/Maven {@code -bin.zip}.
     */
    static void writeToolZip(Path dest, String dir, String launcher) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dest))) {
            out.putNextEntry(new ZipEntry(dir + "/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(dir + "/bin/"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(dir + "/bin/" + launcher));
            out.write(("#!/bin/sh\necho " + launcher + "\n").getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(dir + "/LICENSE"));
            out.write("license".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    // -- minimal ustar emitter ---------------------------------------------

    private static void tarDir(ByteArrayOutputStream out, String name) {
        tarHeader(out, name, 0, 0755, '5');
    }

    private static void tarFile(ByteArrayOutputStream out, String name, byte[] data, int mode) {
        tarHeader(out, name, data.length, mode, '0');
        out.write(data, 0, data.length);
        int pad = (512 - (data.length % 512)) % 512;
        for (int i = 0; i < pad; i++) {
            out.write(0);
        }
    }

    private static void tarEnd(ByteArrayOutputStream out) {
        out.write(new byte[1024], 0, 1024);
    }

    private static void tarHeader(ByteArrayOutputStream out, String name, long size,
                                  int mode, char type) {
        byte[] header = new byte[512];
        putString(header, 0, name, 100);
        putOctal(header, 100, mode, 8);
        putOctal(header, 108, 0, 8);
        putOctal(header, 116, 0, 8);
        putOctal(header, 124, size, 12);
        putOctal(header, 136, 0, 12);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = (byte) type;
        putString(header, 257, "ustar", 6);
        putString(header, 263, "00", 2);
        int sum = 0;
        for (byte b : header) {
            sum += (b & 0xFF);
        }
        putOctal(header, 148, sum, 7);
        header[155] = ' ';
        out.write(header, 0, 512);
    }

    private static void putString(byte[] buf, int off, String s, int len) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, buf, off, Math.min(bytes.length, len));
    }

    private static void putOctal(byte[] buf, int off, long value, int len) {
        String oct = String.format("%0" + (len - 1) + "o", value);
        byte[] bytes = oct.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buf, off, Math.min(bytes.length, len - 1));
        buf[off + len - 1] = 0;
    }
}
