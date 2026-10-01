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
package org.jdesktop.lg3d.apps.videoplayer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The AWT-free video playback seam. Unlike audio, the JDK has no video decoder
 * at all, and the project deliberately ships no third-party codec, so every
 * video is handed to a real external player the backend resolves and drives.
 * This mirrors the honest split the rest of the desktop uses (the Media Writer
 * drives {@code dd}/{@code xorriso}, the Audio Player hands MP3 and streams to
 * {@code mpv}/{@code vlc}, Video Conference hands the session to the browser):
 * a small pure layer decides <em>how</em> to launch a player and builds the
 * exact command line, while the panel performs the thin, guarded process start.
 *
 * <p>Every method here is pure and side-effect free - no process is started, no
 * display is touched - so the whole launch-decision table is unit-testable
 * headless. The process launch lives in {@link VideoPlayerPanel}, guarded like
 * the desktop's other external-command paths.</p>
 */
public final class VideoBackend {

    /**
     * External players this backend knows how to drive, in preference order.
     * {@code vlc} and {@code mpv} lead because they handle the widest range of
     * containers, codecs, streams and discs out of the box.
     */
    public static final List<String> KNOWN_PLAYERS = List.of(
            "vlc", "mpv", "mplayer", "totem", "ffplay", "kodi", "xplayer",
            "celluloid");

    /** URL schemes treated as a network stream (never a local file). */
    private static final Set<String> STREAM_SCHEMES =
            Set.of("http", "https", "rtsp", "rtmp", "mms", "srt", "udp");

    /** URL prefixes that mean "play this optical disc". */
    private static final Set<String> DISC_SCHEMES =
            Set.of("dvd", "bd", "br", "bluray", "vcd", "svcd", "cdda");

    private VideoBackend() {
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
        return hasScheme(location, STREAM_SCHEMES);
    }

    /** True when {@code location} is a disc URL ({@code dvd://}, {@code bd://}). */
    public static boolean isDiscUrl(String location) {
        return hasScheme(location, DISC_SCHEMES);
    }

    /** True when {@code device} looks like an optical disc block device. */
    public static boolean isDiscDevice(String device) {
        if (device == null || device.isBlank()) {
            return false;
        }
        String s = device.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("/dev/sr") || s.startsWith("/dev/dvd")
                || s.startsWith("/dev/cdrom") || s.startsWith("/dev/scd");
    }

    /**
     * Builds a disc URL for {@code device} using the given scheme (defaulting to
     * {@code dvd}). {@code /dev/sr0} becomes {@code dvd:///dev/sr0}.
     */
    public static String discUrl(String scheme, String device) {
        String s = (scheme == null || scheme.isBlank()) ? "dvd" : scheme.trim();
        String d = (device == null) ? "" : device.trim();
        return s + "://" + d;
    }

    private static boolean hasScheme(String location, Set<String> schemes) {
        if (location == null || location.isBlank()) {
            return false;
        }
        String s = location.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon < 0) {
            return false;
        }
        return schemes.contains(s.substring(0, colon));
    }

    /**
     * Builds the command line that plays {@code location} with {@code player}.
     * An unknown player is invoked with just the location.
     *
     * @param player   the executable name (e.g. {@code vlc})
     * @param location the file path, stream URL or disc URL
     * @return the argument list, never null; empty when either input is blank
     */
    public static List<String> playCommand(String player, String location) {
        return playCommand(player, location, false, -1);
    }

    /**
     * Builds the play command with optional full-screen and master volume. The
     * volume is applied only for players that take a percentage flag
     * ({@code mpv}, {@code ffplay}, {@code mplayer}); full-screen is applied for
     * the players that understand a flag for it ({@code vlc}, {@code mpv},
     * {@code mplayer}, {@code ffplay}).
     *
     * @param player        the executable name (e.g. {@code vlc})
     * @param location      the file path, stream URL or disc URL
     * @param fullscreen    true to start in full-screen where supported
     * @param volumePercent the master volume 0..100, or a value outside that
     *                      range to leave the player's default untouched
     * @return the argument list, never null; empty when either input is blank
     */
    public static List<String> playCommand(String player, String location,
                                           boolean fullscreen, int volumePercent) {
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
                return build(p, loc, fullscreen, "--fullscreen", vol,
                        List.of("--volume=" + v), List.of("--really-quiet"));
            case "ffplay":
                return build(p, loc, fullscreen, "-fs", vol,
                        List.of("-volume", Integer.toString(v)),
                        List.of("-autoexit", "-loglevel", "quiet"));
            case "mplayer":
                return build(p, loc, fullscreen, "-fs", vol,
                        List.of("-volume", Integer.toString(v)), List.of());
            case "vlc":
                return build(p, loc, fullscreen, "--fullscreen", vol, List.of(),
                        List.of("--play-and-exit"));
            default:
                // totem, kodi, xplayer, celluloid and anything else: just play.
                return List.of(p, loc);
        }
    }

    /** Assembles {@code player [extra] [fs?] [vol?] location}. */
    private static List<String> build(String player, String location,
                                      boolean fullscreen, String fsFlag,
                                      boolean volume, List<String> volArgs,
                                      List<String> extra) {
        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add(player);
        cmd.addAll(extra);
        if (fullscreen) {
            cmd.add(fsFlag);
        }
        if (volume) {
            cmd.addAll(volArgs);
        }
        cmd.add(location);
        return List.copyOf(cmd);
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
