# Changelog

All notable changes to Remoteviewer are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Remote Viewer** — a
  port of the standalone jrdesktop / Remote Viewer RMI remote-desktop tool,
  hosting its original `MainFrame` GUI (server start/stop, status, viewer
  connect, file transfer, about, exit) unchanged as `RemoteViewerPanel`: a
  SwingNode-in-`Frame3D` host in 3D via `TitledSwingWindow` and an MDI internal
  frame in 2D via `Desktop2DAppRegistry.PANEL_APPS`. Registered as the
  *Developers* start-menu app `remoteviewer.lgcfg` with a purpose-drawn
  `remoteviewer.png` icon.

### Fixed

- **The Remote Viewer's Exit button no longer tears down the whole desktop** — the ported jrdesktop GUI
  is hosted inside the lg3d desktop JVM (a 3D `Frame3D` via `TitledSwingWindow`
  and a 2D MDI internal frame via `Desktop2DAppRegistry.PANEL_APPS`), but its
  Exit button — together with the system-tray "Exit" and the
  standalone `ViewerGUI` window-close — still ended in `System.exit(0)`, so
  quitting the Remote Viewer killed the entire desktop session instead of just
  its window. `RemoteViewerPanel` now exposes the canonical
  `setOnClose(Runnable)` host hook (wired directly by the 3D `RemoteViewer`
  wrapper and reflectively by the 2D `Desktop2DAppRegistry.setCloseCallback`);
  its confirmed-Exit action runs `exitApplication()`, which stops the RMI server
  if it is bound and then delegates the close to the host callback
  (`RemoteViewer.close()` → `frame.changeEnabled(false)` plus a singleton reset
  so a later launch reopens), never `System.exit`. `Main.exit()` (the tray path)
  now closes the hosted window and `ViewerGUI`'s close always `dispose()`s.
  The same fix relocates the app's persistent config / keystore off the process
  working directory — which inside the desktop is the `lg3d-core` source tree,
  where `viewer.config` / `server.config` were landing a **plaintext password**
  in the checkout — to per-user `~/.lg3d/remoteviewer/` , and makes `Server`'s screen-capture
  `robot` lazy so merely loading `Server` (e.g. the panel's status refresh
  calling `isRunning()`) no longer requires a display / throws
  `HeadlessException`. Covered by headless JUnit 5 tests (`RemoteViewerPanelTest`
  pins construction, the host size, the `setOnClose` contract and that Exit runs
  the host callback rather than the JVM; a
  `Desktop2DAppRegistryTest#remoteViewerIsHostedPanel` classification case).

- **The Remote Viewer's live-viewing window is now hosted inside the desktop**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.remoteviewer`; new core API in
  `lg3d-core`, `org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D`) — the main
  panel was already integrated, but on Connect the viewer opened its
  live-viewing UI as a `ViewerGUI extends JFrame` created eagerly by `Recorder`.
  A top-level `JFrame` is an OS window owned by the host window manager: it is
  neither a child of the 2D desktop's `JDesktopPane` nor part of the 3D scene, so
  the internal screen capture (which paints each top-level `Frame` into its own
  `lgscreen-<i>-*.png`) never showed the viewer in the desktop's
  `lgscreen-0-0.png`. The viewer content is extracted into a plain
  `ViewerPanel extends JPanel` (toolbar + `ScreenPlayer`, no window chrome) that
  a new `ViewerHost` embeds in whichever desktop is running: a 2D MDI internal
  frame via the new reusable `Desktop2D.openHostedPanel(title, icon, content)`
  (which returns null when no 2D desktop is live, so it is headless- and
  unit-test-safe), else a 3D `Frame3D` via `TitledSwingWindow`, else — standalone
  CLI `viewer` mode only — the thin `ViewerGUI` `JFrame` wrapper. `Recorder` no
  longer opens a window at construction (it just builds `viewerPanel`), and
  `Viewer.Start()` calls `ViewerHost.show(recorder)` then
  `recorder.viewerPanel.startRecording()`. The panel reports state upward through
  three host hooks that
  each host maps onto its own window idiom, so Close never disposes a stray frame
  or kills the JVM; **full-screen becomes maximize-within-desktop when hosted**
  (2D `JInternalFrame.setMaximum`, 3D `HostedWindowResizer.resize`), keeping
  exclusive OS full-screen for the standalone `JFrame` only. Supporting
  headless-safety: `ClipbrdUtility` initialises the system clipboard lazily,
  `HostProperties.getLocalProperties()` skips `Toolkit` screen metrics headless,
  and `ScreenPlayer` skips its `DropTarget` headless. Covered by headless JUnit 5
  tests (`ViewerPanelTest` pins that constructing the panel creates **no
  top-level `Window`** — the regression that caused this bug — plus the host-hook
  contract and that Close/maximize delegate to the host callback;
  `Desktop2DHostedPanelTest` asserts `openHostedPanel` returns null with no 2D
  instance).
