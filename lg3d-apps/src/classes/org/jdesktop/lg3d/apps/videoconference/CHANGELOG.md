# Changelog

All notable changes to Videoconference are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Video Conference (Visioconférence) app for the 2D/Swing and 3D desktops**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.videoconference`; registration in
  `lg3d-core`, icon in `lg3d-core` resources) — a production **Jitsi Meet**
  conference client: a Swing lobby, address book and launcher. The user types or
  picks a room name, optionally sets a display name / e-mail and mute-on-join
  preferences, and presses **Join Meeting**; the client builds the correct Jitsi
  Meet deep link (`JitsiUrlBuilder`, with `#config.*` / `#userInfo.*` fragments
  and RFC 3986 percent-encoding) and hands it to the system browser or an external meeting command, where the real
  WebRTC audio/video session runs. It offers saved rooms (with per-room
  moderator / lock / mute overrides), contacts, a recent-call history and a
  settings dialog, plus a camera preview that degrades gracefully to an animated
  placeholder through the pluggable `CameraCapture` seam (no native A/V codec or
  embeddable HTML engine ships in-tree, so the browser owns the live session).
  Rooms, contacts, history and settings persist as JSON under
  `~/.lg3d/videoconference` via `VideoConferenceStore` (Jackson). The one panel
  serves both desktops: in 2D `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI
  internal frame, in 3D the `VideoConference` wrapper hosts it on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow`; a standalone `VideoConferenceClient` runs it outside the desktop. Registered in the start menu
  (**Internet** group) via `videoconference.lgcfg` in `lg3d-apps` `src/config`.
