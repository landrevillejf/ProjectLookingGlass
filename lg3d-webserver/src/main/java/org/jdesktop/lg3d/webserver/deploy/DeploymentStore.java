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
package org.jdesktop.lg3d.webserver.deploy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Persists the deployment table as JSON so a server restart remembers what it was
 * supervising -- the same {@code ~/.lg3d/<tool>} convention the desktop's
 * db-manager uses.
 *
 * <p>The directory resolves in order: the {@code webserver.deploy-dir} property,
 * then the {@code lg3d.webserver.dir} system property (which the tests set to a
 * temp dir so they never touch the real home), then {@code ~/.lg3d/webserver}.
 * Reads are forgiving -- a missing, empty or corrupt file yields an empty table
 * rather than failing the boot -- and writes are best effort, logging rather than
 * throwing so a read-only home cannot take the API down.</p>
 */
@Component
public class DeploymentStore {

    /** System property that overrides the state directory (used by the tests). */
    public static final String DIR_PROPERTY = "lg3d.webserver.dir";

    private static final String FILE_NAME = "deployments.json";
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentStore.class);

    private final Path dir;
    private final ObjectMapper mapper;

    /** Production constructor: resolves the directory from configuration. */
    @Autowired
    public DeploymentStore(WebServerProperties properties) {
        this(resolveDir(properties));
    }

    /** Test constructor: pins the state directory to an explicit path. */
    DeploymentStore(Path dir) {
        this.dir = dir;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                // Tolerate fields written by an older/newer schema so a restart never
                // fails to read its own state (mirrors Spring Boot's lenient default).
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static Path resolveDir(WebServerProperties properties) {
        String configured = properties == null ? null : properties.getDeployDir();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String system = System.getProperty(DIR_PROPERTY);
        if (system != null && !system.isBlank()) {
            return Path.of(system);
        }
        return Path.of(System.getProperty("user.home"), ".lg3d", "webserver");
    }

    /** @return the {@code deployments.json} file this store reads and writes. */
    Path file() {
        return dir.resolve(FILE_NAME);
    }

    /** @return the persisted deployments, or an empty list when none/corrupt. */
    List<Deployment> load() {
        Path file = file();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            Deployment[] saved = mapper.readValue(file.toFile(), Deployment[].class);
            return new ArrayList<>(Arrays.asList(saved));
        } catch (IOException e) {
            LOG.warn("could not read {}; starting with an empty table: {}", file, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Persists the whole table, best effort. */
    void save(Collection<Deployment> deployments) {
        try {
            Files.createDirectories(dir);
            mapper.writeValue(file().toFile(), deployments);
        } catch (IOException e) {
            LOG.warn("could not write {}; deployment state will not survive a restart: {}",
                    file(), e.getMessage());
        }
    }
}
