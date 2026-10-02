# Contacts Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** desktop address book (replaces the legacy read-only Contact 3D card browser and its bundled demo `contacts.xml`) |
| Entry point | `Contacts.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (hosts `ContactsPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop as an MDI internal frame |
| Start-menu name / group | Contacts / **Office** |
| Command | `java org.jdesktop.lg3d.apps.contacts.Contacts` |
| Descriptor | `src/config/contacts.lgcfg` → `config/demo` |
| Data | `~/.lg3d/contacts/contacts.json` via `org.jdesktop.lg3d.contacts.ContactStore` (lg3d-core) — starts **empty**, never seeded with demo data; test override: `-Dlg3d.contacts.dir=<dir>` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Contacts** — thin entry point; installs the hosted look and feel and shows
  `ContactsPanel` in a `TitledSwingWindow` (`WIDTH_PX` × `HEIGHT_PX`).
- **ContactsPanel** — a plain `JPanel` (no Java 3D): toolbar (incremental
  search, New/Edit/Delete, vCard import/export), a favourites-first contact
  list, a read-only detail card and an in-panel CardLayout editor (no modal
  dialog escaping the SwingNode capture except delete confirmation and file
  choosers). Multi-valued e-mails/phones/tags, notes, favourite flag.
- **`VCard`** (nested) — a minimal vCard 3.0 reader/writer for
  interoperability with external address books.
- **`org.jdesktop.lg3d.contacts.Contact` / `ContactStore`** (lg3d-core) — the
  shared model and JSON store: synchronized CRUD, atomic temp-file +
  `ATOMIC_MOVE` saves, defensive reads (missing/corrupt file degrades to an
  empty book, never throws).

## Roles

- **Architect** — The address book lives in **lg3d-core**
  (`org.jdesktop.lg3d.contacts`) because it is the only module every consumer
  depends on: this app edits it, the incubator Agenda/Mail read it through the
  `ui.agenda.ContactDirectory` adapter, the Messenger saves private-chat peers
  into it, and the Video Conference contacts tab is a live view of it. One
  book, many readers; dependency direction stays apps/incubator → core. The
  legacy `java.util.prefs` `/contacts` node and demo `contacts.xml` seeding are
  gone from this path (only the dormant Contact 3D and the Chart org app still
  read them). Jackson is an `api` dependency of lg3d-core for this reason.
- **Engineer / Developer** — Follow the core UI/UX rulebook for the SwingNode
  host: offscreen paint, EDT hops, hosted LAF via `installHostedLookAndFeel`.
  Keep the `ContactsPanel` constructors **non-throwing** and never write
  directly to the JSON file — always go through `ContactStore` so saves stay
  atomic and synchronized. Store no secrets in contacts. Jogamp packages only
  for any new 3D code.
- **QA** — `ContactStoreTest` (lg3d-core) covers CRUD, persistence, search,
  ordering, corrupt-file recovery and display-name fallbacks headless;
  `ContactsPanelTest` covers construction, empty state, create/edit/cancel
  flows, incremental search and the vCard codec. Both use temp dirs — never
  the real `~/.lg3d/contacts`. Verify the hosted window renders with an in-JVM
  probe + internal screencapture (`lg3d-core/lgscreen-*.png`); a black host
  capture under Wayland is not a defect.
- **Business Analyst** — The desktop's single trustworthy address book: create
  a contact once and it is inviteable from the Agenda, reachable from the Mail
  recipient list, saveable from the Messenger and callable from the Video
  Conference — with no demo data pretending to be real people.
- **Functional Analyst** — Spec this app as the *address-book contract*: which
  fields a contact carries, the JSON file location, the vCard 3.0
  interoperability surface, and the empty-first-run behaviour. Deletions are
  confirmed; a contact needs at least a name, nickname or e-mail.
- **Project Manager** — Commit scope `lg3d-apps` for the app; the feature also
  touches `lg3d-core` (shared store, registry mapping, icon) and
  `lg3d-incubator` (ContactDirectory adapter, agenda/mail) — call that out.
  Done = build + headless tests + `:lg3d-core:runtimeResources` +
  `./run-lg3d.sh` + capture/log evidence. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the shared `ContactsPanel` via `Desktop2DAppRegistry` — keep it
  identical across both surfaces. The editor is an in-panel card (never a
  modal that would escape the SwingNode capture); only delete confirmations
  and vCard file choosers are separate top-level windows.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host), the shared-store contract, and
the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
