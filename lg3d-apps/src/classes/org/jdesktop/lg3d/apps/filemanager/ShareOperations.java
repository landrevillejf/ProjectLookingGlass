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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Read-only network sharing for the file manager: serves a folder over HTTP so
 * other machines on the LAN can browse and download it, using the JDK's
 * built-in {@code com.sun.net.httpserver}. No external daemon (Samba/NFS), no
 * root and no third-party dependency, so it works on any distro out of the box.
 *
 * <p>A {@link Share} binds to {@code 0.0.0.0} on the requested port (or an
 * ephemeral one when the port is taken) and lives until {@link Share#stop()}.
 * Requests are hardened against path traversal: the request path is resolved and
 * normalised against the shared root and rejected with {@code 403} if it escapes
 * it, so only the chosen folder is ever exposed.</p>
 *
 * <p>Deliberately free of Swing/AWT imports so a share can be started, fetched
 * and stopped inside a headless JUnit test.</p>
 */
public final class ShareOperations {

    /** The port {@link #start(Path)} tries first before falling back to any. */
    public static final int DEFAULT_PORT = 8000;

    private ShareOperations() {
        // no instances
    }

    /** A running HTTP folder share. */
    public static final class Share implements AutoCloseable {
        private final HttpServer server;
        private final Path root;
        private final int port;

        Share(HttpServer server, Path root, int port) {
            this.server = server;
            this.root = root;
            this.port = port;
        }

        /** The shared folder. */
        public Path getRoot() {
            return root;
        }

        /** The bound port (useful when an ephemeral port was chosen). */
        public int getPort() {
            return port;
        }

        /** The LAN URL to hand to the user, e.g. {@code http://192.168.1.5:8000/}. */
        public String getUrl() {
            return "http://" + localAddress() + ":" + port + "/";
        }

        /** Stops the server and releases the port. */
        public void stop() {
            server.stop(0);
        }

        @Override
        public void close() {
            stop();
        }
    }

    /**
     * Starts a share of {@code dir} on {@link #DEFAULT_PORT}, falling back to an
     * ephemeral port when that one is already in use.
     *
     * @throws IOException if the folder cannot be served or no port can be bound
     */
    public static Share start(Path dir) throws IOException {
        try {
            return start(dir, DEFAULT_PORT);
        } catch (IOException e) {
            // Port taken (or privileged); retry on an ephemeral port.
            return start(dir, 0);
        }
    }

    /**
     * Starts a share of {@code dir} on the given port ({@code <= 0} selects an
     * ephemeral port).
     *
     * @throws IOException if the folder is unreadable or the port cannot be bound
     */
    public static Share start(Path dir, int port) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IOException("Not a readable directory: " + dir);
        }
        Path root = dir.toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new FileHandler(root));
        server.setExecutor(null); // default: a small internal thread pool
        server.start();
        return new Share(server, root, server.getAddress().getPort());
    }

    /**
     * Best-effort LAN IPv4 address of this machine: the first site-local,
     * non-loopback address found, else the local host address, else loopback.
     */
    public static String localAddress() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> list = (nics == null)
                    ? Collections.emptyList() : Collections.list(nics);
            for (NetworkInterface nic : list) {
                if (nic.isLoopback() || !nic.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (SocketException | RuntimeException e) {
            // fall through to getLocalHost
        }
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local != null && !local.isLoopbackAddress()) {
                return local.getHostAddress();
            }
        } catch (IOException | RuntimeException e) {
            // fall through to loopback
        }
        return "127.0.0.1";
    }

    // ------------------------------------------------------------------

    /** Serves directory listings and file downloads from a fixed root. */
    static final class FileHandler implements HttpHandler {
        private final Path root;

        FileHandler(Path root) {
            this.root = root;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                // URI.getPath() is already percent-decoded; do NOT decode again
                // (a second pass would corrupt names, turning '+' into a space).
                String reqPath = ex.getRequestURI().getPath();
                if (reqPath == null) {
                    reqPath = "/";
                }
                Path target = root.resolve("." + reqPath).normalize();
                // Path-traversal guard: only serve inside the shared root.
                if (!target.startsWith(root)) {
                    sendStatus(ex, 403, "Forbidden");
                    return;
                }
                if (Files.isDirectory(target)) {
                    sendListing(ex, target);
                } else if (Files.isRegularFile(target)) {
                    sendFile(ex, target);
                } else {
                    sendStatus(ex, 404, "Not found");
                }
            } catch (IOException | RuntimeException e) {
                sendStatus(ex, 500, "Server error");
            } finally {
                ex.close();
            }
        }

        private void sendFile(HttpExchange ex, Path file) throws IOException {
            long len = Files.size(file);
            String type = Files.probeContentType(file);
            ex.getResponseHeaders().set("Content-Type",
                    (type == null) ? "application/octet-stream" : type);
            ex.sendResponseHeaders(200, len);
            try (OutputStream os = ex.getResponseBody()) {
                Files.copy(file, os);
            }
        }

        private void sendListing(HttpExchange ex, Path dir) throws IOException {
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    names.add(p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""));
                }
            } catch (IOException | RuntimeException e) {
                // An unreadable directory lists as empty.
            }
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

            String title = (dir.equals(root))
                    ? String.valueOf(root.getFileName()) + " (shared)"
                    : root.relativize(dir).toString();
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
                .append("<title>").append(escHtml(title)).append("</title>")
                .append("<style>body{font-family:sans-serif;margin:24px}"
                        + "a{text-decoration:none;color:#1a5fb4}"
                        + "li{margin:2px 0}</style></head><body>");
            html.append("<h2>").append(escHtml(title)).append("</h2><ul>");
            if (!dir.equals(root)) {
                html.append("<li><a href=\"../\">../</a></li>");
            }
            for (String n : names) {
                boolean isDir = n.endsWith("/");
                String base = isDir ? n.substring(0, n.length() - 1) : n;
                String href = encSegment(base) + (isDir ? "/" : "");
                html.append("<li><a href=\"").append(href).append("\">")
                    .append(escHtml(n)).append("</a></li>");
            }
            html.append("</ul></body></html>");
            byte[] body = html.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }

        private void sendStatus(HttpExchange ex, int code, String msg) throws IOException {
            byte[] body = msg.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }

        private static String escHtml(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '&': sb.append("&amp;"); break;
                    case '<': sb.append("&lt;"); break;
                    case '>': sb.append("&gt;"); break;
                    case '"': sb.append("&quot;"); break;
                    default:  sb.append(c);
                }
            }
            return sb.toString();
        }

        /** Percent-encodes one path segment (RFC 3986 unreserved set is kept). */
        private static String encSegment(String s) {
            StringBuilder sb = new StringBuilder(s.length() + 8);
            for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
                int c = b & 0xFF;
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9')
                        || c == '-' || c == '_' || c == '.' || c == '~') {
                    sb.append((char) c);
                } else {
                    sb.append('%');
                    sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                    sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
                }
            }
            return sb.toString();
        }
    }
}
