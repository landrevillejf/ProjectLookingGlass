/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.Control;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.Port;

/**
 * Master output volume for the 2D taskbar indicator, the Control Center Sound
 * panel and the system-indicators widget.
 *
 * <p>The clamping, dB↔percentage maths, CLI output parsing, command building
 * and formatting are pure ({@link #clamp}, {@link #percentFromDb},
 * {@link #dbFromPercent}, {@link #parseWpctl}, {@link #parsePactlVolume},
 * {@link #parsePactlMute}, {@link #parseAmixer}, the {@code *Command} builders,
 * {@link #glyph}, {@link #label}) and unit-tested; the read/write methods are
 * thin probes over the host's audio tooling.</p>
 *
 * <p>Backends are tried most-authoritative first, because on a PipeWire or
 * PulseAudio desktop the {@code javax.sound.sampled} master port is either
 * absent or - worse - reports a stale ALSA view that does not track the real
 * sink volume:</p>
 * <ol>
 *   <li><b>PipeWire</b> via {@code wpctl} (Fedora 31+, modern distros);</li>
 *   <li><b>PulseAudio</b> via {@code pactl};</li>
 *   <li><b>plain ALSA</b> via {@code amixer} - the Linux From Scratch / minimal
 *       case, where there is no sound server and {@code alsa-utils} drives the
 *       card directly;</li>
 *   <li><b>{@code javax.sound.sampled}</b> master port, as a last resort for
 *       hosts with none of the above tools (e.g. macOS, Windows, or a bare JDK).</li>
 * </ol>
 *
 * <p>Every probe returns {@link Optional#empty()} / no-ops when no master
 * control exists (headless CI, no sound card, no tool installed), so the
 * indicator hides itself and the panel shows its "no audio device" note instead
 * of failing.</p>
 */
public final class VolumeStatus {

    /** Probe timeout for each external audio tool, in seconds. */
    private static final long TIMEOUT_SECONDS = 5L;

    /**
     * ALSA simple controls tried in order for the plain-ALSA ({@code amixer})
     * backend. Most cards expose {@code Master}; a few only expose {@code PCM}.
     */
    private static final String[] ALSA_CONTROLS = {"Master", "PCM"};

    /** A {@code wpctl status} sink line: {@code 48. Description [vol: 0.50]}. */
    private static final Pattern WPCTL_SINK_VOL =
            Pattern.compile("(\\d+)\\.\\s+(.*?)\\s*\\[vol:\\s*([0-9.]+)\\]");

    /** A {@code wpctl status} sink line with no volume reported. */
    private static final Pattern WPCTL_SINK_PLAIN =
            Pattern.compile("(\\d+)\\.\\s+([^\\[]+)");

    /** An {@code aplay -l} playback line: {@code card 0: ID [Name], device 3: ID [Name]}. */
    private static final Pattern ALSA_CARD =
            Pattern.compile("card (\\d+): \\S+ \\[(.*?)\\], device (\\d+): .*? \\[(.*?)\\]");

    private VolumeStatus() {
        // no instances
    }

    /** A volume reading: master percentage (0-100) and mute state. */
    public record Level(int percent, boolean muted) {
    }

    /**
     * An audio output device (sink or card): its backend id (a {@code wpctl}
     * numeric id, a {@code pactl} sink name, or an ALSA card number), a human
     * description, whether it is the current default, its volume percentage
     * (0-100, or -1 when the backend does not report one) and its mute state.
     */
    public record Device(String id, String description, boolean isDefault, int percent,
            boolean muted) {
    }

    /** Clamps a percentage into 0-100. */
    static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    /**
     * Converts a master-gain value in dB (between the control's min and max) to
     * a 0-100 percentage, linear in dB. Pure so it can be unit-tested without a
     * sound card.
     */
    static int percentFromDb(float value, float min, float max) {
        if (max <= min) {
            return 0;
        }
        float clamped = Math.max(min, Math.min(max, value));
        return Math.round((clamped - min) / (max - min) * 100f);
    }

    /** The inverse of {@link #percentFromDb}: a 0-100 percentage to dB. */
    static float dbFromPercent(int percent, float min, float max) {
        float clamped = clamp(percent) / 100f;
        return min + clamped * (max - min);
    }

    // ------------------------------------------------------------------
    // Pure CLI output parsing (unit-tested; no tool is invoked).
    // ------------------------------------------------------------------

    /**
     * Parses {@code wpctl get-volume @DEFAULT_AUDIO_SINK@} output of the form
     * {@code "Volume: 0.42"} or {@code "Volume: 1.40 [MUTED]"}. The value is a
     * linear 0..N scale (PipeWire allows over-100% amplification), so it is
     * scaled by 100 and clamped; the {@code [MUTED]} marker sets the mute flag.
     */
    static Optional<Level> parseWpctl(String out) {
        if (out == null) {
            return Optional.empty();
        }
        int idx = out.indexOf("Volume:");
        if (idx < 0) {
            return Optional.empty();
        }
        String rest = out.substring(idx + "Volume:".length()).trim();
        int space = rest.indexOf(' ');
        String token = (space < 0) ? rest : rest.substring(0, space);
        try {
            float scale = Float.parseFloat(token);
            int percent = clamp(Math.round(scale * 100f));
            boolean muted = out.contains("[MUTED]");
            return Optional.of(new Level(percent, muted));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Parses {@code pactl get-sink-volume @DEFAULT_SINK@} output, e.g.
     * {@code "Volume: front-left: 65536 / 100% / 0.00 dB, front-right: ..."},
     * returning the first percentage (which may exceed 100) or {@code null}
     * when no {@code NN%} token is present.
     */
    static Integer parsePactlVolume(String out) {
        if (out == null) {
            return null;
        }
        int pct = out.indexOf('%');
        if (pct < 0) {
            return null;
        }
        int i = pct - 1;
        while (i >= 0 && Character.isDigit(out.charAt(i))) {
            i--;
        }
        String num = out.substring(i + 1, pct);
        if (num.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parses {@code pactl get-sink-mute} output ({@code "Mute: yes"} / {@code "Mute: no"}). */
    static boolean parsePactlMute(String out) {
        return out != null && out.toLowerCase().contains("mute: yes");
    }

    /**
     * Parses {@code amixer sget <control>} output, taking the first bracketed
     * percentage ({@code [100%]}) and treating {@code [off]} as muted, e.g.
     * {@code "Front Left: Playback 65536 [100%] [on]"}. Returns empty when no
     * percentage is present.
     */
    static Optional<Level> parseAmixer(String out) {
        Integer percent = bracketPercent(out);
        if (percent == null) {
            return Optional.empty();
        }
        boolean muted = out != null && out.contains("[off]");
        return Optional.of(new Level(clamp(percent), muted));
    }

    /** The integer inside the first {@code [NN%]} token, or {@code null}. */
    private static Integer bracketPercent(String out) {
        if (out == null) {
            return null;
        }
        int i = 0;
        while (i < out.length()) {
            int open = out.indexOf('[', i);
            if (open < 0) {
                break;
            }
            int j = open + 1;
            while (j < out.length() && Character.isDigit(out.charAt(j))) {
                j++;
            }
            if (j > open + 1 && j < out.length() && out.charAt(j) == '%') {
                try {
                    return Integer.valueOf(out.substring(open + 1, j));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            i = open + 1;
        }
        return null;
    }

    /**
     * Parses the {@code Sinks:} section of {@code wpctl status} into devices,
     * e.g. {@code "│  *   48. Ryzen HD Audio ... [vol: 0.50]"} where {@code *}
     * marks the default sink. Only lines between {@code Sinks:} and the next
     * section header are considered; {@code wpctl status} reports no per-sink
     * mute, so {@code muted} is left false (the master mute still reads via
     * {@link #read()}).
     */
    static List<Device> parseWpctlSinks(String status) {
        List<Device> out = new ArrayList<>();
        if (status == null) {
            return out;
        }
        boolean inSinks = false;
        for (String raw : status.split("\n")) {
            String t = raw.trim();
            if (t.endsWith("Sinks:")) {
                inSinks = true;
                continue;
            }
            if (t.endsWith("Sources:") || t.endsWith("Filters:") || t.endsWith("Streams:")
                    || t.endsWith("Devices:") || t.endsWith("Endpoints:")) {
                inSinks = false;
                continue;
            }
            if (!inSinks) {
                continue;
            }
            boolean isDefault = raw.indexOf('*') >= 0;
            Matcher vol = WPCTL_SINK_VOL.matcher(raw);
            if (vol.find()) {
                int percent = clamp(Math.round(parseFloat(vol.group(3), 0f) * 100f));
                out.add(new Device(vol.group(1), vol.group(2).trim(), isDefault, percent, false));
                continue;
            }
            Matcher plain = WPCTL_SINK_PLAIN.matcher(raw);
            if (plain.find()) {
                out.add(new Device(plain.group(1), plain.group(2).trim(), isDefault, -1, false));
            }
        }
        return out;
    }

    /**
     * Parses {@code pactl list sinks} blocks into devices, marking the sink
     * whose {@code Name:} equals {@code defaultName} (from
     * {@code pactl get-default-sink}) as the default.
     */
    static List<Device> parsePactlSinks(String listSinks, String defaultName) {
        List<Device> out = new ArrayList<>();
        if (listSinks == null) {
            return out;
        }
        String def = (defaultName == null) ? "" : defaultName.trim();
        for (String block : listSinks.split("(?m)^Sink #")) {
            String name = pactlField(block, "Name:");
            if (name == null || name.isEmpty()) {
                continue;
            }
            String desc = pactlField(block, "Description:");
            String mute = pactlField(block, "Mute:");
            Integer vol = parsePactlVolume(pactlField(block, "Volume:"));
            int percent = (vol == null) ? -1 : clamp(vol);
            boolean muted = mute != null && mute.equalsIgnoreCase("yes");
            boolean isDefault = !def.isEmpty() && def.equals(name);
            out.add(new Device(name, (desc == null || desc.isEmpty()) ? name : desc,
                    isDefault, percent, muted));
        }
        return out;
    }

    /**
     * Parses {@code aplay -l} playback hardware lines into devices. Plain ALSA
     * has no runtime default-sink switch, so no device is marked default and the
     * id is the card number (used for {@code amixer -c <card>}).
     */
    static List<Device> parseAlsaCards(String aplayL) {
        List<Device> out = new ArrayList<>();
        if (aplayL == null) {
            return out;
        }
        Matcher m = ALSA_CARD.matcher(aplayL);
        while (m.find()) {
            String card = m.group(1);
            String desc = "card " + card + ": " + m.group(2) + " - " + m.group(4);
            out.add(new Device(card, desc, false, -1, false));
        }
        return out;
    }

    /** The value of a {@code Key:} line inside a {@code pactl} block, or null. */
    private static String pactlField(String block, String key) {
        for (String line : block.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key)) {
                return t.substring(key.length()).trim();
            }
        }
        return null;
    }

    /** Parses a float, returning {@code fallback} on any failure. */
    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** One-line list text for a device: description, volume and state markers. */
    public static String deviceLabel(Device device) {
        if (device == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(device.description());
        if (device.percent() >= 0) {
            sb.append("  ").append(device.percent()).append('%');
        }
        if (device.muted()) {
            sb.append("  [muted]");
        }
        if (device.isDefault()) {
            sb.append("  [default]");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Pure command builders (unit-tested; no tool is invoked).
    // ------------------------------------------------------------------

    /** {@code wpctl get-volume @DEFAULT_AUDIO_SINK@}. */
    static String[] wpctlGetVolumeCommand() {
        return new String[] {"wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"};
    }

    /** {@code pactl get-sink-volume @DEFAULT_SINK@}. */
    static String[] pactlGetVolumeCommand() {
        return new String[] {"pactl", "get-sink-volume", "@DEFAULT_SINK@"};
    }

    /** {@code pactl get-sink-mute @DEFAULT_SINK@}. */
    static String[] pactlGetMuteCommand() {
        return new String[] {"pactl", "get-sink-mute", "@DEFAULT_SINK@"};
    }

    /** {@code amixer sget <control>}. */
    static String[] amixerGetCommand(String control) {
        return new String[] {"amixer", "sget", control};
    }

    /** {@code wpctl set-volume @DEFAULT_AUDIO_SINK@ N%}. */
    static String[] wpctlSetVolumeCommand(int percent) {
        return new String[] {"wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", clamp(percent) + "%"};
    }

    /** {@code pactl set-sink-volume @DEFAULT_SINK@ N%}. */
    static String[] pactlSetVolumeCommand(int percent) {
        return new String[] {"pactl", "set-sink-volume", "@DEFAULT_SINK@", clamp(percent) + "%"};
    }

    /** {@code amixer sset <control> N%}. */
    static String[] amixerSetVolumeCommand(String control, int percent) {
        return new String[] {"amixer", "sset", control, clamp(percent) + "%"};
    }

    /** {@code wpctl set-mute @DEFAULT_AUDIO_SINK@ 1|0}. */
    static String[] wpctlSetMuteCommand(boolean muted) {
        return new String[] {"wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", muted ? "1" : "0"};
    }

    /** {@code pactl set-sink-mute @DEFAULT_SINK@ 1|0}. */
    static String[] pactlSetMuteCommand(boolean muted) {
        return new String[] {"pactl", "set-sink-mute", "@DEFAULT_SINK@", muted ? "1" : "0"};
    }

    /** {@code amixer sset <control> mute|unmute}. */
    static String[] amixerSetMuteCommand(String control, boolean muted) {
        return new String[] {"amixer", "sset", control, muted ? "mute" : "unmute"};
    }

    /** {@code wpctl status}. */
    static String[] wpctlStatusCommand() {
        return new String[] {"wpctl", "status"};
    }

    /** {@code pactl list sinks}. */
    static String[] pactlListSinksCommand() {
        return new String[] {"pactl", "list", "sinks"};
    }

    /** {@code pactl get-default-sink}. */
    static String[] pactlGetDefaultSinkCommand() {
        return new String[] {"pactl", "get-default-sink"};
    }

    /** {@code aplay -l}. */
    static String[] aplayListCommand() {
        return new String[] {"aplay", "-l"};
    }

    /** {@code wpctl set-default <id>}. */
    static String[] wpctlSetDefaultCommand(String id) {
        return new String[] {"wpctl", "set-default", id};
    }

    /** {@code pactl set-default-sink <name>}. */
    static String[] pactlSetDefaultCommand(String name) {
        return new String[] {"pactl", "set-default-sink", name};
    }

    /** {@code wpctl set-volume <id> N%}. */
    static String[] wpctlSetDeviceVolumeCommand(String id, int percent) {
        return new String[] {"wpctl", "set-volume", id, clamp(percent) + "%"};
    }

    /** {@code pactl set-sink-volume <name> N%}. */
    static String[] pactlSetSinkVolumeCommand(String name, int percent) {
        return new String[] {"pactl", "set-sink-volume", name, clamp(percent) + "%"};
    }

    /** {@code wpctl set-mute <id> 1|0}. */
    static String[] wpctlSetDeviceMuteCommand(String id, boolean muted) {
        return new String[] {"wpctl", "set-mute", id, muted ? "1" : "0"};
    }

    /** {@code pactl set-sink-mute <name> 1|0}. */
    static String[] pactlSetSinkMuteCommand(String name, boolean muted) {
        return new String[] {"pactl", "set-sink-mute", name, muted ? "1" : "0"};
    }

    /** {@code amixer -c <card> sset <control> N%}. */
    static String[] amixerSetCardVolumeCommand(String card, String control, int percent) {
        return new String[] {"amixer", "-c", card, "sset", control, clamp(percent) + "%"};
    }

    /** {@code amixer -c <card> sset <control> mute|unmute}. */
    static String[] amixerSetCardMuteCommand(String card, String control, boolean muted) {
        return new String[] {"amixer", "-c", card, "sset", control, muted ? "mute" : "unmute"};
    }

    // ------------------------------------------------------------------
    // Public probes.
    // ------------------------------------------------------------------

    /** Reads the master volume, or empty when no master control is available. */
    public static Optional<Level> read() {
        Optional<Level> cli = readNative();
        if (cli.isPresent()) {
            return cli;
        }
        return readJavaSound();
    }

    /** Sets the master volume (0-100); a no-op when no master control exists. */
    public static void setVolume(int percent) {
        if (setVolumeNative(percent)) {
            return;
        }
        setVolumeJavaSound(percent);
    }

    /** Sets the master mute; a no-op when no master control exists. */
    public static void setMuted(boolean muted) {
        if (setMutedNative(muted)) {
            return;
        }
        setMutedJavaSound(muted);
    }

    /**
     * The output devices (sinks/cards), most-authoritative backend first
     * (PipeWire {@code wpctl status} -> PulseAudio {@code pactl list sinks} ->
     * plain ALSA {@code aplay -l}); empty when none can be enumerated.
     */
    public static List<Device> devices() {
        String wp = exec(wpctlStatusCommand());
        if (wp != null) {
            List<Device> sinks = parseWpctlSinks(wp);
            if (!sinks.isEmpty()) {
                return sinks;
            }
        }
        String ps = exec(pactlListSinksCommand());
        if (ps != null) {
            List<Device> sinks = parsePactlSinks(ps, exec(pactlGetDefaultSinkCommand()));
            if (!sinks.isEmpty()) {
                return sinks;
            }
        }
        String ap = exec(aplayListCommand());
        if (ap != null) {
            List<Device> cards = parseAlsaCards(ap);
            if (!cards.isEmpty()) {
                return cards;
            }
        }
        return List.of();
    }

    /**
     * Makes {@code id} the default output device; false when the host has no
     * sound server that supports switching (plain ALSA has no runtime default).
     */
    public static boolean setDefault(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        return run(wpctlSetDefaultCommand(id)) || run(pactlSetDefaultCommand(id));
    }

    /**
     * Sets a specific device's volume (0-100). A null/empty {@code id} falls
     * back to the master volume; on plain ALSA the {@code id} is a card number.
     */
    public static void setVolume(String id, int percent) {
        if (id == null || id.isEmpty()) {
            setVolume(percent);
            return;
        }
        int value = clamp(percent);
        if (run(wpctlSetDeviceVolumeCommand(id, value))
                || run(pactlSetSinkVolumeCommand(id, value))) {
            return;
        }
        for (String control : ALSA_CONTROLS) {
            if (run(amixerSetCardVolumeCommand(id, control, value))) {
                return;
            }
        }
    }

    /**
     * Sets a specific device's mute. A null/empty {@code id} falls back to the
     * master mute; on plain ALSA the {@code id} is a card number.
     */
    public static void setMuted(String id, boolean muted) {
        if (id == null || id.isEmpty()) {
            setMuted(muted);
            return;
        }
        if (run(wpctlSetDeviceMuteCommand(id, muted))
                || run(pactlSetSinkMuteCommand(id, muted))) {
            return;
        }
        for (String control : ALSA_CONTROLS) {
            if (run(amixerSetCardMuteCommand(id, control, muted))) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Native CLI backends (PipeWire -> PulseAudio -> ALSA).
    // ------------------------------------------------------------------

    private static Optional<Level> readNative() {
        String wp = exec(wpctlGetVolumeCommand());
        if (wp != null) {
            Optional<Level> level = parseWpctl(wp);
            if (level.isPresent()) {
                return level;
            }
        }
        String pv = exec(pactlGetVolumeCommand());
        if (pv != null) {
            Integer percent = parsePactlVolume(pv);
            if (percent != null) {
                boolean muted = parsePactlMute(exec(pactlGetMuteCommand()));
                return Optional.of(new Level(clamp(percent), muted));
            }
        }
        for (String control : ALSA_CONTROLS) {
            String am = exec(amixerGetCommand(control));
            if (am != null) {
                Optional<Level> level = parseAmixer(am);
                if (level.isPresent()) {
                    return level;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean setVolumeNative(int percent) {
        int value = clamp(percent);
        if (run(wpctlSetVolumeCommand(value)) || run(pactlSetVolumeCommand(value))) {
            return true;
        }
        for (String control : ALSA_CONTROLS) {
            if (run(amixerSetVolumeCommand(control, value))) {
                return true;
            }
        }
        return false;
    }

    private static boolean setMutedNative(boolean muted) {
        if (run(wpctlSetMuteCommand(muted)) || run(pactlSetMuteCommand(muted))) {
            return true;
        }
        for (String control : ALSA_CONTROLS) {
            if (run(amixerSetMuteCommand(control, muted))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // javax.sound.sampled fallback. The master OUTPUT gain lives on a target
    // (playback) port; the previous isSource() filter only matched capture
    // ports, so it never found the master volume even where one existed.
    // ------------------------------------------------------------------

    private static Optional<Level> readJavaSound() {
        try {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                    if (!(lineInfo instanceof Port.Info portInfo) || portInfo.isSource()) {
                        continue;
                    }
                    try (Line line = openLine(portInfo)) {
                        if (line == null) {
                            continue;
                        }
                        FloatControl gain = floatControl(line, FloatControl.Type.MASTER_GAIN);
                        BooleanControl mute = booleanControl(line, BooleanControl.Type.MUTE);
                        if (gain == null && mute == null) {
                            continue;
                        }
                        int percent = (gain == null) ? 0
                                : percentFromDb(gain.getValue(), gain.getMinimum(), gain.getMaximum());
                        boolean muted = mute != null && mute.getValue();
                        return Optional.of(new Level(percent, muted));
                    }
                }
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static void setVolumeJavaSound(int percent) {
        withControl(FloatControl.Type.MASTER_GAIN, line -> {
            FloatControl gain = (FloatControl) ((Port) line).getControl(FloatControl.Type.MASTER_GAIN);
            gain.setValue(dbFromPercent(percent, gain.getMinimum(), gain.getMaximum()));
        });
    }

    private static void setMutedJavaSound(boolean muted) {
        withControl(BooleanControl.Type.MUTE, line -> {
            BooleanControl mute = (BooleanControl) ((Port) line).getControl(BooleanControl.Type.MUTE);
            mute.setValue(muted);
        });
    }

    /** An action to run against an open master port line that supports {@code type}. */
    private interface LineAction {
        void accept(Line line);
    }

    /**
     * Opens the first target port supporting {@code type} and runs
     * {@code action} against it, best-effort; a host with no such control is a
     * silent no-op.
     */
    private static void withControl(Control.Type type, LineAction action) {
        try {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                    if (!(lineInfo instanceof Port.Info portInfo) || portInfo.isSource()) {
                        continue;
                    }
                    try (Line line = openLine(portInfo)) {
                        if (line instanceof Port port && port.isControlSupported(type)) {
                            action.accept(line);
                            return;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // best effort: a machine with no master control just ignores this
        }
    }

    private static Line openLine(Port.Info info) {
        try {
            Line line = AudioSystem.getLine(info);
            line.open();
            return line;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return null;
        }
    }

    private static FloatControl floatControl(Line line, Control.Type type) {
        if (line instanceof Port port && port.isControlSupported(type)) {
            Control control = port.getControl(type);
            if (control instanceof FloatControl fc) {
                return fc;
            }
        }
        return null;
    }

    private static BooleanControl booleanControl(Line line, Control.Type type) {
        if (line instanceof Port port && port.isControlSupported(type)) {
            Control control = port.getControl(type);
            if (control instanceof BooleanControl bc) {
                return bc;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // External-command seam (mirrors PrinterStatus).
    // ------------------------------------------------------------------

    /** Runs a command, returning true only on a zero exit within the timeout. */
    static boolean run(String[] cmd) {
        return exec(cmd) != null;
    }

    /**
     * Runs a command and returns its stdout on a zero exit, or {@code null} on
     * a non-zero exit, timeout, or any launch error (missing binary, etc.).
     */
    static String exec(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return (p.exitValue() == 0) ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Formatting.
    // ------------------------------------------------------------------

    /** Compact taskbar text: {@code "Vol x"} muted, {@code "Vol 42%"} otherwise, {@code "Vol --"} unknown. */
    public static String glyph(Level level) {
        if (level == null) {
            return "Vol --";
        }
        if (level.muted()) {
            return "Vol x";
        }
        return "Vol " + level.percent() + "%";
    }

    /** Detailed tooltip text. */
    public static String label(Level level) {
        if (level == null) {
            return "Volume: unavailable";
        }
        if (level.muted()) {
            return "Volume: muted";
        }
        return "Volume: " + level.percent() + "%";
    }
}
