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
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import org.jdesktop.lg3d.apps.texteditor.ext.ExtensionBroker;
import org.jdesktop.lg3d.apps.texteditor.ext.ExtensionRegistry;
import org.jdesktop.lg3d.apps.texteditor.ext.TextEditorManifest;

/**
 * The Advanced Text Editor: a production plain-text and source-code editor
 * for the lg3d desktop, built as one plain-Swing panel that serves both
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
 * one undo step); and a {@link TextEditorExtension} SPI loaded through
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
        RecentCard.Host, ExtensionsCard.Host {

    /** Preferred panel width, read by the 3D wrapper and the MDI host. */
    public static final int WIDTH_PX = 960;

    /** Preferred panel height, read by the 3D wrapper and the MDI host. */
    public static final int HEIGHT_PX = 680;

    private static final String CARD_EDITOR = "editor";
    private static final String CARD_SETTINGS = "settings";
    private static final String CARD_RECENT = "recent";
    private static final String CARD_EXTENSIONS = "extensions";

    /** How long a transient status message stays up. */
    private static final int MESSAGE_MS = 4000;

    private static final Logger logger =
            Logger.getLogger(AdvancedTextEditorPanel.class.getName());

    /**
     * The curated display order for extension categories; development-oriented
     * groups lead. Categories not listed here keep their registration order and
     * appear after these.
     */
    private static final List<String> PREFERRED_CATEGORIES = List.of(
            "Text", "Code", "Java/Kotlin", "Web", "Encoding", "Markdown",
            "Analysis", "General");

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
    private final EditorStatusBar statusBar = new EditorStatusBar();
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
        this.extensionBroker = new ExtensionBroker(
                extensionRegistry,
                this::message,
                this::openFileForExtension,
                this::saveCurrentTab
        );

        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        findBar = new FindReplaceBar(this);
        findBar.setVisible(false);
        settingsCard = new SettingsCard(this);
        recentCard = new RecentCard(this);
        extensionsCard = new ExtensionsCard(this);

        editorCard.add(findBar, BorderLayout.NORTH);
        editorCard.add(tabs, BorderLayout.CENTER);

        center.add(editorCard, CARD_EDITOR);
        center.add(settingsCard, CARD_SETTINGS);
        center.add(recentCard, CARD_RECENT);
        center.add(extensionsCard, CARD_EXTENSIONS);

        messageTimer = new Timer(MESSAGE_MS, e -> statusBar.setMessage(""));
        messageTimer.setRepeats(false);

        add(buildToolbar(), BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        tabs.addChangeListener(e -> {
            refreshButtons();
            refreshStatus();
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
        bar.addSeparator();
        bar.add(button("Close", "Close the editor window", e -> requestClose()));

        return bar;
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
        tabs.addTab(tab.getDisplayName(), tab);
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
            tabs.setTitleAt(i, tab.getDisplayName()
                    + (tab.isDirty() ? " \u2022" : ""));
            tabs.setToolTipTextAt(i, tab.getPath() != null
                    ? tab.getPath().toString() : "Unsaved document");
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
                String accel = formatAccelerator(action.accelerator());
                if (!accel.isEmpty()) {
                    text += "  (" + accel + ")";
                }
                body.add(ExtensionsCard.Row.action(text, i));
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
        for (ExtensionBroker.ContributedAction ca
                : extensionBroker.categorizedActions()) {
            extensionActions.add(new ExtensionAction(ca.category(), ca.extension(),
                    ca.contribution().getId(), ca.contribution().getLabel(),
                    ca.contribution().getAccelerator(), ca.contribution().getAction()));
        }
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
            String spec = action.accelerator();
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
        String filePath = tab.getPath() != null ? tab.getPath().toString() : null;
        String fileName = tab.getPath() != null ? tab.getPath().getFileName().toString() : null;
        String selectedText = tab.textPane().getSelectedText();
        extensionBroker.notifyDocumentOpened(
                filePath,
                fileName,
                tab.getText(),
                selectedText != null ? selectedText : "",
                text -> tab.replaceWholeText(text),
                text -> tab.textPane().replaceSelection(text)
        );
    }

    /** Notifies extensions when a document is saved. */
    private void notifyDocumentSaved(EditorTab tab) {
        if (tab == null) {
            return;
        }
        String filePath = tab.getPath() != null ? tab.getPath().toString() : null;
        String fileName = tab.getPath() != null ? tab.getPath().getFileName().toString() : null;
        String selectedText = tab.textPane().getSelectedText();
        extensionBroker.notifyDocumentSaved(
                filePath,
                fileName,
                tab.getText(),
                selectedText != null ? selectedText : "",
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
    // Status, close, window hook
    // ------------------------------------------------------------------

    private void refreshStatus() {
        EditorTab tab = currentTab();
        if (tab == null) {
            statusBar.setPosition(0, 0);
            statusBar.setSelection(0);
            statusBar.setDocumentSize(0, 0);
            statusBar.setFileInfo("-", "-", "-");
            return;
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
        extensionBroker.notifyStopping();
        persistSettings();
        messageTimer.stop();
    }
}
