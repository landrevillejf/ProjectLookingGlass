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
package org.jdesktop.lg3d.apps.mediawriter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless engine behind {@link MediaWriterPanel}: enumerates real block and
 * optical devices and drives real burn / image / format operations by shelling
 * out to the standard Linux media tools ({@code dd}, {@code wodim}/{@code
 * cdrecord}, {@code growisofs}, {@code xorriso}, {@code mkfs.*},
 * {@code isohybrid}, {@code wipefs}, {@code parted}).
 *
 * <p>This class performs <b>destructive, irreversible</b> writes to physical
 * media. Every write path is guarded: internal fixed disks are never offered
 * as a target, read-only devices are rejected, mounted filesystems are
 * unmounted first, and the caller is expected to obtain an explicit
 * confirmation before invoking a write method.</p>
 *
 * <p>Deliberately free of Swing/AWT imports so it can be exercised headless.
 * Long-running operations report through a {@link ProgressHandler} and honour
 * {@link #cancel()}.</p>
 */
public final class MediaWriterEngine {

    /** Physical class of a detected device. */
    public enum DeviceKind { OPTICAL, USB, DISK }

    /** The operations the panel exposes. */
    public enum MediaMode { BURN_ISO, WRITE_USB, CLONE_DISC, FORMAT_USB, DATA_DISC }

    /** Thrown for any invalid request or failed operation. */
    public static class MediaWriterException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MediaWriterException(String message) {
            super(message);
        }

        public MediaWriterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Callbacks for a running operation. All calls happen on the worker thread. */
    public interface ProgressHandler {
        /** A line for the log console (command echo, tool output, status). */
        void onLog(String line);

        /** Progress in [0,1], or a negative value for indeterminate. */
        void onProgress(double fraction, String stage);

        /** Terminal status; {@code success} reflects the overall result. */
        void onFinished(boolean success, String message);
    }

    /** A detected device (whole disk, optical drive or removable key). */
    public static final class DeviceInfo {
        public final String name;          // e.g. sdb, sr0
        public final String path;          // e.g. /dev/sdb
        public final DeviceKind kind;
        public final long sizeBytes;
        public final String model;
        public final String vendor;
        public final String transport;     // usb, sata, ...
        public final boolean removable;
        public final boolean readOnly;
        public final List<String> mountPoints = new ArrayList<>();
        public final List<String> childPaths = new ArrayList<>(); // partitions
        public String media = "";          // optical media description, if any

        DeviceInfo(String name, String path, DeviceKind kind, long sizeBytes,
                   String model, String vendor, String transport,
                   boolean removable, boolean readOnly) {
            this.name = name;
            this.path = path;
            this.kind = kind;
            this.sizeBytes = sizeBytes;
            this.model = model;
            this.vendor = vendor;
            this.transport = transport;
            this.removable = removable;
            this.readOnly = readOnly;
        }

        /** True when this device may be offered as a write target. */
        public boolean isWritableTarget() {
            return kind != DeviceKind.DISK && !readOnly;
        }

        /** A one-line human description for the device picker. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(path).append("  ");
            String id = (vendor == null ? "" : vendor.trim())
                    + " " + (model == null ? "" : model.trim());
            sb.append(id.trim().isEmpty() ? kindLabel() : id.trim());
            sb.append("  (").append(kindLabel());
            if (kind == DeviceKind.OPTICAL) {
                sb.append(media.isEmpty() ? ", no media" : ", " + media);
            } else {
                sb.append(", ").append(humanSize(sizeBytes));
            }
            sb.append(')');
            return sb.toString();
        }

        private String kindLabel() {
            switch (kind) {
                case OPTICAL: return "optical";
                case USB:     return "removable";
                default:      return "disk";
            }
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** Static facts about a selected image file. */
    public static final class ImageInfo {
        public final File file;
        public final long sizeBytes;
        public final boolean iso9660;
        public final boolean hybrid;

        ImageInfo(File file, long sizeBytes, boolean iso9660, boolean hybrid) {
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.iso9660 = iso9660;
            this.hybrid = hybrid;
        }

        public String describe() {
            return file.getName() + "  (" + humanSize(sizeBytes) + ")"
                    + (iso9660 ? "  ISO-9660" : "  raw image")
                    + (hybrid ? "  isohybrid" : "");
        }
    }

    // ------------------------------------------------------------------ state

    private volatile Process activeProcess;
    private volatile boolean cancelled;

    private static final Pattern KV = Pattern.compile("([A-Z]+)=\"([^\"]*)\"");
    private static final Pattern DD_BYTES =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s+bytes?\\b.*copied");
    private static final Pattern PERCENT =
            Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*%");

    /** Requests cancellation of the running operation, if any. */
    public void cancel() {
        cancelled = true;
        Process p = activeProcess;
        if (p != null) {
            p.destroy();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Resets the cancel flag; call before starting each new operation. */
    public void resetCancel() {
        cancelled = false;
    }

    // ================================================================ detect

    /**
     * Enumerates block devices via {@code lsblk} plus optical drives, and
     * returns them classified as OPTICAL / USB / DISK. Partition mount points
     * are attached to their parent device so writes can unmount them first.
     */
    public List<DeviceInfo> detectDevices() {
        Map<String, DeviceInfo> byName = new LinkedHashMap<>();
        List<DeviceInfo> ordered = new ArrayList<>();

        String lsblk = which("lsblk");
        if (lsblk != null) {
            String cols = "NAME,PATH,PKNAME,TYPE,SIZE,RM,RO,MODEL,VENDOR,TRAN,MOUNTPOINT,LABEL,FSTYPE";
            List<String> lines = runCollect(lsblk, "-b", "-P", "-o", cols);
            for (String line : lines) {
                Map<String, String> f = parseKv(line);
                String type = f.getOrDefault("TYPE", "");
                String name = f.getOrDefault("NAME", "");
                if (name.isEmpty()) {
                    continue;
                }
                if (type.equals("disk") || type.equals("rom")) {
                    DeviceInfo d = toDevice(f, type);
                    byName.put(name, d);
                    ordered.add(d);
                }
            }
            // second pass: attach partitions / mount points to their parent
            for (String line : lines) {
                Map<String, String> f = parseKv(line);
                String type = f.getOrDefault("TYPE", "");
                if (type.equals("disk") || type.equals("rom")) {
                    continue;
                }
                String pk = f.getOrDefault("PKNAME", "");
                DeviceInfo parent = byName.get(pk);
                if (parent == null) {
                    continue;
                }
                String path = f.getOrDefault("PATH", "");
                if (!path.isEmpty()) {
                    parent.childPaths.add(path);
                }
                String mp = f.getOrDefault("MOUNTPOINT", "");
                if (!mp.isEmpty()) {
                    parent.mountPoints.add(mp);
                }
            }
        }

        supplementOptical(byName, ordered);
        probeOpticalMedia(ordered);
        return ordered;
    }

    private DeviceInfo toDevice(Map<String, String> f, String type) {
        String path = f.getOrDefault("PATH", "/dev/" + f.get("NAME"));
        boolean rm = "1".equals(f.get("RM"));
        boolean ro = "1".equals(f.get("RO"));
        String tran = f.getOrDefault("TRAN", "").toLowerCase(Locale.ROOT);
        long size = parseLong(f.get("SIZE"));
        DeviceKind kind;
        if (type.equals("rom")) {
            kind = DeviceKind.OPTICAL;
        } else if (rm && (tran.equals("usb") || tran.isEmpty())) {
            kind = DeviceKind.USB;
        } else if (rm) {
            kind = DeviceKind.USB; // other removable (sd cards, etc.)
        } else {
            kind = DeviceKind.DISK;
        }
        return new DeviceInfo(f.get("NAME"), path, kind, size,
                f.getOrDefault("MODEL", ""), f.getOrDefault("VENDOR", ""),
                tran, rm, ro);
    }

    /** Adds optical drives from /proc/sys/dev/cdrom/info that lsblk may miss. */
    private void supplementOptical(Map<String, DeviceInfo> byName, List<DeviceInfo> ordered) {
        File info = new File("/proc/sys/dev/cdrom/info");
        if (!info.canRead()) {
            return;
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : readLines(info)) {
            int c = line.indexOf(':');
            if (c > 0) {
                kv.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
            }
        }
        String names = kv.getOrDefault("drive name", "");
        for (String n : names.split("\\s+")) {
            if (n.isEmpty() || byName.containsKey(n)) {
                continue;
            }
            DeviceInfo d = new DeviceInfo(n, "/dev/" + n, DeviceKind.OPTICAL,
                    0, "", "", "ata", true, false);
            byName.put(n, d);
            ordered.add(d);
        }
    }

    /** Best-effort media description for optical drives (blank / DVD / CD). */
    private void probeOpticalMedia(List<DeviceInfo> devices) {
        for (DeviceInfo d : devices) {
            if (d.kind != DeviceKind.OPTICAL) {
                continue;
            }
            String tool = which("dvd+rw-mediainfo");
            if (tool != null) {
                List<String> out = runCollect(tool, d.path);
                for (String line : out) {
                    if (line.contains("Disc status")) {
                        d.media = line.substring(line.indexOf(':') + 1).trim();
                    } else if (line.contains("Mounted media") || line.contains("Disc ID")) {
                        // keep whatever Disc status gave us
                    }
                }
                if (!d.media.isEmpty()) {
                    continue;
                }
            }
            // fallback: size>0 from lsblk implies media present
            d.media = d.sizeBytes > 0 ? "media present" : "";
        }
    }

    /** Reads facts about an image file the user wants to write. */
    public ImageInfo probeImage(File f) {
        if (f == null || !f.isFile()) {
            throw new MediaWriterException("Not a readable file: " + f);
        }
        boolean iso = false;
        boolean hybrid = false;
        try (FileInputStream in = new FileInputStream(f)) {
            // ISO-9660 primary volume descriptor magic "CD001" at 0x8001.
            byte[] head = new byte[0x8001 + 5];
            int read = readFully(in, head, head.length);
            if (read >= head.length) {
                iso = head[0x8001] == 'C' && head[0x8002] == 'D'
                        && head[0x8003] == '0' && head[0x8004] == '0'
                        && head[0x8005] == '1';
                // A protective MBR (0x55AA at 0x1FE) marks an isohybrid image.
                hybrid = (head[0x1FE] & 0xFF) == 0x55 && (head[0x1FF] & 0xFF) == 0xAA;
            }
        } catch (IOException e) {
            throw new MediaWriterException("Cannot read image: " + f, e);
        }
        return new ImageInfo(f, f.length(), iso, hybrid);
    }

    // ============================================================ operations

    /**
     * Burns an ISO to an optical drive. Chooses {@code growisofs} for DVD media,
     * otherwise {@code wodim}/{@code cdrecord} (or {@code xorriso -as
     * cdrecord}). Optionally verifies by reading the disc back.
     */
    public void burnIsoToOptical(DeviceInfo drive, File iso, Integer speed,
                                 boolean verify, ProgressHandler h) {
        requireOptical(drive);
        ImageInfo info = probeImage(iso);
        checkCancelled(h);

        boolean dvd = isDvdMedia(drive);
        List<String> cmd = new ArrayList<>();
        String growisofs = which("growisofs");
        String wodim = which("wodim");
        if (wodim == null) {
            wodim = which("cdrecord");
        }
        String xorriso = which("xorriso");

        if (dvd && growisofs != null) {
            cmd.add(growisofs);
            cmd.add("-dvd-compat");
            cmd.add("-Z");
            cmd.add(drive.path + "=" + iso.getAbsolutePath());
            if (speed != null && speed > 0) {
                cmd.add("-speed=" + speed);
            }
        } else if (wodim != null) {
            cmd.add(wodim);
            cmd.add("-v");
            cmd.add("-data");
            cmd.add("dev=" + drive.path);
            if (speed != null && speed > 0) {
                cmd.add("speed=" + speed);
            }
            cmd.add(iso.getAbsolutePath());
        } else if (xorriso != null) {
            cmd.add(xorriso);
            cmd.add("-as");
            cmd.add("cdrecord");
            cmd.add("-v");
            cmd.add("dev=" + drive.path);
            if (speed != null && speed > 0) {
                cmd.add("speed=" + speed);
            }
            cmd.add(iso.getAbsolutePath());
        } else {
            throw new MediaWriterException(
                    "No optical burning tool found (install wodim, growisofs or xorriso)");
        }

        log(h, "Burning " + iso.getName() + " (" + humanSize(info.sizeBytes) + ") to "
                + drive.path + (dvd ? " [DVD]" : " [CD]"));
        int code = run(cmd, h, info.sizeBytes, "Burning");
        if (cancelled) {
            finish(h, false, "Cancelled");
            return;
        }
        if (code != 0) {
            finish(h, false, "Burn failed (exit " + code + ")");
            return;
        }
        if (verify) {
            verifyOptical(drive, iso, h);
        } else {
            finish(h, true, "Burn complete");
        }
    }

    /**
     * Writes a raw image or ISO to a USB / removable device with {@code dd}.
     * Unmounts first; optionally makes the image isohybrid-bootable (on a
     * temporary copy) and verifies the write with a SHA-256 read-back.
     */
    public void writeImageToUsb(DeviceInfo usb, File image, boolean bootable,
                                boolean verify, ProgressHandler h) {
        requireUsb(usb);
        ImageInfo info = probeImage(image);
        checkCancelled(h);

        unmount(usb, h);

        String source = image.getAbsolutePath();
        File tempIso = null;
        if (bootable) {
            String isohybrid = which("isohybrid");
            if (isohybrid == null) {
                throw new MediaWriterException("isohybrid not found (install syslinux)");
            }
            tempIso = copyToTemp(image, h);
            source = tempIso.getAbsolutePath();
            List<String> iso = new ArrayList<>();
            iso.add(isohybrid);
            iso.add(source);
            log(h, "Making image bootable (isohybrid)");
            int c = run(iso, h, -1, "isohybrid");
            if (c != 0 && !cancelled) {
                log(h, "isohybrid returned " + c + " - continuing with plain write");
            }
        }

        List<String> dd = new ArrayList<>();
        dd.add(whichOrFail("dd"));
        dd.add("if=" + source);
        dd.add("of=" + usb.path);
        dd.add("bs=4M");
        dd.add("status=progress");
        dd.add("oflag=sync");
        dd.add("conv=fsync");

        log(h, "Writing " + image.getName() + " (" + humanSize(info.sizeBytes)
                + ") to " + usb.path);
        int code = run(dd, h, info.sizeBytes, "Writing");
        try {
            if (cancelled) {
                finish(h, false, "Cancelled");
                return;
            }
            if (code != 0) {
                finish(h, false, "Write failed (exit " + code + ")");
                return;
            }
            rereadPartitionTable(usb, h);
            if (verify) {
                verifyUsbWrite(usb.path, new File(source), info.sizeBytes, h);
            } else {
                finish(h, true, "Write complete");
            }
        } finally {
            if (tempIso != null) {
                //noinspection ResultOfMethodCallIgnored
                tempIso.delete();
            }
        }
    }

    /**
     * Formats a USB / removable device. Optionally rebuilds a single-partition
     * MBR table first, then creates the requested filesystem with a label.
     */
    public void formatUsb(DeviceInfo usb, String fs, String label,
                          boolean newPartitionTable, ProgressHandler h) {
        requireUsb(usb);
        checkCancelled(h);
        unmount(usb, h);

        String target = usb.path;
        if (newPartitionTable) {
            List<String> wipe = new ArrayList<>();
            wipe.add(whichOrFail("wipefs"));
            wipe.add("-a");
            wipe.add(usb.path);
            log(h, "Wiping existing signatures");
            run(elevate(wipe), h, -1, "wipefs");

            String parted = which("parted");
            if (parted != null) {
                List<String> p1 = new ArrayList<>();
                p1.add(parted);
                p1.add("-s");
                p1.add(usb.path);
                p1.add("mklabel");
                p1.add("msdos");
                run(elevate(p1), h, -1, "parted");
                List<String> p2 = new ArrayList<>();
                p2.add(parted);
                p2.add("-s");
                p2.add(usb.path);
                p2.add("mkpart");
                p2.add("primary");
                p2.add(fs.equals("vfat") ? "fat32" : fs);
                p2.add("1MiB");
                p2.add("100%");
                run(elevate(p2), h, -1, "parted");
                rereadPartitionTable(usb, h);
                target = usb.path + "1";
            } else {
                log(h, "parted not found - formatting whole device " + usb.path);
            }
        }

        String mkfs = which("mkfs." + fs);
        if (mkfs == null) {
            throw new MediaWriterException("mkfs." + fs + " not found");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(mkfs);
        if (label != null && !label.trim().isEmpty()) {
            if (fs.equals("vfat") || fs.equals("exfat") || fs.equals("ntfs")) {
                cmd.add("-n");
            } else {
                cmd.add("-L");
            }
            cmd.add(label.trim());
        }
        if (fs.equals("ext4") || fs.equals("ext3") || fs.equals("ext2")) {
            cmd.add("-F");
        }
        cmd.add(target);
        log(h, "Creating " + fs + " filesystem on " + target);
        int code = run(elevate(cmd), h, -1, "Formatting");
        if (cancelled) {
            finish(h, false, "Cancelled");
        } else if (code != 0) {
            finish(h, false, "Format failed (exit " + code + ")");
        } else {
            finish(h, true, "Format complete");
        }
    }

    /**
     * Clones one device to another by reading the source into a temporary image
     * and writing it to the destination (optical burn or USB image write).
     */
    public void cloneDisc(DeviceInfo src, DeviceInfo dst, boolean verify, ProgressHandler h) {
        if (src == null || dst == null) {
            throw new MediaWriterException("Select both a source and a destination");
        }
        if (!dst.isWritableTarget()) {
            throw new MediaWriterException("Destination " + dst.path + " is not a writable target");
        }
        checkCancelled(h);
        if (!src.mountPoints.isEmpty()) {
            unmount(src, h);
        }

        File tmp;
        try {
            tmp = File.createTempFile("lg3d-clone-", ".img");
        } catch (IOException e) {
            throw new MediaWriterException("Cannot create temporary image", e);
        }
        try {
            List<String> dd = new ArrayList<>();
            dd.add(whichOrFail("dd"));
            dd.add("if=" + src.path);
            dd.add("of=" + tmp.getAbsolutePath());
            dd.add("bs=4M");
            dd.add("status=progress");
            dd.add("conv=noerror,sync");
            long srcSize = src.sizeBytes > 0 ? src.sizeBytes : -1;
            log(h, "Reading " + src.path + " into a temporary image");
            int code = run(elevate(dd), h, srcSize, "Reading");
            if (cancelled) {
                finish(h, false, "Cancelled");
                return;
            }
            if (code != 0) {
                finish(h, false, "Read failed (exit " + code + ")");
                return;
            }
            if (dst.kind == DeviceKind.OPTICAL) {
                burnIsoToOptical(dst, tmp, null, verify, h);
            } else {
                writeImageToUsb(dst, tmp, false, verify, h);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Builds an ISO-9660 image from a folder (via {@code xorriso} or
     * {@code genisoimage}) and, if a drive is given, burns it. The generated
     * image is kept at {@code outIso}.
     */
    public void createDataDisc(File folder, File outIso, String volumeLabel,
                               DeviceInfo drive, Integer speed, boolean verify,
                               ProgressHandler h) {
        if (folder == null || !folder.isDirectory()) {
            throw new MediaWriterException("Select a source folder");
        }
        checkCancelled(h);
        String xorriso = which("xorriso");
        String geniso = which("genisoimage");
        if (geniso == null) {
            geniso = which("mkisofs");
        }
        List<String> cmd = new ArrayList<>();
        if (xorriso != null) {
            cmd.add(xorriso);
            cmd.add("-as");
            cmd.add("mkisofs");
        } else if (geniso != null) {
            cmd.add(geniso);
        } else {
            throw new MediaWriterException(
                    "No ISO creation tool found (install xorriso or genisoimage)");
        }
        cmd.add("-o");
        cmd.add(outIso.getAbsolutePath());
        cmd.add("-r");
        cmd.add("-J");
        if (volumeLabel != null && !volumeLabel.trim().isEmpty()) {
            cmd.add("-V");
            cmd.add(volumeLabel.trim());
        }
        cmd.add(folder.getAbsolutePath());

        log(h, "Creating ISO image " + outIso.getName() + " from " + folder.getPath());
        int code = run(cmd, h, -1, "Creating ISO");
        if (cancelled) {
            finish(h, false, "Cancelled");
            return;
        }
        if (code != 0) {
            finish(h, false, "ISO creation failed (exit " + code + ")");
            return;
        }
        if (drive != null && drive.kind == DeviceKind.OPTICAL) {
            burnIsoToOptical(drive, outIso, speed, verify, h);
        } else {
            finish(h, true, "ISO created: " + outIso.getPath());
        }
    }

    // ================================================================ verify

    private void verifyUsbWrite(String devPath, File source, long bytes, ProgressHandler h) {
        log(h, "Verifying: computing SHA-256 of source and read-back");
        h.onProgress(-1, "Verifying");
        try {
            String srcHash = sha256(source, bytes);
            String devHash = sha256(new File(devPath), bytes);
            if (srcHash.equals(devHash)) {
                finish(h, true, "Write verified (SHA-256 " + shortHash(srcHash) + ")");
            } else {
                finish(h, false, "Verify FAILED: source " + shortHash(srcHash)
                        + " != device " + shortHash(devHash));
            }
        } catch (Exception e) {
            finish(h, false, "Verify error: " + e.getMessage());
        }
    }

    private void verifyOptical(DeviceInfo drive, File iso, ProgressHandler h) {
        log(h, "Verifying disc by comparing SHA-256 of the image and the disc");
        h.onProgress(-1, "Verifying");
        try {
            long bytes = iso.length();
            String srcHash = sha256(iso, bytes);
            String devHash = sha256(new File(drive.path), bytes);
            if (srcHash.equals(devHash)) {
                finish(h, true, "Burn verified (SHA-256 " + shortHash(srcHash) + ")");
            } else {
                finish(h, false, "Verify mismatch (image " + shortHash(srcHash)
                        + ", disc " + shortHash(devHash) + ")");
            }
        } catch (Exception e) {
            // Optical read-back can legitimately differ (padding/track layout).
            finish(h, true, "Burn complete; verify inconclusive: " + e.getMessage());
        }
    }

    // ================================================================ helpers

    private void requireOptical(DeviceInfo d) {
        if (d == null || d.kind != DeviceKind.OPTICAL) {
            throw new MediaWriterException("Select an optical drive");
        }
        if (d.readOnly) {
            throw new MediaWriterException("Drive " + d.path + " is read-only");
        }
    }

    private void requireUsb(DeviceInfo d) {
        if (d == null) {
            throw new MediaWriterException("Select a target device");
        }
        if (d.kind == DeviceKind.DISK) {
            throw new MediaWriterException(
                    "Refusing to write to internal disk " + d.path);
        }
        if (!d.isWritableTarget()) {
            throw new MediaWriterException("Target " + d.path + " is not writable");
        }
    }

    private boolean isDvdMedia(DeviceInfo drive) {
        String tool = which("dvd+rw-mediainfo");
        if (tool != null) {
            for (String line : runCollect(tool, drive.path)) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("dvd") || l.contains("mounted media: 1")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Unmounts every mounted filesystem on the device and its partitions. */
    public void unmount(DeviceInfo d, ProgressHandler h) {
        if (d.mountPoints.isEmpty()) {
            return;
        }
        String udisksctl = which("udisksctl");
        for (String mp : new ArrayList<>(d.mountPoints)) {
            List<String> cmd = new ArrayList<>();
            if (udisksctl != null) {
                cmd.add(udisksctl);
                cmd.add("unmount");
                cmd.add("-b");
                cmd.add(d.path);
            } else {
                cmd.add(whichOrFail("umount"));
                cmd.add(mp);
            }
            log(h, "Unmounting " + mp);
            runCollectList(cmd);
        }
        d.mountPoints.clear();
    }

    private void rereadPartitionTable(DeviceInfo d, ProgressHandler h) {
        String partprobe = which("partprobe");
        if (partprobe != null) {
            runCollectList(elevate(List.of(partprobe, d.path)));
        } else {
            String blockdev = which("blockdev");
            if (blockdev != null) {
                runCollectList(elevate(List.of(blockdev, "--rereadpt", d.path)));
            }
        }
    }

    private File copyToTemp(File src, ProgressHandler h) {
        File tmp;
        try {
            tmp = File.createTempFile("lg3d-img-", ".iso");
        } catch (IOException e) {
            throw new MediaWriterException("Cannot create temporary file", e);
        }
        List<String> dd = new ArrayList<>();
        dd.add(whichOrFail("dd"));
        dd.add("if=" + src.getAbsolutePath());
        dd.add("of=" + tmp.getAbsolutePath());
        dd.add("bs=4M");
        dd.add("status=progress");
        log(h, "Copying image to a temporary file for isohybrid");
        run(dd, h, src.length(), "Preparing");
        return tmp;
    }

    // ------------------------------------------------------------ privileges

    private boolean isRoot() {
        String euid = System.getenv("EUID");
        if ("0".equals(euid)) {
            return true;
        }
        List<String> out = runCollect(which("id") == null ? "id" : which("id"), "-u");
        return !out.isEmpty() && out.get(0).trim().equals("0");
    }

    /** Wraps a destructive command with pkexec when not running as root. */
    private List<String> elevate(List<String> cmd) {
        if (cmd.isEmpty() || isRoot()) {
            return cmd;
        }
        String pk = which("pkexec");
        if (pk == null) {
            return cmd;
        }
        List<String> out = new ArrayList<>();
        out.add(pk);
        out.addAll(cmd);
        return out;
    }

    // --------------------------------------------------------------- process

    private int run(List<String> cmd, ProgressHandler h, long totalBytes, String stage) {
        List<String> full = cmd.get(0).endsWith("pkexec") || cmd.get(0).contains("pkexec")
                ? cmd : maybeElevateIfNeeded(cmd);
        log(h, "$ " + String.join(" ", full));
        try {
            ProcessBuilder pb = new ProcessBuilder(full);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            activeProcess = p;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (h != null) {
                        h.onLog(line);
                    }
                    double frac = parseProgress(line, totalBytes);
                    if (frac >= 0 && h != null) {
                        h.onProgress(frac, stage);
                    }
                    if (cancelled) {
                        p.destroy();
                        break;
                    }
                }
            }
            int code = p.waitFor();
            activeProcess = null;
            return code;
        } catch (IOException | InterruptedException e) {
            activeProcess = null;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (h != null) {
                h.onLog("error: " + e.getMessage());
            }
            return -1;
        }
    }

    /** dd/mkfs/wodim need root; elevate those when available. */
    private List<String> maybeElevateIfNeeded(List<String> cmd) {
        String exe = new File(cmd.get(0)).getName();
        if (exe.equals("dd") || exe.startsWith("mkfs") || exe.equals("wipefs")
                || exe.equals("parted") || exe.equals("partprobe")
                || exe.equals("blockdev") || exe.equals("wodim")
                || exe.equals("cdrecord") || exe.equals("growisofs")) {
            return elevate(cmd);
        }
        return cmd;
    }

    private double parseProgress(String line, long totalBytes) {
        Matcher dd = DD_BYTES.matcher(line);
        if (dd.find() && totalBytes > 0) {
            double copied = Double.parseDouble(dd.group(1));
            return clamp01(copied / totalBytes);
        }
        Matcher pc = PERCENT.matcher(line);
        if (pc.find()) {
            return clamp01(Double.parseDouble(pc.group(1)) / 100.0);
        }
        return -1;
    }

    private List<String> runCollect(String... cmd) {
        List<String> out = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.add(line);
                }
            }
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return out;
    }

    private void runCollectList(List<String> cmd) {
        runCollect(cmd.toArray(new String[0]));
    }

    // ---------------------------------------------------------------- sha256

    /** SHA-256 of the first {@code limit} bytes of a file or block device. */
    public String sha256(File f, long limit) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 20];
            long remaining = limit < 0 ? Long.MAX_VALUE : limit;
            try (InputStream in = new FileInputStream(f)) {
                while (remaining > 0) {
                    int want = (int) Math.min(buf.length, remaining);
                    int n = in.read(buf, 0, want);
                    if (n <= 0) {
                        break;
                    }
                    md.update(buf, 0, n);
                    remaining -= n;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ----------------------------------------------------------------- misc

    /** Absolute path of a tool on PATH, or null when absent. */
    public String which(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, tool);
            if (f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private String whichOrFail(String tool) {
        String p = which(tool);
        if (p == null) {
            throw new MediaWriterException("Required tool not found: " + tool);
        }
        return p;
    }

    public boolean hasTool(String tool) {
        return which(tool) != null;
    }

    private void log(ProgressHandler h, String line) {
        if (h != null) {
            h.onLog(line);
        }
    }

    private void finish(ProgressHandler h, boolean ok, String msg) {
        if (h != null) {
            h.onProgress(ok ? 1.0 : -1, ok ? "Done" : "Failed");
            h.onFinished(ok, msg);
        }
    }

    private void checkCancelled(ProgressHandler h) {
        if (cancelled) {
            finish(h, false, "Cancelled");
            throw new MediaWriterException("Cancelled");
        }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static String shortHash(String h) {
        return h.length() > 12 ? h.substring(0, 12) : h;
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isEmpty() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, String> parseKv(String line) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher mt = KV.matcher(line);
        while (mt.find()) {
            m.put(mt.group(1), mt.group(2));
        }
        return m;
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n <= 0) {
                break;
            }
            off += n;
        }
        return off;
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } catch (IOException e) {
            // ignore: supplemental source
        }
        return out;
    }

    /** Human-readable byte size (binary units). */
    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] u = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, i == 0 ? "%.0f %s" : "%.1f %s", v, u[i]);
    }
}
