/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.apps.presentoire;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.model.ScriptTransitionModel;
import org.jdesktop.lg3d.apps.presentoire.transition.ScriptTransition;
import org.jdesktop.lg3d.apps.presentoire.transition.Transition;

/**
 * The native-3D face of a Presentoire deck.
 *
 * <p>This is now a thin adapter over the shared AWT-free {@link PresentoireModel}
 * parser: it opens the deck, hands out {@link Slide} views and resolves the deck's
 * entries (images, {@code .class} transitions). The 2006 original did the ZIP and
 * XML parsing itself with a raw {@code Vector}, a {@code System.out.println} per
 * node and {@code printStackTrace} on every failure; all of that now lives in
 * {@link PresentoireModel}, which reports a malformed deck as a single
 * {@link IOException} and hardens the XML reader against entity expansion.</p>
 *
 * <p>Script transitions are built lazily and cached: a {@link ScriptTransition}
 * that finds no engine for its language (Nashorn JavaScript was removed in JDK 15)
 * degrades to a plain slide swap instead of throwing, so a deck always opens.</p>
 *
 * @author Pierre Ducroquet
 */
public class PresentoireDocument implements Closeable {

    private final PresentoireModel model;
    private final Map<String, ScriptTransition> scriptTransitions = new HashMap<>();

    /**
     * Opens a Presentoire {@code .zip} deck from the filesystem.
     *
     * @param doc the deck archive, which must contain a {@code content.xml}
     * @throws IOException if the file is not a readable, parseable deck
     */
    public PresentoireDocument(File doc) throws IOException {
        this(PresentoireModel.open(doc));
    }

    /**
     * Wraps an already-parsed model (the built-in demo deck, or a headless test).
     *
     * @param model the parsed deck; this document takes ownership and closes it
     */
    public PresentoireDocument(PresentoireModel model) {
        this.model = model;
    }

    /** The underlying AWT-free model. */
    public PresentoireModel getModel() {
        return model;
    }

    /**
     * Offers access to any entry of the deck archive (an image, or a {@code .class}
     * transition the 3D {@code TransitionFactory} defines at runtime).
     *
     * @param fileName the ZIP-relative entry name
     * @return an open stream the caller must close
     * @throws IOException if there is no archive (the demo deck) or no such entry
     */
    public InputStream getZIPInputStream(String fileName) throws IOException {
        InputStream in = model.openEntry(fileName);
        if (in == null) {
            throw new IOException("No such entry in the deck: " + fileName);
        }
        return in;
    }

    /** The number of slides in this document. */
    public int getSlideCount() {
        return model.getSlideCount();
    }

    /**
     * The slide view at {@code slideNumber}.
     *
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public Slide getSlide(int slideNumber) {
        return new Slide(model.getSlide(slideNumber), model);
    }

    /**
     * The scripted {@link Transition} with the given id, built on first use and
     * cached, or null when the deck defines none by that name.
     *
     * @param name the script-transition id
     */
    public Transition getScriptTransition(String name) {
        ScriptTransitionModel scriptModel = model.getScriptTransition(name);
        if (scriptModel == null) {
            return null;
        }
        return scriptTransitions.computeIfAbsent(name, key -> new ScriptTransition(scriptModel));
    }

    /** Closes the backing archive, if any. Safe to call more than once. */
    @Override
    public void close() {
        model.close();
    }
}
