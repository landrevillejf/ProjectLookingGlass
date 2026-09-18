# Control Center Application

## Overview

Control Center is a system settings application that provides a Swing-based UI for configuring display, users, system information, and appearance settings. It demonstrates hosting a traditional Swing panel within a 3D Frame3D using SwingNode.

## Purpose

- Provide centralized access to system configuration
- Demonstrate SwingNode integration for hybrid 2D/3D UI
- Showcase tabbed panel layout in 3D environment

## Key Components

- **ControlCenter** - Main entry point, creates Frame3D and SwingNode
- **ControlCenterPanel** - Swing JPanel containing tabbed interface
- **DisplayPanel** - Display configuration settings
- **UsersPanel** - User account management
- **SystemInfoPanel** - System information display
- **AppearancePanel** - Appearance/theme settings
- **ControlPanel** - Base class for individual setting panels
- **ControlPanelRegistry** - Registry for available control panels

## Architecture

### Scene Graph Structure
```
Frame3D (Control Center)
└── SwingNode
    └── ControlCenterPanel (JPanel)
        ├── JTabbedPane
        │   ├── DisplayPanel
        │   ├── UsersPanel
        │   ├── SystemInfoPanel
        │   └── AppearancePanel
```

### Swing Integration Pattern
```java
// Create Swing panel
ControlCenterPanel panel = new ControlCenterPanel();

// Wrap in SwingNode
SwingNode swingNode = new SwingNode();
swingNode.setJPanel(panel);
swingNode.setTransparency(0.0f); // Opaque

// Add to 3D frame
Frame3D frame3d = new Frame3D();
frame3d.addChild(swingNode);

// Set physical size
Toolkit3D tk = Toolkit3D.getToolkit3D();
float w = tk.widthNativeToPhysical(PANEL_W);
float h = tk.heightNativeToPhysical(PANEL_H);
frame3d.setPreferredSize(new Vector3f(w, h, 0.01f));
```

## Development Guidelines

### Adding New Control Panels

1. Extend `ControlPanel` base class
2. Register in `ControlPanelRegistry`
3. Add tab to `ControlCenterPanel`

```java
public class MyPanel extends ControlPanel {
    public MyPanel() {
        super("My Settings");
        // Build UI components
    }
}
```

### Panel Dimensions

- **Native Width**: 720 pixels
- **Native Height**: 500 pixels
- Converted to physical units via `Toolkit3D.widthNativeToPhysical()`

### Transparency

- SwingNode transparency set to 0.0f (fully opaque)
- Adjust for semi-transparent backgrounds if needed

## Best Practices

- **Panel Size**: Keep panels within 720x500 for consistent layout
- **Thread Safety**: Swing components must be modified on EDT
- **Resource Cleanup**: Dispose resources when panel is removed
- **Validation**: Validate settings before applying changes
- **Persistence**: Save settings to appropriate configuration files

## Dependencies

- LG3D Core: Frame3D, SwingNode, Toolkit3D
- Java Swing: JPanel, JTabbedPane, standard Swing components
- Java NIO: For file operations (if persisting settings)

## Testing

Launch via main method:
```bash
./gradlew :lg3d-demo-apps:run -Papp=controlcenter
```

## Extension Points

- **ControlPanel Registry**: Add new panels via `ControlPanelRegistry`
- **Panel Layout**: Modify `ControlCenterPanel` for different tab arrangement
- **Settings Storage**: Implement persistence layer for each panel

## Known Limitations

- No settings persistence implemented (panels are UI-only)
- Limited to Swing components (no native 3D controls in panels)
- Fixed panel size (not responsive to screen size changes)
