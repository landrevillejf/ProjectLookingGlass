# lg3d-core

The **core of Project Looking Glass**: the scene-graph / windowing /
display-server SDK and the 3D desktop itself. Entry point is
`org.jdesktop.lg3d.displayserver.Main`.

> The `src/` tree is the original Project Looking Glass source dump, migrated to
> build on a modern toolchain. See the [root README](../README.md) for the full
> port overview.

## Build status

Part of the Gradle build (JDK 21 toolchain). Ported from the 2006-era Ant build
(`build.xml`); this module reproduces the "compile-javaonly" flavour
(nonative + nox11) — the pure-Java SDK against Java 3D.

```bash
./gradlew :lg3d-core:build     # jar -> build-gradle/libs/lg3d-core-1.0.1-dev.jar
./gradlew :lg3d-core:run       # launch the desktop in dev mode (windowed)
```

From the repository root, `./run-lg3d.sh` is the convenient launcher.

## Dependencies

- **Java 3D** — Jogamp `org.jogamp.java3d:{java3d-core,java3d-utils,vecmath}:1.7.2`
  (the only release keeping the 1.5-era API the sources use). Jogamp renames the
  packages, so all sources were migrated `javax.media.j3d`/`com.sun.j3d` →
  `org.jogamp.java3d`, `javax.vecmath` → `org.jogamp.vecmath`.
- **Jogamp natives** — GlueGen/JOGL/JOAL 2.6.0 platform-classifier jars, added
  explicitly at runtime (they are not pulled transitively); without them
  `VirtualUniverse` init dies with `UnsatisfiedLinkError`.
- **`lg3d-escher`** — sibling module (X11 protocol library).

## Notable Gradle tasks

- `generateBuildInfo` — reproduces the legacy `LgBuildInfo.java` token
  substitution into `build-gradle/generated-src` (keeps the checked-in tree clean).
- `runtimeResources` — assembles the legacy top-level `resources/` classpath tree
  (icons, wallpapers, background-manager configs) from `lg3d-art`, `lg3d-core` and
  the incubator bgmanager, so the desktop comes up fully populated. Wired onto the
  `run` classpath.
- `run` — `JavaExec` launching the display server in dev mode; pins the JDK 21
  launcher. Accepts `-Pbackground3d` to opt into the 3D `pinguin.j3f` background.

## In-tree contrib replacements (`src/contrib/java`)

`j3d-contrib-utils.jar` and `satin-v2.3.jar` were compiled against the legacy
`javax.media.j3d` packages and are binary-incompatible with the Jogamp rename.
The parts lg3d uses were reimplemented here against the Jogamp API:

- `org.jdesktop.j3d.utils.math.Math3D`
- `org.jdesktop.j3d.utils.scenegraph.traverser.{TreeScan,NodeChangeProcessor,ProcessNodeInterface}`
- `org.jdesktop.j3d.utils.scenegraph.transparency.{TransparencyOrderedGroup,TransparencyOrderController}`
- `org.jdesktop.j3d.loaders.wrappers.J3fLoader` — reads the `pinguin.j3f` model.

Plus two legacy-named compatibility shims so `.j3f` files (which bake in the old
class names) can be deserialised under Jogamp:

- `javax.media.j3d.AmbientLight`
- `com.sun.j3d.utils.scenegraph.io.state.javax.media.j3d.AmbientLightState`

## Excluded from this module's build

- `org.jdesktop.lg3d.awt.*` / `awtpeer.*` and `sun.awt.X11.*` — the custom AWT
  peer toolkit / X11 shims, built on JDK-internal APIs removed after JDK 6.
- `displayserver/fws/x11`, `apps/x11integration` — native X11 integration.
- `sg/internal/rmi`, `wg/internal/rmi` — unused RMI scene-graph transport.
- `wg/.../j3dnodes/Ode*` — ODE physics, superseded by an in-tree spring-damper.

Dev mode (`lg.fws.mode=dev`) uses the standard AWT/Swing toolkit, so none of the
above are needed to run the desktop.
