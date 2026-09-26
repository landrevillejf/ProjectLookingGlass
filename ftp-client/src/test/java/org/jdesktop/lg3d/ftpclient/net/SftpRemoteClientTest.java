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
package org.jdesktop.lg3d.ftpclient.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Vector;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link SftpRemoteClient} through its package-private test seam: an
 * injected, mocked {@link ChannelSftp} lets the listing, stat, working-directory,
 * download and upload logic (including the resume offset) be exercised with no
 * live SSH server. The pure {@code toPermissionString} helper is tested directly.
 */
class SftpRemoteClientTest {

    private final SiteProfile profile = new SiteProfile("sftp", Protocol.SFTP, "sftp.example.com");
    private final AppSettings settings = new AppSettings();

    private SftpRemoteClient clientWith(ChannelSftp channel) {
        return new SftpRemoteClient(profile, settings, channel);
    }

    private ChannelSftp connectedChannel() {
        ChannelSftp channel = mock(ChannelSftp.class);
        when(channel.isConnected()).thenReturn(true);
        return channel;
    }

    // ------------------------------------------------------------------
    // pure helper
    // ------------------------------------------------------------------

    @Test
    @DisplayName("toPermissionString drops the leading file-type column")
    void permissionString() {
        assertThat(SftpRemoteClient.toPermissionString("-rwxr-xr-x")).isEqualTo("rwxr-xr-x");
        assertThat(SftpRemoteClient.toPermissionString("drwxr-xr-x")).isEqualTo("rwxr-xr-x");
        // Already nine characters: returned unchanged.
        assertThat(SftpRemoteClient.toPermissionString("rwxr-xr-x")).isEqualTo("rwxr-xr-x");
        // Longer than ten: keeps the trailing nine.
        assertThat(SftpRemoteClient.toPermissionString("--rwxr-xr-x")).isEqualTo("rwxr-xr-x");
        // Short/blank/null degrade to empty.
        assertThat(SftpRemoteClient.toPermissionString("abc")).isEqualTo("abc");
        assertThat(SftpRemoteClient.toPermissionString("")).isEmpty();
        assertThat(SftpRemoteClient.toPermissionString(null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // connection state
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getProtocol is SFTP and the injected-channel client reports not connected")
    void protocolAndConnectionState() {
        SftpRemoteClient c = clientWith(connectedChannel());
        assertThat(c.getProtocol()).isEqualTo(Protocol.SFTP);
        // The test seam leaves the SSH session null, so isConnected() is false
        // even though the injected channel answers operations.
        assertThat(c.isConnected()).isFalse();
    }

    @Test
    @DisplayName("operations fail cleanly when the channel is not connected")
    void requireChannelWhenDisconnected() {
        ChannelSftp dead = mock(ChannelSftp.class);
        when(dead.isConnected()).thenReturn(false);
        SftpRemoteClient c = clientWith(dead);
        assertThatThrownBy(() -> c.list("/pub")).isInstanceOf(IOException.class);
        assertThatThrownBy(c::getWorkingDirectory).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("disconnect closes the injected channel")
    void disconnectClosesChannel() {
        ChannelSftp channel = connectedChannel();
        SftpRemoteClient c = clientWith(channel);
        c.disconnect();
        verify(channel).disconnect();
    }

    // ------------------------------------------------------------------
    // working directory / navigation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getWorkingDirectory returns pwd, defaulting to root when empty")
    void workingDirectory() throws Exception {
        ChannelSftp channel = connectedChannel();
        when(channel.pwd()).thenReturn("/home/alice");
        assertThat(clientWith(channel).getWorkingDirectory()).isEqualTo("/home/alice");

        when(channel.pwd()).thenReturn("");
        assertThat(clientWith(channel).getWorkingDirectory()).isEqualTo("/");
    }

    @Test
    @DisplayName("getWorkingDirectory wraps a pwd failure in IOException")
    void workingDirectoryFailure() throws Exception {
        ChannelSftp channel = connectedChannel();
        when(channel.pwd()).thenThrow(new SftpException(4, "boom"));
        assertThatThrownBy(() -> clientWith(channel).getWorkingDirectory())
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("changeDirectory normalizes the path before cd")
    void changeDirectory() throws Exception {
        ChannelSftp channel = connectedChannel();
        clientWith(channel).changeDirectory("pub//files/");
        verify(channel).cd("/pub/files");
    }

    // ------------------------------------------------------------------
    // listing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("list maps entries and drops the . and .. rows")
    void list() throws Exception {
        ChannelSftp channel = connectedChannel();
        Vector<ChannelSftp.LsEntry> entries = new Vector<>();
        entries.add(lsEntry("notes.txt", false, 1024L, 1_700_000_000, "-rw-r--r--", false));
        entries.add(lsEntry("pub", true, 0L, 1_700_000_000, "drwxr-xr-x", false));
        entries.add(lsEntry(".", true, 0L, 0, null, false));
        entries.add(lsEntry("..", true, 0L, 0, null, false));
        when(channel.ls("/pub")).thenReturn(entries);

        List<RemoteEntry> out = clientWith(channel).list("/pub");
        assertThat(out).extracting(RemoteEntry::getName).containsExactly("notes.txt", "pub");

        RemoteEntry file = out.get(0);
        assertThat(file.getSize()).isEqualTo(1024L);
        assertThat(file.getModifiedMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(file.getPermissions()).isEqualTo("rw-r--r--");
        assertThat(file.isDirectory()).isFalse();

        RemoteEntry dir = out.get(1);
        assertThat(dir.isDirectory()).isTrue();
        assertThat(dir.getSize()).isEqualTo(RemoteEntry.UNKNOWN_SIZE);
    }

    @Test
    @DisplayName("list resolves a symlink target via readlink")
    void listSymlink() throws Exception {
        ChannelSftp channel = connectedChannel();
        Vector<ChannelSftp.LsEntry> entries = new Vector<>();
        entries.add(lsEntry("link", false, 0L, 0, "lrwxrwxrwx", true));
        when(channel.ls("/")).thenReturn(entries);
        when(channel.readlink("/link")).thenReturn("/pub/target.txt");

        List<RemoteEntry> out = clientWith(channel).list("/");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getLinkTarget()).isEqualTo("/pub/target.txt");
    }

    @Test
    @DisplayName("list wraps an ls failure in IOException")
    void listFailure() throws Exception {
        ChannelSftp channel = connectedChannel();
        when(channel.ls("/nope")).thenThrow(new SftpException(2, "no such dir"));
        assertThatThrownBy(() -> clientWith(channel).list("/nope")).isInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------
    // stat: exists / size
    // ------------------------------------------------------------------

    @Test
    @DisplayName("exists is true on a successful stat and false for NO_SUCH_FILE")
    void exists() throws Exception {
        ChannelSftp channel = connectedChannel();
        SftpATTRS attrs = mock(SftpATTRS.class);
        when(attrs.getSize()).thenReturn(2048L);
        when(channel.stat("/f.txt")).thenReturn(attrs);
        SftpRemoteClient c = clientWith(channel);
        assertThat(c.exists("/f.txt")).isTrue();
        assertThat(c.size("/f.txt")).isEqualTo(2048L);

        when(channel.stat("/missing")).thenThrow(
                new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "no such file"));
        assertThat(c.exists("/missing")).isFalse();
    }

    @Test
    @DisplayName("exists surfaces a non-not-found stat error as IOException")
    void existsOtherError() throws Exception {
        ChannelSftp channel = connectedChannel();
        when(channel.stat("/denied")).thenThrow(new SftpException(3, "permission denied"));
        assertThatThrownBy(() -> clientWith(channel).exists("/denied")).isInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------
    // transfers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("download writes the remote stream to the local file")
    void download(@TempDir Path dir) throws Exception {
        ChannelSftp channel = connectedChannel();
        byte[] payload = "hello remote".getBytes(StandardCharsets.UTF_8);
        when(channel.get(eq("/remote/f.txt"), any(SftpProgressMonitor.class), eq(0L)))
                .thenReturn(new ByteArrayInputStream(payload));

        Path target = dir.resolve("nested/f.txt");
        clientWith(channel).download("/remote/f.txt", target, 0L, null);
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("hello remote");
    }

    @Test
    @DisplayName("download honours a non-zero resume offset")
    void downloadResume(@TempDir Path dir) throws Exception {
        ChannelSftp channel = connectedChannel();
        when(channel.get(eq("/remote/f.txt"), any(SftpProgressMonitor.class), anyLong()))
                .thenReturn(new ByteArrayInputStream("tail".getBytes(StandardCharsets.UTF_8)));

        Path target = dir.resolve("f.txt");
        clientWith(channel).download("/remote/f.txt", target, 100L, null);
        verify(channel).get(eq("/remote/f.txt"), any(SftpProgressMonitor.class), eq(100L));
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("tail");
    }

    @Test
    @DisplayName("upload sends the whole local file in OVERWRITE mode")
    void upload(@TempDir Path dir) throws Exception {
        Path local = dir.resolve("local.txt");
        Files.writeString(local, "hello world", StandardCharsets.UTF_8);

        ChannelSftp channel = connectedChannel();
        ByteArrayOutputStream remote = new ByteArrayOutputStream();
        when(channel.put(eq("/remote/f.txt"), any(SftpProgressMonitor.class),
                eq(ChannelSftp.OVERWRITE), eq(0L))).thenReturn(remote);

        clientWith(channel).upload(local, "/remote/f.txt", 0L, null);
        assertThat(remote.toString(StandardCharsets.UTF_8)).isEqualTo("hello world");
    }

    @Test
    @DisplayName("upload resumes from the offset in APPEND mode, skipping leading bytes")
    void uploadResume(@TempDir Path dir) throws Exception {
        Path local = dir.resolve("local.txt");
        Files.writeString(local, "hello world", StandardCharsets.UTF_8);

        ChannelSftp channel = connectedChannel();
        ByteArrayOutputStream remote = new ByteArrayOutputStream();
        when(channel.put(eq("/remote/f.txt"), any(SftpProgressMonitor.class),
                eq(ChannelSftp.APPEND), eq(6L))).thenReturn(remote);

        clientWith(channel).upload(local, "/remote/f.txt", 6L, null);
        assertThat(remote.toString(StandardCharsets.UTF_8)).isEqualTo("world");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ChannelSftp.LsEntry lsEntry(String name, boolean dir, long size,
                                               int mtime, String perms, boolean link) {
        ChannelSftp.LsEntry entry = mock(ChannelSftp.LsEntry.class);
        when(entry.getFilename()).thenReturn(name);
        SftpATTRS attrs = mock(SftpATTRS.class);
        when(attrs.isDir()).thenReturn(dir);
        when(attrs.getSize()).thenReturn(size);
        when(attrs.getMTime()).thenReturn(mtime);
        when(attrs.isLink()).thenReturn(link);
        if (perms != null) {
            when(attrs.getPermissionsString()).thenReturn(perms);
        }
        when(entry.getAttrs()).thenReturn(attrs);
        return entry;
    }
}
