# Luncher (3D Card Launcher Menu)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Ported & live** (was excluded for API drift; ported with a jar-resource fallback) |
| Entry point | `luncher.Luncher1` (also `Luncher2`); `GlassyCubeTaskbarItem` (`Tapp`) is the taskbar variant |
| Surface | **pure-3D** card launcher (`GlassyCardMenu` extends `Container3D`, `TextPanel` extends `Shape3D`) |
| Start-menu name / group | Luncher / **Utilities** — descriptor **`lg3d-demo-apps/src/config/luncher.lgcfg`** → `config/demo` (**discovered**) |
| Command | `java org.jdesktop.lg3d.apps.luncher.Luncher1` |
| Runtime note | `MenuConfigFileReader` originally used `getResource("etc/lg3d/MenuConfigFile.xml")` (null → NPE); fixed to fall back to a class-relative/classpath resource |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A native-3D card launcher menu. Unlike most incubator prototypes it
  is **ported and registered** (its descriptor lives in `lg3d-demo-apps/src/config`).
  Keep the menu-config loading resilient: legacy `etc/`-relative paths are not
  installed by this port, so resource lookups must fall back to the jar/classpath.
- **Engineer / Developer** — Obey the core UI/UX rulebook (texture pixels before
  attach, `Component3D` wrapping, translucency sorting, EDT hops). Load menu config via
  a classpath/class-relative resource (never a bare `etc/` filesystem path). Dev mode
  routes no keyboard focus — click-driven only. Jogamp packages only.
- **QA** — Verify the menu opens and launches an entry (in-JVM probe + internal
  screencapture). Confirm no `NullPointerException` from menu-config loading (the
  original drift bug). A black host capture under Wayland is not a defect.
- **Business Analyst** — An alternative 3D launcher UX (card menu). Niche but
  functional; overlaps the desktop's built-in start menu.
- **Functional Analyst** — Spec user-visible function (open a card menu, pick an app to
  launch) plus the config-resource contract and the discovered descriptor location.
- **Project Manager** — Commit scope `lg3d-incubator`; the descriptor lives in
  `lg3d-demo-apps`, so a PR may span two modules — say so. Done = build +
  `./run-lg3d.sh` + capture/log evidence.
- **UI/UX (3D & 2D)** — **3D only**: glassy cards + text panels in a `Container3D`.
  Follow the glassy vocabulary, depth ordering and click-driven-input rules from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
