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

## Agenda 3D

`org.jdesktop.lg3d.apps.orgchart.ui.agenda` is a **new** native-3D week-agenda
app (not a port) that interacts **one-way** with the ported **Contact 3D**. Both
run in the same JVM and share the user `Preferences` root, so `Agenda3D` reads the
same `/contacts` node `Contact3D` imports — falling back to the bundled
`contacts.xml` if Contact 3D has not run yet — and offers those contacts as
meeting attendees, drawing each invitee's live free/busy presence as a coloured
chip on the appointment block. **Contact 3D itself is unchanged.**

It is launched from the start menu (Office) with
`java org.jdesktop.lg3d.apps.orgchart.ui.agenda.Agenda3D`; the descriptor lives in
[`lg3d-demo-apps/src/config/agenda3d.lgcfg`](../lg3d-demo-apps/src/config/agenda3d.lgcfg)
for the same discovery reason Image Studio follows.

| Class | Role |
| --- | --- |
| `Agenda3D` | `main` entry point: the `Frame3D` window; wires data, grid and controls. |
| `AgendaGrid` | Week-view `Component3D`: renders 7 day columns x 10 hour rows (08:00-18:00) into one live texture and maps clicks to cells. |
| `AgendaButton` | Runtime-drawn labelled 3D push button (normal/hover appearances, no PNG assets). |
| `Appointment` | A user-created entry (title, day, start hour, duration, attendees) with `Preferences` serialisation. |
| `AppointmentStore` | Loads / saves / deletes appointments under `/agenda/appointments`. |
| `ContactDirectory` | Read-only view of the shared `/contacts` store Contact 3D populates. |

Appointments are **user-created only** (the agenda starts empty) and persist
across launches. The grid obeys the live-texture rule: one fixed-size
`ImageComponent2D` with `ALLOW_IMAGE_WRITE` is attached to a `Texture2D` once,
off-live, and every edit only repaints the `BufferedImage` and calls
`ImageComponent2D.set` in place — no texture is ever re-attached to the live
scene graph. The grid quad sets `Geometry.ALLOW_INTERSECT` so the pick engine's
`PICK_GEOMETRY` mode reports an intersection point for click-to-cell mapping.

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

**Deep core-API drift** (a from-scratch rewrite, not a small fix):

| App | Why it stays excluded |
| --- | --- |
| `apps/wilkoaim3d/**` | Targets a whole 2004-era lg3d utility vocabulary that no longer exists in core (`Frame3DToFrontEvent`, `ComponentMover`, `ResilientRotateAction`, `NaturalMotionComponent3D`/`Container3D`, `ColorAlphaChangeAction`), the obsolete 2-arg event-adapter constructors, and `setTexture(String)`. Porting it means rewriting a 1277-line prototype — and its AOL AIM TOC backend was discontinued by AOL in Dec 2017, so it could never log in even if rewritten. (The `com.wilko` `jaimlib.jar` *is* present in [`ext/`](ext) and on the classpath; a missing dependency was never the real blocker.) |

## Ported apps

Four apps whose sources merely predated the core API snapshot in this repository
have been ported to the current API and now build:

| App | Entry point(s) | Drift that was fixed |
| --- | --- | --- |
| `apps/jmf23D/**` | `jmf23D.Algea3D` | vecmath dropped the `Color3f(java.awt.Color)` constructor — replaced with explicit RGB floats. |
| `apps/luncher/**` | `luncher.Luncher1`, `luncher.Luncher2` | `AppLaunchAction(String,ClassLoader)` / `Pseudo3DShortcut(URL,String,ClassLoader)` signatures; `SimpleAppearance.setTexture(URL)` instead of `setTexture(String)`. |
| `apps/nlc/**` | `nlc.Main` | `AppLaunchAction(String,ClassLoader)`; vecmath `Color4f(java.awt.Color)` constructor. |
| `apps/orgchart/**` | `orgchart.ui.chart.Chart3D`, `orgchart.ui.contact.Contact3D` | `FuzzyEdgePanel.setSize(float,float,float,float)` signature. |

Each is registered in the desktop **Start Menu** via a `.lgcfg` descriptor under
[`lg3d-demo-apps/src/config`](../lg3d-demo-apps/src/config) (`algea3d`, `luncher`,
`nlc`, `orgchart-chart`, `orgchart-contact`) rather than this module's own
`src/config`, for the same discovery reason Image Studio follows: discovery only
scans `config/demo` and `config/incubator`, while the incubator's `src/config` is
bundled to `config/`.

At runtime these apps lean on their bundled `ext/` libraries, so the
`lg3d-core:run` task puts the whole `ext/` jar tree on the desktop classpath (not
just the JAI jars Image Studio needs): `jmf.jar` for Algea3D, `nanoxml-lite` +
`javanlp` for nlc, `prefuse.jar` for the org chart apps. Two apps also resolve
data files from the classpath rather than the (uninstalled) legacy `etc/lg3d/`
location: luncher's `MenuConfigFile.xml` and nlc's `englishPCFG.ser.gz` grammar
model are both loaded from the jar. Algea3D's transport-button models/icon are
merged into the top-level `resources/` tree by `lg3d-core:runtimeResources`.

Everything else builds against the Jogamp Java 3D API migrated across the tree.

## Dependencies

- `lg3d-core` (project dependency).
- Bundled third-party jars under `ext/` (axis, jai, jmf, jxta, log4j, prefuse,
  svgSalamander, nanoxml, jl, jpedal, mail/activation, bouncycastle, …).
