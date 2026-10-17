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
package org.jdesktop.lg3d.apps.presentoire.model;

/**
 * The parsed data of a document-level {@code <transition>} script definition:
 * its id, the scripting language it names and the source body.
 *
 * <p>This is pure data - it carries no script engine. The 2006 original built a
 * {@code javax.script.ScriptEngine} for the language right inside the parser and
 * threw a {@code RuntimeException} when {@code getEngineByName} returned null.
 * Since the JDK removed the bundled Nashorn JavaScript engine in JDK 15, that
 * made <em>every</em> deck containing a {@code language="JavaScript"} transition
 * fail to open on a modern JVM. Splitting the parsed definition (this record)
 * from its execution (the 3D {@code ScriptTransition}, which now degrades to a
 * plain slide swap when no engine is present) means a deck always opens; only the
 * scripted animation is skipped, and only when the host truly has no engine for
 * the named language.</p>
 *
 * @param id       the transition id referenced by a slide's {@code transition}
 * @param language the scripting language named by the {@code language} attribute
 * @param code     the script body (the {@code <transition>} text content)
 */
public record ScriptTransitionModel(String id, String language, String code) {

    /** Normalises a null field to an empty string so accessors are never null. */
    public ScriptTransitionModel {
        id = (id == null) ? "" : id;
        language = (language == null) ? "" : language;
        code = (code == null) ? "" : code;
    }
}
