/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.mail;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.jdesktop.lg3d.scenemanager.utils.hud.NotificationService;

/**
 * The 2D/Swing mail client: a real, configurable IMAP/SMTP front end built on the
 * shared {@link MailSessionManager} / {@link MailService} model it also shares with
 * the native-3D {@link Mail3D}. It is registered in
 * {@code Desktop2DAppRegistry.PANEL_APPS} against the {@code Mail3D} main class, so
 * the one start-menu descriptor launches this panel as an MDI internal frame on the
 * Swing desktop while the 3D desktop keeps building {@code Mail3D}.
 *
 * <p>Layout is the classic three-pane mailbox: an account/folder {@link JTree} on
 * the left, a sortable message {@link JTable} in the middle, and the
 * {@link MessageReader} reading pane to the right / below / hidden per
 * {@link MailSettings}. A toolbar carries New / Reply / Reply All / Forward /
 * Delete / Mark unread / Flag / Refresh / Search / Settings, and a status bar shows
 * the connection state and unread count.</p>
 *
 * <p>All backend I/O runs off the EDT through {@link #load}; the headless tests
 * flip {@link #setSynchronous(boolean)} so the same code path executes inline and
 * deterministically. With no account configured the panel shows an empty state
 * rather than failing, so it never crashes offline.</p>
 *
 * <p><b>New-mail notifications:</b> the inbox keeps a seen-set of message ids, so
 * every reload after the first can tell which messages actually arrived and raise
 * one desktop notification for them; the state machine lives in the shared
 * {@link NewMailNotifier} (same behaviour as the 3D {@link Mail3D}), the toast
 * goes through {@link NotificationService#notify} on the running desktop shell,
 * and {@link MailSettings#isNotifyOnNewMail()} gates it. The periodic inbox check
 * runs on the {@link MailSessionManager} auto-check scheduler; both the notifier
 * and the scheduler are seams the headless tests replace.</p>
 */
public class MailPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 900;
    public static final int HEIGHT_PX = 600;

    /** How a compose window is prefilled. */
    enum ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD }

    private final MailSessionManager manager;
    private MailSettings settings;

    /** Runs backend calls inline instead of on a worker (headless tests). */
    private boolean synchronous;

    /**
     * Detects newly arrived inbox mail on every folder reload and raises the
     * desktop toast; swappable through {@link #setNewMailNotifier} for tests.
     */
    private NewMailNotifier newMailNotifier = new NewMailNotifier(null);

    // Model / state.
    private final List<MailAccount> accounts = new ArrayList<MailAccount>();
    private final List<MailFolder> folders = new ArrayList<MailFolder>();
    private MailAccount currentAccount;
    private String currentFolder = MailMessage.FOLDER_INBOX;
    private final List<MailMessage> messages = new ArrayList<MailMessage>();
    private MailMessage selected;

    // Widgets.
    private final DefaultMutableTreeNode treeRoot =
            new DefaultMutableTreeNode("Mail");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree tree = new JTree(treeModel);

    private final MailTableModel tableModel = new MailTableModel();
    private final JTable table = new JTable(tableModel);
    private final TableRowSorter<MailTableModel> sorter =
            new TableRowSorter<MailTableModel>(tableModel);

    private final MessageReader reader = new MessageReader();
    private final JLabel statusLabel = new JLabel(" ");
    private final JTextField searchField = new JTextField(18);
    private final JLabel emptyLabel = new JLabel(
            "Add an account to get started (Settings \u2192 Accounts).",
            JLabel.CENTER);

    private JSplitPane listReaderSplit;
    private final JPanel centerHolder = new JPanel(new BorderLayout());
    private Runnable onClose;

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /** Production constructor used by the reflective desktop registry. */
    public MailPanel() {
        this(new MailSessionManager(), MailSettings.load());
        refresh();
        // Periodic inbox poll on the manager's daemon scheduler: this is what
        // surfaces "you got mail" while the panel sits open. Interval 0 disables.
        startAutoCheck();
    }

    /**
     * Test seam: inject the session manager and settings. Unlike the no-arg
     * constructor this does <em>not</em> kick off a fetch, so a headless test can
     * {@link #setSynchronous(boolean)} first and then {@link #refresh()} inline.
     */
    public MailPanel(MailSessionManager manager, MailSettings settings) {
        super(new BorderLayout());
        this.manager = manager;
        this.settings = settings;
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        // Credentials: ASK-mode accounts prompt through a modal dialog. Tests
        // override this with an inline prompt before the first fetch.
        manager.setPasswordPrompt(account ->
                PasswordPromptDialog.prompt(this, account, manager.accounts()));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        applySettings();
    }

    /** Optional registry hook; invoked by {@link #close()} to close the host frame. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** (Re)starts the periodic auto-check from the current settings. */
    private void startAutoCheck() {
        manager.startAutoCheck(settings.getCheckIntervalMinutes(),
                this::autoCheckTick);
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("New", e -> newMessage()));
        bar.add(button("Reply", e -> reply()));
        bar.add(button("Reply All", e -> replyAll()));
        bar.add(button("Forward", e -> forward()));
        bar.addSeparator();
        bar.add(button("Delete", e -> deleteSelected()));
        bar.add(button("Mark unread", e -> toggleRead()));
        bar.add(button("Flag", e -> toggleFlag()));
        bar.addSeparator();
        bar.add(button("Refresh", e -> refresh()));
        bar.addSeparator();
        bar.add(new JLabel(" Search: "));
        bar.add(searchField);
        bar.add(button("Go", e -> doSearch()));
        bar.addSeparator();
        bar.add(button("Settings", e -> openSettings()));

        searchField.addActionListener(e -> doSearch());
        bindShortcuts();
        return bar;
    }

    private static JButton button(String label,
            java.awt.event.ActionListener a) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.addActionListener(a);
        return b;
    }

    private void bindShortcuts() {
        bind(KeyStroke.getKeyStroke("control N"), new AbstractAction() {
            public void actionPerformed(ActionEvent e) { newMessage(); } });
        bind(KeyStroke.getKeyStroke("control R"), new AbstractAction() {
            public void actionPerformed(ActionEvent e) { reply(); } });
        bind(KeyStroke.getKeyStroke("control F5"), new AbstractAction() {
            public void actionPerformed(ActionEvent e) { refresh(); } });
        bind(KeyStroke.getKeyStroke("DELETE"), new AbstractAction() {
            public void actionPerformed(ActionEvent e) { deleteSelected(); } });
    }

    private void bind(KeyStroke ks, javax.swing.Action a) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ks, ks);
        getActionMap().put(ks, a);
    }

    private Component buildCenter() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.addTreeSelectionListener(e -> onTreeSelection());
        JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setPreferredSize(new Dimension(200, HEIGHT_PX));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onTableSelection();
            }
        });
        table.getColumnModel().getColumn(MailTableModel.COL_DATE)
                .setCellRenderer(new DateRenderer());
        JScrollPane tableScroll = new JScrollPane(table);

        listReaderSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                tableScroll, reader);
        listReaderSplit.setResizeWeight(0.5);

        centerHolder.add(listReaderSplit, BorderLayout.CENTER);

        JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                treeScroll, centerHolder);
        outer.setDividerLocation(200);
        outer.setResizeWeight(0.0);
        return outer;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)));
        bar.add(statusLabel, BorderLayout.CENTER);
        return bar;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** Runs backend calls inline instead of on a worker (headless tests). */
    void setSynchronous(boolean synchronous) {
        this.synchronous = synchronous;
    }

    /** Replaces the new-mail detector (headless tests capture the posts). */
    void setNewMailNotifier(NewMailNotifier notifier) {
        this.newMailNotifier = (notifier == null)
                ? new NewMailNotifier(null) : notifier;
    }

    void applySettings() {
        Font listFont = new Font(settings.getListFontFamily(), Font.PLAIN,
                settings.getListFontSize());
        table.setFont(listFont);
        table.setRowHeight(settings.rowHeight());
        reader.applySettings(settings);
        reader.setAttachmentHandler(this::saveAttachment);

        boolean dark = settings.getTheme() == MailSettings.Theme.DARK;
        Color bg = dark ? new Color(0x1E, 0x1E, 0x24) : Color.WHITE;
        Color fg = dark ? new Color(0xDD, 0xDD, 0xDD) : Color.BLACK;
        table.setBackground(bg);
        table.setForeground(fg);
        emptyLabel.setForeground(fg);

        int pos = settings.getReadingPanePosition().ordinal();
        if (settings.getReadingPanePosition() == MailSettings.ReadingPanePosition.HIDDEN) {
            listReaderSplit.setRightComponent(null);
        } else {
            listReaderSplit.setOrientation(
                    settings.getReadingPanePosition()
                            == MailSettings.ReadingPanePosition.BOTTOM
                            ? JSplitPane.VERTICAL_SPLIT
                            : JSplitPane.HORIZONTAL_SPLIT);
            if (listReaderSplit.getRightComponent() == null) {
                listReaderSplit.setRightComponent(reader);
            }
        }
        applySortKeys();
        listReaderSplit.revalidate();
        listReaderSplit.repaint();
    }

    private void applySortKeys() {
        int col;
        switch (settings.getSortColumn()) {
            case FROM: col = MailTableModel.COL_FROM; break;
            case SUBJECT: col = MailTableModel.COL_SUBJECT; break;
            default: col = MailTableModel.COL_DATE; break;
        }
        SortOrder order = settings.isSortDescending()
                ? SortOrder.DESCENDING : SortOrder.ASCENDING;
        List<RowSorter.SortKey> keys = new ArrayList<RowSorter.SortKey>();
        keys.add(new RowSorter.SortKey(col, order));
        sorter.setSortKeys(keys);
    }

    // ------------------------------------------------------------------
    // Refresh / folder loading
    // ------------------------------------------------------------------

    /** Reloads accounts, the folder tree, and the current folder. */
    void refresh() {
        accounts.clear();
        accounts.addAll(manager.accounts().load());
        if (accounts.isEmpty()) {
            currentAccount = null;
            folders.clear();
            messages.clear();
            tableModel.setMessages(messages);
            treeRoot.removeAllChildren();
            treeModel.reload();
            reader.clear();
            showEmpty(true);
            setStatus("No account configured.");
            return;
        }
        showEmpty(false);
        if (currentAccount == null || manager.accounts().findById(
                currentAccount.getId()) == null) {
            MailAccount def = manager.accounts().defaultAccount();
            currentAccount = (def != null) ? def : accounts.get(0);
        }
        loadFolders();
    }

    private void loadFolders() {
        final String accountId = currentAccount.getId();
        setStatus("Connecting to " + currentAccount.getDisplayLabel() + "...");
        load(() -> manager.session(accountId).listFolders(), result -> {
            folders.clear();
            folders.addAll(result);
            buildTree();
            if (folderIndex(currentFolder) < 0) {
                currentFolder = pickInitialFolder();
            }
            loadFolder();
        });
    }

    private String pickInitialFolder() {
        for (MailFolder f : folders) {
            if (f.getType() == MailFolder.Type.INBOX) {
                return f.getName();
            }
        }
        return folders.isEmpty() ? MailMessage.FOLDER_INBOX
                : folders.get(0).getName();
    }

    private int folderIndex(String name) {
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private void buildTree() {
        treeRoot.removeAllChildren();
        for (MailAccount a : accounts) {
            DefaultMutableTreeNode an = new DefaultMutableTreeNode(
                    new AccountNode(a));
            if (a.getId().equals(currentAccount.getId())) {
                for (MailFolder f : folders) {
                    an.add(new DefaultMutableTreeNode(new FolderNode(a.getId(), f)));
                }
            }
            treeRoot.add(an);
        }
        treeModel.reload();
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    private void onTreeSelection() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return;
        }
        Object user = ((DefaultMutableTreeNode) path.getLastPathComponent())
                .getUserObject();
        if (user instanceof AccountNode) {
            currentAccount = ((AccountNode) user).account;
            currentFolder = null;
            loadFolders();
        } else if (user instanceof FolderNode) {
            FolderNode fn = (FolderNode) user;
            currentAccount = manager.accounts().findById(fn.accountId);
            selectFolder(fn.accountId, fn.folder.getName());
        }
    }

    /** Selects a folder and loads its messages (test-friendly entry point). */
    void selectFolder(String accountId, String folderName) {
        MailAccount a = manager.accounts().findById(accountId);
        if (a != null) {
            currentAccount = a;
        }
        currentFolder = folderName;
        loadFolder();
    }

    private void loadFolder() {
        final String accountId = currentAccount.getId();
        final String folder = currentFolder;
        setStatus("Loading " + folder + "...");
        load(() -> manager.fetch(accountId, folder), result -> {
            messages.clear();
            messages.addAll(result);
            selected = null;
            tableModel.setMessages(messages);
            reader.clear();
            applySortKeys();
            int unread = 0;
            for (MailMessage m : messages) {
                if (!m.isRead()) {
                    unread++;
                }
            }
            setStatus(connectedText() + "  \u2014  " + folder + ": "
                    + messages.size() + " message(s), " + unread + " unread");
            newMailNotifier.onFolderLoaded(accountId, folder, result,
                    settings.isNotifyOnNewMail());
        });
    }

    private String connectedText() {
        return currentAccount == null ? "" : currentAccount.getDisplayLabel();
    }

    // ------------------------------------------------------------------
    // Selection / reading
    // ------------------------------------------------------------------

    private void onTableSelection() {
        int view = table.getSelectedRow();
        if (view < 0) {
            return;
        }
        openMessage(tableModel.getMessageAt(table.convertRowIndexToModel(view)));
    }

    /** Opens a message: fetches its body lazily and marks it read. */
    void openMessage(MailMessage envelope) {
        if (envelope == null) {
            return;
        }
        final String accountId = envelope.getAccountId();
        load(() -> {
            MailService s = manager.session(accountId);
            MailMessage full = s.open(envelope);
            if (!full.isRead()) {
                s.setRead(full, true);
                full.setRead(true);
            }
            return full;
        }, full -> {
            selected = full;
            int idx = tableModel.indexOf(envelope);
            if (idx >= 0) {
                messages.set(idx, full);
                tableModel.setMessages(messages);
            }
            reader.setMessage(full);
        });
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    void newMessage() {
        if (currentAccount == null) {
            setStatus("Configure an account first.");
            return;
        }
        openCompose(composeFor(currentAccount, null, ComposeMode.NEW));
    }

    void reply() {
        composeSelected(ComposeMode.REPLY);
    }

    void replyAll() {
        composeSelected(ComposeMode.REPLY_ALL);
    }

    void forward() {
        composeSelected(ComposeMode.FORWARD);
    }

    private void composeSelected(ComposeMode mode) {
        if (selected == null) {
            setStatus("Select a message first.");
            return;
        }
        MailAccount a = currentAccount != null ? currentAccount
                : manager.accounts().findById(selected.getAccountId());
        openCompose(composeFor(a, selected, mode));
    }

    /**
     * Builds and prefills a {@link ComposePanel} without showing it, so the tests
     * can drive compose/send headlessly. Production callers wrap it in
     * {@link #openCompose}.
     */
    ComposePanel composeFor(MailAccount account, MailMessage src,
            ComposeMode mode) {
        ComposePanel panel = new ComposePanel(accounts, account);
        switch (mode) {
            case REPLY: panel.prefillReply(src, false); break;
            case REPLY_ALL: panel.prefillReply(src, true); break;
            case FORWARD: panel.prefillForward(src); break;
            default: panel.initNew(); break;
        }
        return panel;
    }

    private void openCompose(ComposePanel panel) {
        if (GraphicsEnvironment.isHeadless()) {
            pendingCompose = panel;      // tests drive sendDraft() directly
            return;
        }
        ComposeDialog d = new ComposeDialog(panel);
        d.setVisible(true);
    }

    private ComposePanel pendingCompose;

    ComposePanel getPendingCompose() {
        return pendingCompose;
    }

    /** Sends a draft on a worker thread and refreshes the Sent folder on success. */
    void sendDraft(MailMessage draft, List<MailAttachment> attachments) {
        final String accountId = draft.getAccountId();
        setStatus("Sending...");
        load(() -> {
            manager.session(accountId).send(draft, attachments);
            return null;
        }, v -> {
            setStatus("Message sent.");
            draft.setFolder(MailMessage.FOLDER_SENT);
            selectFolder(accountId, MailMessage.FOLDER_SENT);
        });
    }

    void deleteSelected() {
        if (selected == null) {
            return;
        }
        if (settings.isConfirmOnDelete() && !GraphicsEnvironment.isHeadless()) {
            int c = JOptionPane.showConfirmDialog(this,
                    "Delete this message?", "Delete",
                    JOptionPane.OK_CANCEL_OPTION);
            if (c != JOptionPane.OK_OPTION) {
                return;
            }
        }
        final MailMessage m = selected;
        load(() -> {
            manager.session(m.getAccountId()).delete(m);
            return null;
        }, v -> {
            messages.remove(m);
            selected = null;
            tableModel.setMessages(messages);
            reader.clear();
            setStatus("Message deleted.");
        });
    }

    void toggleRead() {
        if (selected == null) {
            return;
        }
        final MailMessage m = selected;
        final boolean to = !m.isRead();
        load(() -> {
            manager.session(m.getAccountId()).setRead(m, to);
            return null;
        }, v -> {
            m.setRead(to);
            tableModel.setMessages(messages);
        });
    }

    void toggleFlag() {
        if (selected == null) {
            return;
        }
        final MailMessage m = selected;
        final boolean to = !m.isFlagged();
        load(() -> {
            manager.session(m.getAccountId()).setFlagged(m, to);
            return null;
        }, v -> {
            m.setFlagged(to);
            tableModel.setMessages(messages);
        });
    }

    void doSearch() {
        final String terms = searchField.getText();
        final String accountId = currentAccount == null ? null
                : currentAccount.getId();
        final String folder = currentFolder;
        if (accountId == null) {
            return;
        }
        setStatus("Searching...");
        load(() -> manager.session(accountId).search(folder, terms), result -> {
            messages.clear();
            messages.addAll(result);
            selected = null;
            tableModel.setMessages(messages);
            reader.clear();
            setStatus(result.size() + " result(s).");
        });
    }

    private void openSettings() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JFrame frame = (JFrame) javax.swing.SwingUtilities.getWindowAncestor(this);
        boolean applied = MailSettingsDialog.show(frame, manager,
                manager.accounts(), manager.rules(), settings);
        if (applied) {
            settings = MailSettings.load();
            applySettings();
            startAutoCheck();
            refresh();
        }
    }

    private void autoCheck() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (currentAccount != null) {
                loadFolder();
            }
        });
    }

    /**
     * The tick the auto-check scheduler fires (from its daemon thread). Runs
     * the inbox reload inline when {@link #setSynchronous(boolean)} is set, so
     * a headless test can drive one poll deterministically; otherwise it
     * marshals onto the EDT.
     */
    void autoCheckTick() {
        if (synchronous) {
            if (currentAccount != null) {
                loadFolder();
            }
            return;
        }
        autoCheck();
    }

    private void saveAttachment(MailAttachment att) {
        if (selected == null || GraphicsEnvironment.isHeadless()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File(att.getFileName()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final MailMessage m = selected;
        final java.io.File dest = chooser.getSelectedFile();
        load(() -> manager.session(m.getAccountId()).openAttachment(m, att),
                bytes -> {
                    try {
                        java.nio.file.Files.write(dest.toPath(), bytes);
                        setStatus("Saved " + dest.getName());
                    } catch (java.io.IOException ex) {
                        setStatus("Could not save attachment: " + ex.getMessage());
                    }
                });
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private void showEmpty(boolean empty) {
        centerHolder.removeAll();
        if (empty) {
            centerHolder.add(emptyLabel, BorderLayout.CENTER);
        } else {
            centerHolder.add(listReaderSplit, BorderLayout.CENTER);
        }
        centerHolder.revalidate();
        centerHolder.repaint();
    }

    private void setStatus(String text) {
        statusLabel.setText(text == null ? " " : text);
    }

    /**
     * Runs {@code network} off the EDT and hands the result to {@code ui} on the
     * EDT - or, in {@link #setSynchronous(boolean) synchronous} mode, runs both
     * inline on the caller's thread. Backend failures are reported in the status
     * bar rather than thrown, so the panel degrades gracefully offline.
     */
    private <T> void load(Callable<T> network, Consumer<T> ui) {
        if (synchronous) {
            try {
                T r = network.call();
                if (ui != null) {
                    ui.accept(r);
                }
            } catch (Exception e) {
                reportError(e);
            }
            return;
        }
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return network.call();
            }

            @Override
            protected void done() {
                try {
                    T r = get();
                    if (ui != null) {
                        ui.accept(r);
                    }
                } catch (Exception e) {
                    reportError(e.getCause() == null ? e : e.getCause());
                }
            }
        }.execute();
    }

    private void reportError(Throwable t) {
        String msg = (t instanceof MailBackendException) ? t.getMessage()
                : "Mail error: " + t.getMessage();
        setStatus(msg == null ? "Mail error." : msg);
    }

    /** Disconnects every session and invokes the host close callback, if any. */
    public void close() {
        manager.close();
        if (onClose != null) {
            onClose.run();
        }
    }

    // ------------------------------------------------------------------
    // Test / inspection accessors (package-private)
    // ------------------------------------------------------------------

    List<MailAccount> getAccounts() {
        return accounts;
    }

    List<MailFolder> getFolders() {
        return folders;
    }

    List<MailMessage> getMessages() {
        return messages;
    }

    MailMessage getSelected() {
        return selected;
    }

    /** Selects a message in the model and opens it (bypasses the table view). */
    void selectMessage(MailMessage m) {
        int idx = tableModel.indexOf(m);
        if (idx >= 0) {
            table.setRowSelectionInterval(0, 0);
            int view = table.convertRowIndexToView(idx);
            if (view >= 0) {
                table.setRowSelectionInterval(view, view);
            }
        }
        openMessage(m);
    }

    String getFolder() {
        return currentFolder;
    }

    String getStatusText() {
        return statusLabel.getText();
    }

    /** Sets the search box and runs the search (test-friendly entry point). */
    void performSearch(String terms) {
        searchField.setText(terms);
        doSearch();
    }

    /** The message-list row height currently implied by the density setting. */
    int getTableRowHeight() {
        return table.getRowHeight();
    }

    MailSettings getSettings() {
        return settings;
    }

    // ------------------------------------------------------------------
    // Tree node payloads
    // ------------------------------------------------------------------

    private static final class AccountNode {
        final MailAccount account;
        AccountNode(MailAccount account) {
            this.account = account;
        }
        public String toString() {
            return account.getDisplayLabel();
        }
    }

    private static final class FolderNode {
        final String accountId;
        final MailFolder folder;
        FolderNode(String accountId, MailFolder folder) {
            this.accountId = accountId;
            this.folder = folder;
        }
        public String toString() {
            int n = folder.getUnreadCount();
            return n > 0 ? folder.getDisplayLabel() + " (" + n + ")"
                    : folder.getDisplayLabel();
        }
    }

    /** Renders the Date column as a formatted timestamp from its epoch value. */
    private final class DateRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(t, value, isSelected, hasFocus,
                    row, column);
            if (value instanceof Long) {
                setText(tableModel.formatDate((Long) value));
            }
            return this;
        }
    }

    /** A modal compose window wrapping a {@link ComposePanel} with Send/Cancel. */
    private final class ComposeDialog extends javax.swing.JDialog {
        ComposeDialog(ComposePanel panel) {
            super((JFrame) javax.swing.SwingUtilities.getWindowAncestor(MailPanel.this),
                    "New message", true);
            JPanel south = new JPanel(new java.awt.FlowLayout(
                    java.awt.FlowLayout.RIGHT));
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> dispose());
            JButton send = new JButton("Send");
            send.addActionListener(e -> {
                MailMessage draft = panel.buildDraft();
                List<MailAttachment> atts = panel.attachments();
                dispose();
                sendDraft(draft, atts);
            });
            south.add(cancel);
            south.add(send);
            getContentPane().add(panel, BorderLayout.CENTER);
            getContentPane().add(south, BorderLayout.SOUTH);
            setSize(720, 520);
            setLocationRelativeTo(MailPanel.this);
        }
    }
}
