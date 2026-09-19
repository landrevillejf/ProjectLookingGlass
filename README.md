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

The original project is split across several git submodules. Four of them are
part of the Gradle build (see [`settings.gradle`](settings.gradle)); this port
adds a fifth, in-tree module, `lg3d-widgets`:

| Module            | In build | Role |
| ----------------- | :------: | ---- |
| `lg3d-escher`     | ✅ | Pure-Java X11 protocol library (Escher 0.2.2) bundled with lg3d. |
| `lg3d-core`       | ✅ | The scene-graph / windowing / display-server SDK and the desktop itself. |
| `lg3d-demo-apps`  | ✅ | Sample and demo applications shipped with lg3d. |
| `lg3d-incubator`  | ✅ | Grab-bag of independent experimental lg3d apps. |
| `lg3d-widgets`    | ✅ | **New in this port:** desktop widget API/host and built-in widgets. |
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
lg3d-widgets/build-gradle/libs/lg3d-widgets-1.0.1-dev.jar
```

## Running the desktop

The simplest way is the launcher script, which pins the JDK 21 toolchain, ensures
a `DISPLAY`, assembles the runtime resources, and starts the display server:

```bash
./run-lg3d.sh              # launch the 3D desktop
./run-lg3d.sh -b           # use the 3D model (pinguin.j3f) desktop background
./run-lg3d.sh -c           # clean lg3d-core first
./run-lg3d.sh -r           # force the runtime resources/ tree to be reassembled
./run-lg3d.sh -x           # run lg3d as its own X11 window manager + compositor
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

## Desktop shell features

On top of the 3D scene this port adds a small desktop shell: desktop widgets,
dock folder stacks, and three system apps. All system access is **pure Java**
(`ProcessBuilder`, `/proc`, `/sys`, `xrandr`, `pkexec`, `xdg-open`) — no JNI, no
JNA.

- **Widgets (`lg3d-widgets`)** — a public, pluggable widget API
  (`org.jdesktop.lg3d.widgets.api`: `Widget`, `AbstractWidget`, `WidgetContext`,
  `WidgetDescriptor`, `WidgetProvider` SPI, `WidgetRegistry`). Third parties add
  widgets by dropping a jar that carries a
  `META-INF/services/org.jdesktop.lg3d.widgets.api.WidgetProvider` entry. The
  host (`...widgets.host`) renders a draggable desktop widget layer whose layout
  persists to `~/.config/lg3d/widgets.properties`. Built-ins: clock,
  temperature, CPU load, memory. Manage them with the **Widget Gallery** app
  (Utilities menu).
- **Dock stacks** — Documents and Downloads folder stacks on the taskbar's
  right side, immediately before Exit
  (`[Background] [Documents] [Downloads] [Exit]`). Each expands to a list or a
  grid (OSX-style); files open with `xdg-open`, folders open in the file manager.
- **File Manager** (System menu) — tree + list browsing with copy / move /
  rename / delete-to-trash / new-folder, multi-select, drag-and-drop and
  keyboard shortcuts.
- **Task Manager** (System menu) — live process table (CPU% and memory from
  procfs deltas) with End Task / Force Quit / Change Priority; processes owned
  by other users are signalled through `pkexec`.
- **Control Center** (System menu) — Display (xrandr modes, refresh rate,
  multi-monitor position, scale, with a timed auto-revert), Users (add / edit /
  password / groups / remove via `pkexec`), System (live CPU / memory / disk /
  kernel / distro), and Appearance (wallpaper chooser that changes the live
  desktop background).

System requirements for the shell: `xrandr` (Display panel), `xdg-utils`
(`xdg-open`), polkit / `pkexec` (privileged operations), and optionally
`lm-sensors` (a `sensors` fallback for temperatures). Every backend degrades
gracefully — read-only or "n/a" — when a tool or file is absent.

## Image Studio (JAI image editor)

**Image Studio** (Utilities menu) is a full-featured image editor with an
lg3d-native **3D** UI, built on the **bundled Java Advanced Imaging API**
(`javax.media.jai`) that ships in [`lg3d-incubator/ext`](lg3d-incubator/ext). It
provides categorised Geometry / Color / Filter / Math operations driven by a live
3D parameter slider, a log-scaled 256-bin RGB histogram, bounded undo/redo/reset,
a `~/Pictures` thumbnail filmstrip, and native open/save dialogs (PNG/JPEG via
`ImageIO`, TIFF/BMP via the JAI codec). Full details in
[`lg3d-incubator/README.md`](lg3d-incubator/README.md).

JAI is on the incubator's *compile* classpath but not on the desktop's, so the
`lg3d-core:run` task adds the two genuine JAI jars (`jai_core.jar`,
`jai_codec.jar`) to the run classpath and passes
`--add-exports java.desktop/sun.awt.image=ALL-UNNAMED` — JAI's `RasterAccessor`
fast path reaches into that JDK-internal package, which JDK 21 otherwise
encapsulates (without the export every operator fails at runtime).

> **Terminal item.** The taskbar / start-menu **Terminal** launcher now falls
> back through `gnome-terminal`, `konsole`, `xfce4-terminal`, `mate-terminal`,
> `lxterminal`, `xterm` when `xterm` is absent, so it is no longer dropped from
> the desktop. In dev mode a native terminal opens as an ordinary host window,
> not embedded in the 3D scene — embedding real X11 clients requires the
> `-Pcompositor` mode described below.

## X11 compositor mode

Dev mode above runs lg3d *inside* your existing desktop. This port also revives
lg3d's original ambition — running **real X11 applications as textured windows
inside the 3D scene** — using the modern X **Composite / Damage / XTest**
extensions rather than the blocked 2006 native path. In this mode lg3d becomes
the **window manager and compositor** of the display:

- lg3d claims `SubstructureRedirect` on the root window (the WM takeover) and
  calls `CompositeRedirectSubwindows`, so the X server redirects every top-level
  client window into an offscreen pixmap.
- On each `DamageNotify`, the damaged region of a client's pixmap is read back
  (via MIT-SHM, falling back to `GetImage`) into a `BufferedImage` and uploaded
  as a texture on that window's `NativeWindow3D` quad, decorated by the existing
  `GlassyNativeWindowLookAndFeel`.
- Physical input on the Canvas3D is picked in 3D, translated back to the client
  window's pixel coordinates, and re-injected with **XTest** (`fake_motion` /
  `fake_button` / `fake_key`); focus follows the pointer via `XSetInputFocus`.

All of it is **pure Java** through the in-tree Escher X11 library — no JNI, no
JNA, no patched JDK, and no custom X server.

```bash
./run-lg3d.sh -x                                                # via the launcher
JAVA_HOME=/path/to/jdk21 ./gradlew :lg3d-core:run -Pcompositor  # via Gradle
```

`-Pcompositor` switches `lg.configurl` to `lgconfig_1p_x_composite.xml`
(`WinSysAWT` + `X11IntegrationModule`), sets `lg3d.x11.compositor=true`, and adds
`--add-exports java.desktop/sun.awt=ALL-UNNAMED` so lg3d can find — and exempt
from redirection — its own Canvas3D window. It is opt-in: the default dev-mode
desktop (`lgconfig_1p_nox.xml`) is unchanged.

> **Requires a bare X server.** Because lg3d claims `SubstructureRedirect`, it
> must be the *only* window manager on that display. It will **not** start under
> an existing session (GNOME/mutter, KDE, or Xwayland) — the WM claim fails with
> `BadAccess`. It is meant for a dedicated Xorg session, as below.

### Deployment target (Linux From Scratch)

The intended runtime is a minimal, purpose-built system: **Xorg on `:0` with no
display manager and no other window manager**, and lg3d started as the session.
A systemd unit can express this with the classic `xinit` hand-off (Xorg starts,
lg3d runs as the session client, and Xorg exits when lg3d does):

```ini
# /etc/systemd/system/lg3d-compositor.service
[Unit]
Description=Project Looking Glass as the X11 session (window manager + compositor)
After=systemd-user-sessions.service

[Service]
Environment=JAVA_HOME=/opt/jdk-21
ExecStart=/usr/bin/xinit /opt/lg3d/run-lg3d.sh -x -- /usr/bin/Xorg :0 vt1 -nolisten tcp
Restart=on-failure

[Install]
WantedBy=graphical.target
```

Any equivalent that (1) starts Xorg on `:0` and (2) launches `run-lg3d.sh -x`
with no competing window manager will work. lg3d normally discovers its own
Canvas3D window id automatically (that is what the `--add-exports` above
enables); if discovery fails on the target, pin the id with the
`lg3d.x11.ownwindowid` system property, e.g.
`JAVA_TOOL_OPTIONS=-Dlg3d.x11.ownwindowid=<id>`. For a leaner production session
you can also run the built jars directly instead of via Gradle.

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
- Version: `1.3.0-dev`

See [CHANGELOG.md](CHANGELOG.md) for a summary of the modernization work.

## License

The original Project Looking Glass sources are distributed under their historic
Sun/Java Research licenses; see the `LICENSE` file in each module and
`lg3d-docs/` for the original terms. This port does not change those licenses.
