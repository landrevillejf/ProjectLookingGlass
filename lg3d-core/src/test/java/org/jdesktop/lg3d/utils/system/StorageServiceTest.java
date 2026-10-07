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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.jdesktop.lg3d.utils.system.StorageService.BlockDevice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link StorageService}: the read-only {@code lsblk}/{@code
 * blkid} argument vectors and the pure {@code lsblk -P} key/value parsing into
 * {@link BlockDevice} values. All pure - no process is spawned and no {@code
 * lsblk} binary is needed.
 */
class StorageServiceTest {

    @Test
    @DisplayName("the list vector is exactly lsblk -P -o <columns>")
    void listCommandVector() {
        assertEquals(
                List.of("lsblk", "-P", "-o", "NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT"),
                StorageService.buildListCommand());
    }

    @Test
    @DisplayName("the UUID vector is blkid -s UUID -o value <device>")
    void uuidCommandVector() {
        assertEquals(List.of("blkid", "-s", "UUID", "-o", "value", "/dev/sda2"),
                StorageService.buildUuidCommand("/dev/sda2"));
        assertEquals(List.of("blkid", "-s", "UUID", "-o", "value", "/dev/sda2"),
                StorageService.buildUuidCommand("  /dev/sda2  "), "the device is trimmed");
        assertTrue(StorageService.buildUuidCommand(null).isEmpty());
        assertTrue(StorageService.buildUuidCommand("   ").isEmpty(), "a blank device yields no vector");
    }

    @Test
    @DisplayName("lsblk -P key/value lines parse into block devices")
    void parsesDevices() {
        List<BlockDevice> devs = StorageService.parseDevices(Arrays.asList(
                "NAME=\"sda\" SIZE=\"1.8T\" TYPE=\"disk\" FSTYPE=\"\" MOUNTPOINT=\"\"",
                "NAME=\"sda1\" SIZE=\"1G\" TYPE=\"part\" FSTYPE=\"ext4\" MOUNTPOINT=\"/boot\"",
                "NAME=\"sda2\" SIZE=\"1.8T\" TYPE=\"part\" FSTYPE=\"crypto_LUKS\" MOUNTPOINT=\"\""));
        assertEquals(3, devs.size());

        BlockDevice disk = devs.get(0);
        assertEquals("sda", disk.getName());
        assertEquals("1.8T", disk.getSize());
        assertEquals("disk", disk.getType());
        assertEquals("", disk.getFsType());
        assertEquals("", disk.getMountpoint());

        BlockDevice boot = devs.get(1);
        assertEquals("/boot", boot.getMountpoint());
        assertEquals("/dev/sda1", boot.getPath());
        assertFalse(boot.isLuks());

        assertTrue(devs.get(2).isLuks(), "a crypto_LUKS fstype is detected");
    }

    @Test
    @DisplayName("missing columns default to empty strings, never null")
    void missingColumnsDefaultEmpty() {
        List<BlockDevice> devs = StorageService.parseDevices(
                List.of("NAME=\"loop0\" TYPE=\"loop\""));
        assertEquals(1, devs.size());
        BlockDevice loop = devs.get(0);
        assertEquals("loop0", loop.getName());
        assertEquals("", loop.getSize());
        assertEquals("", loop.getFsType());
        assertEquals("", loop.getMountpoint());
    }

    @Test
    @DisplayName("blank, null and NAME-less lines are dropped without throwing")
    void dropsJunkLines() {
        assertTrue(StorageService.parseDevices(null).isEmpty());
        List<BlockDevice> devs = StorageService.parseDevices(Arrays.asList(
                "", "   ", "SIZE=\"1G\" TYPE=\"disk\"", null));
        assertTrue(devs.isEmpty(), "a line with no NAME yields no device");
    }

    @Test
    @DisplayName("getPath prefixes /dev and isLuks is case-insensitive")
    void pathAndLuksDetection() {
        assertEquals("/dev/sdb", new BlockDevice("sdb", "", "", "", "").getPath());
        assertEquals("/dev/sdb", new BlockDevice("/dev/sdb", "", "", "", "").getPath(),
                "an already-absolute name is not double-prefixed");
        assertTrue(new BlockDevice("sdb", "", "", "crypto_LUKS", "").isLuks());
        assertTrue(new BlockDevice("sdb", "", "", "CRYPTO_LUKS", "").isLuks(),
                "fstype match ignores case");
        assertFalse(new BlockDevice("sdb", "", "", "swap", "").isLuks());
    }

    @Test
    @DisplayName("null constructor arguments are stored as empty strings")
    void nullSafeFields() {
        BlockDevice d = new BlockDevice(null, null, null, null, null);
        assertEquals("", d.getName());
        assertEquals("", d.getSize());
        assertEquals("", d.getType());
        assertEquals("", d.getFsType());
        assertEquals("", d.getMountpoint());
        assertEquals("/dev/", d.getPath());
        assertFalse(d.isLuks());
    }

    @Test
    @DisplayName("toString is a single-line cell label carrying the present fields")
    void toStringLabel() {
        BlockDevice d = new BlockDevice("sda1", "1G", "part", "ext4", "/boot");
        String s = d.toString();
        assertTrue(s.startsWith("sda1"), "the label leads with the device name");
        assertTrue(s.contains("1G"));
        assertTrue(s.contains("[part]"));
        assertTrue(s.contains("ext4"));
        assertTrue(s.contains("/boot"));
        assertFalse(s.contains("\n"), "the label is a single line");
        // an empty device renders as just its name
        assertEquals("sdb", new BlockDevice("sdb", "", "", "", "").toString());
    }
}
