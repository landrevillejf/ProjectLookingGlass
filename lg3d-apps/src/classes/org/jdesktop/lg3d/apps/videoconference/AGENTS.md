# Video Conference Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (Jitsi Meet video-conference client) |
| Entry point | `VideoConference.main` → `TitledSwingWindow.show(...)`; `VideoConferenceClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `VideoConferencePanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Video Conference / **Internet** |
| Command | `java org.jdesktop.lg3d.apps.videoconference.VideoConference` |
| Descriptor | `src/config/videoconference.lgcfg` → `config/demo` |
| Conferencing backend | **Jitsi Meet** deep links built by `JitsiUrlBuilder`; the real WebRTC audio/video runs in the system browser (`java.awt.Desktop.browse`) or an external meeting command |
| Persistence | Jackson JSON under `~/.lg3d/videoconference` (rooms, history, settings) via `VideoConferenceStore`; override dir with `-Dlg3d.videoconference.dir`. The invitee **address book is shared** — `org.jdesktop.lg3d.contacts.ContactStore` (lg3d-core) under `~/.lg3d/contacts`, edited by the Contacts app |
| Camera preview | `CameraCapture` seam + `CameraPreview`; **no native capture library ships**, so it degrades to an honest animated placeholder (backend pluggable via `-Dlg3d.videoconference.camera.backend`) |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **VideoConference** — thin 3D entry point; installs the hosted look and feel and
  shows `VideoConferencePanel` in a `TitledSwingWindow`, wiring `setOnClose` to
  `frame.changeEnabled(false)`.
- **VideoConferenceClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running
  the panel outside the desktop; never calls `System.exit`.
- **VideoConferencePanel** — the one Swing UI (no-arg constructor, no Java 3D):
  toolbar (New Room / Random name / Add Contact / Settings), a Rooms/Contacts/
  History sidebar — the Contacts tab is a **live view of the shared address
  book** (add/edit/delete go straight to `ContactStore`), a lobby with the camera
  preview, room + server fields, mic/camera mute toggles, Join and
  Copy-invite-link, and a status line.
- **JitsiUrlBuilder** — the pure, AWT-free protocol logic: room-name
  sanitisation, domain normalisation/resolution, friendly room-name generation,
  share vs join URL construction (`#config.*` / `#userInfo.*` fragment, RFC 3986
  percent-encoding), effective mute resolution and external-command templating.
  The headless unit-test seam.
- **ConferenceRoom / CallHistoryEntry / VideoConferenceSettings** —
  Jackson-serializable model beans. Invitees are core
  `org.jdesktop.lg3d.contacts.Contact` beans (the app-private `Contact` model
  was deleted when the address book moved to lg3d-core).
- **VideoConferenceStore** — defensive JSON persistence (corrupt/missing files
  yield empty/defaults, never throw).
- **CameraCapture / CameraPreview** — the capture seam and its rendering surface.

## Roles

- **Architect** — Everything lives in this package: model beans, `JitsiUrlBuilder`
  (pure logic), `VideoConferenceStore` (Jackson I/O), `CameraCapture`/`CameraPreview`
  (media seam), `VideoConferencePanel` (the one Swing UI) and the two thin entry
  points. The invitee list is **not** app-private state: it reads/writes the
  desktop-wide `org.jdesktop.lg3d.contacts.ContactStore`, the same book the
  Contacts app edits and the Messenger saves peers into (one address book, many
  readers). The same panel drives both desktops: 3D via `TitledSwingWindow`, 2D via
  `Desktop2DAppRegistry.PANEL_APPS` keyed on `VideoConference`. Jackson + SLF4J are
  already `lg3d-apps` compile deps (added for the SSH client) and are on the
  hand-assembled `:lg3d-core:run` / `releaseBundle` classpath, so the in-JVM launch
  resolves. **No new third-party dependency and no native media stack are added.**
- **Engineer / Developer** — Keep all URL/encoding logic in `JitsiUrlBuilder`
  (pure, unit-tested); the panel only calls it. Guard every browser/clipboard/
  modal path on `!GraphicsEnvironment.isHeadless()` so headless tests never open a
  window or block. Keep the rendered surface free of Synth-only widgets (no combo
  boxes on the SwingNode-painted panel — combos are fine in the modal dialogs,
  which are separate top-level windows). Never call `System.exit`. `CameraCapture`
  must degrade to `NullCameraCapture`; do not add a native capture dependency
  without a design decision. Jogamp packages only where 3D is touched (none here);
  obey the core UI/UX rulebook.
- **QA** — `JitsiUrlBuilderTest`, `VideoConferenceStoreTest`,
  `VideoConferenceModelTest` and `VideoConferencePanelTest` run headless: they
  assert URL sanitisation/encoding/config fragments, JSON round-trips to a temp
  dir, model bean invariants, and that `join()` builds the expected URL and records
  history without launching a browser (headless returns `URL_SHOWN`). For the 3D
  host use the in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`);
  a black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver communication utility: create/join/invite
  to video meetings from the desktop without a separate app, against the public
  Jitsi Meet service or a self-hosted Jitsi. Value = one-click meetings with saved
  rooms, the desktop-wide address book and a recent-calls log, in both desktops.
- **Functional Analyst** — Spec this app as the *conference-client contract*:
  resolve a room on a domain, build the correct join and share deep links with the
  user's identity and mute state, launch the meeting, persist rooms/history/
  settings, and read/write invitees through the shared address book. The
  transport (Jitsi/WebRTC in the browser) is an implementation detail behind
  `JitsiUrlBuilder` + the launcher.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources — call that out. Done = build + headless tests +
  `./run-lg3d.sh` capture/log evidence. Branch → PR against `main`; never commit to
  `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `VideoConferencePanel` in an MDI internal
  frame. Conventional conferencing-lobby chrome (preview + room field + Join),
  never a click-cycling 3D idiom; keep both surfaces pixel-identical. The camera
  placeholder must clearly say where the real video appears (in the meeting
  window) so the absent native preview is not read as a bug.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `JitsiUrlBuilder` seam,
the Jitsi/browser transport boundary, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
