# Project Looking Glass - Desktop 2D Diagrams

> These diagrams describe the **conventional Swing desktop** the shell runs when
> Java 3D is unavailable (the `lg.desktop2d` surface, package
> `org.jdesktop.lg3d.displayserver.desktop2d`). Nothing here touches the scene
> graph: every class is pure Swing/AWT, so the desktop starts on a JVM where the
> Java 3D jars are missing entirely. See the companion
> [Desktop 2D Functional Analysis](../functional-analysis/desktop-2d.md).

## Component Architecture

The whole 2D desktop lives in one undecorated, maximised `JFrame`: a wallpaper
`JDesktopPane` (the MDI canvas) with the `Desktop2DTaskbar` docked along the
bottom. Overlays (toasts, brightness wash, night tint, window switcher, snap
preview) sit on the pane's layered jlayer; models are plain, headless-testable
objects owned by `Desktop2D`.

```mermaid
graph TB
    subgraph Shell["Desktop2D (the shell)"]
        ENTRY["Desktop2D.start()<br/>→ new Desktop2D().show()"]
        FRAME["JFrame<br/>undecorated, maximised"]
        PANE["WallpaperDesktopPane<br/>MDI canvas + wallpaper"]
        TASKBAR["Desktop2DTaskbar<br/>bottom bar"]
        STARTMENU["Desktop2DStartMenu<br/>JPopupMenu from .lgcfg"]
        MENUCFG["Desktop2DMenuConfig<br/>MenuModel / ItemSpec"]
    end

    subgraph Windows["Window layer"]
        WIN["Desktop2DWindow<br/>JInternalFrame (one app)"]
        MGR["SnappingDesktopManager<br/>DesktopManager"]
        SNAP["WindowSnap + SnapPreview<br/>tile / maximise zones"]
        CYCLER["WindowCycler + WindowCyclerOverlay<br/>Alt-Tab switcher"]
    end

    subgraph Models["Pure models (headless)"]
        WS["WorkspaceModel<br/>virtual desktops"]
        QL["QuickLaunchModel<br/>pinned shortcuts"]
        NOTIF["NotificationModel + DoNotDisturb"]
        SESS["SessionManager + SessionSnapshot"]
        APPREG["Desktop2DAppRegistry<br/>classify(command) → Kind"]
    end

    subgraph Chrome["Taskbar chrome"]
        PAGER["WorkspacePager"]
        TRAY["NotificationTray + ToastLayer"]
        CLOCK["clock + CalendarPopup"]
        INDIC["TaskbarIndicators<br/>network/volume/battery/…"]
    end

    subgraph Theming["Look & feel"]
        TRANSP["DesktopTransparency<br/>+ TranslucentSurface"]
        ICONS["IconPackManager / AppIcons / CategoryIcons"]
        BRIGHT["BrightnessDimmer + NightTintOverlay"]
        DESKCFG["DesktopConfig<br/>(prefs)"]
    end

    ENTRY --> FRAME --> PANE
    FRAME --> TASKBAR
    TASKBAR --> STARTMENU --> MENUCFG
    PANE --> WIN
    PANE --> MGR --> SNAP
    STARTMENU --> APPREG
    MENUCFG --> APPREG
    APPREG -->|PANEL| WIN
    TASKBAR --> PAGER --> WS
    TASKBAR --> TRAY --> NOTIF
    TASKBAR --> CLOCK
    TASKBAR --> INDIC
    WIN -. implements .-> TRANSP
    PANE --> TRANSP
    DESKCFG --> TRANSP
    DESKCFG --> ICONS
    DESKCFG --> BRIGHT
    SESS --> WIN
    CYCLER --> WS

    style ENTRY fill:#4CAF50,color:#fff
    style PANE fill:#2196F3,color:#fff
    style TASKBAR fill:#2196F3,color:#fff
    style APPREG fill:#FF9800,color:#fff
    style WIN fill:#9C27B0,color:#fff
    style WS fill:#00BCD4,color:#fff
```

## Application Launch Sequence (`Desktop2D.openApp`)

Every launcher — start-menu entry, quick-launch icon, folder-menu item, the Run
dialog, the search results — funnels through one method, `openApp(ItemSpec)`.
The registry classifies the descriptor's command and the shell branches on the
`Kind`.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant SM as Desktop2DStartMenu<br/>/ QuickLaunch / RunDialog
    participant D as Desktop2D
    participant R as Desktop2DAppRegistry
    participant P as JDesktopPane
    participant W as Desktop2DWindow
    participant N as ToastLayer

    U->>SM: click an entry (ItemSpec)
    SM->>D: openApp(item)
    D->>R: classify(item.command)
    R-->>D: Kind

    alt PANEL (a hosted Swing panel)
        D->>R: instantiate panel reflectively
        R-->>D: JComponent panel
        D->>W: new Desktop2DWindow(title, icon, panel, …)
        W->>P: add(frame)
        D->>W: showIn(desktop) — pack, clamp, cascade, toFront
        W-->>U: internal frame inside the desktop
    else SWING_FRAME (its own JFrame)
        D->>R: launchSwingFrame(command)
        R-->>D: runs in-JVM, own top-level window
        D->>N: toast "launched outside the desktop"
    else EXTERNAL (a native executable)
        D->>R: launchExternal(command)
        R-->>D: ProcessBuilder child (DISPLAY set)
        D->>N: toast "started as a separate window"
    else UNAVAILABLE (needs Java 3D)
        D->>N: toast "Requires the 3D desktop"
    end
```

## Class Model

```mermaid
classDiagram
    class Desktop2D {
        -JFrame frame
        -WallpaperDesktopPane desktop
        -Desktop2DTaskbar taskbar
        -WorkspaceModel workspaces
        -QuickLaunchModel quickLaunch
        -NotificationModel notifications
        -ToastLayer toastLayer
        -SessionManager sessionManager
        +start()$
        +openApp(ItemSpec)
        -openPanelApp(ItemSpec, dir)
        +applyDesktopConfig()
    }

    class WallpaperDesktopPane {
        +paintChildren(Graphics)
    }

    class Desktop2DTaskbar {
        -JPanel windowButtons
        -JPanel quickLaunchBar
        -WorkspacePager workspacePager
        -NotificationTray notificationTray
        -JLabel clock
        +addWindow(Desktop2DWindow)
        +removeWindow(Desktop2DWindow)
    }

    class Desktop2DStartMenu {
        <<utility>>
        +build(MenuModel, Launcher) JPopupMenu$
        +createItem(ItemSpec, Launcher) JMenuItem$
    }

    class Desktop2DWindow {
        -String appName
        -String command
        +showIn(JDesktopPane)
        +transparencySurface() WINDOW
        +refreshFrameIcon()
    }

    class Desktop2DAppRegistry {
        <<utility>>
        +classify(String) Kind$
        +launchExternal(String) boolean$
        +launchSwingFrame(String)$
    }

    class Kind {
        <<enumeration>>
        PANEL
        SWING_FRAME
        EXTERNAL
        UNAVAILABLE
    }

    class WorkspaceModel {
        -int count
        -int current
        -Map~String,Integer~ assignment
        +switchTo(int) int
        +next() int
        +assign(String,int)
        +isOnCurrent(String) boolean
    }

    class SnappingDesktopManager {
        +iconifyFrame(JInternalFrame)
        +dragFrame(JComponent,x,y)
        +endDraggingFrame(JComponent)
    }

    class TranslucentSurface {
        <<interface>>
        +transparencySurface() TransparencySurface
    }

    Desktop2D --> WallpaperDesktopPane
    Desktop2D --> Desktop2DTaskbar
    Desktop2D --> WorkspaceModel
    Desktop2D ..> Desktop2DStartMenu : builds menu
    Desktop2D ..> Desktop2DAppRegistry : classify()
    Desktop2DAppRegistry --> Kind
    Desktop2D ..> Desktop2DWindow : creates (PANEL)
    WallpaperDesktopPane o-- Desktop2DWindow
    Desktop2DWindow ..|> TranslucentSurface
    WallpaperDesktopPane ..> SnappingDesktopManager : setDesktopManager
    Desktop2DTaskbar o-- WorkspaceModel : pager reads
```

## Taskbar Layout (Left → Right)

```mermaid
graph LR
    START[Start<br/>button] --> QUICK[Pinned quick-launch<br/>strip]
    QUICK --> DIV((divider))
    DIV --> WINDOWS[One button per<br/>open window]
    subgraph RIGHT[Right cluster]
        DOCS[Documents<br/>folder menu]
        DL[Downloads<br/>folder menu]
        PAGER[Workspace<br/>pager]
        TRAY[Notification<br/>tray]
        CLOCK[Clock +<br/>calendar]
        EXIT[Exit]
    end
    WINDOWS --> DOCS
```

## Window Snap Zones (`WindowSnap`)

Dragging a window title-bar near an edge tiles it; the pure `WindowSnap` model
turns the pointer position into a zone and `SnapPreview` highlights it on the
pane's palette layer before the drop resizes the frame.

```mermaid
graph TB
    subgraph Desktop["Desktop bounds"]
        direction TB
        TOP["top edge → maximise"]
        LEFT["left edge → left half"]
        RIGHT["right edge → right half"]
        MID["away from edges →<br/>free MDI move"]
    end
    DRAG["drag title-bar"] --> PROX{"pointer within<br/>edge threshold?"}
    PROX -->|top| TOP
    PROX -->|left| LEFT
    PROX -->|right| RIGHT
    PROX -->|no| MID
    TOP --> RESIZE["setBounds(zone) on release"]
    LEFT --> RESIZE
    RIGHT --> RESIZE
```

## Workspaces (Virtual Desktops) State

`WorkspaceModel` is desktop-agnostic (the 3D `WorkspaceRegistry` reuses the same
semantics): a clamped count (1..9, default 4), a wrapped current index, and a
window-id → workspace map.

```mermaid
stateDiagram-v2
    [*] --> W0
    W0: Workspace 0 (shown)
    W1: Workspace 1
    W2: Workspace 2
    W3: Workspace 3
    W0 --> W1: next()
    W1 --> W2: next()
    W2 --> W3: next()
    W3 --> W0: next() (wraps)
    W0 --> W3: previous() (wraps)
    W0 --> W2: switchTo(2) / pager click / move-to shortcut
```

## Surface Transparency Pipeline

`Desktop2D` fades four user-settable surfaces (windows, taskbar, widgets,
notifications). A fadeable component implements `TranslucentSurface`; the pane
routes faded children through `DesktopTransparency.paintFaded`. At 100% it is
byte-identical to the legacy paint path (`isSolid` short-circuits).

```mermaid
graph LR
    CC["Control Center →<br/>Customization → Transparency"] --> CFG["DesktopConfig<br/>set*Opacity (25..100)"]
    CFG --> APPLY["Desktop2D.applyDesktopConfig()"]
    APPLY --> CACHE["DesktopTransparency.refresh()<br/>(in-memory cache)"]
    CACHE --> PAINT["WallpaperDesktopPane.paintChildren"]
    PAINT --> SOLID{"isSolid?"}
    SOLID -->|yes 100%| LEGACY["legacy paint (no change)"]
    SOLID -->|no| FADED["paintFaded →<br/>scratch image → blit @ alpha"]
```

---

**See also:** [Architecture Overview](architecture-overview.md) ·
[Sequence Diagrams](sequence-diagrams.md) · [Class Diagrams](class-diagrams.md) ·
[Desktop 2D Functional Analysis](../functional-analysis/desktop-2d.md)
