# Browser3D — EXCLUDED FROM BUILD

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Excluded from the Gradle build** (`exclude 'org/jdesktop/lg3d/apps/browser3d/**'`) — not compiled, not jarred, not runnable |
| Entry point | `browser3d.src.Browser3D.Browser3D` (a nested NetBeans project: `build.xml`, `manifest.mf`, `README.txt`, `UML/`) |
| Surface | Jini-based distributed 3D browser prototype (separate from the ICEsoft `browser` app) |
| Descriptor | **None** — no `.lgcfg` for browser3d in `src/config`, so even if built it would not reach the start menu |
| Missing dependencies | **Jini** (`net.jini.*`) — absent from the repo |
| Build | Excluded from `./gradlew :lg3d-incubator:build`; the legacy Ant build silently skipped it |

## Roles

- **Architect** — A Jini-service-oriented 3D browser prototype, packaged as its own nested
  NetBeans project (note the doubled `browser3d/src/Browser3D/` layout). It is **excluded**
  because `net.jini.*` is unavailable. Do not confuse it with the ICEsoft-based `browser`
  app — they are unrelated prototypes. Revival needs Jini reacquired and the nested-project
  layout flattened into the Gradle source set.
- **Engineer / Developer** — **No code here compiles.** Do not import its packages into
  built code. The nested NetBeans project layout does not match this module's single
  source set; a port is a rewrite plus a dependency-acquisition effort.
- **QA** — Out of scope: it is not built and has no descriptor, so there is nothing to
  test. Do **not** file "Browser3D does not appear / does not run" as a defect.
- **Business Analyst** — Historical distributed-browser experiment. No product value in
  this port; the Jini middleware no longer exists.
- **Functional Analyst** — Record it as an excluded legacy prototype with its missing
  dependency (Jini) and absent descriptor; do not write an active functional spec.
- **Project Manager** — Excluded by design (root `AGENTS.md` *Incubator Exclusions*).
  Revival is a scoped project gated on Jini and a layout/build rewrite.
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
