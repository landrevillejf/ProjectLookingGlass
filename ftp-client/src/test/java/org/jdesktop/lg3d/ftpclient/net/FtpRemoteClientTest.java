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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockftpserver.fake.FakeFtpServer;
import org.mockftpserver.fake.UserAccount;
import org.mockftpserver.fake.filesystem.DirectoryEntry;
import org.mockftpserver.fake.filesystem.FileEntry;
import org.mockftpserver.fake.filesystem.FileSystem;
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem;

/**
 * Exercises {@link FtpRemoteClient} end-to-end against an in-JVM
 * {@link FakeFtpServer} bound to an ephemeral port: connect/login, working
 * directory, listing, upload, download, size, exists, mkdir, rename, delete and
 * disconnect. No external server or network access is required, so it is CI-safe.
 */
class FtpRemoteClientTest {

    private static final String HOME = "/home";
    private static final String USER = "user";
    private static final String PASS = "pass";

    private FakeFtpServer server;
    private FileSystem fs;
    private FtpRemoteClient client;

    @BeforeEach
    void startServer() {
        fs = new UnixFakeFileSystem();
        fs.add(new DirectoryEntry(HOME));
        fs.add(new FileEntry(HOME + "/hello.txt", "Hello, world!"));
        fs.add(new DirectoryEntry(HOME + "/pub"));

        server = new FakeFtpServer();
        server.setServerControlPort(0); // ephemeral free port
        server.addUserAccount(new UserAccount(USER, PASS, HOME));
        server.setFileSystem(fs);
        server.start();

        SiteProfile profile = new SiteProfile("mock", Protocol.FTP, "localhost");
        profile.setPort(server.getServerControlPort());
        profile.setUser(USER);
        profile.setPassword(PASS);
        profile.setRemoteDir(HOME);
        // PASSIVE is the default; MockFtpServer supports PASV data connections.
        client = new FtpRemoteClient(profile, new AppSettings());
    }

    @AfterEach
    void stopServer() {
        if (client != null) {
            client.disconnect();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("connect logs in and lands in the profile's remote directory")
    void connect() throws IOException {
        client.connect();
        assertThat(client.isConnected()).isTrue();
        assertThat(client.getProtocol()).isEqualTo(Protocol.FTP);
        assertThat(client.getWorkingDirectory()).isEqualTo(HOME);
    }

    @Test
    @DisplayName("list returns the seeded entries without the navigation rows")
    void list() throws IOException {
        client.connect();
        List<RemoteEntry> entries = client.list(HOME);
        assertThat(entries).extracting(RemoteEntry::getName)
                .contains("hello.txt", "pub")
                .doesNotContain(".", "..");
        RemoteEntry pub = entries.stream()
                .filter(e -> e.getName().equals("pub")).findFirst().orElseThrow();
        assertThat(pub.isDirectory()).isTrue();
    }

    @Test
    @DisplayName("download retrieves a seeded file byte-for-byte")
    void download(@TempDir Path dir) throws IOException {
        client.connect();
        Path local = dir.resolve("hello.txt");
        client.download(HOME + "/hello.txt", local, 0L, null);
        assertThat(Files.readString(local, StandardCharsets.UTF_8)).isEqualTo("Hello, world!");
    }

    @Test
    @DisplayName("upload then download round-trips the file contents")
    void uploadRoundTrip(@TempDir Path dir) throws IOException {
        client.connect();
        Path local = dir.resolve("payload.bin");
        byte[] data = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        Files.write(local, data);

        String remote = HOME + "/payload.bin";
        client.upload(local, remote, 0L, null);
        assertThat(fs.exists(remote)).isTrue();
        assertThat(fs.isFile(remote)).isTrue();
        assertThat(((FileEntry) fs.getEntry(remote)).getSize()).isEqualTo(data.length);

        Path back = dir.resolve("payload-back.bin");
        client.download(remote, back, 0L, null);
        assertThat(Files.readAllBytes(back)).isEqualTo(data);
    }

    @Test
    @DisplayName("size and exists report the seeded file correctly")
    void sizeAndExists() throws IOException {
        client.connect();
        assertThat(client.exists(HOME + "/hello.txt")).isTrue();
        assertThat(client.size(HOME + "/hello.txt")).isEqualTo("Hello, world!".length());
        assertThat(client.exists(HOME + "/missing.txt")).isFalse();
        assertThat(client.size(HOME + "/missing.txt")).isEqualTo(RemoteEntry.UNKNOWN_SIZE);
    }

    @Test
    @DisplayName("makeDirectory creates a remote directory")
    void makeDirectory() throws IOException {
        client.connect();
        client.makeDirectory(HOME + "/incoming");
        assertThat(fs.isDirectory(HOME + "/incoming")).isTrue();
    }

    @Test
    @DisplayName("rename moves a remote file")
    void rename() throws IOException {
        client.connect();
        client.rename(HOME + "/hello.txt", HOME + "/renamed.txt");
        assertThat(fs.exists(HOME + "/hello.txt")).isFalse();
        assertThat(fs.exists(HOME + "/renamed.txt")).isTrue();
    }

    @Test
    @DisplayName("delete removes a remote file and removeDirectory removes a directory")
    void deleteAndRemoveDirectory() throws IOException {
        client.connect();
        client.delete(HOME + "/hello.txt");
        assertThat(fs.exists(HOME + "/hello.txt")).isFalse();
        client.removeDirectory(HOME + "/pub");
        assertThat(fs.exists(HOME + "/pub")).isFalse();
    }

    @Test
    @DisplayName("changeDirectory updates the working directory")
    void changeDirectory() throws IOException {
        client.connect();
        client.changeDirectory(HOME + "/pub");
        assertThat(client.getWorkingDirectory()).isEqualTo(HOME + "/pub");
    }

    @Test
    @DisplayName("disconnect drops the control connection")
    void disconnect() throws IOException {
        client.connect();
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
        assertThat(client.isConnected()).isFalse();
    }
}
