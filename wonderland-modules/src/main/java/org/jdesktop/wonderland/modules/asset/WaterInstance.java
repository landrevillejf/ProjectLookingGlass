/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (assetmgr): one JMEWaterInstance record of a .mtg scene.
 *
 * Roadmap P2.3's "re-honour" starts here. The meeting complex's ground is a
 * ColladaInstance fold; its POND is a line this reader used to drop on the
 * floor -- {@code Instance <name> org.jdesktop.mtgame.JMEWaterInstance tx ty
 * tz ax ay az angle sx sy sz width length <ground>} (fifteen tokens). The 2006
 * mtgame engine
 * answered that record with an animated water plane spanning the scene; a
 * port that folds every other record and forgets the water is the reason the
 * complex loads with dry ground.
 *
 * This type is the record made readable: parsed, never throwing (a malformed
 * author line costs one pond, not the scene), and carrying the plane the
 * renderer needs -- centre, orientation, extent. The animation lives with the
 * renderer (WaterArt), because a data record has no clock; what is promised
 * here is that the pond SURVIVES the read to reach one.
 */
package org.jdesktop.wonderland.modules.asset;

/** One water plane an MTGame {@code .mtg} scene placed. */
public record WaterInstance(String name,
        double tx, double ty, double tz,
        double axisX, double axisY, double axisZ, double angleRad,
        double scaleX, double scaleY, double scaleZ,
        double width, double length, String groundName) {

    /** The MTGame class name a water record carries. */
    static final String WATER_CLASS = "JMEWaterInstance";

    /**
     * Parse {@code Instance <name> <class> tx ty tz ax ay az angle sx sy sz
     * width length <ground>}. The twelve transform-and-extent numbers sit
     * where a ColladaInstance record would carry ten plus a file: the engine's
     * water plane is placed, spun, scaled AND sized, and the trailing token is
     * the ground material it floats over, not an artifact to load. Returns null
     * (never throws) for a line that is not a water record or whose numbers do
     * not parse, matching {@code Instance.parse}'s "one bad line, one dropped
     * object" rule.
     */
    static WaterInstance parse(String line) {
        String[] t = line.split("\\s+");
        if (t.length < 15 || !t[2].contains(WATER_CLASS)) {
            return null;
        }
        try {
            return new WaterInstance(t[1],
                    Double.parseDouble(t[3]), Double.parseDouble(t[4]),
                    Double.parseDouble(t[5]), Double.parseDouble(t[6]),
                    Double.parseDouble(t[7]), Double.parseDouble(t[8]),
                    Double.parseDouble(t[9]), Double.parseDouble(t[10]),
                    Double.parseDouble(t[11]), Double.parseDouble(t[12]),
                    Double.parseDouble(t[13]), Double.parseDouble(t[14]),
                    t[15]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Whether the spin is the identity (every shipped pond lies flat). */
    public boolean hasRotation() {
        return angleRad != 0.0;
    }

    /** The plane's half-extents, the shape a renderer builds a quad from. */
    public double halfWidth() {
        return width / 2.0;
    }

    /** The long half-extent. */
    public double halfLength() {
        return length / 2.0;
    }

    @Override
    public String toString() {
        return "WaterInstance{" + name + " at (" + tx + ", " + ty + ", " + tz
                + ") " + width + "x" + length + " over " + groundName + "}";
    }
}
