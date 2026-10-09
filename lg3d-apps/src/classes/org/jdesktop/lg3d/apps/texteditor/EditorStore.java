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
package org.jdesktop.lg3d.apps.texteditor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.apps.texteditor.ext.ExtensionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON persistence for the text editor: the extension enable/grant state.
 *
 * <p>Configuration lives under {@code ~/.lg3d/texteditor/} by default; override
 * with the system property {@link #DIR_PROPERTY}. Every read is defensive: a
 * missing, empty or corrupt file yields the empty/default value and is logged,
 * never thrown, so a damaged profile can never stop the editor from opening.</p>
 *
 * <p>This class holds no AWT references and does no rendering, so it exercises
 * identically in a headless unit test and in the desktop.</p>
 */
public final class EditorStore {

    private static final Logger LOG = LoggerFactory.getLogger(EditorStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.texteditor.dir";

    static final String EXTENSIONS_FILE = "extensions.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public EditorStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public EditorStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.texteditor.dir} when set, else {@code ~/.lg3d/texteditor}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "texteditor");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    // ------------------------------------------------------------------
    // Extensions (enable / permission-grant state)
    // ------------------------------------------------------------------

    /** @return the persisted extension states, or an empty list on any error. */
    public List<ExtensionState> loadExtensionStates() {
        return readList(EXTENSIONS_FILE, new TypeReference<List<ExtensionState>>() { });
    }

    /** Persists the extension enable/grant states. */
    public void saveExtensionStates(List<ExtensionState> states) {
        write(EXTENSIONS_FILE, nonNull(states));
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private <T> List<T> readList(String fileName, TypeReference<List<T>> type) {
        Path file = configDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<T> list = mapper.readValue(file.toFile(), type);
            return (list == null) ? new ArrayList<>() : list;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting empty", file, e);
            return new ArrayList<>();
        }
    }

    private void write(String fileName, Object value) {
        try {
            Files.createDirectories(configDir);
            mapper.writeValue(configDir.resolve(fileName).toFile(), value);
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not write {}", fileName, e);
        }
    }

    private static <T> List<T> nonNull(List<T> list) {
        return (list == null) ? new ArrayList<>() : list;
    }
}
