# Remote Viewer

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production-grade** RMI remote-desktop server + viewer (ported from the standalone jrdesktop / Remote Viewer tool) |
| Entry point | `RemoteViewer.main` → `show()` → `TitledSwingWindow.show(...)`; `Main.main` runs the original standalone CLI (`server` / `viewer` / `display` modes) |
| Surface | **SwingNode-in-Frame3D** (480x380, hosts `RemoteViewerPanel`); the same panel is reused in the 2D desktop as an MDI internal frame |
| Start-menu name / group | Remote Viewer / **Developers** |
| Command | `java org.jdesktop.lg3d.apps.remoteviewer.RemoteViewer` |
| Descriptor | `src/config/remoteviewer.lgcfg` → `config/demo` |
| Engine | **JDK-only RMI remote desktop.** The server captures the screen with `java.awt.Robot` (via `server.main.robot`), injects remote mouse/keyboard, syncs the clipboard and serves file lists over an RMI registry (`server.rmi.Server` / `ServerImpl` / `ServerInterface`); the viewer (`viewer.rmi.Viewer` + `ViewerGUI` / `ScreenPlayer` / `Recorder`) connects, plays the remote screen and forwards input. Optional SSL uses a `keystore` / `truststore` and `MultihomeRMIClientSocketFactory`. Image compression is `ImageUtility` (JPEG/PNG); payloads are zipped by `ZipUtility` |
| Persistence | `.properties` files (`config`, `server.config`, `viewer.config`) plus `keystore` / `truststore`, all under **`~/.lg3d/remoteviewer/`** via `FileUtility.getConfigDirectory()` — **never** the working directory (see Engineer) |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **RemoteViewer** — thin 3D entry point; an idempotent singleton (`panel` /
  `frame`) that installs the hosted look and feel, shows `RemoteViewerPanel` in a
  `TitledSwingWindow` and wires `setOnClose` → `close()`, which does
  `frame.changeEnabled(false)` and clears the singletons so a later launch
  reopens. Never calls `System.exit`.
- **RemoteViewerPanel** — the one Swing UI (the ported jrdesktop `MainFrame`, no
  Java 3D): server start/stop, connection status, viewer connect, file transfer,
  about and exit. Exposes `setOnClose(Runnable)`; the confirmed-Exit action runs
  the package-private `exitApplication()` (stop the RMI server if bound, then the
  host callback), **never** `Main.exit()` / `System.exit`.
- **Main** — the original standalone launcher / controller: resolves the config
  paths (now `getConfigDirectory()`), starts the server / viewer, stores and
  loads the `.properties` config, and hosts `exit()` (the system-tray path) which
  now closes the hosted window via `RemoteViewer.close()` instead of exiting the
  JVM.
- **server.rmi.{Server, ServerImpl, ServerInterface}** — the RMI remote-desktop
  server. `Server` builds its screen-capture `robot` **lazily** (`robot()`), so
  loading the class to answer `isRunning()` needs no display.
- **server.main.robot** — the `java.awt.Robot` wrapper: screen capture, remote
  input injection and clipboard/file hand-off. Its constructor queries the screen
  size, which is exactly why `Server` defers building it.
- **viewer.{rmi.Viewer, main.ViewerGUI, main.ScreenPlayer, main.Recorder}** — the
  RMI client window: plays the remote screen, forwards input, transfers files
  (`viewer.main.FileMng`) and records sessions. `ViewerGUI`'s window-close always
  `dispose()`s (never `Main.exit()`).
- **Config (root / server.main / viewer.main), HostProperties, ConnectionInfos** —
  config beans persisted as `.properties`; **SysTray** — the optional AWT tray
  icon (server running/stopped, Open/Exit).
- **utilities** — `ClipbrdUtility`, `FileUtility` (`getCurrentDirectory` **and**
  `getConfigDirectory`), `ImageUtility`, `InetAdrUtility`, `ZipUtility`.

## Roles

- **Architect** — Everything lives in this package: the hosted `RemoteViewerPanel`
  + thin `RemoteViewer` wrapper, the standalone `Main` controller, and the ported
  jrdesktop `server` / `viewer` / `utilities` trees (kept in their upstream shape
  so the port stays recognisable). The same panel drives both desktops: 3D via
  `TitledSwingWindow`, 2D via `Desktop2DAppRegistry.PANEL_APPS` keyed on
  `RemoteViewer`. **No new dependency is added** — the remote desktop is JDK RMI +
  `java.awt.Robot`. The load-bearing boundary is the *host contract*: the panel is
  a guest inside the desktop JVM, so every close/exit path must go through
  `setOnClose`, and every persisted file must go through `getConfigDirectory()`.
- **Engineer / Developer** — **Never call `System.exit`** anywhere in this app: it
  is hosted inside the desktop JVM, so exiting tears down the whole session —
  route window closes through `setOnClose(Runnable)` (3D wires it directly, 2D
  reflectively by that exact signature) and `frame.changeEnabled(false)`. Persist
  config / keystore **only** under `FileUtility.getConfigDirectory()`
  (`~/.lg3d/remoteviewer`); `getCurrentDirectory()` resolves to the `lg3d-core`
  source tree inside the desktop and `viewer.config` / `server.config` hold a
  plaintext password, so writing there litters the checkout with a secret. Keep
  `Server`'s `robot` lazy and guard every `Robot` / RMI-bind / `JFileChooser` path
  so it is reached only from a user action, never a constructor or `isRunning()`,
  so the panel constructs headless. Use the Jogamp packages for any new 3D and
  obey the core UI/UX rulebook.
- **QA** — `RemoteViewerPanelTest` runs headless (`java.awt.headless=true`, no
  display, no RMI server, no `Robot`): it asserts the no-arg panel constructs and
  lays out its controls, the positive host size (`WIDTH_PX` / `HEIGHT_PX`), the
  presence of the `setOnClose(Runnable)` hook the reflective 2D wiring depends on,
  and — the key regression — that `exitApplication()` invokes the host close
  callback rather than killing the JVM. `Desktop2DAppRegistryTest#remoteViewerIsHostedPanel`
  pins the `RemoteViewer` → `RemoteViewerPanel` classification. For the 3D host
  use the in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`); a
  black capture under Wayland is not a defect.
- **Business Analyst** — Value = share and view a remote desktop over RMI with
  clipboard sync and file transfer, from inside either lg3d desktop — an honest
  port of a real remote-administration tool, not a stub. The "customer" is a
  developer/administrator who wants to expose or connect to a desktop session
  without leaving the lg3d shell.
- **Functional Analyst** — Spec this app as the *hosted remote-desktop contract*:
  start/stop an RMI screen-sharing server, connect a viewer to a host:port,
  transfer files and clipboard, and — critically — **Exit closes only this
  window** (stopping the server if bound), never the desktop. Config persists
  per-user under `~/.lg3d/remoteviewer` and survives a restart; the standalone
  `Main` CLI modes (`server` / `viewer` / `display`) remain available outside the
  desktop.
- **Project Manager** — Commit scope `lg3d-apps`; a registration or classification
  change also touches `lg3d-core` (`Desktop2DAppRegistry` + its test) — call that
  out. Done = `./gradlew :lg3d-apps:build` + headless tests + `./run-lg3d.sh`
  capture/log evidence. Branch → PR against `main`; never commit to `main`. Do not
  commit the runtime `config` / `*.config` / `keystore` artifacts or
  `lg3d-core/lgscreen-*.png`.
- **UI/UX (3D & 2D)** — 3D: a glassy `TitledSwingWindow` frame with the panel as
  the SwingNode content. 2D: the identical `RemoteViewerPanel` in an MDI internal
  frame. Keep the ported jrdesktop server/viewer layout, but the Exit control must
  read as "close this window", and closing it must never appear to quit the
  desktop; a bound server is stopped on close so no stale port stays held. Keep
  both surfaces pixel-identical.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the JDK-only RMI engine,
the exit-path invariant (never `System.exit`; use `setOnClose` +
`frame.changeEnabled(false)`), the `~/.lg3d/remoteviewer` config location and the
evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots, the
per-user config / keystore artifacts and stray downloads.
