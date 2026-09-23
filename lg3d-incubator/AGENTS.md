# AGENTS.md — lg3d-incubator

> Role-aware guide for everyone working on **lg3d-incubator**. The root
> [`../AGENTS.md`](../AGENTS.md) governs the build system, module map, Java 3D
> migration, exclusions and commit conventions; [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md)
> is the canonical **UI/UX** rulebook for the scene-graph toolkit. This file adds
> module-specific guidance and a shared per-role view so **all roles stay coherent**.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | A **grab-bag of independent experimental apps** — the community/prototype "incubator". |
| Root package | `org.jdesktop.lg3d.apps.*` (sources under `src/classes`). |
| Depends on | `lg3d-core` **plus** bundled third-party jars under `ext/` (`fileTree ext/**/*.jar`). |
| Depended on by | Nothing. Does **not** depend on `lg3d-demo-apps`. |
| Jar | `build-gradle/libs/lg3d-incubator-1.0.1-dev.jar`, on the desktop classpath via `:lg3d-core:run`. |
| Build | `./gradlew :lg3d-incubator:build`. |
| Legacy build | The Ant build was a per-app `subant` with `failonerror="false"`; this port compiles the whole `src/classes` tree as one source set. |

**Also ships the background manager** (`org.jdesktop.lg3d.apps.bgmanager`) whose
`BgConfig.xml` and per-background directories are assembled into the runtime
`resources/Backgrounds/` tree by `:lg3d-core:runtimeResources`.

**Native-3D apps authored for the port** (live-texture pattern): Image Studio,
Agenda 3D, the Games (tictactoe / sudoku / chess / solitaire), Mail 3D.
**Ported legacy apps:** jmf23D (Algea3D), luncher, nlc, orgchart (Chart3D /
Contact3D). Their start-menu `.lgcfg` descriptors live in
**`lg3d-demo-apps/src/config`**, not here (see below).

> **Per-app guides.** Every application package under `src/classes` ships its own
> condensed role-aware `AGENTS.md` next to its sources (35 in total), covering the
> native-3D showcases, the ported apps, the dormant prototypes, the framework/library
> trees (`edu/cmu/sun`, `org/jdesktop/lg3d/utils`, `apps/utils`), and the **excluded**
> apps (`archviz3d`, `browser`, `browser3d`, `intel3d`, `wilkoaim3d`, `nu/koidelab/cosmo`).
> Read an app's `Status` row first: it says whether the code is live, dormant,
> library-only, or excluded-from-build, and where its descriptor lives.

## How the roles work together

The incubator is **high-variance**: some apps are modern native-3D showcases,
others are 2006-era prototypes that only compile. The Architect guards the
exclusion policy and the `ext/` dependency surface; Engineers follow the core UI
rules and the live-texture discipline; QA separates "compiles/registers" from
"actually runs" (many prototypes need hardware/libraries that are absent);
Analysts keep the exclusion rationale honest; the PM tracks which apps are live
versus dormant. Everyone works from this file plus the core UI/UX rulebook.

## Architect

- **Descriptor location is architectural.** Discovery scans `config/demo` and
  `config/incubator`; this module's `src/config` bundles to jar-root `config/`
  (never scanned). So an incubator app's `.lgcfg` must be added to
  `lg3d-demo-apps/src/config`. Keep this precedent (Image Studio, Widget Gallery).
- This module cannot see `lg3d-demo-apps` classes (no dependency). A helper
  needed by both must live in `lg3d-core` — e.g. do not try to reuse
  `TitledSwingWindow` from here.
- **Exclusions are policy, not accidents.** Apps excluded for absent third-party
  libraries: `nu/koidelab/**` (Cosmo), `archviz3d`, `intel3d`, `browser`,
  `browser3d`. Excluded for deep core-API drift: `wilkoaim3d` (removed core
  vocabulary + a dead AOL AIM backend). Do not re-enable an excluded app without
  re-verifying its real blocker (a "missing library" rationale has been wrong
  before — `ext/jaimlib.jar` already supplied `com.wilko.jaim`).
- `ext/` jars are on the compile classpath; only some are needed at runtime, so
  `:lg3d-core:run` adds the specific ones (`jai_core`, `jai_codec`, `jmf`,
  `nanoxml-lite`, `javanlp`, `prefuse`, …). Adding a new `ext/` dependency is an
  architecture decision — check whether the runtime classpath and any
  `--add-exports` (JAI needs `java.desktop/sun.awt.image`) must change too.

## Engineer / Developer

- Obey every rule in [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md): Jogamp
  packages only; **upload texture pixels before attaching**; wrap raw `Node`s in
  `Component3D`; sort translucency yourself; hop to the EDT from listeners.
- **Live-texture rule (the native-3D app pattern):** attach one fixed-size
  `ImageComponent2D` with `ALLOW_IMAGE_WRITE` to a `Texture2D` **once, off-live**;
  every edit only repaints the `BufferedImage` and calls `ImageComponent2D.set`
  in place — never re-attach a texture to the live graph. Board/grid quads set
  `Geometry.ALLOW_INTERSECT` so `PICK_GEOMETRY` maps a click to a cell.
- **Dev mode routes no keyboard focus to a `Frame3D`** — native-3D apps must be
  click/button-driven (see Mail 3D, Games). Reuse the runtime-drawn
  `AgendaButton` control idiom rather than expecting typing.
- Keep the game/app **model** plain-Java (no AWT) so it unit-tests headless; the
  `Component3D` view and `Frame3D` host are verified with the in-JVM probe.
- **Cross-app data sharing uses the shared user `Preferences` tree**, not
  ServiceContext/Channel. Agenda 3D / Mail 3D read the `/contacts` node Contact
  3D populates; Mail 3D persists under `/mail/messages`. Never write another
  app's node.
- JAI image I/O: use `javax.imageio.ImageIO` for PNG/JPEG; reserve the JAI codec
  for TIFF/BMP (JAI's JPEG encoder references the JDK-removed
  `com.sun.image.codec.jpeg`).

## QA

- Distinguish three states: **excluded** (does not compile), **compiles +
  registers** (start-menu entry present), and **actually runs**. Many prototypes
  (jmf23D needs JMF hardware, nlc needs a microphone/speech engine) only reach
  the middle state — say so explicitly in the PR rather than claiming a working
  app.
- Verify UI with lg3d's **internal screencapture** (`lg3d-core/lgscreen-*.png`);
  external capture is blocked under GNOME/Wayland. Headless JUnit covers the
  plain-Java models; the 3D view uses the in-JVM probe.
- Watch for swallowed `EventProcessor` exceptions and the texture-NPE class of
  "invisible panel" bugs.
- Coverage/mutation gates are the stated 100% JaCoCo / 0 PIT target, currently
  **report-only**.

## Business Analyst

- The incubator is a **portfolio of experiments**, not committed product. Its
  value is demonstrating what the desktop can host and preserving historically
  interesting apps.
- Treat the native-3D apps (Image Studio, Agenda 3D, Games, Mail 3D) as the
  supported showcase; the dormant prototypes have niche/nostalgic value only.

## Functional Analyst

- For each live app, specify behaviour as **user-visible function + core
  contract** (Frame3D host, live-texture view, Preferences node, descriptor
  fields, and where the descriptor lives).
- Keep the **exclusion rationale** as a living functional document: each excluded
  app records *why* (missing lib vs API drift) so a future port can re-triage.
  Correct stale rationales when evidence contradicts them.

## Project Manager

- Commit scope is **`lg3d-incubator`**. Branch → commit → push → PR against
  `main`; never commit to `main`.
- Because descriptors for incubator apps land in `lg3d-demo-apps`, an app PR
  often spans two modules — call that out and expect a `lg3d-demo-apps` change
  in the same PR.
- Track runtime classpath / `ext/` changes as integration risk (they touch
  `lg3d-core`'s `run` task).

## UI/UX (3D & 2D)

- **3D (native, pure-`Frame3D`) UI** is the dominant surface here: Image Studio,
  Agenda 3D, the Games and Mail 3D draw their entire UI as scene-graph nodes
  (canvas, toolbar, slider, histogram, filmstrip, week grid, cards) with
  runtime-drawn buttons and no PNG assets. Build from the glassy vocabulary;
  follow the live-texture and click-driven-input rules above. Full rules:
  [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md) and
  [`../docs/lg3d-native-apps.md`](../docs/lg3d-native-apps.md).
- **2D (Swing) UI** appears only where an app needs real Swing widgets (e.g. the
  native file dialogs Image Studio uses). If you host a Swing panel, use a
  `SwingNode` per [`../docs/swingnode.md`](../docs/swingnode.md) — but remember
  this module cannot reuse demo-apps' `TitledSwingWindow`.
- Transparency/overlay ordering and the eye-distance sort apply exactly as in the
  core rulebook; verify occlusion with a capture, not numeric Z.

## Communication & coherence

- Single source of truth: this file (module), the core UI/UX rulebook (UI), the
  root `AGENTS.md` (build/exclusions/commits). Conflict → root wins, fix here in
  the same PR.
- Every PR states: the app touched, live vs dormant status, the descriptor
  location (`lg3d-demo-apps/src/config`), any `ext/`/classpath change, and the
  screencapture/log evidence.

## Commit / PR

- Conventional Commit scope **`lg3d-incubator`** (or `agents` for this file).
  Imperative subject ≤ 50 chars; body wrapped at 72.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version.
- Stage only intended paths (never `git add -A`; `lg3d-core/lgscreen-*.png` are
  runtime artifacts). Run branch → commit → push → PR against `main`.
