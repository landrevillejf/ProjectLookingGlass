# NLC (Natural Language Control)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Ported, registered, but runtime-blocked** (needs a microphone + speech/NLP stack) |
| Entry point | `nlc.Main` (+ `nlc.listeners.RotateFrameListener`) |
| Surface | **Controller/listener** app — posts `LgEvent`s (`CloseFrameEvent`, `FocusFrameEvent`, `HideFrameEvent`, `LaunchApplicationEvent`) to drive other windows; no primary 3D UI of its own |
| Start-menu name / group | Natural Language Control / **Utilities** — descriptor **`lg3d-demo-apps/src/config/nlc.lgcfg`** → `config/demo` (**discovered**) |
| Command | `java org.jdesktop.lg3d.apps.nlc.Main` |
| Runtime blocker | Needs a **microphone** + the Stanford parser (`ext/javanlp`) and the grammar resource `englishPCFG.ser.gz`; `StanfordFactory` originally read `lg.etcdir+"/lg3d/englishPCFG.ser.gz"` (absent) — ported to a jar/temp-file fallback |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — An event-based voice/natural-language controller: it parses speech and
  posts `LgEvent`s that focus/hide/close/launch other windows. It has no scene of its
  own. It is **registered** (descriptor in `lg3d-demo-apps`) but cannot fully run
  without audio hardware + the NLP model. Keep the event contract stable — that is its
  integration surface.
- **Engineer / Developer** — Load the grammar model from a classpath/jar resource; for
  APIs needing a real file path (`LexicalizedParser`), copy the jar resource to a temp
  file. Parsing must run off the EDT/render thread; post events via `LgEventConnector`.
  Confirm `ext/javanlp` is on `:lg3d-core:run`'s hand-built classpath. Jogamp only.
- **QA** — Classify honestly: **compiles + registers, but does not run** without a
  microphone and the NLP model. Verify it loads the grammar resource without the old
  `etc/` NPE and posts events when driven programmatically; do not file "no speech
  recognition" as a regression in a hardware-less CI.
- **Business Analyst** — Experimental hands-free control. Niche; blocked on audio
  hardware and an aging NLP stack.
- **Functional Analyst** — Spec the voice-command → `LgEvent` mapping (close/focus/
  hide/launch) and the grammar-resource contract; record the hardware/model blocker.
- **Project Manager** — Commit scope `lg3d-incubator`; descriptor lives in
  `lg3d-demo-apps` (PR may span two modules). Track the `ext/javanlp` run-classpath
  dependency as integration risk.
- **UI/UX (3D & 2D)** — No primary UI; its "UX" is the effect on other windows via
  events. Any status/feedback surface should follow the core glassy vocabulary.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
