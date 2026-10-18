/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JList;
import javax.swing.JTextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ManageJdksCard}, the central JDK manager surface:
 * every button routes exactly one {@link ManageJdksCard.Host} callback, and the
 * list reflects {@link JdkRegistry#list()} over a temp store. No chooser, dialog
 * or network is ever constructed here; the host implementation (its download
 * thread and file choosers) is covered by the panel tests.
 */
class ManageJdksCardTest {

    /** Records every callback the card makes, in order. */
    private static final class RecordingHost implements ManageJdksCard.Host {
        final List<String> calls = new ArrayList<>();

        @Override
        public void downloadJdk(String version) {
            calls.add("download:" + version);
        }

        @Override
        public void registerJdkFolder() {
            calls.add("register");
        }

        @Override
        public void removeJdk(String id) {
            calls.add("remove:" + id);
        }

        @Override
        public void closeCard() {
            calls.add("back");
        }
    }

    private static Path makeJdk(Path home, String version) throws IOException {
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin").resolve("java"), "#!/bin/sh\n");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
        return home;
    }

    /** A manager over a temp store holding two JDKs (21 and 25). */
    private static JdkRegistry registryWithTwo(Path base) throws IOException {
        makeJdk(base.resolve("jdks").resolve("jdk-21.0.4+7"), "21.0.4");
        makeJdk(base.resolve("jdks").resolve("jdk-25+36"), "25");
        return new JdkRegistry(base, (url, dest) -> {
        });
    }

    private static Component find(Container root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        for (Component c : root.getComponents()) {
            if (c instanceof Container container) {
                Component found = find(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static AbstractButton button(ManageJdksCard card, String name) {
        return (AbstractButton) find(card, name);
    }

    @SuppressWarnings("unchecked")
    private static JList<JdkEntry> list(ManageJdksCard card) {
        return (JList<JdkEntry>) find(card, "jdkList");
    }

    private static JTextField field(ManageJdksCard card) {
        return (JTextField) find(card, "jdkVersionField");
    }

    private static List<String> ids(JList<JdkEntry> list) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getModel().getSize(); i++) {
            out.add(list.getModel().getElementAt(i).id());
        }
        return out;
    }

    @Test
    @DisplayName("the list reflects the registry, newest first, and re-reads on refresh")
    void listReflectsTheRegistry(@TempDir Path base) throws Exception {
        JdkRegistry registry = registryWithTwo(base);
        ManageJdksCard card = new ManageJdksCard(registry, new RecordingHost());
        assertEquals(2, card.jdkCount());
        // list() sorts newest-version first, so 25 leads 21.
        assertEquals(List.of("jdk-25+36", "jdk-21.0.4+7"), ids(list(card)),
                "the rows are the registry's entries, newest first");

        // A JDK added behind the card's back shows up on the next refresh.
        makeJdk(base.resolve("jdks").resolve("jdk-17.0.9+9"), "17.0.9");
        card.refresh();
        assertEquals(3, card.jdkCount());
    }

    @Test
    @DisplayName("download, register and back each route exactly one host callback")
    void buttonsRouteOneCallbackEach(@TempDir Path base) throws Exception {
        RecordingHost host = new RecordingHost();
        ManageJdksCard card = new ManageJdksCard(registryWithTwo(base), host);
        field(card).setText("  21  ");

        button(card, "downloadJdkButton").doClick();
        button(card, "registerJdkButton").doClick();
        button(card, "jdkBackButton").doClick();
        assertEquals(List.of("download:21", "register", "back"), host.calls,
                "the version is trimmed and each button fires once");
    }

    @Test
    @DisplayName("remove routes the selected id, and is disabled without a selection")
    void removeRoutesTheSelectedId(@TempDir Path base) throws Exception {
        RecordingHost host = new RecordingHost();
        ManageJdksCard card = new ManageJdksCard(registryWithTwo(base), host);
        assertNull(card.selectedId());
        assertFalse(button(card, "removeJdkButton").isEnabled(),
                "nothing to remove without a selection");

        list(card).setSelectedIndex(1);
        assertEquals("jdk-21.0.4+7", card.selectedId());
        assertTrue(button(card, "removeJdkButton").isEnabled());
        button(card, "removeJdkButton").doClick();
        assertEquals(List.of("remove:jdk-21.0.4+7"), host.calls);
    }
}
