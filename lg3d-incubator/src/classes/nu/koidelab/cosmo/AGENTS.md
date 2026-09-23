# Cosmo (CosmoSchedulerD) — EXCLUDED FROM BUILD

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Excluded from the Gradle build** (`exclude 'nu/koidelab/**'`) — not compiled, not jarred, not runnable |
| Entry point | `nu.koidelab.cosmo.CosmoSchedulerD` (per `src/config/cosmo.lgcfg`) |
| Surface | Distributed scheduler (Jini/JavaSpaces service) — historically a 3D-fronted Office-group app |
| Descriptor | `cosmo.lgcfg` (Office group) sits in `lg3d-incubator/src/config` → `config/` (**not scanned**), and the classes are excluded, so it can never launch |
| Missing dependencies | **Jini / JavaSpaces** (`net.jini.*`), **JGL** (`com.objectspace.jgl`), **SATIN** — never committed to the repo, not on Maven Central under a compatible coordinate |
| Build | Excluded from `./gradlew :lg3d-incubator:build`; the legacy Ant build silently skipped it (`failonerror=false`) |

## Roles

- **Architect** — A Jini/JavaSpaces-based distributed scheduler from the 2006 tree. It is
  **excluded**, not merely dormant: its `net.jini.*`, JGL and SATIN dependencies are
  absent and unobtainable. Do not attempt to wire it into the build without first
  vendoring those libraries and re-verifying the JDK 21 / Jogamp constraints.
- **Engineer / Developer** — **No code here compiles.** Do not import its packages into
  built code. If resurrected, it needs a full dependency-reacquisition plan and a JDK 21
  port; treat that as a new project, not a patch.
- **QA** — Out of scope: it is not built, so there is nothing to test. Do **not** file
  "Cosmo does not appear / does not run" as a defect — exclusion is intentional.
- **Business Analyst** — Historical distributed-scheduling concept. No product value in
  this port; the enabling middleware no longer exists.
- **Functional Analyst** — Record it as an excluded legacy app with its missing-dependency
  list; do not write an active functional spec.
- **Project Manager** — Excluded by design (root `AGENTS.md` *Incubator Exclusions*). Any
  revival is a scoped project with a dependency-acquisition prerequisite, not backlog work.
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
