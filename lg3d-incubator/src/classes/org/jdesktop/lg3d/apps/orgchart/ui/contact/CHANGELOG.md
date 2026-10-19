# Changelog

All notable changes to Contact are documented here.

## [1.53.1] — 2026-10-06

### Removed

- **The legacy read-only 2D contact card browser and the Contact 3D start-menu
  entry** (`lg3d-incubator`
  `org.jdesktop.lg3d.apps.orgchart.ui.contact.ContactCardsPanel` + its test;
  `lg3d-apps/src/config/orgchart-contact.lgcfg`) — superseded by the production
  Contacts app above. The native-3D `Contact3D` card browser stays in-tree but
  dormant (no descriptor); it and the Chart org app remain the only readers of
  the legacy `/contacts` Preferences tree, which the org chart still needs for
  its `manager` hierarchy attribute.
