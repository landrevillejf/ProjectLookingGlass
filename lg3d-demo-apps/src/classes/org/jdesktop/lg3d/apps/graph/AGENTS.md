# Sample Graph Application

## Overview

Sample Graph is a physics-based demonstration of the spring-damper system in LG3D. It creates a graph of connected nodes (frames) that interact via spring forces, demonstrating dynamic scene graph behavior.

## Purpose

- Demonstrate spring-damper physics simulation
- Showcase `SprungFrame3D` for physics-enabled components
- Illustrate dynamic scene graph with force-based layout
- Provide example of natural motion through physics

## Key Components

- **SampleGraph** - Main entry point, creates graph structure
- **SprungFrame3D** - Frame3D subclass with spring-damper physics
- **Spring** - Physics connection between two sprung frames
- **Component3D** - Visual representation (Box or Sphere)
- **Appearance** - Material properties for shapes

## Architecture

### Scene Graph Structure
```
SampleGraph (creates 4 SprungFrame3D instances)
├── SprungFrame3D #1
│   └── Component3D
│       └── Sphere
├── SprungFrame3D #2
│   └── Component3D
│       └── Sphere
├── SprungFrame3D #3
│   └── Component3D
│       └── Sphere
└── SprungFrame3D #4
    └── Component3D
        └── Sphere

Spring connections:
- #1 ↔ #2 (rest length: 0.06f)
- #2 ↔ #3 (rest length: 0.06f)
- #3 ↔ #4 (rest length: 0.06f)
- #4 ↔ #1 (rest length: 0.06f) - forms outer ring
- #1 ↔ #3 (rest length: 0.085f) - diagonal
- #2 ↔ #4 (rest length: 0.085f) - diagonal
```

### Spring-Damper Physics

The spring-damper system provides:
- **Spring Force**: Pulls connected frames toward rest length
- **Damping**: Reduces oscillation over time
- **Natural Motion**: Smooth, physics-based transitions

## Development Guidelines

### Creating Sprung Frames

```java
SprungFrame3D frame = new SprungFrame3D();
frame.addChild(createChild(ChildType.SPHERE));
frame.setEnabled(true);
frame.setVisible(true);
```

### Connecting with Springs

```java
Spring spring = new Spring(frame1, frame2, restLength);
```

### Child Types

```java
private enum ChildType { BOX, SPHERE }

private Component3D createChild(ChildType childType) {
    Component3D ret = new Component3D();
    Appearance app = new Appearance();
    switch(childType) {
        case BOX:
            ret.addChild(new Box(0.01f, 0.01f, 0.01f, app));
            break;
        case SPHERE:
            ret.addChild(new Sphere(0.01f));
            break;
    }
    return ret;
}
```

## Configuration

| Parameter | Value | Description |
|-----------|-------|-------------|
| Sphere radius | 0.01f | Size of node spheres |
| Box size | 0.01f x 0.01f x 0.01f | Size of box nodes |
| Outer spring rest | 0.06f | Rest length for ring connections |
| Diagonal spring rest | 0.085f | Rest length for diagonal connections |

## Best Practices

- **Rest Lengths**: Choose rest lengths that create desired initial layout
- **Damping**: Adjust damping coefficients for desired settling time
- **Initial Positions**: Frames start at origin; springs will arrange them
- **Multiple Graphs**: Each graph is independent; can create multiple instances
- **Performance**: Limit number of nodes/springs for real-time performance

## Dependencies

- LG3D Core: SprungFrame3D, Spring (from scenemanager.utils.springdamper)
- LG3D Utils: Box, Sphere, Appearance
- Java 3D (Jogamp): Standard scene graph components

## Testing

Launch standalone:
```bash
./gradlew :lg3d-demo-apps:run -Papp=graph
```

## Extension Points

- **Child Types**: Add new shapes (Cylinder, Cone, etc.)
- **Spring Properties**: Adjust stiffness and damping per spring
- **Interaction**: Add mouse drag to move nodes
- **Graph Topology**: Create different connection patterns (tree, mesh, etc.)
- **Visual Feedback**: Color nodes based on stress or velocity

## Known Limitations

- No user interaction (nodes move via physics only)
- Fixed topology (4 nodes in specific pattern)
- No visual indication of spring forces
- Nodes start at origin (may cause initial chaotic motion)
