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
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.jdesktop.wonderland.renderer.mesh.ColladaMeshData;
import org.jdesktop.wonderland.renderer.mesh.MaterialAppearance;
import org.jogamp.java3d.Appearance;
import org.jogamp.java3d.GeometryArray;
import org.jogamp.java3d.ImageComponent2D;
import org.jogamp.java3d.IndexedTriangleArray;
import org.jogamp.java3d.Material;
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
    private final Map<String, Texture> textureCache = new ConcurrentHashMap<>();

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

        // The 5-arg IndexedTriangleArray constructor takes (vertexCount,
        // format, primitiveInfo, initialIndexArray, indexCount); the 7-arg
        // overload adds (texCoordSetMap, texCoordSetCount) and is used only
        // when a TEXCOORD semantic actually resolved upstream.
        IndexedTriangleArray arr = hasTex
                ? new IndexedTriangleArray(vertexCount, format, /*primitiveInfo*/ 0,
                        null, indexCount, /*texCoordSetMap*/ null, /*texCoordSetCount*/ 1)
                : new IndexedTriangleArray(vertexCount, format, /*primitiveInfo*/ 0,
                        null, indexCount);

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
        app.setCapability(Appearance.ALLOW_MATERIAL_READ);
        app.setCapability(Appearance.ALLOW_MATERIAL_WRITE);
        app.setCapability(Appearance.ALLOW_TEXTURE_READ);
        app.setCapability(Appearance.ALLOW_TEXTURE_WRITE);
        app.setCapability(Appearance.ALLOW_TEXTURE_ATTRIBUTES_READ);
        app.setCapability(Appearance.ALLOW_TRANSPARENCY_ATTRIBUTES_READ);
        app.setCapability(Appearance.ALLOW_TRANSPARENCY_ATTRIBUTES_WRITE);

        Material mat = new Material();
        mat.setCapability(Material.ALLOW_COMPONENT_READ);
        mat.setCapability(Material.ALLOW_COMPONENT_WRITE);
        Color3f diffuse = new Color3f(ma.red(), ma.green(), ma.blue());
        mat.setDiffuseColor(diffuse);
        mat.setAmbientColor(diffuse);
        app.setMaterial(mat);

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
            Texture tex = loadTexture(ma.texturePath());
            if (tex != null) {
                app.setTexture(tex);
                TextureAttributes txa = new TextureAttributes();
                txa.setTextureMode(TextureAttributes.MODULATE);
                txa.setCapability(TextureAttributes.ALLOW_MODE_WRITE);
                app.setTextureAttributes(txa);
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
    private Texture loadTexture(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Texture cached = textureCache.get(path);
        if (cached != null) {
            return cached;
        }
        try {
            BufferedImage img = ImageIO.read(resolveResource(path));
            if (img == null) {
                return null;
            }
            Texture2D tex = new Texture2D(Texture2D.BASE_LEVEL, Texture2D.RGBA,
                    img.getWidth(), img.getHeight());
            tex.setImage(0, toComponent(img));
            tex.setCapability(Texture.ALLOW_IMAGE_READ);
            textureCache.put(path, tex);
            return tex;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static ImageComponent2D toComponent(BufferedImage img) {
        ImageComponent2D comp = new ImageComponent2D(
                ImageComponent2D.FORMAT_RGBA, img.getWidth(), img.getHeight(),
                /*byReference*/ false, /*isReadable*/ true);
        comp.setCapability(ImageComponent2D.ALLOW_IMAGE_READ);
        comp.set(img);
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
