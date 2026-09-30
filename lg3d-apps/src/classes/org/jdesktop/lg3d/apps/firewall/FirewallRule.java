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
package org.jdesktop.lg3d.apps.firewall;

/**
 * Data class representing a single firewall rule.
 */
public class FirewallRule {

    /** Network protocol (tcp, udp, icmp, or any). */
    public final String protocol;
    
    /** Source address or network. */
    public final String source;
    
    /** Destination address or network. */
    public final String destination;
    
    /** Port number or service name. */
    public final String port;
    
    /** Action (ACCEPT, DROP, REJECT, etc.). */
    public final String action;
    
    /** Target chain or table (INPUT, OUTPUT, FORWARD, etc.). */
    public final String target;

    public FirewallRule(String protocol, String source, String destination,
            String port, String action, String target) {
        this.protocol = protocol;
        this.source = source;
        this.destination = destination;
        this.port = port;
        this.action = action;
        this.target = target;
    }
}
