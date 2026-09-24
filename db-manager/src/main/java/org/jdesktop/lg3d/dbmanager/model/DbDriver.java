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
package org.jdesktop.lg3d.dbmanager.model;

import java.util.Objects;

/**
 * A JDBC driver description: the metadata the UI needs to let a user build a
 * connection URL without hand-typing it, and the driver class to load.
 *
 * <p>Instances are immutable value objects. The built-in drivers ship with the
 * module (their jars are on the runtime classpath); a {@code custom} driver is
 * one the user added, pointing at a driver class and optionally an external jar
 * that {@code DriverRegistry} loads on demand.</p>
 */
public final class DbDriver {

    private final String id;
    private final String label;
    private final String driverClass;
    private final String urlTemplate;
    private final boolean embedded;
    private final boolean custom;
    private final String jarPath;

    /**
     * Creates a driver description.
     *
     * @param id           stable identifier stored on a {@link ConnectionProfile}
     * @param label        human-readable name shown in the UI
     * @param driverClass  fully-qualified JDBC driver class (may be empty)
     * @param urlTemplate  a sample JDBC URL, or empty when not applicable
     * @param embedded     {@code true} for file/in-process databases (SQLite, H2)
     * @param custom       {@code true} when the user supplied this driver
     * @param jarPath      external jar to load, or {@code null} when on the classpath
     */
    public DbDriver(String id, String label, String driverClass, String urlTemplate,
                    boolean embedded, boolean custom, String jarPath) {
        this.id = Objects.requireNonNull(id, "id");
        this.label = Objects.requireNonNullElse(label, id);
        this.driverClass = Objects.requireNonNullElse(driverClass, "");
        this.urlTemplate = Objects.requireNonNullElse(urlTemplate, "");
        this.embedded = embedded;
        this.custom = custom;
        this.jarPath = jarPath;
    }

    /** @return a built-in (classpath) driver. */
    public static DbDriver builtin(String id, String label, String driverClass,
                                   String urlTemplate, boolean embedded) {
        return new DbDriver(id, label, driverClass, urlTemplate, embedded, false, null);
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public String getUrlTemplate() {
        return urlTemplate;
    }

    public boolean isEmbedded() {
        return embedded;
    }

    public boolean isCustom() {
        return custom;
    }

    /** @return the external jar path, or {@code null} when the driver is on the classpath. */
    public String getJarPath() {
        return jarPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DbDriver other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return label;
    }
}
