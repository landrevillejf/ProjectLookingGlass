# CDViewer Application

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Sample / demo** (fan-out CD browser) — technique showcase, not a shipped utility |
| Entry point | `CDViewer.main` |
| Surface | **pure-3D `Frame3D`** with a custom `LayoutManager3D` (`CDLayout`) |
| Start-menu name / group | CD Viewer Sample — an `ApplicationDescription` **taskbar** entry, not a start-menu item |
| Command | `java org.jdesktop.lg3d.apps.cdviewer.CDViewer` |
| Descriptor | `src/config/cdviewer.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-demo-apps:build` |

**Components:** `CDViewer` / `CD` / `CDLayout` (`LayoutManager3D`) / `CDThumbnail` /
`Disc` / `RingShadow`; `NaturalMotionAnimation`; optional GLSL shader via
`-Dlg.shaderdemo`.

## Roles

- **Architect** — The canonical example of a **custom 3D layout** (`CDLayout` extends
  `LayoutManager3D`) plus `NaturalMotionAnimation` for smooth fan-out transitions. It
  is a reference for layout/animation technique, not a product.
- **Engineer / Developer** — Obey the core UI/UX rulebook: upload texture pixels
  before attaching (disc/thumbnail art), wrap raw `Node`s in `Component3D`, sort
  translucency (`RingShadow`, disc sheen). The GLSL shader path is opt-in
  (`-Dlg.shaderdemo`) and must degrade gracefully when unavailable. Jogamp only.
- **QA** — Verify the fan-out animation and (optionally) the shader path with the
  in-JVM probe + internal screencapture; check the log for texture NPEs. A black host
  capture under Wayland is not a defect.
- **Business Analyst** — Demonstration value only (shows 3D layout + motion). No
  end-user product surface.
- **Functional Analyst** — Spec as a demonstration (browse a set of discs in a
  fan-out layout). Its descriptor is a taskbar `ApplicationDescription`, not a
  start-menu item — intentional for a sample.
- **Project Manager** — Commit scope `lg3d-demo-apps`. Low priority; opportunistic
  changes. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — **3D only**: a `LayoutManager3D`-driven fan of textured
  discs with natural-motion transitions and optional shader effects.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-demo-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

CDViewer is a 3D demonstration application that displays a collection of CD covers in a fan-out arrangement. It showcases various LG3D API features including custom layouts, natural motion animations, mouse interactions, and thumbnail support.

## Purpose

- Demonstrate LG3D 3D application development patterns
- Showcase custom LayoutManager3D implementation
- Illustrate natural motion animations and smooth transitions
- Provide interactive 3D UI with mouse wheel and click handling

## Key Components

- **CDViewer** - Main Frame3D application container
- **CD** - Individual CD disc component with NaturalMotionAnimation
- **CDLayout** - Custom LayoutManager3D for circular fan arrangement
- **CDThumbnail** - Custom Thumbnail for taskbar/dock representation
- **Disc** - 3D disc geometry (from utils.shape)
- **RingShadow** - Shadow effect for depth perception

## Architecture

### Scene Graph Structure
```
CDViewer (Frame3D)
└── mainContainer (Container3D, rotated -65° on X-axis)
    └── CD (Container3D with NaturalMotionAnimation)
        └── inner (Component3D with NaturalMotionAnimation)
            ├── Disc (Shape3D)
            └── RingShadow (Shape3D)
```

### Layout Algorithm
- CDs arranged in circular pattern starting from front (3π/2 radians)
- Counter-clockwise progression through the disc collection
- Fan expands on mouse enter, contracts on mouse exit
- Front CD raised and scaled when focused
- Z-stacking: front CD centered, others stacked with slight offsets

## Development Guidelines

### Creating Custom Layouts
Implement `LayoutManager3D` interface:
```java
public class CDLayout implements LayoutManager3D {
    public void setContainer(Container3D cont) { }
    public void layoutContainer() { /* position children */ }
    public void addLayoutComponent(Component3D comp, Object constraints) { }
    public void removeLayoutComponent(Component3D comp) { }
    public boolean rearrangeLayoutComponent(Component3D comp, Object constraints) { }
}
```

### Natural Motion Animation
```java
NaturalMotionAnimation nma = new NaturalMotionAnimation(500); // 500ms duration
nma.setTranslationSmoother(new XYPolarNaturalVector3fSmoother());
component.setAnimation(nma);
```

### Interaction Patterns
- **Mouse Enter/Exit**: Use `MouseEnteredEventAdapter` with `ActionBoolean`
- **Mouse Click**: Use `MouseClickedEventAdapter` with `ActionNoArg`
- **Mouse Wheel**: Use `MouseWheelEventAdapter` with `ActionInt`
- **Right Click**: Pass `ButtonId.BUTTON3` to `MouseClickedEventAdapter`

## Configuration Constants

| Constant | Value | Description |
|----------|-------|-------------|
| `numDiscs` | 10 | Number of CDs in collection |
| `numImages` | 4 | Number of unique cover images (cycled) |
| `discSize` | 0.05f | Physical size of each disc |
| `fanSizeMax` | 0.10f | Expanded fan radius |
| `fanSizeMin` | 0.05f | Contracted fan radius |
| `bodyAngle` | -65° | Container tilt angle |
| `stackSpacing` | 0.00125f | Z-offset between stacked discs |

## Shader Support

Optional GLSL shader demo (enable with `-Dlg.shaderdemo`):
- Vertex shader: `resources/dimple.vert`
- Fragment shader: `resources/dimple.frag`
- Uses `SimpleShaderAppearance` for easy shader setup

## Best Practices

- **Animation Timing**: Use 500ms for natural motion (feels responsive but smooth)
- **Culling**: Disable culling for thin objects like CDs to prevent backface disappearance
- **Thumbnail Updates**: Update thumbnail texture when front CD changes
- **Layout Revalidation**: Call `revalidate()` after state changes that affect layout
- **MouseEvent Propagation**: Set `setMouseEventPropagatable(true)` for nested components

## Dependencies

- LG3D Core: Frame3D, Container3D, Component3D, LayoutManager3D
- LG3D Utils: NaturalMotionAnimation, XYPolarNaturalVector3fSmoother, Disc, RingShadow, SimpleAppearance
- Java 3D (Jogamp): Texture2D, Appearance
- Event Adapters: MouseClickedEventAdapter, MouseEnteredEventAdapter, MouseWheelEventAdapter

## Resources

- Images: `resources/images/CD1.png` through `CD4.png`
- Shaders: `resources/dimple.vert`, `resources/dimple.frag` (optional)

## Testing

Launch standalone or via LG3D:
```bash
./gradlew :lg3d-demo-apps:run -Papp=cdviewer
```

Enable shader demo:
```bash
./gradlew :lg3d-demo-apps:run -Papp=cdviewer -Dlg.shaderdemo
```

## Known Limitations

- Layout manager has dependencies on CD class (not fully abstract)
- Limited to 4 unique cover images (cycled)
- Shader demo requires `-Dlg.shaderdemo` system property
- Right-click iconification (no minimize to taskbar)
