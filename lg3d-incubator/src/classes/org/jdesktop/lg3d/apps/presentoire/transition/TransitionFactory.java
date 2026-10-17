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

import java.io.IOException;
import java.io.InputStream;
import org.jdesktop.lg3d.apps.presentoire.PresentoireDocument;

/**
 * Resolves the {@link Transition} a slide names.
 *
 * <p>A slide's {@code transition} attribute is either a built-in name
 * ({@code rotation}, {@code scale}, {@code transparency}), the id of a
 * document-defined script transition, or a {@code .class} path baked into the deck
 * that is defined at runtime. This port keeps that resolution order but reads the
 * class bytes with {@link InputStream#readAllBytes()} instead of the original's
 * fragile {@code available()}-based loop, uses a generic {@code Class<?>} instead
 * of a raw {@code Class}, drops the {@code System.out} trace, and returns null -
 * never throws - for a name it cannot resolve (an empty name, a missing class, a
 * broken script), so the caller falls back to the plain slide swap.</p>
 *
 * @author Pierre Ducroquet
 */
public final class TransitionFactory {

    private TransitionFactory() {
        // no instances
    }

    /**
     * Returns the transition {@code name} refers to in {@code doc}, or null when
     * the name is empty or cannot be resolved.
     *
     * @param name the transition name a slide declares
     * @param doc  the deck being displayed
     */
    public static Transition getTransition(String name, PresentoireDocument doc) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (name.endsWith(".class")) {
            Transition loaded = loadClassTransition(name, doc);
            if (loaded != null) {
                return loaded;
            }
            // Fall through: a deck may also define a script of the same name.
        }
        Transition script = doc.getScriptTransition(name);
        if (script != null) {
            return script;
        }
        return switch (name) {
            case "rotation" -> new RotationTransition();
            case "scale" -> new ScaleTransition();
            case "transparency" -> new TransparencyTransition();
            default -> null;
        };
    }

    /**
     * Defines a {@code Transition} subclass from a {@code .class} entry in the deck
     * archive and instantiates it. Returns null on any failure (a missing entry,
     * unreadable bytes, a class that is not a {@code Transition}, or one that
     * cannot be instantiated) so the caller degrades to the plain swap.
     */
    private static Transition loadClassTransition(String name, PresentoireDocument doc) {
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        String className = name.substring(0, name.lastIndexOf('.')).replace('/', '.');
        try (InputStream in = doc.getZIPInputStream(fileName)) {
            byte[] classData = in.readAllBytes();
            Class<?> loaded = new TransitionClassLoader()
                    .getTransitionClass(className, classData, 0, classData.length);
            return loaded.asSubclass(Transition.class).getDeclaredConstructor().newInstance();
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /**
     * A minimal {@link ClassLoader} whose only job is to expose {@code defineClass}
     * so a transition class shipped inside a deck can be defined at runtime.
     *
     * @author Pierre Ducroquet
     */
    private static class TransitionClassLoader extends ClassLoader {

        TransitionClassLoader() {
            super(TransitionFactory.class.getClassLoader());
        }

        Class<?> getTransitionClass(String name, byte[] b, int off, int len) {
            return defineClass(name, b, off, len);
        }
    }
}
