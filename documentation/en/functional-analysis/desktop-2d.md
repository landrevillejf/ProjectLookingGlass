# Project Looking Glass - Desktop 2D Functional Analysis

## Document Information

| Field | Value |
| --- | --- |
| **Subject** | The conventional-Swing desktop (`lg.desktop2d` shell) |
| **Package** | `org.jdesktop.lg3d.displayserver.desktop2d` |
| **Companion** | [Desktop 2D Diagrams](../diagrams/desktop-2d.md) |
| **Status** | Production - the fallback desktop used whenever Java 3D is unavailable |

## Introduction

### Purpose

The Desktop 2D is the shell the project runs when the Java 3D scene graph cannot
be created - a headless/`-x`-less machine, a JVM without the Java 3D jars, or a
user who explicitly selects the conventional desktop. It provides the everyday
desktop affordances (a start menu, a taskbar, resizable windows, virtual
desktops, notifications, a session) entirely in Swing/AWT, so the product is
usable and demonstrable with no GPU/3D dependency at all.

### Scope

This analysis covers the shell and its collaborators in the `desktop2d` package:
bootstrap, the application-registry classification, the start menu, the taskbar
and its chrome, the internal-frame window layer, workspaces, notifications,
transparency, theming, session restore and the Run box. It does **not** cover the
3D desktop (`Frame3D`/scene graph) except where the two deliberately share a
pure model (workspaces) or read the same descriptor source.

### Target audience

Architects and developers maintaining the display server, QA verifying the
2D shell headlessly, and analysts needing the behavioural contract of the
fallback desktop.

## System Overview

### System description

`Desktop2D` is one `JFrame`, undecorated and maximised, that fills the display.
Inside it sits a `WallpaperDesktopPane` (a `JDesktopPane` that paints the
wallpaper and hosts the application windows as `JInternalFrame`s) with the
`Desktop2DTaskbar` docked along the bottom edge. The class is started by the
display server when the 3D mode is not selected, and on its own via the static
entry point `Desktop2D.start()`, which marshals onto the EDT and calls
`new Desktop2D().show()`. A single running instance is published in a static
`instance` field so the Control Center's settings panels can reach the live
desktop.

The shell sets the system property `lg.desktop2d` (`MODE_PROPERTY`) while it
runs; 3D-only feature panels read that flag and leave themselves out instead of
throwing. **Nothing in the package touches Java 3D**, which is what lets the
whole desktop boot on a JVM that lacks the Java 3D jars.

### The one constraint that shapes everything: MDI is pure Swing

The 2D desktop is a Swing MDI canvas. It does **not** reparent or composite
foreign X11 windows into itself. That single architectural fact is why native
applications (Firefox, xterm, `javaws`) appear as separate top-level windows
managed by the host window manager *next to* the desktop - this is intended
behaviour, not a defect. Embedding such a window would require being the X11
compositor/window-manager (the `run-lg3d.sh -x` 3D path, still in development),
or wrapping the capability in a hosted Swing panel.

## Functional Requirements

### FR-D2D-1: Desktop bootstrap

| Item | Detail |
| --- | --- |
| **Requirement** | Start a full-screen conventional desktop with no Java 3D. |
| **Entry** | `Desktop2D.start()` → `SwingUtilities.invokeLater` → `new Desktop2D().show()`. |
| **Shell** | Undecorated, maximised `JFrame` titled "Project Looking Glass (2D desktop)". |
| **Backdrop** | A wallpaper resolved from the classpath `resources/` prefix (GrandCanyon → DreamLakeReflections fallbacks). |
| **Mode flag** | Publishes `lg.desktop2d`; single instance kept in a static field for settings panels. |

### FR-D2D-2: Application classification and launch

Every start-menu command is classified by `Desktop2DAppRegistry.classify(command)`
into one of four `Kind`s, and `Desktop2D.openApp(ItemSpec)` acts on it:

| Kind | Command shape | Behaviour |
| --- | --- | --- |
| **PANEL** | `java <class>` whose UI is a plain Swing panel | Instantiate the *panel* class reflectively (never the 3D wrapper's `main`) and host it in a `Desktop2DWindow` internal frame. |
| **SWING_FRAME** | `java`/`swingapp <class>` that shows its own `JFrame` | Launch in-process; it opens beside the desktop (an MDI frame cannot adopt a `JFrame`). Reported as "outside the desktop". |
| **EXTERNAL** | any command not starting with `java `/`swingapp ` (e.g. `firefox`) | Start as an OS child via `ProcessRunner`/`ProcessBuilder` with `DISPLAY` set; the window is managed by the host WM. |
| **UNAVAILABLE** | pure Java 3D apps with no registered panel | Refused (needs the 3D desktop). |

The PANEL set (`PANEL_APPS`) maps a 3D descriptor's main class to a sibling
AWT-free Swing panel in the same package: File Manager, Task Manager, Control
Center, Calculator, Archive, Media Writer, Help Center, About, Widget Gallery,
LPM Console, Software Update, Database Manager, FTP Client, Mail, Agenda, Chart,
Contacts, and more. The lookup is **reflective** because `lg3d-core` cannot
depend on `lg3d-apps`, and on a 3D-less machine the app's `Frame3D`-building
wrapper class must never be loaded - only its panel.

### FR-D2D-3: Start menu

`Desktop2DStartMenu` builds a `JPopupMenu` from the *same* `.lgcfg` descriptors
the 3D start menu reads (via `Desktop2DMenuConfig.MenuModel`/`ItemSpec`). The
group graph is traversable in both directions ("Main" ↔ "Demos"), so sub-menus
are built with an **ancestor guard** and a **depth cap** (`MAX_DEPTH = 4`); a
back-link to an ancestor is dropped rather than recursing. Applications the 2D
desktop cannot run are handled per `Kind`: a missing external executable and a
pure-3D (`UNAVAILABLE`) app are **omitted entirely** so the menu lists only what
this desktop can actually run. Category sub-menus carry a recognisable glyph
(`CategoryIcons`); entries use the active icon pack (`AppIcons`).

### FR-D2D-4: Taskbar and its chrome

`Desktop2DTaskbar` is a bottom `JPanel` standing in for the glassy 3D taskbar.
Left to right:

- the **Start** button (opens the start menu),
- the pinned **quick-launch** strip (2D counterpart of the 3D `shortcuts`
  container; backed by `QuickLaunchModel`, seeded to `DEFAULT_QUICK_LAUNCHERS = 6`
  on first run, drag-to-pin via a `DropTarget`),
- one **window button per open** `Desktop2DWindow` (click to iconify/restore,
  kept in a `LinkedHashMap` so order tracks open order),
- on the right: the **Documents** and **Downloads** folder menus
  (`Desktop2DFolderMenu`), the **workspace pager** (`WorkspacePager`), the
  **notification tray** (`NotificationTray`), the **clock** with a pop-up
  **calendar** (`CalendarPopup`), `TaskbarIndicators` (network/volume/battery/
  brightness/bluetooth/printer/locale/time-zone), and **Exit**.

The bar is configurable: bar height via a `barScale` (base 34px, floor 18px) and
an optional **auto-hide** that collapses the bar to a 5px sliver, restored by a
pointer-proximity poll (`AUTOHIDE_POLL_MS = 250`).

### FR-D2D-5: Window management

Each hosted app is a `Desktop2DWindow` (a `JInternalFrame`), sized to the panel's
preferred size, **clamped** to the pane and **cascaded** (`CASCADE_STEP = 28`) so
windows do not stack exactly. It installs a single *Help → About* menu bar feeding
the reusable `AboutDialog`. A `SnappingDesktopManager` (a `DefaultDesktopManager`)
governs the pane:

- **minimise keeps one representation** - the taskbar button, not a stray desktop
  icon (`iconifyFrame` hides the desktop icon),
- **snap-to-edge tiling** - while the title bar is dragged, the pointer's
  proximity to an edge is resolved to a `WindowSnap.Zone` (left/right half or
  maximise), previewed by a `SnapPreview` on the pane's palette layer, and applied
  (`setBounds`) on release; away from any edge the drag behaves as stock MDI.

The snap *decision* lives in the pure, headless-testable `WindowSnap`; the
manager is only the Swing drag plumbing. `Alt-Tab` cycling is done by a
`WindowCycler`/`WindowCyclerOverlay`.

### FR-D2D-6: Workspaces (virtual desktops)

`WorkspaceModel` is a desktop-agnostic model: a clamped count (`MIN_COUNT` 1 ..
`MAX_COUNT` 9, default `DEFAULT_COUNT` 4), a wrapped current index, and a
`windowId → workspace` map (ids are the window `appName`). `switchTo`, `next` and
`previous` wrap via `Math.floorMod`, so a nonexistent workspace can never be
selected; shrinking the count pulls stranded windows back into range. `Desktop2D`
drives MDI-frame visibility and the taskbar buttons from it, and the same model
powers the 3D desktop's `WorkspaceRegistry` - both surfaces offer identical
workspace semantics.

### FR-D2D-7: Notifications

A `NotificationModel` holds posted notifications; a `ToastLayer` renders transient
banners, a `NotificationTray` on the taskbar lists them, and `DoNotDisturb`
suppresses them. Launch outcomes (e.g. "started outside the desktop",
"Requires the 3D desktop") are surfaced through this path.

### FR-D2D-8: Surface transparency

The shell fades four user-settable surfaces - **windows, taskbar, widgets,
notifications** - through `DesktopTransparency`, configured live in Control
Center → Customization → Transparency (25..100, default 100 = solid, persisted
via `DesktopConfig`). A fadeable component implements `TranslucentSurface` and
declares its `TransparencySurface`; `WallpaperDesktopPane.paintChildren` routes
faded children through `paintFaded` (render into a transparent scratch image,
blit at the surface alpha). Opacity is read from an **in-memory cache refreshed on
the EDT** (`refresh()` at start-up and from `Desktop2D.applyDesktopConfig()`) -
paint code never reads `DesktopConfig` inside a paint pass - and every hook
short-circuits on `isSolid`/`allSolid` so **100% is byte-identical to the legacy
path**.

### FR-D2D-9: Theming and appearance

`Desktop2D.applyDesktopConfig()` pushes `DesktopConfig` into Swing: a scale of
`UIManager` font keys (`FONT_KEYS`), the icon scale, bar scale and opacities.
Icon packs are managed by `IconPackManager`/`AppIcons`/`CategoryIcons` (open
windows re-resolve their frame icons live via `Desktop2DWindow.refreshFrameIcon()`),
Metal themes by `MetalThemeManager`, and a software `BrightnessDimmer` plus a
`NightTintOverlay` wash the whole desktop from the glass pane.

### FR-D2D-10: Session, Run box, search, schedule

`SessionManager` snapshots the open windows (each `Desktop2DWindow` keeps its
descriptor `command` and `iconResource` so it can be relaunched) and restores them,
backed by `SessionSnapshot`/`PrefsSessionStore`. A `RunDialog` + `RunResolver` +
`RunHistory` give a Win-R style command box (routing a raw command back through
`openApp`). `AppSearch`/`StartMenuSearch` make the menu searchable. A `CalendarPopup`
opens the Agenda at a double-clicked date, and `ScheduleService`/`TaskScheduler`
drive reminders.

## Use Cases

### UC-D2D-1: Launch a panel application

1. User clicks an entry in the start menu (or a quick-launch icon).
2. The `Launcher` calls `Desktop2D.openApp(item)`.
3. `Desktop2DAppRegistry.classify` returns `PANEL`.
4. The registry instantiates the app's Swing panel reflectively.
5. `Desktop2D` wraps it in a `Desktop2DWindow`, adds it to the pane and calls
   `showIn` (pack, clamp, cascade, toFront, focus).
6. A matching button appears on the taskbar; the window is workspace-aware and
   fadeable like every other window.

### UC-D2D-2: Launch a native executable

1. User clicks e.g. a browser entry; `classify` returns `EXTERNAL`.
2. `Desktop2DAppRegistry.launchExternal` starts the process with `DISPLAY` set.
3. The app opens as its **own top-level window** beside the desktop; the shell
   posts an informational toast. This is by design - the MDI canvas does not
   embed foreign windows.

### UC-D2D-3: Tile a window by dragging

1. User drags a window's title bar toward the left edge.
2. `SnappingDesktopManager.dragFrame` asks `WindowSnap.zoneFor(pointer,...)` and
   lights a `SnapPreview` over the left half.
3. On release, `endDraggingFrame`/`applyPendingSnap` resizes the frame to that
   region. Dropping at the top maximises; away from an edge moves freely.

### UC-D2D-4: Move a window to another workspace

1. User invokes the pager on the taskbar or the move-to-workspace shortcut.
2. `WorkspaceModel.assign(windowId, index)` (wrapped) re-homes the window.
3. `Desktop2D` re-shows only the windows whose `isOnCurrent` is true; the pager
   reflects the counts.

## Non-goals and constraints

- **No scene graph.** The shell never builds a `Frame3D`; it must boot with the
  Java 3D jars absent.
- **No foreign-window embedding by default.** Native apps live outside the MDI
  canvas; compositing them is a separate 3D compositor feature
  (`Desktop2DCompositorHost`/`CompositedWindowBridge`), not the base 2D path.
- **Pure models stay headless.** `WorkspaceModel`, `WindowSnap` and the registry
  hold no Swing so they are unit-tested without a display.
- **No `System.exit` from in-JVM swingapps** on close - it would take the whole
  desktop down with it.
- **100% transparency must cost nothing.** The solid path is byte-identical to
  legacy painting.

---

**See also:** [Desktop 2D Diagrams](../diagrams/desktop-2d.md) ·
[Functional Analysis](functional-analysis.md) ·
[Architecture Overview](../diagrams/architecture-overview.md)
