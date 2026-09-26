# ftp-client

> Role-aware guide for everyone working on **ftp-client**. The root
> [`../AGENTS.md`](../AGENTS.md) governs the build system, module map and commit
> conventions; [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md) is the
> canonical UI/UX rulebook for the 3D desktop. This file adds module-specific
> guidance and a shared **per-role view** so all roles stay coherent.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | A self-contained, **protocol-neutral file-transfer client** in the FileZilla tradition: site profiles, a dual local \| remote browser, and a transfer queue with retry, resume and cancellation — over **FTP**, **FTPS** (explicit/implicit TLS) and **SFTP** (SSH). Secure by default, reliable under flaky links, and responsive because every blocking call runs off the EDT. |
| Root package | `org.jdesktop.lg3d.ftpclient.*` — `model` (profiles, settings, obfuscation, persistence), `net` (the `RemoteClient` seam + FTP/SFTP adapters, listing parser, path helpers), `session` (one active connection + the transfer queue), `ui` (the Swing client). |
| Depends on | Apache Commons Net (FTP/FTPS), JSch *mwiede* fork (SFTP, keeps the `com.jcraft.jsch.*` packages), Jackson (JSON persistence), slf4j (+nop); `runtimeOnly` BouncyCastle `bcprov-jdk18on` for extended SFTP host-key algorithms (e.g. Ed25519). Tests use JUnit 5, Mockito, AssertJ and in-JVM MockFtpServer. **No `lg3d-core` dependency.** |
| Surfaced as | The **"FTP Client"** start-menu app (Internet group): `FtpClientPanel` (`lg3d-apps`) → `FtpClient` (`TitledSwingWindow`/`SwingNode` in 3D) or an MDI frame in the 2D desktop via `Desktop2DAppRegistry.PANEL_APPS`. |
| Build / test | `./gradlew :ftp-client:test` (138 tests, headless, in-JVM MockFtpServer + mocked JSch) · `:ftp-client:build` · `:ftp-client:pitest` (report-only). |
| Config | Profiles + settings persist as JSON under `~/.lg3d/ftpclient/`; SFTP host keys are recorded in a `known_hosts` file there too. Override the directory with the `lg3d.ftpclient.dir` system property (`ProfileStore.DIR_PROPERTY`) for test isolation. |

## How the roles work together

This module is a **pure Java/Swing library with no lg3d coupling**. The Architect
guards the protocol-neutral `RemoteClient` seam and the strict "all network I/O
off the EDT" rule; Engineers keep the core compiling only against that seam (the
FTP and SFTP libraries are behind it) and keep the panel constructor
non-throwing; QA runs the headless suite against an in-JVM MockFtpServer and a
mocked JSch channel; the Analysts keep the credential-handling and
security-posture contracts explicit; the PM tracks the desktop-integration
dependency (`lg3d-apps` wrapper + run/release classpath). Everyone works from
this file plus the root `AGENTS.md`.

## Architect

- Keep the core **protocol-neutral**: the session and UI layers compile only
  against the `RemoteClient` interface (`net`). FTP/FTPS (Commons Net) and SFTP
  (JSch) live entirely behind `FtpRemoteClient` / `SftpRemoteClient`, selected by
  `RemoteClientFactory` from a `SiteProfile`'s `Protocol`. Never let a transport
  library's types leak above `net`.
- Preserve the **layering**: `model` (data + persistence) → `net` (blocking,
  path/listing primitives) → `session` (`ConnectionManager` owns the single live
  client + the `TransferManager`) → `ui` (Swing). Lower layers never import Swing.
- **All network I/O runs off the EDT** (`SwingWorker`), with connect/data
  timeouts, retry-with-backoff, resume from a partial destination, cooperative
  cancellation via the `ProgressListener.isCancelled()` poll, and strict resource
  close. This is the reliability contract; do not regress it.
- The module is **not** wired into the display server directly — it is surfaced
  only through the `lg3d-apps` panel. Keep that loose coupling; do not add a hard
  `lg3d-core` dependency.

## Engineer / Developer

- **Passwords are never saved by default** (`AppSettings.allowSavePasswords =
  false`). Saving is an explicit opt-in and stores an *obfuscated* value
  (`PasswordObfuscator`, `obf1:` prefix — XOR + Base64, **not** encryption) under
  `~/.lg3d/ftpclient/`; the UI warns that it is only obfuscated. Never commit a
  profile with a secret, and never log one.
- Persistence is JSON via Jackson (`ProfileStore`, `AppSettings`, `SiteProfile`).
  Point the directory at a temp folder in tests via `ProfileStore.DIR_PROPERTY`;
  do not read or write the developer's real `~/.lg3d/ftpclient` from a test.
- **SFTP host keys are verified trust-on-first-use** against
  `~/.lg3d/ftpclient/known_hosts` with `StrictHostKeyChecking=yes`: an unseen key
  is accepted once and recorded, a *changed* key is rejected before any prompt.
  Verification is non-interactive, so it stays headless-testable — do not add an
  interactive "accept this key?" dialog to the connect path.
- **Pin transport APIs from the resolved jar, not memory.** The JSch fork
  deprecates several overloads (`Session.setPassword(String)`, the `int`-mode
  `get`); use `setPassword(byte[])` and `InputStream get(String, monitor, long
  skip)` / `OutputStream put(String, monitor, int mode, long offset)`. For
  explicit FTPS, `FTPSClient.connect()` already issues `AUTH TLS` — do not call
  the `protected` `execAUTH()` again; just `execPBSZ(0)` + `execPROT("P")`.
- Keep the `ui` panel constructors **non-throwing** and headless-safe: build with
  `java.awt.headless=true`, create modal dialogs only on user action, and let the
  wrapper degrade to a readable "unavailable" pane on `RuntimeException` /
  `LinkageError`.
- The `:lg3d-core:run` and `:lg3d-core:releaseBundle` classpaths are
  hand-assembled, so a new runtime dep needs an explicit detached-configuration
  entry there (see *Desktop integration*).
- **Folder (recursive) transfer is intentionally not wired.** The browsers queue
  single files and log a skip for a selected directory; do not claim recursive
  directory upload/download.

## QA

- The suite is **headless** (`java.awt.headless=true`, set by the module's test
  task). FTP is exercised **end-to-end against an in-JVM MockFtpServer** bound to
  an ephemeral port (connect/list/upload/download/size/exists/mkdir/rename/
  delete); SFTP is exercised through a **mocked JSch `ChannelSftp`** injected via
  a package-private test seam (no live SSH server). Run
  `./gradlew :ftp-client:test` (138 tests).
- Isolate persistence with `@TempDir` + `ProfileStore.DIR_PROPERTY`; give the
  SFTP seam its own temp `known_hosts` so host-key state never leaks.
- Drive `TransferManager.run(...)` **directly and synchronously** with a mocked
  `RemoteClient` and a zero retry backoff to test retry, resume offsets, progress
  and cancellation deterministically — do not spin the SwingWorker from a test.
- No external server runs in CI: a live FTPS/SFTP transfer is **manual smoke
  evidence** for the PR, not an automated test.
- PIT is report-only (`mutationThreshold = 0`,
  `avoidCallsTo = ['java.awt', 'javax.swing']`). A conservative `ftp-client`
  coverage floor is pinned in the root `build.gradle` just under the measured
  suite coverage so it never red-lines.

## Business Analyst

- Value: a **trustworthy, bundled file-transfer client** — move files to/from any
  FTP, FTPS or SFTP server without installing a separate tool, with resumable
  transfers that survive a dropped link.
- The **safety posture** is deliberate: FTPS and SFTP are the secure defaults,
  plain FTP is allowed but visibly flagged insecure, passwords are not stored
  unless the user explicitly opts in (and then only obfuscated, with a warning),
  and SFTP host keys are pinned on first use.

## Functional Analyst

- Specify behaviour in terms of **user-visible function** (manage sites, connect,
  browse local and remote trees, queue upload/download, create/rename/delete
  remote items, watch progress, cancel, clear) and the **transfer contract**
  (binary mode, resume from a partial destination, retry-with-backoff,
  cooperative cancel).
- Keep the **protocol model** explicit: `Protocol` = FTP (21, insecure), FTPS
  (21 explicit `AUTH TLS` / 990 implicit, TLS on control **and** data via
  `PROT P`), SFTP (22, SSH). `TransferMode` (ACTIVE/PASSIVE) applies to FTP(S)
  only and is ignored for SFTP.
- Document the single-file (non-recursive) transfer scope as a boundary, not a
  defect.

## Project Manager

- Commit scope is **`ftp-client`** (or `agents` for this file). Branch → commit →
  push → PR against `main`; never commit to `main`.
- This module is coupled to the desktop integration in `lg3d-apps` (the wrapper)
  and `lg3d-core` (the registry entry + run/release classpath + icon); a change
  to the public panel API or a new runtime dep must update those in the same PR.
- Done = `:ftp-client:test` green + `build` (jar + checkstyle report-only +
  jacoco) + the desktop integration (start-menu app) verified.

## UI/UX (2D)

- **2D Swing only.** The user surface is `FtpClientMainPanel` (toolbar + site
  selector NORTH, a `LocalBrowser` \| `RemoteBrowser` `JSplitPane` CENTER, and the
  transfer-queue `JTable` + message log + status SOUTH) embedded in
  `FtpClientPanel`; in the 3D desktop it is hosted on a `SwingNode` inside a
  `Frame3D` via `TitledSwingWindow`, and in the 2D desktop as an MDI internal
  frame — the **same panel** drives both.
- Because it is hosted offscreen on a `SwingNode`, follow the core rulebook's
  SwingNode rules: no modal dialogs escaping the capture (use in-panel overlays),
  `dispose()` the node when discarded, and hop to the EDT from listeners. See
  [`../docs/swingnode.md`](../docs/swingnode.md).
- The UI is **English**, light-themed, idiomatic Swing. Keep every blocking call
  (connect, list, transfer, mkdir, rename, delete) off the EDT so the window
  never freezes mid-transfer, and surface the insecure-plain-FTP warning and the
  obfuscated-password caveat where the user acts on them.

## Communication & coherence

- Single source of truth: this file (module), the core UI/UX rulebook (UI), the
  root [`../AGENTS.md`](../AGENTS.md) (build/commits). Conflict → root wins, fix
  here in the same PR.
- Every PR states: the layer touched (model / net / session / ui), whether the
  persistence schema, protocol handling or credential posture changed, whether a
  new runtime dep needs a run/release classpath entry, and the headless-test
  evidence.

## Commit / PR

- Conventional Commit scope **`ftp-client`** (or `agents` for this file).
  Imperative subject ≤ 50 chars; body wrapped at 72.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version.
- Stage only intended paths (never `git add -A`; exclude build output). Run
  branch → commit → push → PR against `main`.

---

## Overview

A self-contained, **protocol-neutral file-transfer client** for the desktop. It
manages site profiles (FTP, FTPS, SFTP), browses the local filesystem and the
remote server side by side, and moves files through a queue that retries with an
exponential backoff, resumes from a partial destination (FTP `REST` / SFTP
skip-or-append), reports byte-level progress and honours a cooperative cancel.

It is a plain Java 21 / Swing library with a Maven-standard layout
(`src/main/java`, `src/test/java`) and **no `lg3d-core` dependency**, mirroring
the `db-manager` / `update-manager` standalone-module pattern.

## Layers

- **`model`** — `Protocol` (FTP/FTPS/SFTP with default ports + secure flags),
  `TransferMode` (ACTIVE/PASSIVE), `SiteProfile` (id, name, protocol, host, port,
  user, optional password, savePassword, remoteDir, mode, encoding; `copy()` +
  equals-by-id), `PasswordObfuscator` (opt-in, obfuscated-not-encrypted),
  `AppSettings` (timeouts, retry/backoff, buffer size, passive/overwrite/save
  defaults) and `ProfileStore` (Jackson JSON under `~/.lg3d/ftpclient/`,
  forgiving reads, `StoreException` on write).
- **`net`** — `RemoteClient` (the protocol-neutral operations), `RemoteEntry`
  (immutable listing row), `RemotePaths` (pure `/`-path helpers), `ListParser`
  (Unix/Windows `LIST` + `MLSD` fallback), `ProgressListener` (byte progress +
  cancel poll), `FtpRemoteClient` (Commons Net `FTPClient`/`FTPSClient`, binary,
  passive/active, `REST` resume), `SftpRemoteClient` (JSch `ChannelSftp`, TOFU
  host-key verification, skip/append resume) and `RemoteClientFactory`.
- **`session`** — `ConnectionManager` (profiles + settings + the single active
  client, delegating remote ops, injectable factory for tests), `TransferManager`
  (the queue + retry/resume/cancel/progress policy, transport- and thread-agnostic),
  `TransferJob` (a mutable, thread-safe status row) and `TransferListener`.
- **`ui`** — `FtpClientMainPanel` (the root client), `SiteDialog`,
  `SettingsDialog`, `LocalBrowser`, `RemoteBrowser`, `TransferTableModel` and
  `UiFormats` (shared size/time/percent formatting).

## Desktop integration

The module is **not** wired into the display server directly; it is surfaced as
the **"FTP Client"** start-menu app (Internet group), exactly like the Database
Manager app:

- `lg3d-apps` → `org.jdesktop.lg3d.apps.ftpclient.FtpClientPanel` (a plain
  `JPanel` with a no-arg constructor) embeds `FtpClientMainPanel`. It never
  throws out of its constructor: if the client cannot be created (e.g. a missing
  runtime dependency) it degrades to a readable "unavailable" pane on
  `RuntimeException` / `LinkageError`.
- `FtpClient` is the 3D-desktop wrapper: `TitledSwingWindow.show(...)` hosts the
  panel on a `SwingNode` inside a `Frame3D`.
- `lg3d-apps/src/config/ftpclient.lgcfg` registers the start-menu item
  (`command = java org.jdesktop.lg3d.apps.ftpclient.FtpClient`).
- `Desktop2DAppRegistry.PANEL_APPS` maps the command to `FtpClientPanel`, so the
  2D/Swing desktop hosts the *same* panel as an MDI internal frame.
- The `:lg3d-core:run` **and** `:lg3d-core:releaseBundle` classpaths are
  hand-assembled from packaged jars, so they explicitly add the `ftp-client` jar
  plus a detached configuration carrying its runtime deps: commons-net, jsch,
  jackson-databind, slf4j-api/nop and bcprov-jdk18on.

## Build & test

All Gradle invocations must run on the JDK 21 toolchain (Gradle 8.14 cannot run
on Java 25+):

```bash
export JAVA_HOME=/home/fedora/.jdks/jdk-21.0.12.1+1
./gradlew :ftp-client:test          # 138 tests, headless, MockFtpServer + mocked JSch
./gradlew :ftp-client:build         # jar + checkstyle (report-only) + jacoco
./gradlew :ftp-client:pitest        # report-only mutation testing (on demand)
```

## Conventions & gotchas

- Tests run with `java.awt.headless=true`; the Swing panel and browsers construct
  headless and never realize a window. FTP uses an in-JVM MockFtpServer; SFTP uses
  a mocked `ChannelSftp` (never a live server, never the developer's real config
  dir or `known_hosts`).
- `ProfileStore.DIR_PROPERTY` (`lg3d.ftpclient.dir`) overrides the config
  directory; tests set it to a `@TempDir` so they never touch `~/.lg3d/ftpclient`.
- `ListParser` interprets listing timestamps as UTC and requires a **full-field**
  date parse, so a time-only Unix date (`Jan 01 12:30`) is not misread as a year.
- PIT mutation testing is configured report-only (`mutationThreshold = 0`,
  `avoidCallsTo = ['java.awt', 'javax.swing']`) for parity with the other modules.
