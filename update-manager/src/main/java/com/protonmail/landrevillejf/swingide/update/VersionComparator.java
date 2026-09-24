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
package com.protonmail.landrevillejf.swingide.update;

public final class VersionComparator {
    
    private VersionComparator() {
        // Utility class
    }
    
    public static boolean isNewerVersion(String latest, String current) {
        Version latestVer = Version.parse(latest);
        Version currentVer = Version.parse(current);
        return latestVer.compareTo(currentVer) > 0;
    }
    
    public static boolean isSameVersion(String v1, String v2) {
        Version ver1 = Version.parse(v1);
        Version ver2 = Version.parse(v2);
        return ver1.compareTo(ver2) == 0;
    }
    
    public static boolean isOlderVersion(String v1, String v2) {
        Version ver1 = Version.parse(v1);
        Version ver2 = Version.parse(v2);
        return ver1.compareTo(ver2) < 0;
    }
}
