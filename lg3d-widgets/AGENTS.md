# AGENTS.md — lg3d-widgets

> Role-aware guide for everyone working on **lg3d-widgets**. The root
> [`../AGENTS.md`](../AGENTS.md) governs the build system, module map, Java 3D
> migration, exclusions and commit conventions; [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md)
> is the canonical **UI/UX** rulebook for the scene-graph toolkit. This file adds
> module-specific guidance and a shared per-role view so **all roles stay coherent**.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | The **pluggable desktop-widget framework** plus the built-in widgets. |
| Public API | `org.jdesktop.lg3d.widgets.api` (the `WidgetProvider` SPI). |
| Layer / host | `org.jdesktop.lg3d.widgets.host` (`WidgetLayerPlugin`, `SwingWidgetLayer`). |
| Built-ins | `org.jdesktop.lg3d.widgets.builtin` — clock, temperature, CPU, memory. |
| Also | `org.jdesktop.lg3d.widgets.gallery` (Widget Gallery), `org.jdesktop.lg3d.widgets.swing` (Swing cards). |
| Depends on | `lg3d-core` (which exposes the Jogamp Java 3D API and `org.jdesktop.lg3d.utils.system` Linux backends transitively). |
| Jar | `build-gradle/libs/lg3d-widgets.jar`, on the desktop classpath via `:lg3d-core:run`; `WidgetLayerPlugin` registered from `glassy.lgcfg`. |
| Build / test | `./gradlew :lg3d-widgets:build` · `:lg3d-widgets:test` (headless) · `:lg3d-widgets:pitest` (report-only). |

**Discovery is via the standard `java.util.ServiceLoader` SPI**
(`META-INF/services/...WidgetProvider`): a third party adds widgets by dropping a
jar on the desktop classpath — **no change to this module or to `lg3d-core`**.

## How the roles work together

This module is a **framework + a small widget set**. The Architect protects the
SPI contract and the pluggability guarantee; Engineers add widgets by extending
`AbstractWidget` and registering a descriptor (never by editing the gallery or
SPI file); QA runs the headless Swing-card tests and verifies the 3D layer with
the in-JVM probe; Analysts keep the "what makes a widget well-behaved" contract
explicit; the PM tracks that the framework is a public extension point. Everyone
works from this file plus the core UI/UX rulebook.

## Architect

- The **SPI is the contract.** `WidgetProvider` is discovered by `ServiceLoader`;
  keep it stable and backwards-compatible. Adding a built-in must not require
  touching the SPI file or `WidgetGallery` — the gallery auto-lists all
  descriptors from `BuiltinWidgetProvider.descriptors()`.
- Depend on `lg3d-core` only; do not reach into demo-apps or incubator. Shared
  utilities belong in `lg3d-core`.
- Threading is architectural: `scheduleTick` runs on a shared
  `ScheduledExecutorService` (`WidgetContext.scheduler()`), **not** the EDT/J3d
  thread. The design publishes results to `volatile` fields + `setDirty()`.
  Preserve that boundary.
- `WidgetLayerPlugin` registration in `glassy.lgcfg` and the run-classpath entry
  are load-bearing; changing either is an architecture decision.

## Engineer / Developer

To add a built-in widget:
1. Create a class in `org.jdesktop.lg3d.widgets.builtin` extending
   `AbstractWidget` (which extends `Component3D`). Constructor calls
   `super(ID, "Name")`.
2. In `init(WidgetContext)`: call `super.init(context)`, then
   `setSwingPanel(new MyPanel())` and `addListener(...)` for interaction; read
   persisted state via `getOption(key, def)`. In `start()`: call
   `scheduleTick(periodMillis, this::tick)`.
3. The UI is a nested inner class extending the package-private
   `WidgetPanel(title, width, height)`, overriding
   `paintContent(Graphics2D, int w, int h, int top)`. `WidgetPanel` paints the
   dark glassy card + title and exposes `CARD`/`OUTER`/`BORDER`/`TITLE_COLOR`/
   `TEXT`/`TEXT_DIM`.
4. Register it by adding a `WidgetDescriptor(ID, displayName, category,
   iconResource, w, h, Factory::new)` to `BuiltinWidgetProvider.descriptors()`.
   **No** change to the SPI file or `WidgetGallery` is needed.

Rules:
- Jogamp packages only; obey the core UI/UX rules (texture-before-attach, EDT
  hops). `scheduleTick` bodies may block (e.g. HTTP) — publish to `volatile`
  fields and `setDirty()`; never touch Swing off the EDT.
- Persist per-instance options with `setOption(key, value)`; read with
  `getOption(key, def)`.
- Icon resources resolve as `/resources/images/icon/<name>.png` (core runtime
  resources) — generate them with `lg3d-art/tools/GenerateAppIcons.java`, do not
  hand-place PNGs.
- Reference examples: `ClockWidget`, `TemperatureWidget`, `CpuWidget`,
  `WeatherWidget`.

## QA

- The pure-Swing cards, the 2D `SwingWidgetLayer` host and the gallery panel are
  plain Swing and unit-test **headless** (`java.awt.headless=true`; a
  `JDesktopPane`/`JPanel` needs no X display). Suites live in `src/test/java`.
- The 3D widget layer needs the live scene graph — verify with the **in-JVM
  probe + internal screencapture** described in the core rulebook (*Verifying UI
  changes*), not external capture (blocked under GNOME/Wayland).
- PIT is configured report-only (`mutationThreshold = 0`,
  `avoidCallsTo = ['java.awt', 'javax.swing']`): run `./gradlew
  :lg3d-widgets:pitest` and read the report; surviving mutants never fail the
  build. Coverage/mutation gates are the stated 100% / 0 target, not enforced.

## Business Analyst

- Value: **ambient, at-a-glance desktop information** (clock, temperature, CPU,
  memory) plus an extensible widget platform others can build on without forking
  the desktop.
- A widget earns a slot if it is genuinely glanceable and cheap to refresh; avoid
  widgets that need heavy blocking work on every tick.

## Functional Analyst

- Specify a widget by: **ID, display name, category, default size, refresh
  period, the data source it reads, the options it persists, and its click
  behaviour**. The descriptor + `AbstractWidget` lifecycle
  (`init` → `start` → `tick` → `setDirty`) is the functional contract.
- Keep the SPI extension story explicit: a functional requirement that "a third
  party can add a widget without editing core" is a hard constraint, not a
  nicety.

## Project Manager

- Commit scope is **`lg3d-widgets`**. Branch → commit → push → PR against `main`;
  never commit to `main`.
- Framework/SPI changes carry cross-module risk (any external widget jar depends
  on them) — schedule a compatibility review before landing.
- Done = build + headless tests green + (for a visible widget) a screencapture.

## UI/UX (3D & 2D)

- **2D (Swing card) UI** is the primary widget surface: each widget paints into
  a `WidgetPanel` (a Swing card) via `paintContent(Graphics2D, w, h, top)`, which
  the framework renders onto the desktop. Reuse the provided glassy palette
  (`CARD`/`BORDER`/`TITLE_COLOR`/`TEXT`/…) so widgets look consistent.
- **3D placement/hosting** — the widget layer is a `Component3D` in the scene
  graph (`WidgetLayerPlugin`); widgets are positioned by the host
  (`addWidgetAtFreeSpot(id)`). The 3D rules from the core rulebook
  (texture-before-attach, transparency ordering, EDT hops) apply to the layer.
- Repaint through `setDirty()` (which triggers a SwingNode re-capture); never
  mutate Swing state from a `scheduleTick` thread directly.
- Full core rules: [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md); Swing
  hosting: [`../docs/swingnode.md`](../docs/swingnode.md).

## Communication & coherence

- Single source of truth: this file (module), the core UI/UX rulebook (UI), the
  root `AGENTS.md` (build/exclusions/commits). Conflict → root wins, fix here in
  the same PR.
- Every PR states: the widget/API touched, whether the SPI changed, and the
  headless-test + (if visible) screencapture evidence.

## Commit / PR

- Conventional Commit scope **`lg3d-widgets`** (or `agents` for this file).
  Imperative subject ≤ 50 chars; body wrapped at 72.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version.
- Stage only intended paths (never `git add -A`; `lg3d-core/lgscreen-*.png` are
  runtime artifacts). Run branch → commit → push → PR against `main`.
