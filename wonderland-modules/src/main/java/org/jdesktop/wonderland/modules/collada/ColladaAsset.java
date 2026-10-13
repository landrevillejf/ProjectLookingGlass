/*
 * wonderland-modules M5a (colladaloader): a COLLADA <asset>.
 *
 * The document header the authoring tool writes (units, up axis, timestamps,
 * tool). The loader keeps up_axis + the metre scale because the 2006 pipeline
 * used them to normalise a model's units; the rest is provenance.
 */
package org.jdesktop.wonderland.modules.collada;

/** The {@code <asset>} element that opens every COLLADA document. */
public final class ColladaAsset {

    private final String upAxis;
    private final double unitMeter;
    private final String authoringTool;
    private final String created;
    private final String modified;

    public ColladaAsset(String upAxis, double unitMeter, String authoringTool,
                        String created, String modified) {
        this.upAxis = upAxis;
        this.unitMeter = unitMeter;
        this.authoringTool = authoringTool;
        this.created = created;
        this.modified = modified;
    }

    /** {@code X_UP} / {@code Y_UP} / {@code Z_UP}, defaulting to the COLLADA {@code Y_UP}. */
    public String upAxis() {
        return upAxis == null ? "Y_UP" : upAxis;
    }

    /** The metre scale of the document unit (1.0 when absent). */
    public double unitMeter() {
        return unitMeter <= 0 ? 1.0 : unitMeter;
    }

    public String authoringTool() {
        return authoringTool;
    }

    public String created() {
        return created;
    }

    public String modified() {
        return modified;
    }
}
