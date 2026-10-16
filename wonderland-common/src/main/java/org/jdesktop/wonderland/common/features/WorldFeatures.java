/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland roadmap, the wiring phase: the one vocabulary the live desktop
 * asks before it connects any of the P0..P5 kernels.
 *
 * Why this exists as a class rather than a scattering of System.getProperty
 * calls: the roadmap's kernels were each built, tested to 100 % and left
 * un-called, and one reason given for that was "it changes a shipped world's
 * pixels, so it must be opt-in". A rule stated in five call sites in five
 * dialects is how a feature ends up reachable only from a probe. Here the rule
 * is stated once: every roadmap feature is ON, and one property turns the whole
 * program off for a person who needs the pre-wiring behaviour back.
 *
 * The default is ON on purpose. OFF-by-default is what let "delivered" and
 * "invisible" both be true at once, which is the defect being repaired.
 *
 * No System.getProperty in the tested method: a reader function is passed, so
 * a headless suite never mutates process-wide properties (a real hazard here,
 * since the same JVM runs every module's tests).
 */
package org.jdesktop.wonderland.common.features;

import java.util.function.UnaryOperator;

/** The roadmap feature switches, read from {@code lg.wonderland.<name>}. */
public final class WorldFeatures {

    /**
     * The master switch. {@code off} disables every roadmap feature at once,
     * including one that is explicitly named on -- a person pulling this lever
     * wants the old renderer, not an argument about precedence.
     */
    public static final String MASTER = "lg.wonderland.features";

    /** The prefix every per-feature switch is read under. */
    public static final String PREFIX = "lg.wonderland.";

    // The feature names, spelled once so a typo cannot silently become a
    // second, never-read property.
    public static final String LIGHTING = "lighting";
    public static final String CULLING = "culling";
    public static final String COLLISION = "collision";
    public static final String AUDIO = "audio";
    public static final String CAMERA = "camera";
    public static final String BEHAVIOUR = "behaviour";
    public static final String WATER = "water";
    public static final String TRIGGER = "trigger";
    public static final String SHADOWS = "shadows";
    /**
     * The P3.2 post-process bloom on the live 3D canvas. Like every switch here it
     * is ON by default; unlike the scene kernels it is a cosmetic GPU pass, so
     * turning it off ({@code lg.wonderland.bloom=off}) changes how a world reads
     * without changing what it contains.
     */
    public static final String BLOOM = "bloom";

    private WorldFeatures() {
        // holder: no instances
    }

    /** Whether the named feature is live in this JVM. */
    public static boolean enabled(String name) {
        return enabled(System.getProperty(MASTER), System.getProperty(PREFIX + name));
    }

    /**
     * The whole decision as a pure function of the two values a reader would
     * fetch: the master and the feature's own switch. Exposed so the rule is
     * asserted directly instead of through global property mutation.
     *
     * @param master the raw value of {@link #MASTER}, may be null
     * @param own    the raw value of {@code lg.wonderland.<name>}, may be null
     * @return true when the feature should run; a missing or unreadable value
     *         means ON, because a typo in a property is not an instruction to
     *         ship the world without its features
     */
    public static boolean enabled(String master, String own) {
        if (isOff(master)) {
            return false;
        }
        if (own == null) {
            return true;
        }
        if (isOn(own)) {
            return true;
        }
        return !isOff(own);
    }

    private static boolean isOff(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("off") || v.equals("false") || v.equals("no") || v.equals("0");
    }

    private static boolean isOn(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("on") || v.equals("true") || v.equals("yes") || v.equals("1");
    }

    /**
     * What this JVM decided, in one line, for the desktop console and the HUD.
     * A person who sees an unexpected world must be able to tell which rules
     * were read without re-reading the source, so the line names the property
     * it read, not just the answer.
     */
    public static String summary(UnaryOperator<String> reader) {
        StringBuilder out = new StringBuilder("Wonderland features (")
                .append(MASTER).append('=')
                .append(read(reader, MASTER, "on")).append("):");
        String[] names = {LIGHTING, CULLING, COLLISION, AUDIO, CAMERA, BEHAVIOUR,
                WATER, TRIGGER, SHADOWS, BLOOM};
        for (String name : names) {
            out.append(' ').append(name).append('=')
                    .append(enabled(reader.apply(MASTER), reader.apply(PREFIX + name))
                            ? "on" : "off");
        }
        return out.toString();
    }

    private static String read(UnaryOperator<String> reader, String key, String missing) {
        String v = reader.apply(key);
        return v == null || v.isBlank() ? missing : v;
    }
}
