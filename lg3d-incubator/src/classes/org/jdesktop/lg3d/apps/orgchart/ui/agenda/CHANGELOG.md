# Changelog

All notable changes to Agenda are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Agenda now sends real meeting invitations** — inviting attendees no longer
  stops at the local attendee list: both agenda surfaces (native-3D `Agenda3D`
  and 2D/Swing `AgendaPanel`) gained a **Send Invites** control that composes a
  real **RFC 5545 iCalendar `METHOD:REQUEST`** for the selected appointment's
  occurrence in the displayed week and e-mails it through the Mail app's
  existing Jakarta Mail/SMTP backend — so recipients get an `invite.ics` their mail client can accept, tentatively
  accept or decline. `InvitationBuilder` is a pure, AWT-free composer (UTC
  `DTSTART`/`DTEND`, RFC 5545 TEXT escaping, octet-safe 75-column folding,
  `ORGANIZER`/`RSVP=TRUE` `ATTENDEE` lines) and `InvitationSender` is a
  UI-agnostic send seam over `MailSessionManager`: it resolves the desktop's
  **default mail account**, skips invitees without an address, and turns every
  failure (no/incomplete account, no mailable invitee, SMTP error) into a
  user-safe dialog — never a stack trace, never a logged password. Credentials
  follow the Mail app's rules: SAVED-mode accounts send silently, ASK-mode
  accounts prompt in the 2D panel (Swing dialog) and report the manager's
  user-safe error in the 3D app (which, like `Mail3D`, installs no prompt).
  The blocking SMTP round trip runs on a daemon thread off the EDT/scene
  thread. `AgendaGrid.dateFor` became public so `Agenda3D` can date an invite
  for the displayed week. Covered by new headless JUnit 5 suites
  (`InvitationBuilderTest` 13 tests pinning the wire format, folding and
  escaping; `InvitationSenderTest` 7 tests over a recording fake
  `MailService`) plus 2 new `AgendaPanelTest` cases driving the panel's send
  seam.

### Changed

- **Agenda, Mail, Messenger and Video Conference now share one address book**
  (`lg3d-incubator` `org.jdesktop.lg3d.apps.orgchart.ui.agenda` +
  `org.jdesktop.lg3d.apps.mail`; `lg3d-apps`
  `org.jdesktop.lg3d.apps.messenger` / `.videoconference`) — every contact
  consumer was rewired from its own private or demo-seeded data to the shared
  `org.jdesktop.lg3d.contacts.ContactStore`. The agenda/mail `ContactDirectory`
  keeps its class name and API but is now a read-only adapter over the core
  store, so Agenda 3D / `AgendaPanel` attendees and Mail 3D / `MailPanel`
  recipients come from the real desktop-wide book with **no `java.util.prefs`
  `/contacts` reads and no bundled demo `contacts.xml` seeding on this path**.
  The fake free/busy presence flag was removed with it (an address book holds
  identity, not presence): agenda attendee chips and week-grid dots now show
  **known (green) vs unknown (grey)** instead of busy/free. The Video Conference
  contacts tab became a live view of the shared book — its private `Contact`
  model, `contacts.json` and the `loadContacts`/`saveContacts` store methods are
  gone, and add/edit/delete in the tab go straight to `ContactStore`, so an
  invitee added there is instantly visible in the Contacts app and the agenda.
  The Messenger grows a **Save** toolbar button that writes the selected
  private-chat peer into the shared book (tagged `messenger`, deduplicated
  case-insensitively on nickname/display name). Covered by updated headless
  tests — all pointed at temp dirs, never the real
  `~/.lg3d/contacts`.

## [1.27.0] — 2026-09-26

### Added

- **Weekend and holiday marking in the 2D/Swing desktop Agenda** — the Swing `AgendaPanel` week grid
  (the 2D/Swing counterpart of the native-3D `AgendaGrid`) now colours weekends and
  statutory holidays to match the 3D app: each column is anchored to a real date,
  weekend columns get a blue wash, holiday columns a red wash, and the current
  week's today column an accent bar, with a header legend keying the three. Holiday
  and weekend classification is routed through the shared public
  `org.jdesktop.lg3d.utils.prefs.HolidayRegions` seam backed by the bundled
  `jbusinessday` library , honouring the
  same persisted, locale-resolved region the taskbar calendar and the 3D agenda use
  (Control Center → Desktop, or `-Dlg.agenda.holidayRegion` as an explicit
  override) and re-reading it per repaint so a region change applies without a
  restart; the classification degrades to plain untinted columns if the library is
  ever absent. Covered by headless JUnit 5 tests (extended `AgendaPanelTest`, +5).


## [1.9.0] — 2026-09-24

### Added

- **Agenda 3D** — a new native-3D week-agenda app that interacts **one-way** with the ported
  **Contact 3D**. Both run in the same JVM and share the user `Preferences` root,
  so `Agenda3D` reads the very same `/contacts` node `Contact3D` imports (falling
  back to the bundled `contacts.xml` if Contact 3D has not run yet) and offers
  those contacts as meeting attendees, drawing each invitee's live free/busy
  presence as a coloured chip on the appointment block — **Contact 3D itself is
  unchanged**. A `Frame3D` (with the standard decoration) lays out an `AgendaGrid`
  week view — seven day columns by ten one-hour rows (08:00–18:00) rasterised into
  a single live texture with the `Histogram3D` `ImageComponent2D.set` recipe — over
  a strip of runtime-drawn `AgendaButton` controls (New / Del / Title / Today /
  Att- / Att+ and Day / Hr / Dur nudges). Clicking a cell selects an appointment
  or moves the creation cursor; appointments are **user-created only** (the agenda
  starts empty) and persist under `/agenda/appointments`, mirroring how Contact 3D
  stores contacts. Registered in the start menu (Office) by
  `lg3d-apps/src/config/agenda3d.lgcfg`.
