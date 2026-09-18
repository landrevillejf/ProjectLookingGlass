# SwingNode Test Application

## Overview

SwingNodeTest is a demonstration of advanced SwingNode integration, featuring a custom cloth-like geometry for the Swing panel and a physics simulation (cloth with spring-damper system). It showcases how to create custom SwingNodeRenderer implementations for non-rectangular Swing panel geometries.

## Purpose

- Demonstrate custom SwingNodeRenderer implementation
- Showcase cloth physics simulation with springs
- Illustrate dynamic texture mapping on deformable geometry
- Provide example of control frame for physics parameters

## Key Components

- **SwingNodeTest** - Main Tapp entry point
- **ClothSwingNodeGeometry** - Custom SwingNodeRenderer with cloth geometry
- **ClothTest** - Cloth physics simulation with particle-spring system
- **ControlFrame** - Separate JFrame for physics parameter tuning
- **TestPanel** - Swing JPanel to display on cloth
- **Particle** - Individual particle in cloth simulation
- **Spring** - Spring connection between particles

## Architecture

### Scene Graph Structure

```
SwingNodeTest (Tapp)
└── Frame3D
    └── Component3D
        ├── TransformGroup (sphere offset)
        │   └── Sphere (decoration)
        └── SwingNode (with ClothSwingNodeGeometry)
            └── ClothTest (cloth geometry)
```

### ClothSwingNodeGeometry

```java
class ClothSwingNodeGeometry extends SwingNodeRenderer {
    private Appearance swingAppearance;
    private ClothTest body;

    public ClothSwingNodeGeometry() {
        width3D = 0.04f;
        height3D = 0.03f;
        swingAppearance = new Appearance();
        body = new ClothTest(width3D, height3D, swingAppearance);
        ControlFrame controlFrame = new ControlFrame(body);
        controlFrame.setVisible(true);
        addChild(body);
    }

    public void textureChanged(Texture2D texture) {
        swingAppearance.setTexture(texture);
        // Update geometry size if panel changed
        body.setSize(width3D, height3D, ...);
    }
}
```

## Development Guidelines

### Custom SwingNodeRenderer

Extend SwingNodeRenderer to create custom geometries:

```java
class MyGeometry extends SwingNodeRenderer {
    public MyGeometry() {
        width3D = 0.04f;  // Physical width
        height3D = 0.03f; // Physical height
        // Create appearance with texture capability
        swingAppearance = new Appearance();
        swingAppearance.setCapability(Appearance.ALLOW_TEXTURE_WRITE);
        // Create custom geometry
        addChild(myCustomGeometry);
    }

    public void textureChanged(Texture2D texture) {
        swingAppearance.setTexture(texture);
        // Handle texture size changes
    }
}
```

### Using Custom Geometry

```java
SwingNode swingNode = new SwingNode(new ClothSwingNodeGeometry());
swingNode.setPanel(new TestPanel());
```

### Default Geometry

For simple rectangular geometry, use default constructor:
```java
SwingNode swingNode = new SwingNode();
```

## Physics Simulation

The cloth simulation uses:
- **Particles**: Grid of mass points
- **Springs**: Connect adjacent particles (structural, shear, bend)
- **Damping**: Reduces oscillation
- **Gravity**: Pulls particles downward
- **Mouse Interaction**: Drag particles with mouse

## Control Frame

Separate JFrame provides real-time parameter tuning:
- Spring stiffness
- Damping coefficient
- Gravity strength
- Time step

## Best Practices

- **Texture Capability**: Set `Appearance.ALLOW_TEXTURE_WRITE` for dynamic updates
- **Size Tracking**: Monitor panel size changes via `textureChanged()`
- **Physical Units**: Use Toolkit3D to convert native pixels to physical units
- **Control Frame**: Keep control frame separate for easy parameter tuning
- **Performance**: Limit cloth resolution for real-time performance

## Dependencies

- LG3D Core: Frame3D, SwingNode, SwingNodeRenderer, Tapp, Component3D
- LG3D Utils: Sphere, SimpleAppearance, FuzzyEdgePanel
- LG3D WG: Toolkit3D
- Java 3D (Jogamp): Appearance, Texture2D, TextureAttributes, Transform3D, TransformGroup
- Java Swing: JPanel, JFrame

## Testing

Launch standalone:
```bash
./gradlew :lg3d-demo-apps:run -Papp=swingnode
```

## Extension Points

- **Geometries**: Implement other deformable surfaces (cylinder, sphere)
- **Physics**: Add wind, collision detection, tearing
- **Interaction**: Multi-touch, gesture support
- **Rendering**: Bump mapping, specular highlights on cloth
- **Performance**: GPU acceleration for physics simulation

## Known Limitations

- Cloth simulation can be CPU-intensive at high resolutions
- Control frame is separate window (not integrated in 3D)
- No collision detection with other objects
- Fixed grid topology (cannot tear or cut cloth)
