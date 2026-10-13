/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: the pure interpretation of a COLLADA animation
 * <channel target="..."> string.
 *
 * The 1.4.1 vocabulary puts "node-sid/element-sid" in the target and, per
 * exporter, an ".ARRAY" component suffix -- "humanoid-node/rotation.ANGLE"
 * (the shape the bundled M7c body authors, and the one the Khronos validator
 * emits) or a Blender-style sid that embeds the suffix, "rotation_euler_X".
 * The old jME-1.x Animator never saw this string at all -- its clips lived
 * inside the .jme binary -- so this classification is new ground for the
 * port, and it is exactly the kind of vocabulary-mapping decision the plan
 * keeps pure and asserts headless: which node moves, which channel of it,
 * and whether the port understands the element at all (a "matrix" target is
 * honestly unsupported: animating a whole 16-float matrix is a different
 * sampler model than per-array side-effect animation).
 */
package org.jdesktop.wonderland.renderer.animation;

/** A parsed {@code <channel target>}: the node, the animated element and the array. */
public final class ChannelTarget {

    /** Which side-effect transform element the channel drives. */
    public enum Kind { TRANSLATION, ROTATION, SCALE, UNSUPPORTED }

    private static final String[] ARRAYS = {"X", "Y", "Z", "W", "ANGLE"};

    private final String nodeSid;
    private final Kind kind;
    /** The upper-case component token ("X".."ANGLE"), or null when the export gives none. */
    private final String array;

    private ChannelTarget(String nodeSid, Kind kind, String array) {
        this.nodeSid = nodeSid;
        this.kind = kind;
        this.array = array;
    }

    /**
     * Parse one target string. Malformed input (blank, no node part) yields
     * {@link Kind#UNSUPPORTED} with a null node -- callers skip the channel
     * rather than throw, the port's standing degrade-don't-throw rule.
     */
    public static ChannelTarget parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ChannelTarget(null, Kind.UNSUPPORTED, null);
        }
        String trimmed = raw.trim();
        int slash = trimmed.lastIndexOf('/');
        String node = slash < 0 ? trimmed : trimmed.substring(0, slash);
        String element = slash < 0 ? "" : trimmed.substring(slash + 1);
        if (node.isEmpty()) {
            return new ChannelTarget(null, Kind.UNSUPPORTED, null);
        }
        String array = null;
        String base = element;
        int dot = element.lastIndexOf('.');
        if (dot >= 0) {
            String token = element.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
            if (isKnownArray(token)) {
                array = token;
                base = element.substring(0, dot);
            }
        }
        if (array == null) {
            // Blender style: the component rides on the sid itself, "..._X"
            int underscore = element.lastIndexOf('_');
            if (underscore >= 0) {
                String token = element.substring(underscore + 1).toUpperCase(java.util.Locale.ROOT);
                if (isKnownArray(token)) {
                    array = token;
                    base = element.substring(0, underscore);
                }
            }
        }
        Kind kind = kindOf(base.isEmpty() ? element : base);
        return new ChannelTarget(node, kind, kind == Kind.UNSUPPORTED ? null : array);
    }

    /** The animated node's sid / id, or null when the target was malformed. */
    public String nodeSid() {
        return nodeSid;
    }

    public Kind kind() {
        return kind;
    }

    /** The component token, or null: the clip factory then expands by output stride. */
    public String array() {
        return array;
    }

    public boolean supported() {
        return kind != Kind.UNSUPPORTED;
    }

    /**
     * The component index this array selects inside a bundle output source.
     *
     * @param stride the output accessor stride (1 for a single-value channel)
     * @return the offset, or -1 when the array cannot live in that stride
     */
    public int componentIndex(int stride) {
        if (stride <= 1) {
            return 0; // a single-value source carries exactly this array
        }
        if (array == null) {
            return -1; // expansion is the factory's job, not a component pick
        }
        switch (array) {
            case "X":
                return 0;
            case "Y":
                return stride >= 2 ? 1 : -1;
            case "Z":
                return stride >= 3 ? 2 : -1;
            case "W":
            case "ANGLE":
                return stride >= 4 ? 3 : -1;
            default:
                return -1;
        }
    }

    private static boolean isKnownArray(String token) {
        for (String a : ARRAYS) {
            if (a.equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static Kind kindOf(String elementLower) {
        String e = elementLower.toLowerCase(java.util.Locale.ROOT);
        if (e.startsWith("translation") || e.startsWith("location") || e.startsWith("position")) {
            return Kind.TRANSLATION;
        }
        if (e.startsWith("rotation") || e.startsWith("turn") || e.startsWith("quaternion")) {
            return Kind.ROTATION;
        }
        if (e.startsWith("scale") || e.startsWith("scaling")) {
            return Kind.SCALE;
        }
        return Kind.UNSUPPORTED; // includes "matrix": per-array vocabulary only
    }
}
