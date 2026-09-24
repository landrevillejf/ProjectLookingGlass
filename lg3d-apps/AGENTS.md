# AGENTS.md — lg3d-apps

> Role-aware guide for everyone working on **lg3d-apps**. The root
> [`../AGENTS.md`](../AGENTS.md) governs the build system, module map, Java 3D
> migration, exclusions and commit conventions; [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md)
> is the canonical **UI/UX** rulebook for the scene-graph toolkit. This file adds
> module-specific guidance and a shared per-role view so **all roles stay coherent**.

> **Naming note (important).** This module was **renamed from the legacy
> `lg3d-demo-apps`** — "demo" undersold it. The applications in it are
> **production-grade desktop software**, not throwaway demos: Calculator, Media
> Writer, Paint, File Manager, Task Manager, Control Center, Help Center and
> Software Update are the shipped daily-driver utilities, and `TitledSwingWindow`
> is the production host other modules reuse. Treat this module with normal
> production rigor (tests, review, backward compatibility). Only a few packages
> are genuinely tutorial/sample code (`tutorial`, `swingnode`, `swingtest`,
> `graph`, `tapps`, `callviewer`, `cdviewer`, `launcher`); each app's own
> `AGENTS.md` says which. The `config/demo` runtime resource path and the `Demos`
> start-menu group are still legacy names — keep them as-is (renaming them would
> break lg3d-core's descriptor discovery).

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | The **production desktop applications** shipped with lg3d (plus a few tutorial/sample apps), and the reference hosts other modules reuse. Formerly the misleadingly-named `lg3d-demo-apps`; the software is production-grade. |
| Root package | `org.jdesktop.lg3d.apps.*` (sources under `src/classes`). |
| Depends on | `lg3d-core` (SDK + transitive Jogamp Java 3D API). |
| Depended on by | Nothing (top of the app chain). **Not** visible to `lg3d-incubator`. |
| Jar | `build-gradle/libs/lg3d-apps-1.9.0-dev.jar`, placed on the desktop classpath by `:lg3d-core:run`. |
| Build | `./gradlew :lg3d-apps:build`. |
| Descriptors | `src/config/*.lgcfg` bundled to `config/demo` (discovery scans `config/demo` + `config/incubator`). |

**Apps in this module:** calculator, callviewer, cdviewer, controlcenter,
dbmanager, filemanager, graph, help, launcher, mediawriter, paint, screencapture,
swingnode, swingtest, tapps, taskmanager, terminator, tutorial, update — plus
the shared reference host `TitledSwingWindow.java`.

**This module is also the descriptor home for several incubator apps** (Image
Studio, Agenda 3D, the Games, Mail 3D, and the ported jmf23D/luncher/nlc/
orgchart apps): their `.lgcfg` files live in `src/config` because the incubator's
own `src/config` bundles to `config/`, which discovery does **not** scan.

> **Per-app guides.** Every application package under `src/classes` ships its own
> condensed role-aware `AGENTS.md` next to its sources (19 in total). Read an app's
> `Status` row first: it separates the **Production** daily-driver utilities from the
> genuine **Tutorial / sample** packages listed above, so the module's history never
> misleads you about an individual app's rigor.

## How the roles work together

This module ships the desktop's **production applications** (it was renamed from
the misleading `lg3d-demo-apps`): it is both the daily-driver utility suite and
the reference that proves the `lg3d-core` toolkit works, giving other modules
copy-from hosts
(`TitledSwingWindow`, the Calculator/MediaWriter SwingNode panels). The Architect
keeps apps consuming core APIs
rather than forking them; Engineers follow the core UI rules verbatim; QA
verifies with the internal screencapture; Analysts keep the "what an app must do
to be start-menu ready" contract explicit; the PM tracks that a descriptor added
here may belong to an incubator app. Everyone works from this file plus the core
UI/UX rulebook.

## Architect

- Apps **consume** `lg3d-core`; they never re-implement window/scene plumbing.
  A helper needed by both `lg3d-apps` and incubator must live in `lg3d-core`
  (incubator does not depend on `lg3d-apps`, so `TitledSwingWindow` is invisible
  there — see root dependency-direction note).
- Two window paths only: **pure-3D `Frame3D`** and **`SwingNode`-hosted Swing**.
  Dev mode (`lg.fws.mode=dev`) exercises the pure-3D/SwingNode paths; the native
  X11 path is excluded.
- `TitledSwingWindow` is the **reference** for hosting a Swing panel in a
  `Frame3D` (title strip, gesture handle, decoration alignment). New
  Swing-in-3D apps should reuse the pattern, not invent a new one.
- Start-menu registration is architectural: descriptor location (`src/config` →
  `config/demo`) determines discoverability. Keep this rule intact.

## Engineer / Developer

- Obey every rule in [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md), above
  all: **upload texture pixels before attaching**, wrap raw `Node`s in
  `Component3D`, sort translucency yourself, and hop to the EDT before touching
  Swing (lg3d listeners run on the EventProcessor/J3dThread).
- Jogamp packages only (`org.jogamp.java3d`, `org.jogamp.vecmath`); never
  `javax.media.j3d` / `javax.vecmath`.
- **SwingNode panels are painted offscreen with no layout pass** — use a null
  layout with explicit bounds (see Calculator / MediaWriter). Modal dialogs
  escape the offscreen capture: use in-panel overlays for file picking and
  confirmation.
- To add an app: create the package under `src/classes/.../apps/<name>`, add a
  `src/config/<name>.lgcfg` (`command = java <MainClass>`, `menuGroup`, `name`,
  `desc`, `displayResourceUrlName = resource:///resources/images/icon/....png`),
  and, for a 2D-desktop-hosted Swing panel, register the command in
  `Desktop2DAppRegistry.PANEL_APPS` (in `lg3d-core`).
- Icons are generated by `lg3d-art/tools/GenerateAppIcons.java` into
  `lg3d-core/src/resources/images/icon/`; do not hand-place PNGs.

## QA

- Verify with lg3d's **internal screencapture** (`lg3d-core/lgscreen-0-0.png`);
  external tools (`import`, `scrot`, AWT `Robot`) return black/hang under
  GNOME/Wayland + Xwayland. See the core rulebook *Verifying UI changes*.
- Headless JUnit 5 suites live in `src/test/java` (`useJUnitPlatform()`,
  `java.awt.headless=true`). Pure-logic engines (e.g. the Calculator expression
  engine, MediaWriterEngine) unit-test headless; the 3D/SwingNode view is
  verified with the in-JVM probe + capture.
- Read the desktop log for `EventProcessor` warnings before calling an
  interaction broken — most "does nothing" reports are a swallowed exception
  (often the texture NPE).
- Coverage/mutation gates are the stated 100% JaCoCo / 0 PIT target, currently
  **report-only**. Report real evidence, not "looks fine".

## Business Analyst

- Most of these apps are **production-grade daily-driver utilities** (calculator,
  media writer, paint, file manager, task manager, control center, help center,
  software update). They ship to end users, so hold them to production standards:
  real tests, review, and backward compatibility — not "it's just a demo".
- A minority are genuine tutorial/sample code (`tutorial`, `swingnode`,
  `swingtest`, `graph`, `tapps`, `callviewer`, `cdviewer`, `launcher`); their
  value is teaching the toolkit, and their per-app `AGENTS.md` marks them as such.
- New apps should justify a start-menu slot and a `menuGroup`; an app nobody can
  discover is wasted effort.

## Functional Analyst

- Define each app's behaviour in terms of **user-visible function** (what the
  panel does, what a click produces) and the **contract with core** (Frame3D vs
  SwingNode, descriptor fields, PANEL_APPS registration for the 2D desktop).
- For shared-reference apps (`TitledSwingWindow`, Calculator, MediaWriter), the
  functional spec is also the template other modules copy — keep it explicit and
  stable.

## Project Manager

- Commit scope is **`lg3d-apps`**. Branch → commit → push → PR against
  `main`; never commit to `main`.
- A PR that adds a descriptor here for an *incubator* app must say so (the split
  is intentional and easy to mis-file).
- Track desktop-runtime verification as part of done: build + `./run-lg3d.sh` +
  a screencapture in the PR.

## UI/UX (3D & 2D)

Both surfaces are in scope here:

- **3D (pure-`Frame3D`) UI** — build from the glassy vocabulary
  (`GlassyPanel`, `GlassyText2D`, `SimpleAppearance`, `Component3D`), origin at
  window centre, sizes derived as fractions of screen `W`/`H`, small positive
  `z` offsets to avoid z-fighting, `Cursor3D` on interactive components, and
  event-adapter + action wiring. Full rules: [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md)
  and [`../docs/lg3d-native-apps.md`](../docs/lg3d-native-apps.md).
- **2D (Swing) UI** — Swing content is hosted on a `SwingNode` inside a
  `Frame3D` via `TitledSwingWindow`, or as an MDI internal frame in the 2D
  desktop via `Desktop2DAppRegistry.PANEL_APPS`. The **same panel** should drive
  both. Null layout with explicit bounds; no modal dialogs (use in-panel
  overlays); `dispose()` the node when discarded. Full contract:
  [`../docs/swingnode.md`](../docs/swingnode.md).
- Transparency/overlay ordering and the eye-distance sort are the top source of
  "my panel is behind the window" bugs — follow the core rulebook's depth/overlay
  section and verify with a capture over a maximized window.

## Communication & coherence

- Single source of truth: this file (module), the core UI/UX rulebook (UI), the
  root `AGENTS.md` (build/exclusions/commits). Conflict → root wins, fix here in
  the same PR.
- Every PR states: the app touched, Frame3D vs SwingNode surface, the descriptor
  change (if any), and the screencapture/log evidence.

## Commit / PR

- Conventional Commit scope **`lg3d-apps`** (or `agents` for this file).
  Imperative subject ≤ 50 chars; body wrapped at 72.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version.
- Stage only intended paths (never `git add -A`; `lg3d-core/lgscreen-*.png` are
  runtime artifacts). Run branch → commit → push → PR against `main`.
