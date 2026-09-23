# Mail 3D Application

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · native-3D app guide: [`docs/lg3d-native-apps.md`](../../../../../../../../docs/lg3d-native-apps.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production-grade** native-3D app (supported showcase) |
| Entry point | `Mail3D.main` → `Frame3D` host |
| Surface | **pure-3D `Frame3D`** — click-driven native-3D UI (no keyboard focus in dev mode) |
| Start-menu name / group | Mail 3D / **Office** |
| Command | `java org.jdesktop.lg3d.apps.mail.Mail3D` |
| Descriptor | **`lg3d-demo-apps/src/config/mail3d.lgcfg`** → `config/demo` (incubator `src/config` is not scanned) |
| Persistence | Shared user `Preferences` node `/mail/messages`; reads `/contacts` (populated by Contact 3D) |
| Build | `./gradlew :lg3d-incubator:build` |

## Key components

- **Mail3D** — `Frame3D` entry point + control layout.
- **MailStore** — loads/saves messages under the `/mail/messages` `Preferences` node.
- **MailMessage** — the message model.
- **MailView** — the live-texture `Component3D` that renders the mailbox/reading pane.

## Roles

- **Architect** — A native-3D showcase app. Cross-app data uses the **shared user
  `Preferences` tree**, not ServiceContext/Channel (a fresh context is created per
  app, so channels are not shared across separately-launched apps). Mail 3D reads the
  `/contacts` node Contact 3D populates and owns `/mail/messages`. Keep the model
  (`MailStore`/`MailMessage`) AWT-free so it unit-tests headless.
- **Engineer / Developer** — Obey the core UI/UX rulebook and the **live-texture
  rule** (one fixed-size `ImageComponent2D` with `ALLOW_IMAGE_WRITE`, repaint + `.set()`
  in place, never re-attach; power-of-two textures; pixels uploaded before attach).
  Board/list quads set `Geometry.ALLOW_INTERSECT` so `PICK_GEOMETRY` maps a click to a
  row. **Dev mode routes no keyboard focus to a `Frame3D`** — everything is
  click/button driven; reuse the runtime-drawn `AgendaButton` idiom rather than
  expecting typing. Never write another app's `Preferences` node. Jogamp only.
- **QA** — Unit-test `MailStore`/`MailMessage` headless (persistence round-trip,
  contact lookup). Verify the 3D view with the in-JVM probe + internal screencapture;
  a black host capture under Wayland is not a defect. Watch for swallowed
  `EventProcessor` exceptions and the texture-NPE "invisible panel" class.
- **Business Analyst** — A supported showcase (3D mail client) demonstrating native-3D
  productivity UI and cross-app data sharing. Production expectations apply; it is a
  local/Preferences-backed client, not a live IMAP/SMTP product.
- **Functional Analyst** — Spec user-visible function (browse/read/compose messages,
  pick contacts as recipients) plus the core contract (Frame3D host, live-texture
  view, `/mail/messages` + `/contacts` Preferences nodes, descriptor location).
- **Project Manager** — Commit scope `lg3d-incubator`; the descriptor lives in
  `lg3d-demo-apps`, so a PR may span two modules — say so. Done = build +
  `:lg3d-core:runtimeResources` (icon) + `./run-lg3d.sh` + capture/log evidence.
- **UI/UX (3D & 2D)** — **3D only**: the mailbox, reading pane and controls are
  runtime-drawn scene-graph widgets. Follow the glassy vocabulary, depth ordering and
  click-driven-input rules from core; verify occlusion with a capture, not numeric Z.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook +
`docs/lg3d-native-apps.md` → root `AGENTS.md`. On conflict the higher file wins; fix
here in the same PR. Every PR states live vs dormant status, the descriptor location,
the Preferences nodes touched, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump.
Stage only intended paths (never `git add -A`).
