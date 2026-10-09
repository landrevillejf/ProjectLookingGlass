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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.texteditor.ext.DocumentContext;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the bundled {@link SpringBootTools} extension: the pure
 * properties &harr; YAML conversions (nesting, sequences, comments, round-trip),
 * the SPI contract and the whole-document action through a {@link DocumentContext}.
 */
class SpringBootToolsTest {

    @Test
    @DisplayName("propertiesToYaml nests dotted keys")
    void propertiesToYaml() {
        String props = "server.port=8080\nserver.servlet.context-path=/app\n";
        String yaml = SpringBootTools.propertiesToYaml(props);
        assertEquals("server:\n  port: 8080\n  servlet:\n    context-path: /app\n", yaml);
    }

    @Test
    @DisplayName("yamlToProperties flattens nested maps and indexes sequences")
    void yamlToProperties() {
        String yaml = "server:\n  port: 8080\n  servlet:\n    context-path: /app\n";
        assertEquals("server.port=8080\nserver.servlet.context-path=/app\n",
                SpringBootTools.yamlToProperties(yaml));
        assertEquals("servers[0]=one\nservers[1]=two\n",
                SpringBootTools.yamlToProperties("servers:\n  - one\n  - two\n"));
    }

    @Test
    @DisplayName("properties -> yaml -> properties round-trips")
    void roundTrip() {
        String props = "a.b=1\na.c=2\n";
        assertEquals(props, SpringBootTools.yamlToProperties(
                SpringBootTools.propertiesToYaml(props)));
    }

    @Test
    @DisplayName("comments are preserved and null/empty pass through")
    void commentsAndGuards() {
        assertTrue(SpringBootTools.propertiesToYaml("# note\na.b=1\n").startsWith("# note\n"));
        assertEquals(null, SpringBootTools.propertiesToYaml(null));
        assertEquals("", SpringBootTools.yamlToProperties(""));
    }

    @Test
    @DisplayName("manifest declares the Spring category and write/toolbar permissions")
    void manifest() {
        SpringBootTools tools = new SpringBootTools();
        TextEditorManifest m = tools.manifest();
        assertNotNull(m);
        assertEquals("lg3d.spring-boot-tools", m.getId());
        assertEquals("Spring Boot Tools", m.getName());
        assertEquals("Spring", tools.category());
        assertTrue(m.getPermissions().contains(TextEditorPermission.WRITE));
        assertTrue(m.getPermissions().contains(TextEditorPermission.TOOLBAR));
    }

    @Test
    @DisplayName("toolbarContributions list two actions with accelerators")
    void toolbarContributions() {
        var c = new SpringBootTools().toolbarContributions();
        assertEquals(2, c.size());
        assertEquals("Properties to YAML", c.get(0).getLabel());
        assertEquals("control alt S", c.get(0).getAccelerator());
        assertEquals("control alt V", c.get(1).getAccelerator());
    }

    @Test
    @DisplayName("the properties-to-yaml action rewrites the whole document")
    void convertAction() {
        SpringBootTools tools = new SpringBootTools();
        String[] doc = {"a.b=1\n"};
        Consumer<String> textMutator = s -> doc[0] = s;
        tools.onDocumentOpened(new DocumentContext(null, null, doc[0],
                "", textMutator, s -> { }));
        tools.toolbarContributions().get(0).getAction().run();
        assertEquals("a:\n  b: 1\n", doc[0]);
    }
}
