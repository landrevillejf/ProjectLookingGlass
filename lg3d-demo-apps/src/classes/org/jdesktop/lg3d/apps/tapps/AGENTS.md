# Tapps (3D Icons) Applications

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Sample / demo** (3D taskbar/dock icons) — technique showcase, not shipped |
| Entry point | No `main()`; `WebIcon` / `WebIcon2` are instantiated as `Tapp` taskbar items |
| Surface | **pure-3D `Tapp` icons** (a Java 3D `BranchGroup` bridged in via `Java3DGraph`) |
| Start-menu name / group | *None* — `webicon.lgcfg` sets `ignoreConfig=true` (read but not posted); these are taskbar icons |
| Command | `org.jdesktop.lg3d.apps.tapps.WebIcon2` (taskbar item, not a start-menu launch) |
| Descriptor | `src/config/webicon.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-demo-apps:build` |

**Components:** `WebIcon` (earth–moon system) / `WebIcon2` / `Java3DGraph` (bridges a
raw Java 3D `BranchGroup` into lg3d) on the `Tapp` base; hover/press effects via
`NaturalMotionAnimation` + `Translate/ScaleActionBoolean`.

## Roles

- **Architect** — The reference for **embedding a raw Java 3D `BranchGroup`** into the
  lg3d scene graph via `Java3DGraph`, and for building animated `Tapp` taskbar icons.
  Keep the Java3DGraph bridge generic; do not hard-code the earth–moon scene into it.
- **Engineer / Developer** — Obey the core UI/UX rulebook: upload texture pixels
  before attaching (earth/moon textures), set `ALLOW_TRANSFORM_WRITE` on animated
  `TransformGroup`s, infinite `BoundingSphere` for always-on interpolators,
  `setMouseEventPropagatable(true)` for nested pickable parts, `Cursor3D` on
  interactive components. Jogamp packages only.
- **QA** — Verify the icons animate (rotation, hover lift/scale, press scale) with the
  in-JVM probe + internal screencapture; check the log for texture NPEs. `AppLaunchAction`
  is commented out in `WebIcon` — a known gap, not a defect.
- **Business Analyst** — Demonstration value only (animated 3D taskbar icons). No
  end-user product surface; `ignoreConfig=true` keeps them out of the start menu.
- **Functional Analyst** — Spec as a demonstration (a `Tapp` icon with rotation +
  hover/press effects). Record the un-wired launch action as an explicit gap.
- **Project Manager** — Commit scope `lg3d-demo-apps`. Low priority; opportunistic.
  Branch → PR against `main`.
- **UI/UX (3D & 2D)** — **3D only**: animated textured icons with hover-lift,
  hover-scale and press-scale micro-interactions — the model for taskbar/dock icon UX.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-demo-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

## Overview

Tapps contains sample 3D icon implementations that demonstrate creating interactive 3D taskbar/dock icons. These icons showcase Java 3D integration within LG3D, including rotation animations, texture mapping, and mouse interaction effects.

## Purpose

- Demonstrate 3D icon creation for taskbar/dock
- Showcase Java 3D graph integration via Java3DGraph
- Illustrate rotation animations and mouse interactions
- Provide examples of textured 3D objects

## Key Components

- **WebIcon** - Earth-moon system with rotation animations
- **WebIcon2** - Alternative 3D icon implementation
- **Java3DGraph** - Bridge for integrating Java 3D BranchGroup into LG3D
- **Tapp** - Base class for taskbar/dock items

## Architecture

### WebIcon Structure

```
WebIcon (Tapp)
└── Java3DGraph (with NaturalMotionAnimation)
    └── BranchGroup (Java 3D scene graph)
        ├── planetRot (TransformGroup with RotationInterpolator)
        │   └── Sphere (earth with texture)
        └── moonRot (TransformGroup with RotationInterpolator)
            └── moonPos (TransformGroup)
                └── Sphere (moon)
```

### Interaction Effects

WebIcon implements three mouse interaction effects:
1. **Mouse Enter**: Translates icon upward (0.002f) with 175ms animation
2. **Mouse Enter**: Scales icon to 1.2x with 175ms animation
3. **Mouse Press**: Scales icon to 1.1x with 100ms animation

## Development Guidelines

### Creating a 3D Icon

```java
public class MyIcon extends Tapp {
    public MyIcon() {
        setPreferredSize(new Vector3f(0.01f, 0.01f, 0.01f));
        
        Java3DGraph j3d = new Java3DGraph();
        j3d.setAnimation(new NaturalMotionAnimation(150));
        
        // Add interaction effects
        j3d.addListener(new MouseEnteredEventAdapter(
            new TranslateActionBoolean(j3d, new Vector3f(0.0f, 0.002f, 0.0f), 175)));
        j3d.addListener(new MouseEnteredEventAdapter(
            new ScaleActionBoolean(j3d, 1.2f, 175)));
        
        j3d.addJ3dChild(buildGraph());
        addChild(j3d);
    }
    
    private BranchGroup buildGraph() {
        // Build Java 3D scene graph
        BranchGroup ret = new BranchGroup();
        // Add shapes, transforms, behaviors
        return ret;
    }
}
```

### Java 3D Integration

Use `Java3DGraph` to wrap Java 3D BranchGroup:
```java
Java3DGraph j3d = new Java3DGraph();
j3d.addJ3dChild(myBranchGroup);
```

### Rotation Animation

```java
TransformGroup tg = new TransformGroup();
tg.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);

Alpha alpha = new Alpha(-1, 10000L); // -1 = infinite loop, 10000ms duration
RotationInterpolator rotator = new RotationInterpolator(alpha, tg);
rotator.setSchedulingBounds(new BoundingSphere(new Point3d(), Double.POSITIVE_INFINITY));

tg.addChild(rotator);
```

### Texture Mapping

```java
Appearance app = new Appearance();
TextureLoader loader = new TextureLoader(
    getClass().getResource("/path/to/texture.jpg"),
    null
);
Texture tex = loader.getTexture();
app.setTexture(tex);

Sphere sphere = new Sphere(
    0.004f,
    Sphere.GENERATE_NORMALS | Sphere.GENERATE_TEXTURE_COORDS,
    9,
    app
);
```

## Configuration

| Parameter | WebIcon Value | Description |
|-----------|---------------|-------------|
| Icon size | 0.01f | Preferred size |
| Animation duration | 150ms | Natural motion duration |
| Hover translation | 0.002f upward | Mouse enter lift |
| Hover scale | 1.2x | Mouse enter scale |
| Press scale | 1.1x | Mouse press scale |
| Planet radius | 0.004f | Earth sphere size |
| Moon radius | 0.001f | Moon sphere size |
| Planet rotation | 10000ms | Earth rotation period |
| Moon rotation | 2000ms | Moon orbit period |

## Best Practices

- **Bounding Sphere**: Use infinite bounds for behaviors that should always be active
- **Capabilities**: Set ALLOW_TRANSFORM_WRITE on TransformGroups for animations
- **Texture Coordinates**: Include GENERATE_TEXTURE_COORDS for textured spheres
- **Natural Motion**: Use NaturalMotionAnimation for smooth transitions
- **MouseEvent Propagation**: Set `setMouseEventPropagatable(true)` for nested components
- **Cursor**: Set appropriate cursor (e.g., Cursor3D.SMALL_CURSOR)

## Dependencies

- LG3D Core: Tapp, Java3DGraph, Component3D
- LG3D Utils: NaturalMotionAnimation, ScaleActionBoolean, TranslateActionBoolean, AppLaunchAction
- LG3D WG: Cursor3D
- Java 3D (Jogamp): BranchGroup, TransformGroup, Transform3D, Sphere, Appearance, Texture, TextureLoader, Alpha, RotationInterpolator, BoundingSphere
- Event Adapters: MouseClickedEventAdapter, MouseEnteredEventAdapter, MousePressedEventAdapter

## Resources

- Earth texture: `/org/jdesktop/lg3d/apps/tutorial/resources/images/earth.jpg`

## Testing

Launch WebIcon:
```bash
./gradlew :lg3d-demo-apps:run -Papp=tapps.WebIcon
```

Launch WebIcon2:
```bash
./gradlew :lg3d-demo-apps:run -Papp=tapps.WebIcon2
```

## Extension Points

- **AppLaunchAction**: Connect icon click to application launch
- **Custom Geometries**: Create different 3D shapes (cylinder, cone, box)
- **Complex Animations**: Add multiple behaviors (scale, rotation, translation)
- **Sound Effects**: Add audio feedback on interactions
- **Dynamic Textures**: Animate texture changes
- **Particle Effects**: Add particle systems for visual flair

## Known Limitations

- AppLaunchAction commented out in WebIcon (not connected to launcher)
- Limited to two example icons
- No sound effects
- No dynamic texture animation
