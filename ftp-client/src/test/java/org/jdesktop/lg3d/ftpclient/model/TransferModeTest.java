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
package org.jdesktop.lg3d.ftpclient.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers the {@link TransferMode} enum's labels and name resolution. */
class TransferModeTest {

    @Test
    @DisplayName("display names and toString are the friendly labels")
    void labels() {
        assertThat(TransferMode.PASSIVE.getDisplayName()).isEqualTo("Passive");
        assertThat(TransferMode.ACTIVE.toString()).isEqualTo("Active");
    }

    @Test
    @DisplayName("fromName matches name or label, case-insensitively")
    void fromName() {
        assertThat(TransferMode.fromName("passive")).isEqualTo(TransferMode.PASSIVE);
        assertThat(TransferMode.fromName("ACTIVE")).isEqualTo(TransferMode.ACTIVE);
        assertThat(TransferMode.fromName("Passive")).isEqualTo(TransferMode.PASSIVE);
        assertThat(TransferMode.fromName("sideways")).isNull();
        assertThat(TransferMode.fromName(null)).isNull();
    }
}
