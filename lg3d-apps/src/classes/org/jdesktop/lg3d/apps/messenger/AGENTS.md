# Instant Messenger Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (multi-protocol instant messenger) |
| Entry point | `Messenger.main` → `TitledSwingWindow.show(...)`; `MessengerClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `MessengerPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Instant Messenger / **Internet** |
| Command | `java org.jdesktop.lg3d.apps.messenger.Messenger` |
| Descriptor | `src/config/messenger.lgcfg` → `config/demo` |
| Protocols | **Native IRC** (RFC 2812, plain or TLS, in-process, unit-tested) plus **bridge** backends for XMPP, Matrix, Telegram, WhatsApp, Signal, SMS and SIP that hand off to the network's official client/web app; all behind the `MessengerProtocol` SPI and the `ProtocolRegistry` catalogue |
| Persistence | Jackson JSON under `~/.lg3d/messenger` (accounts, transcript, settings) via `MessengerStore`; override dir with `-Dlg3d.messenger.dir` |
| Security | **No secret is ever written to disk**: `AccountConfig`'s password fields are `@JsonIgnore`; a password lives in memory for the session and is re-entered at connect time. IRC supports TLS (`SSLSocket`) and NickServ `IDENTIFY` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Messenger** — thin 3D entry point; installs the hosted look and feel and
  shows `MessengerPanel` in a `TitledSwingWindow`, wiring `setOnClose` to
  `shutdown()` + `frame.changeEnabled(false)`, then `autoConnect()`.
- **MessengerClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the
  panel outside the desktop; never calls `System.exit`.
- **MessengerPanel** — the one Swing UI (no-arg constructor, no Java 3D): a
  toolbar (Connect / Disconnect / Add Account / Settings), a WEST rail of
  accounts and conversations, a styled CENTER transcript (coloured nicknames,
  timestamps, join/part/system filters), a SOUTH input bar that sends text or
  interprets `/slash` commands (`/join /part /msg /me /nick /topic /quit /raw
  /connect /disconnect /clear /help`), and a status line. Protocol callbacks are
  marshalled onto the EDT before touching a widget.
- **MessengerProtocol / ProtocolListener** — the backend SPI (async connect,
  capability set) and its callback surface; the single seam every protocol
  implements, so the UI never knows the wire format.
- **ProtocolRegistry** — the backend catalogue + factory. `standard()` registers
  the native IRC client and the seven bridges; adding a protocol (native or
  bridge) is one `register(...)` call with **no UI change**.
- **IrcProtocol / IrcMessage / IrcCodec** — the fully native IRC client: `IrcMessage`
  is the pure RFC 2812 line parser/formatter, `IrcCodec` handles CTCP, mIRC
  formatting and 512-octet line splitting, and `IrcProtocol` owns the socket,
  registration, keep-alive PING, auto-reconnect, channels and roster. The
  headless unit-test seam (an embedded mock IRC server drives it end to end).
- **BridgeProtocol** — the deep-link/external-command hand-off backend for the
  networks that need an external or native stack; headless-guarded.
- **AccountConfig / ChatMessage / StoredMessage / MessengerSettings** — model
  beans (`ChatMessage` is the protocol-neutral event; `StoredMessage` the
  persisted transcript line).
- **MessengerStore** — defensive JSON persistence (corrupt/missing files yield
  empty/defaults, never throw).

## Roles

- **Architect** — Everything lives in this package: model beans, the
  `MessengerProtocol` SPI + `ProtocolRegistry`, the native IRC stack
  (`IrcMessage`/`IrcCodec`/`IrcProtocol`), the `BridgeProtocol`, `MessengerStore`
  (Jackson I/O), `MessengerPanel` (the one Swing UI) and the two thin entry
  points. The same panel drives both desktops: 3D via `TitledSwingWindow`, 2D via
  `Desktop2DAppRegistry.PANEL_APPS` keyed on `Messenger`. Jackson + SLF4J are
  already `lg3d-apps` compile deps (added for the SSH client) and are on the
  hand-assembled `:lg3d-core:run` / `releaseBundle` classpath, so the in-JVM
  launch resolves. **No new third-party dependency and no native codec stack are
  added** — IRC is pure Java + JSSE, and every other network is reached through
  its official client. This is how "every known protocol" is approached honestly:
  one real, tested native client and an extensible seam for the rest.
- **Engineer / Developer** — Keep the wire logic in the protocol classes and pure
  helpers (`IrcMessage`/`IrcCodec`), never in the panel; the panel only calls the
  SPI. Guard every browser/clipboard/modal/notification path on
  `!GraphicsEnvironment.isHeadless()` so headless tests never open a window or
  block, and keep the constructor free of network I/O. Marshal every
  `ProtocolListener` callback onto the EDT (`SwingUtilities.invokeLater`) before
  touching a widget. Keep the rendered surface free of Synth-only widgets (no
  combo boxes on the SwingNode-painted panel — combos are fine in the modal
  dialogs). Never persist a password (keep the `@JsonIgnore` on the secret
  fields). Never call `System.exit`. To add a protocol, implement
  `MessengerProtocol` and register it in `ProtocolRegistry.standard()`; do not
  branch on protocol id inside the panel. Jogamp packages only where 3D is
  touched (none here); obey the core UI/UX rulebook.
- **QA** — `IrcCodecTest`, `IrcMessageTest`, `IrcProtocolTest` (an embedded mock
  IRC `ServerSocket` drives registration, PRIVMSG/CTCP, JOIN/roster and reconnect
  end to end), `MessengerStoreTest`, `MessengerModelTest` and `MessengerPanelTest`
  run headless: they assert line parsing/formatting, CTCP + formatting + 512-octet
  splitting, JSON round-trips to a temp dir (and that no password is written),
  model bean invariants, `/slash`-command parsing, nickname colouring determinism,
  transcript routing/rendering and that sending without a live backend only sets a
  status (no socket, no browser). For the 3D host use the in-JVM probe + internal
  screencapture (`lg3d-core/lgscreen-*.png`); a black capture under Wayland is not
  a defect.
- **Business Analyst** — A daily-driver communication utility: chat on IRC and
  reach XMPP/Matrix/Telegram/WhatsApp/Signal/SMS/SIP from one desktop app, with
  saved accounts, persistent history and per-user display preferences, in both
  desktops. Value = one integrated messenger instead of a dozen separate clients,
  with a native, fully-offline-testable IRC core and honest bridges for the rest.
- **Functional Analyst** — Spec this app as the *chat-client contract*: connect an
  account over a registered protocol, join/leave channels, exchange messages and
  actions, track presence/roster, and persist accounts/history/settings — with the
  wire protocol hidden behind `MessengerProtocol` + `ChatMessage`. Bridges are
  specified as "hand off to the official client for this target", never as a
  false claim of in-process support.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `MessengerPanel` in an MDI internal frame.
  Conventional three-pane messenger chrome (accounts/conversations rail +
  transcript + input), never a click-cycling 3D idiom; keep both surfaces
  pixel-identical. A bridge conversation must clearly say it opens the external
  client, so the hand-off is not read as a bug.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `MessengerProtocol` /
`ProtocolRegistry` seam, the native-IRC vs bridge transport boundary, and the
evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
