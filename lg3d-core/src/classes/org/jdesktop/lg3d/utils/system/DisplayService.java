/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Queries and configures the display through {@code xrandr}: connected outputs,
 * the modes and refresh rates each supports, the current configuration, and
 * applying a new one.
 *
 * <p>The control center's Display panel uses {@link #query()} to populate its
 * combos, {@link #capture()} to snapshot the current configuration before a
 * change, {@link #apply(List)} to commit a new one, and {@link #restore(List)}
 * to revert if the user does not confirm within the timeout.</p>
 *
 * <p>xrandr only controls a real X server. Under a Wayland compositor (the
 * dev-machine default) it sees a single virtual output and {@code apply} is
 * usually a no-op or fails; {@link SystemInfoService#isWayland()} lets the UI
 * warn the user instead of pretending the change took effect.</p>
 */
public final class DisplayService {
    private static final Logger logger = Logger.getLogger("lg.system");

    private DisplayService() {
        // no instances
    }

    /** True if the xrandr tool is installed. */
    public static boolean isAvailable() {
        return ProcessRunner.isAvailable("xrandr");
    }

    /** One supported mode (resolution) and its refresh rates. */
    public static final class Mode {
        private final int width;
        private final int height;
        private final List<Double> rates = new ArrayList<>();
        private boolean preferred;
        private double currentRate;

        Mode(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public List<Double> getRates() { return rates; }
        public boolean isPreferred() { return preferred; }
        public double getCurrentRate() { return currentRate; }
        public String getId() { return width + "x" + height; }

        @Override
        public String toString() { return getId(); }
    }

    /** A display output (connector) and its state. */
    public static final class Output {
        private final String name;
        private final boolean connected;
        private boolean primary;
        private int posX;
        private int posY;
        private int curWidth;
        private int curHeight;
        private double curRate;
        private final List<Mode> modes = new ArrayList<>();

        Output(String name, boolean connected) {
            this.name = name;
            this.connected = connected;
        }

        public String getName() { return name; }
        public boolean isConnected() { return connected; }
        public boolean isPrimary() { return primary; }
        public int getPosX() { return posX; }
        public int getPosY() { return posY; }
        public List<Mode> getModes() { return modes; }

        /** True if connected and currently driving a mode. */
        public boolean isEnabled() { return connected && curWidth > 0 && curHeight > 0; }

        public int getCurrentWidth() { return curWidth; }
        public int getCurrentHeight() { return curHeight; }
        public double getCurrentRate() { return curRate; }

        /** The Mode matching the current resolution, or null. */
        public Mode getCurrentMode() {
            for (Mode m : modes) {
                if (m.getWidth() == curWidth && m.getHeight() == curHeight) {
                    return m;
                }
            }
            return null;
        }

        /** The preferred Mode, else the first, else null. */
        public Mode getPreferredMode() {
            for (Mode m : modes) {
                if (m.isPreferred()) {
                    return m;
                }
            }
            return modes.isEmpty() ? null : modes.get(0);
        }

        @Override
        public String toString() { return name + (connected ? " (connected)" : " (disconnected)"); }
    }

    /** A desired configuration for one output, used by {@link #apply(List)}. */
    public static final class OutputSetting {
        public String output;
        /** Mode id (e.g. "1920x1080"), or null for --auto. Ignored if off. */
        public String mode;
        /** Refresh rate in Hz, or null to leave unspecified. */
        public Double rate;
        public boolean primary;
        /** Turn the output off. */
        public boolean off;
        /** Absolute position; if null, relativeTo is used. */
        public Integer posX;
        public Integer posY;
        /** Relative placement: one of "left-of", "right-of", "above", "below"
         *  plus the target output name, e.g. {@code "left-of:eDP-1"}. */
        public String relativeTo;
        /** Output scale factor (e.g. 1.5), or null to leave unspecified. */
        public Double scale;

        public OutputSetting() { }

        public OutputSetting(String output) {
            this.output = output;
        }
    }

    /** Result of applying a configuration. */
    public static final class ApplyResult {
        private final boolean success;
        private final String message;
        private final int exitCode;

        ApplyResult(boolean success, String message, int exitCode) {
            this.success = success;
            this.message = message;
            this.exitCode = exitCode;
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public int getExitCode() { return exitCode; }
    }

    /**
     * Parses {@code xrandr -q} into the list of outputs (connected and
     * disconnected). Returns an empty list if xrandr is missing or fails.
     */
    public static List<Output> query() {
        List<Output> outputs = new ArrayList<>();
        if (!isAvailable()) {
            return outputs;
        }
        ProcessRunner.Result r = ProcessRunner.run("xrandr", "-q");
        if (!r.isSuccess()) {
            logger.log(Level.FINE, "xrandr -q failed: {0}", r.getMessage());
            return outputs;
        }

        Output current = null;
        Map<String, Mode> modesById = null;
        for (String raw : r.getStdout().split("\\r?\\n")) {
            if (raw.isEmpty()) {
                continue;
            }
            boolean indented = Character.isWhitespace(raw.charAt(0));
            String line = raw.trim();
            if (!indented) {
                if (line.startsWith("Screen ")) {
                    current = null;
                    continue;
                }
                // Output header line.
                modesById = new LinkedHashMap<>();
                current = parseOutputHeader(line);
                outputs.add(current);
            } else if (current != null && modesById != null) {
                parseModeLine(line, current, modesById);
            }
        }
        return outputs;
    }

    private static Output parseOutputHeader(String line) {
        String[] t = line.split("\\s+");
        String name = t[0];
        boolean connected = t.length > 1 && t[1].equals("connected");
        Output o = new Output(name, connected);
        for (int i = 1; i < t.length; i++) {
            String tok = t[i];
            if (tok.equals("primary")) {
                o.primary = true;
            } else if (tok.contains("x") && tok.contains("+")) {
                // e.g. 1920x1080+0+0
                try {
                    String[] wh = tok.split("\\+");
                    String[] res = wh[0].split("x");
                    o.curWidth = Integer.parseInt(res[0]);
                    o.curHeight = Integer.parseInt(res[1]);
                    if (wh.length >= 3) {
                        o.posX = Integer.parseInt(wh[1]);
                        o.posY = Integer.parseInt(wh[2]);
                    }
                } catch (RuntimeException ignored) {
                    // not a geometry token; ignore
                }
            }
        }
        return o;
    }

    private static void parseModeLine(String line, Output out, Map<String, Mode> modesById) {
        String[] t = line.split("\\s+");
        if (t.length == 0) {
            return;
        }
        String[] res = t[0].split("x");
        if (res.length != 2) {
            return;
        }
        int w, h;
        try {
            w = Integer.parseInt(res[0]);
            h = Integer.parseInt(res[1]);
        } catch (NumberFormatException e) {
            return;
        }
        Mode mode = modesById.get(t[0]);
        if (mode == null) {
            mode = new Mode(w, h);
            modesById.put(t[0], mode);
            out.modes.add(mode);
        }
        for (int i = 1; i < t.length; i++) {
            String tok = t[i];
            boolean isCurrent = tok.contains("*");
            boolean isPreferred = tok.contains("+");
            String num = tok.replace("*", "").replace("+", "");
            try {
                double rate = Double.parseDouble(num);
                if (!mode.rates.contains(rate)) {
                    mode.rates.add(rate);
                }
                if (isPreferred) {
                    mode.preferred = true;
                }
                if (isCurrent) {
                    mode.currentRate = rate;
                    if (out.curWidth == 0 && out.curHeight == 0) {
                        out.curWidth = w;
                        out.curHeight = h;
                    }
                    out.curRate = rate;
                }
            } catch (NumberFormatException ignored) {
                // trailing tokens like "(normal" - ignore
            }
        }
    }

    /**
     * Captures the current configuration as a list of {@link OutputSetting}s
     * that would reproduce it, for use with {@link #restore(List)}.
     */
    public static List<OutputSetting> capture() {
        List<OutputSetting> settings = new ArrayList<>();
        for (Output o : query()) {
            if (!o.isConnected()) {
                continue;
            }
            OutputSetting s = new OutputSetting(o.getName());
            if (!o.isEnabled()) {
                s.off = true;
            } else {
                s.mode = o.getCurrentWidth() + "x" + o.getCurrentHeight();
                if (o.getCurrentRate() > 0) {
                    s.rate = o.getCurrentRate();
                }
                s.primary = o.isPrimary();
                s.posX = o.getPosX();
                s.posY = o.getPosY();
            }
            settings.add(s);
        }
        return settings;
    }

    /**
     * Applies output settings by building and running a single
     * {@code xrandr --output ...} command.
     */
    public static ApplyResult apply(List<OutputSetting> settings) {
        if (!isAvailable()) {
            return new ApplyResult(false, "xrandr is not installed.", -1);
        }
        if (settings == null || settings.isEmpty()) {
            return new ApplyResult(false, "No display changes to apply.", -1);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("xrandr");
        for (OutputSetting s : settings) {
            if (s.output == null || s.output.isBlank()) {
                continue;
            }
            cmd.add("--output");
            cmd.add(s.output);
            if (s.off) {
                cmd.add("--off");
                continue;
            }
            if (s.mode != null && !s.mode.isBlank()) {
                cmd.add("--mode");
                cmd.add(s.mode);
            } else {
                cmd.add("--auto");
            }
            if (s.rate != null && s.rate > 0) {
                cmd.add("--rate");
                cmd.add(formatRate(s.rate));
            }
            if (s.scale != null && s.scale > 0) {
                cmd.add("--scale");
                cmd.add(String.format("%.3f", s.scale));
            }
            if (s.primary) {
                cmd.add("--primary");
            }
            if (s.relativeTo != null && !s.relativeTo.isBlank()) {
                int colon = s.relativeTo.indexOf(':');
                if (colon > 0) {
                    cmd.add("--" + s.relativeTo.substring(0, colon));
                    cmd.add(s.relativeTo.substring(colon + 1));
                }
            } else if (s.posX != null && s.posY != null) {
                cmd.add("--pos");
                cmd.add(s.posX + "x" + s.posY);
            }
        }
        ProcessRunner.Result r = ProcessRunner.run(cmd);
        if (r.isSuccess()) {
            return new ApplyResult(true, "", 0);
        }
        logger.log(Level.INFO, "xrandr apply failed: {0}", String.join(" ", cmd));
        return new ApplyResult(false,
                r.getMessage().isEmpty() ? "xrandr rejected the configuration." : r.getMessage(),
                r.getExitCode());
    }

    /** Reverts to a configuration previously returned by {@link #capture()}. */
    public static ApplyResult restore(List<OutputSetting> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return new ApplyResult(false, "No snapshot to restore.", -1);
        }
        return apply(snapshot);
    }

    private static String formatRate(double rate) {
        // xrandr accepts e.g. "59.95"; trim trailing zeros but keep at least
        // the integer part.
        if (rate == Math.rint(rate)) {
            return String.valueOf((long) rate);
        }
        return String.format("%.2f", rate);
    }
}
