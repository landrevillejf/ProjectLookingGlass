/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland P5.1: a world's behaviour is one Mandela script it ships as an asset.
 *
 * The renderer draws what the server says a cell is; it never asks a cell to do
 * anything. This is the seam that lets a world decide something about its own
 * cells without a rebuild: the world publishes a single {@code behaviour.mnd}
 * beside its art, and for each cell this class binds that cell's plain facts
 * (its label, its place, whether it is a model, an avatar or a picture), runs
 * the script, and reads back the value the script left. That value is the only
 * thing a behaviour may influence here -- an annotation the walk-in shows over
 * the cell -- because a Mandela script sees no scene graph and holds no Java 3D
 * handle (see the dependency rule in the language's own AGENTS.md). The renderer
 * is the one that would apply a returned transform or colour; the decision of
 * what to do is the script's.
 *
 * <p><strong>Why the asset is read through {@link HttpWorldStore}.</strong> A
 * behaviour is art the world module ships, and the store already caches one read
 * per path and treats a transport failure as "absent" rather than a throw -- the
 * exact two properties a render-path optional needs: fetched once, and its
 * absence is never a failed world. No new endpoint, no new wire format.</p>
 *
 * <p><strong>What is safe to call headlessly.</strong> {@link #scriptOf},
 * {@link #bindingsOf} and {@link #annotation} are pure over the parsed cell and
 * the real Mandela engine, which runs without a display; the effect of an
 * annotation on the live scene is the only display-bound part, verified by the
 * walk-in probe rather than asserted here.</p>
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jdesktop.lg3d.mandela.api.Mandela;

/** Runs one world's {@code behaviour.mnd} over its cells. */
final class WorldBehavior {

    /**
     * The asset name a world's behaviour lives under, beside its models and
     * textures. One per world, not one per cell: the script is handed each cell
     * in turn, which is what lets a whole world be re-annotated by a file the
     * server already knows how to serve.
     */
    static final String SCRIPT_PATH = "behaviour.mnd";

    private WorldBehavior() {
    }

    /**
     * The world's behaviour source, or {@code null} when it ships none -- which
     * is the normal case and must change nothing. A blank or whitespace-only
     * file is treated as "none" too; a half-written file is not a behaviour.
     *
     * <p>The read is exactly the store's: cached, and a failure yields absent
     * bytes rather than an exception, so a hiccup on the behaviour asset is a
     * world without behaviour, not a world that failed to open.</p>
     */
    static String scriptOf(HttpWorldStore store, String worldModule) {
        if (store == null || worldModule == null) {
            return null;
        }
        try (InputStream in = store.open(worldModule, SCRIPT_PATH).orElse(null)) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? null : text;
        } catch (Exception readFailed) {
            return null;
        }
    }

    /**
     * The facts about one cell a behaviour script may read, as the plain values
     * {@link Mandela#eval(String, Map)} binds. Every name here is a scalar a
     * script can use without importing a host type; a missing number is bound as
     * {@code 0.0} so a script doing arithmetic never trips on a null.
     */
    static Map<String, Object> bindingsOf(WorldCatalog.Cell cell) {
        Map<String, Object> bound = new LinkedHashMap<>();
        if (cell == null) {
            return bound;
        }
        bound.put("label", cell.label());
        bound.put("name", cell.name);
        bound.put("cellType", cell.cellType);
        bound.put("x", orZero(cell.x));
        bound.put("y", orZero(cell.y));
        bound.put("z", orZero(cell.z));
        bound.put("width", orZero(cell.width));
        bound.put("depth", orZero(cell.depth));
        bound.put("hasModel", cell.hasModel());
        bound.put("isAvatar", cell.isAvatar());
        bound.put("isGui", cell.isGui());
        return bound;
    }

    /**
     * What the behaviour says about one cell, or {@code fallback} when it has
     * nothing to say. This is the whole contract's safety: a null or blank
     * script, a script that throws, a script that reads a name nobody bound, and
     * a script whose last value is empty all collapse to {@code fallback} --
     * never an exception onto the render path, which may be the code that keeps
     * the world on screen.
     */
    static String annotation(String script, WorldCatalog.Cell cell, String fallback) {
        if (script == null || script.isBlank()) {
            return fallback;
        }
        Object result;
        try {
            result = Mandela.eval(script, bindingsOf(cell));
        } catch (Throwable behaviourFailed) {
            return fallback;
        }
        if (result == null) {
            return fallback;
        }
        String text = String.valueOf(result).strip();
        return text.isEmpty() ? fallback : text;
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /**
     * The facts about one trigger crossing a script may read, as the plain values
     * {@link Mandela#eval(String, Map)} binds: the zone's name ({@code trigger}),
     * whether the walker {@code action} is entering or leaving it, and the pose
     * {@code x/y/z} they crossed at. A trigger script and a cell-annotation script
     * are the same {@code behaviour.mnd} asked a different question -- bound a
     * different set of names -- which is why a world that only annotates labels
     * reads nothing here and changes nothing.</p>
     */
    static Map<String, Object> triggerBindings(String zoneId, String action,
            double x, double y, double z) {
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put("trigger", zoneId == null ? "" : zoneId);
        bound.put("action", action == null ? "" : action);
        bound.put("x", x);
        bound.put("y", y);
        bound.put("z", z);
        return bound;
    }

    /**
     * What the behaviour says about one trigger crossing, or {@code null} when it
     * has nothing to say -- the host then shows no note. The same safety as
     * {@link #annotation}: a null or blank script, one that throws, one that reads
     * a name no trigger bound, and one whose value is empty all collapse to
     * {@code null}, so wiring a trigger can never throw onto the walk and a world
     * whose behaviour was written only for cells stays silent as it always was.</p>
     *
     * @param action {@code "enter"} or {@code "exit"}, bound as the script's
     *               {@code action} name so one file can answer both
     */
    static String triggerNote(String script, String zoneId, String action,
            double x, double y, double z) {
        if (script == null || script.isBlank()) {
            return null;
        }
        Object result;
        try {
            result = Mandela.eval(script, triggerBindings(zoneId, action, x, y, z));
        } catch (Throwable behaviourFailed) {
            return null;
        }
        if (result == null) {
            return null;
        }
        String text = String.valueOf(result).strip();
        return text.isEmpty() ? null : text;
    }
}
