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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the raw text output of a CD TOC reader into a {@link Toc}. Two dialects
 * are understood:
 *
 * <ul>
 *   <li>{@code cdparanoia -Q} (the primary ripper), whose track rows look like
 *       {@code   1.     32157 (07:08.57)        0 (00:00.00)   0   0  2} -
 *       track number, length in frames, begin in frames;</li>
 *   <li>{@code cd-info} (the fallback), whose rows carry the track number and
 *       its start LSN; lengths are derived from the gap between consecutive
 *       starts, and the final track's length from a {@code Lead-out} row when
 *       present.</li>
 * </ul>
 *
 * <p>Parsing is pure and tolerant: unrecognised lines are ignored, and output
 * with no track rows yields an empty {@link Toc} rather than throwing, so a
 * changed tool banner or a data track can never break the rip dialog.</p>
 */
public final class TocParser {

    // cdparanoia:  "  1.     32157 (07:08.57)        0 (00:00.00) ..."
    private static final Pattern CDPARANOIA = Pattern.compile(
            "^\\s*(\\d+)\\.\\s+(\\d+)\\s+\\([^)]*\\)\\s+(\\d+)\\s+\\([^)]*\\)");

    // cd-info:     "  1         0 00:00:00.00     00:07:08.57"
    private static final Pattern CDINFO = Pattern.compile(
            "^\\s*(\\d+)\\s+(\\d+)\\s+\\d+:\\d+:?\\d*\\.\\d+");

    // cd-info lead-out / cdparanoia TOTAL:  "Lead-out  73249 ..." or "TOTAL 73249"
    private static final Pattern LEAD_OUT = Pattern.compile(
            "^\\s*(?i:lead-?out|total)\\s+(\\d+)");

    private TocParser() {
        // no instances
    }

    /**
     * Parses tool output into a {@link Toc}.
     *
     * @param output the combined stdout/stderr of a TOC reader; may be null
     * @return the parsed TOC, or an empty one when no track rows are found
     */
    public static Toc parse(String output) {
        if (output == null || output.isBlank()) {
            return new Toc(1, List.of());
        }
        Toc cdparanoia = parseCdparanoia(output);
        if (!cdparanoia.isEmpty()) {
            return cdparanoia;
        }
        Toc cdinfo = parseCdInfo(output);
        return cdinfo.isEmpty() ? new Toc(1, List.of()) : cdinfo;
    }

    private static Toc parseCdparanoia(String output) {
        List<Toc.Track> tracks = new ArrayList<>();
        int first = 1;
        for (String line : output.split("\\R")) {
            Matcher m = CDPARANOIA.matcher(line);
            if (m.find()) {
                int number = Integer.parseInt(m.group(1));
                long length = Long.parseLong(m.group(2));
                long begin = Long.parseLong(m.group(3));
                if (tracks.isEmpty()) {
                    first = number;
                }
                tracks.add(new Toc.Track(number, begin, length));
            }
        }
        return new Toc(first, tracks);
    }

    private static Toc parseCdInfo(String output) {
        List<int[]> starts = new ArrayList<>(); // {number, lsn}
        long leadOut = -1;
        for (String line : output.split("\\R")) {
            Matcher lo = LEAD_OUT.matcher(line);
            if (lo.find()) {
                leadOut = Long.parseLong(lo.group(1));
                continue;
            }
            Matcher m = CDINFO.matcher(line);
            if (m.find()) {
                starts.add(new int[] {
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)) });
            }
        }
        if (starts.isEmpty()) {
            return new Toc(1, List.of());
        }
        List<Toc.Track> tracks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int number = starts.get(i)[0];
            long begin = starts.get(i)[1];
            long length;
            if (i + 1 < starts.size()) {
                length = starts.get(i + 1)[1] - begin;
            } else if (leadOut >= 0) {
                length = leadOut - begin;
            } else {
                length = 0;
            }
            tracks.add(new Toc.Track(number, begin, Math.max(0, length)));
        }
        return new Toc(tracks.get(0).number, tracks);
    }
}
