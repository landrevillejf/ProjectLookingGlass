# About

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** desktop utility (product identity, version, credits, system info) |
| Entry point | `About.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (Swing panel on a `SwingNode` quad under a glassy title bar) |
| Start-menu name / group | About / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.about.About` |
| Descriptor | `src/config/about.lgcfg` → `config/demo` |
| Icon | `resource:///resources/images/icon/lg3d-logo.png` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **About** — entry point; installs the hosted Metal LAF and shows the window.
- **AboutPanel** — Swing UI: logo, product name, version, description, runtime
  facts grid, attribution and licence.
- **AboutInfo** — headless model (no Swing/AWT): resolves the build version and
  the host runtime facts; unit-testable.

## Version resolution

`AboutInfo.getVersion()` never hardcodes a version literal. It reads the
`lg.version` system property (set to `project.version` by the `:lg3d-core:run`
task), falls back to the lg3d-apps jar manifest `Implementation-Version`
(stamped from `project.version` in `lg3d-apps/build.gradle`), and finally to
`unknown`. Both live paths read the single canonical `project.version`, so a
version bump needs no change here.

## Desktop-mode-aware content

One `AboutPanel` serves both desktops, so its text is mode-aware. `AboutInfo.is2D()`
reads the canonical `lg.fws.mode` property (reusing `DesktopMode.MODE_2D` /
`MODE_SWING`; no 3D probe, headless-safe) and the pure selectors
`tagline(boolean)` / `description(boolean)` / `credits(boolean)` /
`getFields(boolean)` pick the variant. On the conventional **2D/Swing desktop**
the About box must **not** advertise 3D or credit the ported-base lineage: it
shows a "desktop environment for the Java platform" tagline/description, omits
the **Java 3D** runtime row, and attributes the work solely to Jean-Francois
Landreville. The **3D desktop** keeps the full immersive-3D description, the
Java 3D row and the Sun Microsystems port lineage. Keep the two variants in
step with these rules; add both to `AboutInfoTest` when the text changes.

## Roles

- **Architect** — Consume `TitledSwingWindow`/`SwingNode` from `lg3d-core`;
  never re-implement window plumbing. Keep `AboutInfo` free of UI so it stays
  headless-testable, and source the version from the existing canonical
  reference (property → manifest) rather than a new literal.
- **Engineer / Developer** — Follow the core UI/UX rulebook: SwingNode paints
  offscreen with no layout pass, no modal dialogs, hop to the EDT from lg3d
  listeners, `dispose()` the node when discarded. Use Metal LAF
  (`installHostedLookAndFeel`) — Synth widgets NPE under SwingNode capture. The
  logo load must degrade gracefully (omit the image) if the asset is missing.
- **QA** — Unit-test `AboutInfo` headless (`src/test/java`, JUnit 5): the
  `resolveVersion`/`resolveJava3D` fallback branches are exercised directly.
  `AboutPanelTest` builds the panel headless. Verify the SwingNode view with the
  in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`); external
  capture tools return black under GNOME/Wayland.
- **Business Analyst** — Gives users and bug reports a single place to read the
  product version, the host runtime (Java, and Java 3D on the 3D desktop only,
  platform) and the attribution. Value = correct, honest, mode-appropriate credit
  plus fast diagnostics.
- **Functional Analyst** — Spec the panel as user-visible function (identity,
  version, runtime facts, credits, licence) plus the contract with core
  (SwingNode surface, descriptor fields, the `Desktop2DAppRegistry` PANEL entry).
- **Project Manager** — Commit scope `lg3d-apps`. Done = build + `./run-lg3d.sh`
  + screencapture/log evidence in the PR. Branch → PR against `main`; never
  commit to `main`.
- **UI/UX (3D & 2D)** — 3D: the glassy `TitledSwingWindow` frame. 2D: the Swing
  identity/system-info panel hosted in an MDI internal frame by
  `Desktop2DAppRegistry`. The layout must read cleanly at the hosted size and
  scroll if the frame is shrunk.

## Communication & coherence

Single source of truth: this file (app) → module `AGENTS.md` → core UI/UX
rulebook → root `AGENTS.md`. On conflict the higher file wins; fix here in the
same PR. Every PR states the surface (SwingNode), any descriptor change, and the
evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
