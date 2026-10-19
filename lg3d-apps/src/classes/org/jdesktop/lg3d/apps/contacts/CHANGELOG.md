# Changelog

All notable changes to Contacts are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Contacts — a production address book for the 2D/Swing and 3D desktops**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.contacts`; shared store in `lg3d-core`,
  `org.jdesktop.lg3d.contacts`; registration in `lg3d-core`, icon in `lg3d-core`
  resources) — the desktop's contact surface is now a fully functional address
  book replacing the read-only 2006-era Contact 3D card browser. The data layer
  is a new **desktop-wide shared store in lg3d-core** (the only module every
  consumer depends on): a `Contact` bean (names, nickname, multi-valued
  e-mails/phones/tags, organisation, title, notes, favourite flag) and a
  `ContactStore` with synchronized CRUD, search and favourites-first ordering
  over `~/.lg3d/contacts/contacts.json` (Jackson — promoted to an `api`
  dependency of lg3d-core so consumers see the annotations on `Contact`), with
  atomic temp-file + `ATOMIC_MOVE` saves, defensive reads (missing/corrupt file
  degrades to an empty book, never throws) and a `-Dlg3d.contacts.dir` override
  for tests. The book **starts empty and is never seeded with demo data**. The
  one `ContactsPanel` (plain Swing, no Java 3D) serves both desktops: an
  incremental-search toolbar, New / Edit / Delete (confirmed), vCard 3.0
  import/export for interoperability with external address books, a
  favourites-first list, a read-only detail card and an in-panel CardLayout
  editor (never a modal escaping the SwingNode capture). In 2D
  `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  `Contacts` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`. Registered in the start menu (**Office** group) via
  `contacts.lgcfg` in `lg3d-apps/src/config`, with an address-book icon in
  `lg3d-core/src/resources/images/icon/contacts.png`. One book, many readers:
  the Agenda/Mail `ContactDirectory`, the Messenger **Save** button and the
  Video Conference contacts tab all read/write the same store (see Changed).
  Covered by headless JUnit 5 tests (`ContactStoreTest` — CRUD, persistence,
  search, ordering, corrupt-file recovery, display-name fallbacks;
  `ContactsPanelTest` — construction, empty state, create/edit/cancel flows,
  incremental search, vCard codec round-trip and defensive parse) plus a
  `Desktop2DAppRegistryTest` mapping assertion.
