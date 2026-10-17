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
package org.jdesktop.lg3d.apps.presentoire.transition;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import org.jdesktop.lg3d.apps.presentoire.model.ScriptTransitionModel;
import org.jdesktop.lg3d.wg.Component3D;

/**
 * A transition driven by a document-supplied script (JavaScript or any other
 * {@code javax.script} language the host provides).
 *
 * <p>This is the class that made Presentoire unusable on a modern JVM: the 2006
 * original resolved the {@link ScriptEngine} in its constructor and threw a
 * {@code RuntimeException} when {@code getEngineByName} returned null. The JDK
 * removed the bundled Nashorn JavaScript engine in JDK 15, so on JDK 21 every
 * deck carrying a {@code language="JavaScript"} transition failed to open. This
 * port builds the engine defensively and records its absence instead of throwing;
 * when no engine is available the transition degrades to the plain slide swap the
 * base {@link Transition} performs, so the deck still advances.</p>
 *
 * <p>Note the inherited quirk this port deliberately preserves: the base
 * {@code Transition.runTransition} is {@code private} and its transition thread
 * binds to it non-virtually, so the {@link #runTransition} override below only
 * runs when called directly. Activating the scripted animation on the render
 * thread is out of scope for a resurrection whose 3D behaviour cannot be verified
 * without a live desktop; the reliable swap is kept.</p>
 *
 * @author Pierre Ducroquet
 */
public class ScriptTransition extends Transition {

    private static final ScriptEngineManager MANAGER = new ScriptEngineManager();

    private final String id;
    private final String language;

    /** The resolved engine, or null when the host has none for {@link #language}. */
    private final Invocable script;

    /**
     * Builds the transition from its parsed definition, resolving the script
     * engine defensively (never throwing when the language is unavailable).
     *
     * @param model the parsed {@code <transition>} definition
     */
    public ScriptTransition(ScriptTransitionModel model) {
        this.id = model.id();
        this.language = model.language();
        this.script = resolveScript(language, model.code());
    }

    private static Invocable resolveScript(String language, String code) {
        if (language.isEmpty()) {
            return null;
        }
        try {
            ScriptEngine engine = MANAGER.getEngineByName(language);
            if (engine == null) {
                return null;
            }
            engine.eval(code);
            return (engine instanceof Invocable invocable) ? invocable : null;
        } catch (ScriptException | RuntimeException e) {
            // A bad script must not stop the deck from opening; skip the animation.
            return null;
        }
    }

    /** The transition id referenced by a slide's {@code transition} attribute. */
    public String getId() {
        return id;
    }

    /** The scripting language named by the definition; may be empty. */
    public String getLanguage() {
        return language;
    }

    /** True when a script engine was resolved and the definition compiled. */
    public boolean isAvailable() {
        return script != null;
    }

    /**
     * Runs the transition: the script's {@code before}/{@code after} hooks around
     * the slide swap when an engine is available, otherwise the plain swap.
     *
     * @param mainComponent  the main Presentoire component
     * @param slideContainer the container holding the current slide
     * @param nextSlide      the next slide to reveal
     */
    public void runTransition(Component3D mainComponent, Component3D slideContainer,
            Component3D nextSlide) {
        if (script != null) {
            try {
                script.invokeFunction("before", mainComponent, slideContainer, nextSlide);
                slideContainer.removeAllChildren();
                script.invokeFunction("after", mainComponent, slideContainer, nextSlide);
                return;
            } catch (ScriptException | NoSuchMethodException | RuntimeException e) {
                // Fall through to the plain swap so the deck still advances.
            }
        }
        slideContainer.removeAllChildren();
        slideContainer.addChild(nextSlide);
    }
}
