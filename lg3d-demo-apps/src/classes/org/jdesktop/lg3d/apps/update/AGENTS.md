# Software Update Application

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).
> The panel it hosts belongs to the [`update-manager`](../../../../../../../../update-manager/AGENTS.md) module.

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (host shim for the `update-manager` panel) |
| Entry point | `UpdateManager.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (hosts `UpdateManagerPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Software Update / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.update.UpdateManager` |
| Descriptor | `src/config/updatemanager.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-demo-apps:build` |

## Key components

- **UpdateManager** — thin entry point; installs the hosted Metal LAF and shows
  the `update-manager` module's `UpdateManagerPanel` in a `TitledSwingWindow`.
- **UpdateManagerPanel** — the actual UI + logic, owned by the `update-manager`
  module (check-for-updates, install, progress). This app only hosts it.

## Roles

- **Architect** — This is a **hosting shim**, not the feature: the update logic and
  panel live in the `update-manager` module. Keep the boundary — demo-apps supplies
  the `Frame3D`/`TitledSwingWindow` 3D host, `update-manager` supplies the panel so
  the same UI drives both the 3D and 2D desktops. Do not fork update logic here.
- **Engineer / Developer** — Follow the core UI/UX rulebook for the SwingNode host:
  offscreen paint (null layout + explicit bounds inside the panel), no modal dialogs
  (in-panel overlays), EDT hops from lg3d listeners, `dispose()` on discard, Metal
  LAF via `installHostedLookAndFeel`. Behavioural changes belong in `update-manager`,
  not here. Jogamp packages only.
- **QA** — Unit tests for update logic live in the `update-manager` module. Here,
  verify the hosted window renders and delegates: in-JVM probe + internal
  screencapture (`lg3d-core/lgscreen-*.png`); a black host capture under Wayland is
  not a defect. Check the desktop log for `EventProcessor` warnings.
- **Business Analyst** — A daily-driver utility: check for and install desktop
  updates. Value = keeping the desktop current; the trust/install flow is the
  product surface and lives in `update-manager`.
- **Functional Analyst** — Spec this app as the *host contract* (which panel, which
  window chrome, which start-menu slot). The functional spec for update behaviour is
  owned by `update-manager/AGENTS.md`; keep the two coherent and cross-referenced.
- **Project Manager** — Commit scope `lg3d-demo-apps` for this host; changes to
  update behaviour are a separate `update-manager` PR. Done = build + `./run-lg3d.sh`
  + capture/log evidence. Branch → PR against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the shared `UpdateManagerPanel` (also used in the 2D desktop) —
  keep it identical across both surfaces. Verify overlay ordering over a maximized
  window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`; update behaviour → `update-manager/AGENTS.md`. On conflict the
higher file wins; fix here in the same PR. Every PR states the surface (SwingNode
host), the panel/module boundary, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-demo-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
