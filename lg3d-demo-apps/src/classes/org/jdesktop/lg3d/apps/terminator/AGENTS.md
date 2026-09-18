# Terminator Application

## Overview

Terminator is a system plugin that provides a shutdown/logout mechanism for LG3D. It implements the SceneManagerPlugin interface to add a taskbar item that, when clicked, launches a confirmation dialog before terminating the desktop session.

## Purpose

- Provide clean shutdown mechanism for LG3D
- Demonstrate SceneManagerPlugin implementation
- Show taskbar item registration via events
- Handle session termination

## Key Components

- **Terminator** - Main SceneManagerPlugin implementation
- **TerminatorDialog** - Confirmation dialog for shutdown
- **Pseudo3DIcon** - 3D icon for taskbar item
- **TaskbarItemConfig** - Event for registering taskbar items
- **SceneManagerPlugin** - Interface for scene manager plugins

## Architecture

### Plugin Lifecycle

```
Terminator (SceneManagerPlugin)
├── initialize(SceneControl)
│   ├── Create Pseudo3DIcon with JollyRoger image
│   ├── Add MouseClickedEventAdapter with AppLaunchAction
│   └── Post TaskbarItemConfig event
├── destroy()
├── isRemovable() -> true
└── getPluginRoot() -> null
```

### Taskbar Item Registration

```java
LgEventConnector.getLgEventConnector().postEvent(
    new TaskbarItemConfig() {
        @Override
        public Tapp createItem() {
            Tapp tapp = new Tapp();
            tapp.addChild(icon);
            tapp.setPreferredSize(icon.getPreferredSize(new Vector3f()));
            return tapp;
        }
        @Override
        public int getItemIndex() {
            return -1; // Append to end
        }
    },
    null
);
```

### Launch Flow

1. User clicks JollyRoger icon in taskbar
2. AppLaunchAction triggers: `java org.jdesktop.lg3d.apps.terminator.TerminatorDialog`
3. TerminatorDialog shows confirmation
4. User confirms shutdown
5. System.exit() called (current implementation)

## Development Guidelines

### SceneManagerPlugin Interface

```java
public class MyPlugin implements SceneManagerPlugin {
    @Override
    public void initialize(SceneControl sceneControl) {
        // Plugin initialization
    }

    @Override
    public void destroy() {
        // Cleanup
    }

    @Override
    public boolean isRemovable() {
        return true; // or false
    }

    @Override
    public Component3D getPluginRoot() {
        return null; // or root component
    }
}
```

### Taskbar Item Registration

Use `TaskbarItemConfig` event to add taskbar items:
- `createItem()`: Return Tapp with icon
- `getItemIndex()`: Return position (-1 for append)

### AppLaunchAction

```java
icon.addListener(
    new MouseClickedEventAdapter(
        new AppLaunchAction(
            "java org.jdesktop.lg3d.apps.terminator.TerminatorDialog",
            getClass().getClassLoader()
        )
    )
);
```

## Best Practices

- **Plugin Cleanup**: Remove taskbar item in destroy() method (TODO in current implementation)
- **Confirmation**: Always confirm before destructive actions
- **Client-Server**: Future LG3D will use client-server model; System.exit() will not work
- **SceneControl**: Use SceneControl API for termination when available
- **Icon Size**: Use appropriate size for taskbar (typically small)
- **Item Index**: Use -1 to append, or specific index for insertion

## Dependencies

- LG3D Core: SceneManagerPlugin, SceneControl, Component3D, Tapp
- LG3D Utils: AppLaunchAction, Pseudo3DIcon
- LG3D WG: LgEventConnector, Cursor3D
- LG3D SceneManager: TaskbarItemConfig
- Event Adapters: MouseClickedEventAdapter
- Java 3D (Jogamp): Vector3f

## Resources

- Icon: `resources/images/icon/JollyRoger.png`

## Testing

Terminator is loaded as a plugin by the scene manager. It appears in the taskbar when LG3D starts.

## Extension Points

- **SceneControl API**: Replace System.exit() with SceneControl termination method
- **Cleanup**: Implement taskbar item removal in destroy()
- **Options**: Add shutdown options (restart, logout, suspend)
- **Timer**: Add delayed shutdown with countdown
- **Force Shutdown**: Add force option for hung applications
- **Session Management**: Save session state before shutdown

## Known Limitations

- Uses System.exit() which won't work in future client-server architecture
- Taskbar item not removed in destroy() (TODO comment)
- No shutdown options (only immediate termination)
- No countdown or cancellation after confirmation
