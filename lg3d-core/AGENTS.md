# AGENTS.md — lg3d-core UI/UX

Guidance for AI agents creating or modifying **user interface** in Project
Looking Glass. This covers the scene-graph UI toolkit that lives in `lg3d-core`
(`org.jdesktop.lg3d.wg`, `org.jdesktop.lg3d.utils.*`) and how apps in
`lg3d-apps` / `lg3d-incubator` should consume it.

Companion guides (read these for worked examples and API detail):
- [`../docs/lg3d-native-apps.md`](../docs/lg3d-native-apps.md) — building native 3D apps
- [`../docs/lg3d-native-apps-advanced.md`](../docs/lg3d-native-apps-advanced.md) — **advanced/full-featured** native apps: the complete component, appearance, event-adapter, action, animation, cursor and scene-graph-utility reference
- [`../docs/swingnode.md`](../docs/swingnode.md) — embedding Swing into the scene graph

The root [`../AGENTS.md`](../AGENTS.md) still governs build, modules, Java 3D
migration, exclusions and commit conventions. This file adds UI/UX-specific rules
and a shared **per-role view** so every role working on the core stays coherent.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | The **scene-graph / windowing / display-server SDK and the desktop** itself. |
| Root packages | `org.jdesktop.lg3d.sg` (scene graph), `.wg` (widgets), `.utils.*`, `.scenemanager.*`, `.displayserver.*`. |
| Depends on | `lg3d-escher`; Jogamp Java 3D 1.7.2 + natives. |
| Depended on by | `lg3d-apps`, `lg3d-incubator`, `lg3d-widgets` (all `implementation project(':lg3d-core')`). |
| Also hosts | In-tree replacements under `src/contrib/java` (`Math3D`, traverser, `TransparencyOrderedGroup`, `J3fLoader`, shims). |
| Build / run | `./gradlew :lg3d-core:build` · `:lg3d-core:run` · `:lg3d-core:runtimeResources` · `./run-lg3d.sh`. |

## How the roles work together

lg3d-core is the **keystone**: every app module consumes its toolkit, and its
UI/UX rulebook (the sections below) is the single source every other module's
AGENTS.md defers to. The Architect owns the scene-graph/window boundaries and the
exclusion policy; Engineers implement against the non-negotiable UI rules; QA
verifies with the in-JVM probe + internal screencapture; the Analysts keep the
window-path and texture contracts explicit; the PM tracks the cross-module blast
radius. Disagreements are resolved in the PR, not silently in code.

## Architect

- Guard the **two window paths** (pure-3D `Frame3D` vs native X11) and the
  exclusions in the root `AGENTS.md`; a feature added to only one path affects
  only that class of window.
- Shared utilities used by more than one app module **must live here** — the
  module dependency direction forbids demo-apps/incubator/widgets from seeing
  each other's classes.
- Keep the in-tree replacements (`src/contrib/java`) and the legacy-name shims
  behaviour-compatible with the dropped jars they stand in for.
- Any change to the scene-graph facade, transparency ordering, or the SwingNode
  contract is an architecture decision with repo-wide impact.

## Engineer / Developer

- Follow the **non-negotiable UI rules** below verbatim (Jogamp-only, upload
  texture pixels before attaching, `Component3D` children only, sort
  translucency yourself, respect threading).
- Use `./gradlew :lg3d-core:compileJava` for a fast loop; `:lg3d-core:run` /
  `./run-lg3d.sh` to launch (needs `DISPLAY`).
- Runtime artwork resolves under a classpath `resources/` prefix; add assets to
  the source dirs the `runtimeResources` task assembles, not to build output.
- Add headless JUnit 5 tests under `src/test/java` for logic; verify live-graph
  behaviour with the in-JVM probe (*Verifying UI changes*).

## QA

- Verify scene-graph/rendering changes with the **in-JVM probe + internal
  screencapture** (`lg3d-core/lgscreen-*.png`); external capture is blocked under
  GNOME/Wayland. Numeric geometry alone is not proof of a visual fix.
- Read the desktop log for `EventProcessor` warnings before concluding an
  interaction is broken (usually a swallowed exception, often the texture NPE).
- `lg3d-core/lgscreen-*.png` are runtime artifacts — never commit them.
- Coverage/mutation gates are the stated 100% JaCoCo / 0 PIT target, currently
  **report-only**; report real evidence in the PR.

## Business Analyst

- Core is **platform infrastructure + the desktop shell**, not an end-user
  feature. Its "customers" are the app modules and, ultimately, the desktop user
  experience (windows, taskbar, start menu, backgrounds).
- Capability changes should be justified by what they unlock for apps or the
  shell, weighed against the JDK-21 exclusion constraints.

## Functional Analyst

- Specify behaviour in terms of the **window path**, the **texture/transparency
  contract**, and the **threading model** — these are the functional invariants
  apps rely on.
- Keep the exclusion rationale (AWT peer, native X11, RMI, ODE) current; if a
  path is re-enabled, the functional requirements change with it — flag to the
  Architect and PM first.

## Project Manager

- Commit scope is **`lg3d-core`**. Branch → commit → push → PR against `main`;
  never commit to `main`.
- Core changes are high-blast-radius: schedule a cross-module impact review
  (demo-apps / incubator / widgets) before landing.
- Done = build + headless tests + a runtime screencapture for any visible change.

## UI/UX (3D & 2D) — canonical rulebook

> The sections below are the desktop-wide **UI/UX rulebook**. Every module's
> AGENTS.md defers here for both the pure-3D (`Frame3D`) and 2D (`SwingNode`)
> surfaces; read them before touching any UI in any module.

---

## Two window paths — never conflate them

- **Pure-3D path** — `org.jdesktop.lg3d.wg.Frame3D` (a `Container3D`) managed by
  the scene manager / `StandardAppContainer`. This is the *only* path in dev mode
  (`lg.fws.mode=dev`). All UI guidance below applies here.
- **Native X11 path** — `NativeWindow3D` + `X11WindowManager` +
  `GlassyNativeWindowLookAndFeel`. **Excluded from this build.** Do not implement
  UI features here expecting them to show up in dev mode.

A feature added only to `Frame3D` affects only pure-3D apps; one added only to
the X11 path affects only real X11 windows.

---

## Non-negotiable UI rules

1. **Jogamp packages only.** `org.jogamp.vecmath.{Vector3f,Color3f,Color4f,Point3f}`
   and the `org.jdesktop.lg3d.sg` facade. Never `javax.vecmath` /
   `javax.media.j3d`.

2. **Upload texture pixels before attaching.** On a live scene graph,
   `Appearance.setTexture(tex)` calls `TextureRetained.setLive`, which
   dereferences the texture's `ImageComponent2D` image data and **NPEs if the
   component is still empty**. Always `imageComponent.set(bufferedImage)` *first*,
   then build and `setTexture`. To update in place, keep
   `ImageComponent2D.ALLOW_IMAGE_WRITE` and call `.set(...)` again — do not
   re-attach. This single rule is the cause of most "the panel/text/image is
   invisible" and "opening a file does nothing" bugs.

3. **`Container3D`/`Frame3D` accept only `Component3D` children.** Wrap raw
   `org.jdesktop.lg3d.sg.Node`s in a `Component3D`, ideally inside a
   `TransparencyOrderedGroup` (`org.jdesktop.lg3d.sg.utils.transparency`).

4. **Sort translucency yourself.** `Component3D` only auto-sorts transparency
   when a transparency *animation* is installed. For static translucent UI use a
   `TransparencyOrderedGroup` and add children back-to-front.

5. **Respect threading.** lg3d event listeners run on the **EventProcessor /
   J3dThread**, not the Swing EDT. Hop to the EDT (`SwingUtilities.invokeLater`)
   before touching Swing. An uncaught exception in a listener is swallowed into
   the desktop log as `WARNING: Exception caught in the EventProcessor` — the UI
   then silently "does nothing".

---

## Depth, draw order and overlays (transparent sorting)

Java 3D sorts **transparent** shapes back-to-front by the distance from the eye
(`Toolkit3D.getEyePositionInVworld`) to each shape's **bounding-sphere centre** —
*not* by Z. Consequences that bite in this codebase:

- An overlay docked in a screen corner (start menu, popup) has an *off-axis*
  bounding-sphere centre, so it can be **farther** from the eye than an on-axis
  full-width window quad even when its Z is nearer. Lifting it by a small Z
  margin (`z = 0`, `+0.01`, even `+0.05`) therefore does **not** bring it in
  front of a maximized window — it keeps sorting (and picking) behind it.
- To put an overlay in front of every window, lift it a **fraction of the eye
  distance** (e.g. `frontZ = eye.z * 0.4`, kept safely behind the front clip
  plane) so its bounding-sphere centre falls inside the window's, then keep the
  apparent pose unchanged with perspective compensation: multiply the overlay's
  world X/Y and node scale by `r = (eye.z - zNew)/(eye.z - zRef)`. See
  `StartMenuModel.compensatedFrontPose` / `FRONT_WORLD_Z_FRACTION`.
- Within a single subtree, explicit order still comes from
  `TransparencyOrderedGroup` (rule #4); the eye-distance sort only arbitrates
  *between* separate transparent subtrees.

Verify any overlay/occlusion claim with a framebuffer capture over a maximized
window (see *Verifying UI changes*); numeric Z alone has repeatedly proven
misleading here.

---

## Preferred UI vocabulary

Build from the existing glassy toolkit instead of new geometry or PNG assets.
For the **exhaustive** vocabulary — every widget in `utils.shape`, every action
in `utils.action`, every adapter in `utils.eventadapter`, the animation classes,
all `Cursor3D` constants, `Toolkit3D` metrics and the scene-graph traversers —
see [`../docs/lg3d-native-apps-advanced.md`](../docs/lg3d-native-apps-advanced.md)
(the full component index and the advanced MVC/`Component3D` patterns).

- **Panels/shapes:** `GlassyPanel`, `GlassyBentPanel`, `GlassyCurvedPanel`,
  `GlassyDisc`, `GlassyRingPanel`, `Box` (`org.jdesktop.lg3d.utils.shape`).
- **Text:** `GlassyText2D(text, maxWidth, height, Color4f, LightDirection,
  Alignment)` — rendered to a texture at runtime, no image asset. Its geometry
  grows **upward** (and about the origin for CENTER/RIGHT); offset by
  `-height * 0.5f` to vertically centre on a point.
- **Appearance:** `SimpleAppearance(r,g,b,a, flags)`; pass
  `SimpleAppearance.DISABLE_CULLING` for flat double-sided quads. Tint comes from
  the Material **diffuse** colour.
- **Positioning:** `Component3D.setTranslation(x,y,z)` or
  `OriginTranslation(node, Vector3f)`.

### Interaction idiom (event adapter + action)

```java
c.addListener(new MouseEnteredEventAdapter(
        new AppearanceChangeAction(bg, hoverAppearance)));  // hover tint
c.addListener(new MouseEnteredEventAdapter(
        new ScaleActionBoolean(c, 1.06f, 120)));            // hover grow
c.addListener(new MouseClickedEventAdapter(onClickAction)); // click
c.setCursor(Cursor3D.SMALL_CURSOR);
```

- Adapters: `org.jdesktop.lg3d.utils.eventadapter.*`
  (`MouseClickedEventAdapter`, `MouseEnteredEventAdapter`,
  `MousePressedEventAdapter`, `MouseDraggedEventAdapter`,
  `MouseWheelEventAdapter`, `KeyPressedEventAdapter`, `MouseHoverEventAdapter`, …).
- Actions: `org.jdesktop.lg3d.utils.action.*` (`ActionNoArg`, `ActionBoolean`,
  `ActionInt`, `AppearanceChangeAction`, `ScaleActionBoolean`,
  `RotateActionBoolean`, `TransparencyAction*`, `AppLaunchAction`, …).
- Prefer an existing action over a bespoke `ActionNoArg` when one fits.

### Cursor feedback

Set a `Cursor3D` (`SMALL_CURSOR`, `MEDIUM_CURSOR`, …) on any interactive
`Component3D` so the user gets hover affordance, matching the rest of the
desktop.

---

## Layout conventions

- Origin at the **window centre**, `+x` right, `+y` up, `+z` toward the viewer.
- Size windows/children from `Toolkit3D.getToolkit3D().getScreenWidth()/
  getScreenHeight()`; derive margins/paddings as fractions of `W`/`H` so layouts
  scale. Avoid hard-coded absolute constants.
- Convert pixel sizes to 3D units with `widthNativeToPhysical(px)` /
  `heightNativeToPhysical(px)`.
- A decorated `Frame3D` shows minimize/maximize/close at the **top-right** plus
  right-click flip and middle-drag spin. Keep your own controls clear of that
  corner (or set `Frame3DWindowDecoration.OPT_OUT_PROPERTY` and draw your own).
- Use small positive `z` offsets (e.g. `0.002f`) to layer foreground elements
  above the backdrop and avoid z-fighting.

---

## SwingNode (Swing content in 3D)

Use `org.jdesktop.lg3d.wg.SwingNode` when you need real Swing widgets (text
input, `JList`, `JScrollPane`, `JTable`, existing Swing panels). It renders the
panel offscreen into an RGBA `Texture2D` on a quad and forwards mouse, wheel and
keyboard input.

Agent rules:
- Call `dispose()` when the node is discarded; do not reuse a disposed node.
- `setTransparency(float)` only affects the **default** renderer; a custom
  `SwingNodeRenderer` needs its own setter with
  `TransparencyAttributes.ALLOW_VALUE_WRITE`.
- Do **not** call the deprecated `setPanel(...)` / `addMouseHandlers(...)`;
  use `setJPanel(...)` / `addInputHandlers(...)`.
- Do not reintroduce the old `printHeirarchy` background-mutation behaviour;
  hierarchy dumping is gated behind `-Dlg3d.swingnode.debugHierarchy=true`.
- The default renderer fills the whole `Frame3D` with **one opaque, pickable,
  non-propagatable quad**. That quad swallows the BUTTON1 drag the frame mover
  needs (so the window cannot be moved) and covers the auto-attached
  min/max/close buttons. Hosted windows therefore reserve a title strip: shift
  the node down by `-titleBarH/2`, add a pickable + **propagatable** title bar as
  the window's gesture handle (move / spin / flip), and publish
  `Frame3DWindowDecoration.TITLE_BAR_HEIGHT_PROPERTY` before `changeEnabled` so
  the decoration centres its buttons on the strip, aligned with the title text.
  `TitledSwingWindow` (`lg3d-apps`) is the reference implementation.
- Capturing a conventional `JFrame` (`SwingNodeWindowCapture`) on a
  compositor-managed session (GNOME/Wayland): **unmap** it (`setVisible(false)`),
  never "hide" it by relocating to `(-32000,-32000)` — the compositor ignores the
  move, the window stays mapped and steals native input. Paint the frame's
  **root pane** (`JComponent`), not the hidden `Window` (a hidden `Window` paints
  blank offscreen), and size the quad to the content area, not `frame.getSize()`.
- **Sticky note (flip target): `setEnabled(true)` BEFORE `initialize()`.**
  `StickyNote.initialize()` dereferences the Swing panel / title field / text
  area that only `enable()` (run from `setEnabled(true)`) creates; calling
  `initialize()` first throws an NPE that the event loop swallows, so the window
  silently never flips.
- **Typing into a `SwingNode` text field:** the offscreen host frame is never
  *shown*, so AWT installs no focus owner and a direct `dispatchEvent` of a
  `KeyEvent` is dropped. Emulate click-to-focus (remember the deepest component
  under a mouse press) and deliver keys via
  `KeyboardFocusManager.redispatchEvent(target, evt)`, marshalled on the EDT
  (rule #5) or the caret races the capture repaint and text inserts reversed.

See [`../docs/swingnode.md`](../docs/swingnode.md) for the full contract.

---

## Resizing live windows and chrome (maximize)

- Hosted `SwingNode` windows maximize by **native resize**, not uniform scale:
  scaling only magnifies the fixed-resolution texture (blurry text) and
  letterboxes narrow windows. `Frame3DWindowDecoration.toggleMaximized` consults
  the `HostedWindowResizer` registry (`org.jdesktop.lg3d.wg`); a registered frame
  is resized in native pixels via `SwingNode.setHostedSize`, which re-lays-out
  the Swing hierarchy with a recursive `invalidate()`+`validate()` (a bare
  `revalidate()`+`doLayout()` leaves nested `JScrollPane` subtrees at stale
  bounds). Pure-3D windows keep uniform scale-to-fit.
- On a **live** scene graph, resize `GlassyPanel` / `RectShadow` **in place** via
  their `setSize()` (both carry `ALLOW_COORDINATE_WRITE`). Removing/re-adding a
  non-`BranchGroup` shape throws `RestrictedAccessException`; detaching a group
  and re-inserting it throws `MultipleParentException`. `Component3D` chrome
  (title bar, spines) *is* a `BranchGroup` and may be removed and rebuilt.
- Keep a top headroom band when maximizing so the title strip and its buttons
  stay inside the visible area and clickable.

---

## Registering an app in the Start Menu

Add a `*.lgcfg` (`StartMenuItemConfig`) descriptor under
`lg3d-apps/src/config/` (bundled to `config/demo`; discovery scans
`config/demo` and `config/incubator`). Use `command = java <MainClass>` for
in-JVM launch, `menuGroup`, `name`, `desc`, and a
`displayResourceUrlName = resource:///resources/images/icon/....png` icon.

---

## Verifying UI changes

1. **Compile:** `./gradlew build` (or `:lg3d-core:compileJava` /
   `:lg3d-incubator:compileJava` for a fast loop).
2. **Run:** `./run-lg3d.sh` or `./gradlew :lg3d-core:run` (needs `DISPLAY`).
3. **Capture:** rely on lg3d's **internal screencapture** →
   `lg3d-core/lgscreen-0-0.png`. External X tools (`import`, `scrot`, AWT
   `Robot`) return black/hang under GNOME/Wayland + Xwayland.
4. **Logs:** read the desktop log for `EventProcessor` warnings before concluding
   an interaction is broken — most "does nothing" reports are a swallowed
   exception (often the texture NPE from rule #2).
5. `lg3d-core/lgscreen-*.png` are **runtime artifacts**: do not commit them.
6. **In-JVM probe** for anything that needs the live scene graph: write a probe
   class with a `main`, compile it against
   `lg3d-core/build-gradle/classes/java/main` (+ the Jogamp jars + demo-apps
   classes), then run
   `./run-lg3d.sh --swing-app-cp <ABS classes dir> -s <fqcn>`. Use **absolute**
   paths for `--swing-app-cp` — entries resolve relative to the `lg3d-core`
   project dir, so a relative path yields `ClassNotFoundException`. Capture with
   `AppConnectorPrivate.getAppConnector().postEvent(new ScreenCaptureEvent(dir),
   null)` and read `dir/lgscreen-0-N.png`. The desktop JVM exits when the probe
   `main` returns, so no leftover process needs killing.
7. Under GNOME/Wayland every *external* capture path is blocked (`Robot`,
   `gnome-screenshot`, `import`, `scrot`); only the internal `ScreenCaptureEvent`
   works. Numeric geometry alone is not proof of a visual fix — confirm with a
   capture before declaring success.

---

## Commit / PR

Follow the root `AGENTS.md` Conventional Commit rules. UI changes are usually
`fix(lg3d-core)` / `feat(lg3d-incubator)` / `style(...)`. Keep the subject
imperative and ≤50 chars, add a `CHANGELOG.md` entry, and run the
branch → commit → push → PR flow.
