Je vais analyser le desktop Project Looking Glass pour identifier les applications et fonctionnalités manquantes par rapport à un desktop 2D/3D standard.











# Analyse du Desktop Project Looking Glass

## Applications actuelles (lg3d-apps)

**Productivité:**
- Calculator, Paint, FileManager, PDF Viewer, Control Center, Task Manager
- Agenda/Calendar (orgchart), Weather, Screen Capture, Backup
- FTP Client, Database Manager, Media Writer, Photo Viewer, CD Viewer
- Periodic Table, About, Help Center
- Archive (compression/extraction), Search, Contacts
- OpenAPI Editor, Payload Manager

**Communication:**
- Web Browser (JavaFX/WebKit), Mail (Mail3D), Messenger (IM)
- Video Conference, Call Viewer (SourceViewer)
- P2P (MyJXTa), Remote Viewer (RMI remote desktop)

**Multimédia:**
- Audio Player, Video Player, Image Editor, Image Studio (3D), Recorder
- Photo Viewer, CD Viewer, Media Writer, Tuner

**Sécurité / Système:**
- Firewall, Security Center (Antivirus), VPN, Password Manager
- SSH Client, Update Manager, Launcher
- Docker Manager (écrit mais pas encore intégré)

**Jeux:**
- Chess, Solitaire, Sudoku, Tic-Tac-Toe (tous en 3D depuis incubator)

**Développement:**
- SwingIDE, Git GUI, Terminator (terminal), SwingNode/SwingTest (test)
- Graph, Tutorials, Widget Gallery

## Fonctionnalités Desktop 2D existantes

**Taskbar:**
- Start menu avec recherche
- Boutons fenêtres par workspace
- Menus Documents/Downloads
- Workspace pager (virtual desktops)
- Indicateurs système (volume, réseau, batterie)
- Notification tray avec Do Not Disturb
- Horloge avec calendrier/agenda
- Auto-hide, position configurable

**Context menu:**
- Launchers (Terminal, File Manager)
- Personnalisation (Wallpaper, Settings)
- Arrangement fenêtres (Cascade, Tile, Minimize/Restore all)
- Power (Lock, Suspend, Reboot, Shut down)
- Session (Refresh, Exit)

**Autres:**
- Workspace/virtual desktops multiples
- Toast notifications
- Wallpaper slideshow avec schedule jour/nuit
- Night tint overlay
- Keyboard shortcuts configurables
- Session persistence (restore fenêtres)
- Alt+F2 run dialog avec historique
- Window snapping aux bords
- Window cycling (Alt+`)
- Brightness control logiciel

## Ce qui manque par rapport à un desktop standard

### Applications de communication
- **Client email** done
- **Navigateur web** done (app `webbrowser`, JavaFX WebView/WebKit — full en 2D, preview + child-process en 3D)
- **Messagerie instantanée** done
- **Visioconférence** done

### Suite bureautique complète
- **Traitement de texte** (Word, LibreOffice Writer)
- **Tableur** (Excel, LibreOffice Calc)
- **Présentations** (PowerPoint, LibreOffice Impress)
- **Éditeur de texte avancé** (VS Code, Sublime - SwingIDE existe mais basique)

### Développement
- **Éditeur de code moderne** (syntax highlighting, LSP, Git integration)
- **Terminal avancé** (onglets, split, profiles) — Terminator existe, fonctions avancées à ajouter
- **Outils Git GUI** done
- **Docker/Container management** written but not integrated yet

### Personnalisation
- **Thèmes desktop** (au-delà du wallpaper)
- **Icon packs**
- **Window decorations personnalisables**
- **Animations/effets configurables**

### Cloud/Intégration
- **Stockage cloud** (Google Drive, Dropbox, Nextcloud)
- **Sync contacts/calendrier**
- **Email client moderne** (IMAP/SMTP) done (app `mail` — vrai client IMAP/SMTP sur Jakarta Mail 2.x/Angus, comptes multiples, règles, pièces jointes, TLS par défaut, partagé 2D + 3D)

## Applications dans l'incubator (non buildées)

**Disponibles mais exclus:**
- [bgmanager](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/bgmanager:0:0-0:0) - Background manager 3D
- [clock](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/clock:0:0-0:0) - Horloge 3D
- [fm3d](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/fm3d:0:0-0:0) - File manager 3D
- [physics](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/physics:0:0-0:0) - Simulation physique
- [presentoire](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/presentoire:0:0-0:0) - Présentations

**Exclus pour dépendances manquantes:**
- [browser](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/browser:0:0-0:0), [browser3d](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/browser3d:0:0-0:0) - Navigateur legacy (ICEbrowser, Jini manquants) — supplanté par l'app moderne `webbrowser` (JavaFX/WebKit)
- [intel3d](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/intel3d:0:0-0:0) - Applications Intel (Jini manquant)
- [archviz3d](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/archviz3d:0:0-0:0) - Architecture 3D (XMLBeans manquant)
- [wilkoaim3d](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/wilkoaim3d:0:0-0:0) - AIM (service AOL discontinué)

## Priorités suggérées

1. **Navigateur web** done - JavaFX/WebKit, intégré en 2D + preview 3D (app `webbrowser`)
2. **Client email fonctionnel** done - vrai client IMAP/SMTP (Jakarta Mail 2.x/Angus), comptes + règles + pièces jointes, partagé 2D (`MailPanel`) + 3D (`Mail3D`)
3. **Suite bureautique légère** - Traitement de texte/tableur
4. **Lecteur multimédia** done - Audio Player + Video Player
5. **Terminal avancé** - Terminator existe; onglets/split/profiles à ajouter
6. **Moniteur système** done - Task Manager + Control Center
7. **Gestionnaire de fichiers avancé** done - compression, partage réseau, montage de volumes
