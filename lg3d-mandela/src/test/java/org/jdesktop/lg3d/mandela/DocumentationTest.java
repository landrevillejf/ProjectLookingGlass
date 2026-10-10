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
package org.jdesktop.lg3d.mandela;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.lang.Diagnostic;
import org.jdesktop.lg3d.mandela.lang.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The language guide's {@code ```mandela} blocks are parsed by the real parser.
 *
 * <p>A tutorial that drifts from the language is worse than no tutorial: the
 * reader cannot tell whether their own script is wrong. So every fenced sample in
 * <code>docs/mandela-language.md</code> goes through {@link Parser} here, and a
 * block the grammar rejects fails the build.</p>
 *
 * <p>Only the parse is checked, not name resolution: a guide fragment deliberately
 * references a value introduced in the prose around it, and resolving those names
 * would mean writing the prose twice. A block whose text contains an elision
 * marker &mdash; {@code …} or {@code ...} &mdash; stands for code the guide is
 * abbreviating, so it is skipped rather than rewritten to satisfy a test.</p>
 */
class DocumentationTest {

    /** The guide this module ships, resolved against the repository layout. */
    private static final String GUIDE = "docs/mandela-language.md";

    /** Elision markers that mark a block as an abbreviation, not a program. */
    private static final List<String> ELISIONS = List.of("…", "...");

    /** The one compile-time finding a guide fragment is entitled to. */
    private static final String UNDECLARED = "undefined-name";

    /**
     * A sample the reader could paste is one the grammar accepts. The floor on the
     * block count is here so a broken extractor cannot turn this into a test that
     * checks nothing.
     */
    @Test
    @DisplayName("every mandela block in the language guide parses")
    void guideBlocksParse() {
        Path guide = guidePath();
        List<String> problems = new ArrayList<>();
        for (Block block : pasteableBlocks(guide)) {
            Parser.Program program = Parser.parse(block.text(), guide.toString());
            for (Diagnostic d : program.diagnostics()) {
                if (d.isError()) {
                    problems.add(d.location() + ": " + d.message());
                }
            }
        }
        assertEquals(List.of(), problems, "the guide teaches syntax the parser rejects");
    }

    /**
     * Past the grammar: a sample must also survive the compiler's own checks,
     * except for the one finding a fragment is entitled to &mdash; a name the prose
     * introduced around it ({@code prices}, {@code list}, {@code text}), which the
     * compiler reports as {@code undefined-name}. Anything else the compiler
     * refuses is a tutorial teaching a construct that never runs.<p>
     *
     * The compile stops at its first hard error, so one block can mask a later
     * finding inside itself; the parse test above is the exhaustive one, and name
     * resolution deliberately is not tried here because writing the declarations a
     * fragment omits would mean writing the prose twice.
     */
    @Test
    @DisplayName("every mandela block in the language guide compiles")
    void guideBlocksCompile() {
        Path guide = guidePath();
        List<String> problems = new ArrayList<>();
        for (Block block : pasteableBlocks(guide)) {
            for (Diagnostic d : Mandela.check(block.text(), guide.toString())) {
                if (d.isError() && !UNDECLARED.equals(d.rule())) {
                    problems.add(d.location() + " [" + d.rule() + "]: " + d.message());
                }
            }
        }
        assertEquals(List.of(), problems, "the guide teaches a program the compiler refuses");
    }

    /**
     * The extractor itself: fences are matched on their info string, a block keeps
     * its line number for the failure message, and an unterminated fence is a
     * document defect rather than a silently dropped sample.
     */
    @Test
    @DisplayName("the block extractor sees every fence and its line")
    void extractorReadsFences() {
        List<Block> blocks = blocksOf("""
                text
                ```mandela
                let a = 1
                ```
                more
                ```java
                not ours
                ```
                ```mandela
                println(…)
                ```
                """);
        assertEquals(2, blocks.size());
        assertEquals("let a = 1", blocks.get(0).text());
        assertEquals(2, blocks.get(0).startLine());
        assertFalse(blocks.get(0).elided());
        assertTrue(blocks.get(1).elided());

        List<String> unterminated = List.of("```mandela", "let a = 1", "");
        // A fence left open at end of file yields one block, not zero: the test above
        // must still see the sample rather than quietly skip it.
        assertEquals(1, blocksOf(String.join("\n", unterminated)).size());
    }

    /** @return the guide's blocks a reader could paste, failing if too few are found */
    private static List<Block> pasteableBlocks(Path guide) {
        List<Block> pasteable = new ArrayList<>();
        for (Block block : blocks(guide)) {
            if (!block.elided()) {
                pasteable.add(block);
            }
        }
        assertTrue(pasteable.size() >= 10, "only " + pasteable.size()
                + " pasteable mandela blocks were extracted from " + GUIDE
                + "; the extractor is probably broken");
        return pasteable;
    }

    /** @return the guide's path, or a failure naming the layouts that were tried */
    private static Path guidePath() {
        Path fromModule = Path.of("..", GUIDE);
        Path fromRoot = Path.of(GUIDE);
        for (Path candidate : List.of(fromModule, fromRoot)) {
            if (Files.isReadable(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return fail("cannot find " + GUIDE + " from " + Path.of("").toAbsolutePath()
                + " (expected the module test task to run with the module as cwd)");
    }

    private static List<Block> blocks(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return blocksOf(String.join("\n", lines));
    }

    /** Collects the {@code ```mandela} fences of one document. */
    private static List<Block> blocksOf(String text) {
        List<Block> out = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            if (lines[index].trim().equals("```mandela")) {
                int start = index + 1;
                List<String> body = new ArrayList<>();
                index++;
                while (index < lines.length && !lines[index].trim().equals("```")) {
                    body.add(lines[index]);
                    index++;
                }
                out.add(new Block(String.join("\n", body), start,
                        body.stream().anyMatch(line -> ELISIONS.stream().anyMatch(line::contains))));
            }
            index++;
        }
        return out;
    }

    /**
     * @param text   the block's source
     * @param startLine the line the block begins on, for a readable failure
     * @param elided true when the block abbreviates code the prose describes
     */
    private record Block(String text, int startLine, boolean elided) { }
}
