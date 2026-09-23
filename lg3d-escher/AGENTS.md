# AGENTS.md — lg3d-escher

> Role-aware guide for everyone working on **lg3d-escher**. The root
> [`../AGENTS.md`](../AGENTS.md) governs the build system, module map, Java 3D
> migration, exclusions and commit conventions; this file adds module-specific
> guidance and a shared per-role view so **all roles stay coherent**.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | Pure-Java **X11 protocol library** (the "Escher" X client) bundled with lg3d. |
| Root package | `gnu.*` (legacy layout; sources live under `gnu/`, compiled from `projectDir`). |
| Language / encoding | Java, **ISO-8859-1** (not UTF-8 — a Big5 comment in the `gnu/x11/test` harness). |
| Depends on | Nothing inside the repo (leaf module). |
| Depended on by | `lg3d-core` (the X11 / native-window plumbing). |
| Jar | `build-gradle/libs/escher-0.2.2.jar`. |
| Build | `./gradlew :lg3d-escher:build` (JDK 21 toolchain, Gradle 8.14). |
| Excluded from jar | The bundled `antlr-2.7.4/` tree and `gnu/x11/test` harness. |

**Nature of the module:** this is low-level, protocol-faithful code, not a UI
layer. It speaks the X11 wire protocol; correctness is measured against the X
protocol specification, not against a rendered desktop.

## How the roles work together

Escher is a **foundation** module: an error here surfaces far away (in
`lg3d-core`'s native-window path) as a protocol failure or a garbled display.
The Architect owns the wire-protocol boundaries, the Engineer keeps the port
JDK 21-clean, QA verifies against protocol behaviour, the Analysts keep the
"what must not regress" contract explicit, and the PM tracks that changes here
ripple into `lg3d-core`. Everyone reads this file and the root `AGENTS.md`;
disagreements are resolved in the PR, not silently in code.

## Architect

- Preserve the **X11 protocol contract**: request/reply/event encodings, byte
  order, and the `gnu.x11.*` class boundaries. Public API changes here are
  effectively ABI changes for `lg3d-core`.
- This module is a **leaf** — never add a dependency on `lg3d-core` or any
  higher module (that would create a cycle).
- The native X11 window path that consumes Escher
  (`org.jdesktop.lg3d.displayserver.fws.x11.*`) is **excluded from the build**
  (see root *Code Exclusions*). Keep Escher self-consistent and compilable even
  though its largest consumer is dormant; do not "fix" it by wiring in excluded
  code.
- The `antlr-2.7.4/` subtree and legacy `Makefile`/`build`/`clean` scripts are
  reference-only; do not treat them as part of the architecture.

## Engineer / Developer

- Compile under **ISO-8859-1**; do not "modernise" the encoding — non-ASCII
  bytes in the test harness will fail a UTF-8 compile.
- Use the Gradle source set (`srcDirs = [projectDir]`, `include 'gnu/**/*.java'`);
  do not add new source roots without updating `build.gradle`.
- JDK 21 migration rules apply repo-wide: no `javax.media.j3d` / `javax.vecmath`
  (irrelevant here, but never introduce them), and no reliance on JDK-internal
  `sun.*` APIs.
- Warnings are disabled (`-nowarn`) for noisy legacy sources — do not "clean up"
  unrelated legacy code in a functional PR.
- Keep changes **mechanical and minimal**; this is ported 2006-era code whose
  value is protocol fidelity.

## QA

- Escher is verified by **compilation** and by the `lg3d-core` consumers that
  exercise it; there is no desktop UI to screenshot for this module.
- Regression focus: request/reply round-trips, event decoding, and connection
  setup. A protocol regression appears as a crash or corruption in the native
  X11 path, not in dev mode (`lg.fws.mode=dev` does not drive real X11 windows).
- Coverage/mutation gates: 100% JaCoCo / 0 PIT mutants is the stated target
  (root *Test coverage*), currently **report-only** and not enforced. Do not
  claim untested success — state the evidence in the PR.

## Business Analyst

- Business value: Escher is the **portable X11 client** that lets lg3d run its
  own foundation window system without a native C X toolkit. Its "customer" is
  internal (the desktop), not an end user.
- Any capability change must be justified against the native-X11 roadmap; today
  that path is excluded, so treat Escher as **maintained-but-dormant**
  infrastructure, not a feature surface.

## Functional Analyst

- The functional contract is the **X11 protocol** plus the API `lg3d-core`
  consumes. Document behaviour changes in terms of protocol semantics
  (e.g. "reply length now includes the padding word"), not UI outcomes.
- Keep the exclusion rationale current: if a consumer is re-enabled, the
  functional requirements for Escher change with it — flag this to the Architect
  and PM before any code lands.

## Project Manager

- Scope: changes here are **infrastructure**, high-blast-radius, low-frequency.
  Schedule them with an explicit `lg3d-core` impact review.
- Commit scope is `lg3d-escher`; follow the branch → commit → push → PR flow
  (root *Git Conventions*). Do not commit directly to `main`.
- Track the JDK-21/ISO-8859-1 constraints as standing risks; a build break here
  blocks every downstream module.

## UI/UX

**Not applicable.** lg3d-escher has no user interface; it is a protocol library.
UI/UX guidance for the desktop lives in [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md).

## Communication & coherence

- Single source of truth: this file for module specifics, the root
  [`../AGENTS.md`](../AGENTS.md) for build/exclusions/commits. If they conflict,
  the root file wins and this file gets fixed in the same PR.
- Every PR states: the protocol behaviour touched, the `lg3d-core` impact, and
  the verification performed (compile + consumer exercise).

## Commit / PR

- Conventional Commit scope: **`lg3d-escher`** (or `agents` for edits to this
  file). Imperative subject ≤ 50 chars, body wrapped at 72.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version
  (that is a separate chore PR).
- Stage only intended paths — never `git add -A` (the tree holds untracked
  runtime artifacts). Run branch → commit → push → PR against `main`.
