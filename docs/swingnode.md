# SwingNode — embedding Swing into the 3D scene graph

`org.jdesktop.lg3d.wg.SwingNode` renders a real Swing `JPanel` **offscreen**
into a `Texture2D` on a 3D quad, so ordinary Swing widgets (text fields, lists,
scroll panes, buttons) appear inside the pure-3D desktop and receive mouse,
mouse-wheel and keyboard input.

This is the JDK-21 replacement for the old custom AWT peer toolkit
(`lg3d-awt` / `lg.use3dtoolkit=true`), which cannot be installed on JDK 9+
because `Toolkit.getDefaultToolkit()` no longer honours the `awt.toolkit`
property. `SwingNode` composites Swing itself instead of relying on a custom
peer.

Sources:
[`SwingNode.java`](../lg3d-core/src/classes/org/jdesktop/lg3d/wg/SwingNode.java),
[`SwingNodeRenderer.java`](../lg3d-core/src/classes/org/jdesktop/lg3d/wg/SwingNodeRenderer.java).

---

## 1. Quick start

```java
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jogamp.vecmath.Vector3f;

SwingNode node = new SwingNode();
node.setJPanel(new MySwingPanel());     // any JPanel
node.setTranslation(0.1f, 0.08f, 0.04f);

Frame3D frame = new Frame3D();
frame.addChild(node);                   // SwingNode IS a Component3D
frame.setPreferredSize(new Vector3f(0.2f, 0.15f, 0.06f));
frame.changeEnabled(true);
frame.changeVisible(true);
```

`SwingNode extends Component3D`, so it can be added straight to a
`Frame3D`/`Container3D`, positioned with `setTranslation`, given a cursor, and
animated like any other component.

### Public API

| Member | Purpose |
|---|---|
| `SwingNode()` | default geometry (`DefaultSwingNodeRenderer`) |
| `SwingNode(SwingNodeRenderer)` | custom geometry/renderer |
| `setJPanel(JPanel)` | set (or replace) the hosted panel |
| `getJPanel()` | current panel |
| `setTransparency(float)` | 0.0 opaque … 1.0 transparent (default renderer only) |
| `getLocalWidth()` / `getLocalHeight()` | panel size in 3D units |
| `dispose()` | release the hidden frame + listeners; do not reuse afterwards |
| `setPanel(JPanel)` | **deprecated** alias for `setJPanel` |

---

## 2. How rendering works

`SwingNode` never maps a real on-screen window. It keeps a **hidden,
undecorated `SwingNodeJFrame`** purely as a displayable host for Swing layout
and input dispatch, and paints the panel into a texture itself:

1. `setJPanel(p)` sets the frame's content pane, `pack()`s it, computes the 3D
   size via `Toolkit3D.widthNativeToPhysical/heightNativeToPhysical`, and
   schedules a capture.
2. A shared **`RepaintManager`** (`SwingNodeRepaintManager`) watches every
   hosted panel; any repaint of the panel or a descendant marks its node dirty.
3. A single coalescing **`javax.swing.Timer`** (`CAPTURE_DELAY_MS = 30`) batches
   dirty nodes and calls `captureNow()` on the EDT.
4. `captureNow()` paints the panel into a **power-of-two** `TYPE_INT_ARGB`
   `BufferedImage`, uploads it with `imageComponent.set(...)` **before** the
   texture is bound, and hands the `Texture2D` to the renderer via
   `textureChanged(...)`.

### Texture details that matter

- The image is **RGBA**, not RGB. The default renderer samples it with
  `TextureAttributes.REPLACE`, so fragment alpha comes straight from the image;
  an alpha-less RGB texture would blend the quad away to (almost) nothing under
  Jogamp.
- `captureNow()` fills a fully **opaque backdrop** (the panel's own background)
  across the whole power-of-two image first — including the pow2 padding that
  linear filtering can touch at the edges — so `REPLACE` never punches a
  transparent hole and dark panels get no light fringe.
- Pixels are uploaded **before** the texture is attached (the live-graph
  `TextureRetained.setLive` rule; see
  [`lg3d-native-apps.md` §7](./lg3d-native-apps.md)).
- The texture is recreated only when the pow2 size changes; otherwise the
  existing `ImageComponent2D` is updated in place.

---

## 3. Input forwarding

`SwingNodeRenderer.addInputHandlers(Component3D)` registers lg3d listeners that
translate 3D events into AWT/Swing events on the hidden frame:

| lg3d event | Forwarded as | Notes |
|---|---|---|
| `MouseButtonEvent3D` | `MouseEvent` (click/press/release) | position mapped into panel coords |
| `MouseMotionEvent3D` | `MouseEvent` (move) | |
| `MouseDraggedEvent3D` | `MouseEvent` (drag) | |
| `MouseWheelEvent3D` | real `MouseWheelEvent` | rotation/scrollType/amount preserved |
| `MouseEnteredEvent3D` | enter/exit + `WINDOW_GAINED/LOST_FOCUS`, `WINDOW_ACTIVATED/DEACTIVATED` | drives focus in/out of the panel |
| `KeyEvent3D` | `KeyEvent` to the frame's focus owner | makes `JTextField`/`JTextArea` editable |

- The 3D intersection point is converted to panel pixel coordinates by
  `calcPositionInPanel(...)` (perpendicular-distance math against the quad's
  world-transformed corners).
- **Keyboard** events are only delivered when the `SwingNode` currently has
  lg3d focus, so no extra guard is needed.
- `addMouseHandlers(...)` is a **deprecated shim** that delegates to
  `addInputHandlers(...)`; new code should not call it.

This forwarding is what makes editable widgets (`StickyNote`'s `JTextArea`) and
scrollable widgets (`JScrollPane`, `JList`) actually work inside the 3D desktop.

---

## 4. Custom geometry: subclass `SwingNodeRenderer`

`SwingNodeRenderer` is an abstract `Group` that owns the geometry the Swing
texture is drawn onto. Subclass it to render Swing on your own shape:

```java
public abstract class SwingNodeRenderer extends Group
        implements SwingNodeJFrame.TextureChangedListener {
    protected float width3D, height3D;
    protected JPanel panel;
    public abstract void textureChanged(Texture2D texture);   // bind + fit geometry
    public void addInputHandlers(Component3D comp) { ... }    // provided
}
```

Implement `textureChanged(Texture2D)` to call `appearance.setTexture(texture)`
and re-fit your geometry to `width3D`/`height3D` (the default renderer uses a
`NativeWindowFuzzyEdgePanel` and calls `body.setSize(...)` with the pow2/panel
size ratio). Pass your renderer to `new SwingNode(myRenderer)`.

> `setTransparency(float)` only affects the **default** renderer. A custom
> renderer that wants dynamic transparency must expose its own setter and keep
> `TransparencyAttributes.ALLOW_VALUE_WRITE` so it can change after the graph is
> live.

The default renderer uses `TransparencyAttributes.BLENDED` (alpha blending) with
`BLEND_SRC_ALPHA` / `BLEND_ONE_MINUS_SRC_ALPHA` — the same configuration
`SimpleAppearance` uses for native windows, proven to render an image quad
opaque under Jogamp Java 3D 1.7.2. Default transparency is `0.0f` (opaque);
widgets opt into ~`0.12f`.

---

## 5. Lifecycle and cleanup

- Call **`dispose()`** when the node is no longer needed. It removes the resize
  listener from the current panel, unregisters it from the shared capture
  machinery, and disposes the hidden `JFrame`. Do not reuse a disposed node.
- `setJPanel(...)` can be called repeatedly: it removes the previous panel's
  resize listener first, so repeated calls do not leak listeners.
- The capture `Timer` and `RepaintManager` are **process-wide singletons**
  shared by all `SwingNode`s; one failing widget is caught so it cannot stall
  the batch.

---

## 6. Debugging

- Set `-Dlg3d.swingnode.debugHierarchy=true` to dump the Swing hierarchy to
  stdout from `setJPanel`. (The pre-port code always dumped *and* repainted
  every component orange/red, corrupting user panels; that mutation is gone —
  the dump is now name-only and off by default.)
- If the quad is blank: confirm the panel has a non-zero size (a zero-size panel
  falls back to `getPreferredSize()`), that the texture is RGBA, and that pixels
  are uploaded before `setTexture`.
- If input does nothing: check the node is `isVisible()`, and that the relevant
  `*Event3D` class is in the adapter's `getTargetEventClasses()`.
- Runtime exceptions surface in the desktop log as
  `WARNING: Exception caught in the EventProcessor`.

---

## 7. When to use SwingNode vs pure-3D widgets

| Use **SwingNode** when… | Use **pure-3D** (`GlassyPanel`/`GlassyText2D`) when… |
|---|---|
| You need real Swing widgets: text input, `JList`, `JScrollPane`, `JTable`, complex layouts, existing Swing code | You want the native glassy look, 3D depth/bent panels, or per-node 3D animation |
| The UI is form-like and benefits from Swing layout managers | The UI is a handful of buttons/labels/sliders in 3D space |
| You accept a flat textured quad (no true 3D relief on the widget itself) | You want translucency, lighting, and back-to-front blending in 3D |

Both can coexist in one `Frame3D`: e.g. a glassy 3D chrome with a `SwingNode`
content area.

### Live consumers in this repo

`StickyNote`, `SwingNodeTest`, `SwingNodeTutorial`, `GooglerFrame3D`,
`TextViewer`, `ViewerContainer`, `ReplyForwardComponent3D`,
`PreferenceComponent3D`, `Calculator` (scientific calculator panel with key
pad, preview and history). The `swingnode.lgcfg` demo
(`lg3d-demo-apps/src/config/swingnode.lgcfg`) launches the SwingNode demo app.
