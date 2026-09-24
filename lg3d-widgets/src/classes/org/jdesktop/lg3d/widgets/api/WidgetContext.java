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
package org.jdesktop.lg3d.widgets.api;

import java.util.concurrent.ScheduledExecutorService;
import org.jdesktop.lg3d.wg.Toolkit3D;

/**
 * Runtime services handed to a widget in {@link Widget#init(WidgetContext)}.
 *
 * <p>Provides:</p>
 * <ul>
 *   <li>a shared {@link #scheduler()} - widgets schedule periodic updates here
 *       rather than spawning their own threads, so the desktop uses one timer
 *       pool for all widgets;</li>
 *   <li>the {@link #config()} store for persisting per-instance options;</li>
 *   <li>scene/screen metrics ({@link #screenWidth()}, {@link #screenHeight()})
 *       from {@link Toolkit3D}, in physical meters.</li>
 * </ul>
 *
 * <p>The Linux system backends (temperature, processes, system info) live in
 * {@code org.jdesktop.lg3d.utils.system} as static utility classes
 * ({@code ThermalService}, {@code ProcessService}, {@code SystemInfoService}) and
 * are called directly by widgets - they hold no per-desktop state, so there is
 * nothing to inject here.</p>
 */
public final class WidgetContext {

    private final ScheduledExecutorService scheduler;
    private final WidgetConfigStore config;

    public WidgetContext(ScheduledExecutorService scheduler, WidgetConfigStore config) {
        this.scheduler = scheduler;
        this.config = config;
    }

    /** The shared scheduler used for periodic widget updates. */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /** The persistent widget configuration store. */
    public WidgetConfigStore config() {
        return config;
    }

    /** The 3D toolkit (may be null very early in startup). */
    public Toolkit3D toolkit() {
        try {
            return Toolkit3D.getToolkit3D();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Virtual screen width in meters, or a fallback when not yet available. */
    public float screenWidth() {
        Toolkit3D tk = toolkit();
        float w = (tk != null) ? tk.getScreenWidth() : 0f;
        return (w > 0f) ? w : 1.0f;
    }

    /** Virtual screen height in meters, or a fallback when not yet available. */
    public float screenHeight() {
        Toolkit3D tk = toolkit();
        float h = (tk != null) ? tk.getScreenHeight() : 0f;
        return (h > 0f) ? h : 1.0f;
    }
}
