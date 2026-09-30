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

import java.util.List;
import java.util.ArrayList;

/**
 * Data class representing the current firewall status and rules.
 */
public class FirewallStatus {

    /** Whether the firewall is currently enabled. */
    public final boolean enabled;
    
    /** List of active firewall rules. */
    public final List<FirewallRule> rules;

    public FirewallStatus(boolean enabled, List<FirewallRule> rules) {
        this.enabled = enabled;
        this.rules = rules != null ? rules : new ArrayList<>();
    }
}
