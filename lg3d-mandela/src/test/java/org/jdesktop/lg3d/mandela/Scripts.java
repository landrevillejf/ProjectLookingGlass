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

import java.nio.file.Path;
import java.util.Map;

import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.lg3d.mandela.rt.Capabilities;
import org.jdesktop.lg3d.mandela.rt.Runtime;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.Values;

/**
 * The two shapes every suite in this module needs: run a program, and run one and
 * read what it printed as text.
 *
 * <p>Assertions are written against {@link Values#display(Object)} rather than the
 * Java object because the display form <em>is</em> the language's answer &mdash;
 * {@code 1} and {@code 1.0} are different values and a test that compares
 * {@code Long} to {@code Long} cannot tell a correct {@code Double} result from a
 * lucky one. It also keeps a test's expected value readable: {@code "[1, 2]"} is
 * what the author wrote in the session, not what the port happens to use.</p>
 *
 * <p>The budgets are deliberately small. A test that needs a hundred million
 * steps to pass is a test that will hang the suite, and a runaway loop is exactly
 * the kind of defect the suites are here to catch.</p>
 */
final class Scripts {

    /** A console-only engine with the budgets a unit test should run under. */
    static final Capabilities TINY = Capabilities.builder()
            .label("test")
            .grant(Capabilities.Grant.STDOUT)
            .grant(Capabilities.Grant.STDERR)
            .maxSteps(2_000_000L)
            .maxDepth(128)
            .build();

    private Scripts() {
        // Static namespace.
    }

    /**
     * Runs one program on a fresh engine.
     *
     * @param source the program
     * @return the value of its last expression
     */
    static Object run(String source) {
        return Mandela.engine(TINY).build().run(source, "test.mnd");
    }

    /**
     * Runs one program with host names bound before the compile.
     *
     * @param source   the program
     * @param bindings the names it may read
     * @return the value of its last expression
     */
    static Object run(String source, Map<String, Object> bindings) {
        return Mandela.engine(TINY).bindAll(bindings).build().run(source, "test.mnd");
    }

    /**
     * Runs one program and returns its answer in the language's own notation.
     *
     * @param source the program
     * @return the displayed value, {@code "null"} for no value
     */
    static String display(String source) {
        return Values.display(run(source));
    }

    /**
     * An engine the caller keeps, so a multi-statement scenario (a setup run and
     * then a call) can share one set of names.
     *
     * @return a builder over the test sandbox
     */
    static Runtime.Builder engine() {
        return Mandela.engine(TINY);
    }

    /**
     * An engine that may read and write one directory, the shape a scripted tool has:
     * both console channels, a filesystem under {@code root} and nowhere else, the
     * environment and a thread.
     *
     * <p>The root is a real directory rather than a mock, because the rule under test
     * is the one that resolves a path against a root; a fake would let the suite pass
     * while the desktop wrote outside its folder.</p>
     *
     * @param root the directory the script is allowed to touch
     * @return a builder over that shape
     */
    static Runtime.Builder files(Path root) {
        return Mandela.engine(Capabilities.builder()
                .label("test")
                .grant(Capabilities.Grant.STDOUT)
                .grant(Capabilities.Grant.STDERR)
                .grant(Capabilities.Grant.FILE_READ)
                .grant(Capabilities.Grant.FILE_WRITE)
                .grant(Capabilities.Grant.ENV)
                .grant(Capabilities.Grant.THREAD)
                .fileRoot(root)
                .maxSteps(20_000_000L)
                .maxDepth(128)
                .build());
    }

    /**
     * Runs a program that is expected to fail, and returns the failure.
     *
     * @param source the program
     * @return the error the script raised
     * @throws AssertionError when the program finished, which is the bug
     */
    static MandelaError failure(String source) {
        return failure(source, engine());
    }

    /**
     * Runs a program that is expected to fail on a caller-supplied engine shape.
     *
     * @param source  the program
     * @param builder the engine it should run on
     * @return the error the script raised
     * @throws AssertionError when the program finished, which is the bug
     */
    static MandelaError failure(String source, Runtime.Builder builder) {
        return failure(() -> builder.build().run(source, "test.mnd"));
    }

    /**
     * Runs a program on an engine that already exists.
     *
     * <p>A sandbox case needs the runtime itself rather than a builder, because the
     * point of the test is what that one engine refuses after it was configured.</p>
     *
     * @param source  the program
     * @param runtime the engine it should run on
     * @return the error the script raised
     * @throws AssertionError when the program finished, which is the bug
     */
    static MandelaError failure(String source, Runtime runtime) {
        return failure(() -> runtime.run(source, "test.mnd"));
    }

    /**
     * Runs anything that is expected to fail, and returns the failure.
     *
     * @param program the work, usually one {@code run} call
     * @return the error it raised
     * @throws AssertionError when it finished, which is the bug
     */
    static MandelaError failure(Runnable program) {
        try {
            program.run();
            throw new AssertionError("expected a failure, got none");
        } catch (MandelaError raised) {
            return raised;
        }
    }
}
