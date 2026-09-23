# LG3D Native Apps

A guide to building **native 3D applications** for Project Looking Glass — apps
that live *inside* the scene graph as `Frame3D` / `Component3D` objects, drawn
with the glassy 3D widget vocabulary rather than as ordinary Swing windows.

> Scope: the **pure-3D path** (`org.jdesktop.lg3d.wg.Frame3D` managed by the
> scene manager). This is the only window path available in dev mode
> (`lg.fws.mode=dev`). The heavyweight native-X11 path
> (`NativeWindow3D` + `X11WindowManager`) is excluded from this build; do not
> confuse the two.

For embedding real Swing UI into the scene graph, see
[`swingnode.md`](./swingnode.md). For the rules an AI agent must follow when
touching UI, see [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md).

---

## 1. Anatomy of a native app

A native app is a small tree of scene-graph nodes:

```
Frame3D                       top-level window (a Container3D)
└── Component3D               an interactive/layout unit (accepts listeners)
    └── TransparencyOrderedGroup   blends translucent children back-to-front
        ├── GlassyPanel       translucent body
        ├── GlassyText2D      text label (no PNG asset needed)
        └── ...               buttons, sliders, quads, boxes
```

Key packages:

| Package | Role |
|---|---|
| `org.jdesktop.lg3d.wg` | Windowing toolkit: `Frame3D`, `Component3D`, `Container3D`, `SwingNode`, `Cursor3D`, `Toolkit3D` |
| `org.jdesktop.lg3d.sg` | Scene-graph facade over Jogamp Java 3D (`Appearance`, `Shape3D`, `Texture2D`, `ImageComponent2D`, …) |
| `org.jdesktop.lg3d.utils.shape` | Glassy widgets: `GlassyPanel`, `GlassyText2D`, `SimpleAppearance`, `OriginTranslation`, `Box`, `GlassyBentPanel`, … |
| `org.jdesktop.lg3d.utils.action` | Declarative behaviours: `ActionNoArg`, `AppearanceChangeAction`, `ScaleActionBoolean`, … |
| `org.jdesktop.lg3d.utils.eventadapter` | Event → action bridges: `MouseClickedEventAdapter`, `MouseEnteredEventAdapter`, … |
| `org.jogamp.vecmath` | `Vector3f`, `Color3f`, `Color4f`, `Point3f` (Jogamp, **not** `javax.vecmath`) |

### Minimal example

```java
package org.jdesktop.lg3d.apps.hello;

import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jogamp.vecmath.Vector3f;

public class HelloApp {
    public static void main(String[] args) {
        Frame3D frame = new Frame3D();
        frame.setPreferredSize(new Vector3f(0.2f, 0.15f, 0.01f));

        Component3D body = new Component3D();
        body.addChild(new GlassyPanel(0.2f, 0.15f, 0.01f,
                new SimpleAppearance(0.2f, 0.4f, 0.8f, 0.7f)));
        frame.addChild(body);

        frame.changeEnabled(true);   // register with the app connector / scene manager
        frame.changeVisible(true);   // show it
    }
}
```

`changeEnabled(true)` + `changeVisible(true)` is the canonical "make the window
appear" pair used by `Lg3dHelp`, `FileManager`, and `ImageStudioApp`.

---

## 2. Frame3D vs Component3D vs Container3D

- **`Frame3D`** — the top-level window. It is a `Container3D`, so it only
  accepts **`Component3D`** children. It has a `Thumbnail`, an enable/visible
  animation (`Frame3DAnimation`), and (unless it sets
  `Frame3DWindowDecoration.OPT_OUT_PROPERTY`) the standard decoration:
  minimize/maximize/close buttons, right-click flip, middle-drag spin. Leave the
  top-right corner clear of your own controls.
- **`Container3D`** — a layout container (`Frame3D`, `TransparentContainer3D`,
  …). Children must be `Component3D`.
- **`Component3D`** — the interactive unit. This is what you attach event
  listeners and cursors to, and what you animate (scale/rotate/translate/
  transparency). Wrap any raw `org.jdesktop.lg3d.sg.Node` in a `Component3D`
  before adding it to a container.

**Wrapping raw nodes** (the idiom `Lg3dHelp` and Image Studio use):

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

## 3. Sizing and layout

There is no CSS/Box-Layout in the pure-3D path. You position children with
explicit 3D transforms, origin at the **window centre**, `+x` right, `+y` up.

- Get the screen size in physical (3D) units from `Toolkit3D`:

  ```java
  Toolkit3D tk = Toolkit3D.getToolkit3D();
  float W = tk.getScreenWidth()  * 0.62f;   // e.g. 62% of the screen
  float H = tk.getScreenHeight() * 0.66f;
  frame.setPreferredSize(new Vector3f(W, H, 0.01f));
  ```

- Convert between native pixel sizes and 3D units with
  `tk.widthNativeToPhysical(px)` / `heightNativeToPhysical(px)` (used by
  `SwingNode` to size its quad from a JPanel's pixel size).

- Place a node with `setTranslation(x, y, z)` on the `Component3D`, or wrap it
  in `OriginTranslation(node, new Vector3f(x, y, z))` to move its local origin.

- Derive all margins/paddings from `W`/`H` (as Image Studio does) so the layout
  scales with the window instead of using hard-coded constants.

> `GlassyText2D` geometry grows **upward** (and, for CENTER/RIGHT alignment,
> about its origin). To vertically centre a label on a point `cy`, offset it by
> `-height * 0.5f`.

---

## 4. The glassy widget vocabulary

Build UIs from these rather than inventing new geometry or PNG assets:

- **`GlassyPanel(w, h, depth, appearance)`** — translucent rounded body. The
  tint comes from the appearance's **Material diffuse** colour; supply alpha via
  a `Color4f`-based `SimpleAppearance`.
- **`GlassyText2D(text, maxWidth, height, color4f, lightDirection, alignment)`**
  — anti-aliased text rendered into a texture at runtime. No image assets.
  `Alignment` ∈ {LEFT, CENTER, RIGHT}; `LightDirection` ∈ {TOP_LEFT, …}.
- **`SimpleAppearance(r, g, b, a, flags)`** — convenience appearance; pass
  `SimpleAppearance.DISABLE_CULLING` for flat quads so they render from both
  sides.
- **`Box`, `GlassyBentPanel`, `GlassyCurvedPanel`, `GlassyDisc`,
  `GlassyRingPanel`** — richer shapes when you need them.

A reusable **push button** (hover highlight + click) combines a panel, a label,
and two `MouseEnteredEventAdapter`s plus a `MouseClickedEventAdapter`:

```java
Component3D c = new Component3D();
TransparencyOrderedGroup tog = new TransparencyOrderedGroup();
GlassyPanel bg = new GlassyPanel(w, h, 0.002f, appearance(offColor));
tog.addChild(bg);
tog.addChild(centeredLabel);
c.addChild(tog);

c.addListener(new MouseEnteredEventAdapter(
        new AppearanceChangeAction(bg, appearance(onColor))));   // hover tint
c.addListener(new MouseEnteredEventAdapter(
        new ScaleActionBoolean(c, 1.06f, 120)));                 // hover grow
c.addListener(new MouseClickedEventAdapter(onClickAction));      // click
c.setCursor(Cursor3D.SMALL_CURSOR);
```

---

## 5. Events and actions

lg3d uses a **listener + declarative action** model, not Swing's
`ActionListener`.

1. An **event adapter** (`org.jdesktop.lg3d.utils.eventadapter.*`) listens for a
   3D event class and fires an **action**.
2. An **action** (`org.jdesktop.lg3d.utils.action.*`) is a reusable behaviour.

Common adapters: `MouseClickedEventAdapter`, `MousePressedEventAdapter`,
`MouseEnteredEventAdapter`, `MouseDraggedEventAdapter`, `MouseMotionEventAdapter`,
`MouseWheelEventAdapter`, `MouseHoverEventAdapter`, `KeyPressedEventAdapter`,
`KeyTypedEventAdapter`, `Component3DHighlightEventAdapter`,
`Component3DToFrontEventAdapter`.

Common actions: `ActionNoArg`, `ActionBoolean`, `ActionInt`, `ActionFloat`,
`AppearanceChangeAction`, `ScaleActionBoolean`/`ScaleActionFloat`,
`RotateActionBoolean`, `TranslateActionBoolean`, `TransparencyAction*`,
`AppLaunchAction`, `Component3DMigrationAction`.

Ad-hoc behaviour via `ActionNoArg`:

```java
c.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
    public void performAction(LgEventSource source) {
        model.open(path);
    }
}));
```

**Threading:** listeners run on the lg3d **EventProcessor / J3dThread**, *not*
the Swing EDT. An exception thrown from `performAction` surfaces in the desktop
log as:

```
WARNING: Exception caught in the EventProcessor
```

That log line (and the stack under it) is the single most useful diagnostic when
"a click does nothing". Always check it first.

To touch Swing from a listener, hop to the EDT with
`SwingUtilities.invokeLater(...)`.

---

## 6. Transparency ordering

`Component3D` only sorts translucent children automatically when a transparency
*animation* is installed. For static translucent UI, group children in a
**`TransparencyOrderedGroup`** (`org.jdesktop.lg3d.sg.utils.transparency`) so
they blend back-to-front, and also add them back-to-front as a second guarantee.
This is what `Ui3D.component(...)` does above.

---

## 7. Textures: the live-scene-graph rule (critical)

Under Jogamp Java 3D 1.7.2, calling `Appearance.setTexture(texture)` on a
**live** scene graph triggers `TextureRetained.setLive`, which dereferences the
texture's `ImageComponent2D` image data. If that `ImageComponent2D` was created
empty and not yet `.set(image)`d, `setLive` throws a `NullPointerException`.

**Rule: upload the pixels *before* attaching the texture.**

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

To update an already-attached texture in place, keep
`ALLOW_IMAGE_WRITE` and just call `ic.set(newImage)` again — do **not** re-attach
the texture.

**Orientation:** for `ImageComponent2D(format, BufferedImage, byReference, yUp)`,
the empirical `yUp` behaviour under Jogamp is the opposite of the javadoc for
glyph/text textures. `GlassyTextTextureGenerator` uses `byReference=false,
yUp=false` together with a flipped texcoord `v` mapping. When text renders
blank or upside-down, this is the knob to check.

---

## 8. Registering the app in the Start Menu

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
  <void property="desc"><string>My native 3D app</string></void>
  <void property="displayResourceType"><string>ICON</string></void>
  <void property="displayResourceUrlName">
   <string>resource:///resources/images/icon/defaultapp.png</string>
  </void>
  <void property="menuGroup"><string>Utilities</string></void>
  <void property="name"><string>Hello 3D</string></void>
 </object>
</java>
```

- `command` — `java <fully.qualified.Main>` runs **in-JVM** on the desktop's
  classpath (fastest; use this for apps bundled in `lg3d-apps` /
  `lg3d-incubator`).
- `displayResourceUrlName` — `resource:///resources/...` resolves through the
  runtime-resources classpath prefix (see the root `AGENTS.md`).
- Place the descriptor in `lg3d-apps/src/config/` (bundled to
  `config/demo`), following the Image Studio / Widget Gallery precedent.

---

## 9. Building and running

```bash
# Compile everything
./gradlew build

# Launch the desktop in dev mode (needs an X display; DISPLAY defaults to :0)
./gradlew :lg3d-core:run
./run-lg3d.sh            # basic launch
./run-lg3d.sh -b         # with the 3D background model
```

Your app's classes must be on the `:lg3d-core:run` classpath — bundle the app in
`lg3d-apps` or `lg3d-incubator`, and put its `.lgcfg` in
`lg3d-apps/src/config/`.

**Verifying UI at runtime.** X-client screenshot tools (`import`, `scrot`, AWT
`Robot`) return black or hang under GNOME/Wayland + Xwayland. The reliable
capture is lg3d's **internal screencapture**, which writes
`lg3d-core/lgscreen-0-0.png` (≈1920×1048, ~1:1 screen coords). Trigger it from
the desktop and inspect that file. Runtime errors go to the desktop log
(`EventProcessor` warnings); read the log before assuming a click "did nothing".

---

## 10. Checklist for a new native app

- [ ] Uses Jogamp packages (`org.jogamp.vecmath`, `org.jdesktop.lg3d.sg`), never
      `javax.media.j3d` / `javax.vecmath`.
- [ ] Top level is a `Frame3D`; raw nodes wrapped in `Component3D`
      (+ `TransparencyOrderedGroup`).
- [ ] Layout derived from `Toolkit3D` screen size; top-right left clear for
      decoration.
- [ ] Textures upload pixels **before** `Appearance.setTexture` on the live
      graph.
- [ ] Interactions use event adapters + actions; Swing work hopped to the EDT.
- [ ] `.lgcfg` descriptor added and discoverable.
- [ ] `./gradlew build` passes; runtime verified via `lgscreen-0-0.png` and the
      desktop log.

## Reference app

`org.jdesktop.lg3d.apps.imagestudio` (in `lg3d-incubator`) is the fullest
worked example: `ImageStudioApp` (entry point), `ImageStudioFrame3D` (layout),
`Ui3D` (widget factory + button idiom), `Toolbar3D` / `Slider3D` /
`FileStrip3D` / `Histogram3D` / `ImageCanvas3D` (custom `Component3D`s), and
`EditorModel` (observable model). Read `Ui3D` first for the glassy vocabulary.
