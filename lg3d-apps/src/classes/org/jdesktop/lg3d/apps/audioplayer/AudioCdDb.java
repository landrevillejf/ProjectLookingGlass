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
package org.jdesktop.lg3d.apps.audioplayer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The AWT-free audio CD database seam: it resolves a disc ID (see
 * {@link MusicBrainzDiscId}) to album / artist / track metadata through the free
 * <a href="https://musicbrainz.org/">MusicBrainz</a> web service and fetches the
 * album cover from the free
 * <a href="https://coverartarchive.org/">Cover Art Archive</a> - both key-less,
 * using only the JDK's {@link HttpClient} (the same stance as
 * {@code org.jdesktop.lg3d.apps.weather.OpenMeteo}).
 *
 * <p>Following the desktop's pure-seam convention, all URL building and JSON
 * parsing live in side-effect-free methods that {@code AudioCdDbTest} exercises
 * headless against canned documents with no network; only {@link #lookup} and
 * {@link #fetchCover} touch the wire, and both are called off the EDT by the rip
 * dialog. Every failure degrades to "no metadata" (an empty {@link Release} /
 * {@code false}) rather than throwing at the caller, so an offline or unmatched
 * rip still completes with blank tags the user can fill in.</p>
 */
public final class AudioCdDb {

    /** MusicBrainz disc-ID lookup endpoint (free, key-less). */
    static final String DISCID_API = "https://musicbrainz.org/ws/2/discid/";

    /** Cover Art Archive front-image endpoint (free, key-less). */
    static final String COVER_API = "https://coverartarchive.org/release/";

    /** The requested cover edge length in pixels. */
    public static final int COVER_SIZE = 500;

    /** A polite, identifying User-Agent, as both services request. */
    static final String USER_AGENT = "lg3d-audioplayer/1.0 (https://github.com/landrevillejf/ProjectLookingGlass)";

    /** Shared client; no connection is made at construction. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AudioCdDb() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    /** One track's metadata as returned by the database. */
    public static final class TrackInfo {
        /** The 1-based track number. */
        public final int number;
        /** The track title (never null; may be blank). */
        public final String title;
        /** The track length in milliseconds, or -1 when unknown. */
        public final long lengthMillis;

        TrackInfo(int number, String title, long lengthMillis) {
            this.number = number;
            this.title = (title == null) ? "" : title;
            this.lengthMillis = lengthMillis;
        }
    }

    /** A resolved release: its MBID, album artist, title and track list. */
    public static final class Release {
        /** The MusicBrainz release MBID (never null; blank when unmatched). */
        public final String mbid;
        /** The album artist (never null; blank when unknown). */
        public final String artist;
        /** The album title (never null; blank when unknown). */
        public final String title;
        /** The ordered track list (never null; empty when unknown). */
        public final List<TrackInfo> tracks;

        Release(String mbid, String artist, String title, List<TrackInfo> tracks) {
            this.mbid = (mbid == null) ? "" : mbid;
            this.artist = (artist == null) ? "" : artist;
            this.title = (title == null) ? "" : title;
            this.tracks = (tracks == null) ? List.of() : List.copyOf(tracks);
        }

        /** @return true when nothing was resolved (no MBID and no title). */
        public boolean isEmpty() {
            return mbid.isBlank() && title.isBlank() && tracks.isEmpty();
        }
    }

    /** An empty result used whenever a lookup fails or matches nothing. */
    public static final Release NO_MATCH = new Release("", "", "", List.of());

    // ------------------------------------------------------------------
    // URL builders (pure)
    // ------------------------------------------------------------------

    /** Builds the MusicBrainz disc-ID lookup URL (JSON, with recordings). */
    public static String discidLookupUrl(String discid) {
        return DISCID_API + enc(discid) + "?inc=recordings&fmt=json";
    }

    /** Builds the Cover Art Archive front-image URL for a release MBID. */
    public static String coverUrl(String mbid) {
        return coverUrl(mbid, COVER_SIZE);
    }

    /** Builds the Cover Art Archive front-image URL at a given edge size. */
    public static String coverUrl(String mbid, int size) {
        int s = (size <= 0) ? COVER_SIZE : size;
        return COVER_API + enc(mbid) + "/front-" + s;
    }

    private static String enc(String s) {
        return (s == null) ? "" : URLEncoder.encode(s.trim(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Parsing (AWT-free, unit-tested)
    // ------------------------------------------------------------------

    /**
     * Parses a MusicBrainz disc-ID JSON document into the best {@link Release}.
     * The first release in the response is used; missing fields become blanks so
     * a partial document still yields usable metadata.
     *
     * @param body the JSON response body
     * @return the parsed release, or {@link #NO_MATCH} when it holds no releases
     */
    public static Release parseRelease(String body) {
        if (body == null || body.isBlank()) {
            return NO_MATCH;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode releases = root.path("releases");
            if (!releases.isArray() || releases.isEmpty()) {
                return NO_MATCH;
            }
            JsonNode rel = releases.get(0);
            String mbid = text(rel, "id");
            String title = text(rel, "title");
            String artist = artistOf(rel);
            List<TrackInfo> tracks = tracksOf(rel);
            return new Release(mbid, artist, title, tracks);
        } catch (IOException | RuntimeException e) {
            return NO_MATCH;
        }
    }

    private static String artistOf(JsonNode rel) {
        JsonNode credit = rel.path("artist-credit");
        if (credit.isArray() && !credit.isEmpty()) {
            JsonNode first = credit.get(0);
            String name = text(first, "name");
            if (!name.isBlank()) {
                return name;
            }
            return text(first.path("artist"), "name");
        }
        // Some payloads nest the artist directly.
        return text(rel.path("artist"), "name");
    }

    private static List<TrackInfo> tracksOf(JsonNode rel) {
        List<TrackInfo> out = new ArrayList<>();
        JsonNode media = rel.path("media");
        if (!media.isArray()) {
            return out;
        }
        int fallback = 1;
        for (JsonNode medium : media) {
            JsonNode tracks = medium.path("tracks");
            if (!tracks.isArray()) {
                continue;
            }
            for (JsonNode t : tracks) {
                int number = parseInt(text(t, "number"), 0);
                if (number <= 0) {
                    JsonNode pos = t.path("position");
                    number = pos.isNumber() ? pos.asInt() : fallback;
                }
                String title = text(t, "title");
                if (title.isBlank()) {
                    title = text(t.path("recording"), "title");
                }
                long len = t.path("length").isNumber() ? t.path("length").asLong() : -1L;
                out.add(new TrackInfo(number, title, len));
                fallback++;
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isValueNode() ? v.asText() : "";
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Network (blocking; call off the EDT)
    // ------------------------------------------------------------------

    /**
     * Resolves a disc ID to release metadata. Never throws: a transport error, a
     * non-2xx status, a 404 (no such disc) or a parse failure all yield
     * {@link #NO_MATCH}.
     *
     * @param discid the MusicBrainz disc ID
     * @return the resolved release, or {@link #NO_MATCH}
     */
    public static Release lookup(String discid) {
        if (discid == null || discid.isBlank()) {
            return NO_MATCH;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(discidLookupUrl(discid)))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp =
                    HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return NO_MATCH;
            }
            return parseRelease(resp.body());
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return NO_MATCH;
        }
    }

    /**
     * Downloads the front cover for a release MBID into {@code dest} (creating
     * parent directories). Never throws: any failure leaves no file and returns
     * {@code false}.
     *
     * @param mbid the MusicBrainz release MBID
     * @param dest the destination image path (e.g. {@code .../covers/<mbid>.jpg})
     * @return true when a cover image was written to {@code dest}
     */
    public static boolean fetchCover(String mbid, Path dest) {
        if (mbid == null || mbid.isBlank() || dest == null) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(coverUrl(mbid)))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> resp =
                    HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                return false;
            }
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            try (InputStream in = resp.body()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return Files.isRegularFile(dest) && Files.size(dest) > 0;
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
