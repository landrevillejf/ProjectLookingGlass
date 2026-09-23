# AGENTS.md — lpm-console

> Role-aware guide for everyone working on **lpm-console**. The root
> [`../AGENTS.md`](../AGENTS.md) governs the build system, module map, Java 3D
> migration, exclusions and commit conventions. This file adds module-specific
> guidance and a shared per-role view so **all roles stay coherent**.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | **Graphical front-end for LPM** (Linux Package Manager) on BLFS/LFS systems. |
| Root package | `org.lpmconsole.*` (sources under `src/classes`). |
| Depends on | **Nothing in the repo** — a standalone Java 21 Swing app; no `lg3d-core`, no Java 3D. |
| Runtime model | Runs as an **X11 client composited by lg3d**, not a 3D scene-graph app. |
| Main class | `org.lpmconsole.LPMConsole`. |
| Jar | `build-gradle/libs/lpm-console.jar` (fat jar via `application` plugin). |
| Build / run | `./gradlew :lpm-console:build` · `:lpm-console:run` · `:lpm-console:pitest` (report-only). |
| Requires | LPM 2.7.0 at `/usr/bin/lpm`, an X11 display, and `pkexec`/`sudo` for privileged ops. |
| License | GPL-3.0. |

**Key classes:** `LPMConsole` (main Swing UI), `LPMExecutor` (runs LPM commands),
`LPMCommand` (enum of supported commands), `OperationResult` (result wrapper),
`PrivilegeEscalator` (pkexec/sudo), `LPMExecutionException`, and a read-only
`LpmDatabase` parser.

## How the roles work together

lpm-console is a **thin controller** over a trusted CLI: it never re-implements
LPM's dependency resolution, DB writes, locking, checksums, or rollback. The
Architect guards that "front-end only" boundary and the privilege-separation
model; Engineers keep mutating operations dry-run-first and off the EDT; QA
verifies command mapping, lock handling and escalation; the Business/Functional
Analysts keep the LPM Control Application Contract satisfied; the PM tracks the
packaging/polkit deliverables. Everyone works from this file and the
[`../lpm-lg3d-app-contract.md`](../lpm-lg3d-app-contract.md).

## Architect

- **Front-end only, always.** All state changes go through `/usr/bin/lpm`; reads
  may use DB files for speed but **writes never bypass LPM**. Do not add
  dependency resolution, locking, or DB mutation logic here.
- **Privilege separation:** the GUI process never runs as root; mutating
  operations escalate per-operation via `pkexec` (preferred) or `sudo` (polkit
  action `com.lpmconsole.policy`). Preserve this boundary.
- **Concurrency:** a single lock serialises LPM operations and respects
  `/var/lock/lpm.lock`; the UI must detect "Another lpm instance is running" and
  offer retry.
- Packaging is architectural: the LPM package (`package/build-package.sh`),
  `.desktop` entry, and polkit action are part of the delivered contract.
- This module is intentionally **decoupled** from the lg3d scene graph — do not
  add a `lg3d-core`/Java 3D dependency; it is composited as an ordinary X11
  window.

## Engineer / Developer

- Long-running LPM operations run **off the EDT** with live stdout/stderr
  streaming; disable UI controls while an operation is in flight.
- Always pass `--no-color` to LPM; capture stdout and stderr separately; use
  canonical command names (no aliases); surface LPM's **verbatim** error messages
  (error fidelity); handle non-zero exit codes explicitly.
- **Dry-run first:** every mutating operation shows a preview before execution;
  destructive operations (remove, autoremove, upgrade) require explicit
  confirmation.
- The `LPMCommand` enum + `LPMExecutor` argument builders are the single place a
  command mapping is defined — keep the README's UI→LPM table in sync when adding
  a command.
- Headless-testable logic (value objects, the read-only `LpmDatabase` parser via
  `-Dlpm.dbdir`, and the executor/escalator command builders) goes in
  `src/test/java`; the Swing frame/panel need a peer and are verified with the
  in-JVM probe (see [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md)).
- Tests pin `java.awt.headless=true` so a stray Swing touch cannot fail CI.

## QA

- Verify the **command mapping** (UI action → exact `lpm` invocation), the
  dry-run preview, destructive-op confirmation, `--no-color`, and separated
  stdout/stderr.
- Verify **lock handling** ("Another lpm instance is running" → retry affordance)
  and **privilege escalation** (pkexec preferred, sudo fallback, GUI never root).
- Headless JUnit covers the GUI-free logic; PIT is report-only
  (`mutationThreshold = 0`, `avoidCallsTo = ['java.awt', 'javax.swing']`) via
  `./gradlew :lpm-console:pitest`. The Swing UI is verified with the in-JVM probe
  + internal screencapture, not external capture (blocked under GNOME/Wayland).
- Compliance checklist lives in the module `README.md` (*Compliance with
  Contract*) — keep it green.

## Business Analyst

- Value: a **safe, user-friendly package manager** for BLFS/LFS users that never
  lets the GUI corrupt the system — all critical operations stay with LPM.
- The differentiators to protect: dry-run-before-mutate, verbatim error
  fidelity, privilege separation, and one-operation-at-a-time safety.

## Functional Analyst

- The functional contract is the
  [**LPM Control Application Contract**](../lpm-lg3d-app-contract.md) and the
  [**LFS X11 contract**](../docs/lfs-x11-contract.md): required commands
  implemented, dry-run preview, confirmation for destructive ops, `--no-color`,
  separate streams, canonical names, exit-code handling, lock serialisation,
  pkexec/sudo escalation, off-EDT streaming, X11-client delivery.
- Specify each new UI command as: the exact LPM invocation, whether it mutates
  (→ dry-run) or is destructive (→ confirm), and its privilege level.

## Project Manager

- Commit scope is **`lpm-console`**. Branch → commit → push → PR against `main`;
  never commit to `main`.
- Deliverables include the LPM package, `.desktop` file, and polkit action —
  track packaging/install hooks (`package/`) as part of done.
- Runtime verification needs a real LPM install + X display; note in the PR what
  was actually exercised versus unit-tested headless.

## UI/UX (2D)

- **2D Swing only** — this app has no 3D scene-graph surface. It appears in lg3d
  as a composited X11 window, so it must behave as a well-mannered standard
  Swing application (no WM/compositor assumptions of its own).
- Respect Swing threading: all UI work on the **EDT**, long operations on worker
  threads with streamed progress; disable controls during an operation and
  re-enable on completion.
- Keep the quick-action bar (List / Upgradable / History / Holds / Update DB /
  Clean Cache) and the command dropdown coherent with the LPM command table;
  destructive actions must be visually distinguished and confirmed.
- Because it is composited, do not rely on lg3d-specific decoration; standard
  Swing look-and-feel and dialogs are correct here (unlike the offscreen
  `SwingNode` panels in the 3D desktop, which cannot use modal dialogs).

## Communication & coherence

- Single source of truth: this file (module), the LPM/LFS contracts, the module
  `README.md`, and the root [`../AGENTS.md`](../AGENTS.md). Conflict → root wins,
  fix here in the same PR.
- Every PR states: the LPM command(s) touched, whether it mutates/escalates, and
  the headless-test + probe/screencapture evidence.

## Commit / PR

- Conventional Commit scope **`lpm-console`** (or `agents` for this file).
  Imperative subject ≤ 50 chars; body wrapped at 72.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version.
- Stage only intended paths (never `git add -A`; `lg3d-core/lgscreen-*.png` are
  runtime artifacts). Run branch → commit → push → PR against `main`.
