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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The AWT-free audio playback seam. The JDK's {@code javax.sound.sampled}
 * decodes only its own formats (WAV / AU / AIFF); the headline formats of a
 * music player - MP3, AAC, Ogg and every network stream (internet radio,
 * podcasts) - have no in-JDK decoder, and the project deliberately ships no
 * third-party codec. So this backend follows the same honest split the rest of
 * the desktop uses (the Media Writer drives {@code dd}/{@code xorriso}, Video
 * Conference hands the session to the browser): a small pure layer decides
 * <em>how</em> to play a location and builds the exact command line for a real
 * external player, while the panel keeps a native {@code javax.sound} path for
 * the formats the JDK can decode on its own.
 *
 * <p>Every method here is pure and side-effect free - no process is started, no
 * audio device is touched - so the whole playback-decision table is unit-testable
 * headless. The thin process launch lives in {@link AudioPlayerPanel}, guarded
 * like the desktop's other external-command paths.</p>
 */
public final class AudioBackend {

    /**
     * External players this backend knows how to drive, in preference order.
     * {@code mpv} and {@code mpg123} lead because they play audio without a
     * video surface and exit when the track ends.
     */
    public static final List<String> KNOWN_PLAYERS = List.of(
            "mpv", "mpg123", "ffplay", "mplayer", "cvlc", "vlc", "totem",
            "audacious");

    /** Extensions {@code javax.sound.sampled} can decode without a codec. */
    private static final Set<String> NATIVE_EXT =
            Set.of("wav", "au", "aif", "aiff", "snd");

    /** URL schemes treated as a network stream (never a local file). */
    private static final Set<String> STREAM_SCHEMES =
            Set.of("http", "https", "rtsp", "rtmp", "mms", "icecast");

    private AudioBackend() {
        // no instances
    }

    /**
     * The lower-case file extension of {@code location} (no dot), or an empty
     * string when there is none. A query string on a URL is stripped first.
     */
    public static String extension(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        String s = location.trim();
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String last = (slash >= 0) ? s.substring(slash + 1) : s;
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) {
            return "";
        }
        return last.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** True when {@code location} is a network stream URL. */
    public static boolean isStream(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        String s = location.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon < 0) {
            return false;
        }
        return STREAM_SCHEMES.contains(s.substring(0, colon));
    }

    /**
     * True when the JDK can decode {@code location} on its own - a local file
     * whose extension is one {@code javax.sound.sampled} supports. Streams and
     * codec formats (mp3/aac/ogg/flac) are not natively playable.
     */
    public static boolean isNativelyPlayable(String location) {
        if (location == null || isStream(location)) {
            return false;
        }
        return NATIVE_EXT.contains(extension(location));
    }

    /**
     * Builds the command line that plays {@code location} with {@code player},
     * adding the audio-only / exit-when-done flags each known player expects.
     * An unknown player is invoked with just the location.
     *
     * @param player   the executable name (e.g. {@code mpv})
     * @param location the file path or stream URL
     * @return the argument list, never null; empty when either input is blank
     */
    public static List<String> playCommand(String player, String location) {
        return playCommand(player, location, -1);
    }

    /**
     * Builds the play command with an optional master volume. The volume is
     * applied only for players that take a percentage flag ({@code mpv},
     * {@code ffplay}, {@code mplayer}); others ignore it and manage their own
     * level.
     *
     * @param player        the executable name (e.g. {@code mpv})
     * @param location      the file path or stream URL
     * @param volumePercent the master volume 0..100, or a value outside that
     *                      range to leave the player's default untouched
     * @return the argument list, never null; empty when either input is blank
     */
    public static List<String> playCommand(String player, String location,
                                           int volumePercent) {
        if (player == null || player.isBlank()
                || location == null || location.isBlank()) {
            return List.of();
        }
        String p = player.trim();
        String loc = location.trim();
        boolean vol = volumePercent >= 0 && volumePercent <= 100;
        int v = vol ? Math.max(0, Math.min(100, volumePercent)) : 0;
        switch (p) {
            case "mpv":
                return vol
                        ? List.of(p, "--no-video", "--really-quiet", "--volume=" + v, loc)
                        : List.of(p, "--no-video", "--really-quiet", loc);
            case "ffplay":
                return vol
                        ? List.of(p, "-nodisp", "-autoexit", "-loglevel", "quiet",
                                "-volume", Integer.toString(v), loc)
                        : List.of(p, "-nodisp", "-autoexit", "-loglevel", "quiet", loc);
            case "mplayer":
                return vol
                        ? List.of(p, "-novideo", "-volume", Integer.toString(v), loc)
                        : List.of(p, "-novideo", loc);
            case "cvlc":
                return List.of(p, "--play-and-exit", "--no-video", loc);
            case "vlc":
                return List.of(p, "--play-and-exit", "--intf", "dummy", loc);
            default:
                // mpg123, totem, audacious and anything else: just the location.
                return List.of(p, loc);
        }
    }

    /**
     * The first candidate player {@code available} reports present, in
     * {@link #KNOWN_PLAYERS} order.
     *
     * @param available a probe (typically a PATH lookup) - may be null
     * @return the first available player, or empty when none is
     */
    public static Optional<String> firstAvailablePlayer(Predicate<String> available) {
        return firstAvailablePlayer(available, KNOWN_PLAYERS);
    }

    /**
     * The first of {@code candidates} that {@code available} reports present.
     *
     * @param available  a probe - may be null (then nothing is available)
     * @param candidates the players to consider, in preference order
     * @return the first available candidate, or empty when none is
     */
    public static Optional<String> firstAvailablePlayer(Predicate<String> available,
                                                        List<String> candidates) {
        if (available == null || candidates == null) {
            return Optional.empty();
        }
        for (String player : candidates) {
            if (player != null && !player.isBlank() && available.test(player)) {
                return Optional.of(player);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the player to use: the {@code preferred} one when it is set and
     * available, otherwise the first available known player.
     *
     * @param preferred a user-chosen player (may be null/blank for auto)
     * @param available a probe for whether an executable is on the PATH
     * @return the player to drive, or empty when none is available
     */
    public static Optional<String> resolvePlayer(String preferred,
                                                 Predicate<String> available) {
        if (preferred != null && !preferred.isBlank()
                && available != null && available.test(preferred.trim())) {
            return Optional.of(preferred.trim());
        }
        return firstAvailablePlayer(available);
    }
}
