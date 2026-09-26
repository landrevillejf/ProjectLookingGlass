# FTP Client Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).
> The panel it hosts belongs to the [`ftp-client`](../../../../../../../../ftp-client/AGENTS.md) module.

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (host shim for the `ftp-client` panel) |
| Entry point | `FtpClient.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (hosts `FtpClientPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | FTP Client / **Internet** |
| Command | `java org.jdesktop.lg3d.apps.ftpclient.FtpClient` |
| Descriptor | `src/config/ftpclient.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **FtpClient** — thin entry point; installs the hosted look and feel and shows
  the `ftp-client` module's `FtpClientPanel` in a `TitledSwingWindow`
  (`FtpClientMainPanel.WIDTH_PX` × `HEIGHT_PX`).
- **FtpClientPanel** — a plain `JPanel` (no-arg constructor, no Java 3D) that
  embeds the `ftp-client` module's `FtpClientMainPanel` (site profiles, dual
  local | remote browser, transfer queue with retry/resume/cancel over FTP,
  FTPS and SFTP). This app only hosts it.

## Roles

- **Architect** — This is a **hosting shim**, not the feature: the transfer
  client and panel live in the `ftp-client` module. Keep the boundary —
  `lg3d-apps` supplies the `Frame3D`/`TitledSwingWindow` 3D host, `ftp-client`
  supplies the panel so the same UI drives both the 3D and 2D desktops. Do not
  fork transfer/protocol logic here.
- **Engineer / Developer** — Follow the core UI/UX rulebook for the SwingNode
  host: offscreen paint, no modal dialogs escaping the capture (in-panel
  overlays), EDT hops from lg3d listeners, `dispose()` on discard, hosted LAF
  via `installHostedLookAndFeel`. Keep the `FtpClientPanel` constructor
  **non-throwing**: it catches `RuntimeException` / `LinkageError` and degrades
  to a readable "unavailable" pane so a broken bundle cannot take down the host
  window. Behavioural changes belong in `ftp-client`, not here. Jogamp packages only.
- **QA** — `FtpClientPanelTest` (headless) asserts the panel constructs without
  throwing, embeds the real client (ftp-client is on this module's compile
  classpath), and reports the advertised host size; it isolates the config dir
  via `ProfileStore.DIR_PROPERTY`. The transfer/protocol unit tests (in-JVM
  MockFtpServer + mocked JSch) live in the `ftp-client` module. Here, also verify
  the hosted window renders and delegates: in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black host capture under Wayland is not a
  defect. Check the desktop log for `EventProcessor` warnings.
- **Business Analyst** — A daily-driver utility: move files to/from any FTP,
  FTPS or SFTP server from the desktop without installing a separate tool. Value
  = a trustworthy, resumable transfer client; the connect/browse/transfer and
  credential-handling flow is the product surface and lives in `ftp-client`.
- **Functional Analyst** — Spec this app as the *host contract* (which panel,
  which window chrome, which start-menu slot: Internet group). The functional
  spec for transfer behaviour is owned by `ftp-client/AGENTS.md`; keep the two
  coherent and cross-referenced.
- **Project Manager** — Commit scope `lg3d-apps` for this host; changes to
  transfer behaviour are a separate `ftp-client` PR. Adding the app also touched
  `lg3d-core` (the `Desktop2DAppRegistry` panel mapping + the run/releaseBundle
  classpath + the start-menu icon) — call that out. Done = build +
  `./run-lg3d.sh` + capture/log evidence. Branch → PR against `main`; never
  commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the shared `FtpClientPanel` (also used in the 2D desktop via
  `Desktop2DAppRegistry`) — keep it identical across both surfaces. Verify
  overlay ordering over a maximized window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`; transfer behaviour → `ftp-client/AGENTS.md`. On conflict the
higher file wins; fix here in the same PR. Every PR states the surface (SwingNode
host), the panel/module boundary, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
