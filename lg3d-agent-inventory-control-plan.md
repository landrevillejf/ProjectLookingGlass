# LG3D Agent — Plan de contrôle complet de l'inventaire

> **Objet.** Amener le desktop lg3d à **contrôler tout ce que décrit
> `lg3d-agent-package-inventory.md`** — la surface « manageable » (§4, §8) et la
> surface « accès » (§5) des logiciels installés par le profil `lg3d` du builder
> BLFS. Ce plan est la **copie racine durable** de la feuille de route ; il ne
> remplace pas les contrats normatifs, il les pilote.
>
> **Contrats normatifs (autorité).** L'affichage runtime : `lfs-x11-contract.md`
> (dépôt LFS). Le gestionnaire de paquets : `lpm-lg3d-app-contract.md`. La gestion
> système par phases : `system-management-contract.md`. L'inventaire des logiciels
> : `lg3d-agent-package-inventory.md` (descriptif, pas une licence de contourner
> les contrats). Toutes ces références sont citées en **prose** (jamais en lien
> markdown) quand le fichier visé n'est pas suivi dans ce dépôt, pour que la gate
> `docLinksCheck` reste verte.
>
> **Règle unique (héritée des contrats).** Le desktop est un **front-end** : il
> pilote les CLI installés (`lpm`, `systemctl`/scripts init, `nft`, `cryptsetup`,
> `nmcli`, `fail2ban-client`, `rkhunter`, `lynis`, `ausearch`, `semanage`…), ne
> **réimplémente jamais** un sous-système, n'écrit **jamais** dans `/etc`
> directement (lecture seule bornée via `ConfigFileReader` ; toute mutation passe
> par l'outil propriétaire), GUI **non privilégiée**, escalade **polkit par
> opération** (`PrivilegedRunner`), confirmation avant toute mutation,
> sérialisation des opérations mutantes, opérations longues hors-EDT (log live +
> progression + annulation), **fidélité des erreurs** (stderr verbatim, code de
> sortie non-nul = état, pas crash), et **portabilité init** systemd/sysvinit
> (§8) via `InitSystemService`.

---

## 1. Constat — état réel vérifié (audit du 2026-10-24)

Méthode : `grep` des outils de l'inventaire dans `lg3d-core/src/classes`,
`lg3d-apps/src/classes`, `lpm-console/src`, `db-manager/src` ; lecture du
registre `ControlPanelRegistry` et des backends `utils.system`. Résultat mesuré,
pas supposé.

### 1.1 DÉJÀ contrôlé (un front-end pilote réellement le CLI)

| Surface inventaire | Backend (`lg3d-core utils.system` sauf mention) | Surface UI | Preuve |
|---|---|---|---|
| Paquets / profils / noyau (§2, LPM) | — (`lpm-console`) | `lpm-console` | `LPMCommand` couvre list/search/info/why/install/remove/update/… |
| Services + portabilité init systemd/sysvinit (§4, §8) | `InitSystemService` | `ServicesPanel` | détection d'init + map opération→CLI |
| Mises à jour système `lfs-update` (§6.13) | `LfsUpdateService` | `SystemUpdatePanel` | check/status/upgrade |
| Stockage / LUKS `lsblk`+`cryptsetup` (§6.11) | `StorageService`, `LuksService` | `StoragePanel` | status/open/close/format/addKey + helper |
| Réseau `nmcli`/`dhcpcd`/`networkctl` (§6.9) | `NetworkService` | `NetworkPanel` | détection de backend + up/down/status/list |
| VPN WireGuard / OpenVPN (§6.12) | — (`apps/vpn`) | `VpnPanel` (`VpnBackend`) | `ConnectionType` OPENVPN/WIREGUARD |
| Pare-feu `nft` (§4, §6.11) | `SecurityService` | `FirewallPanel` | `nft list ruleset` / `nft -f` appliqué (pkexec) |
| État SELinux `getenforce`/`selinuxenabled` + AppArmor + `sshd` (§6.11) | `SecurityService`, `SecurityProbe` (apps) | `SecurityCenterPanel` | **état seulement** |
| Confidentialité `tor` (§6.12) | `PrivacyService`, `TorPrivateMode` | `PrivacyPanel` | cycle de vie délégué à §4.1 + lecture torrc/log bornée |
| Serveurs httpd/BIND/Postfix/Dovecot/Samba/NFS/ProFTPD/vsftpd/slapd/Tomcat/MariaDB/PostgreSQL (§4, §6.10) | `ServerService`, `ConfigFileReader` | `ServersPanel` | mergé (main `8123dcc1`) — cycle de vie via §4.1 + config lecture seule |
| Client SQL MariaDB/PostgreSQL/H2/SQLite (§5, §9) | — (`db-manager`) | `DbManagerPanel` | JDBC réel (`ConnectionProvider`, `DriverManager`) |
| Conteneurs Docker (§6.7) | — (`apps/dockermanager`) | `DockerManagementPanel` | CLI `docker` |
| Antivirus `clamscan`/`clamdscan` | — (`apps/securitycenter`) | `AntivirusBackend` | moteur externe, aucun moteur embarqué |
| Installateur Calamares (§6.14) | — | item start-menu « Install WBLFS (LG3D) » | `pkexec calamares` |
| Utilisateurs, Date/Heure (`ntpd`), Affichage, Thermique, Info système | `UserService`, `SystemInfoService`, `DisplayService`, `ThermalService` | panneaux dédiés | — |

**Conclusion 1.1 :** `system-management-contract.md` (Phases 0→4 + Servers) est
**livré au complet**. C'était le prérequis, pas le présent plan.

### 1.2 TROUS mesurés (dans l'inventaire, **0** front-end ne les pilote)

| # | Trou (section inventaire) | Outils à piloter | Réf. mesurées dans le code |
|---|---|---|---|
| **G1** | **Tableau de bord HIDS / sécurité runtime** (§6.11, candidat §9 explicite) | `fail2ban` (`fail2ban-client`), `rkhunter`, `lynis`, `auditd` (`auditctl`/`ausearch`) | fail2ban **0**, rkhunter **0**, lynis **0**, auditd **0**. `HardeningRules` ne fait que *scorer/aviser* (« reads nothing, spawns nothing ») |
| **G2** | **Gestion de politique SELinux** (§6.11) — au-delà de l'état | `semanage`, `audit2allow`, `restorecon`, `semodule`, `load_policy`, `ausearch` | semanage **0**, audit2allow **0**, restorecon **0** (seul `getenforce`/`selinuxenabled` existe) |
| **G3** | **`dnscrypt-proxy`** confidentialité (§6.12) | service `dnscrypt-proxy` + config `/etc/dnscrypt-proxy/` | **0** pilote réel (1 réf = exemple dans un javadoc) ; `tor` est couvert, pas dnscrypt-proxy |
| **G4** | **Lanceur Java/dev** (§5, §9 candidat) | JDK 21 (`java`/`javac`), Maven, Gradle, Tomcat (`catalina.sh`), Jenkins, `kubectl` | jenkins **0**, kubectl **0** ; Tomcat n'existe que comme *entrée de catalogue serveur* (cycle de vie), aucun lanceur dev |
| **G5** *(secondaire)* | Config lecture seule des services non-servers (§4) | `/etc/ntp.conf`, `/etc/lightdm/lightdm.conf`, `/etc/wpa_supplicant.conf`, `/etc/dhcpcd.conf`, `/etc/ssh/sshd_config` | cycle de vie couvert par `ServicesPanel`, mais **pas de vue config** pour ces unités |
| **G6** *(optionnel, §5 accès)* | Console d'accès réseau/annuaire | `nmap`, `ldapsearch` (OpenLDAP) | aucun front-end ; §5 est « accès », non « gestion » — priorité basse |

---

## 2. Décisions d'architecture

- **Un trou = un backend fin + une surface.** Chaque backend vit dans
  `org.jdesktop.lg3d.utils.system` (partagé, injectable, headless-testable) sur le
  modèle de `InitSystemService`/`SecurityService`/`ServerService` : construction du
  **vecteur d'arguments** (aucune interpolation shell), parsing de sortie, codes de
  sortie comme état, `ProcessRunner` (lecture) / `PrivilegedRunner` (mutation)
  **réutilisés, jamais remplacés**.
- **Surfaces = extensions des applis existantes** quand c'est cohérent (règle :
  pas de nouvelle appli si une appli couvre déjà le domaine) :
  - **G1** → nouvel onglet **« HIDS »** dans `SecurityCenterPanel` (voisin de
    Firewall/AppArmor/SSH/Privacy), backend `HidsService`.
  - **G2** → extension de `SecurityService` + onglet **« SELinux policy »** dans
    `SecurityCenterPanel` (l'état existe déjà ; on ajoute la gestion).
  - **G3** → extension de `PrivacyService` + `PrivacyPanel` (dnscrypt-proxy traité
    exactement comme tor : cycle de vie via §4.1 + lecture config bornée).
  - **G4** → **nouvelle appli start-menu « Dev Launcher »** (`apps/devlauncher`,
    `DevLauncherPanel` en PANEL, menuGroup approprié) : sondes de version lecture
    seule (java/javac/mvn/gradle/kubectl) + cycle de vie Tomcat/Jenkins via §4.1.
  - **G5** → vue config lecture seule (`ConfigFileReader`) ajoutée à
    `ServicesPanel` pour l'unité sélectionnée (chemin de config connu).
  - **G6** → optionnel, seulement si demandé : console `nmap` + client LDAP.
- **Sélecteurs `JList`, jamais de combo box** (règle SwingNode offscreen).
- **Aucune écriture `/etc`** : toute mutation passe par l'outil propriétaire sous
  pkexec ; la GUI ne fait que lire (borné) et construire des vecteurs d'args.
- **Pas de bump de version dans une PR de feature** ; puces `CHANGELOG.md` sous
  `[Unreleased]`. Historique **linéaire** : intégrer chaque PR avec
  `gh pr merge --rebase --delete-branch`.

---

## 3. Phases (1 trou = 1 branche = 1 PR, mergée en rebase)

### Phase H1 — Tableau de bord HIDS (G1)
- Backend `HidsService` (`utils.system`) :
  - `fail2ban-client status` / `status <jail>` (lecture), `set <jail> banip/unbanip`
    (mutation, escaladé, confirmé).
  - `rkhunter --check --sk` (scan, escaladé, long → hors-EDT + progression),
    `--propupd` (premier boot, documenté), lecture de `/var/log/rkhunter.log`.
  - `lynis audit system --report-file …` (scan long, lecture du rapport), parsing
    des suggestions/avertissements.
  - `auditctl -s` (état, lecture), `ausearch -m <type> -ts recent` (lecture des
    événements) — **lecture seule**, jamais de modification des règles d'audit ici.
  - Détection de présence binaire (chaque outil peut être absent → ligne « non
    installé », pas d'erreur).
- UI : onglet **« HIDS »** dans `SecurityCenterPanel`, `JList` d'outils + détail
  verbatim ; scans hors-EDT avec barre de progression et annulation ; mutations
  confirmées + pkexec.
- Tests headless : matrice de détection, vecteurs d'args, parsing
  (fail2ban/lynis/rkhunter/ausearch), fidélité d'erreur, sérialisation ;
  Cucumber `hids.feature`.

### Phase H2 — Gestion de politique SELinux (G2)
- Extension `SecurityService` : `semanage fcontext|boolean|port` (list/add/del),
  `restorecon -Rv <path>`, `semodule -l`/`-i`/`-r`, `load_policy`,
  `ausearch -m avc` → `audit2allow` (génération de module **affichée pour revue**,
  appliquée seulement sur confirmation explicite + pkexec).
- UI : onglet **« SELinux policy »** dans `SecurityCenterPanel` (l'état
  `getenforce` reste dans l'onglet existant) ; toute écriture de politique est
  **destructive** → confirmation typée + escalade + sortie verbatim.
- Tests headless : vecteurs semanage/restorecon/semodule, pipe ausearch→audit2allow
  (sans exécution réelle), classification destructif ; Cucumber `selinux-policy.feature`.

### Phase H3 — dnscrypt-proxy (G3)
- Extension `PrivacyService` : dnscrypt-proxy comme **service** (§4.1, unit
  `dnscrypt-proxy`) + lecture bornée de `/etc/dnscrypt-proxy/dnscrypt-proxy.toml`
  via `ConfigFileReader` ; même traitement que tor.
- UI : `PrivacyPanel` — section dnscrypt-proxy à côté de tor (statut, start/stop,
  vue config lecture seule).
- Tests headless : détection, vecteur de cycle de vie délégué à §4.1, résolution
  du chemin config ; Cucumber étendu `privacy.feature`.

### Phase H4 — Lanceur Java/dev (G4)
- **Nouvelle appli** `apps/devlauncher` : `DevLauncher` (wrapper 3D via
  `TitledSwingWindow`) + `DevLauncherPanel` (PANEL, start-menu).
- Sondes **lecture seule** de version : `java -version`, `javac -version`,
  `mvn -v`, `gradle -v`, `kubectl version --client`, présence `catalina.sh`,
  `jenkins.war`. Cycle de vie **Tomcat**/`jenkins` via §4.1 (unit `tomcat`,
  `jenkins`). Aucun téléchargement, aucune exécution de build.
- Descripteur start-menu `devlauncher.lgcfg` + icône (`GenerateAppIcons`) +
  enregistrement `Desktop2DAppRegistry` (PANEL) ; `AGENTS.md` par appli.
- Tests headless : parsing des versions, détection de présence, vecteurs §4.1 ;
  Cucumber `devlauncher.feature`.

### Phase H5 — Vue config des services non-servers (G5, secondaire)
- `ServicesPanel` : pour l'unité sélectionnée dont un chemin de config est connu
  (`/etc/ntp.conf`, `/etc/lightdm/lightdm.conf`, `/etc/wpa_supplicant.conf`,
  `/etc/dhcpcd.conf`, `/etc/ssh/sshd_config`), bouton « View Config » →
  `ConfigFileReader` (lecture seule bornée). Table unité→chemin dans un backend
  (extension de `InitSystemService` ou `ServiceConfigPaths`).
- Tests headless : résolution unité→chemin, lecture bornée ; Cucumber étendu.

### Phase H6 — Console d'accès réseau/annuaire (G6, optionnel, sur demande)
- Seulement si l'utilisateur le demande : console `nmap` (scan ciblé confirmé) et
  client LDAP `ldapsearch` (lecture). §5 est « accès », pas « gestion » → priorité
  la plus basse.

---

## 4. Critères d'acceptation

- **Par phase** : `./gradlew build` vert (JDK 21, plancher JaCoCo `lg3d-core`
  line 1.0 % / branch 2.0 % respecté), Checkstyle zero-tolerance vert,
  `:lg3d-mandela:repositoryChecks` vert (dont `docLinksCheck`, `i18nKeysCheck` si
  chaînes `Messages.tr`, `startmenu-audit` si nouveau descripteur), PIT inchangé.
- **Cucumber obligatoire** par PR (règle du dépôt) : un `.feature` par phase, collé
  au glue `org.jdesktop.lg3d.utils.system` dans `CucumberSuite`.
- **Sur cible LFS** (session provisionnée sur `:0`) : fenêtre compostée, chemin
  lecture = CLI réel, **une seule invite polkit par opération**, fidélité d'erreur
  (stderr verbatim), sérialisation (contrôles désactivés pendant l'opération).
  **Impossible sur le poste Fedora** (fail2ban/rkhunter/lynis/auditd/semanage/
  dnscrypt-proxy/Jenkins absents) → enregistré comme preuve ; la vérification dev
  reste headless (construction/parsing), conformément à la politique de test.

## 5. Git flow (rappel, automatique)

Branche depuis `main` → Conventional Commit module-scopé signé (`lg3d-core` /
`lg3d-apps`) → push → PR contre `main` (`gh`) → CI verte → **`gh pr merge --rebase
--delete-branch`** (historique linéaire) → branche locale supprimée → `main` local
synchronisé. Stage explicite (jamais `git add -A`). Puce `CHANGELOG.md` sous
`[Unreleased]`. Aucun bump de version dans une PR de feature ; le bump `-dev` est
une PR `chore` séparée après merge, le cas échéant.

## 6. Hypothèses / points à confirmer

- G1/G2 s'intègrent comme **onglets de `SecurityCenterPanel`** (pas de nouvelle
  appli) ; G4 est une **nouvelle appli start-menu** ; G3 étend `PrivacyPanel`.
- `lg3d-agent-package-inventory.md` est un **miroir du dépôt LFS** : ses liens
  (`lfs-x11-contract.md`, `docs/lpm.md`, `docs/security-hardening.md`) ne résolvent
  que là-bas → il reste **non suivi** ici (comme les références prose à
  `lfs-x11-contract.md`) pour garder `docLinksCheck` vert. Ce plan le cite en prose.
- Ordre des phases = ordre des trous (H1 → H6), du plus gros manque au plus
  optionnel. H5/H6 sont secondaires/optionnels.
- Les backends réels n'existent que sur la cible LFS ; la vérification dev est
  headless + preuve sur cible.
