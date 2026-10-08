# VPN

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** VPN tunnel front-end (delegate, not a stack) |
| Entry point | `Vpn.main` → `TitledSwingWindow.show(...)`; `VpnClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (820x540, hosts `VpnPanel`); the same panel is reused in the 2D desktop |
| Start-menu name / group | VPN / **Internet** |
| Command | `java org.jdesktop.lg3d.apps.vpn.Vpn` |
| Descriptor | `src/config/vpn.lgcfg` → `config/demo` |
| Engine | **No in-tree tunnel stack.** The connection is delegated to an installed tool: **`nmcli`** preferred (it reuses NetworkManager's stored VPN connections and secrets), **`openvpn`** / **`wg-quick`** for a config file the user imports. A missing tool, or a connect that needs privilege the session lacks, degrades to honest guidance, never a fake "connected" |
| Persistence | Jackson JSON under `~/.lg3d/vpn` via `VpnStore`: `settings.json` (preferred backend, auto-connect, last profile, refresh-on-open) and `profiles.json` (imported profiles with their auto-connect and kill-switch flags). Override dir with `-Dlg3d.vpn.dir` |
| Security | **Stores no secret** — NetworkManager keeps its own VPN credentials, and an imported OpenVPN/WireGuard config keeps its own keys where the user left them (only the path is remembered) |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Vpn** — thin 3D entry point; installs the hosted look and feel and shows
  `VpnPanel` in a `TitledSwingWindow`, wiring `setOnClose` to `stopTunnel()` and
  `frame.changeEnabled(false)`.
- **VpnClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the panel
  outside the desktop; a `windowClosed` handler calls `stopTunnel()`. Never calls
  `System.exit`.
- **VpnPanel** — the one Swing UI (no-arg constructor, no Java 3D): a profile dock
  (NetworkManager connections discovered live, plus imported `.ovpn`/`.conf`) with
  New / Edit / Import / Remove, a detail/status centre with Connect, Disconnect and
  Verify tunnel, an auto-connect and a per-profile kill-switch checkbox, and a
  backend picker. A 5 s Swing `Timer` (started in `addNotify`, stopped in
  `removeNotify`) polls the live tunnel state, so an unexpected drop can arm the
  kill switch and/or schedule an auto-reconnect; the cut lifts on reconnect, on
  un-arming or on close. No process, probe, timer or dialog runs until the panel is
  shown or the user acts (Refresh / Connect / Disconnect / Import / New / Edit /
  Verify); each external command and the IP-echo fetch run on a daemon thread and
  hop back to the EDT to render, so the panel constructs and is asserted on
  headless.
- **VpnBackend** — the AWT-free tunnel seam: resolves which tool to drive, builds
  the exact `nmcli connection up/down`, `openvpn --config … --daemon` and `wg-quick
  up/down` command lines, and **parses** terse `nmcli` output into `VpnProfile` /
  `VpnStatus` (escape-aware on `\:`). Pure and side-effect free, so the whole
  command / parser table is unit-testable headless against recorded `nmcli` output.
- **ReconnectPolicy / TunnelVerdict** — the pure decision seams behind the live
  behaviour: `ReconnectPolicy` is an overflow-safe exponential backoff (a delay for
  a 1-based attempt, a ceiling clamp and a `shouldRetry` cap) and `TunnelVerdict`
  classifies a leak check (`TUNNELED` / `LEAK` / `UNREACHABLE`) by comparing the
  pre-tunnel baseline public IP with the current one. Both are side-effect free and
  unit-tested without a clock, thread, tunnel or network.
- **ConnectionType / VpnProfile / VpnStatus** — the tolerant type enum (`fromText`
  maps an `nmcli` TYPE or a file extension), the normalising profile bean (uuid ⇒
  NetworkManager-managed, configPath ⇒ file-driven, plus the `autoConnect` and
  migration-safe `killSwitch` flags) and the immutable status record (clears
  identity when disconnected, derives its own `summary()`).
- **VpnSettings / VpnStore** — the Jackson preferences bean and defensive JSON
  persistence (a corrupt/missing file yields defaults / an empty list, never throws).

## Roles

- **Architect** — Everything lives in this package: the value types, the AWT-free
  `VpnBackend` seam, `VpnStore` (Jackson I/O), `VpnPanel` (the one Swing UI) and the
  two thin entry points. The same panel drives both desktops: 3D via
  `TitledSwingWindow`, 2D via `Desktop2DAppRegistry.PANEL_APPS` keyed on `Vpn`. **No
  new dependency is added** — the tunnel is delegated to `nmcli` / `openvpn` /
  `wg-quick`. Keep all command/parse logic in the seam (testable without a display),
  never in the panel.
- **Engineer / Developer** — Keep the tool resolution, command-building and terse
  parsing in `VpnBackend`; the panel only launches the guarded `ProcessBuilder` on a
  user action, on a daemon thread, and renders on the EDT. Prefer `nmcli` for
  discovered connections (it already holds the secrets); use `openvpn` / `wg-quick`
  only for an imported config, and track a standalone `openvpn` process so
  `stopTunnel()` can terminate it (it has no clean CLI teardown). Never synthesise a
  "connected" — report the tool's real exit/output via `describeResult`. Guard every
  process/`JFileChooser` path so it is reached only from a user action, never the
  constructor, so headless tests can build the panel. Never call `System.exit`. Obey
  the core UI/UX rulebook.
- **QA** — `VpnBackendTest`, `VpnModelTest`, `VpnStoreTest`, `VpnPanelTest`,
  `ReconnectPolicyTest` and `TunnelVerdictTest` run headless (60 tests): the backend
  suite asserts tool resolution, the exact command builders and the parse of recorded
  `nmcli` output; the model suite asserts the tolerant type mapping, profile
  normalisation (incl. the `killSwitch` default) and the status record; the store
  suite asserts settings/profile round-trips, kill-switch persistence and
  migration-safety (an older `profiles.json` loads with it off), corrupt/missing-file
  resilience and the dir override; `ReconnectPolicyTest` asserts the backoff
  schedule, ceiling clamp, retry cap and overflow safety; `TunnelVerdictTest` asserts
  the leak classification and IP parse; the panel suite drives `applyStatus` /
  `applyPolledStatus` / `addProfile` / `applyDiscoveredProfiles` / `profileForConfig`
  / `saveProfile` / `applyVerdict` with synthetic values — asserting the kill-switch
  cut/restore and the scheduled reconnect on an unexpected drop — never spawning a
  tunnel, timer or network fetch (it restores the global `NetworkCut` before and
  after each test). For the 3D host use the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver network utility: list the host's VPN
  connections, import an OpenVPN/WireGuard config, and bring a tunnel up or down
  through the trusted system tool, in both desktops. Value = an honest,
  dependency-free front end to real VPN tooling (no secret is ever copied out).
- **Functional Analyst** — Spec this app as the *tunnel contract*: delegate the
  connection to `nmcli` / `openvpn` / `wg-quick` and report exactly what the tool
  said (never claim an in-tree stack or a false "connected"), remember imported
  profiles by path, and persist preferences — with the command/parse decisions hidden
  behind `VpnBackend`. Connecting is specified as "hand off to the resolved tool",
  and an unprivileged or missing-tool case as guidance, not a silent failure. The
  live contract adds: track the tunnel while open, reconnect an auto-connect profile
  that drops (bounded backoff), cut the desktop's network on an unexpected drop of a
  kill-switch-armed profile until it returns (fails closed, reusing `NetworkCut`),
  and verify the tunnel by proving the public egress IP changed — an unchanged IP is
  a leak, never a silent pass.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `VpnPanel` in an MDI internal frame.
  Conventional profile-dock + detail/status chrome with a banner and a backend
  picker, never a click-cycling 3D idiom; keep both surfaces pixel-identical. A
  missing tool or an unprivileged connect must surface in the status line as
  guidance, not a silent failure or a false "connected".

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `VpnBackend` seam, the
honest nmcli/openvpn/wg-quick delegation and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
