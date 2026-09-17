# lg3d-incubator

A **grab-bag of independent experimental Project Looking Glass applications** —
the "incubator" where community and prototype apps lived.

> The `src/` tree is the original Project Looking Glass source dump, migrated to
> build on a modern toolchain. See the [root README](../README.md) for the full
> port overview.

## Build status

Part of the Gradle build (JDK 21 toolchain). The legacy Ant build was a
meta-target that ran a per-app `subant` over `src/build-scripts/build-*.xml` with
`failonerror="false"`: each app was compiled and jarred on its own and any app
that failed was silently skipped. This port compiles the whole `src/classes` tree
as one source set against `lg3d-core` plus every bundled `ext` jar.

```bash
./gradlew :lg3d-incubator:build   # jar -> build-gradle/libs/lg3d-incubator-1.0.1-dev.jar
```

The jar is placed on the desktop's classpath by the `lg3d-core:run` task. It also
supplies the **background manager** (`org.jdesktop.lg3d.apps.bgmanager`) whose
`BgConfig.xml` and per-background directories are assembled into the runtime
`resources/Backgrounds/` tree by the `lg3d-core:runtimeResources` task.

## Image Studio

`org.jdesktop.lg3d.apps.imagestudio` is a full-featured image editor with an
**lg3d-native 3D UI** — the canvas, toolbar, slider, histogram and filmstrip are
all Java 3D scene-graph nodes, not Swing (Swing is used only for the two native
file dialogs). It is launched from the start menu (Utilities) with
`java org.jdesktop.lg3d.apps.imagestudio.ImageStudioApp`. The descriptor lives in
[`lg3d-demo-apps/src/config/imagestudio.lgcfg`](../lg3d-demo-apps/src/config/imagestudio.lgcfg)
rather than this module's own `src/config`, because discovery only scans
`config/demo` and `config/incubator` while the incubator's `src/config` is
bundled to `config/` — the same precedent the Widget Gallery follows.

| Class | Role |
| --- | --- |
| `ImageStudioApp` | `main` entry point: builds the frame, then enables + shows it. |
| `ImageStudioFrame3D` | The `Frame3D` window; lays out the scene and owns the `EditorModel`. |
| `EditorModel` | Original/current image, bounded undo/redo, listeners, continuous-edit preview. |
| `JaiProcessor` | The JAI bridge: image operators, conversions, histogram, and file I/O. |
| `ImageCanvas3D` | Textured-quad image view with mouse-wheel zoom and reset-view. |
| `Toolbar3D` | Category tabs, operation buttons, undo/redo/reset/fit; hosts the slider. |
| `Slider3D` | Draggable 3D slider for the active operation's parameter. |
| `Histogram3D` | Log-scaled 256-bin RGB histogram, redrawn on every model change. |
| `FileStrip3D` | `~/Pictures` filmstrip plus Open / Save / Save As (native dialogs). |
| `Ui3D` | Shared widget factory (colours, panels, labels, buttons). |

The toolbar exposes 27 operations in four categories, each mapped to a JAI
operator: **Geometry** (Flip H/V, Rot 90, Rotate, Scale, Pixelate, Border),
**Color** (Brighter, Contrast, Gamma, Gray, Sepia, Invert, Posterize,
Threshold), **Filter** (Blur, Sharpen, Emboss, Edges) and **Math** (Add,
Subtract, Multiply, Abs, AND, OR, XOR, Noise). `JaiProcessor` additionally
provides crop and 180/270 rotations used internally.

### JAI dependency

The vendored JAI jars live in [`ext/`](ext) and are on this module's *compile*
classpath (`fileTree ext/**/*.jar`). Two are also needed at *runtime* on the
desktop, so the `lg3d-core:run` task adds `jai_core.jar` (`javax.media.jai.*`,
whose `META-INF` registry auto-registers the standard operators) and
`jai_codec.jar` (`com.sun.media.jai.codec.*`, the TIFF/BMP path) to its classpath,
and passes `--add-exports java.desktop/sun.awt.image=ALL-UNNAMED` — JAI's
`RasterAccessor` fast path references JDK-internal `sun.awt.image` raster classes
that JDK 21 strongly encapsulates, so without the export every operator fails
with `IllegalAccessError`. (`jaimlib.jar` in the same directory is
`com.wilko.jaim`, an unrelated AIM library — not JAI — and is deliberately
excluded.) Image I/O uses `javax.imageio.ImageIO` for PNG/JPEG (robust on JDK 21)
and reserves the JAI codec for TIFF/BMP, avoiding JAI's JPEG encoder which
references the JDK-removed `com.sun.image.codec.jpeg`.

## Excluded apps

A handful of apps cannot be compiled here — the legacy `failonerror="false"`
build never produced jars for them either. They are excluded as whole
self-contained apps.

**Third-party libraries absent from the repository** (and not on Maven Central
under a compatible coordinate):

| App | Missing dependency |
| --- | --- |
| `nu/koidelab/**` (Cosmo) | Jini/JavaSpaces (`net.jini.*`), JGL (`com.objectspace.jgl`), SATIN |
| `apps/archviz3d/**` | XMLBeans-generated schema docs (`org.candc`, `org.reqarch3D`, `org.module`, `org.apache.xmlbeans`), JavaLog |
| `apps/intel3d/**` | Jini (`net.jini.*`) |
| `apps/browser/**` | ICEsoft ICEbrowser (`com.icesoft.*`), BeanShell (`bsh`) |
| `apps/browser3d/**` | Jini (`net.jini.*`) |
| `apps/wilkoaim3d/**` | the absent `com.wilko` AIM lib (and API drift, below) |

**Sources that predate the core API snapshot in this repository** (pre-existing
failures, unrelated to the JDK 21 / Jogamp migration):

| App | API drift |
| --- | --- |
| `apps/luncher/**`, `apps/nlc/**` | `AppLaunchAction` / `Pseudo3DShortcut` |
| `apps/orgchart/**` | `FuzzyEdgePanel.setSize(float,float)` |
| `apps/jmf23D/**` | vecmath `Color3f(awt.Color)` constructor |

Everything else builds against the Jogamp Java 3D API migrated across the tree.

## Dependencies

- `lg3d-core` (project dependency).
- Bundled third-party jars under `ext/` (axis, jai, jmf, jxta, log4j, prefuse,
  svgSalamander, nanoxml, jl, jpedal, mail/activation, bouncycastle, …).
