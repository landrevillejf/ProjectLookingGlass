# LFS System Management — lg3d front-end contract

A binding specification for the agent that makes the **Project Looking Glass
(lg3d)** desktop the management surface for the whole **Beyond Linux From
Scratch (BLFS/LFS)** system it runs on.

> **Audience.** The lg3d application agent (and any human reviewing its work).
> **The one rule.** Every manager is a *front-end*. It MUST drive the CLI the
> LFS builder already installed (`systemctl`/`rc-service`/`sv`/`s6-svc`,
> `lfs-update`, `lpm`, `nmcli`/`dhcpcdctl`/`networkctl`, `lsblk`/`cryptsetup`,
> `nft`, `apparmor_status`/`getenforce`, `sshd`, `tor`) and MUST NOT
> re-implement, fork, or bypass that tool's own logic, locking, verification,
> or state files.
> **Companion contracts.** Runtime display rules are normative in
> [`docs/lfs-x11-contract.md`](docs/lfs-x11-contract.md); package/profile/kernel control
> is normative in [`lpm-lg3d-app-contract.md`](lpm-lg3d-app-contract.md). This
> document inherits both and only adds the whole-system management
> requirements.
> **Keywords.** MUST / MUST NOT / SHOULD follow RFC 2119. Everything under §3
> (Integration), §4 (Subsystems) and §6 (Prohibitions) is normative.
> **Mirror.** An identical copy of this file lives at the root of the LFS
> builder repository (`beyond-linux-from-scratch`), next to
> `lpm-lg3d-app-contract.md`. The two MUST be kept byte-identical.

---

## 1. Mandate

Deliver graphical managers that let a user administer the running LFS/BLFS
system — services & init, whole-system updates, packages/profiles/kernel,
network, storage/LUKS, security and privacy — **by invoking the CLIs the
builder installs**. The managers run as clients inside the lg3d session
described in `lfs-x11-contract.md` and appear composited in the 3D scene like
any other window.

The desktop is a *controller* of these binaries, never a replacement for them.
Where a manager already exists (Control Center panels, the `firewall`,
`securitycenter`, `vpn`, `taskmanager` apps, `lpm-console`), it is extended to
drive the real backend; new managers are added only for the genuine gaps
(services/init, whole-system updates, storage/LUKS, privacy/tor).

## 2. Why front-ends only (rationale)

Each subsystem is owned by a self-contained tool that holds all
correctness-critical behaviour:

- **Init/service tools** own dependency ordering, supervision (runit/s6), and
  the enable/disable symlinks; a GUI that edited `/etc/init.d`, `/var/service`
  or unit symlinks directly would desync the supervisor.
- **`lfs-update`** owns the backup of `/etc`+`/boot`, the version marker, the
  kernel-change detection and the `lpm upgrade` + `rebuild-kernel` + GRUB
  refresh sequence.
- **`lpm`** owns locking, transactional installs, checksum/GPG verification and
  history (see `lpm-lg3d-app-contract.md` §2).
- **`cryptsetup`** owns LUKS2 header/keyslot management; **`nft`** owns the
  ruleset; **`nmcli`/`dhcpcd`/`networkctl`** own connection and lease state.

Re-implementing any of this in Java would fork the source of truth and corrupt
system state. The GUI therefore shells out and presents the tool's own results.

## 3. Integration contract (normative — how to talk to every CLI)

These rules are inherited from `lpm-lg3d-app-contract.md` §5 and apply to
**all** subsystems.

### 3.1 Invocation

- Call the **installed binary** discovered on `PATH` (or its absolute path under
  `/usr/bin`, `/usr/sbin`, `/sbin`); MUST NOT embed a copy of a builder script
  (`lfs/06b-*.sh`, `blfs/18-*.sh`, `blfs/21-*.sh`, ...) or re-implement it.
- Pass arguments as an **argument vector** (no shell string interpolation):
  service names, device paths, connection names and patterns come from the
  user, so word-splitting/injection MUST be avoided. The shared
  `org.jdesktop.lg3d.utils.system.ProcessRunner` already takes a `List<String>`.
- Capture **stdout and stderr separately** (`ProcessRunner.Result` does this on
  separate gobbler threads).
- Pass **`--no-color`** where the tool supports it (`lpm` does). Where a tool
  has **no** colour switch — notably **`lfs-update`**, which hard-codes ANSI
  escapes — the front-end MUST strip ANSI SGR sequences before parsing or
  display, and MUST NOT rely on colour for meaning.
- **Detect the backend, then drive its native CLI.** Never assume a fixed init
  system or network manager; probe at runtime (§4.1, §4.4) and degrade to
  read-only / "unavailable" when no backend is present.

### 3.2 Exit codes and error fidelity (MUST)

- `0` = success. Non-zero = a **result state**, not necessarily a crash.
  Examples that MUST be surfaced as state, not errors:
  - `lfs-update check` exits **1** when the system is already up to date.
  - `lpm verify` exits non-zero when a file is modified/missing.
  - `getenforce` prints `Disabled`/`Permissive`/`Enforcing`; `selinuxenabled`
    exits non-zero when SELinux is not enabled.
- On any failure the UI MUST surface the tool's own **stderr verbatim** and MUST
  NOT swallow it or replace it with a generic dialog.

### 3.3 Concurrency / serialization (MUST)

- Mutating operations MUST be serialized (one at a time); mutating controls MUST
  be disabled while an operation runs.
- Where the tool holds its own lock (`lpm` → `/var/lock/lpm.lock`), the UI MUST
  gracefully present the "another instance is running" failure with a retry
  affordance rather than hanging.

### 3.4 Privilege escalation (MUST)

- Managers SHOULD run their own process **unprivileged** and escalate
  **per-operation** via `org.jdesktop.lg3d.utils.system.PrivilegedRunner`
  (`pkexec`/polkit). One user action = at most one polkit prompt.
- Read-only views MUST run unprivileged where the tool allows it.
- If `pkexec` is unavailable (`PrivilegedRunner.isAvailable()` == false), the
  manager MUST switch to read-only and say so, never hard-code credentials or
  run the whole GUI as root.
- Cancelling the polkit prompt (`PrivilegedRunner.Status.CANCELLED`) MUST abort
  the action cleanly with no partial change.

### 3.5 Reading state (MAY read files, read-only)

For fast structured listing a manager MAY read a tool's state files
**read-only** (e.g. `/etc/crypttab`, `/etc/fstab`, `/var/lib/lfs-updater/repo.list`,
`/etc/lfs-version`, `/etc/tor/torrc`, `/etc/nftables.conf`). **Any write MUST go
through the owning CLI**, never by editing these files from the GUI.

### 3.6 Long-running operations (MUST)

`lfs-update upgrade`, `lpm install/upgrade/build`, `cryptsetup luksFormat`,
`nft` reloads and package rebuilds can run for a long time. The UI MUST run them
off the event-dispatch thread, stream stdout/stderr into a live log view, show
progress, and allow the user to view (not silently kill) the output. Any
cancellation MUST terminate the child process group cleanly and MUST NOT leave a
half-applied state the tool's own rollback would not handle.

## 4. Subsystems (normative — feature → command)

Do not invent flags. Each row maps a UI feature to the exact CLI the builder
installs.

### 4.1 Services & init — new `ServicesPanel` over `InitSystemService`

**Backend detection** (mirrors `lfs/06b-service-management.sh` `_detect_init`):

| Probe | Detected init |
|---|---|
| `systemctl` present **and** `/usr/lib/systemd` is a directory | **systemd** |
| `rc-service` present **and** `/etc/init.d` is a directory | **openrc** |
| `sv` present **and** `/etc/sv` is a directory | **runit** |
| `s6-svscan` present **and** `/etc/s6` is a directory | **s6** |
| otherwise | **sysvinit** (fallback) |

**Operation → command** (verbatim from the builder's abstraction layer):

| Op | systemd | openrc | runit | s6 | sysvinit |
|---|---|---|---|---|---|
| start | `systemctl start <svc>` | `rc-service <svc> start` | `sv up <svc>` | `s6-svc -u /etc/s6/sv/<svc>` | `/etc/init.d/<svc> start` |
| stop | `systemctl stop <svc>` | `rc-service <svc> stop` | `sv down <svc>` | `s6-svc -d /etc/s6/sv/<svc>` | `/etc/init.d/<svc> stop` |
| restart | `systemctl restart <svc>` | `rc-service <svc> restart` | `sv restart <svc>` | `s6-svc -r /etc/s6/sv/<svc>` | `/etc/init.d/<svc> restart` |
| status (read) | `systemctl status <svc>` | `rc-service <svc> status` | `sv status <svc>` | `s6-svstat /etc/s6/sv/<svc>` | `/etc/init.d/<svc> status` |
| enable | `systemctl enable <svc>` | `rc-update add <svc> default` | `ln -sfn /etc/sv/<svc> /var/service/<svc>` | `ln -sfn /etc/s6/sv/<svc> /etc/s6/current/<svc>` | *not supported* |
| disable | `systemctl disable <svc>` | `rc-update del <svc>` | `rm -f /var/service/<svc>` | `rm -f /etc/s6/current/<svc>` | *not supported* |

- **list (read-only):** systemd `systemctl list-units --type=service --all
  --no-legend --no-pager`; openrc `rc-status --all` (or list `/etc/init.d`);
  runit `sv status /var/service/*` (or list `/etc/sv`); s6 list `/etc/s6/sv`;
  sysvinit list `/etc/init.d`. Listing is best-effort; a backend without a list
  verb falls back to enumerating its service directory.
- Mutating ops (start/stop/restart/enable/disable) MUST be escalated (§3.4) and
  confirmed; `status`/`list` MUST run unprivileged.
- `enable`/`disable` on sysvinit MUST be reported as unsupported rather than
  silently no-op'd.

### 4.2 System updates — new `SystemUpdatePanel` over `LfsUpdateService`

The builder installs **`/usr/bin/lfs-update`** (`blfs/18-system-updater.sh`),
usage `lfs-update [check|upgrade|status]`.

| Feature | Command | Class |
|---|---|---|
| Check for updates | `lfs-update check` | read-only (exit **1** = up to date, §3.2) |
| System status | `lfs-update status` | read-only (version, installed/upgradable counts) |
| Apply updates | `lfs-update upgrade` | **mutating**, escalated, confirmed, live log |

- `lfs-update upgrade` internally runs `check_updates && apply_updates`: it
  backs up `/etc`+`/boot` to `/var/lib/lfs-updater/backups`, then `lpm
  update-db` + `lpm upgrade`, detects a kernel change and runs `lpm
  kernel-deps`/`rebuild-kernel` + `grub-mkconfig`. The UI MUST NOT duplicate
  this; it runs `lfs-update upgrade` and streams its output.
- State files (read-only): `/etc/lfs-version`, `/var/lib/lfs-updater/repo.list`
  (`LFS_VERSION=...`), backups under `/var/lib/lfs-updater/backups`.
- `lfs-update` emits ANSI colour with **no** disable switch → strip ANSI (§3.1).
- **Three-layer update model (MUST be documented in the UI):**
  1. **`update-manager`** — auto-update of the *lg3d bundle itself*
     (`version.json`, PGP-verified). Not the OS.
  2. **`lpm`** — individual *packages* (`lpm-console`, see the LPM contract).
  3. **`lfs-update`** — *whole-system* update (drives `lpm` + kernel/GRUB).
  These are distinct and complementary; the panel MUST NOT conflate them.

### 4.3 Packages / profiles / kernel — `lpm-console` (already implemented)

Covered by [`lpm-lg3d-app-contract.md`](lpm-lg3d-app-contract.md). `lpm-console`
(`org.lpmconsole.LPMCommand`) already implements list/search/info/why/install/
remove/update/upgrade/upgradable/reinstall/autoremove/hold/unhold/holds/history/
verify/update-db/clean/list-profiles/add-profile/kernel-deps/rebuild-kernel/
build/version/help. **This contract adds no new work here beyond verifying the
existing app against the LPM contract.**

### 4.4 Network — extend `NetworkPanel` over the detected backend

**Backend detection:** NetworkManager (`nmcli` or `NetworkManager` present) →
`nmcli`; else dhcpcd (`dhcpcd` present) → `dhcpcdctl`; else systemd-networkd
(`networkctl` present) → `networkctl`; else read-only `ip`.

| Feature | NetworkManager | dhcpcd | networkd |
|---|---|---|---|
| list connections (read) | `nmcli -t -f NAME,TYPE,DEVICE connection show` | list `/etc/dhcpcd.conf` interfaces | `networkctl list` |
| link/address state (read) | `nmcli device status` | `dhcpcdctl -U <if>` / `ip addr` | `networkctl status <if>` |
| up / connect | `nmcli connection up id <name>` | `dhcpcd <if>` | `networkctl up <if>` |
| down / disconnect | `nmcli connection down id <name>` | `dhcpcdctl -k <if>` | `networkctl down <if>` |

The existing `NetworkConnections` seam already parses `nmcli -t` and builds
activate/deactivate vectors; the panel MUST be extended to fall back to
`dhcpcdctl`/`networkctl` on non-NetworkManager targets, and MUST run read-only
probes unprivileged.

### 4.5 Storage & LUKS — new `StoragePanel` over `StorageService`/`LuksService`

| Feature | Command | Class |
|---|---|---|
| list block devices (read) | `lsblk` (e.g. `lsblk -P -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT`) | read-only |
| LUKS status (read) | `cryptsetup status <name>` | read-only |
| open | `cryptsetup luksOpen <device> <name>` | **mutating/destructive-adjacent**, escalated, confirmed |
| close | `cryptsetup luksClose <name>` | **mutating**, escalated, confirmed |
| format (destroys data) | `cryptsetup luksFormat --type luks2 <device>` | **destructive**, escalated, typed confirmation |
| add key | `cryptsetup luksAddKey <device>` | **mutating**, escalated, confirmed |
| installer helper | `/usr/sbin/lfs-encrypt-disk <device> [luks-name]` | **destructive**, escalated, confirmed |

- State files (read-only): `/etc/crypttab`, `/etc/fstab`; `blkid -s UUID -o
  value <device>` for UUIDs. Writes to `crypttab`/`fstab` MUST go through the
  helper/`cryptsetup`, never by direct GUI edits (§3.5).
- `luksFormat`/`lfs-encrypt-disk` erase all data → the UI MUST require an
  explicit typed confirmation in addition to the polkit prompt.

### 4.6 Security — extend `firewall`/`securitycenter` over real backends

| Feature | Command | Class |
|---|---|---|
| firewall ruleset (read) | `nft list ruleset` (`/usr/sbin/nft`) | read-only |
| apply/reload firewall | `nft -f /etc/nftables.conf` | **mutating**, escalated, confirmed |
| AppArmor status (read) | `apparmor_status` / `aa-status` | read-only |
| SELinux mode (read) | `getenforce`; `selinuxenabled` (exit code = enabled?) | read-only |
| SSH daemon status (read) | init-system `status sshd` (§4.1) or `sshd -t` (config test) | read-only |
| start/stop sshd | init-system start/stop (§4.1) | **mutating**, escalated, confirmed |

Config lives at `/etc/nftables.conf` (+ `/etc/nftables/conf.d/*.nft`),
`/etc/ssh/sshd_config.d/*.conf`, `/etc/apparmor.d/*` — read-only for the GUI.

### 4.7 Privacy — extend `securitycenter` (or a Privacy section) over `tor`

| Feature | Command | Class |
|---|---|---|
| tor status (read) | init-system `status tor` (§4.1) | read-only |
| start/stop/restart tor | init-system start/stop/restart `tor` | **mutating**, escalated, confirmed |
| tor config (read) | read `/etc/tor/torrc` | read-only |
| tor log (read) | read `/var/log/tor/notices.log` | read-only |

Tor is a service → its lifecycle goes through the §4.1 init abstraction with
`tor` as the service name; no tor-specific reimplementation.

## 5. Surface mapping (subsystem → lg3d surface)

| Subsystem | Surface | Status |
|---|---|---|
| Services & init | Control Center → **`ServicesPanel`** (new) over `InitSystemService` | Phase 1 |
| System updates | Control Center → **`SystemUpdatePanel`** (new) over `LfsUpdateService` | Phase 2 |
| Packages/profiles/kernel | **`lpm-console`** (existing) | verify only |
| Network | Control Center → **`NetworkPanel`** (extend to dhcpcd/networkd) | Phase 3 |
| Storage & LUKS | Control Center → **`StoragePanel`** (new) over `StorageService`/`LuksService` | Phase 3 |
| Security (firewall/MAC/ssh) | **`firewall`** + **`securitycenter`** apps (extend to real backends) | Phase 4 |
| Privacy (tor) | **`securitycenter`** Privacy section (extend) | Phase 4 |

New Control Center panels MUST register through `ControlPanelRegistry`
(`addDefault(<Panel>::new, "<Name>")`), implement `ControlPanel`, construct
lazily, and use `JList`/`JCheckBox`/`JButton`/`JTextField` — **no `JComboBox`**
(SwingNode offscreen-rendering rule).

## 6. Prohibitions (normative — MUST NOT)

- **MUST NOT** re-implement init/lpm/lfs-update/cryptsetup/nft/nmcli logic or
  copy a builder script into the desktop.
- **MUST NOT** write to `/etc`, `/var/lib/*`, `/var/service`, `/etc/s6`,
  `/etc/crypttab`, `/etc/fstab`, unit/symlink trees or lock files directly; all
  mutations go through the owning CLI.
- **MUST NOT** run mutating commands concurrently or bypass a tool's own lock.
- **MUST NOT** parse colourized output — pass `--no-color` where supported, else
  strip ANSI.
- **MUST NOT** assume a fixed init system or network manager; always detect.
- **MUST NOT** hard-code passwords/passphrases/tokens; use polkit escalation and
  feed secrets via stdin only where the tool requires it (e.g. `cryptsetup`).
- **MUST NOT** act as a window manager/compositor or claim the X root; coexist
  with lg3d per `lfs-x11-contract.md`.
- **MUST NOT** require Wayland/XWayland, a display manager, or a second DE.

## 7. Acceptance criteria (how the agent proves compliance)

All of the following MUST hold before a subsystem manager is declared
compliant. Run them inside the provisioned lg3d session on `:0` (the LFS
target). On a non-target dev host (e.g. Fedora), the equivalent headless JUnit
tests MUST prove command-vector construction, backend detection and output
parsing; the on-target run is recorded as the compliance artefact.

1. **Launch.** The manager's window is composited in the 3D scene; no
   `BadAccess`, no second WM, no crash.
2. **Backend detection.** With each backend present/absent the manager picks the
   right CLI (or degrades to read-only/"unavailable") — never a hard failure.
3. **Read path.** Read-only views match the CLI output (`systemctl status`,
   `lfs-update status`, `lsblk`, `nft list ruleset`, `getenforce`, ...).
4. **Confirmation.** Every mutating/destructive action shows a confirmation
   (and a typed confirmation for `luksFormat`/`lfs-encrypt-disk`) before running.
5. **Privilege separation.** The GUI runs unprivileged; a mutating action
   triggers exactly one polkit prompt; cancelling aborts cleanly with no partial
   change.
6. **Serialization.** Mutating controls are disabled while an operation runs; a
   second concurrent request is refused, not interleaved.
7. **Error fidelity.** A forced failure shows the tool's verbatim stderr and a
   non-zero status is reflected as state, not a crash.
8. **Long-running ops.** `lfs-update upgrade` / `cryptsetup luksFormat` stream
   live output off-EDT with progress and remain viewable; the UI never freezes.
9. **No reimplementation.** A code review confirms the manager only builds
   argument vectors and parses output — no backend logic is duplicated.

Record the output/evidence of (1)–(9) per subsystem as the compliance artefact.

## 8. Deliverables (per phase)

1. The backend service class(es) in `org.jdesktop.lg3d.utils.system`
   (`InitSystemService`, `LfsUpdateService`, `StorageService`/`LuksService`,
   `NetworkService`, `SecurityService`, `PrivacyService`) — thin, injectable,
   headless-testable; `ProcessRunner`/`PrivilegedRunner` reused, not replaced.
2. The Control Center panel(s) / app extension(s) wired to
   `ControlPanelRegistry` (and a start-menu descriptor where applicable).
3. Headless JUnit 5 tests: backend detection matrix, argument-vector
   construction (no shell interpolation), ANSI stripping, stdout/stderr
   separation, exit-code-as-state, error fidelity, serialization, and parsing of
   each CLI's output; a fake `ProcessRunner`/`PrivilegedRunner` injected.
4. This contract's §7 acceptance evidence for the subsystem.

Each phase is delivered as its own branch → module-scoped Conventional Commit
(signed-off) → PR → green CI → merge, with a `CHANGELOG.md` bullet under the
open `[Unreleased]` header and the relevant `AGENTS.md` updated.

## 9. References

- [`docs/lfs-x11-contract.md`](docs/lfs-x11-contract.md) — normative lg3d / Xorg display
  contract.
- [`lpm-lg3d-app-contract.md`](lpm-lg3d-app-contract.md) — package/profile/
  kernel control; §5 integration rules inherited here.
- `lfs/06b-service-management.sh` — init detection + per-init operation map
  (§4.1 source of truth).
- `blfs/18-system-updater.sh` — `/usr/bin/lfs-update` (`check|upgrade|status`).
- `blfs/21-luks-encryption.sh` — `cryptsetup` + `/usr/sbin/lfs-encrypt-disk`.
- `blfs/23-basic-networking.sh` — dhcpcd / NetworkManager backends.
- `blfs/15-security-hardening.sh` — `nft`, AppArmor, SELinux, `sshd`.
- `blfs/16-privacy-tools.sh` — `tor`.
- `lg3d-core/src/classes/org/jdesktop/lg3d/utils/system/ProcessRunner.java`,
  `PrivilegedRunner.java` — the shared exec / polkit-escalation backends.
