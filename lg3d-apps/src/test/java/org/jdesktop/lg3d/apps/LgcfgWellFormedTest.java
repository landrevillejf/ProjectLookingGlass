/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.StringReader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

/**
 * Asserts every shipped {@code .lgcfg} start-menu descriptor is well-formed XML.
 *
 * <p>Both discovery paths parse these files with a conformant XML parser - the
 * 3D desktop's {@code DefaultConfigControl} (via {@code java.beans.XMLDecoder})
 * and the 2D desktop's {@code Desktop2DMenuConfig} (via {@code DocumentBuilder})
 * - and both <em>silently skip</em> a descriptor that fails to parse: the menu
 * item simply never appears, with only a WARNING on {@code lg.config} /
 * {@code lg.desktop2d}. The {@code startmenu-audit.mnd} gate cannot catch this
 * because it deliberately greps the descriptor text rather than parsing it.</p>
 *
 * <p>The classic trap is prose in an XML comment: {@code --} is illegal inside a
 * comment, so a sentence such as "the classpath -- lg3d-apps does not depend on
 * it" makes the whole file unparseable and the app invisible in both desktops.
 * This test parses every descriptor so that mistake fails the build instead.</p>
 */
class LgcfgWellFormedTest {

    /**
     * The source directories holding {@code .lgcfg} descriptors, resolved
     * relative to the {@code lg3d-apps} module directory (Gradle's test working
     * directory). The sibling-module paths are scanned only when present, so the
     * test still runs against a lone {@code lg3d-apps} checkout.
     */
    private static final String[] CONFIG_DIRS = {
        "src/config",
        "../lg3d-core/src/etc/lg3d",
        "../lg3d-incubator/src/config",
    };

    @Test
    @DisplayName("every shipped .lgcfg descriptor is well-formed XML")
    void everyDescriptorIsWellFormedXml() throws Exception {
        List<Path> descriptors = collectDescriptors();
        // If nothing was found the layout is unexpected (e.g. an exotic working
        // directory); skip rather than pass vacuously or fail spuriously.
        assumeTrue(!descriptors.isEmpty(), "no .lgcfg descriptors found to validate");

        List<String> failures = new ArrayList<>();
        for (Path descriptor : descriptors) {
            try {
                newParser().parse(descriptor.toFile());
            } catch (Exception e) {
                failures.add(descriptor + " -> " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(),
                "Malformed .lgcfg descriptor(s) - these are silently dropped by "
                + "both the 3D (XMLDecoder) and 2D (Desktop2DMenuConfig) start "
                + "menus, so the app never appears. A '--' inside an XML comment "
                + "is the usual cause:\n" + String.join("\n", failures));
    }

    /** Every {@code *.lgcfg} under the configured source directories. */
    private static List<Path> collectDescriptors() throws Exception {
        List<Path> found = new ArrayList<>();
        for (String dir : CONFIG_DIRS) {
            Path path = Paths.get(dir);
            if (!Files.isDirectory(path)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path, "*.lgcfg")) {
                for (Path p : ds) {
                    found.add(p);
                }
            }
        }
        found.sort(Path::compareTo);
        return found;
    }

    /**
     * A parser matching {@code Desktop2DMenuConfig}'s: non-validating and never
     * reaching out for a DTD (these are 2006-era documents), so the only thing
     * that can fail is genuine XML well-formedness.
     */
    private static DocumentBuilder newParser() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) ->
                new InputSource(new StringReader("")));
        return builder;
    }
}
