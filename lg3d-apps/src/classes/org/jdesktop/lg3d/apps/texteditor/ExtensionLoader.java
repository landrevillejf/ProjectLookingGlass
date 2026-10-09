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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovery and fault-isolated installation of {@link TextEditorExtension}s.
 *
 * <p>{@link #discover()} walks the {@link ServiceLoader} for the editor's SPI
 * on the desktop classpath; a broken provider jar (a class that fails to
 * link, a services file naming a missing class) is logged and skipped
 * instead of taking the whole desktop down &mdash; the editor must stay
 * reliable no matter what is dropped onto the classpath.
 * {@link #installAll} applies the same containment to
 * {@link TextEditorExtension#install}: a throwing extension is disabled, the
 * rest still load, and the return value reports how many succeeded.</p>
 */
public final class ExtensionLoader {

    private static final Logger logger =
            Logger.getLogger(ExtensionLoader.class.getName());

    private ExtensionLoader() {
        // Static utility.
    }

    /**
     * The extensions registered through {@code META-INF/services}, in
     * discovery order. Never null; broken providers are omitted.
     */
    public static List<TextEditorExtension> discover() {
        List<TextEditorExtension> found = new ArrayList<>();
        try {
            ServiceLoader<TextEditorExtension> loader =
                    ServiceLoader.load(TextEditorExtension.class);
            Iterator<TextEditorExtension> it = loader.iterator();
            while (true) {
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    found.add(it.next());
                } catch (ServiceConfigurationError sce) {
                    // One bad provider: log it and keep scanning the rest.
                    logger.log(Level.WARNING,
                            "Skipping a broken text editor extension", sce);
                }
            }
        } catch (ServiceConfigurationError sce) {
            logger.log(Level.WARNING,
                    "Text editor extension discovery failed", sce);
        }
        return Collections.unmodifiableList(found);
    }

    /**
     * Installs every extension against {@code context}, isolating failures.
     *
     * @return the number of extensions installed without throwing
     */
    public static int installAll(
            Iterable<? extends TextEditorExtension> extensions,
            EditorContext context) {
        if (extensions == null || context == null) {
            return 0;
        }
        int installed = 0;
        for (TextEditorExtension extension : extensions) {
            if (extension == null) {
                continue;
            }
            try {
                extension.install(context);
                installed++;
            } catch (Throwable t) {
                // Containment boundary: an extension may never break the
                // editor, whatever it does at install time.
                logger.log(Level.WARNING, "Text editor extension \""
                        + safeName(extension) + "\" failed to install", t);
            }
        }
        return installed;
    }

    private static String safeName(TextEditorExtension extension) {
        try {
            return String.valueOf(extension.name());
        } catch (Throwable t) {
            return extension.getClass().getName();
        }
    }
}
