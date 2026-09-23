# Terminator Application

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** system plugin (shutdown/logout) |
| Entry point | `Terminator` (`SceneManagerPlugin`, loaded by the scene manager); icon click runs `TerminatorDialog.main` via `AppLaunchAction` |
| Surface | **Taskbar plugin** (`Pseudo3DIcon` in a `Tapp`) + a Swing confirmation dialog |
| Start-menu name / group | *None* — registered as a taskbar item via a `TaskbarItemConfig` event, not a `.lgcfg` descriptor |
| Command | `java org.jdesktop.lg3d.apps.terminator.TerminatorDialog` (from the icon's `AppLaunchAction`) |
| Descriptor | *None* (plugin); icon `resources/images/icon/JollyRoger.png` |
| Build | `./gradlew :lg3d-demo-apps:build` |

## Roles

- **Architect** — A `SceneManagerPlugin`, not a start-menu app: `initialize(SceneControl)`
  builds the `Pseudo3DIcon`, attaches a `MouseClickedEventAdapter`+`AppLaunchAction`,
  and posts a `TaskbarItemConfig` event to add the taskbar item. Keep this event-based
  registration. The current `System.exit()` termination is a stopgap — the intended
  design terminates via the `SceneControl` API (client-server safe).
- **Engineer / Developer** — Follow the core UI/UX rulebook for the 3D icon
  (`Component3D`, `Cursor3D`, texture pixels uploaded before attach). The confirm
  dialog is a conventional Swing window on the EDT. `destroy()` should remove the
  taskbar item (currently a TODO) — wire cleanup when you touch this. Jogamp packages.
- **QA** — Verify the JollyRoger icon appears in the taskbar on startup and that
  clicking it opens the confirmation dialog (in-JVM probe + internal screencapture).
  Do **not** test the actual `System.exit()` path in CI. A black host capture under
  Wayland is not a defect.
- **Business Analyst** — A shipped system affordance (clean shutdown/logout).
  Production standards apply; a broken or missing shutdown is a high-severity issue.
- **Functional Analyst** — Spec user-visible function (click icon → confirm →
  terminate session) plus the plugin contract (`SceneManagerPlugin`, `TaskbarItemConfig`).
  Track the missing shutdown options / cleanup as backlog.
- **Project Manager** — Commit scope `lg3d-demo-apps`. Because this touches session
  termination, changes need explicit review. Done = build + `./run-lg3d.sh` + evidence.
- **UI/UX (3D & 2D)** — 3D: a small taskbar `Pseudo3DIcon` with hover/press feedback.
  2D: the Swing confirmation dialog. Keep the destructive-action confirmation clear.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-demo-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

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
