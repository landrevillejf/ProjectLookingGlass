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
package org.jdesktop.lg3d.apps.vpn;

/**
 * The outcome of a tunnel leak check. The desktop ships no tunnel stack, so the only
 * honest way to prove a VPN is actually carrying traffic is to compare the public
 * egress IP seen <em>before</em> the tunnel came up (the baseline, captured direct)
 * with the one seen <em>now</em> (fetched through whatever route is live):
 *
 * <ul>
 *   <li>{@link #TUNNELED} - the egress IP changed, so traffic now leaves via the
 *       tunnel;</li>
 *   <li>{@link #LEAK} - the egress IP is unchanged, so traffic is still going out
 *       directly and the tunnel is <em>not</em> protecting it;</li>
 *   <li>{@link #UNREACHABLE} - one of the two IPs could not be fetched, so no
 *       claim is made either way (a missing baseline is honest, never a false
 *       "tunneled").</li>
 * </ul>
 *
 * <p>The classification and the plain-text IP parse are pure, so the whole decision
 * table is unit-testable headless; only the {@code java.net.http} fetch lives in
 * {@link VpnPanel} and is never exercised by a test.</p>
 */
public enum TunnelVerdict {

    /** The egress IP changed - traffic leaves via the tunnel. */
    TUNNELED,
    /** The egress IP is unchanged - traffic is leaking around the tunnel. */
    LEAK,
    /** An IP could not be fetched, so the tunnel cannot be confirmed. */
    UNREACHABLE;

    /**
     * Classifies a leak check by comparing the baseline (pre-tunnel) public IP with
     * the current one. Either side being blank means the check could not run.
     *
     * @param baselineIp the public IP recorded before the tunnel came up (may be null)
     * @param currentIp  the public IP observed now (may be null)
     * @return the verdict, never null
     */
    public static TunnelVerdict classify(String baselineIp, String currentIp) {
        String base = normalize(baselineIp);
        String now = normalize(currentIp);
        if (base.isEmpty() || now.isEmpty()) {
            return UNREACHABLE;
        }
        return base.equals(now) ? LEAK : TUNNELED;
    }

    /**
     * Normalises a public-IP echo body (plain text, possibly with surrounding
     * whitespace or a trailing newline) into a bare token.
     *
     * @param body the response body (may be null)
     * @return the trimmed IP, or an empty string when there is none
     */
    public static String parsePublicIp(String body) {
        return normalize(body);
    }

    /** A short human-readable explanation of this verdict, for the status line. */
    public String describe() {
        return switch (this) {
            case TUNNELED -> "Tunnel verified - your public IP changed, traffic leaves via the tunnel.";
            case LEAK -> "Leak - your public IP is unchanged, traffic is NOT going through the tunnel.";
            case UNREACHABLE -> "Could not reach the IP echo service, so the tunnel is unverified.";
        };
    }

    private static String normalize(String value) {
        return (value == null) ? "" : value.trim();
    }
}
