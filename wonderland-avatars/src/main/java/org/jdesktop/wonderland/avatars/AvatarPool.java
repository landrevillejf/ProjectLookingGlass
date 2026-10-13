/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the catalog of bundled avatar .dae bodies.
 *
 * The pool lives inside this module as classpath resources under
 * wonderland-avatars/*.dae; the client-side AvatarLoadResolver reaches them
 * through wonderland-modules' ClasspathWorldStore. This class is the tiny
 * pure catalog that names them and shapes the wla:// URL, so the pool's
 * identity is code-reviewable independently of the .dae bytes it points at
 * (the bytes themselves are placeholder content until a real artist ships
 * CC0 humanoids; only the filenames form the code contract).
 */
package org.jdesktop.wonderland.avatars;

import java.util.Collections;
import java.util.List;

public final class AvatarPool {

    /** The module directory the .dae bodies sit under, on the classpath. */
    public static final String MODULE = "wonderland-avatars";

    /** Stable identifiers for the bodies shipped in this jar. */
    public static final String HUMANOID_A = "humanoid-a";
    public static final String HUMANOID_B = "humanoid-b";
    public static final String HUMANOID_C = "humanoid-c";

    /** The name the client picks when a wire state carries no avatarModelUrl. */
    public static final String DEFAULT_NAME = HUMANOID_A;

    private static final List<String> NAMES = Collections.unmodifiableList(
            List.of(HUMANOID_A, HUMANOID_B, HUMANOID_C));

    private AvatarPool() {
        // catalog holder: no instances
    }

    /** Every bundled avatar name, in declaration order. */
    public static List<String> names() {
        return NAMES;
    }

    /** The stable fallback name a client uses when a wire state is silent. */
    public static String defaultName() {
        return DEFAULT_NAME;
    }

    /**
     * The {@code wla://} URL that identifies the named body. Every name in
     * {@link #names()} resolves through {@code ClasspathWorldStore} to a real
     * .dae resource inside this module; an unknown name yields a well-formed
     * URL whose load degrades to an ERROR result, never a null and never a
     * throw, so callers can pass any string through safely.
     */
    public static String urlFor(String name) {
        if (name == null || name.isEmpty()) {
            name = DEFAULT_NAME;
        }
        return "wla://" + MODULE + "/" + name + ".dae";
    }
}
