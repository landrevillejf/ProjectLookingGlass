# Changelog

All notable changes to this **modernization port** of Project Looking Glass are
documented here. The format follows [Keep a Changelog](https://keepachangelog.com/),
grouped by Added / Changed / Removed / Fixed.

The original 2006 Sun codebase is the baseline; everything below describes the
work to make it build and run on a current toolchain.

## [Unreleased] — 1.9.0-dev — Gradle / JDK 21 modernization

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
- **Desktop shell: Weather widget** (`lg3d-widgets`, `WeatherWidget`) — a new
  built-in desktop widget showing current conditions from the free
  **Open-Meteo** forecast API (no API key): temperature, a sky glyph drawn from
  the WMO weather code, condition text, location, today's high/low, feels-like,
  humidity and wind. The **mouse wheel** cycles a preset list of major cities
  (persisted) and a **click** toggles &deg;C/&deg;F (defaulted from the system
  locale); a custom `lat`/`lon`/`label` can be pinned in
  `~/.config/lg3d/widgets.properties`. Fetches run on the shared widget
  scheduler thread every 15 minutes (and immediately after a city change) via
  the JDK `java.net.http` client with a small dependency-free JSON reader, so
  neither the EDT nor the 3D event loop is ever blocked; a failed refresh keeps
  the last reading and flags it "stale". Listed in the Widget Gallery under
  **Web**.
- **Desktop shell: dock folder stacks** — Documents and Downloads stacks on the
  taskbar's right side
  (`[Documents] [Downloads] [Background] [Exit]`), each fanning out its most
  recent entries as an OSX/Leopard-style fan of icon cards, each a filename
  pill beside its MIME icon
  (`org.jdesktop.lg3d.scenemanager.utils.taskbar.stack`, registered from
  `glassy.lgcfg`). Hovering the dock icon opens the fan (a click toggles it),
  newest entries leading the fan. The fan window is anchored just above its own
  dock icon (clamped to stay fully on screen) instead of defaulting to the centre
  of the screen. Files open with `xdg-open`; folders — and a
  header **Show in File Manager** action — open the folder in the file manager.
  Escape or the close button dismisses the fan.
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
  3D rotation: **right-click** on the window's green border flips it over to a
  `StickyNote` back side and **middle-drag** free-spins it. Previously this chrome
  existed only for native X11 windows (`GlassyNativeWindowLookAndFeel`), an excluded
  code path, so
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
- **Three more `lg3d-incubator` apps ported, registered and verified launching** —
  apps whose sources merely predated the current core API snapshot were brought up
  to date, built, and confirmed to start on the desktop: **Luncher**
  (`luncher.Luncher1`, a 3D glassy-cube card launcher), **Natural Language
  Control** (`nlc.Main`, a command-driven 3D mascot) and the **org chart** apps
  (`orgchart.ui.chart.Chart3D`, `orgchart.ui.contact.Contact3D`). Each is
  registered in the desktop start menu by a new descriptor under
  `lg3d-demo-apps/src/config` (`luncher`, `nlc`, `orgchart-chart`,
  `orgchart-contact`), following the Image Studio precedent. The compile-time
  drift fixed was small and self-contained: vecmath's dropped
  `Color3f/Color4f(java.awt.Color)` constructors,
  `AppLaunchAction(String,ClassLoader)` /
  `Pseudo3DShortcut(URL,String,ClassLoader)` signatures,
  `SimpleAppearance.setTexture(URL)`, and `FuzzyEdgePanel.setSize(float,float,float,float)`.
  Making them actually *run* also required fixing legacy resource/API drift that
  only surfaces at launch: luncher's `MenuConfigFileReader` falls back to the
  `MenuConfigFile.xml` bundled beside the class and its menu icon now resolves
  from the bundled `GlassyCardIcon.png` (the legacy `etc/lg3d/` and
  `resources/images/icon/` install paths are not populated by this port);
  `Luncher1.setShortcuts` no longer calls `Container3D.setLayout` on an
  already-populated container (the modern API rejects that — `MenuConfigFileReader`
  sets the layout while the container is still empty); nlc's `StanfordFactory`
  loads its `englishPCFG.ser.gz` grammar model from the jar (copying it to a temp
  file) and `Main`/`knowledge.xml` point at the bundled `conf/` resources rather
  than the absent `/etc/lg3d` paths; and `Chart3D.upLevel` guards on
  `numChildren > 1` before reading `getChild(1)` (child 0 is the Up button), so a
  stray keypress at the top level no longer throws. Runtime dependencies are
  supplied by putting the *specific* bundled `ext` jars these apps need on the
  `lg3d-core:run` classpath — `nanoxml-lite` + `javanlp` (nlc), `prefuse`
  (orgchart) and the two JAI jars (Image Studio) — deliberately **not** the whole
  `ext` tree, because `ext/axis/xercesImpl.jar` registers itself as the JAXP
  `DocumentBuilderFactory` and references `org.w3c.dom.ls.DocumentLS` (long
  removed from the JDK), which breaks `java.util.prefs` and hence `DesktopConfig`
  and the background manager on every desktop. `wilkoaim3d` remains excluded: it
  needs a whole removed 2004-era utility vocabulary (`Frame3DToFrontEvent`,
  `ComponentMover`, `ResilientRotateAction`, `NaturalMotion*`,
  `ColorAlphaChangeAction`) and its AOL AIM backend was discontinued in 2017, so
  it could never run — its exclusion rationale was corrected (the `com.wilko`
  `jaimlib.jar` is in fact present).
- **Agenda 3D** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.orgchart.ui.agenda`) —
  a new native-3D week-agenda app that interacts **one-way** with the ported
  **Contact 3D**. Both run in the same JVM and share the user `Preferences` root,
  so `Agenda3D` reads the very same `/contacts` node `Contact3D` imports (falling
  back to the bundled `contacts.xml` if Contact 3D has not run yet) and offers
  those contacts as meeting attendees, drawing each invitee's live free/busy
  presence as a coloured chip on the appointment block — **Contact 3D itself is
  unchanged**. A `Frame3D` (with the standard decoration) lays out an `AgendaGrid`
  week view — seven day columns by ten one-hour rows (08:00–18:00) rasterised into
  a single live texture with the `Histogram3D` `ImageComponent2D.set` recipe — over
  a strip of runtime-drawn `AgendaButton` controls (New / Del / Title / Today /
  Att- / Att+ and Day / Hr / Dur nudges). Clicking a cell selects an appointment
  or moves the creation cursor; appointments are **user-created only** (the agenda
  starts empty) and persist under `/agenda/appointments`, mirroring how Contact 3D
  stores contacts. Registered in the start menu (Office) by
  `lg3d-demo-apps/src/config/agenda3d.lgcfg`.
- **Agenda 3D week grid now marks business days, holidays and weekends** — the
  `AgendaGrid` columns are anchored to real `LocalDate`s (Monday of the current
  week + offset) instead of bare indices, and each day is classified with the
  bundled **`jbusinessday`** library (`libs/jbusinessday-0.9.1-SNAPSHOT.jar`):
  `JBusinessDay.isWeekend` / `isBusinessDay` against a per-year cached federal
  holiday list (`AmericanHolidayUtil.getFederalHolidays` by default, or
  `CanadianHolidayUtil.getCanadianFederalHolidays` when started with
  `-Dlg.agenda.holidayRegion=CA`). A title band across the top of the grid shows
  the displayed week's month range **and year** (e.g. `September 14–20, 2026`, or
  `Dec 28, 2026 – Jan 3, 2027` when the week straddles a year), the day header is
  two lines — the day name over a short month + day-of-month (e.g. `Mon` /
  `Sep 14`) — and weekend and holiday columns get distinct header tints plus a
  faint full-height body wash, with an accent bar over today. A new navigation
  row (`Yr-`/`Mo-`/`Wk-` and their `+` counterparts) cycles the displayed week
  back and forth through the calendar, and `Today` snaps back to the current
  week; the today-highlight only appears when the displayed week is the current
  one. `jbusinessday` logs through slf4j and touches
  `org.slf4j.LoggerFactory` in a static initializer, so `slf4j-api` + the silent
  `slf4j-nop` provider (2.0.16) are added to the incubator runtime classpath and
  resolved onto the `lg3d-core:run` classpath alongside the jar.
- **Mail 3D** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.mail`) — a new native-3D
  e-mail client. A `Frame3D` (standard decoration) lays out a single live-texture
  `MailView` — a message list beside a reading / compose pane under a slim folder
  header, rasterised with the same `ImageComponent2D.set` recipe as `AgendaGrid` —
  over a strip of the agenda's runtime-drawn `AgendaButton` controls. The mailbox
  is **local-only** (no SMTP/IMAP): "sending" files a message into Sent, which
  keeps the compose / reply / send loop fully exercisable offline, and the store
  persists under `/mail/messages` in the user `Preferences` tree, seeded with a
  few sample messages on first run. Interaction is button-driven (dev mode has no
  keyboard focus routing): click a row to open and mark it read; Inbox / Sent
  switch folders and Next walks the selection; New / Reply open a draft whose
  To / Subj / Body cycle presets (recipients drawn from the same shared
  `/contacts` directory Contact 3D populates, via `ContactDirectory`); Send files
  it and jumps to Sent, Back discards it. A 48x48 `mail3d.png` icon (INDIGO tile +
  `SendMail` glyph) is generated by `lg3d-art/tools/GenerateAppIcons.java`, and the
  app is registered in the start menu (Office) by
  `lg3d-demo-apps/src/config/mail3d.lgcfg`.
- **App icons via the bundled `IconManager` library** (`libs/IconManager-1.6.0.jar`)
  — the six start-menu apps that previously fell back to the generic
  `defaultapp.png` (**Image Studio**, **Luncher**, **Natural Language Control**,
  **Chart 3D**, **Contact 3D**, **Agenda 3D**) now each get a distinct 48x48
  icon: a per-app coloured gradient/glass tile with the most fitting
  `toolbarButtonGraphics` glyph overlaid, generated by
  `lg3d-art/tools/GenerateAppIcons.java` (IconManager `createGradientIcon` +
  `createCompositeIcon(OVERLAY)` + `exportIcon`) into
  `lg3d-core/src/resources/images/icon/` and referenced from each app's `.lgcfg`.
- **`jmf23D` (Algea3D) ported but intentionally not menu-registered** — the
  JMF-backed 3D media player now compiles, and its `main` guards against a null
  `Player` so it degrades gracefully instead of throwing an NPE, but it is a
  *media player*: its default clip (`GoMonkeyDemo.ogg`) is not shipped in the
  repository and its Ogg demuxer needs the `fobs4jmf` **native** library, which
  has no modern x86-64 build. With no playable media and no available codec it
  cannot render anything from a start-menu click, so no `.lgcfg` descriptor is
  installed; it stays usable from the command line via `java ...Algea3D -m <url>`
  wherever a suitable JMF codec exists. Its `jmf`/`fobs4jmf`/`jl1.0`/`commons-cli`
  jars are still on the run classpath and its transport-button models are merged
  into `runtimeResources` for that command-line path.
- **UI/UX developer documentation** — a new top-level `docs/` tree (distinct from
  the historical `lg3d-docs/`): `docs/lg3d-native-apps.md` (building native 3D
  apps — `Frame3D`/`Component3D`, layout, the glassy widget vocabulary, event
  adapters + actions, transparency ordering, the live-graph texture-upload rule,
  and Start-Menu `.lgcfg` registration) and `docs/swingnode.md` (embedding Swing
  via `SwingNode` — offscreen texture pipeline, input forwarding, custom
  renderers, lifecycle/`dispose()`). A nested `lg3d-core/AGENTS.md` captures the
  UI/UX rules for agents, and the root `AGENTS.md` now links both.
- **Live taskbar miniatures for Swing windows** — `TitledSwingWindow` now sets a
  content thumbnail (the `Lg3dHelp` `HelpThumbnail` pattern: glass plate, drop
  shadow and a `FuzzyEdgePanel` textured with the window's own image) instead of
  falling back to the blank coloured `DefaultThumbnail` plate. To make this
  possible `SwingNode` gained a public `TextureListener` API
  (`addTextureListener` / `removeTextureListener`): observers are notified on
  the EDT whenever the rendered `Texture2D` is recreated (first capture and
  resizes), so the miniature binds — and re-binds — to the same live texture
  the window renders from, tracking panel repaints in real time.
- **Desktop configuration (Control Center → Desktop)** — a user-friendly,
  persisted settings surface for the desktop shell: **taskbar thickness**,
  **docking position** (bottom/top; left/right reserved for a later phase),
  **icon size**, the **Swing application UI font** (family + size), and a
  taskbar **auto-hide** toggle. Settings are held in a new lg3d-core
  `DesktopConfig` singleton backed by `java.util.prefs`
  (`LgPreferencesHelper`), edited from a new `DesktopPanel` in the Control
  Center, and applied **live**: the panel saves the prefs and posts a
  `DesktopConfigChangeEvent`, which the active `AdvancedGlassyTaskbar` consumes
  to re-lay-out (thickness/position/icon scale) on the fly, while
  `TitledSwingWindow` re-applies the configured font to the Swing
  `UIManager` defaults. Taskbar docking now publishes a top *or* bottom
  reserved strip (`Taskbar.get/setReservedTopHeight`), and window maximize
  clearance fills the usable band between them. Auto-hide slides the bar mostly
  off its docking edge on mouse-exit and back on edge hover (self-contained in
  the taskbar, as the legacy dormant `HideEvent` path has no poster).
- **Four native-3D games** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.games`) —
  **Tic-Tac-Toe 3D**, **Sudoku 3D**, **Chess 3D** and **Solitaire 3D**, each a
  self-contained pure-3D app on the Agenda 3D pattern: a plain-Java game **model**
  (no AWT, unit-tested headless), a live-texture `Component3D` **view** that
  rasterises the board into one `ImageComponent2D` with the `Histogram3D` `.set`
  recipe, and a `Frame3D` **host** (with the standard decoration) laying the view
  over a strip of runtime-drawn `AgendaButton` controls. Interaction is entirely
  **click-driven** — dev mode routes no keyboard focus to a `Frame3D` — picking the
  textured quad and mapping the hit back to a board cell or card. **Tic-Tac-Toe**
  plays an unbeatable full-width **minimax** opponent (perfect from either side).
  **Sudoku** generates a puzzle from a solved grid across three difficulty levels
  (Easy / Medium / Hard = 44 / 34 / 27 givens) with live row/column/box **conflict
  highlighting**, hints, solve and reset-to-puzzle. **Chess** implements the full
  ruleset — castling, en passant, promotion, check / checkmate / stalemate and
  insufficient-material draws — against a **negamax + alpha-beta** engine with
  quiescence search and piece-square evaluation (perft-verified to depth 4:
  20 / 400 / 8902 / 197281 nodes), plus legal-move highlighting, undo and board
  flip. **Solitaire** is Klondike with a recycling stock, four foundations, seven
  tableau piles, run dragging, auto-finish, undo and hints; its cards are drawn
  with **vector suit shapes** (`Path2D` / `Ellipse2D`) so it needs no extended
  font. Each game is registered in a new **Games** start-menu group by a descriptor
  under `lg3d-demo-apps/src/config` (`tictactoe`, `sudoku`, `chess`, `solitaire`)
  and gets a distinct 48x48 `IconManager` icon from `GenerateAppIcons.java`.
- **Calculator** (`lg3d-demo-apps`, `org.jdesktop.lg3d.apps.calculator`) — an
  advanced scientific calculator whose Swing `JPanel` is hosted on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow` (title bar, min/max/close, live
  taskbar thumbnail). A headless recursive-descent **expression engine**
  (`CalculatorEngine`: parentheses, `^` powers, factorial, `%`, `mod`, DEG/RAD
  trigonometry and inverses, `ln`/`log`/`sqrt`/`abs`, `pi`/`e`/`Ans`, memory
  register MC/MR/M+/M-/MS) drives an editable expression field with a **live
  result preview**, a six-column key pad and a clickable **history** list;
  keyboard input reaches the field through the `KeyEvent3D` forwarding. It is
  registered in the **Utilities** start-menu group (`calculator.lgcfg`) and gets
  a 48x48 icon whose keypad glyph is drawn inside `GenerateAppIcons.java`, the
  bundled glyph set carrying nothing calculator shaped.
- **Media Writer** (`lg3d-demo-apps`, `org.jdesktop.lg3d.apps.mediawriter`) — a
  full-featured disc and USB imaging tool whose Swing `JPanel` is hosted on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow`. A headless engine
  (`MediaWriterEngine`) drives the **real** Linux media tools — `growisofs` /
  `wodim` / `xorriso` for optical burns, `dd` for USB imaging and cloning,
  `wipefs` / `parted` / `mkfs.*` for formatting — across five modes: **burn an
  ISO to CD/DVD** (speed selection, `-dvd-compat`), **write a raw image or ISO
  to a USB key** (with `isohybrid` master-boot-record fix-up and optional
  bootable-partition handling), **clone a disc/device**, **format a removable
  key** (vfat/exfat/ntfs/ext4/ext2, optional msdos partition table, volume
  label) and **build a data disc** from a folder (`xorriso -as mkisofs`,
  Rock Ridge + Joliet, optionally burned straight to a drive). Devices are
  enumerated by parsing `lsblk -b -P` (optical / USB / internal-disk
  classification, mount points, media state from `/proc/sys/dev/cdrom/info`);
  images are probed for ISO-9660 and hybrid-magic before writing. Safety: an
  internal disk is never a writable target, mounted filesystems are unmounted
  first, destructive tools run under `pkexec` when not root, every write is
  gated by an explicit inline confirmation, and an optional **SHA-256 verify**
  re-reads the written media. The panel adds cancellation, live progress
  (parsed from `dd`/`growisofs` output) and a scrolling command log. Because
  `SwingNode` captures only its own panel, file picking and confirmation use
  **in-panel overlays** instead of modal dialogs. Registered in the
  **Utilities** start-menu group (`mediawriter.lgcfg`); its 48x48 icon gets a
  disc glyph drawn inside `GenerateAppIcons.java`, the bundled glyph set
  carrying nothing disc shaped.
- **Top-level Swing window capture in `SwingNode`** (`lg3d-core`,
  `org.jdesktop.lg3d.wg.internal.swingnode`) — a conventional, *unmodified* Swing
  application now integrates into the 3D desktop. A single global
  `AWTEventListener` (`SwingNodeWindowCapture`) watches every top-level `Window`
  the JVM opens and, instead of letting it pop onto the host desktop where the
  offscreen texture capture cannot reach it: presents a captured `JFrame` as a
  real desktop window (`CapturedFrameHost` — a `Frame3D` + `SwingNode` with the
  standard title bar / spine titles / min-max-close `Frame3DWindowDecoration`, a
  live taskbar thumbnail, and resize / title / close sync between the real frame
  and its 3D window), and paints a captured `JDialog` / `JWindow` (`JOptionPane`,
  `JFileChooser`, popups) as a centred in-scene **overlay** inside the owning
  node's texture, routing forwarded mouse and key input to it with modal
  semantics preserved (a modal dialog's own secondary EDT loop processes the
  events the node dispatches). `SwingNode.captureNow` composites the captured
  overlays on top of the hosted panel, its `RepaintManager` marks the owning node
  dirty when a captured dialog repaints, and `SwingNodeRenderer` resolves each
  input event's target through the capture registry (topmost modal dialog first,
  else the overlay under the pointer, else the node's own hidden frame). This
  removes the technical reason for the "in-panel overlays instead of modal
  dialogs" workaround noted for Media Writer above — that app is intentionally
  left as-is, but real modal dialogs now render in-scene for any app that opens
  them.
- **`SwingAppLauncher` + `--swing-app` launcher** (`lg3d-core`,
  `org.jdesktop.lg3d.utils`) — runs a conventional Swing application's `main`
  inside the desktop JVM on a dedicated thread so every window it creates is
  captured. Wired through `DisplayServerControl` (after "Start-up configuration
  completed", reading `-Dlg.swingapp`), the `:lg3d-core:run` task
  (`-PswingApp="<fqcn> [args...]"` and `-PswingAppCp=<path[:path...]>` for the
  app's classes/jar) and `run-lg3d.sh` (`--swing-app <fqcn> [args...]`,
  `--swing-app-cp <paths>`).
- **Paint drawing app** (`lg3d-demo-apps`, `org.jdesktop.lg3d.apps.paint`) — a
  conventional Swing `JFrame` raster editor (brush/pencil/shape/fill/eyedropper
  tools, layers, selections, image ops, undo/redo) registered in the Start menu
  under *Utilities* via `paint.lgcfg`. Its descriptor uses the new `swingapp`
  command verb, which opts the app into 3D window capture and launches it in-JVM,
  so its frame is captured and presented as an integrated 3D desktop window.

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
- **`lg3d-art` raster assets modernised for high-resolution displays** — the
  wallpapers shipped as 512x512 JPEGs are stretched full-screen by
  `SimpleImageBackground`, so they looked soft and blocky on modern 1080p/4K
  panels. A new reproducible tool, `lg3d-art/tools/modernize_assets.py`
  (Pillow + NumPy + SciPy), cleans and enlarges them in place while keeping the
  *same content*: JPEGs get a mild variance-gated (Wiener-like) denoise at
  native resolution to dissolve compression blockiness, a Lanczos upscale to a
  2048px longest side (capped at 4x so small icons are not over-inflated), and a
  high-quality progressive re-encode; PNGs (icons, splash art) are lossless
  already, so only the Lanczos upscale + optimised re-encode is applied, with
  alpha preserved. Per the agreed policy there is **no sharpening and no
  contrast/colour retouch** — the look is preserved, only cleaned and enlarged.
  Filenames, formats and aspect ratios are unchanged, so every runtime reference
  (BgConfig, start-menu icons, splash, GDM theme) keeps working, and the tool is
  idempotent (images already >= the target are never re-enlarged). The website
  thumbnails under `www/` and the fixed-size GDM chrome buttons are excluded.
  93 assets processed; the art payload grows ~6.8 MB -> ~37.8 MB.
- **Window flip-to-sticky gesture now requires CTRL + right-click** — the
  `Frame3DWindowDecoration` flip (and the matching flip-back on the sticky note)
  was bound to a plain BUTTON3, which never reached the frame for Swing-to-Node or
  native LG3D apps: those reserve a bare right-click for their own context menus
  and their content is non-propagatable, so `PickEngine` stopped the event before
  the frame-level listener ever saw it. Rebinding both listeners to CTRL + BUTTON3
  disambiguates the desktop gesture from app context menus; it still arrives
  through a propagatable handle (the title bar / window chrome).
- **Taskbar right-hand group reordered and inset from the screen edge** — the
  dock group now reads `[Documents] [Downloads] [Background] [Exit]` (Documents
  `-4`, Downloads `-3`, background `-2`, Exit `-1`) instead of background-first,
  and the right-aligned group is inset by a quarter bar height so the rightmost
  (Exit) icon sits on the tapered tip of the tilted glass shelf rather than
  hanging off the end of the bar.

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
  `wilkoaim3d`. (`luncher`, `orgchart`, `nlc` and `jmf23D` were previously in
  this list and have since been ported — see Added.)

### Fixed
- **Closing an in-JVM Swing app could tear down the whole desktop** — conventional
  apps run inside the desktop JVM (the `java` / `swingapp` command verbs), and they
  routinely default to `EXIT_ON_CLOSE`, so clicking their close button fired
  `System.exit` and killed lg3d along with the app (e.g. Screen Capture).
  `SwingNodeWindowCapture.onWindowOpened` now rewrites `EXIT_ON_CLOSE` to
  `DISPOSE_ON_CLOSE` on every non-host `JFrame`, so closing an app window only
  disposes that frame. (An app that calls `System.exit` directly from a menu
  handler is still out of scope.) Verified with an in-JVM probe: a plain
  `JFrame` opened as `EXIT_ON_CLOSE` (op 3) was rewritten to `DISPOSE_ON_CLOSE`
  (op 2) by the hook while the desktop kept running.
- **Window capture hijacked every conventional Swing app** — the global
  `SwingNodeWindowCapture` hook captured *all* top-level `JFrame`s unconditionally,
  so apps that used to run as normal host windows with native input (Screen
  Capture, Image Studio, Calculator, …) were hidden and re-presented as 3D windows
  driven by synthetic in-scene input, leaving their buttons unresponsive. Capture
  is now **opt-in per app**: `SwingNodeWindowCapture.registerCapturePackage` records
  the launching app's package and the hook only captures a `JFrame` whose class is
  in a registered package. The `swingapp <mainClass>` command verb (and
  `-Dlg.swingapp` / `--swing-app`) register that package; the plain `java <class>`
  verb used by every other Start-menu app does not, so those apps keep their
  native windows and working input. Only Paint opts in today.
- **Captured conventional Swing `JFrame` opened two windows on Wayland** — the
  capture layer hid the real frame only by relocating it to `(-32000,-32000)`,
  but a compositor-managed window manager (GNOME/Mutter under Wayland/XWayland)
  ignores that, so the host `JFrame` stayed mapped beside the 3D window, stole
  native input, and closing it exited the app. `SwingNodeWindowCapture` now
  unmaps the frame (`setVisible(false)`) and `SwingNode.captureNow` paints its
  **root pane** (a `JComponent`) instead of the hidden `Window`, which paints
  blank offscreen; `CapturedFrameHost` sizes the quad to the content area. The
  app now shows as a single integrated desktop window.
- **Could not type into a flipped sticky note (or any `SwingNode` text field)** —
  `SwingNodeRenderer` forwarded `KeyEvent3D`s with `target.dispatchEvent(...)`, but
  the offscreen `SwingNodeJFrame` is displayable yet never *shown*, so AWT never
  installs a focus owner: `hiddenFrame.getFocusOwner()` stayed `null`, the keys fell
  back to the content pane, and a direct dispatch of a `KeyEvent` to a component in
  an unfocused window is dropped before it ever reaches the `JTextArea`'s
  `WHEN_FOCUSED` input map. The old build relied on the excluded `lg3d-awt` peer
  toolkit (`Lg3dComponentPeer.setGlobalFocusOwner`) for this, which is unavailable
  on JDK 21 (strong encapsulation). The renderer now emulates click-to-focus —
  it remembers the deepest Swing component under a mouse press — and delivers
  keystrokes through `KeyboardFocusManager.redispatchEvent(target, evt)`, which
  hands the event straight to that component so editable widgets receive typed
  characters. Verified against a hidden-frame probe on JDK 21 with no reflection
  and no `--add-opens`.
- **Sticky-note typing came out reversed and kept losing focus** — typing "salut"
  produced "tulas" and the caret had to be re-clicked constantly. The
  `SwingNodeRenderer` input listeners run on the lg3d event thread while the
  `SwingNode` capture timer repaints the hosted panel on the EDT; mutating Swing
  state (caret / document / focus) off the EDT races with that repaint and pins
  the caret at 0, so every character inserts at position 0 (reversed text) and
  keystrokes/focus are intermittently dropped. All Swing dispatch in
  `SwingNodeRenderer` (mouse, enter/exit focus and key forwarding) is now
  marshalled onto the EDT with `SwingUtilities.invokeLater`, which serialises it
  with the capture repaint. Reproduced and verified with an off-EDT + capture-timer
  probe: off-EDT gave "tulas"/caret 0, EDT-marshalled gave "salut"/advancing caret.
- **Right-click flip to the sticky note did nothing on any app** — two compounding
  faults. (1) `Frame3DWindowDecoration.createStickyNote()` called
  `StickyNote.initialize(...)` *before* `setEnabled(true)`, but `initialize()`
  dereferences the Swing panel / title field / text area that only `enable()`
  (run from `setEnabled(true)`) creates, so every flip threw a
  `NullPointerException` that the event loop swallowed and the window never
  turned — on native 3D apps and Swing-to-Node windows alike. The native
  look-and-feel has always enabled first and initialised second; the decoration
  now does the same. (2) The flip is a frame-level `BUTTON3` listener, so it only
  fires where the pick propagates to the frame, and a decorated `Frame3D` had no
  propagatable surface to right-click: app content is deliberately
  non-propagatable (it keeps its own context menus) and the decoration backdrop
  was `setPickable(false)`. The backdrop border is now pickable and mouse-event
  propagatable — it sits *behind* the content so it never occludes or intercepts
  app clicks, yet a plain right-click on the exposed green border (or on
  `TitledSwingWindow`'s title bar) reaches the frame's flip listener. The flip is
  bound to a plain `BUTTON3` again, matching the 2006 / native X11 idiom.
- **Dock stack fan crashed on repeated hover and showed stale content** — the
  Documents/Downloads fan is shown and hidden with `Frame3D.changeEnabled`, and
  every re-enable re-ran `StandardAppContainer.addFrame3D`, which re-created the
  window animation. Replacing the animation destroys the previous
  `NaturalMotionWithSwayAnimation` *after* its target `Component3D` reference is
  cleared, so `removeListenerFromComponent3D` dereferenced a null `WeakReference`
  and threw a `NullPointerException` in the `EventProcessor`; the aborted
  `show()` also left the previously-open fan (e.g. Documents) on screen when
  hovering the other stack (Downloads). `addFrame3D` now performs its one-time
  setup (translucency listener, animation, window decoration) only once per
  frame, `Component3DAnimationTarget` tolerates an already-cleared target when
  adding/removing listeners, and opening one stack fan dismisses the other.
- **Window flip showed a plain green back instead of the sticky note** — the
  right-click flip of `Frame3DWindowDecoration` worked on both Swing
  (`TitledSwingWindow`) and pure-3D app windows, but the `StickyNote` was
  placed at `z = -1.1 x BODY_DEPTH`, inside the opaque decoration backdrop
  slab (which spans `[-2 x BODY_DEPTH, -BODY_DEPTH]` because `GlassyPanel`
  grows backwards from its local z=0). After the PI flip the slab's opaque
  back face is closest to the viewer and completely hid the note. The note
  now sits just outside the back face (`z = -2 x BODY_DEPTH - 0.0002`), so
  the flipped window shows the editable yellow note; flipping back also
  disposes the note's offscreen Swing resources, which previously leaked a
  hidden `SwingNodeJFrame` per flip cycle.
- **`TitledSwingWindow` windows could be parked but never rotated** — every
  rotation gesture of the desktop lives in frame-level listeners
  (`ZLayeredMovableLayout`'s CTRL spinner and `Frame3DWindowDecoration`'s
  middle-button spinner), and `PickEngine` only walks picked events up the
  ancestor chain while each source it meets is mouse-event propagatable; the
  Swing quad must stay non-propagatable (or Swing loses its own gestures) and
  the title bar was too, so no spin gesture ever reached the frame. The title
  bar is now propagatable, which turns it into the window's full gesture
  handle: left-drag moves, middle-drag or CTRL+left-drag rotates and
  right-click flips to the sticky note — the same idioms as pure-3D windows,
  with no duplicate listeners (the native window look-and-feel uses the same
  trick for its title panel).
- **`TitledSwingWindow` windows could be parked but not left-clicked back** —
  `StandardAppContainer` unparks a window when the *frame* receives a BUTTON1
  click (`Frame3D` click -> `Component3DToFrontEvent` -> migrate back to the
  main container), and a native window body is covered by a propagatable move
  region so a click anywhere on it reaches the frame. The Swing quad is
  deliberately non-propagatable and the only propagatable strip (the title bar)
  is edge-on once `BookshelfLayout` turns the parked frame +/-90deg, so a left
  click on the visible content died at the quad and never unparked. The quad is
  now made propagatable for the parked state only (via a
  `Component3DParkedEventAdapter`), so a click on a parked window travels up to
  the frame and restores it exactly like a native window, while live Swing
  input keeps the quad non-propagatable and untouched.
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
