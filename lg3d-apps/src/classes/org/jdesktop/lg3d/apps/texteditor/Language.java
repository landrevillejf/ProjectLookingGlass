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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * An immutable, declarative definition of a source language for the syntax
 * colouriser: the file-name extensions that select it, its reserved words,
 * its comment syntax and a handful of mode flags. Languages carry no
 * behaviour &mdash; {@link SyntaxHighlighter} interprets them &mdash; so
 * adding a language is pure data, and the whole class is safe to share across
 * threads.
 *
 * <p>Instances are built through {@link Languages}; user code should never
 * need the constructor, but it is public so extensions can register custom
 * languages through the same value type.</p>
 */
public final class Language {

    private final String name;
    private final Set<String> extensions;
    private final Set<String> keywords;
    private final String lineComment;
    private final String blockOpen;
    private final String blockClose;
    private final String stringQuotes;
    private final boolean preprocessor;
    private final boolean markup;

    /**
     * Creates a language definition.
     *
     * @param name         display name, also the language selector value
     * @param extensions   lower-case file extensions without a dot
     * @param keywords     reserved words, matched case-sensitively
     * @param lineComment  line-comment introducer, or null for none
     * @param blockOpen    block-comment opener, or null for none
     * @param blockClose   block-comment closer, or null for none
     * @param stringQuote  characters that open a string literal (e.g. {@code "'\"`"})
     * @param preprocessor true to colour {@code #}-directive lines (C family)
     * @param markup       true to scan XML/HTML-style tags instead of comments
     */
    public Language(String name, Set<String> extensions, Set<String> keywords,
            String lineComment, String blockOpen, String blockClose,
            String stringQuote, boolean preprocessor, boolean markup) {
        this.name = name;
        this.extensions = Collections.unmodifiableSet(
                new LinkedHashSet<>(extensions));
        this.keywords = Collections.unmodifiableSet(
                new LinkedHashSet<>(keywords));
        this.lineComment = lineComment;
        this.blockOpen = blockOpen;
        this.blockClose = blockClose;
        this.stringQuotes = (stringQuote == null) ? "" : stringQuote;
        this.preprocessor = preprocessor;
        this.markup = markup;
    }

    /** The display name (e.g. "Java"). */
    public String getName() {
        return name;
    }

    /** The lower-case extensions selecting this language, without dots. */
    public Set<String> getExtensions() {
        return extensions;
    }

    /** The reserved words; matched case-sensitively. */
    public Set<String> getKeywords() {
        return keywords;
    }

    /** The line-comment introducer, or null when the language has none. */
    public String getLineComment() {
        return lineComment;
    }

    /** The block-comment opener, or null when the language has none. */
    public String getBlockOpen() {
        return blockOpen;
    }

    /** The block-comment closer, or null when the language has none. */
    public String getBlockClose() {
        return blockClose;
    }

    /** The characters that can open a string literal. */
    public String getStringQuotes() {
        return stringQuotes;
    }

    /** True when {@code #}-prefixed directive lines are coloured. */
    public boolean hasPreprocessor() {
        return preprocessor;
    }

    /** True for XML/HTML-style markup scanning. */
    public boolean isMarkup() {
        return markup;
    }

    /** True when the language has any reserved words at all. */
    public boolean hasKeywords() {
        return !keywords.isEmpty();
    }

    @Override
    public String toString() {
        return name;
    }
}
