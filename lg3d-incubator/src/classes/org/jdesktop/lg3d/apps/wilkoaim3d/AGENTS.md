# WilkoAIM3D (Aim3d) — EXCLUDED FROM BUILD

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Excluded from the Gradle build** (`exclude 'org/jdesktop/lg3d/apps/wilkoaim3d/**'`) — not compiled, not jarred, not runnable |
| Entry point | `wilkoaim3d.Aim3d` (`extends Frame3D`, `public static void main`, implements `JaimEventListener`); nested `imWindow`/`imMessage` (both `Frame3D`), `Button extends Component3D`, `ButtonAppearance extends SimpleAppearance` |
| Surface | 3D AOL Instant Messenger client (chat windows as `Frame3D`s) |
| Descriptor | **None** — no `.lgcfg` for wilkoaim3d in `src/config` |
| Blocker (real) | **Deep 2004-era core-API drift** — targets a utility vocabulary that no longer exists (`Frame3DToFrontEvent`, `ComponentMover`, `ResilientRotateAction`, `NaturalMotionComponent3D/Container3D`, `ColorAlphaChangeAction`), obsolete 2-arg event-adapter constructors, and `setTexture(String)`. Porting means rewriting the 1277-line prototype from scratch. |
| Blocker (fatal) | Its **AOL AIM TOC** backend was discontinued by AOL in **Dec 2017** — it could never log in even if rewritten. (The `com.wilko` `jaimlib.jar` IS present in `ext/`; the missing library was never the real blocker.) |
| Build | Excluded from `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A 3D AIM chat client. It is **excluded** for two independent reasons: a
  from-scratch rewrite would be needed for the vanished 2004 core-API vocabulary, *and* the
  AOL AIM TOC service it talks to was shut down in 2017, so it cannot function regardless.
  Do not schedule a port — the backend is dead. If a 3D chat client is ever wanted, start
  fresh on a live protocol (see `mail`/`blackgoat` for the native-3D communication pattern).
- **Engineer / Developer** — **No code here compiles.** Do not import its packages or copy
  its idioms into built code — they reference removed APIs and the obsolete 2-arg
  event-adapter constructors. Treat the source as historical reference only.
- **QA** — Out of scope: it is not built and has no descriptor. Do **not** file "Aim3d does
  not appear / cannot log in" as a defect — exclusion is intentional and the service is dead.
- **Business Analyst** — Historical 3D-IM concept whose backing service (AOL AIM) no longer
  exists. No product value; not a revival candidate.
- **Functional Analyst** — Record it as an excluded legacy app with both blockers (API drift
  + discontinued AIM TOC); do not write an active functional spec.
- **Project Manager** — Excluded by design (root `AGENTS.md` *Incubator Exclusions*).
  Explicitly **not** backlog work: the dead backend makes revival pointless.
- **UI/UX (3D & 2D)** — Not applicable while excluded. Any future 3D chat UI would follow
  the current core glassy vocabulary, SwingNode contract and threading rules — not this file.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md` (which lists this exclusion). On conflict the higher file wins; fix here in
the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
