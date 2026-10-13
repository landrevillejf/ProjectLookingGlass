/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps Wonderland M7d: the NAT-traversal probe — the stun.jar → ice4j
 * replacement for the wonderland softphone module.
 *
 * The 2006 softphone shipped stun.jar, a Sun-era STUN client that was never
 * open-sourced under a redistributable licence and has no maintained JDK 21
 * build. Its whole job was one question: "what is my public reflexive address,
 * and what kind of NAT sits in front of me", so the SIP signalling could
 * announce a routable contact. ice4j — the Apache-2.0 ICE/STUN/TURN engine the
 * Jitsi project maintains on Maven Central — answers the identical question
 * through its NetworkConfigurationDiscoveryProcess, the direct descendant of
 * the RFC 3489/5389 discovery stun.jar performed.
 *
 * Why it lives HERE and not in wonderland-modules: a real STUN round trip is
 * live network I/O, and the module keeps a hard headless floor with no sockets.
 * The module reaches this probe only through the desktop boot that wires the
 * ExternalSoftphoneBackend; the answer feeds the child's account configuration.
 *
 * Unlike the pure softphone classes, this probe is NOT CI-tested: the
 * StunDiscoveryReport it wraps has a package-private constructor for its public
 * address, so no report can be fabricated off-network, and a probe that fakes
 * the STUN server would prove nothing about NAT. It is compile-checked here and
 * verified live on the desktop against a real STUN server — the same standard
 * M7a's JfxMediaSurface was held to. probe() degrades honestly: an unreachable
 * server yields Optional.empty(), never a phantom address.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.net.InetAddress;
import java.util.Optional;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.stack.StunStack;
import org.ice4j.stunclient.NetworkConfigurationDiscoveryProcess;
import org.ice4j.stunclient.StunDiscoveryReport;

/**
 * Discovers the local reflexive (public) address and NAT type through a single
 * ice4j STUN round trip. Stateless: each {@link #probe()} spins up and tears
 * down its own discovery process, because the softphone asks once per call.
 */
public final class Ice4jNatProbe {

    /** A public STUN server to fall back to when none is configured. */
    public static final String DEFAULT_STUN_SERVER = "stun.l.google.com";
    /** The standard STUN port. */
    public static final int DEFAULT_STUN_PORT = 3478;

    /** System property overriding the STUN server host. */
    public static final String HOST_PROPERTY = "lg.wonderland.softphone.stun.host";
    /** System property overriding the STUN server port. */
    public static final String PORT_PROPERTY = "lg.wonderland.softphone.stun.port";

    /**
     * The routable contact the SIP signalling announces: the public host and
     * port the desktop was seen at, plus the NAT classification that tells the
     * softphone whether a direct media path is even possible.
     */
    public record NatReport(String publicHost, int publicPort, String natType) {
    }

    /** The server this probe queries; resolved at call time, not construction. */
    private final String stunHost;
    private final int stunPort;

    public Ice4jNatProbe() {
        this(System.getProperty(HOST_PROPERTY, DEFAULT_STUN_SERVER),
                Integer.getInteger(PORT_PROPERTY, DEFAULT_STUN_PORT));
    }

    public Ice4jNatProbe(String stunHost, int stunPort) {
        this.stunHost = stunHost;
        this.stunPort = stunPort;
    }

    /**
     * Run one discovery round trip.
     *
     * @return the reflexive contact, or {@link Optional#empty()} when the STUN
     *         server was unreachable, the round trip failed, or the NAT type
     *         yields no mapped address (a symmetric firewall)
     */
    public Optional<NatReport> probe() {
        StunStack stack = new StunStack();
        NetworkConfigurationDiscoveryProcess discovery = null;
        try {
            InetAddress address = InetAddress.getByName(stunHost);
            TransportAddress stun = new TransportAddress(address, stunPort, Transport.UDP);
            // the third argument is the "response wait" keepalive address; the
            // public server doubles as it for a one-shot discovery, exactly as
            // stun.jar used its configured server
            discovery = new NetworkConfigurationDiscoveryProcess(stack, stun, stun);
            discovery.start();
            StunDiscoveryReport report = discovery.determineAddress();
            TransportAddress publicAddress = report.getPublicAddress();
            if (publicAddress == null) {
                return Optional.empty();     // NAT reported no mapped address
            }
            return Optional.of(new NatReport(
                    publicAddress.getHostAddress(),
                    publicAddress.getPort(),
                    report.getNatType()));
        } catch (Exception unreachableOrFailed) {
            // DNS, socket, STUN and IO problems all collapse to "no answer" —
            // the caller stays on its local address rather than guessing
            return Optional.empty();
        } finally {
            if (discovery != null) {
                discovery.shutDown();
            }
        }
    }
}
