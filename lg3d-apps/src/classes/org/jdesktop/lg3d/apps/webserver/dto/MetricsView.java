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
package org.jdesktop.lg3d.apps.webserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A point-in-time JVM snapshot from {@code GET /api/server/metrics}, and the
 * {@code metrics} member of {@link ServerInfoView}.
 *
 * <p>A client-side mirror of the server's own {@code MetricsView} record; the
 * console never imports a server class, so the wire shape is restated here.
 * Unknown fields are ignored so a newer server can enrich the snapshot without
 * breaking an older console.</p>
 *
 * @param heapUsedBytes       committed heap in use
 * @param heapMaxBytes        maximum heap the JVM will grow to
 * @param threadCount         live threads
 * @param peakThreadCount     high-water mark of live threads
 * @param systemLoadAverage   the OS load average, or a negative value if unavailable
 * @param availableProcessors cores visible to the JVM
 * @param uptimeMillis        how long the server JVM has been up
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetricsView(
        long heapUsedBytes,
        long heapMaxBytes,
        int threadCount,
        int peakThreadCount,
        double systemLoadAverage,
        int availableProcessors,
        long uptimeMillis) {

    /** The fraction of the maximum heap in use, in {@code [0, 1]}. */
    public double heapFraction() {
        return heapMaxBytes <= 0L ? 0.0d : (double) heapUsedBytes / (double) heapMaxBytes;
    }
}
