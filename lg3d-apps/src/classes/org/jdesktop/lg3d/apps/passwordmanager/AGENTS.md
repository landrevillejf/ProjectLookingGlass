# Password Manager

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** encrypted password vault + generator |
| Entry point | `PasswordManager.main` → `TitledSwingWindow.show(...)`; `PasswordManagerClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (860x560, hosts `PasswordManagerPanel`); the same panel is reused in the 2D desktop |
| Start-menu name / group | Password Manager / **System** |
| Command | `java org.jdesktop.lg3d.apps.passwordmanager.PasswordManager` |
| Descriptor | `src/config/passwordmanager.lgcfg` → `config/demo` |
| Engine | **No in-tree crypto library — JDK only.** `VaultCrypto` derives the key with `PBKDF2WithHmacSHA256` (210,000 iterations, 256-bit) and seals the vault with `AES/GCM/NoPadding` (128-bit tag, 12-byte IV, 16-byte salt). A wrong master password fails GCM authentication (`AEADBadTagException`), which is how the panel tells "incorrect password" from "corrupt file" |
| Persistence | Jackson JSON under `~/.lg3d/passwordmanager` via `VaultStore`: `vault.json` holds **only** the sealed envelope (Base64 salt / iv / iterations / ciphertext); `settings.json` holds non-secret prefs. Override dir with `-Dlg3d.passwordmanager.dir` |
| Security | The master password, the derived key and the decrypted vault live in memory **only while unlocked** and are never written; auto-lock re-seals after idle; entry passwords are masked by default and excluded from search |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **PasswordManager** — thin 3D entry point; installs the hosted look and feel and
  shows `PasswordManagerPanel` in a `TitledSwingWindow`, wiring `setOnClose` to lock
  the vault and `frame.changeEnabled(false)`.
- **PasswordManagerClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running
  the panel outside the desktop; never calls `System.exit`.
- **PasswordManagerPanel** — the one Swing UI (no-arg constructor, no Java 3D): a
  **lock card** (create-vault with confirmation + strength meter, or unlock) and a
  **master/detail** workspace (entry list, category filter, add/edit/remove, a
  masked password field with reveal + copy, and a generator with length / class
  toggles and a strength bar). No crypto, timer or dialog runs until the user acts;
  the auto-lock `Timer` only starts once unlocked and is disabled when
  `autoLockMinutes == 0`.
- **VaultCrypto** — the AWT-free crypto seam: salt/IV generation, `deriveKey`,
  `encrypt`/`decrypt`, `generatePassword` (length 4..128, per-class selection) and
  `strengthScore`/`strength`/`describeStrength`. Pure and side-effect free, so the
  whole contract is unit-testable headless.
- **VaultEnvelope** — the sealed, persisted form of the vault: `seal(bytes, pw,
  iterations)` / `open(pw)` round-trip the plaintext, `isPresent()` reports whether
  a vault exists yet. Setters normalise null → empty and clamp iterations ≥ 1000.
- **PasswordEntry / PasswordVault** — the entry bean (title/username/url/category
  trim; password/notes do not; `matches()` searches everything **but** the secret)
  and the in-memory collection (add/remove/replace/get/find/categories, JSON
  round-trip, immutable `entries()` view).
- **PasswordManagerSettings / VaultStore** — Jackson beans (clamped auto-lock and
  generator options) and defensive JSON persistence (a corrupt/missing file yields
  defaults, never throws).

## Roles

- **Architect** — Everything lives in this package: the beans, the AWT-free
  `VaultCrypto` seam, `VaultStore` (Jackson I/O), `PasswordManagerPanel` (the one
  Swing UI) and the two thin entry points. The same panel drives both desktops: 3D
  via `TitledSwingWindow`, 2D via `Desktop2DAppRegistry.PANEL_APPS` keyed on
  `PasswordManager`. **No new dependency is added** — the crypto is the JDK's own
  PBKDF2 + AES-GCM. Keep all key-derivation / cipher / generation logic in
  `VaultCrypto` (testable without a display), never in the panel.
- **Engineer / Developer** — Keep PBKDF2/AES-GCM and the generator in `VaultCrypto`;
  the panel only calls the seam and holds the key in a field that `lock()` clears.
  Derive at `DEFAULT_ITERATIONS` (210k); never lower it in production paths (tests
  may pass the 1000 minimum for speed). Persist **only** the sealed `VaultEnvelope`
  — never the key, the plaintext vault or the master password. Guard every
  `JFileChooser` / clipboard path so it is reached only from a user action, never
  the constructor, so headless tests can build the panel. Never call `System.exit`.
  Obey the core UI/UX rulebook.
- **QA** — `VaultCryptoTest`, `VaultEnvelopeTest`, `PasswordVaultTest`,
  `VaultStoreTest` and `PasswordManagerPanelTest` run headless (45 tests): the crypto
  suite asserts deterministic key derivation, the guards, the seal/open round-trip,
  password generation per class and the strength scoring bounds; the envelope suite
  asserts presence, the wrong-password throw and iteration clamping; the vault suite
  asserts the collection contract, JSON round-trip and entry normalisation; the store
  suite asserts settings/envelope round-trips, corrupt/missing-file resilience and
  the dir override; the panel suite drives create/lock/unlock and add-entry through
  the package-private hooks with auto-lock disabled — never deriving on a timer. For
  the 3D host use the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver credential safe: keep logins/notes in one
  AES-GCM-encrypted vault behind a master password, generate strong passwords, and
  auto-lock on idle — in both desktops. Value = an honest, dependency-free vault
  where only ciphertext ever touches disk.
- **Functional Analyst** — Spec this app as the *vault contract*: derive a key from
  the master password (never store it), seal/open the vault with authenticated
  encryption so a wrong password is detected, and persist only the envelope — with
  the crypto decisions hidden behind `VaultCrypto`. Auto-lock and masking are
  specified as safety defaults, and search is specified to never match a secret.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `PasswordManagerPanel` in an MDI internal
  frame. Conventional lock → master/detail chrome with a generator and a strength
  meter, never a click-cycling 3D idiom; keep both surfaces pixel-identical. A wrong
  master password, a too-weak choice or a missing vault must surface in the status
  line as guidance, not a silent failure; passwords stay masked until revealed.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `VaultCrypto` seam, the
JDK-only PBKDF2 + AES-GCM envelope and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
