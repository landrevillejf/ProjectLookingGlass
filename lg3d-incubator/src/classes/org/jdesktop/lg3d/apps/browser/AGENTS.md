# Browser (3D Browser) — EXCLUDED FROM BUILD

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Excluded from the Gradle build** (`exclude 'org/jdesktop/lg3d/apps/browser/**'`) — not compiled, not jarred, not runnable |
| Entry point | `browser.Browser3DApp` (per `src/config/Browser.lgcfg`); also `BrowserApp`, `Browser3D`, `Browser3DComponent`, `Browser3DFactroy`, `Browser3DUIAdapter`, `HistoryItem` |
| Surface | 3D web browser built on the ICEsoft ICEbrowser HTML engine, with BeanShell scripting |
| Descriptor | `Browser.lgcfg` ("3D Browser", Early Prototypes) sits in `lg3d-incubator/src/config` → `config/` (**not scanned**), and the classes are excluded, so it can never launch |
| Missing dependencies | **ICEsoft ICEbrowser** (`com.icesoft.*`) and **BeanShell** (`bsh`) — absent from the repo |
| Build | Excluded from `./gradlew :lg3d-incubator:build`; the legacy Ant build silently skipped it |

## Roles

- **Architect** — A 3D web browser that renders HTML via the commercial ICEsoft ICEbrowser
  engine and scripts via BeanShell. It is **excluded**: neither `com.icesoft.*` nor `bsh`
  is present. Revival needs a modern embeddable HTML engine substituted for ICEbrowser —
  effectively a rewrite of the rendering back end. (Distinct from `browser3d`, a separate
  Jini-based prototype.)
- **Engineer / Developer** — **No code here compiles.** Do not import its packages into
  built code. A port means replacing the ICEbrowser/BeanShell dependencies and re-checking
  JDK 21 / Jogamp constraints — a project, not a patch.
- **QA** — Out of scope: it is not built, so there is nothing to test. Do **not** file
  "Browser does not appear / does not run" as a defect — exclusion is intentional.
- **Business Analyst** — Historical 3D-web concept tied to a discontinued commercial engine.
  No product value in this port.
- **Functional Analyst** — Record it as an excluded legacy app with its missing-dependency
  list; do not write an active functional spec.
- **Project Manager** — Excluded by design (root `AGENTS.md` *Incubator Exclusions*).
  Revival is a scoped project gated on sourcing a replacement HTML/scripting engine.
- **UI/UX (3D & 2D)** — Not applicable while excluded. A revival would follow the core
  glassy vocabulary, SwingNode contract and threading rules.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md` (which lists this exclusion). On conflict the higher file wins; fix here in
the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
