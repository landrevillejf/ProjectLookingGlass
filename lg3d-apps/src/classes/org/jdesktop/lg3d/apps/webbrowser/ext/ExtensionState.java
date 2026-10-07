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
package org.jdesktop.lg3d.apps.webbrowser.ext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * The persisted enable/grant state of one discovered extension, serialised as
 * one entry of {@code extensions.json} by {@code BrowserStore}. Mutable with
 * Jackson-friendly accessors; permissions are stored by name so an unknown or
 * renamed permission degrades to being dropped rather than failing to parse.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExtensionState {

    private String id = "";
    private boolean enabled;
    private List<String> grantedPermissions = new ArrayList<>();
    private String source = "";

    public String getId() { return id; }
    public void setId(String id) { this.id = (id == null) ? "" : id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getGrantedPermissions() { return grantedPermissions; }
    public void setGrantedPermissions(List<String> grantedPermissions) {
        this.grantedPermissions = (grantedPermissions == null)
                ? new ArrayList<>() : grantedPermissions;
    }

    /** @return where the extension was loaded from, or "" for a built-in. */
    public String getSource() { return source; }
    public void setSource(String source) { this.source = (source == null) ? "" : source; }
}
