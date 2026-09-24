/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Enumerates running processes and exposes terminate/kill/renice operations,
 * backed by {@code /proc} and {@link ProcessHandle}.
 *
 * <p>Per-process CPU usage is computed from the delta of the kernel
 * {@code utime+stime} counters between successive {@link #snapshot()} calls, so
 * a caller that refreshes on a timer gets a meaningful percentage. The first
 * snapshot for a given process reports 0% (there is no previous sample yet).</p>
 *
 * <p>Signalling a process we own uses {@link ProcessHandle#destroy()} /
 * {@link ProcessHandle#destroyForcibly()}; processes owned by other users fall
 * back to {@code pkexec kill} via {@link PrivilegedRunner}, which returns a
 * structured result so the UI can prompt or explain rather than fail.</p>
 */
public final class ProcessService {
    private static final Logger logger = Logger.getLogger("lg.system");

    /** Previous {@code utime+stime} (ticks) and wall-clock nanos per pid, for
     *  CPU% deltas. */
    private static final Map<Long, long[]> PREV_CPU = new HashMap<>();
    /** Previous aggregate {@code /proc/stat} cpu line + wall nanos. */
    private static long[] prevTotalCpu;
    private static long prevTotalNanos;

    /** Cached uid -> user name map from /etc/passwd (refreshed periodically). */
    private static Map<Long, String> uidNames = new HashMap<>();
    private static long uidNamesLoadedAt = 0L;
    private static final long UID_CACHE_MS = 30_000L;

    private ProcessService() {
        // no instances
    }

    /** An immutable snapshot of one process. */
    public static final class ProcessInfo {
        private final long pid;
        private final String name;
        private final String command;
        private final String user;
        private final char state;
        private final long rssBytes;
        private final double cpuPercent;
        private final Instant startTime;
        private final long threads;

        ProcessInfo(long pid, String name, String command, String user, char state,
                    long rssBytes, double cpuPercent, Instant startTime, long threads) {
            this.pid = pid;
            this.name = name;
            this.command = command;
            this.user = user;
            this.state = state;
            this.rssBytes = rssBytes;
            this.cpuPercent = cpuPercent;
            this.startTime = startTime;
            this.threads = threads;
        }

        public long getPid() { return pid; }
        public String getName() { return name; }
        public String getCommand() { return command; }
        public String getUser() { return user; }
        public char getState() { return state; }
        public long getRssBytes() { return rssBytes; }
        public double getCpuPercent() { return cpuPercent; }
        public Instant getStartTime() { return startTime; }
        public long getThreads() { return threads; }

        /** Human-readable process state (R/S/D/Z/T/...). */
        public String getStateLabel() {
            switch (state) {
                case 'R': return "Running";
                case 'S': return "Sleeping";
                case 'D': return "Disk sleep";
                case 'Z': return "Zombie";
                case 'T': return "Stopped";
                case 't': return "Tracing stop";
                case 'X': return "Dead";
                case 'I': return "Idle";
                default:  return String.valueOf(state);
            }
        }

        @Override
        public String toString() {
            return pid + " " + name + " (" + user + ") " + String.format("%.1f%%", cpuPercent);
        }
    }

    /** Aggregate system load for the task manager header / widgets. */
    public static final class SystemLoad {
        private final double cpuPercent;
        private final double[] loadAverage;
        private final long memTotalKb;
        private final long memUsedKb;
        private final long swapTotalKb;
        private final long swapUsedKb;
        private final double uptimeSeconds;
        private final int processCount;

        SystemLoad(double cpuPercent, double[] loadAverage, long memTotalKb, long memUsedKb,
                   long swapTotalKb, long swapUsedKb, double uptimeSeconds, int processCount) {
            this.cpuPercent = cpuPercent;
            this.loadAverage = loadAverage;
            this.memTotalKb = memTotalKb;
            this.memUsedKb = memUsedKb;
            this.swapTotalKb = swapTotalKb;
            this.swapUsedKb = swapUsedKb;
            this.uptimeSeconds = uptimeSeconds;
            this.processCount = processCount;
        }

        public double getCpuPercent() { return cpuPercent; }
        public double[] getLoadAverage() { return loadAverage; }
        public long getMemTotalKb() { return memTotalKb; }
        public long getMemUsedKb() { return memUsedKb; }
        public long getSwapTotalKb() { return swapTotalKb; }
        public long getSwapUsedKb() { return swapUsedKb; }
        public double getUptimeSeconds() { return uptimeSeconds; }
        public int getProcessCount() { return processCount; }

        public double getMemUsedPercent() {
            return (memTotalKb > 0) ? (100.0 * memUsedKb / memTotalKb) : 0.0;
        }
    }

    /**
     * Takes a snapshot of all processes. Call this repeatedly on a timer to get
     * meaningful per-process CPU percentages.
     */
    public static List<ProcessInfo> snapshot() {
        long nowNanos = System.nanoTime();
        long clkTck = Proc.clockTicksPerSecond();
        long pageSize = Proc.pageSize();
        long bootSeconds = Proc.bootTimeSeconds();
        Map<Long, String> names = uidNames();

        List<Long> pids = Proc.pids();
        List<ProcessInfo> result = new ArrayList<>(pids.size());
        Map<Long, long[]> nextPrev = new HashMap<>(pids.size() * 2);

        for (long pid : pids) {
            Proc.Stat st = Proc.stat(pid);
            if (!st.valid) {
                continue; // process vanished mid-scan
            }
            String cmdline = Proc.cmdline(pid);
            String command = (cmdline != null && !cmdline.isEmpty()) ? cmdline : ("[" + st.comm + "]");
            String name = (st.comm != null && !st.comm.isEmpty()) ? st.comm : deriveName(command);

            long uid = Proc.uid(pid);
            String user = names.getOrDefault(uid, (uid >= 0 ? String.valueOf(uid) : "?"));

            long rssBytes = Proc.rssPages(pid) * pageSize;
            long threads = Proc.threads(pid);

            long cpuTicks = st.utime + st.stime;
            double cpuPercent = 0.0;
            long[] prev = PREV_CPU.get(pid);
            if (prev != null) {
                long dTicks = cpuTicks - prev[0];
                double dSeconds = (nowNanos - prev[1]) / 1_000_000_000.0;
                if (dTicks > 0 && dSeconds > 0 && clkTck > 0) {
                    cpuPercent = (dTicks / (double) clkTck) / dSeconds * 100.0;
                    int cores = Runtime.getRuntime().availableProcessors();
                    cpuPercent = Math.min(cpuPercent, cores * 100.0);
                }
            }
            nextPrev.put(pid, new long[] { cpuTicks, nowNanos });

            Instant start = null;
            if (bootSeconds > 0 && clkTck > 0) {
                long startEpochMs = bootSeconds * 1000L + (st.startTime * 1000L / clkTck);
                start = Instant.ofEpochMilli(startEpochMs);
            }

            result.add(new ProcessInfo(pid, name, command, user, st.state,
                    rssBytes, cpuPercent, start, threads));
        }

        // Swap in the fresh sample and drop entries for exited processes.
        PREV_CPU.clear();
        PREV_CPU.putAll(nextPrev);

        return result;
    }

    /** Aggregate CPU load (0-100%) computed from successive /proc/stat reads. */
    public static double totalCpuPercent() {
        long[] cur = Proc.cpuTimes();
        long nowNanos = System.nanoTime();
        double percent = 0.0;
        if (cur.length >= 4 && prevTotalCpu != null && prevTotalCpu.length == cur.length) {
            long idleDelta = (cur[3] + (cur.length > 4 ? cur[4] : 0))
                           - (prevTotalCpu[3] + (prevTotalCpu.length > 4 ? prevTotalCpu[4] : 0));
            long totalDelta = sum(cur) - sum(prevTotalCpu);
            if (totalDelta > 0) {
                percent = 100.0 * (totalDelta - idleDelta) / totalDelta;
                percent = Math.max(0.0, Math.min(100.0, percent));
            }
        }
        prevTotalCpu = cur;
        prevTotalNanos = nowNanos;
        return percent;
    }

    /** Aggregate system load (CPU%, memory, swap, load average, uptime). */
    public static SystemLoad systemLoad() {
        double cpu = totalCpuPercent();
        Proc.MemInfo m = Proc.memInfo();
        double[] load = Proc.loadAverage();
        double uptime = Proc.uptimeSeconds();
        int count = Proc.pids().size();
        return new SystemLoad(cpu, load, m.memTotalKb, m.usedKb(),
                m.swapTotalKb, Math.max(0, m.swapTotalKb - m.swapFreeKb), uptime, count);
    }

    // ------------------------------------------------------------------
    // Process control
    // ------------------------------------------------------------------

    /** Sends SIGTERM. Falls back to {@code pkexec kill -TERM} for processes we
     *  do not own. */
    public static TerminateResult terminate(long pid) {
        return signal(pid, false);
    }

    /** Sends SIGKILL. Falls back to {@code pkexec kill -KILL} for processes we
     *  do not own. */
    public static TerminateResult kill(long pid) {
        return signal(pid, true);
    }

    /** Outcome of a terminate/kill attempt. */
    public static final class TerminateResult {
        public enum Kind { SUCCESS, NOT_PERMITTED, FAILED }
        private final Kind kind;
        private final String message;
        TerminateResult(Kind kind, String message) {
            this.kind = kind;
            this.message = message;
        }
        public Kind getKind() { return kind; }
        public boolean isSuccess() { return kind == Kind.SUCCESS; }
        public String getMessage() { return message; }
    }

    private static TerminateResult signal(long pid, boolean force) {
        // Try the in-JVM path first (works for processes owned by us).
        try {
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) {
                boolean ok = force ? handle.get().destroyForcibly() : handle.get().destroy();
                if (ok) {
                    return new TerminateResult(TerminateResult.Kind.SUCCESS, "");
                }
            }
        } catch (SecurityException e) {
            logger.fine("Not permitted to signal pid " + pid + " in-JVM: " + e.getMessage());
        }
        // Fall back to pkexec (prompts for admin rights).
        PrivilegedRunner.PrivilegedResult r =
                PrivilegedRunner.run("kill", force ? "-KILL" : "-TERM", String.valueOf(pid));
        switch (r.getStatus()) {
            case SUCCESS:
                return new TerminateResult(TerminateResult.Kind.SUCCESS, "");
            case CANCELLED:
            case UNAVAILABLE:
                return new TerminateResult(TerminateResult.Kind.NOT_PERMITTED, r.getMessage());
            default:
                return new TerminateResult(TerminateResult.Kind.FAILED, r.getMessage());
        }
    }

    /**
     * Changes scheduling priority via {@code pkexec renice -n <niceness>}.
     * Niceness ranges from -20 (highest) to 19 (lowest).
     */
    public static PrivilegedRunner.PrivilegedResult renice(long pid, int niceness) {
        int n = Math.max(-20, Math.min(19, niceness));
        return PrivilegedRunner.run("renice", "-n", String.valueOf(n), "-p", String.valueOf(pid));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) {
            s += v;
        }
        return s;
    }

    private static String deriveName(String command) {
        if (command == null || command.isEmpty()) {
            return "?";
        }
        String first = command.split("\\s+")[0];
        int slash = first.lastIndexOf('/');
        return (slash >= 0 && slash < first.length() - 1) ? first.substring(slash + 1) : first;
    }

    /** Cached uid -> name map from /etc/passwd. */
    private static synchronized Map<Long, String> uidNames() {
        long now = System.currentTimeMillis();
        if (now - uidNamesLoadedAt < UID_CACHE_MS && !uidNames.isEmpty()) {
            return uidNames;
        }
        Map<Long, String> map = new HashMap<>();
        for (String line : Proc.readLines("/etc/passwd")) {
            String[] f = line.split(":");
            if (f.length >= 3) {
                try {
                    map.put(Long.parseLong(f[2].trim()), f[0]);
                } catch (NumberFormatException ignored) {
                    // skip malformed line
                }
            }
        }
        if (!map.isEmpty()) {
            uidNames = map;
            uidNamesLoadedAt = now;
        }
        return uidNames;
    }

    /** Clears cached CPU samples (e.g. after the task manager window closes). */
    public static synchronized void resetSamples() {
        PREV_CPU.clear();
        prevTotalCpu = null;
        prevTotalNanos = 0L;
    }

    /** Formats a byte count as a human-readable string (B/KB/MB/GB). */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    /** Formats a duration in seconds as "Xd HH:MM:SS" / "HH:MM:SS" / "MM:SS". */
    public static String formatUptime(double seconds) {
        long s = (long) seconds;
        long days = s / 86400; s -= days * 86400;
        long hours = s / 3600; s -= hours * 3600;
        long mins = s / 60; s -= mins * 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (days > 0 || hours > 0) sb.append(String.format("%02d:", hours));
        sb.append(String.format("%02d:%02d", mins, s));
        return sb.toString();
    }

    /** Suppresses "unused" warnings for the retained aggregate-nanos field. */
    static long lastTotalNanos() {
        return prevTotalNanos;
    }

    /** Exposed for tests: iterate a snapshot deterministically by pid. */
    static Iterator<ProcessInfo> sortedSnapshot() {
        List<ProcessInfo> list = snapshot();
        list.sort((a, b) -> Long.compare(a.getPid(), b.getPid()));
        return list.iterator();
    }
}
