# Mail Application (real IMAP/SMTP client)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · native-3D app guide: [`docs/lg3d-native-apps.md`](../../../../../../../../docs/lg3d-native-apps.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production-grade** mail client (supported showcase), 2D + native-3D |
| Backend | Real **IMAP/SMTP** on **Jakarta Mail 2.x** via Eclipse Angus (`org.eclipse.angus:angus-mail`) |
| 2D entry point | `MailPanel` (`JPanel`, no Java 3D) — hosted by the Swing/2D desktop |
| 3D entry point | `Mail3D.main` → `Frame3D` host (browse/triage surface) |
| Start-menu name / group | Mail 3D / **Office** (one descriptor; 2D strips the `3D` marker to "Mail") |
| Command | `java org.jdesktop.lg3d.apps.mail.Mail3D` |
| Descriptor | **`lg3d-apps/src/config/mail3d.lgcfg`** → `config/demo` (incubator `src/config` is not scanned) |
| Persistence | Shared user `Preferences`: `/mail/accounts`, `/mail/settings`, `/mail/rules`, `/mail/.secret`; reads `/contacts` (populated by Contact 3D) |
| Build | `./gradlew :lg3d-incubator:build` |

## Key components

**Model (AWT-free, shared by both desktops)**

- **MailMessage / MailAddress / MailAttachment / MailFolder** — the message,
  address, attachment-metadata and folder models. No Java 3D or Swing types.
- **MailAccount** — per-account config (IMAP + SMTP host/port/security, username,
  `credentialMode` ASK|SAVED, signature, default flag); Preferences (de)serialisation.

**Config / persistence**

- **MailAccountStore** — CRUD over `/mail/accounts`, single-default invariant.
- **MailSettings** — appearance/behaviour prefs under `/mail/settings` (fonts, theme,
  density, reading-pane position, sort, auto-check interval, confirm-on-delete,
  HTML-render toggle, notify-on-new-mail toggle). Secure defaults: HTML rendering
  **off**.
- **NewMailNotifier** — the shared new-mail state machine (seen-set of message keys,
  baseline on first listing, one toast per arrival batch, inbox-only + unread-only).
  Pure state behind a `Notifier` seam; both UI surfaces post through
  `NotificationService.notify` so the toast lands on whichever desktop shell runs.
- **MailRule / MailRuleStore** — filter rules under `/mail/rules`
  (from/subject/to · contains/equals/regex → move/mark-read/flag/delete), applied on fetch.
- **CredentialVault** — AES-GCM obfuscation of SAVED passwords under a per-install
  random secret at `/mail/.secret`. ASK-mode passwords are **never persisted**; secrets
  are never logged.

**Service layer (real backend)**

- **MailService** — the backend interface (connect, listFolders, list, open, send,
  move/delete/setFlags, search, disconnect).
- **ImapSmtpMailService** — Jakarta Mail implementation. IMAP `Store` for
  folders/envelopes/flags/search (`FetchProfile` headers-only listing, lazy body fetch);
  SMTP `Transport` for send with `MimeMultipart` attachments. **Secure by default**:
  SSL/STARTTLS, certificate validation on the JDK truststore, explicit connect/read/write
  timeouts, plaintext only when an account explicitly selects `Security.NONE`.
- **MailSessionManager** — per-account live sessions off the EDT, envelope caching,
  reconnect-on-drop, credential resolution (ASK → UI prompt callback; SAVED →
  `CredentialVault`), rule application on fetch, scheduled auto-check, status callbacks.
- **MailBackendException** — wraps `MessagingException` for the UI to surface.

**2D Swing UI**

- **MailPanel** — three-pane client (account/folder `JTree` · sortable `JTable` ·
  reading pane), toolbar, status bar, empty state. **No-arg constructor** for reflective
  registry instantiation; a `(manager, settings)` test-seam constructor + `setSynchronous(true)`
  make it headless-testable. Runs the periodic auto-check (`MailSessionManager.startAutoCheck`,
  interval from settings, 0 = off) and raises the new-mail toast through `NewMailNotifier`.
  Never loads a Java 3D class.
- **MailTableModel / MessageReader / ComposePanel** — list model, reading pane
  (plain-text preferred; HTML only if enabled, remote content blocked), and the
  To/Cc/Bcc/Subject/body + signature + attachments editor.
- **MailSettingsDialog / MailAccountDialog / PasswordPromptDialog** — tabbed settings
  (Accounts / Appearance / Rules / Behaviour), the account editor with "Test connection",
  and the ASK-mode credential modal. Choice options use **JList / JRadioButton**, never
  combo boxes (SwingNode offscreen rendering on the 3D desktop).

**Native-3D**

- **Mail3D** — `Frame3D` entry point driving the same `MailSessionManager`/`MailService`;
  browse/triage (read/flag/delete/move) plus preset quick-reply (3D has no keyboard).
  Full compose/attachments stays a 2D capability. Runs the same auto-check poll and the
  same `NewMailNotifier` toast as the 2D panel, via the 3D HUD `NotificationService`.
- **MailView** — the live-texture `Component3D` rendering the list + reading pane.

## Roles

- **Architect** — Both desktops share ONE model + service layer; the only split is the
  presentation (`MailPanel` vs `Mail3D`/`MailView`). Cross-app data uses the **shared user
  `Preferences` tree**, not ServiceContext/Channel (a fresh context per app is not shared
  across separately-launched apps). Mail reads `/contacts` and owns `/mail/*`. Keep the
  model + service AWT-free so they unit-test headless; keep all Java 3D out of `MailPanel`
  and all Swing-blocking I/O off the EDT (worker/virtual threads via `MailSessionManager`).
- **Engineer / Developer** — Obey the core UI/UX rulebook. In 3D follow the **live-texture
  rule** (one fixed power-of-two `ImageComponent2D` with `ALLOW_IMAGE_WRITE`, built once off-live,
  repaint + `.set()` in place, never re-attach); list quads set `Geometry.ALLOW_INTERSECT`
  for `PICK_GEOMETRY` row mapping. **Dev mode routes no keyboard focus to a `Frame3D`** —
  everything is click/button driven; reuse the runtime-drawn `AgendaButton` idiom. Security
  invariants: TLS by default, no wildcard `ssl.trust`, no plaintext fallback unless the
  account opts in, secrets never logged, HTML mail opt-in with remote content blocked.
  Never write another app's `Preferences` node. Jogamp only.
- **QA** — Protocol correctness is proven headlessly against **GreenMail**
  (`ImapSmtpMailServiceTest`: connect/list/open/search/setFlags/move/delete + send-with-attachment
  round trip). Stores/rules/settings/vault and `MailSessionManager` are unit-tested against
  `Preferences` and a `FakeMailService`; `MailPanelTest` drives the panel in synchronous mode.
  Tests run `java.awt.headless=true` and clear `/mail` before/after. The legacy
  `ext/mail.jar` (javax.mail, kept only for `blackgoat`) is filtered off the **test** classpath:
  its `com.sun.mail.handlers.text_plain` clashes with angus's jakarta handler of the same name.
  Verify the 3D view with the in-JVM probe + internal screencapture; a black host capture under
  Wayland is not a defect.
- **Business Analyst** — A supported showcase demonstrating a real, configurable, secure
  IMAP/SMTP client with native-3D browse/triage and cross-app data sharing. Production
  expectations apply. No live external mail server exists in CI, so protocol correctness is
  proven against GreenMail; real-provider smoke testing is a manual step reported in the PR.
- **Functional Analyst** — Spec user-visible function (accounts + credential modes, folder
  tree, list/read/compose/reply/forward, attachments, search, rules, appearance/behaviour
  settings, 3D triage) plus the core contract (shared model/service, `/mail/*` + `/contacts`
  nodes, descriptor location, TLS-by-default security).
- **Project Manager** — Commit scope `lg3d-incubator`; the descriptor lives in `lg3d-apps`
  and the run/releaseBundle classpath wiring in `lg3d-core`, so a PR may span modules — say so.
  Done = build + `:lg3d-core:runtimeResources` (icon) + tests + `./run-lg3d.sh` + capture/log evidence.
- **UI/UX (3D & 2D)** — **Runs in both desktops off one descriptor.** In the 3D desktop the
  list, reading pane and controls are runtime-drawn scene-graph widgets: follow the glassy
  vocabulary, depth ordering and click-driven-input rules from core; verify occlusion with a
  capture, not numeric Z. In the 2D/Swing desktop the same descriptor launches `MailPanel`
  (registered in `Desktop2DAppRegistry` on the `Mail3D` main class), an idiomatic three-pane
  Swing client. Settings/rules/accounts configured in one desktop are visible in the other
  (shared `/mail/*`). `MailPanel` must never load a Java 3D class (a 3D-less JVM runs it).

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook +
`docs/lg3d-native-apps.md` → root `AGENTS.md`. On conflict the higher file wins; fix
here in the same PR. Every PR states live vs dormant status, the descriptor location,
the Preferences nodes touched, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump.
Stage only intended paths (never `git add -A`).
