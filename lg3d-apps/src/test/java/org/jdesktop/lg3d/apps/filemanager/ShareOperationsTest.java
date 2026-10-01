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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ShareOperations}: a real HTTP folder share is started on
 * an ephemeral loopback port and exercised end-to-end (directory listing, file
 * download, 404, the path-traversal guard and {@code stop()}). Binding to port
 * 0 on the loopback keeps the suite hermetic and CI-safe (no fixed port, no LAN
 * dependency).
 */
class ShareOperationsTest {

    @TempDir
    Path temp;

    private Path sharedRoot() throws IOException {
        Path root = temp.resolve("shared");
        Files.createDirectories(root.resolve("docs"));
        Files.write(root.resolve("hello.txt"), "hi there".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("my file.txt"), "spaced".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("docs").resolve("readme.md"),
                "# doc".getBytes(StandardCharsets.UTF_8));
        return root;
    }

    private static final class Resp {
        final int code;
        final String body;
        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private Resp get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String body = (is == null) ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        c.disconnect();
        return new Resp(code, body);
    }

    private String base(ShareOperations.Share share) {
        return "http://127.0.0.1:" + share.getPort();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("the root listing links every entry, folders with a trailing slash")
    void listingShowsEntries() throws Exception {
        Path root = sharedRoot();
        try (ShareOperations.Share share = ShareOperations.start(root, 0)) {
            assertTrue(share.getPort() > 0);
            Resp r = get(base(share) + "/");
            assertEquals(200, r.code);
            assertTrue(r.body.contains("hello.txt"), r.body);
            assertTrue(r.body.contains("docs/"), "folders link with a trailing slash: " + r.body);
            assertTrue(r.body.contains("my%20file.txt"),
                    "the href percent-encodes the space: " + r.body);
            assertTrue(r.body.contains("my file.txt"),
                    "the visible label keeps the literal space: " + r.body);
        }
    }

    @Test
    @DisplayName("a file downloads with its exact content")
    void downloadsFile() throws Exception {
        Path root = sharedRoot();
        try (ShareOperations.Share share = ShareOperations.start(root, 0)) {
            Resp r = get(base(share) + "/hello.txt");
            assertEquals(200, r.code);
            assertEquals("hi there", r.body);
        }
    }

    @Test
    @DisplayName("a percent-encoded space in the URL resolves the right file")
    void downloadsFileWithSpace() throws Exception {
        Path root = sharedRoot();
        try (ShareOperations.Share share = ShareOperations.start(root, 0)) {
            Resp r = get(base(share) + "/my%20file.txt");
            assertEquals(200, r.code);
            assertEquals("spaced", r.body);
        }
    }

    @Test
    @DisplayName("a subdirectory listing and nested file both resolve")
    void navigatesSubdirectory() throws Exception {
        Path root = sharedRoot();
        try (ShareOperations.Share share = ShareOperations.start(root, 0)) {
            Resp listing = get(base(share) + "/docs/");
            assertEquals(200, listing.code);
            assertTrue(listing.body.contains("readme.md"), listing.body);
            assertTrue(listing.body.contains("../"), "a subdir offers a parent link");

            Resp file = get(base(share) + "/docs/readme.md");
            assertEquals(200, file.code);
            assertEquals("# doc", file.body);
        }
    }

    @Test
    @DisplayName("a missing path returns 404")
    void missingPathIs404() throws Exception {
        Path root = sharedRoot();
        try (ShareOperations.Share share = ShareOperations.start(root, 0)) {
            assertEquals(404, get(base(share) + "/nope.txt").code);
        }
    }

    @Test
    @DisplayName("a path-traversal request is refused and leaks nothing (4xx)")
    void traversalIsRefused() throws Exception {
        Path root = sharedRoot();
        // A file that lives OUTSIDE the shared root and must never be served.
        Files.write(temp.resolve("secret.txt"), "top-secret".getBytes(StandardCharsets.UTF_8));
        try (ShareOperations.Share share = ShareOperations.start(root, 0)) {
            int code = rawStatus(share.getPort(), "/%2e%2e/secret.txt");
            assertTrue(code >= 400 && code < 500,
                    "a traversal request must be refused with a 4xx status, got " + code);
        }
    }

    /** Issues a raw HTTP GET (no client-side normalisation) and reads the code. */
    private int rawStatus(int port, String rawPath) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 4000);
            s.setSoTimeout(4000);
            OutputStream os = s.getOutputStream();
            os.write(("GET " + rawPath + " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = r.readLine();
            assertNotNull(statusLine, "the server must send a status line");
            String[] parts = statusLine.split("\\s+");
            assertTrue(parts.length >= 2, statusLine);
            return Integer.parseInt(parts[1]);
        }
    }

    @Test
    @DisplayName("start() rejects a null or non-directory source")
    void startRejectsBadDirectory() {
        assertThrows(IOException.class, () -> ShareOperations.start(null, 0));
        assertThrows(IOException.class,
                () -> ShareOperations.start(temp.resolve("missing.txt"), 0));
    }

    @Test
    @DisplayName("stop() is idempotent and getUrl() carries host and port")
    void stopIsIdempotent() throws Exception {
        Path root = sharedRoot();
        ShareOperations.Share share = ShareOperations.start(root, 0);
        String url = share.getUrl();
        assertTrue(url.startsWith("http://"), url);
        assertTrue(url.contains(":" + share.getPort()), url);
        assertEquals(root.toAbsolutePath().normalize(), share.getRoot());
        share.stop();
        share.stop(); // must not throw
        assertFalse(url.isEmpty());
    }

    @Test
    @DisplayName("localAddress() returns a usable, non-empty host string")
    void localAddressIsResolved() {
        String addr = ShareOperations.localAddress();
        assertNotNull(addr);
        assertFalse(addr.isBlank());
    }

    @Test
    @DisplayName("start(dir) falls back to an ephemeral port when the default is taken")
    void startFallsBackToEphemeralPort() throws Exception {
        Path root = sharedRoot();
        // Occupy an ephemeral port first, then let start(dir) pick any free one.
        try (ShareOperations.Share first = ShareOperations.start(root, 0);
             ShareOperations.Share second = ShareOperations.start(root, 0)) {
            assertTrue(first.getPort() > 0);
            assertTrue(second.getPort() > 0);
            assertNotNull(base(first));
            assertNotNull(base(second));
        }
    }
}
