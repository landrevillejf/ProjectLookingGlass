Voici le plan complet, tel quel :

---

# LFS System Management — contrat d'abord, par phases

## Summary
« Gérer tout du LFS » = faire du desktop lg3d la surface de gestion du système BLFS/LFS, sur le modèle du contrat LPM existant : un **front-end** qui pilote les **CLI installés** (jamais de réimplémentation), avec escalade polkit par opération, `--dry-run`/confirmation, sérialisation des opérations mutantes, fidélité des erreurs, et vues lecture seule non privilégiées. On rédige d'abord un contrat normatif couvrant tous les sous-systèmes, puis on implémente par phases en réutilisant l'existant.

## Constat (état réel vérifié)
- `lpm-console` (`org.lpmconsole`, 9 classes + 7 tests) implémente **déjà toute la surface LPM** : `LPMCommand` couvre list/search/info/why/install/remove/update/upgrade/upgradable/reinstall/autoremove/hold/unhold/holds/history/verify/update-db/clean/list-profiles/add-profile/kernel-deps/rebuild-kernel/build/version/help. → **paquets/profils/noyau déjà couverts** (à valider contre le contrat, pas à reconstruire).
- Control Center (`lg3d-apps/.../controlcenter`, 23 panneaux) a déjà `NetworkPanel`, `SystemInfoPanel`, `UsersPanel`, `PowerPanel`, `DateTimePanel`, `TaskSchedulerPanel`, etc. ; `firewall` (`FirewallService`), `securitycenter` (`AntivirusBackend`), `taskmanager`, `vpn` (`VpnBackend`), `dockermanager` pilotent déjà de vrais backends.
- `lg3d-core` `org.jdesktop.lg3d.utils.system` fournit le backend partagé : `ProcessRunner`, `ProcessService`, `PrivilegedRunner` (polkit), `SystemInfoService`, `UserService`, `ThermalService`, `DisplayService`.
- CLI réels exposés par le builder LFS : services → `systemctl` / `rc-service` / `sv` / `s6-svc`+`s6-svstat` / `/etc/init.d/*` (init détecté, cf. `lfs/06b-service-management.sh`) ; updates système → `/usr/bin/lfs-update [check|upgrade|status]` (`blfs/18`) ; LUKS → `cryptsetup` + `/usr/sbin/lfs-encrypt-disk` (`blfs/21`) ; réseau → `nmcli`/`dhcpcd` (`blfs/23`) ; sécurité → `nft`, `apparmor_status`, `getenforce`/`selinuxenabled`, `sshd` (`blfs/15`) ; vie privée → `tor` (`blfs/16`).
- **Trous** : (1) services/init, (2) updates système `lfs-update` (l'app `update` = simple shim vers `update-manager`, qui ne gère que l'auto-update du bundle lg3d), (3) stockage/LUKS, (4) vie privée/tor, (5) audit réseau/sécurité existants.

## Décisions d'architecture
- **Contrat d'abord** : créer `system-management-contract.md` à la racine de ProjectLookingGlass (frère de `lpm-lg3d-app-contract.md`), **miroir identique** à la racine du dépôt LFS. Il hérite de `lfs-x11-contract.md` (affichage) et des règles §5 du contrat LPM (invocation, codes de sortie, lock, escalade, lecture seule, opérations longues).
- **Nouveaux gestionnaires = panneaux du Control Center** enregistrés via `ControlPanelRegistry` (`ServicesPanel`, `SystemUpdatePanel`, `StoragePanel`), cohérent avec `UsersPanel`/`NetworkPanel` et la règle « JList selectors, pas de combo boxes (SwingNode) ».
- **Sécurité/vie privée = extension des applis existantes** (`firewall`→`nft`, `securitycenter`→`apparmor_status`/`getenforce`/`sshd`, ajout `tor`), pas de nouvelles applis.
- **Paquets/profils/noyau restent dans `lpm-console`** (déjà complet) — simple validation contrat.
- **Backend par sous-système** : une classe de service fine et injectable (bâtir le vecteur d'arguments + parser la sortie), `ProcessRunner`/`PrivilegedRunner` injectés pour les tests headless — calqué sur `lpm-console` (`LPMExecutor`/`LpmDatabase`/`OperationResult`) et sur `FirewallService`/`VpnBackend`. Services prévus : `InitSystemService`, `LfsUpdateService`, `StorageService`/`LuksService`, `NetworkService`, `SecurityService`, `PrivacyService` (dans `utils.system` quand partagé, sinon dans le paquet de l'appli).
- **Modèle de mise à jour à 3 couches** (à expliciter dans le contrat) : `update-manager` = auto-update du bundle lg3d (version.json) ; `lpm` = paquets ; `lfs-update` = mise à jour système globale (repo.list). Les trois sont distincts et complémentaires.

## Livrable Phase 0 — le contrat + socle partagé
`system-management-contract.md` (normatif, RFC 2119) contient :
- Mandat + règle unique (front-end, piloter les CLI installés, jamais réimplémenter/verrouiller/vérifier à leur place) ; héritage de `lfs-x11-contract.md`.
- Règles d'intégration partagées (reprises du LPM §5) : vecteur d'arguments (pas d'interpolation shell), capture stdout/stderr séparée, `--no-color` quand applicable, sérialisation des ops mutantes, escalade **polkit par opération** via `PrivilegedRunner` (GUI non privilégié), lecture seule non privilégiée, ops longues hors-EDT avec log live + progression + annulation propre, fidélité des erreurs (stderr verbatim), codes de sortie comme état (ex. non-zéro = résultat, pas crash).
- Tables **fonction → commande** par sous-système (ne pas inventer de flags) :
    - Services/init : détection (`systemctl` + `/usr/lib/systemd` → systemd ; `rc-service` + `/etc/init.d` → openrc ; `sv` + `/etc/sv` → runit ; `s6-svscan` + `/etc/s6` → s6 ; sinon sysvinit) puis start/stop/restart/status/enable/disable/list mappés sur le CLI natif.
    - Updates système : `lfs-update check` (lecture seule), `lfs-update status`, `lfs-update upgrade` (mutant, escaladé, confirmé).
    - Paquets/profils/noyau : référence au contrat LPM (déjà implémenté par `lpm-console`).
    - Réseau : détection du backend (NetworkManager→`nmcli`, dhcpcd→`dhcpcdctl`, systemd-networkd→`networkctl`) puis up/down/status/list.
    - Stockage/LUKS : `lsblk` (lecture), `cryptsetup status|luksOpen|luksClose|luksFormat|luksAddKey`, helper installateur `/usr/sbin/lfs-encrypt-disk`.
    - Sécurité : `nft list ruleset`/règles, `apparmor_status`/`aa-status`, `getenforce`/`selinuxenabled`, `sshd` status.
    - Vie privée : `tor` statut/contrôle.
- **Critères d'acceptation** par sous-système (calqués sur LPM §8), à prouver dans la session lg3d sur `:0` ; sur le poste de dev Fedora, tests headless de construction/parsing.
- **Table d'appariement** sous-système → surface lg3d (panneau existant à étendre vs nouveau panneau), + note de miroir vers le dépôt LFS.
- Socle : ajouter `org.jdesktop.lg3d.utils.system.InitSystemService` (détection d'init + map opération→CLI, headless-testable) ; vérifier que `PrivilegedRunner`/`ProcessRunner` suffisent, sinon ajouter un helper générique `CliBackend` (vecteur d'args + stdout/stderr + exit). Aucune modification de comportement des applis à ce stade.

## Phases d'implémentation (1 famille = 1 PR)
- **Phase 1 — Services & init** : `ServicesPanel` (Control Center) sur `InitSystemService` : lister les unités, start/stop/restart/enable/disable, statut ; ops mutantes escaladées (polkit) + confirmées ; lecture seule non privilégiée. Tests headless : matrice de détection d'init, vecteurs d'args, parsing des sorties, fidélité d'erreur, sérialisation.
- **Phase 2 — Updates système** : `SystemUpdatePanel` sur `LfsUpdateService` : `check`/`status` (lecture), `upgrade` (mutant, escaladé, log live + progression, confirmation). Documenter la distinction avec `update-manager` et `lpm`. Validation contrat de `lpm-console` (paquets/profils/noyau) en complément.
- **Phase 3 — Réseau & Stockage/LUKS** : auditer/étendre `NetworkPanel` pour piloter le backend réel détecté (`nmcli`/`dhcpcdctl`/`networkctl`) ; nouveau `StoragePanel` sur `StorageService`/`LuksService` (`lsblk` + `cryptsetup status/open/close/format/addKey`, helper `lfs-encrypt-disk`), escaladé + confirmé (destructif).
- **Phase 4 — Sécurité & vie privée** : étendre `firewall`→`nft`, `securitycenter`→`apparmor_status`/`getenforce`/`sshd` ; ajouter la gestion `tor` (statut/contrôle). Câbler sur les backends réels, escalade polkit.

Chaque phase : branche depuis `main` → Conventional Commit module-scopé (`lg3d-apps`/`lg3d-core`) signé → PR → CI verte → merge ; puce `CHANGELOG.md` sous `[Unreleased] — 1.65.0-dev` ; `AGENTS.md` par appli mis à jour (Status/rôles) ; enregistrement `ControlPanelRegistry` + descripteur start-menu le cas échéant ; pas de bump de version (le cycle 1.65.0-dev est ouvert).

## Test Plan
- **Headless JUnit 5** par service backend : détection d'init/backend, construction du vecteur d'arguments (aucune interpolation shell), `--no-color`, séparation stdout/stderr, codes de sortie + fidélité d'erreur, lock/sérialisation, parsing de chaque sortie CLI ; `ProcessRunner`/`PrivilegedRunner` fake injectés (calqué sur `LPMExecutorTest`).
- **Panneaux** : construction headless-safe (comme les autres panneaux controlcenter) ; sélecteurs `JList` (règle SwingNode).
- **Couverture** : `lg3d-apps` n'a pas de plancher ; les ajouts dans `lg3d-core` `utils.system` doivent respecter le plancher lg3d-core (line 1.0% / branch 2.0%) → lancer `:lg3d-core:build` (jacocoTestCoverageVerification).
- **Acceptation sur cible** (session lg3d provisionnée sur `:0`) : critères du contrat (fenêtre compostée, chemin lecture = CLI, parité aperçu, une seule invite polkit, fidélité d'erreur). **Impossible sur le poste Fedora** (pas de systemd-comme-init cible, ni `lfs-update`/`cryptsetup`/`tor` du LFS) → enregistré comme preuve, les tests dev restent headless (construction/parsing), conformément à la politique de test du repo.

## Hypothèses / points à confirmer
- Les nouveaux gestionnaires vivent comme **panneaux du Control Center** (sinon : applis start-menu séparées, ou consoles paquetées LPM comme `lpm-console`).
- Le contrat est **mirroiré à la racine du dépôt LFS** (gardé synchronisé, comme `lpm-lg3d-app-contract.md`).
- Ordre des phases = tes 4 familles ; paquets/profils/noyau déjà faits via `lpm-console` (validation seulement).
- Les backends réels n'existent que sur la cible LFS ; la vérification dev est headless + preuve sur cible.
- Aucun changement au builder LFS n'est requis (le desktop ne fait que piloter ses CLI) ; si un sous-système n'expose pas de CLI runtime (ex. réseau selon backend), le contrat le signale et la phase correspondante ajoute la détection côté desktop.

---

Voilà le plan intégral. Ta Phase 0 est déjà écrite sur la branche `feat/system-management-phase0` (contrat + `InitSystemService` + test). Tu veux que je continue avec quoi — vérifier le build/test, committer la Phase 0, ou passer à la Phase 1 ?
