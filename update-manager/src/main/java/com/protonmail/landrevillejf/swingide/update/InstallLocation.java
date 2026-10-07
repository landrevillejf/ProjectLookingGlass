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

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Locates the root of an installed Project Looking Glass <em>release bundle</em>.
 * <p>
 * lg3d is not a single JAR: {@code :lg3d-core:releaseBundle} ships a directory
 * tree ({@code lib/} with every module + third-party jar, {@code resources/},
 * {@code etc/}, {@code ext/app/}, the {@code lg3d.sh} launcher, {@code README.txt}
 * and {@code VERSION}) launched from a classpath. The {@code update-manager} jar
 * lives at {@code <root>/lib/update-manager-*.jar}, so the install root is the
 * grand-parent of the running jar. This class resolves that root and validates
 * the layout, so the bundle installer knows which tree to replace.
 * </p>
 * <p>
 * The jar-path supplier and the property lookup are injected so the whole
 * detection can be unit tested without running from a real bundle.
 * </p>
 */
@Slf4j
public final class InstallLocation {

    /**
     * System property forcing the install root, for hosts (e.g. the LFS session
     * unit) that know where the bundle lives and do not want it inferred.
     */
    public static final String INSTALL_DIR_PROPERTY = "lg3d.install.dir";

    /** Name of the launcher every release bundle carries at its root. */
    static final String LAUNCHER_NAME = "lg3d.sh";

    /** Name of the classpath directory every release bundle carries. */
    static final String LIB_DIR_NAME = "lib";

    private final Supplier<Path> jarPathSupplier;
    private final Function<String, String> propertyLookup;

    /**
     * Creates a locator inferring the root from the real running jar and the
     * JVM system properties.
     */
    public InstallLocation() {
        this(JarLocator::getCurrentJarPath, System::getProperty);
    }

    /**
     * Full constructor, used by tests.
     *
     * @param jarPathSupplier supplies the running jar (or classes dir) path
     * @param propertyLookup  reads the {@value #INSTALL_DIR_PROPERTY} override
     */
    public InstallLocation(Supplier<Path> jarPathSupplier, Function<String, String> propertyLookup) {
        this.jarPathSupplier = jarPathSupplier;
        this.propertyLookup = propertyLookup;
    }

    /**
     * Resolves the bundle install root, if the desktop runs from one.
     * <p>
     * The explicit {@value #INSTALL_DIR_PROPERTY} property wins when it points at
     * a valid bundle; otherwise the root is derived from the running jar
     * ({@code <root>/lib/<jar>}). A development launch (Gradle classes dir, no
     * bundle layout) yields {@link Optional#empty()}, so callers can fall back on
     * the legacy single-jar path.
     * </p>
     *
     * @return the install root, or empty when not running from a release bundle
     */
    public Optional<Path> detect() {
        Optional<Path> fromProperty = detectFromProperty();
        if (fromProperty.isPresent()) {
            return fromProperty;
        }
        return detectFromJarPath();
    }

    private Optional<Path> detectFromProperty() {
        String configured = propertyLookup.apply(INSTALL_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }

        Path candidate = asPath(configured.trim());
        if (candidate != null && isBundleLayout(candidate)) {
            log.debug("Install root taken from {}: {}", INSTALL_DIR_PROPERTY, candidate);
            return Optional.of(candidate);
        }

        log.warn("{} points at '{}' which is not a release bundle, ignoring it",
            INSTALL_DIR_PROPERTY, configured.trim());
        return Optional.empty();
    }

    private Optional<Path> detectFromJarPath() {
        Path jar;
        try {
            jar = jarPathSupplier.get();
        } catch (RuntimeException e) {
            log.debug("Could not resolve the running jar path: {}", e.getMessage());
            return Optional.empty();
        }

        if (jar == null) {
            return Optional.empty();
        }

        Path lib = jar.getParent();
        Path root = lib != null ? lib.getParent() : null;
        if (root != null && isBundleLayout(root)) {
            log.debug("Install root inferred from the running jar: {}", root);
            return Optional.of(root);
        }

        return Optional.empty();
    }

    private Path asPath(String value) {
        try {
            return Path.of(value);
        } catch (RuntimeException e) {
            log.warn("Invalid {} value '{}': {}", INSTALL_DIR_PROPERTY, value, e.getMessage());
            return null;
        }
    }

    /**
     * Whether {@code root} looks like an extracted release bundle: a {@code lib/}
     * directory next to the {@code lg3d.sh} launcher.
     *
     * @param root candidate install root, may be {@code null}
     * @return {@code true} when both markers are present
     */
    public static boolean isBundleLayout(Path root) {
        return root != null
            && Files.isDirectory(root.resolve(LIB_DIR_NAME))
            && Files.isRegularFile(root.resolve(LAUNCHER_NAME));
    }
}
