# Intel3D — EXCLUDED FROM BUILD

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Excluded from the Gradle build** (`exclude 'org/jdesktop/lg3d/apps/intel3d/**'`) — not compiled, not jarred, not runnable |
| Entry points | `intel3d.jini.services.JiniServiceServer`, `intel3d.workspace.JavaWorkSpace`, `intel3d.util.MemoryMonitorFrame` (each has `main`) |
| Surface | Jini-based distributed workspace / service framework (`conf/`, `jini/`, `lib/`, `util/`, `workspace/`) |
| Descriptor | `intel3d.lgcfg` exists but is **empty** (no config object); in any case it sits in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Missing dependencies | **Jini** (`net.jini.*`) — absent from the repo |
| Build | Excluded from `./gradlew :lg3d-incubator:build`; the legacy Ant build silently skipped it |

## Roles

- **Architect** — A Jini service/workspace framework (service server, Java workspace,
  memory monitor). It is **excluded** because `net.jini.*` is unavailable. Revival needs
  Jini reacquired and a distributed-services design re-validated against JDK 21.
- **Engineer / Developer** — **No code here compiles.** Do not import its packages into
  built code. A port is a dependency-acquisition plus JDK 21 / Jogamp re-verification
  effort, not a patch.
- **QA** — Out of scope: it is not built and its descriptor is empty, so there is nothing
  to test. Do **not** file "Intel3D does not appear / does not run" as a defect.
- **Business Analyst** — Historical distributed-workspace experiment. No product value in
  this port; the Jini middleware no longer exists.
- **Functional Analyst** — Record it as an excluded legacy app with its missing dependency
  (Jini) and empty descriptor; do not write an active functional spec.
- **Project Manager** — Excluded by design (root `AGENTS.md` *Incubator Exclusions*).
  Revival is a scoped project gated on Jini.
- **UI/UX (3D & 2D)** — Not applicable while excluded. A revival would follow the core
  glassy vocabulary and threading rules.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md` (which lists this exclusion). On conflict the higher file wins; fix here in
the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
