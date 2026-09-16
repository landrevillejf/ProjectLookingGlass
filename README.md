# Project Looking Glass (lg3d) — Modernization Port

An experiment in reviving the **Project Looking Glass** abandonware: the 2006-era
Sun Microsystems 3D desktop, originally built with Ant against Java 1.5 and the
Sun Java 3D 1.3/1.5 stack.

This repository ports that codebase to a modern toolchain so it builds and runs
today:

| Before (2006)                 | After (this port)                        |
| ----------------------------- | ---------------------------------------- |
| Ant + `source 1.5`            | **Gradle 8.14** (wrapper) + **JDK 21**    |
| Sun Java 3D (`javax.media.j3d`, `javax.vecmath`) | **Jogamp Java 3D 1.7.2** (`org.jogamp.java3d`, `org.jogamp.vecmath`) |
| Bundled `j3d-contrib-utils` / `satin` jars | Reimplemented in-tree or replaced        |
| Native X11 foundation window system + custom AWT peer toolkit | Standard AWT/Swing "dev mode" (windowed) |

The 3D desktop now starts, renders, and launches its apps on a current Linux
JDK 21 install using the Jogamp OpenGL pipeline.

## Repository layout

The original project is split across several git submodules. Only four of them
are part of the Gradle build (see [`settings.gradle`](settings.gradle)):

| Module            | In build | Role |
| ----------------- | :------: | ---- |
| `lg3d-escher`     | ✅ | Pure-Java X11 protocol library (Escher 0.2.2) bundled with lg3d. |
| `lg3d-core`       | ✅ | The scene-graph / windowing / display-server SDK and the desktop itself. |
| `lg3d-demo-apps`  | ✅ | Sample and demo applications shipped with lg3d. |
| `lg3d-incubator`  | ✅ | Grab-bag of independent experimental lg3d apps. |
| `lg3d-art`        | assets | Wallpapers, splash art, 3D models, GDM theme (consumed at runtime). |
| `lg3d-awt`        | ❌ | Optional custom AWT Toolkit/peer implementation — excluded (see below). |
| `lg3d-x11`        | ❌ | Native X11 foundation window system scripts/binaries — not a Java module. |
| `lg3d-docs`       | docs | Historical project documentation (HTML/PDF). |

Each built module produces its jar under `<module>/build-gradle/libs/`. Gradle
output is kept in `build-gradle/` (not `build/`) so it never clobbers the legacy
per-module `build`/`clean` scripts that still ship in the tree.

## Prerequisites

- **JDK 21** — the build uses a Gradle toolchain pinned to Java 21. Gradle 8.14
  itself cannot run on Java 25+, so point `JAVA_HOME` at a JDK 21 install (or let
  the launcher auto-detect one).
- **An X display** — dev mode renders the desktop into an ordinary window on the
  host window system (`DISPLAY` must be set; it defaults to `:0`).
- **Linux x86-64** is what this port is validated on. Jogamp publishes natives
  for other platforms and the build selects the right classifier automatically,
  but only Linux/amd64 has been exercised here.

Java 3D and the JOGL/GlueGen/JOAL native libraries are pulled from Maven Central
on first build — no manual jar installation is required.

## Building

```bash
./gradlew build          # compile + jar every module in the build
```

Jars land in:

```
lg3d-escher/build-gradle/libs/escher-0.2.2.jar
lg3d-core/build-gradle/libs/lg3d-core-1.0.1-dev.jar
lg3d-demo-apps/build-gradle/libs/lg3d-demo-apps-1.0.1-dev.jar
lg3d-incubator/build-gradle/libs/lg3d-incubator-1.0.1-dev.jar
```

## Running the desktop

The simplest way is the launcher script, which pins the JDK 21 toolchain, ensures
a `DISPLAY`, assembles the runtime resources, and starts the display server:

```bash
./run-lg3d.sh              # launch the 3D desktop
./run-lg3d.sh -b           # use the 3D model (pinguin.j3f) desktop background
./run-lg3d.sh -c           # clean lg3d-core first
./run-lg3d.sh -r           # force the runtime resources/ tree to be reassembled
./run-lg3d.sh -h           # help
```

Equivalently, via Gradle directly:

```bash
JAVA_HOME=/path/to/jdk21 DISPLAY=:0 ./gradlew :lg3d-core:run
```

This runs lg3d in **development mode** (`lg.fws.mode=dev`): the desktop appears
in a window under your existing window manager, using the standard AWT/Swing
toolkit. It needs neither the native X11 foundation window system nor the custom
lg3d AWT peer toolkit.

> **Note:** the default scene configuration uses `GlassySceneManager` +
> `GlassyTaskbar` with image backgrounds. The `AdvancedGlassyTaskbar` block in
> `lg3d-core/src/etc/lg3d/glassy.lgcfg` — the only path that loads the 3D
> `pinguin.j3f` model background — is commented out upstream. Pass `-b`
> (`-Pbackground3d`) to opt into the 3D background when that taskbar is enabled.

## What was changed to make it build & run

### Java 3D migration
Java 3D comes from the **Jogamp-maintained 1.7.2 fork** — the only readily
available release that still provides the 1.5-era API surface the sources rely on
(`ShaderError`, `Node.ALLOW_PARENT_READ`, `VirtualUniverse.addGraphStructureChangeListener`, …).
Jogamp **renames the packages**, so every source file was migrated:

- `javax.media.j3d.*` → `org.jogamp.java3d.*`
- `javax.vecmath.*` → `org.jogamp.vecmath.*`
- `com.sun.j3d.*` → `org.jogamp.java3d.*` (where applicable)

Modern-JDK API drift was also fixed, e.g. `Behavior.processStimulus(Enumeration)`
→ `Iterator<WakeupCriterion>`, and `Group.getAllChildren()`/`getAllScopes()` now
return `Iterator` instead of `Enumeration`.

The platform-specific Jogamp **native** classifier jars (GlueGen/JOGL/JOAL 2.6.0)
are added explicitly as runtime dependencies; without them startup fails with
`UnsatisfiedLinkError`.

### In-tree replacements for dropped jars
Two bundled jars were compiled against the legacy `javax.media.j3d` packages and
are binary-incompatible with the Jogamp rename, so they were dropped and the
parts lg3d actually uses were reimplemented under
[`lg3d-core/src/contrib/java`](lg3d-core/src/contrib/java):

- **`j3d-contrib-utils`** → `Math3D`, the `TreeScan`/`NodeChangeProcessor`
  traverser, `TransparencyOrderedGroup`/`TransparencyOrderController`, and the
  **`J3fLoader`** used to read the `pinguin.j3f` background model.
- **`satin-v2.3`** → dropped; `SatinGestureModule` now classifies strokes
  geometrically instead of via the SATIN/Rubine stack.

Because `.j3f` files bake in the *legacy* class names, reading them under Jogamp
also needs two small compatibility shims (also under `src/contrib/java`):
`javax.media.j3d.AmbientLight` and
`com.sun.j3d.utils.scenegraph.io.state.javax.media.j3d.AmbientLightState`, which
simply subclass the renamed Jogamp types so the scene-graph reader can resolve
the old names.

### Runtime resources (`resources/` classpath tree)
lg3d resolves its artwork through the classpath under a top-level `resources/`
prefix (e.g. `resources/images/icon/firefox-icon.png`,
`resources/Backgrounds/BgConfig.xml`). The legacy jars bundled that whole tree,
but the per-module Gradle builds emit the assets elsewhere, so the lookups would
miss and the desktop would come up without its icons/wallpapers.

The `lg3d-core:runtimeResources` task assembles the union tree from **`lg3d-art`**
(the full wallpaper/splash/model collection), **`lg3d-core`** (icons, buttons,
default theme wallpapers) and the **incubator background manager**
(`BgConfig.xml`, the per-background directories, and its taskbar icons), and puts
it on the `run` classpath. This is additive — no module jar is restructured.

### What is intentionally excluded
- **`lg3d-awt`** — an optional custom AWT Toolkit/peer implementation
  (`lg.use3dtoolkit=true`, default false). 46 of its 79 classes implement the
  `java.awt.peer.*` SPI and 9 use JDK-internal `sun.awt.*` types that are
  unexported in the JDK 21 `java.desktop` module; the peer SPI itself changed
  substantially after JDK 5. lg3d-core has no compile-time dependency on it, so
  it is left out of the build (sources remain in-tree for reference).
- **Native X11 integration** (`displayserver/fws/x11`, `apps/x11integration`,
  `sun.awt.X11.*` shims) — bound to removed JDK internals; dev mode uses the AWT
  foundation window system instead.
- **RMI scene-graph transport** (`sg/internal/rmi`, `wg/internal/rmi`) — unused.
- **ODE physics nodes** (`wg/.../j3dnodes/Ode*`) — superseded by an in-tree
  spring-damper system.
- **A handful of incubator apps** whose third-party libraries were never
  committed to the repo (Cosmo/Jini, archviz3d/XMLBeans, ICEbrowser, …) or whose
  sources predate the core API snapshot here. The legacy per-app
  `failonerror="false"` build silently skipped these too. See
  [`lg3d-incubator/README.md`](lg3d-incubator/README.md).

## Project coordinates

- Group: `org.jdesktop.lg3d`
- Version: `1.0.1-dev`

See [CHANGELOG.md](CHANGELOG.md) for a summary of the modernization work.

## License

The original Project Looking Glass sources are distributed under their historic
Sun/Java Research licenses; see the `LICENSE` file in each module and
`lg3d-docs/` for the original terms. This port does not change those licenses.
