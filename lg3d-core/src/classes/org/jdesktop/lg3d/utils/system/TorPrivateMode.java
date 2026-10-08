/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The desktop-wide <strong>Private (Tor) mode</strong>: a Whonix-style anonymity
 * guarantee expressed at the desktop level rather than the OS level. While the
 * mode is on, every network client the desktop owns is forced through the local
 * tor SOCKS endpoint, and a daemon monitor watches the tor daemon: the moment
 * it stops, the mode trips to {@link State#CUT} and raises {@link NetworkCut},
 * so a broken guarantee degrades to a loud, refused network - never to a
 * silent clearnet leak.
 *
 * <p>Enforcement is by construction, not by promise:</p>
 * <ul>
 *   <li>JVM clients (the web browser, {@code java.net.http}, URLConnection)
 *       ride the SOCKS system properties installed by {@link #enable()} plus a
 *       default {@link ProxySelector}; SOCKS v5 resolves names remotely, so DNS
 *       leaks through the resolver too.</li>
 *   <li>External commands the desktop spawns receive the matching
 *       {@code *_proxy} environment variables ({@link #proxyEnv}), so
 *       proxy-respecting children follow the same path.</li>
 *   <li>When tor dies the SOCKS endpoint dies with it: every forced socket
 *       attempt is refused. The {@link State#CUT} flag additionally lets UIs
 *       abort early and explain why.</li>
 * </ul>
 *
 * <p>Tor remains a plain <em>service</em>: its lifecycle is still delegated to
 * {@link PrivacyService} (and through it the {@code system-management-contract.md}
 * §4.1 init abstraction), so this class adds no tor-specific service handling -
 * only the anonymity guarantee on top. The mutating start is polkit-escalated
 * and MUST be confirmed by the calling UI before {@link #enable()} is invoked
 * (§4.7).</p>
 *
 * <p>Every decision is pure and headless-testable: {@link #transition},
 * {@link #monitorTick}, {@link #proxyProperties}, {@link #proxyEnv},
 * {@link #parseTorCheck} and {@link #socksPort} touch no process and no global
 * state. The live {@link #enable()}/{@link #disable()}/monitor wrap them.</p>
 */
public final class TorPrivateMode {

    /** The private-mode state machine states. */
    public enum State {
        /** The mode is off; clients use the ordinary network. */
        OFF,
        /** An enable request is in flight (probe / tor start / proxy apply). */
        ENABLING,
        /** The mode is on and tor is up; all desktop clients ride the SOCKS endpoint. */
        ON,
        /** The mode is on but tor died: the network is cut until tor returns. */
        CUT
    }

    /** The events the state machine consumes. */
    public enum Event {
        /** The user asked to enter private mode (from {@link State#OFF}). */
        ENABLE_REQUESTED,
        /** Tor is up and the proxy enforcement is installed. */
        ENABLE_SUCCEEDED,
        /** Tor could not be brought up; the mode falls back to {@link State#OFF}. */
        ENABLE_FAILED,
        /** The monitor observed tor down while the mode was {@link State#ON}. */
        TOR_DOWN,
        /** The monitor observed tor running again while {@link State#CUT}. */
        TOR_RESTORED,
        /** The user left private mode (from any state). */
        DISABLE_REQUESTED
    }

    /** The verdict of the exit-node leak check. */
    public enum LeakVerdict {
        /** The check answered through tor: the exit IP is a tor exit. */
        TOR_CONFIRMED,
        /** The check answered but could not confirm tor (fail-closed: a leak). */
        NOT_TOR,
        /** The check could not reach the service at all (inconclusive, cut-safe). */
        UNREACHABLE
    }

    /** The local SOCKS host tor listens on. */
    public static final String SOCKS_HOST = "127.0.0.1";

    /** The default tor {@code SocksPort}. */
    public static final int DEFAULT_SOCKS_PORT = 9050;

    /** System property overriding {@link #DEFAULT_SOCKS_PORT}. */
    public static final String SOCKS_PORT_PROPERTY = "lg.tor.socksPort";

    /** The tor-project exit-IP check answered through the proxy only. */
    public static final String CHECK_URL = "https://check.torproject.org/api/ip";

    /** How often the monitor re-reads the tor state, in seconds. */
    static final long MONITOR_PERIOD_SECONDS = 5L;

    /** Timeout on the leak-check request, in seconds. */
    static final int CHECK_TIMEOUT_SECONDS = 15;

    private static final Logger logger = Logger.getLogger("lg.system");

    /** The registered state-change listeners. */
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** Guards the state machine and the monitor/selector installation. */
    private static final Object LOCK = new Object();

    private static volatile State state = State.OFF;
    private static ScheduledExecutorService monitor;
    private static ScheduledFuture<?> monitorTask;
    private static ProxySelector previousSelector;

    /** Notified on every state transition, on the thread that caused it. */
    public interface Listener {
        void stateChanged(State from, State to);
    }

    private TorPrivateMode() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Pure state machine (headless-testable)

    /**
     * The state transition table. Unknown/null inputs are tolerated: a null
     * event leaves the state untouched, a null state reads as {@link State#OFF}.
     */
    public static State transition(State current, Event event) {
        State s = (current == null) ? State.OFF : current;
        if (event == null) {
            return s;
        }
        return switch (event) {
            case ENABLE_REQUESTED -> (s == State.OFF) ? State.ENABLING : s;
            case ENABLE_SUCCEEDED -> (s == State.ENABLING) ? State.ON : s;
            case ENABLE_FAILED -> (s == State.ENABLING) ? State.OFF : s;
            case TOR_DOWN -> (s == State.ON) ? State.CUT : s;
            case TOR_RESTORED -> (s == State.CUT) ? State.ON : s;
            case DISABLE_REQUESTED -> State.OFF;
        };
    }

    /**
     * One monitor tick: the observed tor state against the current mode state.
     * {@link State#ON} with tor not running trips to {@link State#CUT} (the
     * kill switch); {@link State#CUT} with tor running again recovers to
     * {@link State#ON}. Every other combination is a no-op. Fails closed: an
     * {@code UNKNOWN} tor state cuts just like {@code STOPPED}.
     */
    public static State monitorTick(State current, PrivacyService.TorState tor) {
        if (current == State.ON && tor != PrivacyService.TorState.RUNNING) {
            return State.CUT;
        }
        if (current == State.CUT && tor == PrivacyService.TorState.RUNNING) {
            return State.ON;
        }
        return current;
    }

    /**
     * The JVM SOCKS enforcement properties for {@code port}: the host, the port
     * and a non-proxy list limited to loopback, so nothing else bypasses the
     * proxy. Pure; {@link #enable()} installs exactly this map.
     */
    public static Map<String, String> proxyProperties(int port) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("socksProxyHost", SOCKS_HOST);
        props.put("socksProxyPort", String.valueOf(port));
        props.put("socksNonProxyHosts", "localhost|127.*");
        return Collections.unmodifiableMap(props);
    }

    /**
     * The environment variables a spawned child process must carry while the
     * mode is on ({@code socks5h} = remote DNS through the proxy). Empty when
     * {@code on} is false, so callers can {@code putAll} unconditionally.
     */
    public static Map<String, String> proxyEnv(boolean on, int port) {
        Map<String, String> env = new LinkedHashMap<>();
        if (!on) {
            return env;
        }
        String value = "socks5h://" + SOCKS_HOST + ":" + port;
        for (String key : new String[] {
                "http_proxy", "https_proxy", "all_proxy",
                "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY" }) {
            env.put(key, value);
        }
        return env;
    }

    /**
     * The effective SOCKS port: the {@value #SOCKS_PORT_PROPERTY} system
     * property when it parses to a valid port, else {@link #DEFAULT_SOCKS_PORT}.
     */
    public static int socksPort() {
        int port = Integer.getInteger(SOCKS_PORT_PROPERTY, DEFAULT_SOCKS_PORT);
        return (port > 0 && port <= 65535) ? port : DEFAULT_SOCKS_PORT;
    }

    /**
     * Parses the tor-project check body ({@code {"IsTor":true,...}}) into a
     * {@link LeakVerdict}. Fails closed: a blank/null body is
     * {@link LeakVerdict#UNREACHABLE}, anything reachable that does not
     * positively confirm tor is {@link LeakVerdict#NOT_TOR}.
     */
    public static LeakVerdict parseTorCheck(String body) {
        if (body == null || body.isBlank()) {
            return LeakVerdict.UNREACHABLE;
        }
        String compact = body.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
        if (compact.contains("\"istor\":true")) {
            return LeakVerdict.TOR_CONFIRMED;
        }
        return LeakVerdict.NOT_TOR;
    }

    /** A short human label for a mode state; never null. */
    public static String describe(State s) {
        if (s == null) {
            return "Off";
        }
        return switch (s) {
            case OFF -> "Off";
            case ENABLING -> "Enabling";
            case ON -> "On - traffic forced through tor";
            case CUT -> "Cut - tor stopped, network refused";
        };
    }

    // ------------------------------------------------------------------
    // Live state machine

    /** The current mode state. */
    public static State state() {
        return state;
    }

    /** True while the mode is on and tor is up. */
    public static boolean isOn() {
        return state == State.ON;
    }

    /** True while the mode is on but tor is down (network cut). */
    public static boolean isCut() {
        return state == State.CUT;
    }

    /** Registers a state-change listener; null is ignored. */
    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    /** Removes a previously registered listener. */
    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Enters private mode: probes tor, starts it through {@link PrivacyService}
     * when stopped (polkit-escalated - the caller MUST have confirmed first),
     * installs the SOCKS enforcement and starts the monitor. Idempotent while
     * {@link State#ON}; from {@link State#CUT} it retries the tor start and
     * recovers when the daemon is back.
     *
     * @return true if the mode ended up {@link State#ON}
     */
    public static boolean enable() {
        synchronized (LOCK) {
            if (state == State.ON) {
                return true;
            }
            if (state == State.ENABLING) {
                return false;
            }
            if (state == State.CUT) {
                return retryFromCut();
            }
            setState(transition(state, Event.ENABLE_REQUESTED));
            if (!PrivacyService.isTorManageable(PrivacyService.probe())) {
                logger.log(Level.INFO,
                        "Private mode refused: tor is not manageable on this host");
                setState(transition(state, Event.ENABLE_FAILED));
                return false;
            }
            PrivacyService.TorState tor = readTorState();
            if (tor != PrivacyService.TorState.RUNNING) {
                PrivacyService.runMutating(PrivacyService.Operation.TOR_START);
                tor = readTorState();
            }
            if (tor != PrivacyService.TorState.RUNNING) {
                logger.log(Level.WARNING,
                        "Private mode refused: tor could not be started");
                setState(transition(state, Event.ENABLE_FAILED));
                return false;
            }
            applyProxy();
            startMonitor();
            setState(transition(state, Event.ENABLE_SUCCEEDED));
            NetworkCut.restore();
            return true;
        }
    }

    /**
     * Leaves private mode from any state: stops the monitor, removes the SOCKS
     * enforcement (restoring the previous {@link ProxySelector}) and lowers
     * {@link NetworkCut}. Safe to call when already off.
     */
    public static void disable() {
        synchronized (LOCK) {
            stopMonitor();
            clearProxy();
            State from = state;
            state = transition(state, Event.DISABLE_REQUESTED);
            if (from != state) {
                fire(from, state);
            }
            NetworkCut.restore();
        }
    }

    /**
     * Verifies the anonymity guarantee end-to-end by asking the tor project's
     * exit-IP check <em>through the proxy only</em>. Never throws; any I/O or
     * protocol failure is {@link LeakVerdict#UNREACHABLE}.
     */
    public static LeakVerdict leakCheck() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .proxy(socksSelector(socksPort()))
                    .connectTimeout(Duration.ofSeconds(CHECK_TIMEOUT_SECONDS))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(CHECK_URL))
                    .timeout(Duration.ofSeconds(CHECK_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            return parseTorCheck(response.body());
        } catch (IOException e) {
            logger.log(Level.FINE, "The tor leak check could not reach " + CHECK_URL, e);
            return LeakVerdict.UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LeakVerdict.UNREACHABLE;
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "The tor leak check failed unexpectedly", e);
            return LeakVerdict.UNREACHABLE;
        }
    }

    // ------------------------------------------------------------------
    // Internals

    /** From CUT: retry the tor start and recover when the daemon answers. */
    private static boolean retryFromCut() {
        PrivacyService.runMutating(PrivacyService.Operation.TOR_START);
        if (readTorState() != PrivacyService.TorState.RUNNING) {
            return false;
        }
        State from = state;
        state = transition(state, Event.TOR_RESTORED);
        fire(from, state);
        NetworkCut.restore();
        return true;
    }

    /** Reads the live tor state through the §4.7 read-only path. */
    static PrivacyService.TorState readTorState() {
        ProcessRunner.Result result =
                PrivacyService.runRead(PrivacyService.Operation.TOR_STATUS);
        if (!result.isStarted()) {
            return PrivacyService.TorState.UNKNOWN;
        }
        String output = result.getStdout().isBlank()
                ? result.getStderr() : result.getStdout();
        return PrivacyService.parseTorState(result.getExitCode(), output);
    }

    /** One monitor tick: observe tor and trip/recover the kill switch. */
    private static void tick() {
        PrivacyService.TorState tor = readTorState();
        synchronized (LOCK) {
            State next = monitorTick(state, tor);
            if (next == state) {
                return;
            }
            setState(next);
            if (next == State.CUT) {
                NetworkCut.cut();
            } else if (next == State.ON) {
                NetworkCut.restore();
            }
        }
    }

    private static void startMonitor() {
        if (monitor == null) {
            monitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "lg-tor-private-monitor");
                thread.setDaemon(true);
                return thread;
            });
        }
        if (monitorTask == null) {
            monitorTask = monitor.scheduleWithFixedDelay(
                    TorPrivateMode::tickSafe, MONITOR_PERIOD_SECONDS,
                    MONITOR_PERIOD_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** The monitor body: a throwing tick must not kill the schedule. */
    private static void tickSafe() {
        try {
            tick();
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "The tor private-mode monitor tick failed", e);
        }
    }

    private static void stopMonitor() {
        if (monitorTask != null) {
            monitorTask.cancel(false);
            monitorTask = null;
        }
        if (monitor != null) {
            monitor.shutdownNow();
            monitor = null;
        }
    }

    /** Installs the SOCKS enforcement: system properties + default selector. */
    private static void applyProxy() {
        for (Map.Entry<String, String> entry : proxyProperties(socksPort()).entrySet()) {
            System.setProperty(entry.getKey(), entry.getValue());
        }
        previousSelector = ProxySelector.getDefault();
        ProxySelector.setDefault(socksSelector(socksPort()));
    }

    /** Removes the enforcement and restores the previous selector. */
    private static void clearProxy() {
        for (String key : proxyProperties(socksPort()).keySet()) {
            System.clearProperty(key);
        }
        ProxySelector.setDefault(previousSelector);
        previousSelector = null;
    }

    /** A selector that routes everything through the local tor SOCKS port. */
    private static ProxySelector socksSelector(int port) {
        InetSocketAddress address = new InetSocketAddress(SOCKS_HOST, port);
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(new Proxy(Proxy.Type.SOCKS, address));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress unused, IOException e) {
                // Fails closed by construction: the refused socket is the cut.
                logger.log(Level.FINE, "SOCKS connect failed for " + uri, e);
            }
        };
    }

    private static void setState(State to) {
        State from = state;
        state = to;
        if (from != to) {
            logger.log(Level.INFO, "Private (Tor) mode: " + describe(from)
                    + " -> " + describe(to));
            fire(from, to);
        }
    }

    private static void fire(State from, State to) {
        for (Listener listener : LISTENERS) {
            try {
                listener.stateChanged(from, to);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "A TorPrivateMode listener threw", e);
            }
        }
    }
}
