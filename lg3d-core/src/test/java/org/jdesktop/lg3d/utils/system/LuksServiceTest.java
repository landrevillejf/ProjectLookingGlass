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

import java.util.List;
import java.util.Map;
import org.jdesktop.lg3d.utils.system.LuksService.Operation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link LuksService}: the exact {@code cryptsetup} /
 * {@code lfs-encrypt-disk} argument vectors (§4.5, no invented flags), the
 * mutating/destructive classification that drives the UI's confirmation level,
 * the pure {@code cryptsetup status} parsing, and the guard paths of the run
 * helpers that return before spawning a process or a polkit prompt.
 */
class LuksServiceTest {

    @Test
    @DisplayName("status builds the read-only cryptsetup status vector")
    void statusVector() {
        assertEquals(List.of("cryptsetup", "status", "cryptvol"),
                LuksService.buildCommand(Operation.STATUS, null, "cryptvol"));
        assertTrue(LuksService.buildCommand(Operation.STATUS, "/dev/sda2", null).isEmpty(),
                "status needs a mapper name, not a device");
        assertTrue(LuksService.buildCommand(Operation.STATUS, null, "  ").isEmpty());
    }

    @Test
    @DisplayName("open builds cryptsetup luksOpen <device> <name>")
    void openVector() {
        assertEquals(List.of("cryptsetup", "luksOpen", "/dev/sda2", "cryptvol"),
                LuksService.buildCommand(Operation.OPEN, "/dev/sda2", "cryptvol"));
        assertTrue(LuksService.buildCommand(Operation.OPEN, "/dev/sda2", null).isEmpty());
        assertTrue(LuksService.buildCommand(Operation.OPEN, null, "cryptvol").isEmpty());
    }

    @Test
    @DisplayName("close builds cryptsetup luksClose <name>")
    void closeVector() {
        assertEquals(List.of("cryptsetup", "luksClose", "cryptvol"),
                LuksService.buildCommand(Operation.CLOSE, null, "cryptvol"));
        assertTrue(LuksService.buildCommand(Operation.CLOSE, null, "").isEmpty());
    }

    @Test
    @DisplayName("add-key builds cryptsetup luksAddKey <device>")
    void addKeyVector() {
        assertEquals(List.of("cryptsetup", "luksAddKey", "/dev/sda2"),
                LuksService.buildCommand(Operation.ADD_KEY, "/dev/sda2", null));
        assertTrue(LuksService.buildCommand(Operation.ADD_KEY, "  ", null).isEmpty());
    }

    @Test
    @DisplayName("format builds cryptsetup luksFormat --type luks2 <device> (no invented flags)")
    void formatVector() {
        assertEquals(List.of("cryptsetup", "luksFormat", "--type", "luks2", "/dev/sda2"),
                LuksService.buildCommand(Operation.FORMAT, "/dev/sda2", null));
        assertTrue(LuksService.buildCommand(Operation.FORMAT, null, null).isEmpty());
    }

    @Test
    @DisplayName("encrypt-disk builds the installer helper vector, name optional")
    void encryptDiskVector() {
        assertEquals(List.of("/usr/sbin/lfs-encrypt-disk", "/dev/sda"),
                LuksService.buildCommand(Operation.ENCRYPT_DISK, "/dev/sda", null));
        assertEquals(List.of("/usr/sbin/lfs-encrypt-disk", "/dev/sda", "cryptvol"),
                LuksService.buildCommand(Operation.ENCRYPT_DISK, "/dev/sda", "cryptvol"));
        assertTrue(LuksService.buildCommand(Operation.ENCRYPT_DISK, "", "cryptvol").isEmpty());
        assertEquals("/usr/sbin/lfs-encrypt-disk", LuksService.ENCRYPT_DISK_HELPER);
    }

    @Test
    @DisplayName("a null operation yields no vector")
    void nullOperation() {
        assertTrue(LuksService.buildCommand(null, "/dev/sda", "cryptvol").isEmpty());
    }

    @Test
    @DisplayName("status is read-only; open/close/add-key mutate; format/encrypt-disk destroy")
    void operationClassification() {
        assertFalse(Operation.STATUS.isMutating());
        assertFalse(Operation.STATUS.isDestructive());

        for (Operation op : List.of(Operation.OPEN, Operation.CLOSE, Operation.ADD_KEY)) {
            assertTrue(op.isMutating(), op + " is mutating");
            assertFalse(op.isDestructive(), op + " is not destructive");
        }

        for (Operation op : List.of(Operation.FORMAT, Operation.ENCRYPT_DISK)) {
            assertTrue(op.isMutating(), op + " is mutating");
            assertTrue(op.isDestructive(), op + " erases data and needs a typed confirmation");
        }
    }

    @Test
    @DisplayName("cryptsetup status output parses into a case-insensitive field map")
    void parsesStatus() {
        Map<String, String> f = LuksService.parseStatus(
                "/dev/mapper/cryptvol is active and is in use.\n"
                + "type:    LUKS2\n"
                + "cipher:  aes-xts-plain64\n"
                + "keysize: 512 bits\n"
                + "Key location: keyring\n"
                + "device:  /dev/sda2\n"
                + "mode:    read/write\n");
        assertEquals("LUKS2", f.get("type"));
        assertEquals("aes-xts-plain64", f.get("cipher"));
        assertEquals("512 bits", f.get("keysize"));
        assertEquals("keyring", f.get("key_location"), "a spaced key is underscored + lowercased");
        assertEquals("/dev/sda2", f.get("device"));
        assertEquals("read/write", f.get("mode"), "the value keeps everything after the first colon");
        assertFalse(f.containsKey("/dev/mapper/cryptvol is active and is in use."),
                "a colon-less banner line is ignored");
    }

    @Test
    @DisplayName("status keys are matched case-insensitively")
    void statusKeysCaseInsensitive() {
        assertEquals("luks2", LuksService.parseStatus("TYPE: luks2\n").get("type"));
    }

    @Test
    @DisplayName("empty, null, colon-less and malformed status lines yield no fields")
    void statusDegradesGracefully() {
        assertTrue(LuksService.parseStatus(null).isEmpty());
        assertTrue(LuksService.parseStatus("").isEmpty());
        assertTrue(LuksService.parseStatus("no colon here\njust prose\n").isEmpty());
        assertTrue(LuksService.parseStatus(":leading colon\n").isEmpty());
        assertTrue(LuksService.parseStatus("trailing colon:\n").isEmpty());
    }

    @Test
    @DisplayName("runStatus on a blank name returns a not-started result without spawning")
    void runStatusGuardsBlankName() {
        ProcessRunner.Result r = LuksService.runStatus("");
        assertFalse(r.isStarted());
        assertFalse(r.isSuccess());
        assertTrue(r.getStderr().contains("name is required"));
    }

    @Test
    @DisplayName("runMutating refuses a read-only op or an invalid vector before escalating")
    void runMutatingGuards() {
        PrivilegedRunner.PrivilegedResult readOnly =
                LuksService.runMutating(Operation.STATUS, "/dev/sda2", "cryptvol", null);
        assertEquals(PrivilegedRunner.Status.ERROR, readOnly.getStatus());
        assertFalse(readOnly.isSuccess());
        assertEquals(-1, readOnly.getExitCode());

        PrivilegedRunner.PrivilegedResult badVector =
                LuksService.runMutating(Operation.OPEN, null, null, "secret");
        assertEquals(PrivilegedRunner.Status.ERROR, badVector.getStatus());
        assertTrue(badVector.getMessage().contains("invalid arguments"));

        PrivilegedRunner.PrivilegedResult nullOp =
                LuksService.runMutating(null, "/dev/sda2", "cryptvol", null);
        assertEquals(PrivilegedRunner.Status.ERROR, nullOp.getStatus());
    }
}
