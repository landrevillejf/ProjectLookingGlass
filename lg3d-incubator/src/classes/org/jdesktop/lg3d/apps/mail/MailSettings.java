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
package org.jdesktop.lg3d.apps.mail;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * The user-customizable appearance and behaviour of the mail client, persisted
 * under the {@code /mail/settings} {@link Preferences} node.
 *
 * <p>{@link #load()} reads the stored values (falling back to sensible defaults on
 * first run); mutating the setters and calling {@link #save()} writes them back.
 * The 2D {@link MailPanel} re-reads these live after the settings dialog closes so
 * a change (theme, fonts, density, reading-pane position, sort, auto-check,
 * HTML rendering) takes effect immediately.</p>
 */
public final class MailSettings {

    /** Absolute user-preferences path holding the settings keys. */
    public static final String ROOT = "/mail/settings";

    /** Where the reading pane sits relative to the message list. */
    public enum ReadingPanePosition {
        RIGHT, BOTTOM, HIDDEN
    }

    /** Overall color theme. */
    public enum Theme {
        LIGHT, DARK
    }

    /** Row spacing in the message list. */
    public enum Density {
        COMPACT, COMFORTABLE
    }

    /** The column the list sorts by on open. */
    public enum SortColumn {
        DATE, FROM, SUBJECT
    }

    private static final Logger logger =
            Logger.getLogger(MailSettings.class.getName());

    // Defaults.
    private String listFontFamily = "SansSerif";
    private int listFontSize = 13;
    private String readerFontFamily = "SansSerif";
    private int readerFontSize = 14;
    private Theme theme = Theme.LIGHT;
    private int accentColor = 0x2A6FDB;      // a calm blue
    private Density density = Density.COMFORTABLE;
    private ReadingPanePosition readingPanePosition = ReadingPanePosition.RIGHT;
    private SortColumn sortColumn = SortColumn.DATE;
    private boolean sortDescending = true;    // newest first
    private int checkIntervalMinutes = 10;    // 0 disables auto-check
    private boolean confirmOnDelete = true;
    private boolean renderHtml = false;       // security: plain text by default

    private MailSettings() {
    }

    /** Reads the persisted settings, or the defaults on first run. */
    public static MailSettings load() {
        MailSettings s = new MailSettings();
        try {
            Preferences p = Preferences.userRoot().node(ROOT);
            s.listFontFamily = p.get("listFontFamily", s.listFontFamily);
            s.listFontSize = p.getInt("listFontSize", s.listFontSize);
            s.readerFontFamily = p.get("readerFontFamily", s.readerFontFamily);
            s.readerFontSize = p.getInt("readerFontSize", s.readerFontSize);
            s.theme = enumOr(Theme.class, p.get("theme", null), s.theme);
            s.accentColor = p.getInt("accentColor", s.accentColor);
            s.density = enumOr(Density.class, p.get("density", null), s.density);
            s.readingPanePosition = enumOr(ReadingPanePosition.class,
                    p.get("readingPanePosition", null), s.readingPanePosition);
            s.sortColumn = enumOr(SortColumn.class, p.get("sortColumn", null),
                    s.sortColumn);
            s.sortDescending = p.getBoolean("sortDescending", s.sortDescending);
            s.checkIntervalMinutes = p.getInt("checkIntervalMinutes",
                    s.checkIntervalMinutes);
            s.confirmOnDelete = p.getBoolean("confirmOnDelete", s.confirmOnDelete);
            s.renderHtml = p.getBoolean("renderHtml", s.renderHtml);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error reading mail settings; using defaults", e);
        }
        return s;
    }

    /** Writes every setting back to the preferences node. */
    public void save() {
        try {
            Preferences p = Preferences.userRoot().node(ROOT);
            p.put("listFontFamily", listFontFamily);
            p.putInt("listFontSize", listFontSize);
            p.put("readerFontFamily", readerFontFamily);
            p.putInt("readerFontSize", readerFontSize);
            p.put("theme", theme.name());
            p.putInt("accentColor", accentColor);
            p.put("density", density.name());
            p.put("readingPanePosition", readingPanePosition.name());
            p.put("sortColumn", sortColumn.name());
            p.putBoolean("sortDescending", sortDescending);
            p.putInt("checkIntervalMinutes", checkIntervalMinutes);
            p.putBoolean("confirmOnDelete", confirmOnDelete);
            p.putBoolean("renderHtml", renderHtml);
            p.flush();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error saving mail settings", e);
        }
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value,
            E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getListFontFamily() {
        return listFontFamily;
    }

    public void setListFontFamily(String f) {
        this.listFontFamily = (f == null || f.isEmpty()) ? "SansSerif" : f;
    }

    public int getListFontSize() {
        return listFontSize;
    }

    public void setListFontSize(int size) {
        this.listFontSize = clamp(size, 8, 32);
    }

    public String getReaderFontFamily() {
        return readerFontFamily;
    }

    public void setReaderFontFamily(String f) {
        this.readerFontFamily = (f == null || f.isEmpty()) ? "SansSerif" : f;
    }

    public int getReaderFontSize() {
        return readerFontSize;
    }

    public void setReaderFontSize(int size) {
        this.readerFontSize = clamp(size, 8, 40);
    }

    public Theme getTheme() {
        return theme;
    }

    public void setTheme(Theme theme) {
        this.theme = (theme == null) ? Theme.LIGHT : theme;
    }

    public int getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(int rgb) {
        this.accentColor = rgb & 0xFFFFFF;
    }

    public Density getDensity() {
        return density;
    }

    public void setDensity(Density density) {
        this.density = (density == null) ? Density.COMFORTABLE : density;
    }

    public ReadingPanePosition getReadingPanePosition() {
        return readingPanePosition;
    }

    public void setReadingPanePosition(ReadingPanePosition pos) {
        this.readingPanePosition = (pos == null)
                ? ReadingPanePosition.RIGHT : pos;
    }

    public SortColumn getSortColumn() {
        return sortColumn;
    }

    public void setSortColumn(SortColumn sortColumn) {
        this.sortColumn = (sortColumn == null) ? SortColumn.DATE : sortColumn;
    }

    public boolean isSortDescending() {
        return sortDescending;
    }

    public void setSortDescending(boolean sortDescending) {
        this.sortDescending = sortDescending;
    }

    public int getCheckIntervalMinutes() {
        return checkIntervalMinutes;
    }

    public void setCheckIntervalMinutes(int minutes) {
        this.checkIntervalMinutes = clamp(minutes, 0, 1440);
    }

    public boolean isConfirmOnDelete() {
        return confirmOnDelete;
    }

    public void setConfirmOnDelete(boolean confirmOnDelete) {
        this.confirmOnDelete = confirmOnDelete;
    }

    public boolean isRenderHtml() {
        return renderHtml;
    }

    public void setRenderHtml(boolean renderHtml) {
        this.renderHtml = renderHtml;
    }

    /** Row height in pixels implied by the density setting. */
    public int rowHeight() {
        return density == Density.COMPACT ? 22 : 34;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
