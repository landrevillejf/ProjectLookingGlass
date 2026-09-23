# Aim3D (com.wilko.jaim test stub) — library fragment

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Library / test fragment** (compiles; not an app, no descriptor, no UI) |
| Contents | A single class `aim3d.com.wilko.jaim.Test` — a harness/exercise for the bundled `com.wilko.jaim` AIM library (`ext/jaimlib.jar`) |
| Surface | **None** — no `Frame3D`, no start-menu entry |
| Related | This is the library-test companion of the **excluded** `wilkoaim3d` app (see `../wilkoaim3d/AGENTS.md`); both target the discontinued AOL AIM TOC backend |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — Not an application: it is a leftover test harness for the `com.wilko.jaim`
  AIM library that the excluded `wilkoaim3d` client used. The AIM TOC backend was
  discontinued by AOL in Dec 2017, so nothing here can connect. Keep it only as historical
  reference; do not build new features on it.
- **Engineer / Developer** — It compiles against `ext/jaimlib.jar` but has no live service to
  talk to. Do not import `com.wilko.jaim` into built product code, and do not copy the
  excluded `wilkoaim3d` idioms (removed core APIs). Jogamp packages only for any real work.
- **QA** — Out of scope for functional testing: no UI, no reachable backend. Do **not** file
  "cannot log in to AIM" as a defect — the service is dead.
- **Business Analyst** — No product value; a dead-backend library stub.
- **Functional Analyst** — Record it as a non-functional historical fragment tied to
  `wilkoaim3d`; no spec.
- **Project Manager** — Commit scope `lg3d-incubator`. Candidate for removal if the tree is
  ever pruned of dead-backend code; otherwise leave untouched.
- **UI/UX (3D & 2D)** — Not applicable (no UI).

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. See also `../wilkoaim3d/AGENTS.md`. On conflict the higher file wins; fix here
in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
