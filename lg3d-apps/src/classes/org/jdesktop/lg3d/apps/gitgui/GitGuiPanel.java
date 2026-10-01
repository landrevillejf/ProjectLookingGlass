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
package org.jdesktop.lg3d.apps.gitgui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * A plain-Swing Git client - the desktop face of the Git GUI, styled after
 * GitKraken / GitHub Desktop: a Changes list with Stage / Unstage, a commit
 * message box, a Branches list with Checkout / New Branch, a commit History
 * list, and a diff viewer.
 *
 * <p>The panel touches no Java&nbsp;3D and starts no process until the user acts:
 * every git operation is delegated to the AWT-free {@link GitRepository} (which
 * shells out to the system {@code git}) on a daemon worker thread, and the result
 * is applied back on the EDT. When git is missing, or the folder is not a
 * repository, the status line says so honestly instead of faking a state.</p>
 *
 * <p>It is registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@link GitGui} main class, so the one shared start-menu descriptor opens this
 * panel as an MDI internal frame in the 2D/Swing desktop, while the 3D desktop
 * hosts the very same instance on a {@code SwingNode} through {@link GitGui} /
 * {@code TitledSwingWindow}. Standard layout managers are used throughout and no
 * widget here is a Synth combo box, so it paints correctly offscreen too.</p>
 */
public class GitGuiPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 1000;
    public static final int HEIGHT_PX = 680;

    private static final Color DIFF_BACKDROP = new Color(0x2b, 0x2b, 0x2b);

    /** The backend, or null until a folder is opened. */
    private GitRepository repository;

    /**
     * When true, git work runs inline on the calling thread instead of a daemon
     * worker + {@code invokeLater}. Package-private seam so headless tests drive
     * the whole open / stage / commit / checkout / diff flow deterministically.
     */
    private final boolean synchronous;

    // Observable state, mirrored in plain fields so test hooks never read a
    // value that is still pending on the EDT.
    private volatile String statusMessage = "Open a repository to begin.";
    private volatile String currentBranch = "";
    private volatile String diffMessage = "";
    private volatile boolean repositoryValid;

    /**
     * A one-shot action outcome ("Staged 2 file(s)", "Push failed: ...") that the
     * next reload prefixes onto the repository summary, so the feedback survives
     * the refresh instead of being clobbered by it.
     */
    private volatile String actionMessage = "";

    private final DefaultListModel<GitChange> changeModel = new DefaultListModel<>();
    private final DefaultListModel<GitBranch> branchModel = new DefaultListModel<>();
    private final DefaultListModel<GitCommit> historyModel = new DefaultListModel<>();

    private final JList<GitChange> changeList = new JList<>(changeModel);
    private final JList<GitBranch> branchList = new JList<>(branchModel);
    private final JList<GitCommit> historyList = new JList<>(historyModel);

    private final JButton openButton = new JButton("Open...");
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton initButton = new JButton("Init");
    private final JButton fetchButton = new JButton("Fetch");
    private final JButton pullButton = new JButton("Pull");
    private final JButton pushButton = new JButton("Push");

    private final JButton stageButton = new JButton("Stage");
    private final JButton unstageButton = new JButton("Unstage");
    private final JButton checkoutButton = new JButton("Checkout");
    private final JButton newBranchButton = new JButton("New Branch");
    private final JButton commitButton = new JButton("Commit");

    private final JLabel branchLabel = new JLabel("no repository");
    private final JTextArea commitMessage = new JTextArea(4, 24);
    private final JTextArea diffArea = new JTextArea();
    private final JLabel statusLabel = new JLabel(" ");

    /** Builds an empty panel; nothing runs until a folder is opened. */
    public GitGuiPanel() {
        this(null, false);
    }

    /**
     * Test seam: builds a panel already bound to {@code repository}, running git
     * work inline when {@code synchronous} is set.
     */
    GitGuiPanel(GitRepository repository, boolean synchronous) {
        super(new BorderLayout());
        this.synchronous = synchronous;
        this.repository = repository;
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        commitMessage.setLineWrap(true);
        commitMessage.setWrapStyleWord(true);
        commitMessage.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        commitMessage.setToolTipText("Commit message (the first line is the subject)");

        diffArea.setEditable(false);
        diffArea.setBackground(DIFF_BACKDROP);
        diffArea.setForeground(new Color(0xd4, 0xd4, 0xd4));
        diffArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        changeList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        branchList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        changeList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedChangeDiff();
            }
        });
        historyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedCommitDiff();
            }
        });

        syncStatus();
        updateEnabledState();
        if (repository != null) {
            reload();
        }
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        openButton.addActionListener(e -> chooseRepository());
        bar.add(openButton);
        refreshButton.addActionListener(e -> reload());
        bar.add(refreshButton);
        initButton.addActionListener(e -> doInit());
        bar.add(initButton);
        bar.addSeparator();

        fetchButton.addActionListener(e -> doFetch());
        bar.add(fetchButton);
        pullButton.addActionListener(e -> doPull());
        bar.add(pullButton);
        pushButton.addActionListener(e -> doPush());
        bar.add(pushButton);
        bar.addSeparator();
        bar.add(new JLabel(" Branch: "));
        bar.add(branchLabel);
        return bar;
    }

    private JPanel buildBody() {
        JSplitPane body = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildLeftColumn(), buildRightColumn());
        body.setResizeWeight(0.45);
        body.setBorder(BorderFactory.createEmptyBorder());
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(body, BorderLayout.CENTER);
        return holder;
    }

    private JPanel buildLeftColumn() {
        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildChangesPanel(), buildCommitPanel());
        left.setResizeWeight(0.6);
        left.setBorder(BorderFactory.createEmptyBorder());
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(left, BorderLayout.CENTER);
        return holder;
    }

    private JPanel buildRightColumn() {
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildBranchesPanel(), buildHistoryAndDiff());
        right.setResizeWeight(0.35);
        right.setBorder(BorderFactory.createEmptyBorder());
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(right, BorderLayout.CENTER);
        return holder;
    }

    private JPanel buildChangesPanel() {
        JPanel buttons = new JPanel();
        stageButton.addActionListener(e -> stageSelected());
        unstageButton.addActionListener(e -> unstageSelected());
        buttons.add(stageButton);
        buttons.add(unstageButton);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Changes"));
        panel.add(new JScrollPane(changeList), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildCommitPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Commit"));
        panel.add(new JScrollPane(commitMessage), BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        commitButton.addActionListener(e -> commit());
        south.add(commitButton, BorderLayout.EAST);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildBranchesPanel() {
        JPanel buttons = new JPanel();
        checkoutButton.addActionListener(e -> checkoutSelected());
        newBranchButton.addActionListener(e -> promptNewBranch());
        buttons.add(checkoutButton);
        buttons.add(newBranchButton);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Branches"));
        panel.add(new JScrollPane(branchList), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildHistoryAndDiff() {
        JPanel history = new JPanel(new BorderLayout());
        history.setBorder(BorderFactory.createTitledBorder("History"));
        history.add(new JScrollPane(historyList), BorderLayout.CENTER);

        JPanel diff = new JPanel(new BorderLayout());
        diff.setBorder(BorderFactory.createTitledBorder("Diff"));
        diff.add(new JScrollPane(diffArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, history, diff);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(split, BorderLayout.CENTER);
        return holder;
    }

    // ------------------------------------------------------------------
    // Repository lifecycle
    // ------------------------------------------------------------------

    /** Pops a directory chooser and opens the selected folder, if approved. */
    private void chooseRepository() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Open a Git repository");
        if (repository != null && repository.getDirectory() != null) {
            chooser.setCurrentDirectory(repository.getDirectory());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openRepository(chooser.getSelectedFile());
        }
    }

    /** Opens {@code dir} with the real git backend and refreshes. */
    public void openRepository(File dir) {
        if (dir == null) {
            return;
        }
        loadRepository(new GitRepository(dir));
    }

    /** Binds the panel to {@code repo} and refreshes (test seam accepts a fake). */
    public void loadRepository(GitRepository repo) {
        this.repository = repo;
        reload();
    }

    /** Refreshes all views from the current repository on a worker thread. */
    public void reload() {
        final GitRepository repo = this.repository;
        if (repo == null) {
            applySnapshot(null);
            return;
        }
        background(() -> collect(repo), this::applySnapshot);
    }

    /** The immutable view of a repository gathered in one worker pass. */
    private record Snapshot(boolean valid, String branch, String remote,
                            List<GitChange> changes, List<GitBranch> branches,
                            List<GitCommit> commits, String notice) {
    }

    private Snapshot collect(GitRepository repo) {
        if (!repo.isAvailable()) {
            return new Snapshot(false, "", "", List.of(), List.of(), List.of(),
                    "git was not found on PATH - install git to use this tool.");
        }
        if (!repo.isRepository()) {
            return new Snapshot(false, "", "", List.of(), List.of(), List.of(),
                    "Not a git repository. Use Init to create one here.");
        }
        GitParsers.Status status = repo.status();
        String branch = status.branch().isEmpty() ? repo.currentBranch() : status.branch();
        return new Snapshot(true, branch, repo.remoteUrl(), status.changes(),
                repo.branches(), repo.log(), null);
    }

    /** Applies a gathered snapshot to the models and readouts (EDT). */
    void applySnapshot(Snapshot snap) {
        changeModel.clear();
        branchModel.clear();
        historyModel.clear();

        if (snap == null || !snap.valid()) {
            repositoryValid = false;
            currentBranch = "";
            branchLabel.setText("no repository");
            String notice = (snap == null)
                    ? "Open a repository to begin." : snap.notice();
            setStatus(withAction(notice));
            actionMessage = "";
            updateEnabledState();
            return;
        }

        repositoryValid = true;
        currentBranch = snap.branch();
        for (GitChange c : snap.changes()) {
            changeModel.addElement(c);
        }
        for (GitBranch b : snap.branches()) {
            branchModel.addElement(b);
        }
        for (GitCommit h : snap.commits()) {
            historyModel.addElement(h);
        }
        branchLabel.setText(currentBranch.isEmpty() ? "(detached)" : currentBranch);

        String where = repository == null ? "" : repository.getDirectory().getName();
        int staged = countStaged(snap.changes());
        setStatus(withAction(where + " - " + (currentBranch.isEmpty() ? "(detached)" : currentBranch)
                + " - " + snap.changes().size() + " change(s), " + staged + " staged"
                + (snap.remote().isBlank() ? "" : " - origin: " + snap.remote())));
        actionMessage = "";
        updateEnabledState();
    }

    /** Prefixes the pending one-shot action message onto a status line. */
    private String withAction(String summary) {
        return actionMessage.isEmpty() ? summary : actionMessage + "  -  " + summary;
    }

    private static int countStaged(List<GitChange> changes) {
        int n = 0;
        for (GitChange c : changes) {
            if (c.isStaged()) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Stages the selected changes. */
    public void stageSelected() {
        List<String> paths = selectedChangePaths();
        if (paths.isEmpty() || repository == null) {
            return;
        }
        runAndReload(() -> repository.stage(paths), "Staged " + paths.size() + " file(s)");
    }

    /** Unstages the selected changes. */
    public void unstageSelected() {
        List<String> paths = selectedChangePaths();
        if (paths.isEmpty() || repository == null) {
            return;
        }
        runAndReload(() -> repository.unstage(paths), "Unstaged " + paths.size() + " file(s)");
    }

    /** Commits the staged index with the message box contents. */
    public void commit() {
        if (repository == null) {
            return;
        }
        final String message = commitMessage.getText();
        if (message.isBlank()) {
            setStatus("Enter a commit message first.");
            return;
        }
        background(() -> repository.commit(message), result -> {
            applyActionResult(result, "Commit");
            if (result.isSuccess()) {
                commitMessage.setText("");
            }
            reload();
        });
    }

    /** Checks out the selected branch. */
    public void checkoutSelected() {
        GitBranch branch = branchList.getSelectedValue();
        if (branch == null || repository == null) {
            return;
        }
        doCheckout(branch.name());
    }

    /** Switches to {@code branch}. */
    public void doCheckout(String branch) {
        if (repository == null || branch == null || branch.isBlank()) {
            return;
        }
        runAndReload(() -> repository.checkout(branch), "Checked out " + branch);
    }

    /** Prompts for a new branch name and creates it. */
    private void promptNewBranch() {
        if (repository == null) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "New branch name:",
                "Create Branch", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.isBlank()) {
            doCreateBranch(name.trim());
        }
    }

    /** Creates and checks out a new branch {@code name}. */
    public void doCreateBranch(String name) {
        if (repository == null || name == null || name.isBlank()) {
            return;
        }
        runAndReload(() -> repository.createBranch(name), "Created branch " + name);
    }

    /** Runs {@code git init} in the currently opened folder. */
    public void doInit() {
        if (repository == null) {
            setStatus("Open a folder first, then Init to create a repository.");
            return;
        }
        runAndReload(() -> repository.init(), "Initialised an empty repository");
    }

    /** Refreshes remote-tracking refs. */
    public void doFetch() {
        runAndReload(() -> repository.fetch(), "Fetch");
    }

    /** Fast-forwards the current branch from its tracking remote. */
    public void doPull() {
        runAndReload(() -> repository.pull(), "Pull");
    }

    /** Publishes the current branch to its remote. */
    public void doPush() {
        runAndReload(() -> repository.push(), "Push");
    }

    /** Runs a mutating git call, reports it, then reloads the views. */
    private void runAndReload(Callable<GitResult> task, String successPrefix) {
        if (repository == null) {
            return;
        }
        background(task, result -> {
            applyActionResult(result, successPrefix);
            reload();
        });
    }

    /** Records a git result as the pending action message (EDT). */
    void applyActionResult(GitResult result, String successPrefix) {
        if (result == null) {
            actionMessage = successPrefix + " failed.";
            return;
        }
        if (result.isSuccess()) {
            String detail = result.getMessage();
            actionMessage = successPrefix
                    + (detail.equals("OK") ? " succeeded." : ": " + detail);
        } else {
            actionMessage = successPrefix + " failed: " + result.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // Diff views
    // ------------------------------------------------------------------

    private void showSelectedChangeDiff() {
        GitChange change = changeList.getSelectedValue();
        if (change == null || repository == null) {
            return;
        }
        if (change.isUntracked()) {
            applyDiff("(untracked file - stage it to see a diff)\n\n" + change.path());
            return;
        }
        final String path = change.path();
        final boolean staged = change.isStaged();
        background(() -> repository.diff(path, staged), this::applyDiff);
    }

    private void showSelectedCommitDiff() {
        GitCommit commit = historyList.getSelectedValue();
        if (commit == null || repository == null) {
            return;
        }
        final String hash = commit.hash();
        background(() -> repository.show(hash), this::applyDiff);
    }

    /** Sets the diff viewer text (EDT), mirroring it for the test hook. */
    void applyDiff(String text) {
        diffMessage = (text == null) ? "" : text;
        diffArea.setText(diffMessage);
        diffArea.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private List<String> selectedChangePaths() {
        List<String> paths = new ArrayList<>();
        for (GitChange c : changeList.getSelectedValuesList()) {
            paths.add(c.path());
        }
        return paths;
    }

    private void setStatus(String message) {
        statusMessage = (message == null) ? "" : message;
        syncStatus();
    }

    /** Pushes the mirrored status message into the label. */
    private void syncStatus() {
        statusLabel.setText(statusMessage);
    }

    private void updateEnabledState() {
        boolean open = repository != null;
        refreshButton.setEnabled(open);
        initButton.setEnabled(open);
        fetchButton.setEnabled(repositoryValid);
        pullButton.setEnabled(repositoryValid);
        pushButton.setEnabled(repositoryValid);
        stageButton.setEnabled(repositoryValid);
        unstageButton.setEnabled(repositoryValid);
        checkoutButton.setEnabled(repositoryValid);
        newBranchButton.setEnabled(repositoryValid);
        commitButton.setEnabled(repositoryValid);
        changeList.setEnabled(repositoryValid);
        branchList.setEnabled(repositoryValid);
        historyList.setEnabled(repositoryValid);
    }

    /**
     * Runs {@code task} and hands its value to {@code onEdt}. In the normal
     * (asynchronous) panel the work runs on a daemon thread and the result is
     * applied via {@code invokeLater}; in the synchronous test panel both happen
     * inline so a headless test observes the outcome deterministically.
     */
    private <T> void background(Callable<T> task, Consumer<T> onEdt) {
        if (synchronous) {
            onEdt.accept(safeCall(task));
            return;
        }
        Thread worker = new Thread(() -> {
            final T value = safeCall(task);
            SwingUtilities.invokeLater(() -> onEdt.accept(value));
        }, "gitgui-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static <T> T safeCall(Callable<T> task) {
        try {
            return task.call();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Test / inspection accessors (package-private; the desktop does not use them)
    // ------------------------------------------------------------------

    String statusText() {
        return statusMessage;
    }

    String diffText() {
        return diffMessage;
    }

    String branchText() {
        return currentBranch;
    }

    boolean isRepositoryValid() {
        return repositoryValid;
    }

    GitRepository repository() {
        return repository;
    }

    int changeCount() {
        return changeModel.size();
    }

    int branchCount() {
        return branchModel.size();
    }

    int historyCount() {
        return historyModel.size();
    }

    List<GitChange> changes() {
        List<GitChange> all = new ArrayList<>();
        for (int i = 0; i < changeModel.size(); i++) {
            all.add(changeModel.get(i));
        }
        return all;
    }

    String commitMessageText() {
        return commitMessage.getText();
    }

    void setCommitMessage(String text) {
        commitMessage.setText(text);
    }

    void selectChange(int index) {
        changeList.setSelectedIndex(index);
    }

    void selectBranch(int index) {
        branchList.setSelectedIndex(index);
    }

    void selectCommit(int index) {
        historyList.setSelectedIndex(index);
    }
}
