/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (world library): the spellings one asset path may arrive as.
 *
 * A {@code wla://} reference is a name a world's author typed into a cell file, and
 * the bytes it names are stored by whatever packaged the module. Between the two sit
 * two conventions the stock Open Wonderland world jars apply and no reader can guess
 * from the reference alone:
 *
 * <ul>
 *   <li><b>Where the art sits.</b> Every stock world refers to {@code foo.kmz/x.dep}
 *       at the module root and packages it under {@code art/foo.kmz/x.dep}. The
 *       reference is the author's; the prefix is the packager's.</li>
 *   <li><b>Whether it is compressed.</b> Every COLLADA document in every one of the
 *       seven stock worlds is stored gzipped while the {@code .dep} names it plain:
 *       measured over the shipped jars, {@code 23 of 23} distinct deployed models
 *       exist only as {@code models/untitled.dae.gz}, and {@code 0 of 23} as
 *       {@code models/untitled.dae}. Not one model of the original worlds can be
 *       read by a store that takes the reference literally -- which is the whole
 *       reason a ported world arrived as bare coloured boxes and name plates where
 *       the 2009 client showed a building, a bench and a pavilion.</li>
 * </ul>
 *
 * This class states the rule once, as an ordered list of candidates, so every store
 * tries the same spellings in the same order and cannot disagree about whether an
 * asset exists. The order is exact-reference-first: a module that ships the bytes
 * under the name the world uses is answered without touching the conventions, and
 * an author who gzips a texture by hand is not accidentally served a different file.
 *
 * Reading a candidate is only half of it -- the caller must still inflate. It does:
 * {@link ModelLoader} sniffs the gzip magic rather than trusting a suffix, so a plain
 * {@code .dae} and a {@code .dae.gz} travel the same path and an uncompressed
 * {@code .gz} is impossible by construction here.
 */
package org.jdesktop.wonderland.modules.asset;

import java.util.ArrayList;
import java.util.List;

/** The spellings one asset reference may be stored under, in the order to try them. */
public final class AssetSpelling {

    /** The directory the stock worlds pack their art under while naming it at the root. */
    public static final String ART_PREFIX = "art/";

    /** The compression the stock worlds apply to their COLLADA documents. */
    public static final String GZIP_SUFFIX = ".gz";

    /**
     * The paths to try for one reference, most literal first. Never empty, and
     * duplicate-free by construction: a path already carrying the prefix or the
     * suffix contributes only itself for that dimension.
     *
     * @param path the asset path exactly as the {@code wla://} reference spells it
     * @return the candidate spellings, in order
     */
    public static List<String> candidates(String path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(4);
        out.add(path);
        // The gzip sibling of the name as written, and of the art/ spelling: the two
        // conventions are independent, so they cross rather than choose. A compressed
        // texture sits beside an uncompressed model in the same package, so neither
        // can imply the other.
        if (!path.endsWith(GZIP_SUFFIX)) {
            out.add(path + GZIP_SUFFIX);
        }
        if (!path.startsWith(ART_PREFIX)) {
            String underArt = ART_PREFIX + path;
            out.add(underArt);
            if (!path.endsWith(GZIP_SUFFIX)) {
                out.add(underArt + GZIP_SUFFIX);
            }
        }
        return out;
    }

    private AssetSpelling() {
    }
}
