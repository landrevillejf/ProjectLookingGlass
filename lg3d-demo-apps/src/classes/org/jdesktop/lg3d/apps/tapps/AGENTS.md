# Tapps (3D Icons) Applications

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
