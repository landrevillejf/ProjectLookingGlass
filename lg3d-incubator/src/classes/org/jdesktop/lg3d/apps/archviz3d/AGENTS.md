# ArchViz3D — EXCLUDED FROM BUILD

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Excluded from the Gradle build** (`exclude 'org/jdesktop/lg3d/apps/archviz3d/**'`) — not compiled, not jarred, not runnable |
| Entry point | `archviz3d.geons.main.ArchViz3D` (per `src/config/archviz3d.lgcfg`); also `ArchVizMain` |
| Surface | 3D architectural-visualization / requirements-model browser (`geons`, `abstractors`, `manifest3D`, `PlEnginePool`, `SharedBrain`) |
| Descriptor | `archviz3d.lgcfg` (Developers group) sits in `lg3d-incubator/src/config` → `config/` (**not scanned**), and the classes are excluded, so it can never launch |
| Missing dependencies | **XMLBeans-generated schema docs** (`org.candc`, `org.reqarch3D`, `org.module`, `org.apache.xmlbeans`) and **JavaLog** — absent from the repo |
| Build | Excluded from `./gradlew :lg3d-incubator:build`; the legacy Ant build silently skipped it |

## Roles

- **Architect** — A 3D front end for architectural/requirements models, driven by
  XMLBeans-generated schema classes. It is **excluded**: the generated `org.candc` /
  `org.reqarch3D` / `org.module` schema types and JavaLog were never committed. Revival
  means regenerating those schema bindings from the original `.xsd`s first.
- **Engineer / Developer** — **No code here compiles.** Do not import its packages into
  built code. A port needs the XMLBeans codegen step reproduced and a JavaLog replacement,
  then a JDK 21 / Jogamp re-verification — a project, not a patch.
- **QA** — Out of scope: it is not built, so there is nothing to test. Do **not** file
  "ArchViz3D does not appear / does not run" as a defect — exclusion is intentional.
- **Business Analyst** — Historical domain-specific visualizer. No product value in this
  port; the schema toolchain and log library are gone.
- **Functional Analyst** — Record it as an excluded legacy app with its missing-dependency
  list; do not write an active functional spec.
- **Project Manager** — Excluded by design (root `AGENTS.md` *Incubator Exclusions*).
  Revival is a scoped project gated on regenerating the XMLBeans schema docs.
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
