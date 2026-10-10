/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import org.jdesktop.lg3d.apps.gitgui.GitGuiPanel;
import org.jdesktop.lg3d.apps.texteditor.ext.ExtensionBroker;
import org.jdesktop.lg3d.apps.texteditor.ext.ExtensionRegistry;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;
import org.jdesktop.lg3d.apps.texteditor.ext.EditorSinks;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission;
import org.jdesktop.lg3d.apps.texteditor.ext.ToolbarContribution;

/**
 * Espresso, the Advanced Text Editor: a production plain-text and source-code
 * editor for the lg3d desktop, built as one plain-Swing panel that serves both
 * desktops. In the 3D desktop the {@link AdvancedTextEditor} wrapper hosts it
 * on a {@code SwingNode} inside a {@code Frame3D} through
 * {@code TitledSwingWindow}; in the 2D/Swing desktop
 * {@code Desktop2DAppRegistry.PANEL_APPS} opens this very panel as an MDI
 * internal frame. Because it can be captured offscreen, every secondary
 * surface (find &amp; replace, settings, recent files, extensions) is an
 * in-panel card or bar &mdash; never a popup or a modal dialog, and selectors
 * are {@code JList}s, not combo boxes.
 *
 * <p><b>Feature set.</b> Multi-tab editing with dirty tracking and close
 * confirmation; safe file IO (size and binary guards, BOM and strict-UTF-8
 * charset detection, per-file line endings, atomic saves that preserve POSIX
 * permissions); regex or literal find / replace with live match counts,
 * wrap-around navigation and Go-to-Line; single-undo-step Replace All;
 * debounced regex syntax highlighting for 16 built-in languages; line-number
 * gutter with current-line and bracket-match highlighting; smart
 * auto-indent; soft or hard tabs; word wrap; zoom; light and dark themes;
 * persisted settings and recent files; merge-on-type undo (a typed burst is
 * one undo step); a west {@link ProjectTreePanel} project file tree (re-rooted
 * on the project of the document being edited, double-click opens a file) and
 * a south {@link OutputConsole} where extension tool actions land their
 * output; and a {@link TextEditorExtension} SPI loaded through
 * {@code META-INF/services}, shipped with a bundled "Text Tools" extension
 * (sort lines, strip trailing whitespace, timestamp, case transforms).</p>
 *
 * <p><b>Security.</b> The editor never executes file content, refuses
 * binaries and over-sized files, writes through temp-file-plus-atomic-move so
 * a crash can't truncate the original, copies the target's permissions onto
 * the replacement, and isolates misbehaving extensions behind a
 * {@code Throwable} containment boundary in {@link ExtensionBroker}.</p>
 */
public class AdvancedTextEditorPanel extends JPanel
        implements FindReplaceBar.Host, SettingsCard.Host,
        RecentCard.Host, ExtensionsCard.Host, ProjectCard.Host,
        ToolbarCustomizeCard.Host {

    /** Preferred panel width, read by the 3D wrapper and the MDI host. */
    public static final int WIDTH_PX = 960;

    /** Preferred panel height, read by the 3D wrapper and the MDI host. */
    public static final int HEIGHT_PX = 680;

    private static final String CARD_EDITOR = "editor";
    private static final String CARD_SETTINGS = "settings";
    private static final String CARD_RECENT = "recent";
    private static final String CARD_EXTENSIONS = "extensions";
    private static final String CARD_PROJECT = "project";
    private static final String CARD_GIT = "git";
    /** The toolbar-customise card (choose/reorder user toolbar buttons). */
    static final String CARD_TOOLBAR = "toolbar";

    /** How long a transient status message stays up. */
    private static final int MESSAGE_MS = 4000;

    /** Idle pause before the debounced {@code onDocumentChanged} fires. */
    private static final int ANALYSIS_DELAY_MS = 400;

    private static final Logger logger =
            Logger.getLogger(AdvancedTextEditorPanel.class.getName());

    /**
     * The curated display order for extension categories; development-oriented
     * groups lead. Categories not listed here keep their registration order and
     * appear after these.
     */
    private static final List<String> PREFERRED_CATEGORIES = List.of(
            "Text", "Code", "Java/Kotlin", "Mandela", "Web", "Spring", "Data",
            "Encoding", "Markdown", "Analysis", "General");

    /**
     * One action contributed by one extension, tagged with its category, the
     * owning contribution id and its (optional) keyboard accelerator.
     */
    record ExtensionAction(String category, String extension, String id, String label,
                           String accelerator, Runnable run) {
    }

    private final boolean persistEnabled;
    private EditorSettings settings;
    private final JTabbedPane tabs = new JTabbedPane();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JPanel editorCard = new JPanel(new BorderLayout());
    private final FindReplaceBar findBar;
    private final SettingsCard settingsCard;
    private final RecentCard recentCard;
    private final ExtensionsCard extensionsCard;
    /**
     * The toolbar-customise card ("Customize Toolbar\u2026"): a {@code JList}-based,
     * in-panel editor for the user's ordered toolbar button selection. Hosted in
     * the same {@link #center} card layout as the other surfaces, so it captures
     * offscreen like everything else.
     */
    private final ToolbarCustomizeCard toolbarCustomizeCard;
    /**
     * The user-configured extension-button row; repopulated from
     * {@code settings.getToolbarButtons()} by {@link #rebuildUserToolbarButtons()}.
     * A {@code FlowLayout} so the row wraps onto extra lines instead of being
     * clipped when the user adds more commands than fit the window width.
     */
    private final JPanel userButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    /**
     * The whole second toolbar row: the user's command buttons plus the
     * {@code Customize\u2026} entry point and the empty-state hint (see
     * {@link #buildToolbarNorth()}).
     */
    private final JPanel userStrip = new JPanel(new BorderLayout(6, 0));
    /** Shown on the command row while the user has selected no extension command. */
    private final JLabel userHint = new JLabel(
            "No extension commands on the toolbar yet \u2014 use \u201CCustomize\u2026\u201D to add some.");
    /**
     * The currently-available contributions indexed by their stable
     * {@code providerId/actionId} key, rebuilt with the extension actions so the
     * user toolbar and the customise card resolve config ids to live commands.
     */
    private final Map<String, ExtensionBroker.ConfiguredContribution> contributionsByKey =
            new LinkedHashMap<>();
    /** "Projet" menu card: create / open / manage project folders (Phase 5). */
    private final ProjectCard projectCard;
    /**
     * Espresso's integration with the desktop Git client: the very
     * {@link GitGuiPanel} the Git GUI app hosts, embedded as its own card and
     * re-pointed at the current project root. Constructed empty — it runs no
     * process until {@link #openGitGui()} opens a repository.
     */
    private final GitGuiPanel gitGuiPanel = new GitGuiPanel();
    /** Last directory handed to the embedded Git GUI (test seam). */
    private volatile File gitTarget;
    private final EditorStatusBar statusBar = new EditorStatusBar();
    /** South console receiving compile/run tool output (never the document). */
    private final OutputConsole outputConsole = new OutputConsole();
    /** South Problems tab: the aggregated diagnostic list. */
    private final ProblemsPanel problemsPanel = new ProblemsPanel();
    /** South Structure tab: the current document's symbol outline. */
    private final StructurePanel structurePanel = new StructurePanel();
    /** South Debug tab: the debugger surface (breakpoints/stack/locals/output). */
    private final DebugPanel debugPanel = new DebugPanel();
    private final CompletionPanel completionPanel = new CompletionPanel();
    /**
     * The inline at-caret completion popup (Phase 6), fed from the same
     * {@code publishCompletions} sink as {@link #completionPanel} and layered
     * inside the current tab's scroll pane (offscreen-safe).
     */
    private final CompletionPopup completionPopup = new CompletionPopup();
    /** The last candidates published, replayed by a Ctrl+Space force-show. */
    private List<String> lastCompletionCandidates = List.of();
    /** The path those last candidates belong to (test seam / force routing). */
    private String lastCompletionPath = "";
    /** The identifier run the popup is currently completing (caret auto-hide). */
    private String lastCompletionPrefix = "";
    /** The 0-based offset where {@link #lastCompletionPrefix} starts. */
    private int lastCompletionAnchor;
    /** South tabbed region holding Output / Problems / Structure / Debug / Completions. */
    private final JTabbedPane bottomTabs = new JTabbedPane();
    /** Debounces live analysis after the last keystroke. */
    private final Timer analysisTimer;
    /** West lazily-loaded project file tree. */
    private final ProjectTreePanel projectTree = new ProjectTreePanel();
    private final List<ExtensionAction> extensionActions = new ArrayList<>();
    /** Accelerator KeyStrokes bound by the last {@link #bindAccelerators()} pass. */
    private final List<KeyStroke> boundAccelerators = new ArrayList<>();
    /** ActionMap names bound by the last {@link #bindAccelerators()} pass. */
    private final List<String> boundAcceleratorNames = new ArrayList<>();
    private final ExtensionRegistry extensionRegistry;
    private final ExtensionBroker extensionBroker;
    private final Timer messageTimer;

    private final JButton saveButton = new JButton("Save");
    private final JButton undoButton = new JButton("Undo");
    private final JButton redoButton = new JButton("Redo");
    private final JToggleButton wrapToggle = new JToggleButton("Wrap");
    private final JToggleButton highlightToggle =
            new JToggleButton("Highlight");

    private Runnable onClose;
    private int untitledCounter;
    private List<SearchEngine.Match> matches = List.of();

    /**
     * Builds the editor with the persisted user settings. Used by the 3D
     * wrapper and (reflectively) by the 2D desktop's app registry.
     */
    public AdvancedTextEditorPanel() {
        this(true);
    }

    /**
     * Builds the editor; {@code persistEnabled} false keeps every preference
     * write off the platform store (the headless-test constructor).
     */
    AdvancedTextEditorPanel(boolean persistEnabled) {
        this.persistEnabled = persistEnabled;
        this.settings = persistEnabled ? safeLoadSettings()
                : EditorSettings.defaults();

        EditorStore store = new EditorStore();
        this.extensionRegistry = new ExtensionRegistry(store);
        this.extensionBroker = new ExtensionBroker(extensionRegistry,
                EditorSinks.builder()
                        .showMessage(this::message)
                        .openFile(this::openFileForExtension)
                        .saveFile(this::saveCurrentTab)
                        .showOutput(this::showOutputForExtension)
                        .clearOutput(this::clearOutputConsole)
                        .reportDiagnostics(this::reportDiagnosticsForExtension)
                        .clearDiagnostics(this::clearDiagnosticsForExtension)
                        .showStructure(this::showStructureForExtension)
                        .navigate(this::navigateForExtension)
                        .breakpointsFor(this::breakpointsForExtension)
                        .appendDebugOutput(debugPanel::appendOutput)
                        .setDebugState(debugPanel::setState)
                        .showStack(debugPanel::setStack)
                        .showLocals(debugPanel::setLocals)
                        .setBreakpoints(debugPanel::setBreakpoints)
                        .showCompletions(this::showCompletionsForExtension)
                        .build());
        this.analysisTimer = new Timer(ANALYSIS_DELAY_MS,
                e -> notifyDocumentChanged(currentTab()));
        this.analysisTimer.setRepeats(false);

        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        findBar = new FindReplaceBar(this);
        findBar.setVisible(false);
        settingsCard = new SettingsCard(this);
        recentCard = new RecentCard(this);
        extensionsCard = new ExtensionsCard(this);
        toolbarCustomizeCard = new ToolbarCustomizeCard(this);
        projectCard = new ProjectCard(this);

        projectTree.setOnFileChosen(this::openPath);

        // South tabbed region: Output / Problems / Structure / Debug / Completions.
        // The Output tab keeps the legacy console; the others are the IDE surfaces.
        bottomTabs.setName("bottomTabs");
        bottomTabs.addTab("Output", outputConsole);
        bottomTabs.addTab("Problems", problemsPanel);
        bottomTabs.addTab("Structure", structurePanel);
        bottomTabs.addTab("Debug", debugPanel);
        bottomTabs.addTab("Completions", completionPanel);
        problemsPanel.setOnActivate(this::gotoDiagnostic);
        structurePanel.setOnActivate(symbol -> {
            EditorTab tab = currentTab();
            if (tab != null) {
                tab.goToLine(symbol.line());
            }
        });
        // Accepting a completion inserts at the active tab's caret (Phase 4).
        completionPanel.setOnActivate(text -> {
            EditorTab tab = currentTab();
            if (tab != null) {
                tab.insertCompletion(text);
            }
        });
        // The inline popup accepts through the identical insert-at-caret path.
        completionPopup.setOnAccept(text -> {
            EditorTab tab = currentTab();
            if (tab != null) {
                tab.insertCompletion(text);
            }
        });

        // Editor over the south tab region, inside the editor card only.
        JSplitPane editorVSPLIT = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                tabs, bottomTabs);
        editorVSPLIT.setResizeWeight(0.78);
        editorVSPLIT.setOneTouchExpandable(true);
        editorCard.add(findBar, BorderLayout.NORTH);
        editorCard.add(editorVSPLIT, BorderLayout.CENTER);

        center.add(editorCard, CARD_EDITOR);
        center.add(settingsCard, CARD_SETTINGS);
        center.add(recentCard, CARD_RECENT);
        center.add(extensionsCard, CARD_EXTENSIONS);
        center.add(projectCard, CARD_PROJECT);
        center.add(toolbarCustomizeCard, CARD_TOOLBAR);
        // The Git GUI wants 1000x680; scroll rather than clip inside the
        // 960x680 card area.
        JScrollPane gitScroll = new JScrollPane(gitGuiPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        gitScroll.setName("gitCard");
        center.add(gitScroll, CARD_GIT);

        // Project tree (west) beside the card area.
        JSplitPane mainHSPLIT = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                projectTree, center);
        mainHSPLIT.setResizeWeight(0.0);
        mainHSPLIT.setOneTouchExpandable(true);

        messageTimer = new Timer(MESSAGE_MS, e -> statusBar.setMessage(""));
        messageTimer.setRepeats(false);

        add(buildToolbarNorth(), BorderLayout.NORTH);
        add(mainHSPLIT, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        tabs.addChangeListener(e -> {
            completionPopup.hide();
            refreshButtons();
            refreshStatus();
            scheduleAnalysis();
        });
        tabs.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                // Middle-click closes the tab under the pointer.
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    int index = tabs.indexAtLocation(e.getX(), e.getY());
                    if (index >= 0) {
                        closeTab(index);
                    }
                }
            }
        });

        bindPanelKeys();
        installExtensions();
        newTab();
        refreshButtons();
        refreshStatus();
    }

    // ------------------------------------------------------------------
    // Toolbar
    // ------------------------------------------------------------------

    /**
     * The NORTH chrome, in two rows: the fixed built-in actions on the first,
     * the user-configured extension commands (plus the {@code Customize\u2026}
     * entry point) on the second.
     *
     * <p>The two rows are not a cosmetic choice. The built-ins alone already want
     * more width than the panel's {@value #WIDTH_PX}px preferred size, and a
     * {@code JToolBar} lays its children out in a single row from the left,
     * pushing everything past its right edge off-screen. Appending the user's
     * buttons to that row therefore made them (and the {@code Customize\u2026}
     * button) unreachable: a configured command existed in the model but was
     * never visible or clickable. On its own row the command strip gets the full
     * width, and its {@code FlowLayout} wraps onto extra lines when the user
     * adds more commands than fit.</p>
     */
    private JComponent buildToolbarNorth() {
        JPanel north = new JPanel(new BorderLayout());
        north.setName("toolbarNorth");
        north.add(buildToolbar(), BorderLayout.CENTER);
        north.add(buildUserCommandStrip(), BorderLayout.SOUTH);
        return north;
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        bar.add(button("New", "New tab (Ctrl+T)", e -> newTab()));
        bar.add(button("Open\u2026", "Open a file (Ctrl+O)",
                e -> openWithChooser()));
        saveButton.setToolTipText("Save (Ctrl+S)");
        saveButton.addActionListener(e -> saveCurrent());
        bar.add(saveButton);
        bar.add(button("Save As\u2026", "Save to a chosen file (Ctrl+Shift+S)",
                e -> saveAsWithChooser()));
        bar.add(button("Recent\u2026", "Reopen a recent file",
                e -> showRecent()));
        bar.addSeparator();

        undoButton.setToolTipText("Undo (Ctrl+Z)");
        undoButton.addActionListener(e -> undoCurrent());
        redoButton.setToolTipText("Redo (Ctrl+Y)");
        redoButton.addActionListener(e -> redoCurrent());
        bar.add(undoButton);
        bar.add(redoButton);
        bar.addSeparator();

        bar.add(button("Find\u2026", "Find and replace (Ctrl+F)",
                e -> showFindBar()));
        bar.add(button("Go to Line\u2026", "Jump to a line (Ctrl+G)",
                e -> {
                    showFindBar();
                    findBar.focusLine();
                }));
        bar.addSeparator();

        bar.add(button("\u2212", "Zoom out (Ctrl+-)", e -> zoom(-1)));
        bar.add(button("+", "Zoom in (Ctrl++)", e -> zoom(+1)));
        bar.addSeparator();

        wrapToggle.setToolTipText("Toggle word wrap");
        wrapToggle.setSelected(settings.isWordWrap());
        wrapToggle.addActionListener(e -> {
            settings.setWordWrap(wrapToggle.isSelected());
            applySettingsToTabs();
        });
        highlightToggle.setToolTipText("Toggle syntax highlighting");
        highlightToggle.setSelected(settings.isHighlight());
        highlightToggle.addActionListener(e -> {
            settings.setHighlight(highlightToggle.isSelected());
            applySettingsToTabs();
        });
        bar.add(wrapToggle);
        bar.add(highlightToggle);
        bar.addSeparator();

        bar.add(button("Settings\u2026", "Editor settings",
                e -> showSettings()));
        bar.add(button("Extensions\u2026",
                "Run actions contributed by installed extensions",
                e -> showExtensions()));
        bar.add(button("Project\u2026", "Create and manage projects, open the "
                + "Git client for the current project", e -> showProject()));
        bar.addSeparator();
        bar.add(button("Close", "Close the editor window", e -> requestClose()));
        return bar;
    }

    /**
     * Builds the second toolbar row: a {@code Commands:} label, the wrapping
     * section of user-configured extension buttons (with an italic hint while that
     * section is empty) and the {@code Customize\u2026} button pinned to the right
     * so it stays reachable however many commands the user adds.
     */
    private JComponent buildUserCommandStrip() {
        userButtons.setName("userToolbarButtons");
        userStrip.setName("userToolbarStrip");
        userHint.setName("userToolbarHint");
        userHint.setFont(userHint.getFont().deriveFont(Font.ITALIC));

        JPanel commands = new JPanel(new BorderLayout());
        commands.add(userButtons, BorderLayout.NORTH);
        commands.add(userHint, BorderLayout.SOUTH);

        JButton customize = button("Customize\u2026",
                "Choose which extension commands appear here, and how "
                        + "(icon, text, or both)",
                e -> showToolbarCustomize());
        customize.setName("customizeToolbarButton");
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        east.add(customize);

        userStrip.add(new JLabel("Commands:"), BorderLayout.WEST);
        userStrip.add(commands, BorderLayout.CENTER);
        userStrip.add(east, BorderLayout.EAST);
        rebuildUserToolbarButtons();
        return userStrip;
    }

    private static JButton button(String text, String tooltip,
            java.awt.event.ActionListener action) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(action);
        return button;
    }

    // ------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------

    private void bindPanelKeys() {
        InputMap input = center.getInputMap(
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actions = center.getActionMap();
        bind(input, actions, "ctrl T", "te-new", () -> newTab());
        bind(input, actions, "ctrl O", "te-open", this::openWithChooser);
        bind(input, actions, "ctrl S", "te-save", this::saveCurrent);
        bind(input, actions, "ctrl shift S", "te-save-as",
                this::saveAsWithChooser);
        bind(input, actions, "ctrl W", "te-close-tab", this::closeCurrentTab);
        bind(input, actions, "ctrl F", "te-find", this::showFindBar);
        bind(input, actions, "ctrl H", "te-find2", this::showFindBar);
        bind(input, actions, "F3", "te-find-next", this::findNext);
        bind(input, actions, "shift F3", "te-find-prev", this::findPrevious);
        bind(input, actions, "ctrl G", "te-goto", () -> {
            showFindBar();
            findBar.focusLine();
        });
        bind(input, actions, "ctrl SPACE", "te-complete", this::forceShowCompletion);
        bind(input, actions, "ctrl Z", "te-undo", this::undoCurrent);
        bind(input, actions, "ctrl Y", "te-redo", this::redoCurrent);
        bind(input, actions, "ctrl EQUALS", "te-zoom-in", () -> zoom(+1));
        bind(input, actions, "ctrl PLUS", "te-zoom-in2", () -> zoom(+1));
        bind(input, actions, "ctrl MINUS", "te-zoom-out", () -> zoom(-1));
        bind(input, actions, "ESCAPE", "te-escape", this::escape);
        // Ctrl+Tab / Ctrl+Shift+Tab cycle the tabs.
        bind(input, actions, "ctrl TAB", "te-tab-next", () -> cycleTab(1));
        bind(input, actions, "ctrl shift TAB", "te-tab-prev",
                () -> cycleTab(-1));
    }

    private static void bind(InputMap input, ActionMap actions,
            String key, String name, Runnable run) {
        // KeyStroke.getKeyStroke parses the full spec: "ctrl shift S",
        // "shift F3", "ctrl EQUALS", "ESCAPE", ...
        input.put(KeyStroke.getKeyStroke(key), name);
        actions.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                run.run();
            }
        });
    }

    private void escape() {
        if (findBar.isVisible()) {
            hideFindBar();
        } else {
            cards.show(center, CARD_EDITOR);
        }
    }

    private void cycleTab(int delta) {
        int count = tabs.getTabCount();
        if (count > 1) {
            tabs.setSelectedIndex((tabs.getSelectedIndex() + delta + count)
                    % count);
        }
    }

    // ------------------------------------------------------------------
    // Tabs and documents
    // ------------------------------------------------------------------

    /** Creates and selects a new untitled tab. */
    public final EditorTab newTab() {
        EditorTab tab = new EditorTab(settings);
        untitledCounter++;
        tab.setUntitledName(untitledCounter == 1
                ? "Untitled" : "Untitled " + untitledCounter);
        tab.setDirtyListener(() -> {
            refreshTabTitles();
            refreshButtons();
        });
        tab.setCaretListener(this::refreshStatus);
        tab.setChangeListener(this::scheduleAnalysis);
        tabs.addTab(tab.getDisplayName(), tab);
        // Route Up/Down/Enter/Tab/Esc to the inline popup while it is showing.
        completionPopup.installOn(tab);
        // A custom tab header carries the title plus a close (x) button, so every
        // tab is closable by click (Ctrl+W and middle-click still work).
        tabs.setTabComponentAt(tabs.indexOfComponent(tab), new ClosableTabHeader(tab));
        tabs.setSelectedComponent(tab);
        refreshTabTitles();
        return tab;
    }

    /** The selected tab, or null when every tab is closed. */
    public final EditorTab currentTab() {
        return (EditorTab) tabs.getSelectedComponent();
    }

    /** How many tabs are open (test seam). */
    public final int tabCount() {
        return tabs.getTabCount();
    }

    /** The editor tab strip (test seam). */
    final JTabbedPane tabStrip() {
        return tabs;
    }

    /** Closes the selected tab, confirming when it is dirty. */
    public final void closeCurrentTab() {
        int index = tabs.getSelectedIndex();
        if (index >= 0) {
            closeTab(index);
        }
    }

    /** Closes a tab by index, confirming when it is dirty. */
    public final void closeTab(int index) {
        if (index < 0 || index >= tabs.getTabCount()) {
            return;
        }
        EditorTab tab = (EditorTab) tabs.getComponentAt(index);
        if (tab.isDirty() && !GraphicsEnvironment.isHeadless()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Save changes to " + tab.getDisplayName()
                            + " before closing?",
                    "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION);
            if (answer == JOptionPane.CANCEL_OPTION
                    || answer == JOptionPane.CLOSED_OPTION) {
                return;
            }
            if (answer == JOptionPane.YES_OPTION && !saveTab(tab)) {
                return;
            }
        }
        tabs.removeTabAt(index);
        refreshButtons();
        refreshStatus();
    }

    /**
     * Saves {@code tab} to its file, falling back to Save As for untitled
     * tabs. Returns false when the save was cancelled or failed.
     */
    private boolean saveTab(EditorTab tab) {
        if (tab == null) {
            return false;
        }
        return (tab.getPath() != null)
                ? saveToPath(tab, tab.getPath()) : saveAsWithChooser();
    }

    private void refreshTabTitles() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            EditorTab tab = (EditorTab) tabs.getComponentAt(i);
            String tip = tab.getPath() != null
                    ? tab.getPath().toString() : "Unsaved document";
            java.awt.Component header = tabs.getTabComponentAt(i);
            if (header instanceof ClosableTabHeader cth) {
                cth.refresh();
            } else {
                // Defensive: a tab added without a header (should not happen) gets
                // one now, so the close button is never missing.
                tabs.setTabComponentAt(i, new ClosableTabHeader(tab));
            }
            tabs.setToolTipTextAt(i, tip);
        }
    }

    /**
     * A tab header: the document title (with a dirty dot) and a small close (x)
     * button. The button resolves the tab's current index at click time, because
     * closing a tab shifts the indices of the tabs after it. Plain {@link JLabel}
     * and {@link JButton} only, so it renders in the 3D desktop's offscreen
     * capture like the rest of the panel (no Synth checkbox/radio widgets).
     */
    private final class ClosableTabHeader extends JPanel {
        private final EditorTab tab;
        private final JLabel titleLabel = new JLabel();

        ClosableTabHeader(EditorTab tab) {
            super(new FlowLayout(FlowLayout.LEFT, 4, 0));
            this.tab = tab;
            setOpaque(false);
            titleLabel.setFocusable(false);
            JButton close = new JButton("\u00D7");
            close.setFocusable(false);
            close.setContentAreaFilled(false);
            close.setBorderPainted(false);
            close.setOpaque(false);
            close.setMargin(new java.awt.Insets(0, 2, 0, 2));
            close.setToolTipText("Close tab");
            close.addActionListener(e -> closeTab(tabs.indexOfComponent(tab)));
            add(titleLabel);
            add(close);
            refresh();
        }

        /** Re-reads the title and dirty dot from the owning tab. */
        void refresh() {
            titleLabel.setText(tab.getDisplayName()
                    + (tab.isDirty() ? " \u2022" : ""));
        }
    }

    // ------------------------------------------------------------------
    // File actions
    // ------------------------------------------------------------------

    private void openWithChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open File");
        if (!settings.getRecentFiles().isEmpty()) {
            File last = new File(settings.getRecentFiles().get(0));
            if (last.getParentFile() != null) {
                chooser.setCurrentDirectory(last.getParentFile());
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile());
        }
    }

    /**
     * Opens {@code file} in a tab &mdash; the reflective hook used by the
     * desktop's file-association launcher (double-clicking a {@code .txt} in
     * the File Manager routes here once the user picks this editor).
     *
     * @return true if the file loaded into a tab
     */
    public boolean openFile(File file) {
        if (file == null) {
            return false;
        }
        return openPath(file.toPath());
    }

    /**
     * Loads {@code path} into a tab (reusing the tab that already shows it).
     * All {@link TextFileIO} guards apply; failures surface as a status
     * message, never as an exception.
     */
    public final boolean openPath(Path path) {
        if (path == null) {
            return false;
        }
        for (int i = 0; i < tabs.getTabCount(); i++) {
            EditorTab tab = (EditorTab) tabs.getComponentAt(i);
            if (tab.getPath() != null && sameFile(tab.getPath(), path)) {
                tabs.setSelectedIndex(i);
                // A re-open refreshes the recent list even when the tab is
                // reused.
                settings.pushRecent(path.toAbsolutePath().toString());
                persistSettings();
                message("Already open: " + tab.getDisplayName());
                return true;
            }
        }
        TextFileIO.LoadResult result;
        try {
            result = TextFileIO.load(path);
        } catch (IOException | RuntimeException ioe) {
            message("Could not open " + path.getFileName() + ": "
                    + ioe.getMessage());
            logger.log(Level.INFO, "Open failed for {0}", path);
            return false;
        }
        EditorTab tab = newTab();
        tab.loadContent(result.text(), path, result.charset(), result.eol());
        settings.pushRecent(path.toAbsolutePath().toString());
        persistSettings();
        message(result.warning() != null ? result.warning()
                : "Opened " + tab.getDisplayName());
        refreshProjectTree(path);
        notifyDocumentOpened(tab);
        return true;
    }

    /** Saves the current tab; unnamed tabs go through Save As. */
    public final boolean saveCurrent() {
        EditorTab tab = currentTab();
        if (tab == null) {
            return false;
        }
        if (tab.getPath() == null) {
            return saveAsWithChooser();
        }
        return saveToPath(tab, tab.getPath());
    }

    private boolean saveAsWithChooser() {
        EditorTab tab = currentTab();
        if (tab == null) {
            return false;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save As");
        if (tab.getPath() != null) {
            chooser.setSelectedFile(tab.getPath().toFile());
        } else {
            chooser.setSelectedFile(new File(tab.getDisplayName() + ".txt"));
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return false;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target)) {
            int answer = JOptionPane.showConfirmDialog(this,
                    target.getFileName() + " exists. Overwrite it?",
                    "Confirm Overwrite", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        return saveToPath(tab, target);
    }

    /**
     * Writes a tab to {@code target} through the atomic-save path, then
     * rebinds the tab to it. Exceptions surface as status messages.
     */
    public final boolean saveToPath(EditorTab tab, Path target) {
        if (tab == null || target == null) {
            return false;
        }
        try {
            TextFileIO.save(target, tab.getText(), tab.getCharset(),
                    tab.getEol());
        } catch (IOException | RuntimeException ioe) {
            message("Could not save: " + ioe.getMessage());
            logger.log(Level.INFO, "Save failed for {0}", target);
            return false;
        }
        tab.setPath(target);
        tab.markSaved();
        settings.pushRecent(target.toAbsolutePath().toString());
        persistSettings();
        refreshTabTitles();
        message("Saved " + target.getFileName());
        refreshProjectTree(target);
        notifyDocumentSaved(tab);
        return true;
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException | RuntimeException ioe) {
            return a.toAbsolutePath().normalize()
                    .equals(b.toAbsolutePath().normalize());
        }
    }

    // ------------------------------------------------------------------
    // Undo / zoom / settings
    // ------------------------------------------------------------------

    private void undoCurrent() {
        EditorTab tab = currentTab();
        if (tab != null) {
            tab.undo();
            refreshButtons();
            refreshStatus();
        }
    }

    private void redoCurrent() {
        EditorTab tab = currentTab();
        if (tab != null) {
            tab.redo();
            refreshButtons();
            refreshStatus();
        }
    }

    private void zoom(int delta) {
        settings.setFontSize(settings.getFontSize() + delta);
        applySettingsToTabs();
    }

    private void applySettingsToTabs() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            ((EditorTab) tabs.getComponentAt(i)).applySettings(settings);
        }
        wrapToggle.setSelected(settings.isWordWrap());
        highlightToggle.setSelected(settings.isHighlight());
        statusBar.setFontSize(settings.getFontSize());
        persistSettings();
        refreshStatus();
    }

    /** The live settings object (test seam; never null). */
    public final EditorSettings settings() {
        return settings;
    }

    private static EditorSettings safeLoadSettings() {
        try {
            return EditorSettings.loadUser();
        } catch (RuntimeException rte) {
            // A locked or corrupt preference store must not stop the editor.
            return EditorSettings.defaults();
        }
    }

    private void persistSettings() {
        if (!persistEnabled) {
            return;
        }
        try {
            settings.saveUser();
        } catch (RuntimeException rte) {
            // Includes SecurityException when the prefs store is locked.
            logger.log(Level.FINE, "Could not persist editor settings", rte);
        }
    }

    // ------------------------------------------------------------------
    // Find & replace (FindReplaceBar.Host)
    // ------------------------------------------------------------------

    /** Shows the find bar and runs the live match count. */
    public final void showFindBar() {
        cards.show(center, CARD_EDITOR);
        findBar.setVisible(true);
        findBar.focusFind();
        queryChanged();
    }

    private void hideFindBar() {
        findBar.setVisible(false);
        EditorTab tab = currentTab();
        if (tab != null) {
            tab.textPane().requestFocusInWindow();
        }
    }

    /** The find bar (test seam). */
    final FindReplaceBar findBar() {
        return findBar;
    }

    @Override
    public void queryChanged() {
        refreshMatches(true);
    }

    @Override
    public void findNext() {
        EditorTab tab = currentTab();
        if (tab == null) {
            return;
        }
        refreshMatches(false);
        if (matches.isEmpty()) {
            reportNoMatches();
            return;
        }
        int origin = tab.textPane().getSelectionEnd();
        int index = -1;
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).start() >= origin) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            if (!findBar.wrap()) {
                message("No more matches below the caret");
                return;
            }
            index = 0;
        }
        selectMatch(index);
    }

    @Override
    public void findPrevious() {
        EditorTab tab = currentTab();
        if (tab == null) {
            return;
        }
        refreshMatches(false);
        if (matches.isEmpty()) {
            reportNoMatches();
            return;
        }
        int origin = tab.textPane().getSelectionStart();
        int index = -1;
        for (int i = matches.size() - 1; i >= 0; i--) {
            if (matches.get(i).start() < origin) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            if (!findBar.wrap()) {
                message("No more matches above the caret");
                return;
            }
            index = matches.size() - 1;
        }
        selectMatch(index);
    }

    @Override
    public void replaceOne() {
        EditorTab tab = currentTab();
        if (tab == null) {
            return;
        }
        String query = findBar.query();
        if (query.isEmpty()) {
            return;
        }
        refreshMatches(false);
        JTextComponent pane = tab.textPane();
        SearchEngine.Match at = new SearchEngine.Match(
                pane.getSelectionStart(), pane.getSelectionEnd());
        if (!isMatchAt(tab.getText(), at, query)) {
            findNext();
            at = new SearchEngine.Match(pane.getSelectionStart(),
                    pane.getSelectionEnd());
            if (!isMatchAt(tab.getText(), at, query)) {
                reportNoMatches();
                return;
            }
        }
        String replaced = SearchEngine.replaceOne(tab.getText(), at,
                findBar.replacement(), query, findBar.regex(),
                findBar.matchCase());
        int caret = at.start() + Math.max(0,
                replaced.length() - tab.getText().length()
                        + at.length());
        tab.replaceWholeText(replaced);
        pane.setCaretPosition(Math.min(caret,
                tab.document().getLength()));
        message("Replaced 1 occurrence");
        refreshMatches(true);
    }

    @Override
    public void replaceAll() {
        EditorTab tab = currentTab();
        if (tab == null) {
            return;
        }
        String query = findBar.query();
        if (query.isEmpty()) {
            return;
        }
        SearchEngine.ReplaceResult result = SearchEngine.replaceAll(
                tab.getText(), query, findBar.replacement(),
                findBar.regex(), findBar.matchCase());
        if (result.count() == 0) {
            reportNoMatches();
            return;
        }
        tab.replaceWholeText(result.text());
        message(result.count() + (result.count() == 1
                ? " replacement" : " replacements"));
        refreshMatches(true);
    }

    @Override
    public void goToLine() {
        EditorTab tab = currentTab();
        int line = findBar.lineNumber();
        if (tab == null || line < 1) {
            message("Enter a line number first");
            return;
        }
        tab.goToLine(line);
        refreshStatus();
        message("Line " + Math.min(line, tab.getLineCount()));
    }

    @Override
    public void closeBar() {
        hideFindBar();
    }

    private void refreshMatches(boolean selectFirst) {
        EditorTab tab = currentTab();
        String query = findBar.query();
        if (tab == null || query.isEmpty()) {
            matches = List.of();
            findBar.setMatchInfo("");
            return;
        }
        matches = SearchEngine.findAll(tab.getText(), query,
                findBar.regex(), findBar.matchCase());
        if (matches.isEmpty()) {
            findBar.setMatchInfo("No matches");
            return;
        }
        String suffix = matches.size() >= SearchEngine.MAX_MATCHES
                ? "+" : "";
        if (selectFirst) {
            int caret = tab.textPane().getCaretPosition();
            int index = 0;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i).start() >= caret) {
                    index = i;
                    break;
                }
                index = 0;
            }
            selectMatch(index);
        } else {
            findBar.setMatchInfo(matches.size() + " matches" + suffix);
        }
    }

    private void selectMatch(int index) {
        EditorTab tab = currentTab();
        if (tab == null || index < 0 || index >= matches.size()) {
            return;
        }
        SearchEngine.Match match = matches.get(index);
        tab.textPane().select(match.start(), match.end());
        String suffix = matches.size() >= SearchEngine.MAX_MATCHES
                ? "+" : "";
        findBar.setMatchInfo((index + 1) + " of " + matches.size()
                + suffix);
    }

    private boolean isMatchAt(String text, SearchEngine.Match at,
            String query) {
        if (at == null || at.end() <= at.start()) {
            return false;
        }
        try {
            java.util.regex.Matcher matcher = SearchEngine.compile(query,
                    findBar.regex(), findBar.matchCase()).matcher(text);
            return matcher.find(at.start())
                    && matcher.start() == at.start()
                    && matcher.end() == at.end();
        } catch (java.util.regex.PatternSyntaxException
                | StackOverflowError err) {
            return false;
        }
    }

    private void reportNoMatches() {
        String query = findBar.query();
        if (!query.isEmpty()) {
            findBar.setMatchInfo("No matches");
            message("No matches for \"" + query + "\"");
        }
    }

    // ------------------------------------------------------------------
    // Cards (settings / recent / extensions)
    // ------------------------------------------------------------------

    @Override
    public void applySettings(EditorSettings edited) {
        if (edited == null) {
            cancelSettings();
            return;
        }
        // The recent list lives on the panel's settings, not in the card;
        // push it in reverse so pushRecent's prepend restores the order.
        List<String> recent = new ArrayList<>(settings.getRecentFiles());
        for (int i = recent.size() - 1; i >= 0; i--) {
            edited.pushRecent(recent.get(i));
        }
        // ... and the same for the recent-project list, which lives here too.
        List<String> projects = new ArrayList<>(settings.getRecentProjects());
        for (int i = projects.size() - 1; i >= 0; i--) {
            edited.pushProject(projects.get(i));
        }
        this.settings = edited;
        applySettingsToTabs();
        cards.show(center, CARD_EDITOR);
        message("Settings applied");
    }

    @Override
    public void cancelSettings() {
        cards.show(center, CARD_EDITOR);
    }

    private void showSettings() {
        settingsCard.load(settings);
        cards.show(center, CARD_SETTINGS);
    }

    private void showRecent() {
        recentCard.load(settings.getRecentFiles());
        cards.show(center, CARD_RECENT);
    }

    private void showExtensions() {
        extensionsCard.show(buildExtensionRows(), extensionInfos());
        cards.show(center, CARD_EXTENSIONS);
    }

    /** Opens the toolbar-customise card with the live available/active commands. */
    private void showToolbarCustomize() {
        toolbarCustomizeCard.show(availableCommands(), settings.getToolbarButtons());
        cards.show(center, CARD_TOOLBAR);
    }

    @Override
    public List<ToolbarCustomizeCard.AvailableCommand> availableCommands() {
        List<ToolbarCustomizeCard.AvailableCommand> out = new ArrayList<>();
        for (Map.Entry<String, ExtensionBroker.ConfiguredContribution> e
                : contributionsByKey.entrySet()) {
            ExtensionBroker.ConfiguredContribution cc = e.getValue();
            out.add(new ToolbarCustomizeCard.AvailableCommand(e.getKey(),
                    cc.extension(), cc.category(), cc.contribution().getLabel()));
        }
        return out;
    }

    @Override
    public List<ToolbarButtonConfig.Entry> currentToolbar() {
        return settings.getToolbarButtons().entries();
    }

    @Override
    public void applyToolbar(List<ToolbarButtonConfig.Entry> entries) {
        ToolbarButtonConfig config = new ToolbarButtonConfig();
        if (entries != null) {
            for (ToolbarButtonConfig.Entry entry : entries) {
                config.add(entry.id(), entry.mode());
            }
        }
        settings.setToolbarButtons(config);
        persistSettings();
        rebuildUserToolbarButtons();
    }

    /**
     * Groups the flat {@link #extensionActions} list under category headers in
     * the curated {@link #PREFERRED_CATEGORIES} order (then any remaining
     * categories in registration order). Only the display is reordered: each
     * action row carries its original index, so {@link #runExtensionAction(int)}
     * stays stable regardless of grouping.
     */
    private List<ExtensionsCard.Row> buildExtensionRows() {
        List<ExtensionsCard.Row> rows = new ArrayList<>();
        java.util.Set<String> emitted = new java.util.LinkedHashSet<>();
        for (String category : PREFERRED_CATEGORIES) {
            if (emitCategoryRows(rows, category)) {
                emitted.add(category);
            }
        }
        for (ExtensionAction action : extensionActions) {
            if (!emitted.contains(action.category())
                    && emitCategoryRows(rows, action.category())) {
                emitted.add(action.category());
            }
        }
        return rows;
    }

    /** Adds a header plus its action rows for {@code category}; false if empty. */
    private boolean emitCategoryRows(List<ExtensionsCard.Row> rows, String category) {
        List<ExtensionsCard.Row> body = new ArrayList<>();
        for (int i = 0; i < extensionActions.size(); i++) {
            ExtensionAction action = extensionActions.get(i);
            if (action.category().equals(category)) {
                String text = action.extension() + ": " + action.label();
                String accel = formatAccelerator(effectiveAccelerator(action));
                if (!accel.isEmpty()) {
                    text += "  (" + accel + ")";
                }
                body.add(ExtensionsCard.Row.action(text, i, action.extension()));
            }
        }
        if (body.isEmpty()) {
            return false;
        }
        rows.add(ExtensionsCard.Row.header(category));
        rows.addAll(body);
        return true;
    }

    @Override
    public void openRecent(String path) {
        cards.show(center, CARD_EDITOR);
        if (path != null) {
            openPath(Path.of(path));
        }
    }

    @Override
    public void removeRecent(String path) {
        // Rebuild the settings without the entry: pushRecent prepends, so
        // the survivors go back in reverse to keep most-recent-first.
        EditorSettings copy = EditorSettings.fromMap(settings.toMap());
        List<String> ordered = new ArrayList<>(settings.getRecentFiles());
        ordered.remove(path);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            copy.pushRecent(ordered.get(i));
        }
        // fromMap carries neither list; restore the projects unchanged.
        List<String> orderedProjects = new ArrayList<>(settings.getRecentProjects());
        for (int i = orderedProjects.size() - 1; i >= 0; i--) {
            copy.pushProject(orderedProjects.get(i));
        }
        this.settings = copy;
        persistSettings();
        recentCard.load(settings.getRecentFiles());
    }

    @Override
    public void clearRecent() {
        settings.clearRecent();
        persistSettings();
        recentCard.load(settings.getRecentFiles());
    }

    @Override
    public void closeCard() {
        cards.show(center, CARD_EDITOR);
    }

    @Override
    public void runExtensionAction(int index) {
        if (index < 0 || index >= extensionActions.size()) {
            return;
        }
        cards.show(center, CARD_EDITOR);
        // Extensions hold the document snapshot taken at open time; refresh it
        // so every action runs against the live text (a build action must
        // compile what the user currently sees, not the file as opened).
        notifyDocumentOpened(currentTab());
        try {
            extensionActions.get(index).run().run();
        } catch (Throwable t) {
            // Containment boundary: an extension may never break the editor.
            logger.log(Level.WARNING, "Extension action failed", t);
            message("Extension action failed (see log)");
        }
        refreshStatus();
    }

    @Override
    public List<ExtensionsCard.ExtensionInfo> extensionInfos() {
        List<ExtensionsCard.ExtensionInfo> out = new ArrayList<>();
        for (ExtensionRegistry.LoadedExtension le : extensionRegistry.extensions()) {
            TextEditorManifest m = le.getManifest();
            out.add(new ExtensionsCard.ExtensionInfo(m.getId(), m.getName(),
                    m.getVersion(), normalizeCategory(le.getExtension().category()),
                    le.isEnabled()));
        }
        return out;
    }

    @Override
    public void setExtensionEnabled(String id, boolean enabled) {
        extensionRegistry.setEnabled(id, enabled);
        refreshExtensionBindings();
    }

    /**
     * The accelerator that actually governs an action right now: the user's
     * stored override when one exists (an override of {@code ""} means
     * deliberately unbound), otherwise the extension's declared default.
     */
    private String effectiveAccelerator(ExtensionAction action) {
        String override = settings.getAcceleratorOverrides().get(action.id());
        return (override != null) ? override : action.accelerator();
    }

    @Override
    public String getAccelerator(int actionIndex) {
        if (actionIndex < 0 || actionIndex >= extensionActions.size()) {
            return "";
        }
        return effectiveAccelerator(extensionActions.get(actionIndex));
    }

    @Override
    public void setAccelerator(int actionIndex, String spec) {
        if (actionIndex < 0 || actionIndex >= extensionActions.size()) {
            return;
        }
        ExtensionAction action = extensionActions.get(actionIndex);
        String requested = (spec == null) ? "" : spec.trim();
        String declared = (action.accelerator() == null) ? "" : action.accelerator();
        if (requested.equals(declared)) {
            // Back to the built-in default: drop the override entirely.
            settings.clearAcceleratorOverride(action.id());
        } else {
            settings.setAcceleratorOverride(action.id(), requested);
        }
        persistSettings();
        refreshExtensionBindings();
    }

    @Override
    public List<ExtensionsCard.PermissionInfo> permissionsFor(String id) {
        List<ExtensionsCard.PermissionInfo> out = new ArrayList<>();
        ExtensionRegistry.LoadedExtension le = findExtension(id);
        if (le == null) {
            return out;
        }
        Set<TextEditorPermission> granted = le.getGranted();
        for (TextEditorPermission p : le.getManifest().getPermissions()) {
            out.add(new ExtensionsCard.PermissionInfo(p.name(), granted.contains(p)));
        }
        return out;
    }

    @Override
    public void setPermission(String id, String name, boolean granted) {
        ExtensionRegistry.LoadedExtension le = findExtension(id);
        if (le == null) {
            return;
        }
        TextEditorPermission permission;
        try {
            permission = TextEditorPermission.valueOf(name);
        } catch (RuntimeException rte) {
            return; // unknown permission name
        }
        Set<TextEditorPermission> next = EnumSet.noneOf(TextEditorPermission.class);
        next.addAll(le.getGranted());
        if (granted) {
            next.add(permission);
        } else {
            next.remove(permission);
        }
        extensionRegistry.grant(id, next);
        // A TOOLBAR change alters the contributed actions, so re-load and re-bind.
        refreshExtensionBindings();
    }

    private ExtensionRegistry.LoadedExtension findExtension(String id) {
        if (id == null) {
            return null;
        }
        for (ExtensionRegistry.LoadedExtension le : extensionRegistry.extensions()) {
            if (le.getManifest().getId().equals(id)) {
                return le;
            }
        }
        return null;
    }

    /** Test seam: is the given accelerator spec currently bound to an extension action? */
    final boolean isAcceleratorBound(String spec) {
        KeyStroke ks = KeyStroke.getKeyStroke(spec);
        return ks != null && boundAccelerators.contains(ks);
    }

    private static String normalizeCategory(String category) {
        return (category == null || category.isBlank()) ? "General" : category.trim();
    }

    /** Renders a KeyStroke spec such as {@code "control alt S"} as {@code "Ctrl+Alt+S"}. */
    static String formatAccelerator(String spec) {
        if (spec == null || spec.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String token : spec.trim().split("\\s+")) {
            switch (token.toLowerCase()) {
                case "control" -> parts.add("Ctrl");
                case "alt" -> parts.add("Alt");
                case "shift" -> parts.add("Shift");
                case "meta" -> parts.add("Meta");
                default -> parts.add(Character.toUpperCase(token.charAt(0))
                        + token.substring(1));
            }
        }
        return String.join("+", parts);
    }

    // ------------------------------------------------------------------
    // Extensions
    // ------------------------------------------------------------------

    private void installExtensions() {
        extensionRegistry.scan();
        extensionBroker.notifyStarted();
        loadExtensionActions();
        bindAccelerators();
    }

    /**
     * Rebuilds the flat {@link #extensionActions} list from the broker's
     * currently-enabled contributions. Called at startup and again whenever an
     * extension is enabled or disabled, so the list stays in step with the
     * registry. The list order is the broker (services-file) order, which keeps
     * {@link #runExtensionAction(int)} indices stable.
     */
    private void loadExtensionActions() {
        extensionActions.clear();
        contributionsByKey.clear();
        for (ExtensionBroker.ContributedAction ca
                : extensionBroker.categorizedActions()) {
            extensionActions.add(new ExtensionAction(ca.category(), ca.extension(),
                    ca.contribution().getId(), ca.contribution().getLabel(),
                    ca.contribution().getAccelerator(), ca.contribution().getAction()));
        }
        for (ExtensionBroker.ConfiguredContribution cc
                : extensionBroker.configuredContributions()) {
            contributionsByKey.put(cc.providerId() + "/" + cc.contribution().getId(), cc);
        }
        rebuildUserToolbarButtons();
    }

    /**
     * Repopulates the command row from {@code settings.getToolbarButtons()},
     * resolving each stored id against the currently-available contributions.
     * Stale ids (a disabled/removed extension) render nothing but are left
     * untouched in the persisted config, so disabling an extension never corrupts
     * the user's layout. The empty-state hint swaps in when nothing renders.
     */
    private void rebuildUserToolbarButtons() {
        userButtons.removeAll();
        ToolbarButtonConfig config = settings.getToolbarButtons();
        for (ToolbarButtonConfig.Entry entry
                : config.resolve(contributionsByKey.keySet())) {
            userButtons.add(makeUserButton(entry));
        }
        userHint.setVisible(userButtons.getComponentCount() == 0);
        userStrip.revalidate();
        userStrip.repaint();
    }

    /** How many user-configured extension buttons currently render (test seam). */
    final int userToolbarButtonCount() {
        return userToolbarButtons().size();
    }

    /** The rendered user command buttons, left to right (test seam). */
    final List<JButton> userToolbarButtons() {
        List<JButton> out = new ArrayList<>();
        for (Component button : userButtons.getComponents()) {
            if (button instanceof JButton b) {
                out.add(b);
            }
        }
        return out;
    }

    /** The command strip, so a test can lay it out and check its geometry (test seam). */
    final JComponent userCommandStrip() {
        return userStrip;
    }

    /**
     * Builds one user toolbar button for a resolved entry: icon and/or text per
     * its display mode, a tooltip naming the command (and its accelerator), and
     * an action listener that runs the contribution through the same guarded,
     * live-document path as {@link #runExtensionAction(int)}. Accelerators stay
     * bound via the existing {@link #bindAccelerators()} pass, so a toolbar
     * button and its shortcut share one behaviour.
     */
    private JButton makeUserButton(ToolbarButtonConfig.Entry entry) {
        ExtensionBroker.ConfiguredContribution cc = contributionsByKey.get(entry.id());
        ToolbarContribution c = cc.contribution();
        JButton b = new JButton();
        b.setFocusable(false);
        b.setName("extButton:" + entry.id());
        String label = c.getLabel();
        Icon icon = (c.getIcon() != null) ? c.getIcon()
                : EditorGlyphs.toolbarIcon(
                        EditorGlyphs.guess(c.getId() + " " + cc.providerId(), label));
        switch (entry.mode()) {
            case ICON -> {
                b.setIcon(icon);
                b.setText(null);
            }
            case TEXT -> {
                b.setText(label);
                b.setIcon(null);
            }
            case ICON_TEXT -> {
                b.setText(label);
                b.setIcon(icon);
            }
        }
        String tip = cc.extension() + ": " + label;
        if (!c.getTooltip().isBlank()) {
            tip = c.getTooltip();
        }
        String accel = formatAccelerator(effectiveAcceleratorFor(entry.id(), c));
        if (!accel.isEmpty()) {
            tip += "  (" + accel + ")";
        }
        b.setToolTipText(tip);
        b.addActionListener(e -> runContribution(c));
        return b;
    }

    /**
     * Runs one toolbar contribution against the live document, mirroring
     * {@link #runExtensionAction(int)}'s guard and document-snapshot refresh so a
     * toolbar button and the Extensions card's Run produce identical behaviour.
     */
    private void runContribution(ToolbarContribution c) {
        cards.show(center, CARD_EDITOR);
        notifyDocumentOpened(currentTab());
        try {
            c.getAction().run();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Toolbar extension action failed", t);
            message("Extension action failed (see log)");
        }
        refreshStatus();
    }

    /** The effective accelerator for a configured contribution id. */
    private String effectiveAcceleratorFor(String key, ToolbarContribution c) {
        String override = settings.getAcceleratorOverrides().get(c.getId());
        return (override != null) ? override : c.getAccelerator();
    }

    /**
     * Binds every declared extension accelerator as a keyboard shortcut on the
     * shared {@code center} input map (the same map as {@link #bindPanelKeys()}).
     * Pressing a shortcut runs the action directly from the editor &mdash; no
     * need to open the Extensions card. Old bindings are cleared first so that
     * toggling an extension re-binds cleanly; the first action claiming a given
     * KeyStroke wins (later duplicates are skipped, never silently overriding).
     */
    private void bindAccelerators() {
        InputMap input = center.getInputMap(
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actions = center.getActionMap();
        for (KeyStroke ks : boundAccelerators) {
            input.remove(ks);
        }
        for (String name : boundAcceleratorNames) {
            actions.remove(name);
        }
        boundAccelerators.clear();
        boundAcceleratorNames.clear();
        java.util.Set<KeyStroke> used = new java.util.HashSet<>();
        for (int i = 0; i < extensionActions.size(); i++) {
            ExtensionAction action = extensionActions.get(i);
            String spec = effectiveAccelerator(action);
            if (spec == null || spec.isBlank()) {
                continue;
            }
            KeyStroke ks = KeyStroke.getKeyStroke(spec);
            if (ks == null || !used.add(ks)) {
                continue; // invalid spec, or already claimed by an earlier action
            }
            String name = "ext-accel:" + action.id();
            final int index = i;
            input.put(ks, name);
            actions.put(name, new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    runExtensionAction(index);
                }
            });
            boundAccelerators.add(ks);
            boundAcceleratorNames.add(name);
        }
    }

    /**
     * Re-runs the action list and accelerator bindings after an enable/disable
     * toggle, then refreshes the Extensions card so both views agree.
     */
    private void refreshExtensionBindings() {
        loadExtensionActions();
        bindAccelerators();
        showExtensions();
    }

    /** How many extension actions were registered (test seam). */
    final int extensionActionCount() {
        return extensionActions.size();
    }

    /** Notifies extensions when a document is opened (test seam). */
    void notifyDocumentOpened(EditorTab tab) {
        if (tab == null) {
            return;
        }
        JTextComponent pane = tab.textPane();
        String selectedText = pane.getSelectedText();
        extensionBroker.notifyDocumentOpened(
                tab.getPath() != null ? tab.getPath().toString() : null,
                tab.getPath() != null ? tab.getPath().getFileName().toString() : null,
                tab.getText(),
                selectedText != null ? selectedText : "",
                pane.getCaretPosition(), tab.getCaretLine(), tab.getCaretColumn(),
                pane.getSelectionStart(), pane.getSelectionEnd(),
                text -> tab.replaceWholeText(text),
                text -> tab.textPane().replaceSelection(text)
        );
    }

    /**
     * Notifies extensions of a debounced content change on {@code tab}, carrying
     * the current caret/selection geometry. Fired by {@link #analysisTimer}.
     */
    void notifyDocumentChanged(EditorTab tab) {
        if (tab == null) {
            return;
        }
        JTextComponent pane = tab.textPane();
        String selectedText = pane.getSelectedText();
        extensionBroker.notifyDocumentChanged(
                tab.getPath() != null ? tab.getPath().toString() : null,
                tab.getPath() != null ? tab.getPath().getFileName().toString() : null,
                tab.getText(),
                selectedText != null ? selectedText : "",
                pane.getCaretPosition(), tab.getCaretLine(), tab.getCaretColumn(),
                pane.getSelectionStart(), pane.getSelectionEnd(),
                text -> tab.replaceWholeText(text),
                text -> tab.textPane().replaceSelection(text)
        );
    }

    /** Restarts the debounced live-analysis timer (called on every edit). */
    private void scheduleAnalysis() {
        analysisTimer.restart();
    }

    /** Notifies extensions when a document is saved. */
    private void notifyDocumentSaved(EditorTab tab) {
        if (tab == null) {
            return;
        }
        JTextComponent pane = tab.textPane();
        String selectedText = pane.getSelectedText();
        extensionBroker.notifyDocumentSaved(
                tab.getPath() != null ? tab.getPath().toString() : null,
                tab.getPath() != null ? tab.getPath().getFileName().toString() : null,
                tab.getText(),
                selectedText != null ? selectedText : "",
                pane.getCaretPosition(), tab.getCaretLine(), tab.getCaretColumn(),
                pane.getSelectionStart(), pane.getSelectionEnd(),
                text -> tab.replaceWholeText(text),
                text -> tab.textPane().replaceSelection(text)
        );
    }

    /** Saves the current tab for extension use. */
    private void saveCurrentTab() {
        saveCurrent();
    }

    /** Opens a file for extension use. */
    private void openFileForExtension() {
        openWithChooser();
    }

    // ------------------------------------------------------------------
    // Output console and project tree (west/south chrome)
    // ------------------------------------------------------------------

    /**
     * Appends a titled tool-output block to the south console and makes sure
     * the editor card (not a settings-style card) is showing. Delegate for
     * {@code EditorContext.showOutput}; called on the EDT.
     */
    void showOutputForExtension(String title, String body) {
        cards.show(center, CARD_EDITOR);
        outputConsole.appendOutput(title, body);
    }

    /** Empties the south console. Delegate for {@code EditorContext.clearOutput}. */
    void clearOutputConsole() {
        outputConsole.clear();
    }

    /**
     * Paints {@code diagnostics} on the matching tab (falling back to the current
     * one), lists them in the Problems tab and summarises the counts on the status
     * line. Delegate for {@code EditorContext.reportDiagnostics}; EDT-only.
     */
    void reportDiagnosticsForExtension(String path, List<Diagnostic> diagnostics) {
        List<Diagnostic> diags = (diagnostics == null) ? List.of() : diagnostics;
        EditorTab tab = tabForPath(path);
        if (tab != null) {
            tab.setDiagnostics(diags);
        }
        problemsPanel.setDiagnostics(diags);
        int errors = 0;
        int warnings = 0;
        for (Diagnostic d : diags) {
            if (d.kind() == Diagnostic.Kind.ERROR) {
                errors++;
            } else if (d.kind() == Diagnostic.Kind.WARNING) {
                warnings++;
            }
        }
        if (errors + warnings > 0) {
            message(errors + " error(s), " + warnings + " warning(s)");
        }
    }

    /** Clears the diagnostics for {@code path}. Delegate for clearDiagnostics. */
    void clearDiagnosticsForExtension(String path) {
        EditorTab tab = tabForPath(path);
        if (tab != null) {
            tab.clearDiagnostics();
        }
        problemsPanel.clear();
    }

    /** Fills the Structure tab. Delegate for {@code EditorContext.showStructure}. */
    void showStructureForExtension(String path, List<StructureSymbol> symbols) {
        cards.show(center, CARD_EDITOR);
        String title = (path == null || path.isEmpty())
                ? "Structure" : baseName(path);
        structurePanel.setStructure(title, symbols);
    }

    /**
     * Paints the completion strip and the inline popup. Delegate for
     * {@code EditorContext.publishCompletions}; an empty list clears both
     * (Phase 4 strip, Phase 6 popup). The popup is shown only when the caret
     * sits on an identifier run or after a dot ({@link CompletionTrigger}); the
     * strip always mirrors the full candidate list.
     */
    void showCompletionsForExtension(String path, List<String> candidates) {
        boolean empty = (candidates == null || candidates.isEmpty());
        List<String> list = empty ? List.of() : candidates;
        String title = (path == null || path.isEmpty() || empty)
                ? null : baseName(path);
        completionPanel.setCompletions(title, list);
        lastCompletionCandidates = list;
        lastCompletionPath = (path == null) ? "" : path;
        updateCompletionPopup(false);
    }

    /**
     * Re-evaluates and repaints the inline popup from the last published
     * candidates for the current tab. A force-show (Ctrl+Space) reveals the
     * popup even with no identifier prefix; otherwise it appears only on an
     * identifier run / after a dot and hides whenever those conditions fail.
     */
    private void updateCompletionPopup(boolean force) {
        EditorTab tab = currentTab();
        if (tab == null || lastCompletionCandidates.isEmpty()) {
            completionPopup.hide();
            return;
        }
        CompletionTrigger.Decision d = CompletionTrigger.evaluate(
                tab.getText(), tab.caretOffset(), lastCompletionCandidates, force);
        if (d.show()) {
            lastCompletionPrefix = d.prefix();
            lastCompletionAnchor = d.anchor();
            completionPopup.show(tab, lastCompletionCandidates);
        } else {
            completionPopup.hide();
        }
    }

    /** Ctrl+Space: force the inline popup open at the caret. */
    final void forceShowCompletion() {
        updateCompletionPopup(true);
    }

    /** The inline popup (test seam). */
    final CompletionPopup completionPopup() {
        return completionPopup;
    }

    /** The last candidates routed to the completion surfaces (test seam). */
    final List<String> lastCompletionCandidates() {
        return lastCompletionCandidates;
    }

    /** Jumps the caret to a location. Delegate for {@code EditorContext.navigateTo}. */
    void navigateForExtension(String path, int line) {
        EditorTab tab = tabForPath(path);
        if (tab == null) {
            return;
        }
        cards.show(center, CARD_EDITOR);
        tabs.setSelectedComponent(tab);
        tab.goToLine(line);
    }

    /**
     * The gutter's 1-based breakpoint lines for the tab owning {@code path}.
     * Delegate for {@code EditorContext.getBreakpoints}; an unknown path (or
     * no tab at all) simply has no breakpoints.
     */
    List<Integer> breakpointsForExtension(String path) {
        EditorTab tab = tabForPath(path);
        return (tab == null) ? List.of() : tab.breakpointLines();
    }

    /** Opens the location of a Problems-tab row. */
    private void gotoDiagnostic(Diagnostic d) {
        EditorTab tab = tabForPath(d.path());
        if (tab == null) {
            return;
        }
        cards.show(center, CARD_EDITOR);
        tabs.setSelectedComponent(tab);
        tab.goToLine(d.line());
    }

    /** The tab editing {@code path}, or the current tab when it is blank/unknown. */
    private EditorTab tabForPath(String path) {
        if (path != null && !path.isEmpty()) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                EditorTab tab = (EditorTab) tabs.getComponentAt(i);
                if (tab.getPath() != null && tab.getPath().toString().equals(path)) {
                    return tab;
                }
            }
        }
        return currentTab();
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (slash >= 0 && slash < path.length() - 1)
                ? path.substring(slash + 1) : path;
    }

    /** Re-roots the west tree on the project directory enclosing {@code file}. */
    void refreshProjectTree(Path file) {
        if (file != null) {
            projectTree.setRootPath(ProjectTreePanel.projectRootFor(file));
        }
    }

    /** The console transcript (test seam). */
    final String outputConsoleText() {
        return outputConsole.consoleText();
    }

    /** The console itself (test seam). */
    final OutputConsole outputConsole() {
        return outputConsole;
    }

    /** The project tree itself (test seam). */
    final ProjectTreePanel projectTree() {
        return projectTree;
    }

    // ------------------------------------------------------------------
    // Status, close, window hook
    // ------------------------------------------------------------------

    private void refreshStatus() {
        EditorTab tab = currentTab();
        if (tab == null) {
            completionPopup.hide();
            statusBar.setPosition(0, 0);
            statusBar.setSelection(0);
            statusBar.setDocumentSize(0, 0);
            statusBar.setFileInfo("-", "-", "-");
            return;
        }
        // Auto-hide the inline popup once the caret leaves the run it was
        // completing (arrow keys, clicks, Home/End), without disturbing the strip.
        if (completionPopup.isShowing() && CompletionTrigger.caretLeftRun(
                tab.getText(), tab.caretOffset(), lastCompletionAnchor,
                lastCompletionPrefix)) {
            completionPopup.hide();
        }
        statusBar.setPosition(tab.getCaretLine(), tab.getCaretColumn());
        JTextComponent pane = tab.textPane();
        statusBar.setSelection(pane.getSelectionEnd()
                - pane.getSelectionStart());
        int length = tab.document().getLength();
        statusBar.setDocumentSize(length, tab.getLineCount());
        statusBar.setFileInfo(tab.getCharset().name(), tab.getEol(),
                tab.getLanguage().getName());
        statusBar.setFontSize(settings.getFontSize());
    }

    private void refreshButtons() {
        EditorTab tab = currentTab();
        saveButton.setEnabled(tab != null);
        undoButton.setEnabled(tab != null && tab.canUndo());
        redoButton.setEnabled(tab != null && tab.canRedo());
    }

    /** Shows a transient message on the status line. */
    final void message(String text) {
        statusBar.setMessage(text);
        if (text != null && !text.isEmpty()) {
            messageTimer.restart();
        } else {
            messageTimer.stop();
        }
    }

    /** The current status message (test seam; synchronous). */
    public final String statusMessage() {
        return statusBar.getMessage();
    }

    /** The status bar's "Ln x, Col y" text (test seam). */
    final String statusPosition() {
        return statusBar.getPosition();
    }

    /**
     * Wires the panel's Close button to the hosting window
     * ({@code Desktop2DAppRegistry.setCloseCallback} looks this setter up
     * reflectively).
     */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    private void requestClose() {
        persistSettings();
        if (onClose != null) {
            onClose.run();
        }
    }

    /** Flushes preferences and stops the panel's timers on window close. */
    public void dispose() {
        completionPopup.hide();
        extensionBroker.notifyStopping();
        persistSettings();
        messageTimer.stop();
        analysisTimer.stop();
    }

    /** The south tabbed region (test seam). */
    final JTabbedPane bottomTabs() {
        return bottomTabs;
    }

    /** The Problems tab (test seam). */
    final ProblemsPanel problemsPanel() {
        return problemsPanel;
    }

    /** The Structure tab (test seam). */
    final StructurePanel structurePanel() {
        return structurePanel;
    }

    /** The Debug tab (test seam). */
    final DebugPanel debugPanel() {
        return debugPanel;
    }

    /** The Completions strip (test seam). */
    final CompletionPanel completionPanel() {
        return completionPanel;
    }

    // ------------------------------------------------------------------
    // Project menu (Phase 5): create / open / manage + Git GUI integration
    // ------------------------------------------------------------------

    /** Opens the "Projet" card. */
    private void showProject() {
        projectCard.load(currentProjectRoot(), settings.getRecentProjects());
        cards.show(center, CARD_PROJECT);
    }

    /** The attached project root, or null while the tree is per-file. */
    String currentProjectRoot() {
        Path root = projectTree.rootPath();
        return (root == null) ? null : root.toString();
    }

    @Override
    public void newProject(String name) {
        String clean = sanitizeProjectName(name);
        if (clean == null) {
            message("Invalid project name (letters, digits, . - _ only)");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose parent folder for " + clean);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path root = chooser.getSelectedFile().toPath().resolve(clean);
        try {
            createProjectSkeleton(root);
        } catch (IOException | SecurityException e) {
            message("Could not create project: " + e.getMessage());
            return;
        }
        attachProject(root);
        openPath(root.resolve("src").resolve("Main.java"));
        cards.show(center, CARD_EDITOR);
        message("Project " + clean + " created at " + root);
    }

    @Override
    public void openProjectFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Project Folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        Path current = projectTree.rootPath();
        if (current != null) {
            chooser.setCurrentDirectory(current.toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path root = chooser.getSelectedFile().toPath();
            attachProject(root);
            cards.show(center, CARD_EDITOR);
            message("Project attached: " + root);
        }
    }

    /** Points the west tree at {@code root} and remembers it persistently. */
    void attachProject(Path root) {
        if (root == null) {
            return;
        }
        projectTree.setRootPath(root);
        settings.pushProject(root.toString());
        persistSettings();
    }

    @Override
    public void reRootToCurrentFile() {
        EditorTab tab = currentTab();
        Path file = (tab == null) ? null : tab.getPath();
        if (file == null) {
            message("Save the document first to locate its project");
            return;
        }
        refreshProjectTree(file);
        cards.show(center, CARD_EDITOR);
        message("Tree rooted at " + ProjectTreePanel.projectRootFor(file));
    }

    @Override
    public void closeProject() {
        // "Detach": the tree falls back to the current document's directory
        // (or the home folder for an unsaved buffer), like a fresh session.
        EditorTab tab = currentTab();
        Path file = (tab == null) ? null : tab.getPath();
        Path fallback = (file != null && file.getParent() != null)
                ? file.getParent()
                : Path.of(System.getProperty("user.home"));
        projectTree.setRootPath(fallback);
        cards.show(center, CARD_EDITOR);
        message("Project detached");
    }

    @Override
    public void openGitGui() {
        Path root = projectTree.rootPath();
        if (root == null) {
            EditorTab tab = currentTab();
            Path file = (tab == null) ? null : tab.getPath();
            root = (file == null) ? null : ProjectTreePanel.projectRootFor(file);
        }
        if (root == null) {
            message("Open a file or attach a project first");
            return;
        }
        gitTarget = root.toFile();
        gitGuiPanel.openRepository(gitTarget);
        cards.show(center, CARD_GIT);
    }

    @Override
    public void openRecentProject(String path) {
        cards.show(center, CARD_EDITOR);
        if (path == null) {
            return;
        }
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            settings.removeProject(path);
            persistSettings();
            message("Project folder no longer exists: " + path);
            return;
        }
        attachProject(root);
        message("Project attached: " + root);
    }

    @Override
    public void removeRecentProject(String path) {
        settings.removeProject(path);
        persistSettings();
        projectCard.load(currentProjectRoot(), settings.getRecentProjects());
    }

    @Override
    public void clearRecentProjects() {
        settings.clearProjects();
        persistSettings();
        projectCard.load(currentProjectRoot(), settings.getRecentProjects());
    }

    /** Entry point written into every new project. */
    private static final String MAIN_TEMPLATE = """
            /**
             * New Espresso project.
             */
            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello, Espresso!");
                }
            }
            """;

    /**
     * Creates a minimal Java project skeleton: {@code src/Main.java} and a
     * {@code README.md}. Refuses a non-empty existing directory so an attach
     * mistake can never clobber work. Pure, headless-testable.
     */
    static Path createProjectSkeleton(Path root) throws IOException {
        if (Files.exists(root)) {
            try (java.util.stream.Stream<Path> entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException(root + " already exists and is not empty");
                }
            }
        }
        Path src = root.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Main.java"), MAIN_TEMPLATE,
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("README.md"),
                "# " + root.getFileName()
                        + "\n\nCreated by Espresso, the Project Looking Glass editor.\n",
                StandardCharsets.UTF_8);
        return root;
    }

    /**
     * Validates a new-project name: letters, digits, dot, dash and underscore
     * only; never {@code .} / {@code ..}; no path separators (so the name can
     * never escape the chosen parent folder).
     *
     * @return the trimmed name, or null when unusable
     */
    static String sanitizeProjectName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || !name.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return name;
    }

    /** The Project card (test seam). */
    final ProjectCard projectCard() {
        return projectCard;
    }

    /** The embedded desktop Git client panel (test seam). */
    final GitGuiPanel gitGuiPanel() {
        return gitGuiPanel;
    }

    /** The directory the embedded Git GUI was last pointed at (test seam). */
    final File gitTarget() {
        return gitTarget;
    }
}
