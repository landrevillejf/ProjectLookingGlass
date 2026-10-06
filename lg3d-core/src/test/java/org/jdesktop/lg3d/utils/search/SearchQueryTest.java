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
package org.jdesktop.lg3d.utils.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link SearchQuery} defaults, coercion and accessors. */
class SearchQueryTest {

    @Test
    @DisplayName("a bare builder yields a valid 'everything under home' query")
    void defaults() {
        SearchQuery q = SearchQuery.builder().build();
        assertEquals(1, q.getRoots().size(), "home is the default root");
        assertEquals(SearchQuery.NameMode.SUBSTRING, q.getNameMode());
        assertEquals("", q.getNamePattern());
        assertFalse(q.isCaseSensitive());
        assertFalse(q.isIncludeHidden());
        assertEquals(SearchQuery.Kind.ANY, q.getKind());
        assertFalse(q.isContentSearch());
        assertEquals("", q.getContentPattern());
        assertFalse(q.isContentRegex());
        assertFalse(q.getMinSize().isPresent());
        assertFalse(q.getMaxSize().isPresent());
        assertFalse(q.getModifiedWithinDays().isPresent());
        assertEquals(SearchQuery.DEFAULT_MAX_RESULTS, q.getMaxResults());
    }

    @Test
    @DisplayName("every setter round-trips through build()")
    void settersRoundTrip() {
        List<Path> roots = Arrays.asList(Paths.get("/tmp"), Paths.get("/etc"));
        SearchQuery q = SearchQuery.builder()
                .roots(roots)
                .nameMode(SearchQuery.NameMode.REGEX)
                .namePattern("^a.*z$")
                .caseSensitive(true)
                .includeHidden(true)
                .kind(SearchQuery.Kind.FILES)
                .contentSearch(true)
                .contentPattern("needle")
                .contentRegex(true)
                .minSize(OptionalLong.of(10))
                .maxSize(OptionalLong.of(2000))
                .modifiedWithinDays(OptionalInt.of(3))
                .maxResults(42)
                .build();
        assertEquals(roots, q.getRoots());
        assertEquals(SearchQuery.NameMode.REGEX, q.getNameMode());
        assertEquals("^a.*z$", q.getNamePattern());
        assertTrue(q.isCaseSensitive());
        assertTrue(q.isIncludeHidden());
        assertEquals(SearchQuery.Kind.FILES, q.getKind());
        assertTrue(q.isContentSearch());
        assertEquals("needle", q.getContentPattern());
        assertTrue(q.isContentRegex());
        assertEquals(10L, q.getMinSize().getAsLong());
        assertEquals(2000L, q.getMaxSize().getAsLong());
        assertEquals(3, q.getModifiedWithinDays().getAsInt());
        assertEquals(42, q.getMaxResults());
    }

    @Test
    @DisplayName("null enums/patterns and a non-positive cap are coerced safely")
    void coercion() {
        SearchQuery q = SearchQuery.builder()
                .nameMode(null)
                .namePattern(null)
                .kind(null)
                .contentPattern(null)
                .minSize(null)
                .maxSize(null)
                .modifiedWithinDays(null)
                .maxResults(0)
                .build();
        assertEquals(SearchQuery.NameMode.SUBSTRING, q.getNameMode());
        assertEquals(SearchQuery.Kind.ANY, q.getKind());
        assertEquals("", q.getNamePattern());
        assertEquals("", q.getContentPattern());
        assertFalse(q.getMinSize().isPresent());
        assertFalse(q.getMaxSize().isPresent());
        assertFalse(q.getModifiedWithinDays().isPresent());
        assertEquals(SearchQuery.DEFAULT_MAX_RESULTS, q.getMaxResults(),
                "a non-positive cap falls back to the default");
    }

    @Test
    @DisplayName("null roots are dropped; addRoot(String) parses and ignores blanks")
    void rootHandling() {
        SearchQuery q = SearchQuery.builder()
                .roots(Arrays.asList(Paths.get("/a"), null, Paths.get("/b")))
                .addRoot((Path) null)
                .addRoot("/c")
                .addRoot("   ")
                .addRoot((String) null)
                .build();
        assertEquals(List.of(Paths.get("/a"), Paths.get("/b"), Paths.get("/c")), q.getRoots());
    }

    @Test
    @DisplayName("the roots list is unmodifiable and defensive")
    void rootsImmutable() {
        List<Path> mutable = new java.util.ArrayList<>();
        mutable.add(Paths.get("/x"));
        SearchQuery q = SearchQuery.builder().roots(mutable).build();
        mutable.add(Paths.get("/y"));
        assertEquals(1, q.getRoots().size(), "later mutation of the source is not seen");
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> q.getRoots().add(Paths.get("/z")));
    }

    @Test
    @DisplayName("toString mentions the salient fields")
    void toStringIsInformative() {
        String s = SearchQuery.builder().namePattern("foo").build().toString();
        assertTrue(s.contains("SearchQuery"));
        assertTrue(s.contains("foo"));
    }

    @Test
    @DisplayName("two builders produce independent queries")
    void independence() {
        SearchQuery.Builder b = SearchQuery.builder().namePattern("a");
        SearchQuery first = b.build();
        SearchQuery second = b.namePattern("b").build();
        assertEquals("a", first.getNamePattern());
        assertEquals("b", second.getNamePattern());
        assertFalse(first == second);
        assertSame(SearchQuery.NameMode.SUBSTRING, first.getNameMode());
    }
}
