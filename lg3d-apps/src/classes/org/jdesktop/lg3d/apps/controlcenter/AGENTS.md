# Control Center Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (system settings) |
| Entry point | `ControlCenter.main` → `TitledSwingWindow.show(...)` hosting `ControlCenterPanel` |
| Surface | **SwingNode-in-Frame3D** (720x500); the same panel is reused in the 2D desktop |
| Start-menu name / group | Control Center / **System** |
| Command | `java org.jdesktop.lg3d.apps.controlcenter.ControlCenter` |
| Descriptor | `src/config/controlcenter.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

**Panels:** `ControlCenterPanel` (tabbed) + `DisplayPanel` / `UsersPanel` /
`SystemInfoPanel` / `AppearancePanel`, on the `ControlPanel` base class via
`ControlPanelRegistry`.

## Roles

- **Architect** — Production settings hub hosted through `TitledSwingWindow`/
  `SwingNode` from `lg3d-core`; the tabbed `ControlPanel` + `ControlPanelRegistry`
  design lets new setting panels be added without touching the host. Keep the same
  panel driving both the 3D and 2D desktops.
- **Engineer / Developer** — Follow the core UI/UX rulebook: offscreen SwingNode
  paint (null layout + explicit bounds), no modal dialogs (in-panel overlays), EDT
  hops from lg3d listeners, `dispose()` on discard, Metal LAF via
  `installHostedLookAndFeel`. Add a panel by extending `ControlPanel`, registering
  it in `ControlPanelRegistry`, and adding a tab. Jogamp packages only.
- **QA** — Verify the hosted window with the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black host capture under Wayland is not a defect.
  Unit-test any pure-logic panel helpers headless. Read the desktop log for
  `EventProcessor` warnings before calling an interaction broken.
- **Business Analyst** — A shipped system utility (display, users, system info,
  appearance). Hold it to production standards: real tests, review, backward
  compatibility — not "it's just a demo".
- **Functional Analyst** — Spec each tab as user-visible function plus the
  contract with core (SwingNode surface, descriptor fields, PANEL_APPS reuse).
  Note the Appearance/Desktop panels are omitted under `lg.desktop2d`.
- **Project Manager** — Commit scope `lg3d-apps`. Done = build +
  `./run-lg3d.sh` + capture/log evidence in the PR. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame (title strip,
  decoration buttons, transparency ordering). 2D: the tabbed Swing panel, identical
  in the 2D desktop. Verify overlay ordering over a maximized window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

---

## Detailed app reference (preserved)

> The original in-depth documentation for this app is kept below.

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
./gradlew :lg3d-apps:run -Papp=controlcenter
```

## Extension Points

- **ControlPanel Registry**: Add new panels via `ControlPanelRegistry`
- **Panel Layout**: Modify `ControlCenterPanel` for different tab arrangement
- **Settings Storage**: Implement persistence layer for each panel

## Known Limitations

- No settings persistence implemented (panels are UI-only)
- Limited to Swing components (no native 3D controls in panels)
- Fixed panel size (not responsive to screen size changes)
