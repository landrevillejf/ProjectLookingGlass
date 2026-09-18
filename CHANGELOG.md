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
- **Standard 3D window decoration for all `Frame3D` apps** — a reusable
  `Frame3DWindowDecoration` (`org.jdesktop.lg3d.scenemanager.utils.decoration`)
  is auto-attached by `StandardAppContainer.addFrame3D`, giving every pure-3D
  window (File Manager, Task Manager, Control Center, Widget Gallery, dock stack
  popups and the demos) native-style **minimize / maximize / close** buttons plus
  3D rotation: **right-click** flips the window over to a `StickyNote` back side
  and **middle-drag** free-spins it. Previously this chrome existed only for
  native X11 windows (`GlassyNativeWindowLookAndFeel`), an excluded code path, so
  dev-mode apps had no window buttons and could not be rotated. Frames that build
  their own chrome (e.g. `Lg3dHelp`) opt out via the
  `lg3d.frame3d.decoration.optOut` property.
- **Image Studio** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.imagestudio`) — a
  full-featured image editor with an **lg3d-native 3D UI** (no Swing editing
  surface), built on the **bundled Java Advanced Imaging API** (`javax.media.jai`)
  in `lg3d-incubator/ext`. A `Frame3D` window (with the standard decoration)
  lays out a textured-quad image canvas (mouse-wheel zoom, reset view), a left
  toolbar of categorised operations — **Geometry** (scale, rotate, flip, crop,
  border, pixelate), **Color** (brightness, contrast, gamma, grayscale, sepia,
  invert, posterize, threshold), **Filter** (blur, sharpen, emboss, edge) and
  **Math** (add/subtract/multiply constant, absolute, and/or/xor, noise) — driven
  by a live 3D parameter slider, a log-scaled 256-bin RGB histogram, and a bottom
  filmstrip of `~/Pictures` thumbnails. Bounded undo/redo/reset; images open from
  the filmstrip or a native `JFileChooser`, and save back to the current path or
  export to `~/Pictures/lg3d-imagestudio/` (PNG/JPEG via `ImageIO`, TIFF/BMP via
  the JAI codec). Registered in the start menu (Utilities) by
  `lg3d-demo-apps/src/config/imagestudio.lgcfg`. The `lg3d-core:run` task now
  puts the two genuine JAI jars (`jai_core.jar`, `jai_codec.jar`) on the desktop
  classpath and exports `java.desktop/sun.awt.image` so JAI's `RasterAccessor`
  fast path works under JDK 21.
- **UI/UX developer documentation** — a new top-level `docs/` tree (distinct from
  the historical `lg3d-docs/`): `docs/lg3d-native-apps.md` (building native 3D
  apps — `Frame3D`/`Component3D`, layout, the glassy widget vocabulary, event
  adapters + actions, transparency ordering, the live-graph texture-upload rule,
  and Start-Menu `.lgcfg` registration) and `docs/swingnode.md` (embedding Swing
  via `SwingNode` — offscreen texture pipeline, input forwarding, custom
  renderers, lifecycle/`dispose()`). A nested `lg3d-core/AGENTS.md` captures the
  UI/UX rules for agents, and the root `AGENTS.md` now links both.

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
- **Vertical spine titles floating beside the green window edge** — the rotated
  edge titles built by `TitledSwingWindow` sit on the pale green side face of
  the decoration backdrop: each pre-rotated +/-90deg spine quad is placed just
  outside the backdrop side (`x = +/-(contentW/2 + DECO_WIDTH)`) with its glyph
  band centred on the slab depth (`z = -1.5 x BODY_DEPTH`, half a glyph proud
  of each glass face, the sign following the pre-rotation), so on a turned
  window or on the bookshelf the title reads on the green edge like a book
  spine instead of floating over the window rim. Placement derives from the
  public `Frame3DWindowDecoration.BODY_DEPTH` / `DECO_WIDTH` constants so the
  app helper and the decoration cannot drift apart.
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
- **`SwingNode` blank quads + stray `JFrame`s** — Swing content (desktop
  widgets, the file/task manager, control center, dock stack popups and the
  SwingNode/StickyNote demos) rendered as an empty white rectangle while the
  hidden `SwingNodeJFrame` popped up as a real window. The offscreen capture
  lived in the excluded `lg3d-awt` peer toolkit (`lg.use3dtoolkit`, off in this
  build); `SwingNode` now paints its panel into a power-of-two `Texture2D`
  directly on stock JDK 21, driven by a `RepaintManager` repaint hook, and never
  maps the hidden frame.
- **`SwingNode` content nearly invisible (over-transparent)** — the offscreen
  capture above used an alpha-less `RGB` texture under `TextureAttributes.REPLACE`
  with `TransparencyAttributes.FASTEST` (screen-door); on Jogamp the fragment
  alpha came out ~0, so apps and widgets faded to barely-visible even when they
  called `setTransparency(0.0f)`. The capture now uses an `RGBA` texture laid over
  an opaque backdrop, and `DefaultSwingNodeRenderer` blends with
  `BLENDED` + `SRC_ALPHA`/`ONE_MINUS_SRC_ALPHA` (the same configuration
  `SimpleAppearance` uses to render native windows opaque) defaulting to fully
  opaque; translucency stays opt-in via `setTransparency`.
- **Terminal launcher dropped when `xterm` is absent** — the taskbar and
  start-menu Terminal items hard-referenced `xterm`, so on a host without it
  `ApplicationDescription.isApplicationAvailable` returned false, discovery
  logged `Executable xterm not found, ignoring taskbar item` and the item
  silently disappeared. Both configs now carry a portable fallback list
  (`alternateExec` on the taskbar `ApplicationDescription`, `alternateCommands`
  on the start-menu `StartMenuItemConfig`) of `gnome-terminal`, `konsole`,
  `xfce4-terminal`, `mate-terminal`, `lxterminal`, `xterm`, so whichever emulator
  is installed is used and the item (renamed **Terminal**) is shown. In dev mode
  the native terminal still opens as an ordinary host window, not embedded in the
  3D scene — embedding real X11 clients needs the separate `-Pcompositor` path.
- **All `GlassyText2D` labels invisible** (window titles, taskbar/button text,
  Image Studio toolbar) — two compounding Jogamp migration bugs. (1) The glyph
  texture was built with `ImageComponent2D(..., byReference=true)`, so its pixels
  were never uploaded and every label sampled a fully transparent texture;
  `GlassyTextTextureGenerator` now copies the image (`byReference=false`).
  (2) The texture was uploaded `yUp=true` while the quad's texture coordinates
  sample `v` in `[0, heightRatio]`, so the sampled region missed the glyph rows
  entirely; the generator now uploads with the image origin at the upper left
  (`yUp=false`) and `GlassyText2D` maps the quad bottom edge to `v=0` so the text
  reads upright.
- **Glass panels washed out to near-invisible white** — `GlassyPanel` sets white
  per-vertex `COLOR_4` values, which under Java 3D *replace* the `Material`
  ambient/diffuse, so the themed tint (e.g. the green window decoration) never
  showed. `GlassyPanel` now tints its vertex colors by the material's diffuse
  color (reading it via a new `Material.ALLOW_COMPONENT_READ` capability on
  `SimpleAppearance`).
- **Image Studio histogram channels swapped** — for `TYPE_3BYTE_BGR` images the
  JAI histogram band order is `{2,1,0}`, so band 0 is red; `Histogram3D` now maps
  bands to R/G/B with the identity `{0,1,2}` instead of reversing them.
- **Image Studio open (file chooser / filmstrip) appeared to do nothing** —
  `ImageCanvas3D.setImage` attached the new `Texture2D` to the live appearance
  (`Appearance.setTexture`) while its `ImageComponent2D` still held no pixels;
  under Jogamp that makes `TextureRetained.setLive` dereference null image data
  and throw, and the NPE propagated back through `EditorModel.setImage` into
  `FileStrip3D.loadPath`'s catch, which reported "Could not open ..." and left
  the canvas on the old image. The canvas now paints and uploads the pixels
  before building/attaching the texture, so opening a file updates the viewport
  and histogram.
- **`Frame3D` maximize only enlarged the window in place and covered the taskbar**
  — clicking maximize on a native 3D app window scaled it about its current
  origin without re-centering, so an off-center window merely grew (~2x), the
  pre-maximize position was lost on restore, and filling the full screen height
  made the window overlap the bottom taskbar. `Frame3DWindowDecoration.toggleMaximized`
  now saves both the final scale and translation, fits the window into the
  *usable* desktop area above the taskbar (a new `Taskbar.getReservedBottomHeight()`
  published by `AdvancedGlassyTaskbar`/`GlassyTaskbar`), applies an aspect-preserving
  uniform scale (`min(screenW/frameW, usableH/frameH) * margin`, so the aspect
  ratio is never distorted and the width stays proportional), centers the window
  in that usable band on the front plane, and restores the original scale *and*
  position on toggle-off.
- **Image Studio maximized without filling the screen width** — the frame's
  preferred size used fixed fractions of the raw screen (0.62 W x 0.66 H), an
  aspect narrower than the usable desktop band, so the aspect-preserving
  maximize scale hit the height bound first and left wide empty margins left
  and right while the height looked correct. `ImageStudioFrame3D` now derives
  its preferred size from the usable area (`screenHeight -
  Taskbar.getReservedBottomHeight()`) with the matching aspect, so a maximized
  window fills the viewport on both axes (uniform 5% margin) and the normal
  window keeps screen proportions.

### Known non-fatal runtime messages
These are harmless and expected in dev mode:
- `Executable <app> not found, ignoring taskbar item` — a sample taskbar or
  start-menu item references an app that is not installed on the host and has no
  available alternate (e.g. Firefox/Thunderbird). The **Terminal** item no longer
  triggers this: it now resolves through its `alternateExec` / `alternateCommands`
  fallback list to an installed emulator.
- `Could not lock System prefs` — the JDK preferences backing store warning.
- `No default preferences file found: /etc/lg3d/skel/prefs.xml` — first-run
  defaults are created instead.
- `PluginJ3fData` user-data `ClassNotFoundException` in `J3fLoader` — the original
  J3dFly plugin class is not present; the model geometry still loads.
