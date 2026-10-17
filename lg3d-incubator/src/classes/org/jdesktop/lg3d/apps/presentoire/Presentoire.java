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

import java.io.File;
import java.io.IOException;
import org.jdesktop.lg3d.apps.presentoire.format.DeckCodecs;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.jdesktop.lg3d.apps.presentoire.transition.Transition;
import org.jdesktop.lg3d.apps.presentoire.transition.TransitionFactory;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.utils.shape.Sphere;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;

/**
 * Presentoire: a slide-deck viewer for the native 3D desktop.
 *
 * <p>It builds a {@code Frame3D} holding a glassy slide quad and three sphere
 * buttons (next, previous, lower), opens a deck through the shared
 * {@link PresentoireDocument}/{@link PresentoireModel} seam, and pre-renders the
 * next slide so advancing is a cheap swap. This is the 3D half of the app; the
 * plain-Swing {@code PresentoirePanel} renders the very same model in the 2D
 * desktop.</p>
 *
 * <p>The 2006 original opened a modal {@code FileChooser} hardcoded to a
 * {@code /home/debian/...} path - a self-described "HACK [that] MUST DISAPPEAR".
 * This port resolves the deck instead from (in order) an explicit argument, the
 * {@code lg.presentoire.document} system property, or a built-in demo deck, so the
 * app always opens on real content and never blocks on a stray file dialog. Every
 * {@code System.out.println} trace and {@code printStackTrace} is gone; a deck
 * that will not open falls back to the demo with a single line on the error
 * stream.</p>
 *
 * @author Pierre Ducroquet
 */
public class Presentoire {

    /** The system property naming a deck to open when no argument is supplied. */
    public static final String DOCUMENT_PROPERTY = "lg.presentoire.document";

    private final Component3D slide;
    private final Component3D nextBtn;
    private final Component3D prevBtn;
    private final Component3D mainComponent;
    private final Frame3D frame3d;

    private int currentPageNumber;
    private PresentoireDocument doc;

    // Pre-rendered next slide and the transition that reveals it.
    private Component3D nextPageRendered;
    private Transition nextTransition;

    /** Creates Presentoire on the resolved default document (property or demo). */
    public Presentoire() {
        this(null);
    }

    /**
     * Creates Presentoire and opens {@code document}, falling back to the
     * {@code lg.presentoire.document} property and then the built-in demo deck when
     * {@code document} is null.
     *
     * @param document the deck to open, or null to resolve one
     */
    public Presentoire(File document) {
        currentPageNumber = 0;
        frame3d = new Frame3D();
        mainComponent = new Component3D();
        SimpleAppearance app = new SimpleAppearance(0.6f, 0.8f, 0.6f, 0.7f);
        GlassyPanel panel = new GlassyPanel(0.2f, 0.2f, 0.01f, app);
        mainComponent.addChild(panel);
        slide = new Component3D();
        mainComponent.addChild(slide);

        nextBtn = newButton(0.1f, -0.1f, false, source -> nextPage());
        mainComponent.addChild(nextBtn);

        prevBtn = newButton(-0.1f, -0.1f, false, source -> {
            currentPageNumber = currentPageNumber - 2;
            preRenderNextPage();
            nextPage();
        });
        mainComponent.addChild(prevBtn);

        // The "lower" button hides the frame; it is always available.
        Component3D lowerBtn = newButton(0.1f, 0.1f, true,
                source -> frame3d.changeVisible(false));
        mainComponent.addChild(lowerBtn);

        frame3d.addChild(mainComponent);
        frame3d.setVisible(true);
        frame3d.setEnabled(true);

        openDocument(document);
    }

    /** Builds one of the small translucent sphere buttons at {@code (x, y)}. */
    private static Component3D newButton(float x, float y, boolean visible,
            ActionNoArg action) {
        Component3D button = new Component3D();
        button.addChild(new Sphere(0.005f, Sphere.GENERATE_NORMALS, 10,
                new SimpleAppearance(0.1f, 0.1f, 1.0f, 0.5f)));
        button.setVisible(visible);
        button.setCursor(Cursor3D.DEFAULT_CURSOR);
        button.setTranslation(x, y, 0.0f);
        button.addListener(new MouseClickedEventAdapter(action));
        return button;
    }

    /**
     * Opens the given deck, or resolves one when {@code document} is null (the
     * {@code lg.presentoire.document} property, else the built-in demo). The deck
     * may be Presentoire's own {@code .zip} or an imported PowerPoint
     * ({@code .pptx} / {@code .ppt}) or LibreOffice ({@code .odp}) file: it is
     * resolved through {@link DeckCodecs}, so the 3D desktop shows the very same
     * model the 2D panel would. A deck that cannot be opened degrades to the demo
     * rather than leaving a blank frame. This 3D face stays view-only - export is
     * a 2D-panel feature.
     *
     * @param document the deck to open, or null to resolve one
     */
    public void openDocument(File document) {
        File target = document;
        if (target == null) {
            String property = System.getProperty(DOCUMENT_PROPERTY);
            if (property != null && !property.isBlank()) {
                target = new File(property);
            }
        }
        PresentoireDocument opened;
        try {
            opened = (target != null)
                    ? new PresentoireDocument(DeckCodecs.open(target))
                    : new PresentoireDocument(PresentoireModel.demo());
        } catch (IOException | RuntimeException e) {
            System.err.println("Presentoire: could not open " + target + " ("
                    + e.getMessage() + "); showing the built-in demo deck.");
            opened = new PresentoireDocument(PresentoireModel.demo());
        }
        if (doc != null) {
            doc.close();
        }
        doc = opened;
        currentPageNumber = 0;
        slide.removeAllChildren();
        if (doc.getSlideCount() > 0) {
            slide.addChild(doc.getSlide(currentPageNumber).drawPage());
        }
        prevBtn.setVisible(false);
        preRenderNextPage();
    }

    /**
     * Advances to the pre-rendered next slide, running its transition when one was
     * resolved and a plain swap otherwise. Does nothing at the end of the deck.
     */
    public void nextPage() {
        if (doc == null || nextPageRendered == null) {
            return;
        }
        nextBtn.setVisible(false);
        currentPageNumber++;
        if (nextTransition == null) {
            slide.removeAllChildren();
            slide.addChild(nextPageRendered);
        } else {
            nextTransition.executeTransition(mainComponent, slide, nextPageRendered);
        }
        prevBtn.setVisible(true);
        preRenderNextPage();
    }

    /**
     * Pre-renders the slide after the current one into {@link #nextPageRendered}
     * and resolves the transition that reveals it, so {@link #nextPage()} is a
     * cheap swap. Shows the next button only when there is a next slide.
     */
    private void preRenderNextPage() {
        int next = currentPageNumber + 1;
        if (doc != null && next >= 0 && next < doc.getSlideCount()) {
            Slide nextSlidePrerender = doc.getSlide(next);
            nextPageRendered = nextSlidePrerender.drawPage();
            nextTransition =
                    TransitionFactory.getTransition(nextSlidePrerender.getTransition(), doc);
            nextBtn.setVisible(true);
        } else {
            nextPageRendered = null;
            nextTransition = null;
            nextBtn.setVisible(false);
        }
    }

    /**
     * The 3D entry point. An optional first argument names the deck to open;
     * without one the {@code lg.presentoire.document} property and then the
     * built-in demo deck are used.
     *
     * @param args an optional deck path
     */
    public static void main(String[] args) {
        new Presentoire((args.length > 0) ? new File(args[0]) : null);
    }
}
