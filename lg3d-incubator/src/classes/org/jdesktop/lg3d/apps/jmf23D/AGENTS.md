# jmf23D / Algea3D (Media Player)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (ported from legacy; compiles, needs JMF + hardware to actually play) |
| Entry point | `jmf23D.Algea3D` |
| Surface | **pure-3D** media surface; `HTMLParser`/`HTMLTrack` + a JXTA `DataSource` and `ConferenceThread` for streaming |
| Start-menu name / group | Algea3D (Media Player) / **Media** — descriptor `algea3d.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.jmf23D.Algea3D` |
| Runtime blocker | Needs **JMF** (`ext/jmf.jar`) on the run classpath plus working audio/video capture hardware; JMF is EOL and JDK-21-fragile |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A JMF-backed 3D media player/conference prototype with a JXTA data
  source. It was ported (small self-contained drift) but remains **runtime-blocked**
  on JMF + hardware. `ext/jmf.jar` is on the compile classpath; confirm it is also on
  `:lg3d-core:run`'s hand-built classpath before expecting playback.
- **Engineer / Developer** — Obey the core UI/UX rulebook; the media surface is a live
  texture (single `ImageComponent2D`, `.set()` per frame, never re-attach; POT sizes).
  Keep JMF capture threads off the render thread. Do not use JAI's JPEG encoder
  (references the JDK-removed `com.sun.image.codec.jpeg`). Jogamp packages only.
- **QA** — Classify honestly: **compiles + registers (only if descriptor is moved),
  but does not play** without JMF and capture hardware. Verify no crash on launch; do
  not file missing playback as a regression in a hardware-less CI.
- **Business Analyst** — Niche/historical media prototype. No committed product value;
  JMF's EOL status makes revival costly.
- **Functional Analyst** — Spec as a demonstration (play/stream media onto a 3D
  surface). Record the JMF/hardware blocker and the descriptor-not-scanned fact.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority; any
  revival is a re-platforming project (replace JMF).
- **UI/UX (3D & 2D)** — **3D** media surface. Follow the glassy vocabulary, depth
  ordering and live-texture rules from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
