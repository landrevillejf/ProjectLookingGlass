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
package org.jdesktop.lg3d.utils.schedule;

import java.net.URL;
import java.time.LocalTime;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.scenemanager.utils.background.SimpleImageBackground;
import org.jdesktop.lg3d.scenemanager.utils.event.BackgroundChangeRequestEvent;
import org.jdesktop.lg3d.scenemanager.utils.globallights.StandardGlobalLights;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.wg.event.LgEventConnector;

/**
 * Service that applies the daylight/nightlight schedule: it can switch the
 * wallpaper and/or re-tint the scene lighting, each an independent opt-in
 * ({@link DesktopConfig#isWallpaperScheduleEnabled()} and
 * {@link DesktopConfig#isLightingScheduleEnabled()}). It checks every minute
 * whether the clock has crossed a transition and applies whatever is enabled.
 */
public final class ScheduleService {

    private static final Logger logger = Logger.getLogger("lg.utils.schedule");
    private static final String BG_DIR = "resources/images/background";
    private static final long CHECK_INTERVAL_MS = 60_000; // Check every minute

    private static volatile ScheduleService instance;
    private final Timer timer;
    private String lastAppliedWallpaper;
    /** Last lighting blend applied, so turning the schedule off can reset it. */
    private float lastLightingFactor = 0.0f;

    private ScheduleService() {
        this.timer = new Timer("LG3D-ScheduleService", true);
    }

    /** The shared instance, starting the background timer on first access. */
    public static ScheduleService get() {
        ScheduleService s = instance;
        if (s == null) {
            synchronized (ScheduleService.class) {
                s = instance;
                if (s == null) {
                    s = new ScheduleService();
                    instance = s;
                    s.start();
                }
            }
        }
        return s;
    }

    /** Starts the background timer that checks for schedule transitions. */
    private void start() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkAndApply();
            }
        }, 0, CHECK_INTERVAL_MS);
        logger.info("ScheduleService started: checking every minute");
    }

    /** Stops the background timer. */
    public void stop() {
        timer.cancel();
        logger.info("ScheduleService stopped");
    }

    /**
     * Checks the current time against the configured schedule and applies
     * the appropriate wallpaper if a transition has occurred.
     */
    private void checkAndApply() {
        DesktopConfig cfg = DesktopConfig.get();
        LocalTime now = LocalTime.now();

        // The wallpaper and lighting schedules are independent opt-ins: each is
        // applied only when its own toggle is on, so either can run alone.
        if (cfg.isWallpaperScheduleEnabled()) {
            String targetWallpaper = resolveWallpaper(now, cfg);
            if (!targetWallpaper.equals(lastAppliedWallpaper)) {
                applyWallpaper(targetWallpaper);
                lastAppliedWallpaper = targetWallpaper;
            }
        }

        if (cfg.isLightingScheduleEnabled()) {
            // Re-tint every tick: the day/night blend factor moves gradually
            // across each transition ramp, so it is applied unconditionally
            // rather than only on a discrete change like the wallpaper. The
            // one-minute tick steps a 30-minute ramp in ~30 small increments,
            // which reads as a smooth fade without a second timer.
            float factor = DayNightCurve.dayFactor(
                    now,
                    LocalTime.of(cfg.getDaylightHour(), cfg.getDaylightMinute()),
                    LocalTime.of(cfg.getNightlightHour(), cfg.getNightlightMinute()),
                    cfg.getRampMinutes());
            applyDayNight(factor);
            lastLightingFactor = factor;
        } else if (lastLightingFactor != 0.0f) {
            // Lighting was just switched off: restore neutral daylight once so
            // the scene (or 2D veil) is not left stuck at night.
            applyDayNight(0.0f);
            lastLightingFactor = 0.0f;
        }
    }

    /**
     * Applies the day/night blend {@code factor} (0 = full daylight, 1 = full
     * night) to the running desktop: the 2D night veil on the conventional
     * desktop, the 3D scene-light rig otherwise. Both paths are no-ops when the
     * corresponding desktop is not running, so this is safe to call headlessly.
     */
    void applyDayNight(float factor) {
        if (Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            Desktop2D.setNightTint(factor);
            return;
        }
        StandardGlobalLights lights = StandardGlobalLights.live();
        if (lights != null) {
            try {
                lights.applyDayNight(factor);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to apply day/night scene lighting", e);
            }
        }
    }

    /**
     * Returns the wallpaper filename that should be showing at {@code now}:
     * the daylight wallpaper while the clock is inside the daylight window,
     * otherwise the nightlight wallpaper. Pure and deterministic (the clock is
     * injected) so the schedule decision is unit-testable headlessly.
     */
    static String resolveWallpaper(LocalTime now, DesktopConfig cfg) {
        LocalTime daylightTime = LocalTime.of(cfg.getDaylightHour(), cfg.getDaylightMinute());
        LocalTime nightlightTime = LocalTime.of(cfg.getNightlightHour(), cfg.getNightlightMinute());
        return isTimeBetween(now, daylightTime, nightlightTime)
                ? cfg.getDaylightWallpaper()
                : cfg.getNightlightWallpaper();
    }

    /**
     * Returns true if {@code time} is between {@code start} and {@code end},
     * handling the case where the interval crosses midnight.
     */
    static boolean isTimeBetween(LocalTime time, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            // Normal interval (e.g., 7:00 to 20:00)
            return !time.isBefore(start) && !time.isAfter(end);
        } else {
            // Interval crosses midnight (e.g., 20:00 to 7:00)
            return !time.isBefore(start) || !time.isAfter(end);
        }
    }

    /** Applies the specified wallpaper filename to the desktop. */
    void applyWallpaper(String wallpaperFilename) {
        if (wallpaperFilename == null || wallpaperFilename.isEmpty()) {
            logger.fine("No wallpaper configured for current period, skipping");
            return;
        }

        URL url = getClass().getClassLoader().getResource(BG_DIR + "/" + wallpaperFilename);
        if (url == null) {
            logger.warning("Wallpaper not found: " + wallpaperFilename);
            return;
        }

        // On the conventional 2D desktop, hand the image straight to Desktop2D
        if (Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            try {
                Desktop2D.setWallpaper(url);
                logger.info("Applied wallpaper (2D desktop): " + wallpaperFilename);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to apply wallpaper on 2D desktop", e);
            }
            return;
        }

        // On the 3D desktop, post a BackgroundChangeRequestEvent
        try {
            SimpleImageBackground background = new SimpleImageBackground(url);
            LgEventConnector.getLgEventConnector().postEvent(
                    new BackgroundChangeRequestEvent(background), null);
            logger.info("Applied wallpaper (3D desktop): " + wallpaperFilename);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to apply wallpaper on 3D desktop", e);
        }
    }

    /** Forces an immediate check and application of the scheduled wallpaper. */
    public void checkNow() {
        checkAndApply();
    }
}
