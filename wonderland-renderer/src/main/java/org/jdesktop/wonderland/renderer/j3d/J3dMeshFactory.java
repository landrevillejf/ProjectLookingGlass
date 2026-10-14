/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: build a Java 3D shape from the pure mesh data.
 *
 * The 2006 jmecolladaloader ended every primitive in a jogamp GeometryArray
 * wrapped in a Shape3D with an Appearance built from the material's colour /
 * texture. That is the code this factory replaces, rebuilt on the Jogamp fork
 * (org.jogamp.java3d, 1.7.2). Everything it consumes -- the flattened triangle
 * soup in ColladaMeshData, the resolved MaterialAppearance -- is already the
 * product of the pure, headless-tested conversion; the only Java 3D calls left
 * are the array / shape / appearance / texture constructors. Those need the
 * native rendering stack and are therefore exercised by the in-JVM probe, not
 * by the headless suite. Kept deliberately thin -- the coverage carrier lives
 * next door in the mesh and transform packages, and every decision this class
 * makes has already been made for it upstream.
 */
package org.jdesktop.wonderland.renderer.j3d;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.jdesktop.wonderland.renderer.mesh.MaterialAppearance;
import org.jdesktop.wonderland.renderer.mesh.TextureSource;
import org.jogamp.java3d.Appearance;
import org.jogamp.java3d.GeometryArray;
import org.jogamp.java3d.ImageComponent2D;
import org.jogamp.java3d.IndexedTriangleArray;
import org.jogamp.java3d.ColoringAttributes;
import org.jogamp.java3d.Shape3D;
import org.jogamp.java3d.Texture;
import org.jogamp.java3d.Texture2D;
import org.jogamp.java3d.TextureAttributes;
import org.jogamp.java3d.TransparencyAttributes;
import org.jogamp.vecmath.Color3f;

/** Turns one {@link ColladaMeshData} plus its resolved appearance into a {@link Shape3D}. */
public final class J3dMeshFactory {

    /**
     * Textures decoded from the store, keyed by path, so N primitives sharing a
     * material reuse one Texture2D. Java 3D does not allow a Texture to be added
     * to more than one Appearance without this kind of sharing.
     */
    private final Map<String, Served> textureCache = new ConcurrentHashMap<>();

    /**
     * Where texture bytes come from. Null keeps the M6a behaviour (a path is a
     * classpath resource or a file), which is what a bundled-fixture probe wants;
     * a live world over the wire installs the store-backed source instead.
     */
    private final TextureSource textures;

    /** The factory that reads textures off the classpath / file system. */
    public J3dMeshFactory() {
        this(null);
    }

    /** The factory that reads textures through {@code textures} (null: as before). */
    public J3dMeshFactory(TextureSource textures) {
        this.textures = textures;
    }

    /**
     * Build the Shape3D for one expanded primitive. The mesh data must have at
     * least one triangle; a primitive the loader walked out to nothing yields
     * null so the caller can skip it -- matching the 2006 loader silently
     * dropping empty draw elements.
     */
    public Shape3D createShape(ColladaMeshData mesh, MaterialAppearance appearance) {
        if (mesh == null || mesh.triangleCount() == 0) {
            return null;
        }
        GeometryArray geometry = buildGeometry(mesh);
        Appearance app = buildAppearance(appearance == null ? fallback() : appearance);
        return new Shape3D(geometry, app);
    }

    /**
     * The GeometryArray for one expanded primitive: an IndexedTriangleArray in
     * the exact vertex layout ColladaMeshData already produced. Because the
     * mesh builder unshares every referenced vertex, the coordinate / normal /
     * texcoord index arrays are the identity 0..vertexCount-1 -- no dedup step
     * is needed here and the shape draws exactly what the builder walked.
     */
    private static GeometryArray buildGeometry(ColladaMeshData mesh) {
        int vertexCount = mesh.vertexCount();
        int indexCount = mesh.triangleIndices().length;

        int format = GeometryArray.COORDINATES;
        boolean hasNormals = mesh.hasNormals();
        boolean hasTex = mesh.hasTexCoords();
        if (hasNormals) {
            format |= GeometryArray.NORMALS;
        }
        if (hasTex) {
            format |= GeometryArray.TEXTURE_COORDINATE_2;
        }

        // This fork's IndexedTriangleArray is not the Java 3D 1.6 signature the
        // old textbooks quote: it takes
        //   (vertexCount, vertexFormat, texCoordSetCount, texCoordSetMap,
        //    [vertexAttrCount, vertexAttrSizes,] indexCount)
        // -- no primitiveInfo at all, and the texture-coordinate set count and map
        // come *before* the index count. Passing the classic order compiles without
        // complaint and then fails at run time the moment the format carries
        // TEXTURE_COORDINATE_2 ("must specify at least one set of texture
        // coordinates", or "texCoord set mapping is not specified" when the classic
        // null map lands in the map slot). The read-back that proved the real slots
        // is build-gradle/scratch/WlProbe13: a construction in this order reports
        // the vertex and index counts that were asked for.
        IndexedTriangleArray arr = hasTex
                ? new IndexedTriangleArray(vertexCount, format, /*texCoordSetCount*/ 1,
                        /*texCoordSetMap*/ new int[] {0}, indexCount)
                : new IndexedTriangleArray(vertexCount, format, indexCount);

        int[] identity = identityIndices(vertexCount);
        arr.setCoordinateIndices(0, identity);
        // coordinates() clones; the setter takes ownership of the passed array
        // contents, so no aliasing to worry about.
        arr.setCoordinates(0, mesh.coordinates());
        if (hasNormals) {
            arr.setNormalIndices(0, identity);
            arr.setNormals(0, mesh.normals());
        }
        if (hasTex) {
            arr.setTextureCoordinateIndices(0, /*texCoordSetIndex*/ 0, identity);
            arr.setTextureCoordinates(0, /*texCoordSetIndex*/ 0, mesh.texCoords());
        }

        // Capability bits the lg3d-core SceneGraphView / pick / collision paths
        // expect on any attached geometry; without these a live universe will
        // throw CapabilityNotSetException the first time a renderer reads back.
        arr.setCapability(GeometryArray.ALLOW_REF_DATA_READ);
        arr.setCapability(GeometryArray.ALLOW_COORDINATE_READ);
        arr.setCapability(GeometryArray.ALLOW_NORMAL_READ);
        arr.setCapability(GeometryArray.ALLOW_TEXCOORD_READ);
        arr.setCapability(GeometryArray.ALLOW_FORMAT_READ);
        arr.setCapability(GeometryArray.ALLOW_COUNT_READ);
        return arr;
    }

    private Appearance buildAppearance(MaterialAppearance ma) {
        Appearance app = new Appearance();
        app.setCapability(Appearance.ALLOW_TEXTURE_READ);
        app.setCapability(Appearance.ALLOW_TEXTURE_WRITE);
        app.setCapability(Appearance.ALLOW_TEXTURE_ATTRIBUTES_READ);
        app.setCapability(Appearance.ALLOW_TRANSPARENCY_ATTRIBUTES_READ);
        app.setCapability(Appearance.ALLOW_TRANSPARENCY_ATTRIBUTES_WRITE);

        // No Material at all, on purpose. Java 3D enables lighting as soon as an
        // Appearance carries a Material, and this build answers a full-white
        // ambient light with about 18 % of the authored colour (measured:
        // (0.9,0.1,0.1) came back as (46,5,5), while the same quad drawn with no
        // Material came back as (229,25,25)). Wonderland's shipped art is
        // pre-lit -- the 2006 client baked its light into the textures, which is
        // why the reference screenshot is flat and evenly coloured -- so the
        // faithful port is a flat ColoringAttributes, not a shading model.
        // See build-gradle/scratch/WlProbe7 for the run that proved it.
        ColoringAttributes color = new ColoringAttributes();
        color.setColor(new Color3f(ma.red(), ma.green(), ma.blue()));
        color.setCapability(ColoringAttributes.ALLOW_COLOR_READ);
        color.setCapability(ColoringAttributes.ALLOW_COLOR_WRITE);
        app.setColoringAttributes(color);

        // Java 3D models opacity at the Appearance level via
        // TransparencyAttributes; the pure TextureBindingPlan already folded the
        // COLLADA transparency factor into rgba[3], so we translate 1-alpha to
        // the blend factor here. Fully opaque material -> TRANSPARENCY_NONE so
        // the renderer does not pay for blending it does not need.
        float alpha = ma.alpha();
        if (alpha < 1.0f) {
            TransparencyAttributes ta = new TransparencyAttributes(
                    TransparencyAttributes.BLENDED, 1.0f - alpha);
            ta.setCapability(TransparencyAttributes.ALLOW_VALUE_WRITE);
            ta.setCapability(TransparencyAttributes.ALLOW_MODE_WRITE);
            app.setTransparencyAttributes(ta);
        }

        if (ma.isTextured()) {
            Served served = loadTexture(ma.texturePath());
            if (served != null) {
                app.setTexture(served.texture());
                // DECAL, not MODULATE: with no Material there is no diffuse to
                // multiply, and MODULATE against the default polygon colour
                // darkens every wall. The texture IS the surface, which is what
                // the shipped art expects.
                //
                // REPLACE, not DECAL, when the image carries transparent pixels:
                // DECAL blends the texture <em>into the polygon colour</em>, so a
                // name tag would arrive as white text on a white card and a leaf
                // as a green square. REPLACE keeps the texture's own alpha, which
                // is then honoured by the blend below. Measured, not assumed --
                // see the probe run that turned the tags' card off.
                TextureAttributes txa = new TextureAttributes();
                txa.setTextureMode(served.translucent()
                        ? TextureAttributes.REPLACE : TextureAttributes.DECAL);
                txa.setCapability(TextureAttributes.ALLOW_MODE_WRITE);
                app.setTextureAttributes(txa);
                if (served.translucent() && alpha >= 1.0f) {
                    TransparencyAttributes ta = new TransparencyAttributes(
                            TransparencyAttributes.BLENDED, 0f);
                    ta.setCapability(TransparencyAttributes.ALLOW_VALUE_WRITE);
                    ta.setCapability(TransparencyAttributes.ALLOW_MODE_WRITE);
                    app.setTransparencyAttributes(ta);
                }
            }
            // A null texture here is the *missing-file* case: the binding plan
            // already decided "textured" from the effect graph, but the store
            // could not actually serve the bytes. Degrade to the diffuse colour
            // -- the same "warning not failure" the 2006 loader treated.
        }
        return app;
    }

    /**
     * Decode + wrap one texture. Java 3D's Texture2D/ImageComponent2D
     * constructors run under the probe only; the ImageIO read that feeds them
     * is pure JDK and can headless-run, but it is only reached here so we keep
     * the whole method probe-side. A null path or an unreadable image returns
     * null so the caller drops to the material colour.
     */
    private Served loadTexture(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Served cached = textureCache.get(path);
        if (cached != null) {
            return cached;
        }
        try {
            BufferedImage img = (textures == null)
                    ? ImageIO.read(resolveResource(path))
                    : decodeServed(path);
            if (img == null) {
                return null;
            }
            Texture2D tex = new Texture2D(Texture2D.BASE_LEVEL, Texture2D.RGBA,
                    img.getWidth(), img.getHeight());
            tex.setImage(0, toComponent(img));
            tex.setCapability(Texture.ALLOW_IMAGE_READ);
            Served served = new Served(tex, isTranslucent(img));
            textureCache.put(path, served);
            return served;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Whether an image has anything to see through. Sampled on a stride rather
     * than scanned: this runs once per texture at build time, and a fully opaque
     * JPEG (no alpha channel at all) is the common case and costs nothing.
     */
    private static boolean isTranslucent(BufferedImage img) {
        if (!img.getColorModel().hasAlpha()) {
            return false;
        }
        for (int y = 0; y < img.getHeight(); y += 3) {
            for (int x = 0; x < img.getWidth(); x += 3) {
                if ((img.getRGB(x, y) >>> 24) != 0xFF) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One decoded texture and whether its alpha has anything to show through.
     * The flag decides the texture mode, so it is remembered with the texture
     * rather than re-derived from pixels the appearance builder cannot see.
     */
    private record Served(Texture texture, boolean translucent) {
    }

    /**
     * Ask the injected source for one texture and decode it. A null answer (or
     * bytes ImageIO cannot read) is the missing-file case, handled by the caller
     * as a colour fallback -- so this never throws.
     */
    private BufferedImage decodeServed(String path) throws IOException {
        byte[] bytes = textures.read(path);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static ImageComponent2D toComponent(BufferedImage img) {
        // Upload with the image's origin at the UPPER LEFT (yUp=false). This is
        // not a guess about Java 3D -- it is the one convention every textured
        // consumer in this renderer already documents and is authored against:
        // SkyArt.rowOf, WorldScene.tagQuad and WorldScene.flatQuad each state
        // that "the image's first row is sampled at v = 0", and the shipped
        // COLLADA exports author their V coordinate top-down the same way. A
        // default-constructed ImageComponent2D + set(img) instead reads
        // bottom-up (v = 0 lands on the image's LAST row), so that single
        // mismatch inverted the sky ("ciel a l'envers") and every sign, poster
        // and name tag in every world at once. Constructing the component from
        // the image with yUp=false is the proven path lg3d's own upright text
        // already uses (GlassyTextTextureGenerator) and, because it sits on the
        // one shared upload, it corrects the orientation for every textured
        // primitive in every stock world rather than one world at a time.
        ImageComponent2D comp = new ImageComponent2D(
                ImageComponent2D.FORMAT_RGBA, img,
                /*byReference*/ false, /*yUp*/ false);
        comp.setCapability(ImageComponent2D.ALLOW_IMAGE_READ);
        return comp;
    }

    /**
     * Resolve the texture path against the classpath first, then the file
     * system. The asset store exposes textures through its own open() channel
     * at M6c; here we accept both a filesystem path and a resource URL so the
     * probe can drive the factory against a bundled test fixture without the
     * full store wiring.
     */
    private static URL resolveResource(String path) throws IOException {
        URL u = J3dMeshFactory.class.getClassLoader().getResource(path);
        if (u != null) {
            return u;
        }
        java.io.File f = new java.io.File(path);
        if (f.isFile() && f.canRead()) {
            return f.toURI().toURL();
        }
        throw new IOException("texture not found: " + path);
    }

    private static int[] identityIndices(int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = i;
        }
        return out;
    }

    private static MaterialAppearance fallback() {
        return new MaterialAppearance(0.5f, 0.5f, 0.5f, 1.0f, null);
    }
}
