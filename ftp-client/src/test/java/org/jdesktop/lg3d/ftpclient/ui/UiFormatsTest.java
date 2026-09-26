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
package org.jdesktop.lg3d.ftpclient.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers the pure presentation formatters shared by the queue table and browsers. */
class UiFormatsTest {

    @Test
    @DisplayName("bytes scales with one decimal and treats a negative count as unknown")
    void bytes() {
        assertThat(UiFormats.bytes(-1L)).isEmpty();
        assertThat(UiFormats.bytes(0L)).isEqualTo("0 B");
        assertThat(UiFormats.bytes(512L)).isEqualTo("512 B");
        assertThat(UiFormats.bytes(1023L)).isEqualTo("1023 B");
        assertThat(UiFormats.bytes(1024L)).isEqualTo("1.0 KB");
        assertThat(UiFormats.bytes(1536L)).isEqualTo("1.5 KB");
        assertThat(UiFormats.bytes(1024L * 1024)).isEqualTo("1.0 MB");
        assertThat(UiFormats.bytes(1024L * 1024 * 1024)).isEqualTo("1.0 GB");
    }

    @Test
    @DisplayName("bytes caps at the largest unit rather than overflowing the scale")
    void bytesCapsAtTopUnit() {
        // A petabyte-scale value stays in PB (the last unit) instead of running off the array.
        assertThat(UiFormats.bytes(3L * 1024 * 1024 * 1024 * 1024 * 1024)).endsWith("PB");
    }

    @Test
    @DisplayName("timestamp renders yyyy-MM-dd HH:mm and treats non-positive as unknown")
    void timestamp() {
        assertThat(UiFormats.timestamp(0L)).isEmpty();
        assertThat(UiFormats.timestamp(-1L)).isEmpty();
        // 2001-09-09T01:46:40Z; the exact local rendering varies, so match the shape.
        assertThat(UiFormats.timestamp(1_000_000_000_000L))
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    @Test
    @DisplayName("percent rounds to a whole percentage and clamps to [0,100]")
    void percent() {
        assertThat(UiFormats.percent(0.0)).isEqualTo("0%");
        assertThat(UiFormats.percent(0.5)).isEqualTo("50%");
        assertThat(UiFormats.percent(1.0)).isEqualTo("100%");
        assertThat(UiFormats.percent(0.426)).isEqualTo("43%");
        assertThat(UiFormats.percent(-0.5)).isEqualTo("0%");
        assertThat(UiFormats.percent(1.5)).isEqualTo("100%");
    }
}
