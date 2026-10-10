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
package org.jdesktop.lg3d.mandela.rt;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jdesktop.lg3d.mandela.values.MandelaError;

/**
 * What a script is allowed to do, decided by the host rather than by the script.
 *
 * <p>Mandela runs inside a desktop that also runs the user's files, so an
 * unbounded {@code eval} is not acceptable anywhere except the CLI. The answer is
 * not a bytecode verifier &mdash; the language has no unprivileged-mode split and
 * adding one would double the machine &mdash; but an explicit grant list that
 * every side-effecting built-in and host function consults before it acts. A
 * script cannot widen its own capabilities: the object is handed to the
 * {@link Runtime} and never exposed as a value.</p>
 *
 * <p>Four shapes cover the users:</p>
 * <ul>
 *   <li>{@link #open()} &mdash; the CLI and {@code mandela repl}: the real
 *       filesystem, the terminal, generous limits.</li>
 *   <li>{@link #console()} &mdash; a snippet a host pasted in: a console and
 *       nothing else, which is what Espresso's <em>Run</em> executes the current
 *       buffer under.</li>
 *   <li>{@link #desktop(java.nio.file.Path)} &mdash; a panel, widget or app
 *       script: files under one directory, a console, the clock, no JVM types,
 *       no sockets.</li>
 *   <li>{@link #webPage()} &mdash; a script embedded in the Web Browser: nothing
 *       but computing, its own console channel and a small step budget, because
 *       page content is untrusted by definition.</li>
 * </ul>
 *
 * <p>Limits are enforced by the machine itself: a runaway loop and unbounded
 * recursion both stop with a catchable {@code LimitError} that quotes the ceiling
 * they hit, so a mistyped {@code loop { }} cannot hang the desktop.</p>
 */
public final class Capabilities {

    /** One grantable side effect. */
    public enum Grant {
        /** Write to the standard output. */
        STDOUT,
        /** Write to the standard error. */
        STDERR,
        /** Read the standard input. */
        STDIN,
        /** Read a file below the granted root. */
        FILE_READ,
        /** Write a file below the granted root. */
        FILE_WRITE,
        /** Read an environment variable. */
        ENV,
        /** Open a socket or fetch a URL. */
        NETWORK,
        /** Reach a JVM class through the interop allow-list. */
        JVM,
        /** Start a process. */
        PROCESS,
        /** Spawn a thread or run a script in parallel. */
        THREAD
    }

    /** The instruction budget for one run. */
    public static final long DEFAULT_STEPS = 200_000_000L;

    /** The frame budget, chosen so a deep-but-legal recursion still fits the JVM stack. */
    public static final int DEFAULT_DEPTH = 1024;

    private final Set<Grant> grants;
    private final Path fileRoot;
    private final Set<String> jvmAllowList;
    private final long maxSteps;
    private final int maxDepth;
    private final String label;

    private Capabilities(Builder b) {
        this.grants = Set.copyOf(b.grants);
        this.fileRoot = b.fileRoot;
        this.jvmAllowList = Set.copyOf(b.jvmAllowList);
        this.maxSteps = b.maxSteps;
        this.maxDepth = b.maxDepth;
        this.label = b.label;
    }

    /** @return a fresh builder seeded with nothing */
    public static Builder builder() {
        return new Builder();
    }

    /** @return everything granted, the mode the CLI and the REPL run in */
    public static Capabilities open() {
        return builder().label("open")
                .grant(Grant.STDOUT).grant(Grant.STDERR).grant(Grant.STDIN)
                .grant(Grant.FILE_READ).grant(Grant.FILE_WRITE)
                .grant(Grant.ENV).grant(Grant.NETWORK).grant(Grant.JVM)
                .grant(Grant.PROCESS).grant(Grant.THREAD)
                .build();
    }

    /**
     * The mode for a snippet a host pasted in: a console and nothing else.
     *
     * <p>Files, the environment, sockets, threads, processes and JVM types are all
     * refused, so the default an embedding API hands out is the safe one and a host
     * has to say so on purpose to get anything more. This is what
     * {@code Mandela.engine()} and the JSR-223 engine start from; {@link #open()}
     * is the opposite end and belongs to a command line run by the person who wrote
     * the script.</p>
     *
     * @return stdout and stderr, with a modest budget
     */
    public static Capabilities console() {
        return builder().label("console")
                .grant(Grant.STDOUT).grant(Grant.STDERR)
                .maxSteps(20_000_000L)
                .maxDepth(512)
                .build();
    }

    /**
     * The mode for a desktop app, panel or widget script: files under one
     * directory, a console, the clock, no JVM types and no sockets.
     *
     * @param root the directory the script may read and write
     * @return the capability set
     */
    public static Capabilities desktop(Path root) {
        return builder().label("desktop")
                .grant(Grant.STDOUT).grant(Grant.STDERR)
                .grant(Grant.FILE_READ).grant(Grant.FILE_WRITE).fileRoot(root)
                .grant(Grant.THREAD)
                .maxSteps(50_000_000L)
                .build();
    }

    /**
     * The mode for a script that arrived from a web page or any other untrusted
     * source: computation only, with its own console channel supplied by the host
     * as a plain {@link HostFunction}, and a small step budget.
     *
     * @return the capability set
     */
    public static Capabilities webPage() {
        return builder().label("webpage")
                .maxSteps(5_000_000L)
                .maxDepth(256)
                .build();
    }

    /**
     * @param grant the side effect to test
     * @return true when the host allowed it
     */
    public boolean isGranted(Grant grant) {
        return grants.contains(grant);
    }

    /**
     * Asserts a grant, throwing the error a script sees when it oversteps.
     *
     * @param grant the side effect about to happen
     * @param what  a short description of the operation, for the message
     * @throws MandelaError always, when the grant is missing
     */
    public void require(Grant grant, String what) {
        if (!grants.contains(grant)) {
            throw MandelaError.permission("this host (" + label + ") does not allow"
                    + " " + what + "; ask the embedding application for the "
                    + grant + " capability");
        }
    }

    /** @return the directory file operations are confined to, or null when unconfined */
    public Path fileRoot() {
        return fileRoot;
    }

    /**
     * Resolves a script-supplied path inside the granted root.
     *
     * <p>Every file built-in goes through here, so {@code "../"} and absolute
     * paths cannot escape the directory the host handed over.</p>
     *
     * @param requested the path the script asked for
     * @param what      a short description for the error message
     * @return the resolved, confined path
     * @throws MandelaError when the result would fall outside the root
     */
    public Path resolve(String requested, String what) {
        if (requested == null || requested.isEmpty()) {
            throw MandelaError.value("a file path must not be empty");
        }
        Path base = (fileRoot == null) ? Path.of("").toAbsolutePath() : fileRoot;
        Path candidate = base.getFileSystem().getPath(requested);
        Path absolute = candidate.isAbsolute() ? candidate : base.resolve(candidate);
        Path normalised = absolute.normalize();
        if (fileRoot != null && !normalised.startsWith(fileRoot.normalize())) {
            throw MandelaError.permission("refusing to " + what + " '" + requested
                    + "': it resolves outside " + fileRoot);
        }
        return normalised;
    }

    /**
     * @param binaryName the JVM type's fully qualified name, dotted
     * @return true when the interop allow-list covers it
     */
    public boolean allowsJvm(String binaryName) {
        if (!grants.contains(Grant.JVM)) {
            return false;
        }
        for (String prefix : jvmAllowList) {
            if (binaryName.equals(prefix) || binaryName.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    /** @return the instruction budget a single run may consume */
    public long maxSteps() {
        return maxSteps;
    }

    /** @return the frame budget a single run may nest */
    public int maxDepth() {
        return maxDepth;
    }

    /** @return the human-readable mode name used in permission errors */
    public String label() {
        return label;
    }

    /** @return the granted side effects, for {@code capabilities()} in a script */
    public Set<String> describe() {
        Set<String> out = new LinkedHashSet<>();
        for (Grant grant : grants) {
            out.add(grant.name().toLowerCase());
        }
        return out;
    }

    @Override
    public String toString() {
        return "<capabilities " + label + " " + grants.size() + " grants>";
    }

    /** Assembles a capability set. */
    public static final class Builder {

        private final Set<Grant> grants = new LinkedHashSet<>();
        private final Set<String> jvmAllowList = new LinkedHashSet<>();

        private Path fileRoot;
        private long maxSteps = DEFAULT_STEPS;
        private int maxDepth = DEFAULT_DEPTH;
        private String label = "custom";

        private Builder() {
        }

        /** Grants one side effect. */
        public Builder grant(Grant grant) {
            grants.add(grant);
            return this;
        }

        /** Revokes one side effect. */
        public Builder revoke(Grant grant) {
            grants.remove(grant);
            return this;
        }

        /** Confines file operations to {@code root}. */
        public Builder fileRoot(Path root) {
            this.fileRoot = (root == null) ? null : root.toAbsolutePath().normalize();
            return this;
        }

        /**
         * Adds a JVM type or package prefix to the interop allow-list, which also
         * implies the {@link Grant#JVM} grant.
         */
        public Builder allowJvm(String binaryNameOrPrefix) {
            grants.add(Grant.JVM);
            jvmAllowList.add(binaryNameOrPrefix);
            return this;
        }

        /** Sets the instruction budget. */
        public Builder maxSteps(long steps) {
            this.maxSteps = Math.max(1L, steps);
            return this;
        }

        /** Sets the frame budget. */
        public Builder maxDepth(int depth) {
            this.maxDepth = Math.max(2, depth);
            return this;
        }

        /** Names the mode, which appears in permission errors. */
        public Builder label(String name) {
            this.label = (name == null || name.isEmpty()) ? "custom" : name;
            return this;
        }

        /** @return the immutable capability set */
        public Capabilities build() {
            return new Capabilities(this);
        }
    }
}
