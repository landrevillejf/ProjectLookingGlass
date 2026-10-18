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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * The production {@link Downloader}: a JDK {@link HttpClient} that follows
 * redirects (Adoptium's binary endpoint 302s to a CDN, Gradle's distribution
 * host does the same) and streams the body to a temp file before atomically
 * moving it into place, so a half-downloaded archive never masquerades as a
 * complete one. No third-party HTTP library - the editor already uses the JDK
 * client elsewhere, and adding one here would pull a dependency into a module
 * that prides itself on {@code java.base}.
 */
final class HttpDownloader implements Downloader {

    /** A distribution or JDK is a few hundred MB over a possibly slow link. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;

    HttpDownloader() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    /** Test seam: an injected client (or a subclass overriding {@link #fetch}). */
    HttpDownloader(HttpClient client) {
        this.client = client;
    }

    @Override
    public void fetch(String url, Path dest) throws IOException {
        Path parent = dest.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path part = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(30))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                response.body().close();
                throw new IOException("Download failed (" + status + ") for " + url);
            }
            try (InputStream body = response.body()) {
                Files.copy(body, part, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted for " + url, ie);
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
