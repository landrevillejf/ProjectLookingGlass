# Security Center

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** antivirus scanner + host security-posture overview |
| Entry point | `SecurityCenter.main` → `TitledSwingWindow.show(...)`; `SecurityCenterClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (880x600, hosts `SecurityCenterPanel`); the same panel is reused in the 2D desktop |
| Start-menu name / group | Security Center / **System** |
| Command | `java org.jdesktop.lg3d.apps.securitycenter.SecurityCenter` |
| Descriptor | `src/config/securitycenter.lgcfg` → `config/demo` |
| Engine | **No in-tree virus engine.** Scanning is delegated to an installed **ClamAV**: `clamdscan` (daemon) preferred, `clamscan` (standalone) fallback; definitions updated via `freshclam`. Posture is probed with `getenforce` (SELinux) and `firewall-cmd --state` (firewalld), plus **AppArmor** (`aa-status`) and the **SSH daemon** (init-system `status sshd`) through the shared `SecurityService` (§4.6), and the **tor** service (init-system `status`/`start`/`stop`/`restart tor`) plus its read-only `/etc/tor/torrc` and `/var/log/tor/notices.log` through the shared `PrivacyService` (§4.7). A missing tool degrades to honest guidance, never a fake result |
| Persistence | Jackson JSON under `~/.lg3d/securitycenter` (settings + scan history) via `SecurityCenterStore`; override dir with `-Dlg3d.securitycenter.dir`. Quarantine **moves** infected files to the configured folder (default `<config>/quarantine`) — it never deletes |
| Security | No secret and no scanned content is stored in config — only the target folder, scan options and finished scan summaries. Infected files are quarantined (moved), not destroyed |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **SecurityCenter** — thin 3D entry point; installs the hosted look and feel and
  shows `SecurityCenterPanel` in a `TitledSwingWindow`, wiring `setOnClose` to stop
  any live scan and `frame.changeEnabled(false)`.
- **SecurityCenterClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running
  the panel outside the desktop; never calls `System.exit`.
- **SecurityCenterPanel** — the one Swing UI (no-arg constructor, no Java 3D): an
  **Antivirus** tab (target + Browse, recursive / quarantine / update-first
  options, scanner picker, Update / Scan / Stop, a summary line and a findings
  list), a **Security Overview** tab (rating header, SELinux / Firewall / AppArmor
  / SSH daemon / Antivirus posture cards, a recommendations list, last-scan line
  and Refresh), a **Privacy** tab (`PrivacyPanel`: tor status / start / stop /
  restart plus read-only config and log views) and
  an EAST scan-history dock. No scanner, probe or dialog runs until the user
  presses Scan / Update / Refresh (or first opens the Overview tab); each external
  command runs on a daemon thread.
- **AntivirusBackend** — the AWT-free ClamAV seam: resolves the installed scanner,
  builds the exact `clamdscan`/`clamscan`/`freshclam` command lines and **parses**
  their output into a `ScanReport` / `VersionInfo`. Pure and side-effect free, so
  the whole contract is unit-testable headless against recorded ClamAV output.
- **SecurityProbe** — the AWT-free posture seam: builds the `getenforce` /
  `firewall-cmd --state` commands and parses them into `SelinuxMode` /
  `FirewallState` (an authorization failure is `UNKNOWN`, not "not running").
- **SecurityService** (shared, `org.jdesktop.lg3d.utils.system` in lg3d-core) — the
  §4.6 backend the Overview tab reads AppArmor and the SSH daemon through:
  `runRead(APPARMOR_STATUS)` / `runRead(SSHD_STATUS)` build the `aa-status` and
  init-system `status sshd` vectors and parse them (`parseAppArmor` /
  `parseSshdState`), never spawning until the user refreshes. This panel keeps
  `SecurityProbe` for its SELinux/firewall rating and only reaches for the shared
  service for the two new host rows.
- **PrivacyPanel** — the Security Center's *Privacy* tab (§4.7): a thin Swing
  front-end over the shared `PrivacyService` that shows the tor service state and
  drives start/stop/restart through the §4.1 init abstraction, plus read-only
  `/etc/tor/torrc` and `/var/log/tor/notices.log` views. It also carries the
  **Private (Tor) mode** section — the Whonix-like desktop-wide anonymity switch
  over the shared `TorPrivateMode` (Enable / Disable / *Verify no leak*, a live
  state line and a red CUT banner): Enable may start tor through polkit and runs
  off the EDT, Disable only clears the in-JVM SOCKS enforcement (no privilege),
  and Verify fetches `check.torproject.org` through the proxy only. Plain labels /
  buttons / text area (never a combo box), headless-safe to construct (it only
  reads the in-memory `TorPrivateMode.state()`, never spawns), and it never writes
  `/etc` or `/var/log`.
- **TorPrivateMode / NetworkCut** (shared, `org.jdesktop.lg3d.utils.system` in
  lg3d-core) — the Whonix-like private-mode engine and the global fails-closed
  cut flag it raises. `TorPrivateMode` forces JVM SOCKS (`socksProxyHost` /
  `socksProxyPort`, overridable via `lg.tor.socksPort`) + a SOCKS5 `ProxySelector`,
  runs a 5 s daemon monitor that trips `OFF/ENABLING/ON → CUT` the moment tor
  stops (raising `NetworkCut`, so every client socket is refused rather than
  leaking clearnet), and does the proxy-only leak check; every decision
  (`transition` / `monitorTick` / `proxyProperties` / `proxyEnv` / `parseTorCheck`)
  is pure and headless-tested.
- **PrivacyService** (shared, `org.jdesktop.lg3d.utils.system` in lg3d-core) — the
  §4.7 backend: it detects tor / init / file presence with non-spawning probes,
  **delegates the whole tor lifecycle to `InitSystemService`** (no tor-specific
  reimplementation, §6), parses the init-specific status shapes (`parseTorState` /
  `describeTor`) and reads the config / log through bounded NIO file reads
  (`readFile` / `FileContent`, a trailing 256 KiB window). Read-only paths stay
  unprivileged; only the lifecycle mutations escalate (polkit).
- **ScanReport / Detection / VersionInfo / SecuritySnapshot** — immutable records:
  a parsed scan (counts, detections, exit code, whether the summary was seen), a
  single finding (`INFECTED` / `ERROR`), the engine/database version and the
  aggregated posture (which derives plain-language `concerns()` and a `rating()`).
- **SecurityCenterSettings / ScanRecord / SecurityCenterStore** — Jackson beans
  (clamped scan options; a finished scan's outcome) and defensive JSON persistence
  (a corrupt/missing file yields defaults / an empty history, never throws).

## Roles

- **Architect** — Everything lives in this package: the records, the two AWT-free
  seams (`AntivirusBackend`, `SecurityProbe`), `SecurityCenterStore` (Jackson I/O),
  `SecurityCenterPanel` (the one Swing UI) and the two thin entry points. The same
  panel drives both desktops: 3D via `TitledSwingWindow`, 2D via
  `Desktop2DAppRegistry.PANEL_APPS` keyed on `SecurityCenter`. **No new dependency
  is added** — virus scanning is delegated to an installed ClamAV, posture to
  `getenforce` / `firewall-cmd`. Keep all command/parse logic in the seams
  (testable without a display), never in the panel.
- **Engineer / Developer** — Keep the ClamAV command-building and output parsing in
  `AntivirusBackend` and the posture parsing in `SecurityProbe`; the panel only
  launches the guarded `ProcessBuilder` on a user action, on a daemon thread, and
  hops back to the EDT to render. Prefer `clamdscan`, but detect the daemon-down
  case (`ScanReport.ranSuccessfully()` is false with total errors and nothing
  scanned) and fall back to `clamscan`, remembering it for the session. Quarantine
  with `--move`, never delete. Guard every process/`JFileChooser` path so it is
  only reached from a user action, never the constructor, so headless tests can
  build the panel. Never call `System.exit`. Obey the core UI/UX rulebook.
- **QA** — `AntivirusBackendTest`, `SecurityProbeTest`, `SecurityCenterStoreTest`,
  `SecurityCenterPanelTest` and `PrivacyPanelTest` run headless (48 tests): the
  backend suite asserts
  scanner resolution, the command builders and the parse of recorded ClamAV output
  (clean, infected, per-file error, daemon-down, empty) plus version/update
  parsing; the probe suite asserts SELinux/firewall parsing (including the
  authorization-failure → `UNKNOWN` path) and the snapshot concerns/rating; the
  store suite asserts bean invariants, JSON round-trips, corrupt/missing-file
  resilience and the quarantine dir; the panel suite drives `applyReport` /
  `applySnapshot` / `addRecord` / `renderHostServices` with synthetic values —
  never spawning a scanner — and asserts the three tabs (Antivirus / Security
  Overview / Privacy); `PrivacyPanelTest` asserts headless construction, the
  tor / init / polkit / file button gating and the private (Tor) mode section
  (state line, hidden CUT banner while off, and Enable/Disable/Verify gating). The
  `TorPrivateMode` / `NetworkCut` decisions behind it are covered by
  `TorPrivateModeTest` / `NetworkCutTest`. The AppArmor / SSH vectors and parsers
  behind `renderHostServices` are covered by `SecurityServiceTest`, and the tor
  lifecycle vectors, `parseTorState` and bounded file reads behind `PrivacyPanel`
  by `PrivacyServiceTest`, all in lg3d-core.
  For the 3D host use the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver security utility: scan a folder for viruses
  through the trusted ClamAV engine, quarantine (not delete) what it finds, keep a
  history of scans, and see the host's SELinux / firewall / antivirus posture at a
  glance with plain-language recommendations, in both desktops. Value = an honest,
  dependency-free front end to real security tooling.
- **Functional Analyst** — Spec this app as the *security contract*: delegate virus
  scanning to ClamAV and report exactly what it found (never claim an in-tree
  engine), aggregate the host posture into a rating and recommendations, and
  persist scan history — with the command/parse decisions hidden behind
  `AntivirusBackend` and `SecurityProbe`. Scanning is specified as "hand off to
  ClamAV", and posture as "read `getenforce` / `firewall-cmd`", never as a claim
  the desktop enforces policy itself.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `SecurityCenterPanel` in an MDI internal
  frame. Conventional tabbed chrome (Antivirus / Security Overview / Privacy) with a findings
  list, posture cards and a history dock, never a click-cycling 3D idiom; keep both
  surfaces pixel-identical. A missing scanner, a stopped daemon or an unprivileged
  firewall query must surface in the status line / recommendations as guidance, not
  a silent failure or a false "secure".

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `AntivirusBackend` /
`SecurityProbe` seams, the honest ClamAV delegation and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
