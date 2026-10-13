/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: pick which bundled .dae body one avatar cell draws.
 *
 * The wire's AvatarCellServerState now carries an optional avatarModelUrl
 * hint; when null the client picks the pool default. This class is the pure
 * decision + read layer:
 *
 *   1. Resolve the URL to try. A caller-provided non-empty URL wins; a null
 *      or empty URL falls back to {@link AvatarPool#urlFor(String)} with the
 *      default name (currently "humanoid-a"). That is exactly the plan's
 *      "server does not yet set the URL; every existing cell decodes through
 *      the pool default" contract.
 *   2. Read the bytes through {@link ClasspathWorldStore} so the same
 *      wla://<module>/<path> confinement M5's directory store applies is
 *      applied here.
 *   3. Parse with {@link ColladaParser} and wrap in an {@link
 *      AvatarLoadResult}. Any failure -- an unparseable URL, a missing
 *      resource, malformed COLLADA -- degrades to a documented error result
 *      rather than throwing, mirroring the M5 loader's "warn and continue"
 *      behaviour so the desktop never crashes on a bad avatar hint.
 *
 * Design deviation from the M6b plan wording: the plan text said "calls
 * ModelLoader.load(new ClasspathWorldStore(), url)". The M5 pipeline reads a
 * .dep indirection first and then follows modelURL to the .dae bytes, but the
 * pool ships raw .dae files with no .dep layer (adding one per body would
 * double the shipped content and change nothing on the wire contract). The
 * resolver therefore opens the .dae through ClasspathWorldStore directly and
 * hands the stream to ColladaParser -- same URL scheme, same store, same
 * parser, no .dep. Documented here so a reviewer can find the reason.
 *
 * The whole class is pure: no Jogamp class is constructed, so the test suite
 * can drive it against the real bundled pool.
 */
package org.jdesktop.wonderland.renderer.avatar;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.jdesktop.wonderland.avatars.AvatarPool;
import org.jdesktop.wonderland.modules.asset.ClasspathWorldStore;
import org.jdesktop.wonderland.modules.asset.WlaRef;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaParser;

/** Resolve an avatar body URL to a parsed Collada document. */
public class AvatarLoadResolver {

    /**
     * Resolve the given URL. A null or empty URL uses the pool default. On
     * any failure (bad scheme, missing resource, malformed COLLADA) the
     * result is {@link AvatarLoadResult#isLoaded() not loaded} with an
     * error message; the resolver never throws.
     */
    public AvatarLoadResult resolve(String avatarModelUrlOrNull) {
        String url = selectUrl(avatarModelUrlOrNull);
        WlaRef ref = WlaRef.parse(url);
        if (ref == null) {
            return AvatarLoadResult.error("not a wla:// reference", url);
        }
        ClasspathWorldStore store = new ClasspathWorldStore();
        Optional<InputStream> stream;
        try {
            stream = store.open(ref.module(), ref.path());
        } catch (RuntimeException ex) {
            // A confined-path rejection or a class-loader failure degrades
            // to the same "no such resource" answer the plan asks for, so a
            // bad server hint never crashes the desktop.
            return AvatarLoadResult.error("resource open failed: " + ex.getMessage(), url);
        }
        if (stream.isEmpty()) {
            return AvatarLoadResult.error("no such classpath resource: "
                    + ref.module() + "/" + ref.path(), url);
        }
        ColladaDocument doc;
        try (InputStream in = stream.get()) {
            doc = new ColladaParser().parse(in);
        } catch (IOException | RuntimeException ex) {
            // ColladaParser.parse() throws an unchecked ColladaParseException
            // on malformed bytes; try-with-resources surfaces IOException on
            // close. Both cases are "the pool is broken for this URL", which
            // the plan asks degrade to an error result rather than a crash.
            return AvatarLoadResult.error("resolve failed: " + ex.getMessage(), url);
        }
        return AvatarLoadResult.loaded(doc, url);
    }

    /**
     * Non-empty caller URL wins; null/empty falls back to the pool's default
     * name. Extracted so the resolver's test can pin this selection rule
     * without also driving the classpath read.
     */
    static String selectUrl(String avatarModelUrlOrNull) {
        if (avatarModelUrlOrNull == null || avatarModelUrlOrNull.isEmpty()) {
            return AvatarPool.urlFor(AvatarPool.defaultName());
        }
        return avatarModelUrlOrNull;
    }
}
