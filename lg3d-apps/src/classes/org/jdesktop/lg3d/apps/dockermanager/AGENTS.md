# Docker Manager Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (port of the Swing IDE docker plugin) |
| Entry point | `DockerManager.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (hosts `DockerManagementPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Docker Manager / **Developers** |
| Command | `java org.jdesktop.lg3d.apps.dockermanager.DockerManager` |
| Descriptor | `src/config/docker.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **DockerManager** — thin entry point; installs the hosted look and feel and
  shows `DockerManagementPanel` in a `TitledSwingWindow`
  (`WIDTH_PX` × `HEIGHT_PX`).
- **DockerManagementPanel** — a plain `JPanel` (no-arg constructor, no Java 3D)
  ported verbatim from the Swing IDE docker plugin: a tabbed terminal /
  containers / files / images workspace that shells out to the system
  `docker` CLI. Every glyph is drawn through the bundled `IconManager`
  library, which is what preserves the plugin's exact appearance.
- **ContainerInfo / ImageInfo / ContainerTableModel / ImageTableModel /
  StatusCellRenderer / FileTreeCellRenderer** — the panel's supporting model
  and renderer classes, ported unchanged.

## Roles

- **Architect** — This app **is** the feature: the panel was ported into
  `lg3d-apps` (package `org.jdesktop.lg3d.apps.dockermanager`) rather than left
  in a plugin, so the same UI drives both the 3D and 2D desktops. The only
  external coupling is the bundled `IconManager` jar (compile-only here, on the
  `lg3d-core` run classpath at runtime) and the system `docker` executable.
  There is no bundled docker-java and no Java 3D in this package.
- **Engineer / Developer** — Do **not** restyle the panel: its look is the
  plugin's look (IconManager glyphs, tab order, button set). Follow the core
  UI/UX rulebook for the SwingNode host: offscreen paint, no modal dialogs
  escaping the capture (in-panel overlays), EDT hops from lg3d listeners,
  `cleanup()` on discard, hosted LAF via `installHostedLookAndFeel`. Keep the
  `DockerManagementPanel` constructors **non-throwing**: with no `docker` on the
  PATH the probe degrades to a readable "not running" status. Jogamp packages
  only for any new 3D code.
- **QA** — `DockerManagementPanelTest` (headless) asserts the no-arg and
  project-root constructors build without throwing, the panel lays out its
  tabs, and `cleanup()`/`refreshAll()` do not throw; `java.awt.headless=true`
  keeps it CI-safe with no display and no docker daemon. Real docker behaviour
  needs a live daemon: verify the hosted window renders and the CLI round-trips
  with an in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`); a
  black host capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver utility: manage containers, images and
  compose files from the desktop without installing a separate tool. Value = a
  trustworthy, familiar Docker workbench whose appearance matches the plugin
  users already know.
- **Functional Analyst** — Spec this app as the *docker workbench contract*:
  which tabs, which CLI verbs it shells out to, which start-menu slot
  (Developers group). The panel degrades honestly when docker is absent; never
  fake a "connected" state.
- **Project Manager** — Commit scope `lg3d-apps` for the app; adding it also
  touched `lg3d-core` (the `Desktop2DAppRegistry` panel mapping) and
  `lg3d-apps/build.gradle` (the IconManager compile/test dep) — call that out.
  Done = build + headless test + `./run-lg3d.sh` + capture/log evidence.
  Branch → PR against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the shared `DockerManagementPanel` (also used in the 2D desktop
  via `Desktop2DAppRegistry`) — keep it identical across both surfaces and
  identical to the source plugin. Verify overlay ordering over a maximized
  window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host), the appearance-preservation
constraint, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
