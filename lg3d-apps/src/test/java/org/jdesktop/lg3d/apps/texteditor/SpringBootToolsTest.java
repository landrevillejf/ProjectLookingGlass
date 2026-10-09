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
 * the Spring Boot 2+ kebab-case normaliser, the ${...} placeholder scanner,
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
    @DisplayName("toolbarContributions list four actions with accelerators")
    void toolbarContributions() {
        var c = new SpringBootTools().toolbarContributions();
        assertEquals(4, c.size());
        assertEquals("Properties to YAML", c.get(0).getLabel());
        assertEquals("control alt S", c.get(0).getAccelerator());
        assertEquals("control alt V", c.get(1).getAccelerator());
        assertEquals("control alt 8", c.get(2).getAccelerator());
        assertEquals("control alt 9", c.get(3).getAccelerator());
    }

    @Test
    @DisplayName("normalizeKeys rewrites camelCase and acronym humps")
    void normalizeKeysCamelAndAcronym() {
        assertEquals("server.servlet.context-path=/api\n",
                SpringBootTools.normalizeKeys("server.servlet.contextPath=/api\n"));
        assertEquals("http-server=on\n",
                SpringBootTools.normalizeKeys("HTTPServer=on\n"));
    }

    @Test
    @DisplayName("normalizeKeys folds underscores and uppercase; values untouched")
    void normalizeKeysUnderscoreAndValue() {
        assertEquals("server-port=8080\n",
                SpringBootTools.normalizeKeys("SERVER_PORT=8080\n"));
        // Value side is preserved byte-for-byte; the key side folds both the
        // camelCase hump and the two `_` separators to single hyphens.
        assertEquals("my-prop-keep-me=KEEP_ME\n",
                SpringBootTools.normalizeKeys("myProp_KEEP_ME=KEEP_ME\n"));
        // Idempotent: already-normalised input round-trips unchanged.
        assertEquals("a.b.c-d=1\n",
                SpringBootTools.normalizeKeys("a.b.c-d=1\n"));
    }

    @Test
    @DisplayName("normalizeKeys leaves comments, blanks and non-property lines alone")
    void normalizeKeysGuards() {
        assertEquals(null, SpringBootTools.normalizeKeys(null));
        assertEquals("", SpringBootTools.normalizeKeys(""));
        assertEquals("# HEADER\n\nnot-a-pair\nserver.port=1\n",
                SpringBootTools.normalizeKeys("# HEADER\n\nnot-a-pair\nserver.port=1\n"));
    }

    @Test
    @DisplayName("listPlaceholders appends a deduplicated summary block")
    void listPlaceholdersAppend() {
        String src = "url=jdbc:${host}:${port}/${db}\n"
                + "alias=${host}\n";
        String out = SpringBootTools.listPlaceholders(src);
        assertTrue(out.startsWith(src), "original text preserved");
        assertTrue(out.contains("\n# Referenced placeholders:\n"), out);
        assertTrue(out.contains("#   host\n"), out);
        assertTrue(out.contains("#   port\n"), out);
        assertTrue(out.contains("#   db\n"), out);
        // `host` appears twice in the source but once in the summary.
        int hostCount = out.split("#   host", -1).length - 1;
        assertEquals(1, hostCount, "deduplicated: " + out);
    }

    @Test
    @DisplayName("listPlaceholders strips :default suffix and handles nesting")
    void listPlaceholdersDefaultAndNest() {
        String out = SpringBootTools.listPlaceholders("p=${DEFAULT_PORT:8080}\n");
        assertTrue(out.contains("#   DEFAULT_PORT\n"), out);
        // Nested ${a-${b}}: outer name is `a`, inner is not surfaced separately.
        out = SpringBootTools.listPlaceholders("q=${a-${b}}\n");
        assertTrue(out.contains("#   a-${b}\n"), out);
    }

    @Test
    @DisplayName("listPlaceholders is idempotent and safe with no placeholders")
    void listPlaceholdersIdempotent() {
        String src = "foo=bar\nbaz=${qux}\n";
        String once = SpringBootTools.listPlaceholders(src);
        String twice = SpringBootTools.listPlaceholders(once);
        assertEquals(once, twice, "second run is a no-op (marker detected)");
        // No placeholders -> unchanged.
        assertEquals("x=1\ny=2\n", SpringBootTools.listPlaceholders("x=1\ny=2\n"));
        // Null / empty pass through.
        assertEquals(null, SpringBootTools.listPlaceholders(null));
        assertEquals("", SpringBootTools.listPlaceholders(""));
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
