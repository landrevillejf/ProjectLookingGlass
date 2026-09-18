# Control Center Application

## Overview

Control Center is a 100% lg3d-native 3D system settings app (no SwingNode, no
Swing widgets): a `Frame3D` shell with a navigation column of glassy tabs on
the left and category pages on the right, built from the shared widget kit in
`org.jdesktop.lg3d.apps.uikit`.

## Purpose

- Centralized access to display, user, system and appearance configuration
- Demonstrate the native page-plugin pattern (`ControlPanel` +
  `ControlPanelRegistry`) in pure 3D

## Key Components

- **ControlCenter** - Main entry point (`java ...controlcenter.ControlCenter`),
  constructs `ControlCenterFrame3D` and calls `changeEnabled/changeVisible(true)`
- **ControlCenterFrame3D** - The shell: nav tab column (`Button3D`), page host,
  and enabled/visible gating forwarded to the current page
- **ControlPanel** - Native page interface:
  `displayName()`, `component(float w, float h)`, `onShow()`, `onHide()`
- **ControlPanelRegistry** - Discovers pages; extras can be registered before
  the window is built
- **DisplayPage3D** - xrandr via `DisplayService`: output + resolution lists,
  Primary toggle, Apply with a 20s Keep/revert countdown (safety net against
  modes the monitor cannot show); read-only warning without xrandr / on Wayland
- **UsersPage3D** - read-only account browser via `UserService` (list, Show
  System toggle, details + group membership); administration needs privileged
  commands and text entry the native widget set does not provide
- **SystemPage3D** - live CPU/memory `Gauge3D`s + host/kernel/session/load/
  filesystem details via `SystemInfoService` (2s timer gated by onShow/onHide)
- **AppearancePage3D** - wallpaper picker: enumerates
  `resources/images/background` on the classpath (FALLBACK list when
  unlistable), textured-quad preview, Apply posts a
  `BackgroundChangeRequestEvent(new SimpleImageBackground(url))`

## Architecture

### Scene Graph Structure
```
ControlCenterFrame3D (Frame3D + standard decoration)
├── Component3D (window backdrop + title)
├── Button3D xN (nav tabs, one per ControlPanel)
└── Component3D (page host)
    └── <current page Component3D>  (swapped on tab click)
```

### Page Lifecycle + Threading

- `component(w, h)` is called once at shell construction; pages position
  children relative to their own origin (page area is centered on the host).
- Tab clicks call `onHide()` on the old page and `onShow()` on the new one;
  the frame also forwards its own `setEnabled`/`setVisible` transitions, so
  minimizing/closing the window stops page timers.
- All blocking service calls (xrandr, /etc/passwd, image decoding) run on
  daemon threads; scene-graph updates go through
  `SwingUtilities.invokeLater`.
- AppearancePage3D follows the texture rule: pixels are in the
  `ImageComponent2D` before `Texture2D.setImage` / `Appearance.setTexture`.
- The Display page's Keep/revert countdown deliberately keeps running while
  another page is shown (it is a safety net, not a UI poll).

### Window Sizing / Maximize

Preferred size matches the usable screen aspect
(`screenH - Taskbar.getReservedBottomHeight()`) so the decoration's
aspect-preserving maximize fills the viewport on both axes.

## Development Guidelines

### Adding a New Page

1. Implement `ControlPanel` (native `Component3D`, no Swing)
2. Register it via `ControlPanelRegistry.register(...)` before the shell is
   built, or add it to the defaults list
3. Gate any polling in `onShow()`/`onHide()`

## Dependencies

- LG3D Core: `Frame3D`, `Component3D`, `GlassyPanel`, `GlassyText2D`, event
  adapters/actions, `DisplayService`, `UserService`, `SystemInfoService`,
  `ProcessService` (formatters), `SimpleImageBackground` +
  `BackgroundChangeRequestEvent`, `Taskbar` (reserved height)
- `org.jdesktop.lg3d.apps.uikit`

## Testing

Launch from the desktop Start Menu (System group) or:
```bash
./gradlew :lg3d-demo-apps:run -Papp=controlcenter
```

## Extension Points

- **New pages**: audio mixer, keyboard layout, network - via the registry
- **Users admin**: would need a native text-input widget first
- **Display**: refresh-rate and multi-monitor position selection
