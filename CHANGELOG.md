# Changelog

All notable changes to this **modernization port** of Project Looking Glass are
documented here. The format follows [Keep a Changelog](https://keepachangelog.com/),
grouped by Added / Changed / Removed / Fixed.

The original 2006 Sun codebase is the baseline; everything below describes the
work to make it build and run on a current toolchain.

## [Unreleased] — 1.0.1-dev — Gradle / JDK 21 modernization

### Added
- **Gradle build** (wrapper 8.14) replacing the 2006-era Ant `source 1.5` build,
  with a JDK 21 toolchain. Modules: `lg3d-escher`, `lg3d-core`, `lg3d-demo-apps`,
  `lg3d-incubator`; jars are emitted to `<module>/build-gradle/libs/` so the
  legacy per-module `build`/`clean` scripts are left untouched.
- **`run-lg3d.sh`** launcher at the repository root — auto-detects/pins the JDK 21
  toolchain, defaults `DISPLAY`, and starts the desktop. Options: `-b`
  (3D `pinguin.j3f` background), `-x` (X11 compositor/WM mode), `-c` (clean
  first), `-r` (reassemble runtime resources), `-h` (help), and `--`
  pass-through to Gradle.
- **`lg3d-core:run`** task (`JavaExec`) launching `org.jdesktop.lg3d.displayserver.Main`
  in development mode (`lg.fws.mode=dev`) with the AWT foundation window system,
  pinned to the JDK 21 launcher. Accepts `-Pbackground3d` to opt into the 3D model
  background and `-Pcompositor` to run lg3d as its own X11 window
  manager/compositor.
- **`lg3d-core:runtimeResources`** task — assembles the legacy top-level
  `resources/` classpath tree from `lg3d-art` (wallpapers, splash, models, GDM
  theme), `lg3d-core` (icons, buttons, default wallpapers) and the incubator
  background manager (`BgConfig.xml`, per-background directories, taskbar icons),
  and wires it onto the `run` classpath so the desktop comes up fully populated.
- **`lg3d-core:generateBuildInfo`** task — reproduces the legacy `LgBuildInfo.java`
  `@TOKEN@` substitution into `build-gradle/generated-src`, keeping the checked-in
  tree clean.
- **In-tree contrib replacements** under `lg3d-core/src/contrib/java` for the
  dropped `j3d-contrib-utils` classes: `Math3D`, the `TreeScan` /
  `NodeChangeProcessor` / `ProcessNodeInterface` traverser, and
  `TransparencyOrderedGroup` / `TransparencyOrderController`.
- **`J3fLoader`** (`org.jdesktop.j3d.loaders.wrappers.J3fLoader`) reimplemented
  against the Jogamp scene-graph IO API, so the `pinguin.j3f` background model can
  be read again (it is loaded reflectively via `Class.forName` in `ModelBackground`).
- **Legacy-name compatibility shims** so pre-existing `.j3f` files (which bake in
  the old class names) deserialise under Jogamp: `javax.media.j3d.AmbientLight`
  and `com.sun.j3d.utils.scenegraph.io.state.javax.media.j3d.AmbientLightState`.
- **Jogamp native runtime dependencies** — GlueGen / JOGL / JOAL 2.6.0
  platform-classifier jars, selected from `os.name`/`os.arch`.
- **X11 compositing integration** (`lg3d-core/.../displayserver/nativewindow/x11/`)
  — lg3d can run as its own X11 **window manager + compositor**, displaying real
  X11 client apps as textured `NativeWindow3D` quads in the 3D scene. Built on new
  pure-Java Escher extension bindings (`X11CompositeExt`, `X11DamageExt`,
  `X11ShmExt`) plus `X11Compositor` (WM takeover + `CompositeRedirectSubwindows`
  + event loop), `CompositeWindowImageLoader` (Damage-triggered pixmap → texture),
  and `X11InputForwarder` (3D pick → XTest pointer/keyboard injection). Opt-in via
  `-Pcompositor` / `run-lg3d.sh -x` (`lgconfig_1p_x_composite.xml`); no JNI, no
  JNA, no patched JDK. Requires a bare Xorg with no other WM already holding
  `SubstructureRedirect`. The legacy native `fws/x11` path stays excluded.
- **Desktop shell: widget framework (`lg3d-widgets`)** — a new in-tree module
  providing a public, pluggable widget API (`org.jdesktop.lg3d.widgets.api`:
  `Widget`, `AbstractWidget`, `WidgetContext`, `WidgetDescriptor`, the
  `WidgetProvider` SPI and `WidgetRegistry`), a desktop widget layer/host
  (`...widgets.host`) that renders draggable widgets whose layout persists to
  `~/.config/lg3d/widgets.properties`, and built-in clock, temperature, CPU and
  memory widgets (`...widgets.builtin`). Third parties add widgets by dropping a
  jar carrying a `META-INF/services/...WidgetProvider` entry; placed widgets are
  managed through the **Widget Gallery** app.
- **Desktop shell: dock folder stacks** — Documents and Downloads stacks on the
  taskbar's right side, immediately before Exit
  (`[Background] [Documents] [Downloads] [Exit]`), each expanding to a list or an
  OSX-style grid (`org.jdesktop.lg3d.scenemanager.utils.taskbar.stack`,
  registered from `glassy.lgcfg`). Files open with `xdg-open`; folders open in
  the file manager.
- **Desktop shell: system apps** (`lg3d-demo-apps`) — **File Manager**
  (tree + list browsing with copy / move / rename / delete-to-trash / new-folder,
  multi-select, drag-and-drop, keyboard shortcuts), **Task Manager** (live
  process table from procfs with End Task / Force Quit / Change Priority), and
  **Control Center** (Display via `xrandr` with a timed auto-revert, Users via
  `pkexec`, live System info, and an Appearance wallpaper chooser). All three sit
  under a new **System** start-menu group.
- **Pure-Java Linux system backends** (`org.jdesktop.lg3d.utils.system` in
  `lg3d-core`) — `ProcessRunner` / `PrivilegedRunner` (`pkexec`), `Opener`
  (`xdg-open`, freedesktop trash), `Proc` (procfs readers), `ProcessService`,
  `ThermalService`, `DisplayService` (`xrandr`), `UserService` and
  `SystemInfoService`. No JNI/JNA; every service degrades gracefully (read-only
  or "n/a") when a tool or file is absent.

### Changed
- **Java 3D** migrated from the Sun `javax.media.j3d` / `javax.vecmath` stack to
  the Jogamp-maintained **1.7.2** fork (`org.jogamp.java3d` / `org.jogamp.vecmath`)
  — the only readily available release preserving the 1.5-era API the sources rely
  on (`ShaderError`, `Node.ALLOW_PARENT_READ`,
  `VirtualUniverse.addGraphStructureChangeListener`, …). Every source file was
  migrated to the renamed packages (`javax.media.jai` left alone).
- **Modern-JDK API drift** fixed across the tree, e.g.
  `Behavior.processStimulus(Enumeration)` → `Iterator<WakeupCriterion>`, and
  `Group.getAllChildren()` / `getAllScopes()` now return `Iterator` instead of
  `Enumeration`.
- **`SatinGestureModule`** rewritten as a geometric stroke classifier, replacing
  the SATIN/Rubine stack from the dropped `satin-v2.3.jar`.
- **`lg3d-incubator`** now compiles the whole `src/classes` tree as one source set
  against `lg3d-core` + the bundled `ext` jars, mirroring the legacy per-app
  `failonerror="false"` behaviour by excluding apps that cannot build.
- **Documentation** — the root and per-module `README.md` files rewritten to
  describe the port, build/run instructions, module map, and exclusions.

### Removed
- **Bundled `j3d-contrib-utils.jar` and `satin-v2.3.jar`** — compiled against the
  legacy `javax.media.j3d` packages and binary-incompatible with the Jogamp
  rename; superseded by the in-tree replacements above.
- **`lg3d-awt`** excluded from the build — the optional custom AWT Toolkit/peer
  implementation depends on the `java.awt.peer.*` SPI (changed after JDK 5) and
  unexported `sun.awt.*` internals. Not needed: it is opt-in via
  `lg.use3dtoolkit=true` (default false) and loaded dynamically, so `lg3d-core`
  has no compile-time dependency on it. Sources kept in-tree.
- **Native X11 integration** excluded from `lg3d-core`
  (`displayserver/fws/x11`, `apps/x11integration`, `sun.awt.X11.*` shims), and the
  **`lg3d-x11`** native X server module left out of the build — dev mode uses the
  AWT foundation window system instead.
- **Unused RMI scene-graph transport** (`sg/internal/rmi`, `wg/internal/rmi`).
- **ODE physics nodes** (`wg/.../j3dnodes/Ode*`) — superseded by an in-tree
  spring-damper system; the bundled `odejava` jar is not used.
- **Incubator apps that cannot build** (silently skipped by the legacy build too):
  `nu/koidelab` (Cosmo), `archviz3d`, `intel3d`, `browser`, `browser3d`,
  `wilkoaim3d`, `luncher`, `orgchart`, `nlc`, `jmf23D`.

### Fixed
- **Blank desktop (missing icons/wallpapers/background chooser)** — caused by the
  `resources/` classpath-prefix mismatch: lg3d requests artwork under a top-level
  `resources/` prefix, but the per-module Gradle builds emit those assets at other
  paths. Resolved by the additive `runtimeResources` assembly (no jar
  restructuring). The background manager's taskbar icon
  (`resources/images/icon/bgicon*.png`) was included in the same fix, clearing the
  `downImage cannot be null` error.
- **Startup halt on modern JVMs** — removed `Main.java`'s obsolete blocking
  "upgrade to JDK 1.6 (Mustang)" dialog, which fired on any non-1.6 JVM.
- **`StringIndexOutOfBoundsException` at startup** — `LgBuildInfo`'s `JAVA_VERSION`
  token must be a full version string (`21.0.12`), because `Main.java` takes
  `substring(0,5)`; a bare `21` crashed.
- **`UnsatisfiedLinkError` during `VirtualUniverse` init** — the Jogamp native
  classifier jars are not pulled transitively and had to be added explicitly.
- **Compile/runtime Java-version guard** — the `run` task now uses
  `javaLauncher = javaToolchains.launcherFor { languageVersion = 21 }` so the
  desktop runs on the same JDK it was compiled with, not the (possibly newer) JVM
  that launched Gradle.
- **Negative taskbar indices** — `Taskbar.addTaskbarItem(item, -n)` now places
  the item n-th from the right of the right-hand group (`-1` rightmost) instead
  of clamping every negative index to append-at-end, so the dock stacks sit
  immediately before Exit regardless of plugin initialisation order.

### Known non-fatal runtime messages
These are harmless and expected in dev mode:
- `Executable xterm not found` — the sample taskbar items reference apps not
  installed on the host.
- `Could not lock System prefs` — the JDK preferences backing store warning.
- `No default preferences file found: /etc/lg3d/skel/prefs.xml` — first-run
  defaults are created instead.
- `PluginJ3fData` user-data `ClassNotFoundException` in `J3fLoader` — the original
  J3dFly plugin class is not present; the model geometry still loads.
