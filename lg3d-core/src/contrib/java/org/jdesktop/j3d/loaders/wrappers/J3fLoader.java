/*
 * In-tree reimplementation of the j3d-contrib-utils J3fLoader, ported to the
 * Jogamp Java 3D 1.7 API (org.jogamp.java3d). See the traverser package for the
 * full rationale behind these in-tree replacements.
 *
 * J3F ("Java 3D Fly") files are scene graphs serialised with Java 3D's own
 * SceneGraphFileWriter, i.e. the j3dffi binary stream format. The original
 * loader simply wrapped com.sun.j3d.utils.scenegraph.io.SceneGraphFileReader;
 * this port wraps the equivalent Jogamp reader, which reconstructs the graph as
 * org.jogamp.java3d nodes. The optional J3dFly plugin user-data blob (a
 * com.sun.j3d.demos.j3dfly.plugins.PluginJ3fData object) is intentionally not
 * required: the reader logs a warning and yields null user data, which does not
 * affect the reconstructed scene graph.
 *
 * The fully-qualified name is preserved so that existing reflective lookups
 * (e.g. ModelBackground's Class.forName("org.jdesktop.j3d.loaders.wrappers.
 * J3fLoader")) resolve unchanged.
 */
package org.jdesktop.j3d.loaders.wrappers;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.Reader;
import java.net.URL;

import org.jogamp.java3d.BranchGroup;
import org.jogamp.java3d.loaders.IncorrectFormatException;
import org.jogamp.java3d.loaders.LoaderBase;
import org.jogamp.java3d.loaders.ParsingErrorException;
import org.jogamp.java3d.loaders.Scene;
import org.jogamp.java3d.loaders.SceneBase;
import org.jogamp.java3d.utils.scenegraph.io.NamedObjectException;
import org.jogamp.java3d.utils.scenegraph.io.ObjectNotLoadedException;
import org.jogamp.java3d.utils.scenegraph.io.SceneGraphFileReader;

/**
 * A Java 3D {@link org.jogamp.java3d.loaders.Loader} that reads J3F scene graph
 * files (those written by {@code SceneGraphFileWriter}) and exposes them as a
 * {@link Scene}.
 */
public class J3fLoader extends LoaderBase {

    private SceneGraphFileReader reader = null;
    private BranchGroup[] graphs = null;

    /**
     * Optional class loader handed to the underlying reader so that application
     * classes referenced by the serialised graph can be resolved.
     */
    private static ClassLoader classLoader = null;

    public J3fLoader() {
    }

    public static void setClassLoader(ClassLoader loader) {
        classLoader = loader;
    }

    public static ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public Scene load(String path) throws FileNotFoundException,
            IncorrectFormatException, ParsingErrorException {
        SceneBase scene;
        try {
            reader = new SceneGraphFileReader(new File(path));
            if (classLoader != null) {
                reader.setClassLoader(classLoader);
            }

            int count = reader.getBranchGraphCount();
            scene = new SceneBase();

            BranchGroup bg;
            graphs = new BranchGroup[count];
            if (count > 1) {
                // Multiple graphs: read each one and parent them under a single
                // branch group so the loader contract (one scene group) holds.
                bg = new BranchGroup();
                for (int i = 0; i < count; i++) {
                    graphs[i] = reader.readBranchGraph(i)[0];
                    bg.addChild(graphs[i]);
                }
            } else {
                bg = reader.readBranchGraph(0)[0];
                graphs[0] = bg;
            }
            scene.setSceneGroup(bg);

            // Copy across any named objects so getNamedObject() works. A name
            // that cannot be resolved is skipped rather than failing the load.
            String[] names = reader.getNames();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    try {
                        scene.addNamedObject(names[i],
                                reader.getNamedObject(names[i]));
                    } catch (ObjectNotLoadedException e) {
                        // ignore: object not part of the loaded graphs
                    } catch (NamedObjectException e) {
                        // ignore: unknown name
                    }
                }
            }

            reader.close();
        } catch (InvalidClassException e) {
            throw new IncorrectFormatException(e.getMessage());
        } catch (FileNotFoundException e) {
            throw new FileNotFoundException(path);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return scene;
    }

    /**
     * Returns the branch graphs read by the last successful {@link #load(String)}
     * call, or {@code null} if none has been loaded.
     */
    public BranchGroup[] getBranchGraphs() {
        return graphs;
    }

    /**
     * Returns the file-level user data blob, if any. For J3dFly-authored files
     * this references a plugin class that is not on the classpath, in which case
     * the reader returns {@code null}.
     */
    public Object getFileUserData() throws IOException {
        return reader.readUserData();
    }

    @Override
    public Scene load(URL url) throws FileNotFoundException,
            IncorrectFormatException, ParsingErrorException {
        throw new RuntimeException("NOT IMPLEMENTED");
    }

    @Override
    public Scene load(Reader reader) throws FileNotFoundException,
            IncorrectFormatException, ParsingErrorException {
        throw new RuntimeException("NOT IMPLEMENTED");
    }
}
