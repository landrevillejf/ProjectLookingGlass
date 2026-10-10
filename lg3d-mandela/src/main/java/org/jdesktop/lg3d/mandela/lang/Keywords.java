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
package org.jdesktop.lg3d.mandela.lang;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Mandela's reserved words, and the words that only matter in one syntactic
 * position.
 *
 * <p>Two sets, deliberately: {@link #RESERVED} words can never be identifiers,
 * which is what makes the grammar unambiguous without lookahead; {@link #CONTEXTUAL}
 * words are ordinary identifiers everywhere except in the position where the
 * parser looks for them, so a script may legitimately name a field {@code step},
 * a variable {@code init} or a map key {@code export}. Editors and completion
 * providers colour both sets, but only the first is a hard error when reused.</p>
 *
 * <p>{@link #CONTEXTUAL} lists exactly the words the parser actually reads as
 * words, which is a stricter question than "which words would be nice": a word
 * named here is coloured as a keyword and is then suggested in a completion list,
 * so an aspiration in the list is a defect a developer trips over. Adding a soft
 * keyword here means adding the position that recognises it first, in
 * {@link Parser} or {@link ExprParser}, and never the other way round.</p>
 *
 * <p>The set is also the single source of truth for the keyword list the desktop's
 * Espresso editor colours for Mandela (see
 * {@code org.jdesktop.lg3d.apps.texteditor.Languages}), so the editor and the
 * parser can never disagree about what a keyword is.</p>
 */
public final class Keywords {

    /** Words that may not be used as identifiers. */
    public static final Set<String> RESERVED = immutableSet(
            "fun", "let", "var", "if", "else", "while", "for", "in", "loop",
            "break", "continue", "return", "class", "type", "enum", "extends",
            "this", "super", "match", "is", "try", "catch", "finally", "throw",
            "defer", "import", "use", "true", "false", "null", "and", "or",
            "not", "as");

    /**
     * Words the parser recognises positionally; legal identifiers otherwise.
     *
     * <p>{@code init} is a constructor only inside a class body
     * ({@code Parser}), {@code export} only at statement level and inside a module
     * body ({@code Parser}), and {@code step} only directly after a range literal
     * ({@code ExprParser.range}).</p>
     */
    public static final Set<String> CONTEXTUAL = immutableSet(
            "init", "export", "step");

    /** Every keyword and contextual word, in declaration order. */
    public static final Set<String> ALL = concat(RESERVED, CONTEXTUAL);

    private Keywords() {
        // Constant vocabulary.
    }

    /** @return true when {@code word} is a reserved word. */
    public static boolean isReserved(String word) {
        return word != null && RESERVED.contains(word);
    }

    /** @return true when {@code word} is reserved or contextual. */
    public static boolean isKeyword(String word) {
        return word != null && ALL.contains(word);
    }

    private static Set<String> immutableSet(String... words) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(words)));
    }

    private static Set<String> concat(Set<String> a, Set<String> b) {
        LinkedHashSet<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return Collections.unmodifiableSet(all);
    }
}
