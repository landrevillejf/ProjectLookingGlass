# LG3D Native Apps — Advanced Guide

A **full-featured** guide to building advanced native 3D applications for
Project Looking Glass. Where [`lg3d-native-apps.md`](./lg3d-native-apps.md)
teaches the anatomy of a *minimal* app, this guide exposes **every component**
of the pure-3D toolkit — the complete widget, appearance, event-adapter, action,
animation, cursor, transform and scene-graph-utility vocabulary — and shows how
to assemble them into a production-grade app with an MVC structure.

> Scope: the **pure-3D path** (`org.jdesktop.lg3d.wg.Frame3D` managed by the
> scene manager), the only window path available in dev mode
> (`lg.fws.mode=dev`). The heavyweight native-X11 path (`NativeWindow3D` +
> `X11WindowManager`) is excluded from this build.
>
> Read first: [`lg3d-native-apps.md`](./lg3d-native-apps.md) (basics),
> [`swingnode.md`](./swingnode.md) (embedding Swing), and the canonical UI/UX
> rulebook [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md). The five
> **non-negotiable UI rules** in that file (Jogamp-only, upload texture pixels
> before attaching, `Component3D`-only children, sort translucency yourself,
> respect threading) apply to everything below.

The reference implementation for this guide is
`org.jdesktop.lg3d.apps.imagestudio` (in `lg3d-incubator`): `ImageStudioApp`
(entry point), `ImageStudioFrame3D` (layout), `Ui3D` (widget factory),
`Toolbar3D` / `Slider3D` / `FileStrip3D` / `Histogram3D` / `ImageCanvas3D`
(custom `Component3D`s) and `EditorModel` (observable model).

---

## Table of contents

1. [Architecture of an advanced app](#1-architecture-of-an-advanced-app)
2. [The window layer: `Frame3D`, `Container3D`, `LayoutManager3D`](#2-the-window-layer)
3. [The interactive unit: `Component3D` complete API](#3-component3d-complete-api)
4. [The complete widget vocabulary (`utils.shape`)](#4-the-complete-widget-vocabulary)
5. [Appearances and materials](#5-appearances-and-materials)
6. [Text rendering](#6-text-rendering)
7. [Events: the complete adapter reference](#7-events-the-complete-adapter-reference)
8. [Actions: the complete reference](#8-actions-the-complete-reference)
9. [Animation](#9-animation)
10. [Cursors](#10-cursors)
11. [Transparency, depth and draw order](#11-transparency-depth-and-draw-order)
12. [Textures and the live-graph rule](#12-textures-and-the-live-graph-rule)
13. [Loading 3D models](#13-loading-3d-models)
14. [`Toolkit3D`: metrics and coordinate conversion](#14-toolkit3d-metrics-and-coordinate-conversion)
15. [Scene-graph traversal utilities](#15-scene-graph-traversal-utilities)
16. [The observable-model (MVC) pattern](#16-the-observable-model-mvc-pattern)
17. [Embedding Swing with `SwingNode`](#17-embedding-swing-with-swingnode)
18. [Worked example: a custom `Slider3D`](#18-worked-example-a-custom-slider3d)
19. [Registering in the Start Menu](#19-registering-in-the-start-menu)
20. [Build, run and verify](#20-build-run-and-verify)
21. [Complete component index](#21-complete-component-index)
22. [Advanced checklist and pitfalls](#22-advanced-checklist-and-pitfalls)

---

## 1. Architecture of an advanced app

A full-featured native app separates **model**, **view** and **widget factory**
so the scene graph stays declarative and testable:

```
MyApp                     entry point: main() builds the frame, enables + shows it
├── MyModel               plain observable POJO (no scene graph) — state + Listener
├── MyFrame3D             extends Frame3D: lays out children from Toolkit3D metrics
│   ├── Ui3D              static widget factory (panels, labels, buttons)
│   ├── Toolbar3D         custom Component3D (a row of buttons)
│   ├── Slider3D          custom Component3D (drag interaction)
│   ├── Canvas3D          custom Component3D (textured quad, wheel zoom)
│   └── Histogram3D       custom Component3D (procedural geometry, model listener)
└── (SwingNode)           optional real-Swing content area
```

Rules of thumb drawn from the reference app:

- **The model knows nothing about Java 3D.** `EditorModel` is a plain class with
  a `Listener` interface; views subscribe and repaint on `modelChanged`. This
  keeps logic headless-testable (see §16).
- **The frame derives all geometry from `Toolkit3D`** screen metrics and lays
  children out with explicit transforms — there is no CSS/BoxLayout in the
  pure-3D path (§2, §14).
- **Each reusable 3D control is a `Component3D` subclass** that owns its own
  geometry and listeners, exposing a small setter API (`setModel`,
  `resetView`, …). The frame positions it with `setTranslation`.
- **A single `Ui3D` factory** centralises the glassy vocabulary so every button
  and label looks identical and the hover/click idiom lives in one place (§4).

The entry point is deliberately tiny:

```java
public class ImageStudioApp {
    public static void main(String[] args) {
        ImageStudioFrame3D app = new ImageStudioFrame3D();
        app.changeEnabled(true);   // register with the app connector / scene manager
        app.changeVisible(true);   // show it
    }
}
```

`changeEnabled(true)` + `changeVisible(true)` is the canonical "make the window
appear" pair (`Lg3dHelp`, `FileManager`, `ImageStudioApp`). Because `main` runs
**in-JVM** on the desktop classpath, never call `System.exit` — it would kill
the whole desktop.

---

## 2. The window layer

### `Frame3D extends Container3D`

The top-level window. It is a `Container3D`, so it accepts **only `Component3D`
children**. Unless it sets `Frame3DWindowDecoration.OPT_OUT_PROPERTY`, it gets
the standard decoration (minimize/maximize/close at the top-right, right-click
flip, middle-drag spin) — keep your own controls clear of that corner.

| Member | Purpose |
|---|---|
| `Frame3D()` | construct an undecorated-until-enabled window |
| `setEnabled(boolean)` / `changeEnabled(boolean[, int duration])` | register/unregister with the scene manager (animated form eases in) |
| `isEnabled()` / `isFinalEnabled()` / `isEnabledInternal()` | enable state (final = post-animation target) |
| `setThumbnail(Thumbnail)` / `getThumbnail()` | live thumbnail used by the taskbar/window-switcher |
| `setAnimation(Frame3DAnimation)` / `setAnimation(Component3DAnimation)` | enable/visible transition animation |
| `setProperty(Object key, Object value)` / `getProperty(Object key)` | arbitrary per-frame metadata (decoration opts, `TITLE_BAR_HEIGHT_PROPERTY`, …) |

`Frame3D` inherits every `Component3D` transform/animation/listener method
(§3), plus the `Container3D` child management.

### `Container3D`

A layout container; children must be `Component3D`. `Frame3D` is the container
you normally build an app in; other `Container3D` subclasses in the desktop
include `AppContainer` (the scene manager's window container), `Background`,
`Taskbar`, `StartMenuModel`, `GlassyDiscContainer` and `GlassyRingPanelContainer`.
A container can own a `LayoutManager3D`.

### `LayoutManager3D` (interface)

Optional automatic layout, mirroring Swing's `LayoutManager`:

```java
public interface LayoutManager3D {
    void setContainer(Container3D cont);
    void layoutContainer();
    void addLayoutComponent(Component3D comp, Object constraints);
    void removeLayoutComponent(Component3D comp);
    boolean rearrangeLayoutComponent(Component3D comp, Object newConstraints);
}
```

Most hand-built apps (Image Studio) skip a layout manager and position children
with explicit transforms derived from the window size — this gives pixel-precise
control over the glassy look. Implement `LayoutManager3D` only when children are
added/removed dynamically and you want them reflowed.

### Wrapping raw nodes

`Container3D`/`Frame3D` reject raw `org.jdesktop.lg3d.sg.Node`s. Wrap them in a
`Component3D`, ideally inside a `TransparencyOrderedGroup` (rule #3/#4). This is
the single most-used idiom (`Ui3D.component`):

```java
static Component3D component(Node node) {
    TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
    tog.addChild(node);
    Component3D c = new Component3D();
    c.addChild(tog);
    return c;
}
```

---

## 3. `Component3D` complete API

`Component3D` is the interactive/animatable unit: attach listeners and cursors
here, and animate its transform, scale, rotation and transparency.

### Child management
`addChild(Node)`, `insertChild(Node, int)`, `setChild(Node, int)`,
`removeChild(Node)`, `removeChild(int)`, `removeAllChildren()`,
`getChild(int)`, `getAllChildren()`, `numChildren()`, `indexOfChild(Node)`,
`moveTo(BranchGroup child)` (migrate this component under another branch).

### Transform (immediate vs animated)
| Immediate | Animated (eases over `duration` ms) | Query |
|---|---|---|
| `setTranslation(x,y,z)` | `changeTranslation(x,y,z[,duration])`, `changeTranslation(Vector3f[,duration])` | `getTranslation(Vector3f)`, `getFinalTranslation(Vector3f)` |
| `setRotationAxis(x,y,z)`, `setRotationAngle(float)` | `changeRotationAxis(Vector3f[,duration])`, `changeRotationAngle(float[,duration])` | `getRotationAngle()`, `getRotationAxis(Vector3f)`, `getFinalRotationAngle()`, `getFinalRotationAxis(Vector3f)` |
| `setScale(float)`, `setScale(x,y,z)` | `changeScale(float[,duration])`, `changeScale(Vector3f,duration)`, `changeScale(x,y,z,duration)` | `getScale()`, `getScale(Vector3f)`, `getFinalScale()`, `getFinalScale(Vector3f)` |

`set*` snaps; `change*` animates via the installed `Component3DAnimation` (or the
default scheduler). `getFinal*` returns the animation's target value — useful to
know where an in-flight animation will land.

### Transparency & visibility
`setTransparency(float)`, `changeTransparency(float[,duration])`,
`getTransparency()`, `getFinalTransparency()`; `setVisible(boolean)`,
`changeVisible(boolean[,duration])`, `isVisible()`, `isFinalVisible()`.
`0.0` = opaque, `1.0` = fully transparent.

### Sizing
`setPreferredSize(Vector3f)`, `getPreferredSize(Vector3f)`.

### Events & picking
| Member | Purpose |
|---|---|
| `addListener(LgEventListener)` / `removeListener(...)` | attach event adapters (§7) |
| `setCursor(Cursor3D)` / `getCursor()` | hover affordance (§10) |
| `setMouseEventSource(Class, boolean)` / `isMouseEventSource(Class)` | opt in/out of generating a specific `*Event3D` |
| `setMouseEventEnabled(boolean)` / `isMouseEventEnabled()` | master switch for mouse-event generation |
| `setMouseEventPropagatable(boolean)` / `isMouseEventPropagatable()` | whether events bubble to parents (critical for `SwingNode` title bars) |
| `setKeyEventSource(boolean)` / `isKeyEventSource()` | opt in to keyboard events |
| `postEvent(LgEvent)` | fire a custom event into the lg3d event system |

### Animation & misc
`setAnimation(Component3DAnimation)`, `getAnimation()`,
`getTranslationTo(Component3D, Vector3f)` (relative offset to another
component), `requestParentToRevalidate()`.

> **Live-graph edit rule.** On a live scene graph you cannot remove/re-add a
> non-`BranchGroup` shape (`RestrictedAccessException`) nor re-parent a group
> (`MultipleParentException`). Resize `GlassyPanel`/`RectShadow` **in place**
> via their `setSize()` (both carry `ALLOW_COORDINATE_WRITE`). `Component3D`
> chrome that *is* a `BranchGroup` may be removed and rebuilt.

---

## 4. The complete widget vocabulary

All glassy widgets live in `org.jdesktop.lg3d.utils.shape`. Build UIs from these
rather than inventing geometry or PNG assets.

### Panels & bodies
| Class | What it is |
|---|---|
| `GlassyPanel(w, h, depth, Appearance)` | the standard translucent rounded body. Tint = the appearance's **Material diffuse** colour; alpha via a `Color4f`-based `SimpleAppearance`. Carries `ALLOW_COORDINATE_WRITE`, so `setSize(...)` works live. |
| `GlassyBentPanel` | a panel bent along an arc (3D relief) |
| `GlassyCurvedPanel` | a smoothly curved panel |
| `GlassyDisc` | a translucent disc |
| `GlassyRingPanel` | a ring/annulus panel |
| `FuzzyEdgePanel` / `FuzzyEdgePanelImpl` / `OrientedFuzzyEdgePanel` | soft-edged panels (the `SwingNode` default renderer uses a fuzzy-edge panel) |
| `ImagePanel` | a flat quad that shows an image texture |

### Primitives & solids
`Box`, `Sphere`, `Cone`, `Cylinder`, `Disc`, `ColorCube`, `Primitive`,
`Quadrics`, `Sparkle` (a glint/highlight sprite).

### Lines & shadows
`BlurLine`, `ExtendableLine`, `RectShadow`, `RingShadow`, `RoundShadow`,
`ShadowAppearance` — drop shadows and hairlines for depth cues. `RectShadow`
also carries `ALLOW_COORDINATE_WRITE` (resize in place).

### Positioning & helpers
`OriginTranslation(Node, Vector3f)` — wrap a node so its local origin moves to
`(x,y,z)` in the parent. `GeomBuffer`, `PickableRegion` (an invisible pickable
area), `Text2D`, `GlassyBentText2D`, `GlassyText2D` (§6),
`GlassyTextTextureGenerator` (the glyph rasteriser), `SimpleAppearance`,
`SimpleShaderAppearance`, `ShadowAppearance` (§5).

### The `Ui3D` factory pattern

Centralise the vocabulary in one static factory so the whole app looks
consistent. The reference `Ui3D` exposes:

```java
static SimpleAppearance appearance(Color4f c) {          // DISABLE_CULLING flat quad
    return new SimpleAppearance(c.x, c.y, c.z, c.w, SimpleAppearance.DISABLE_CULLING);
}
static GlassyPanel panel(float w, float h, float depth, Color4f c) {
    return new GlassyPanel(w, h, depth, appearance(c));
}
static GlassyText2D makeText(String s, float maxWidth, float h, Color4f c,
                             GlassyText2D.Alignment align) {
    return new GlassyText2D(s, maxWidth, h, c,
                            GlassyText2D.LightDirection.TOP_LEFT, align);
}
static OriginTranslation at(Node node, float x, float y, float z) {
    return new OriginTranslation(node, new Vector3f(x, y, z));
}
// A text label vertically centred on cy (GlassyText2D grows upward).
static OriginTranslation label(String s, float maxWidth, float h, Color4f c,
                               GlassyText2D.Alignment align, float cx, float cy, float z) {
    GlassyText2D t = makeText(s, maxWidth, h, c, align);
    return new OriginTranslation(t, new Vector3f(cx, cy - h * 0.5f, z));
}
```

### The reusable push button (hover + click idiom)

A button combines a panel, a centred label, two `MouseEnteredEventAdapter`s
(hover tint + hover grow) and a `MouseClickedEventAdapter` (click):

```java
static Component3D button(String text, float w, float h, float textH,
        Color4f off, Color4f on, Color4f textCol, ActionNoArg onClick) {
    Component3D c = new Component3D();
    TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
    GlassyPanel bg = panel(w, h, 0.002f, off);
    tog.addChild(bg);
    tog.addChild(label(text, w * 0.94f, textH, textCol,
            GlassyText2D.Alignment.CENTER, 0.0f, 0.0f, 0.0025f));
    c.addChild(tog);

    c.addListener(new MouseEnteredEventAdapter(
            new AppearanceChangeAction(bg, appearance(on))));  // hover tint
    c.addListener(new MouseEnteredEventAdapter(
            new ScaleActionBoolean(c, 1.06f, 120)));           // hover grow
    if (onClick != null) {
        c.addListener(new MouseClickedEventAdapter(onClick));  // click
    }
    c.setCursor(Cursor3D.SMALL_CURSOR);
    return c;
}
```

The caller positions the returned component with `setTranslation`. This exact
idiom is proven by `Lg3dHelp.Button` and reused across Image Studio.


---

## 5. Appearances and materials

`Appearance` (`org.jdesktop.lg3d.sg`) bundles material, texture, transparency and
polygon attributes. The toolkit ships convenience subclasses in `utils.shape`:

### `SimpleAppearance extends Appearance`

The workhorse. Tint comes from the **Material diffuse** colour; alpha comes from
a `Color4f`-style constructor.

Constructors:
```java
SimpleAppearance(float r, float g, float b)                 // opaque
SimpleAppearance(float r, float g, float b, float a)         // alpha
SimpleAppearance(float r, float g, float b, int type)        // flags, opaque
SimpleAppearance(float r, float g, float b, float a, int type)
SimpleAppearance(URL imageUrl[, int type[, ...]])            // textured
```

Flag bits (OR them together):
| Flag | Effect |
|---|---|
| `NO_GLOSS` | matte (no specular highlight) |
| `ENABLE_TEXTURE` | enable texturing (required for `GlassyText2D`, `ImagePanel`) |
| `DISABLE_CULLING` | render both faces — **always pass this for flat quads** |
| `DEST_BLEND_ONE` | additive destination blend |

Mutators (safe on a live graph): `setColor(r,g,b[,a])`, `setAlpha(float)`,
`setTexture(URL)`.

```java
static SimpleAppearance appearance(Color4f c) {
    return new SimpleAppearance(c.x, c.y, c.z, c.w, SimpleAppearance.DISABLE_CULLING);
}
```

### `ShadowAppearance`, `SimpleShaderAppearance`
`ShadowAppearance` renders a soft shadow quad; `SimpleShaderAppearance` is the
programmable-shader variant for advanced lighting. Use them when a plain
`SimpleAppearance` cannot express the effect.

---

## 6. Text rendering

`GlassyText2D extends Shape3D` renders anti-aliased text into a texture **at
runtime** — no image assets, no font files to ship.

```java
public enum Alignment     { LEFT, CENTER, RIGHT }
public enum LightDirection{ TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

GlassyText2D(String text, float maxWidth, float height, Color4f textColor)
GlassyText2D(String text, float maxWidth, float height, Color4f textColor,
             LightDirection lightDirection, Alignment alignment)
GlassyText2D(..., Alignment alignment, float widthScale)
GlassyText2D(..., float widthScale, boolean vertical)
```

Mutators: `setText(String)` (re-rasterises and re-uploads the glyph texture),
`setWidth(float widthLimit)` (reflows/reflows within a max width).

**Geometry grows upward**, and for `CENTER`/`RIGHT` alignment it also grows
about the origin. To vertically centre a label on a point `cy`, offset it by
`-height * 0.5f` (see `Ui3D.label`). The appearance is built with
`ENABLE_TEXTURE | DISABLE_CULLING`, so text is legible from both sides.

For text on a curved/bent surface use `GlassyBentText2D`; for the lowest-level
glyph rasterisation see `GlassyTextTextureGenerator`. `Text2D` is the plain
(untextured-glass) variant.

> **Orientation knob.** The glyph texture is uploaded with the image origin at
> the upper-left (`yUp=false`) and a flipped texcoord `v` mapping. When text
> renders blank or upside-down, `GlassyTextTextureGenerator`'s `yUp`/texcoord
> handling is the thing to inspect (see §12).

---

## 7. Events: the complete adapter reference

lg3d uses a **listener + declarative action** model, not Swing's
`ActionListener`. An **event adapter** (`org.jdesktop.lg3d.utils.eventadapter`)
listens for a 3D event class and fires an **action** (§8). Attach with
`component.addListener(adapter)`.

### Mouse adapters
| Adapter | Fires on | Typical action |
|---|---|---|
| `MouseClickedEventAdapter` | click (optionally filtered by `ButtonId`, double-click, `ModifierId`) | `ActionNoArg`, `ActionFloat2`, `ActionFloat3` |
| `MousePressedEventAdapter` | button press | `ActionNoArg` / positional |
| `MouseEnteredEventAdapter` | pointer enter/exit | `ActionBoolean` (true=enter) |
| `MouseDraggedEventAdapter` | drag | `ActionFloat2` / `ActionFloat3` (delta or point) |
| `MouseMotionEventAdapter` | move + drag | positional |
| `MouseMovedEventAdapter` | move (no button) | positional |
| `MouseWheelEventAdapter` | wheel | `ActionInt` (rotation) |
| `MouseHoverEventAdapter` | hover dwell | `ActionBoolean` |
| `MouseDragDistanceAdapter` | drag distance threshold | distance-based |

`MouseClickedEventAdapter` constructor overloads (verified) let you combine
filters, e.g. double-click with a modifier:
```java
MouseClickedEventAdapter(ActionNoArg action)
MouseClickedEventAdapter(ButtonId button, ActionNoArg action)
MouseClickedEventAdapter(Boolean dblClick, ActionNoArg action)
MouseClickedEventAdapter(ModifierId modifier, ActionNoArg action)
MouseClickedEventAdapter(ButtonId button, Boolean dblClick, ModifierId modifier, ActionNoArg action)
// ...and the same shapes for ActionFloat2 / ActionFloat3 (positional callbacks)
```

### Keyboard adapters
`KeyPressedEventAdapter(ActionBooleanInt action)` and
`KeyPressedEventAdapter(ModifierId modifier, ActionBooleanInt action)` — the
action receives `(source, pressed, keyCode)`. This is the idiomatic hook for
in-window shortcuts; global desktop shortcuts are caught on the scene root the
same way. `KeyTypedEventAdapter` fires for typed characters.

### Component-lifecycle adapters
`Component3DToFrontEventAdapter(ActionComponent3D)`,
`Component3DToBackEventAdapter`, `Component3DHighlightEventAdapter`,
`Component3DParkedEventAdapter`, `Component3DVisualAppearanceEventAdapter`,
`Component3DManualMoveEventAdapter`, `Component3DManualResizeEventAdapter` —
react to focus/selection/hover/move/resize of a component.

### Filter enums (verified)
- `MouseEvent3D.ButtonId`: `NOBUTTON, BUTTON1, BUTTON2, BUTTON3`
- `InputEvent3D.ModifierId`: `ALT, ALT_GRAPH, CTRL, META, SHIFT, BUTTON1, BUTTON2, BUTTON3`

### Base & utilities
`EventAdapter` (abstract base — subclass it for a bespoke event class; declare
`getTargetEventClasses()`), `GenericEventAdapter` (fires on any `LgEvent`),
`EventAdapterUtil` (button/modifier matching helpers).

> **Threading (rule #5).** Listeners run on the lg3d **EventProcessor /
> J3dThread**, *not* the Swing EDT. Hop with `SwingUtilities.invokeLater(...)`
> before touching Swing. An uncaught exception in `performAction` is swallowed
> into the desktop log as `WARNING: Exception caught in the EventProcessor` —
> that line is the first thing to read when "a click does nothing".

---

## 8. Actions: the complete reference

Actions (`org.jdesktop.lg3d.utils.action`) are reusable behaviours fired by
adapters. The `Action*` naming encodes the callback signature:

### Action interfaces (by payload)
| Interface | `performAction` signature |
|---|---|
| `ActionNoArg` | `(LgEventSource source)` |
| `ActionBoolean` | `(source, boolean)` |
| `ActionBooleanInt` | `(source, boolean, int)` |
| `ActionBooleanFloat` / `Float2` / `Float3` | `(source, boolean, float…)` |
| `ActionInt` | `(source, int)` |
| `ActionFloat` / `Float2` / `Float3` | `(source, float[, float[, float]])` |
| `ActionChar` | `(source, char)` |
| `ActionComponent3D` | `(source, Component3D)` |
| `Action` | marker base |

### Concrete transform/animation actions
| Action | Constructor | Effect |
|---|---|---|
| `AppearanceChangeAction` | `(Shape3D target, Appearance on)` | swap appearance on enter/exit (hover tint) |
| `ScaleActionBoolean` | `(Component3D, float onScale[, int duration])` | grow on `true`, restore on `false` |
| `ScaleActionFloat` | `(Component3D[, int duration])` | scale driven by a float payload |
| `RotateActionBoolean` | `(Component3D, float onAngle[, int duration])` | rotate on/off |
| `RotateActionFloat` | `(Component3D[, int duration])` | rotate by float payload |
| `TranslateActionBoolean` | `(Component3D, Vector3f onTranslation[, int duration])` | move on/off |
| `TranslateActionFloat` | `(Component3D[, int duration])` | move by float payload |
| `TransparencyActionBoolean` | `(Component3D, float onTrans[, int duration])` | fade on/off |
| `TransparencyActionFloat` | `(Component3D[, int duration])` | fade by float payload |
| `TransparencyActionNoArg` | `(Component3D, float onTrans[, int duration])` | fade to a fixed value |

### App / migration / event actions
| Action | Purpose |
|---|---|
| `AppLaunchAction(String command, ClassLoader cl)` | launch another app (posts an `AppLaunchEvent`); the start menu's `ExecuteItemAction` extends it |
| `Component3DMigrationAction` | move a `Component3D` to another container |
| `Component3DGroupMigrationAction` | move a group of components |
| `GenericEventPostAction` | post an arbitrary `LgEvent` |

### Ad-hoc behaviour

Prefer an existing action when one fits; fall back to an anonymous
`ActionNoArg` for bespoke logic:

```java
c.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
    public void performAction(LgEventSource source) {
        model.open(path);          // touch the model, not Swing, here
    }
}));
```

---

## 9. Animation

### Class hierarchy
```
Animation (abstract, implements LgEventSource)
├── Component3DAnimation (abstract)   per-component transform/transparency/visible
│   └── (default scheduler-backed impl)
├── Frame3DAnimation (abstract)        enable/visible window transitions
│   └── NullFrame3DAnimation           no-op (instant) animation
AnimationScheduler                     drives running animations each frame
AnimationTarget / Component3DAnimationTarget / Frame3DAnimationTarget
AnimationGroup                         composite animations
```

### `Animation` base API
`setTarget(AnimationTarget)`, `getTarget()`, `setRunning(boolean)`,
`isRunning()`, `initialize()`, `destroy()`,
`setAnimationFinishedEvent(Class)` (post an event when the animation ends),
abstract `doAnimation()` / `getAnimationParameters()` (`TRANSPARENCY`,
`TRANSFORM`).

### `Component3DAnimation`
Defines the animated counterparts of every `Component3D` transform:
`changeTransparency(float, int)`, `changeTranslation(x,y,z,int)`,
`changeRotationAngle(float,int)`, `changeRotationAxis(x,y,z,int)`,
`changeScale(float,int)`, `changeScale(x,y,z,int)`, `changeVisible(boolean,int)`,
plus `get*`/`getFinal*` queries and `getDefault*Duration()` accessors and
`copyStatusTo(Component3DAnimation)`.

You rarely subclass these directly: `Component3D.change*(…, duration)` uses the
installed animation (or a default). Install a custom one with
`component.setAnimation(...)` / `frame.setAnimation(...)` to change the easing
or to disable animation entirely (`NullFrame3DAnimation`).

---

## 10. Cursors

`Cursor3D` gives hover affordance. Set one on every interactive `Component3D`
(`setCursor(...)`). Available constants (verified):

`NULL_CURSOR`, `DEFAULT_CURSOR`, `MEDIUM_CURSOR`, `SMALL_CURSOR`,
`SMALL_MOVE_CURSOR`, `MOVE_CURSOR`, `N_RESIZE_CURSOR`, `S_RESIZE_CURSOR`,
`E_RESIZE_CURSOR`, `W_RESIZE_CURSOR`, `NE_RESIZE_CURSOR`, `NW_RESIZE_CURSOR`,
`SE_RESIZE_CURSOR`, `SW_RESIZE_CURSOR`, `MOVE_CURSOR_WL`,
`SE_RESIZE_CURSOR_WL`, `MOVE_Z_CURSOR_WL`, `ROTATE_Y_CURSOR_WL`.

Convention: `SMALL_CURSOR` for buttons, a `*_RESIZE_CURSOR` for drag handles,
`MOVE_CURSOR*` for draggable bodies, `ROTATE_Y_CURSOR_WL` for spin handles.

---

## 11. Transparency, depth and draw order

Two independent mechanisms decide what blends in front:

1. **Within a subtree — `TransparencyOrderedGroup`.** `Component3D` only
   auto-sorts translucent children when a transparency *animation* is installed.
   For static translucent UI, group children in a
   `TransparencyOrderedGroup` (`org.jdesktop.lg3d.sg.utils.transparency`) **and**
   add them back-to-front as a second guarantee (rule #4).

2. **Between subtrees — eye-distance sort.** Java 3D sorts transparent shapes by
   the distance from the eye (`Toolkit3D.getEyePositionInVworld`) to each shape's
   **bounding-sphere centre**, *not* by Z. Consequences:
   - An off-axis overlay (corner popup) can be *farther* from the eye than an
     on-axis full-width window even when its Z is nearer; lifting it by a small
     Z margin does **not** bring it in front of a maximized window.
   - To force an overlay in front of every window, lift it a **fraction of the
     eye distance** (`frontZ = eye.z * 0.4`) and compensate the apparent pose:
     multiply world X/Y and node scale by `r = (eye.z - zNew)/(eye.z - zRef)`.
     See `StartMenuModel.compensatedFrontPose` / `FRONT_WORLD_Z_FRACTION`.

Use small positive `z` offsets (e.g. `0.002f`) to layer foreground elements
above the backdrop and avoid z-fighting. Verify any occlusion claim with a
framebuffer capture over a maximized window (§20) — numeric Z alone is
repeatedly misleading here.

---

## 12. Textures and the live-graph rule

Under Jogamp Java 3D 1.7.2, calling `Appearance.setTexture(texture)` on a
**live** scene graph triggers `TextureRetained.setLive`, which dereferences the
texture's `ImageComponent2D` image data. If that `ImageComponent2D` was created
empty and not yet `.set(image)`d, `setLive` throws a `NullPointerException`
(rule #2 — the cause of most "the panel/text/image is invisible" bugs).

**Upload the pixels *before* attaching the texture:**

```java
// 1. create the image component
ImageComponent2D ic = new ImageComponent2D(
        ImageComponent2D.FORMAT_RGBA, w, h, /*byReference*/ false, /*yUp*/ true);
ic.setCapability(ImageComponent2D.ALLOW_IMAGE_WRITE);

// 2. paint and UPLOAD the pixels
Graphics2D g = bufferedImage.createGraphics();
try { /* draw */ } finally { g.dispose(); }
ic.set(bufferedImage);

// 3. only now build + attach the texture
Texture2D tex = new Texture2D(Texture2D.BASE_LEVEL, Texture2D.RGBA, w, h);
tex.setMinFilter(Texture2D.BASE_LEVEL_LINEAR);
tex.setMagFilter(Texture2D.BASE_LEVEL_LINEAR);
tex.setImage(0, ic);
appearance.setTexture(tex);          // safe: ic already has pixels
```

**In-place update.** Keep `ALLOW_IMAGE_WRITE` and call `ic.set(newImage)` again —
do **not** re-attach the texture. The texture is recreated only when its size
changes.

**Orientation.** For `ImageComponent2D(format, BufferedImage, byReference, yUp)`
the empirical `yUp` behaviour under Jogamp is the opposite of the javadoc for
glyph/text textures; `GlassyTextTextureGenerator` uses `byReference=false,
yUp=false` with a flipped texcoord `v`. When text renders blank or upside-down,
this is the knob to check.

**Loading from a file/URL.** `org.jdesktop.lg3d.sg.utils.image.TextureLoader`
loads an image into a `Texture`; `SimpleAppearance(URL imageUrl)` is the
one-liner for a textured quad. For a canvas you repaint often (Image Studio's
`ImageCanvas3D`), keep your own `ImageComponent2D` + `Texture2D` and update in
place.

---

## 13. Loading 3D models

`ModelLoader extends Component3D` loads a 3D model file into the scene graph:

```java
ModelLoader(String path, String filename, Class loaderClass)
ModelLoader(URL base, URL filename, Class loaderClass)
ModelLoader(String path, String filename, Class loaderClass, Matrix4f matrix)
// ...
loader.resize(Vector3f center, float radius);   // normalise into a bounding sphere
Object named = loader.getNamedObject("SomeNode");
```

`loaderClass` selects the format loader (e.g. the in-tree `J3fLoader` for `.j3f`,
or an OBJ loader). Because `ModelLoader` **is** a `Component3D`, add it straight
to a `Container3D`/`Frame3D` and transform/animate it like any component. Use
`resize(...)` to fit an arbitrarily-scaled model into your layout, and
`getNamedObject(...)` to grab a sub-node for animation.

> The in-tree replacements (`J3fLoader`, `Math3D`, the traverser,
> `TransparencyOrderedGroup`) live in `lg3d-core/src/contrib/java` and stand in
> for the dropped `j3d-contrib-utils` / `satin` jars. Compatibility shims let
> pre-existing `.j3f` files deserialize under Jogamp.

---

## 14. `Toolkit3D`: metrics and coordinate conversion

`Toolkit3D.getToolkit3D()` is the singleton bridge between native pixel space
and 3D "physical" units.

| Member | Purpose |
|---|---|
| `getScreenWidth()` / `getScreenHeight()` | screen size in **3D physical units** |
| `getCanvasWidth()` / `getCanvasHeight()` | canvas size in **native pixels** |
| `widthNativeToPhysical(int)` / `heightNativeToPhysical(int)` | px → 3D units (what `SwingNode` uses to size its quad) |
| `widthPhysicalToNative(float)` / `heightPhysicalToNative(float)` | 3D units → px |
| `getEyePositionInVworld(Point3f ret)` | eye position (for depth/overlay math, §11) |
| `getFieldOfView([float width])` | the view frustum FOV |

**Layout convention:** origin at the **window centre**, `+x` right, `+y` up,
`+z` toward the viewer. Derive every margin/padding as a fraction of `W`/`H` so
the layout scales. From `ImageStudioFrame3D`:

```java
Toolkit3D tk = Toolkit3D.getToolkit3D();
float usableH = tk.getScreenHeight() - Taskbar.getReservedBottomHeight();
float H = usableH * 0.70f;
float W = H * tk.getScreenWidth() / usableH;   // match the screen aspect so
setPreferredSize(new Vector3f(W, H, 0.01f));    // maximize fills the viewport

float pad       = Math.min(W, H) * 0.022f;
float topMargin = H * 0.075f;                   // clear of the decoration buttons
float toolbarW  = W * 0.235f;
float xLeft     = -W * 0.5f + pad;
float xRight    =  W * 0.5f - pad;
```

Subtract `Taskbar.getReservedBottomHeight()` so the window sits above the
taskbar reserve rather than under it.

---

## 15. Scene-graph traversal utilities

`org.jdesktop.lg3d.sg.utils.traverser` provides a `TreeScan` depth-first walker
plus processor callbacks — the tool for bulk edits across a subtree (e.g.
re-tint every shape, toggle texturing/culling across a loaded model):

| Class | Role |
|---|---|
| `TreeScan` | traversal driver: `TreeScan.findNode(Node root, Class nodeClass, ProcessNodeInterface processor, boolean onlyEnabledSwitchChildren, boolean sharedGroupsOnce)` |
| `ProcessNodeInterface` | `boolean processNode(Node)` — the visit callback |
| `NodeChangeProcessor` | abstract `ProcessNodeInterface`; override `changeNode(Node)` |
| `AppearanceChangeProcessor` | abstract `ProcessNodeInterface`; override `changeAppearance(Shape3D, Appearance)` — fires for every `Shape3D` visited |
| `ChangeMaterial` | static `setLightingEnable(Node root, ...)` bulk material edit |
| `ChangeTextureAttributes` | static `setTextureEnable(Node root, ...)` |
| `ChangePolygonAttributes` | static `setPolygonMode(...)` / `setCullFace(...)` |

The `Change*` classes are convenience wrappers: each static method walks the
tree with an internal `AppearanceChangeProcessor`. Example — enable texturing
across a whole loaded model:

```java
ChangeTextureAttributes.setTextureEnable(modelLoader, true);
```

For a custom bulk edit, drive `TreeScan.findNode(...)` with your own
`AppearanceChangeProcessor` (override `changeAppearance(Shape3D, Appearance)`).

These are the in-tree replacements for the dropped `j3d-contrib-utils`
traverser; keep them behaviour-compatible.

---

## 16. The observable-model (MVC) pattern

Keep application state in a plain observable POJO so it is headless-testable and
free of Java 3D. The reference `EditorModel`:

```java
public class EditorModel {
    public interface Listener { void modelChanged(EditorModel model); }
    private final List<Listener> listeners = new ArrayList<>();

    public void addListener(Listener l)    { if (l != null && !listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void fire() {                       // copy-iterate: safe against
        for (Listener l : new ArrayList<>(listeners)) l.modelChanged(this);
    }                                           // listeners that unsubscribe
    // ...state mutators call fire()...
}
```

Views subscribe and repaint on the callback:

```java
public class ImageStudioFrame3D extends Frame3D implements EditorModel.Listener {
    public void modelChanged(EditorModel m) {
        if (statusText != null) statusText.setText(m.getStatus());  // in-place update
    }
}
```

Rules:
- Mutate **texture-backed widgets in place** (`GlassyText2D.setText`,
  `ImageComponent2D.set`) rather than rebuilding geometry on a live graph.
- `fire()` copy-iterates so a listener may unsubscribe during notification.
- Model callbacks run wherever the mutator was called — if that is an lg3d
  listener thread and you touch Swing, hop to the EDT (rule #5).

---

## 17. Embedding Swing with `SwingNode`

When you need real Swing widgets (text input, `JList`, `JScrollPane`, `JTable`,
existing panels), host them on a `SwingNode` — it renders the panel offscreen
into an RGBA `Texture2D` on a quad and forwards mouse, wheel and keyboard input.
`SwingNode extends Component3D`, so it drops straight into a `Frame3D`.

```java
SwingNode node = new SwingNode();
node.setJPanel(new MySwingPanel());
node.setTranslation(0.1f, 0.08f, 0.04f);
frame.addChild(node);
```

Advanced gotchas (full contract in [`swingnode.md`](./swingnode.md)):
- The default renderer fills the frame with **one opaque, pickable,
  non-propagatable quad** that swallows the BUTTON1 drag the frame mover needs
  and covers the min/max/close buttons. Reserve a **title strip**: shift the node
  down by `-titleBarH/2`, add a pickable **and propagatable** title bar as the
  gesture handle, and publish `Frame3DWindowDecoration.TITLE_BAR_HEIGHT_PROPERTY`
  before `changeEnabled`. `TitledSwingWindow` (lg3d-apps) is the reference.
- Maximize by **native resize** (`HostedWindowResizer` + `SwingNode.setHostedSize`),
  not uniform scale (scaling blurs the fixed-resolution texture).
- Typing: the offscreen host frame is never shown, so emulate click-to-focus and
  deliver keys via `KeyboardFocusManager.redispatchEvent(target, evt)` on the EDT.
- Call `dispose()` when discarded; use `setJPanel`/`addInputHandlers`, never the
  deprecated `setPanel`/`addMouseHandlers`.

---

## 18. Worked example: a custom `Slider3D`

A reusable control is a `Component3D` that builds its own geometry, exposes a
small setter API, and translates a press/drag into a value change. The reference
`Slider3D` uses a `MousePressedEventAdapter` (jump-to-click) plus a
`MouseDraggedEventAdapter` (drag), both delivering a **local** point via
`ActionBooleanFloat3` / `ActionFloat3`. The essential structure:

```java
public class Slider3D extends Component3D {
    private final float width, height;
    private final GlassyPanel track;
    private final GlassyPanel knob;
    private float value;                       // 0..1

    public Slider3D(float width, float height) {
        this.width = width; this.height = height;
        TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
        track = Ui3D.panel(width, height * 0.34f, 0.002f, TRACK);
        knob  = Ui3D.panel(height * 0.6f, height * 0.6f, 0.003f, KNOB);
        tog.addChild(track);
        tog.addChild(knob);
        addChild(tog);

        setCursor(Cursor3D.SMALL_CURSOR);
        // Drag delivers the local point (x in [-w/2, w/2]); map it onto 0..1.
        addListener(new MouseDraggedEventAdapter(new ActionFloat3() {
            public void performAction(LgEventSource src, float x, float y, float z) {
                setValueFromLocalX(x);
            }
        }));
        // Press jumps straight to the clicked position.
        addListener(new MousePressedEventAdapter(new ActionBooleanFloat3() {
            public void performAction(LgEventSource src, boolean pressed,
                                      float x, float y, float z) {
                if (pressed) setValueFromLocalX(x);
            }
        }));
    }

    private void setValueFromLocalX(float x) {
        setValue(Math.max(0f, Math.min(1f, x / width + 0.5f)));
    }

    public void setValue(float v) {
        this.value = v;
        float kx = (v - 0.5f) * width;         // reposition without rebuilding
        knob.setTranslation(kx, 0f, 0.001f);   // (live-graph safe)
        // ...push value into the model / notify listeners...
    }
}
```

Points illustrated: geometry owned by the component; `TransparencyOrderedGroup`
for blending; press + drag adapters feeding positional `ActionBooleanFloat3` /
`ActionFloat3`; **in-place** knob repositioning (`setTranslation`) rather than
remove/re-add; and a single `setValue` sink that both the interaction and the
model funnel through. The frame positions the whole slider with
`slider.setTranslation(cx, cy, z)`.

---

## 19. Registering in the Start Menu

Apps are discovered from `*.lgcfg` descriptors (a `java.beans.XMLDecoder`
`StartMenuItemConfig`). Discovery scans `config/demo` and `config/incubator` on
the runtime classpath.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<java version="1.5.0" class="java.beans.XMLDecoder">
 <object class="org.jdesktop.lg3d.scenemanager.utils.startmenu.StartMenuItemConfig">
  <void property="command">
   <string>java org.jdesktop.lg3d.apps.hello.HelloApp</string>
  </void>
  <void property="desc"><string>My advanced native 3D app</string></void>
  <void property="displayResourceType"><string>ICON</string></void>
  <void property="displayResourceUrlName">
   <string>resource:///resources/images/icon/defaultapp.png</string>
  </void>
  <void property="menuGroup"><string>Utilities</string></void>
  <void property="name"><string>Hello 3D</string></void>
 </object>
</java>
```

- `command = java <fully.qualified.Main>` runs **in-JVM** on the desktop
  classpath (fastest; use for apps bundled in `lg3d-apps`/`lg3d-incubator`).
- `displayResourceUrlName = resource:///resources/...` resolves through the
  runtime-resources classpath prefix (see the root `AGENTS.md`).
- Place the descriptor in `lg3d-apps/src/config/` (bundled to `config/demo`),
  following the Image Studio / Widget Gallery precedent.
- Generate the icon bitmap with `lg3d-art/tools/GenerateAppIcons.java` and ship
  it via `lg3d-core` runtime resources.

To also expose the app as an MDI window in the **2D/Swing desktop**, register a
host-shim panel in `Desktop2DAppRegistry`'s panel map (see that class and its
test) — this is orthogonal to the 3D start-menu descriptor.

---

## 20. Build, run and verify

```bash
./gradlew build                       # compile + jar + headless tests
./gradlew :lg3d-core:compileJava      # fast loop
./gradlew :lg3d-core:run              # launch desktop in dev mode (needs DISPLAY)
./run-lg3d.sh                         # basic launch
./run-lg3d.sh -b                      # with the 3D background model
./gradlew :lg3d-core:runtimeResources # assemble icons/wallpapers/configs
```

Your app's classes must be on the `:lg3d-core:run` classpath — bundle the app in
`lg3d-apps` or `lg3d-incubator` and put its `.lgcfg` in `lg3d-apps/src/config/`.

**Verifying UI at runtime.** External X tools (`import`, `scrot`, AWT `Robot`,
`gnome-screenshot`) return black or hang under GNOME/Wayland + Xwayland. The
reliable capture is lg3d's **internal screencapture** →
`lg3d-core/lgscreen-0-0.png`. For anything needing the live scene graph, use the
**in-JVM probe**:

```bash
./run-lg3d.sh --swing-app-cp <ABS classes dir> -s <fqcn>
```

Use **absolute** paths for `--swing-app-cp` (entries resolve relative to the
`lg3d-core` project dir). Capture inside the probe with
`AppConnectorPrivate.getAppConnector().postEvent(new ScreenCaptureEvent(dir), null)`
and read `dir/lgscreen-0-N.png`. Read the desktop log for `EventProcessor`
warnings before concluding an interaction is broken. `lgscreen-*.png` are
runtime artifacts — never commit them. Full procedure: the *Verifying UI
changes* section of [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md).

---

## 21. Complete component index

### `org.jdesktop.lg3d.wg` (windowing)
`Frame3D`, `Container3D`, `Component3D`, `LayoutManager3D`, `SwingNode`,
`SwingNodeRenderer`, `Cursor3D`, `Toolkit3D`, `Thumbnail`, `ModelLoader`,
`Animation`, `AnimationGroup`, `AnimationScheduler`, `AnimationTarget`,
`Component3DAnimation`, `Component3DAnimationTarget`, `Frame3DAnimation`,
`Frame3DAnimationTarget`, `NullFrame3DAnimation`, `HostedWindowResizer`,
`TransparencyManager`, `WidgetManager`, `Java3DGraph`, `Lapp`, `Tapp`.

### `org.jdesktop.lg3d.utils.shape` (widgets)
Panels/bodies: `GlassyPanel`, `GlassyBentPanel`, `GlassyCurvedPanel`,
`GlassyDisc`, `GlassyRingPanel`, `FuzzyEdgePanel`, `FuzzyEdgePanelImpl`,
`OrientedFuzzyEdgePanel`, `ImagePanel`.
Solids/primitives: `Box`, `Sphere`, `Cone`, `Cylinder`, `Disc`, `ColorCube`,
`Primitive`, `Quadrics`, `Sparkle`.
Lines/shadows: `BlurLine`, `ExtendableLine`, `RectShadow`, `RingShadow`,
`RoundShadow`, `ShadowAppearance`.
Text: `GlassyText2D`, `GlassyBentText2D`, `Text2D`, `GlassyTextTextureGenerator`.
Appearance/helpers: `SimpleAppearance`, `SimpleShaderAppearance`,
`OriginTranslation`, `GeomBuffer`, `PickableRegion`.

### `org.jdesktop.lg3d.utils.action` (actions)
Interfaces: `Action`, `ActionNoArg`, `ActionBoolean`, `ActionBooleanInt`,
`ActionBooleanFloat`, `ActionBooleanFloat2`, `ActionBooleanFloat3`, `ActionInt`,
`ActionFloat`, `ActionFloat2`, `ActionFloat3`, `ActionChar`,
`ActionComponent3D`.
Concrete: `AppearanceChangeAction`, `ScaleActionBoolean`, `ScaleActionFloat`,
`RotateActionBoolean`, `RotateActionFloat`, `TranslateActionBoolean`,
`TranslateActionFloat`, `TransparencyActionBoolean`, `TransparencyActionFloat`,
`TransparencyActionNoArg`, `AppLaunchAction`, `Component3DMigrationAction`,
`Component3DGroupMigrationAction`, `GenericEventPostAction`.

### `org.jdesktop.lg3d.utils.eventadapter` (adapters)
`EventAdapter`, `GenericEventAdapter`, `EventAdapterUtil`,
`MouseClickedEventAdapter`, `MousePressedEventAdapter`,
`MouseEnteredEventAdapter`, `MouseDraggedEventAdapter`,
`MouseMotionEventAdapter`, `MouseMovedEventAdapter`, `MouseWheelEventAdapter`,
`MouseHoverEventAdapter`, `MouseDragDistanceAdapter`, `KeyPressedEventAdapter`,
`KeyTypedEventAdapter`, `Component3DToFrontEventAdapter`,
`Component3DToBackEventAdapter`, `Component3DHighlightEventAdapter`,
`Component3DParkedEventAdapter`, `Component3DVisualAppearanceEventAdapter`,
`Component3DManualMoveEventAdapter`, `Component3DManualResizeEventAdapter`.

### `org.jdesktop.lg3d.sg.utils` (scene-graph utilities)
`transparency.TransparencyOrderedGroup`; `image.TextureLoader`;
`traverser.{TreeScan, NodeChangeProcessor, ProcessNodeInterface,
AppearanceChangeProcessor, ChangeMaterial, ChangeTextureAttributes,
ChangePolygonAttributes}`.

### `org.jogamp.vecmath` (math — **never** `javax.vecmath`)
`Vector3f`, `Point3f`, `Color3f`, `Color4f`, `Matrix4f`, `Transform3D`.

---

## 22. Advanced checklist and pitfalls

**Checklist for an advanced native app**

- [ ] MVC split: plain observable model, `Frame3D` view, `Ui3D` widget factory,
      one `Component3D` subclass per reusable control.
- [ ] Entry point calls `changeEnabled(true)` + `changeVisible(true)`; never
      `System.exit`.
- [ ] Jogamp packages only (`org.jogamp.vecmath`, `org.jdesktop.lg3d.sg`);
      never `javax.media.j3d` / `javax.vecmath`.
- [ ] Layout derived from `Toolkit3D` metrics (minus `Taskbar` reserve);
      top-right left clear for the decoration.
- [ ] Raw nodes wrapped in `Component3D` (+ `TransparencyOrderedGroup`);
      children added back-to-front.
- [ ] Texture pixels uploaded **before** `Appearance.setTexture`; live updates
      via `ImageComponent2D.set(...)` in place.
- [ ] Interactions use event adapters + existing actions; Swing work hopped to
      the EDT; every interactive component has a `Cursor3D`.
- [ ] `.lgcfg` descriptor added and discoverable; icon generated and shipped.
- [ ] `./gradlew build` passes; runtime verified via in-JVM probe +
      `lgscreen-0-N.png` and the desktop log.
- [ ] Headless JUnit tests for model/logic; `CHANGELOG.md` bullet added.

**Pitfalls that repeatedly bite**

| Symptom | Cause | Fix |
|---|---|---|
| Panel/text/image invisible; NPE in log | `setTexture` on an empty `ImageComponent2D` (live graph) | upload pixels first (§12) |
| "Click does nothing" | exception swallowed into `EventProcessor` warning | read the desktop log first (§7) |
| Translucent children blend wrong | no `TransparencyOrderedGroup` / wrong add order | group + add back-to-front (§11) |
| Overlay hidden behind a maximized window | eye-distance sort, off-axis bounding sphere | lift by a fraction of eye distance + compensate (§11) |
| `RestrictedAccessException` / `MultipleParentException` | remove/re-add or re-parent a live non-`BranchGroup` | resize in place via `setSize`/`setTranslation` (§3) |
| `SwingNode` window cannot be moved / buttons covered | default opaque quad swallows drag | reserve a propagatable title strip (§17) |
| Text renders blank/upside-down | `yUp`/texcoord orientation | check `GlassyTextTextureGenerator` knobs (§6, §12) |
| Typing into a `SwingNode` field does nothing | offscreen frame has no focus owner | `KeyboardFocusManager.redispatchEvent` on the EDT (§17) |
| Window sits under the taskbar | ignored `Taskbar.getReservedBottomHeight()` | subtract the reserve from `H` (§14) |
