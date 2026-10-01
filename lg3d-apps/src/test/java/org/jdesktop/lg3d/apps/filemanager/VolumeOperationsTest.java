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
package org.jdesktop.lg3d.apps.filemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.apps.filemanager.VolumeOperations.Volume;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VolumeOperations}: the {@code lsblk -P} parsing (which
 * volumes are considered mountable, mounted-state, size and removable flags),
 * the {@code udisksctl} "Mounted ... at ..." output handling, and the guard
 * clauses that reject empty mount/unmount requests without shelling out.
 *
 * <p>Pure parsing/guard tests only: nothing here mounts a real device, so the
 * suite is hermetic and CI-safe regardless of the host's storage.</p>
 */
class VolumeOperationsTest {

    private static String lsblk(String name, String path, String type, String size,
                                String rm, String mp, String label, String fs) {
        return "NAME=\"" + name + "\" PATH=\"" + path + "\" TYPE=\"" + type
                + "\" SIZE=\"" + size + "\" RM=\"" + rm + "\" MOUNTPOINT=\"" + mp
                + "\" LABEL=\"" + label + "\" FSTYPE=\"" + fs + "\"";
    }

    @Test
    @DisplayName("a vfat partition is parsed as a mountable, removable volume")
    void parsesMountablePartition() {
        List<Volume> vols = VolumeOperations.parseLsblk(List.of(
                lsblk("sdb1", "/dev/sdb1", "part", "16000000000", "1", "", "STICK", "vfat")));
        assertEquals(1, vols.size());
        Volume v = vols.get(0);
        assertEquals("/dev/sdb1", v.path);
        assertEquals("vfat", v.fsType);
        assertEquals("STICK", v.label);
        assertEquals(16_000_000_000L, v.sizeBytes);
        assertTrue(v.removable);
        assertFalse(v.isMounted());
    }

    @Test
    @DisplayName("a mounted ext4 partition reports its mount point")
    void parsesMountedState() {
        List<Volume> vols = VolumeOperations.parseLsblk(List.of(
                lsblk("sda2", "/dev/sda2", "part", "512000", "0", "/home", "", "ext4")));
        assertEquals(1, vols.size());
        assertTrue(vols.get(0).isMounted());
        assertEquals("/home", vols.get(0).mountPoint);
    }

    @Test
    @DisplayName("swap, raid, LVM and blank-filesystem rows are skipped")
    void skipsNonMountableFilesystems() {
        List<Volume> vols = VolumeOperations.parseLsblk(List.of(
                lsblk("sda1", "/dev/sda1", "part", "1000", "0", "", "", "swap"),
                lsblk("md0", "/dev/md0", "raid1", "2000", "0", "", "", "linux_raid_member"),
                lsblk("sda3", "/dev/sda3", "part", "3000", "0", "", "", "lvm2_member"),
                lsblk("sdc", "/dev/sdc", "disk", "4000", "1", "", "", "")));
        assertTrue(vols.isEmpty(), "none of these carry a mountable filesystem: " + vols);
    }

    @Test
    @DisplayName("an empty or null lsblk output yields an empty list")
    void parsesEmptyOutput() {
        assertTrue(VolumeOperations.parseLsblk(List.of()).isEmpty());
        assertTrue(VolumeOperations.parseLsblk(null).isEmpty());
    }

    @Test
    @DisplayName("a non-numeric size is tolerated as zero")
    void toleratesBadSize() {
        List<Volume> vols = VolumeOperations.parseLsblk(List.of(
                lsblk("sdb1", "/dev/sdb1", "part", "not-a-number", "1", "", "", "exfat")));
        assertEquals(1, vols.size());
        assertEquals(0L, vols.get(0).sizeBytes);
    }

    @Test
    @DisplayName("a whole-disk vfat key (no partition table) is still mountable")
    void parsesWholeDiskFilesystem() {
        List<Volume> vols = VolumeOperations.parseLsblk(List.of(
                lsblk("sdb", "/dev/sdb", "disk", "8000", "1", "", "KEY", "vfat")));
        assertEquals(1, vols.size());
        assertEquals("/dev/sdb", vols.get(0).path);
    }

    @Test
    @DisplayName("parseMountedAt extracts and trims the udisksctl mount point")
    void parsesMountedAt() {
        assertEquals("/media/user/STICK",
                VolumeOperations.parseMountedAt("Mounted /dev/sdb1 at /media/user/STICK."));
        assertEquals("/run/media/x",
                VolumeOperations.parseMountedAt("Mounted /dev/sdc1 at /run/media/x.\n"));
        assertEquals("", VolumeOperations.parseMountedAt(""));
        assertEquals("", VolumeOperations.parseMountedAt(null));
        assertEquals("", VolumeOperations.parseMountedAt("Error mounting /dev/sdb1"));
    }

    @Test
    @DisplayName("describe() mentions the device path and mounted state")
    void describeIsInformative() {
        Volume mounted = VolumeOperations.parseLsblk(List.of(
                lsblk("sdb1", "/dev/sdb1", "part", "1000", "1", "/media/x", "STICK", "vfat"))).get(0);
        String d = mounted.describe();
        assertTrue(d.contains("/dev/sdb1"), d);
        assertTrue(d.contains("mounted"), d);

        Volume free = VolumeOperations.parseLsblk(List.of(
                lsblk("sdc1", "/dev/sdc1", "part", "1000", "1", "", "KEY", "vfat"))).get(0);
        assertTrue(free.describe().contains("not mounted"), free.describe());
    }

    @Test
    @DisplayName("mount/unmount reject empty targets without invoking any tool")
    void guardsRejectEmptyTargets() {
        VolumeOperations.OpResult m = VolumeOperations.mount(null, null);
        assertFalse(m.isSuccess());
        assertFalse(m.getMessage().isEmpty());

        VolumeOperations.OpResult m2 = VolumeOperations.mount("  ", "x");
        assertFalse(m2.isSuccess());

        VolumeOperations.OpResult u = VolumeOperations.unmount(null, "");
        assertFalse(u.isSuccess());
        assertFalse(u.getMessage().isEmpty());
    }

    @Test
    @DisplayName("listVolumes() never throws and returns a non-null list")
    void listVolumesIsSafe() {
        List<Volume> vols = VolumeOperations.listVolumes();
        assertNotNull(vols);
        // canElevate() simply reports pkexec availability; it must not throw.
        boolean ignored = VolumeOperations.canElevate();
        assertNotNull(vols);
    }
}
