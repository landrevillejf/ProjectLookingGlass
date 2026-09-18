# AGENTS.md — lg3d-core UI/UX

Guidance for AI agents creating or modifying **user interface** in Project
Looking Glass. This covers the scene-graph UI toolkit that lives in `lg3d-core`
(`org.jdesktop.lg3d.wg`, `org.jdesktop.lg3d.utils.*`) and how apps in
`lg3d-demo-apps` / `lg3d-incubator` should consume it.

Companion guides (read these for worked examples and API detail):
- [`../docs/lg3d-native-apps.md`](../docs/lg3d-native-apps.md) — building native 3D apps
- [`../docs/swingnode.md`](../docs/swingnode.md) — embedding Swing into the scene graph

The root [`../AGENTS.md`](../AGENTS.md) still governs build, modules, Java 3D
migration, exclusions and commit conventions. This file adds UI/UX-specific rules.

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

## Preferred UI vocabulary

Build from the existing glassy toolkit instead of new geometry or PNG assets:

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

See [`../docs/swingnode.md`](../docs/swingnode.md) for the full contract.

---

## Registering an app in the Start Menu

Add a `*.lgcfg` (`StartMenuItemConfig`) descriptor under
`lg3d-demo-apps/src/config/` (bundled to `config/demo`; discovery scans
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

---

## Commit / PR

Follow the root `AGENTS.md` Conventional Commit rules. UI changes are usually
`fix(lg3d-core)` / `feat(lg3d-incubator)` / `style(...)`. Keep the subject
imperative and ≤50 chars, add a `CHANGELOG.md` entry, and run the
branch → commit → push → PR flow.
