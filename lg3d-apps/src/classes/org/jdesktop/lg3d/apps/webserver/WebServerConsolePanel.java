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
package org.jdesktop.lg3d.apps.webserver;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import org.jdesktop.lg3d.apps.webserver.dto.AppView;
import org.jdesktop.lg3d.apps.webserver.dto.DeployRequest;
import org.jdesktop.lg3d.apps.webserver.dto.HealthView;
import org.jdesktop.lg3d.apps.webserver.dto.LogsView;
import org.jdesktop.lg3d.apps.webserver.dto.MetricsView;
import org.jdesktop.lg3d.apps.webserver.dto.ServerInfoView;

/**
 * The Web Server Console: a plain Swing workspace that drives a running
 * {@code lg3d-webserver} over its management REST API and can fork a local one as
 * a child process.
 *
 * <p>This is a plain {@link JPanel} with a non-throwing no-argument constructor
 * and no Java 3D, so it is hosted two ways exactly like the Docker Manager and
 * Database Manager: in the 3D desktop the {@link WebServerConsole} wrapper puts it
 * on a {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow};
 * in the 2D/Swing desktop {@code Desktop2DAppRegistry} constructs
 * {@link WebServerPanel} reflectively, which wraps this panel in an MDI internal
 * frame. It touches no scene graph, so the 2D path never needs one.</p>
 *
 * <p>The console never blocks the event thread and never throws out of a public
 * method: every HTTP call runs on a background {@link ExecutorService} and lands
 * back on the EDT through {@link SwingUtilities#invokeLater}, and a server that
 * is down, a bootJar that is missing or a deploy that the server refuses all
 * degrade to a readable status line rather than a stack trace or a modal dialog
 * (which would escape the {@code SwingNode} capture). {@link #dispose()} and
 * {@link #refresh()} are safe to call at any time, including before the panel is
 * shown and after it is torn down.</p>
 */
public class WebServerConsolePanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 1024;
    public static final int HEIGHT_PX = 768;

    /** The columns of the deployed-application table. */
    private static final String[] APP_COLUMNS = {
        "Name", "Type", "State", "Port", "Context", "URL"
    };

    /** The deploy types the form offers, matching the server's DeploymentType. */
    private static final String[] DEPLOY_TYPES = {"DOCKER", "JAR"};

    /** The client the console drives; re-pointed by Connect / Start local. */
    private volatile ManagementClient client;
    private final ServerProcessSupervisor supervisor;
    private final ExecutorService executor;

    private final JTextField addressField = new JTextField(ManagementClient.defaultServer(), 24);
    private final JTextField portField = new JTextField(
            String.valueOf(ServerProcessSupervisor.DEFAULT_PORT), 5);
    private final JLabel statusLabel = new JLabel(" ");

    private final JTextArea overviewArea = new JTextArea();
    private final DefaultTableModel appModel = new DefaultTableModel(APP_COLUMNS, 0);
    private final JTable appTable = new JTable(appModel);
    private final List<AppView> apps = new ArrayList<>();

    private final JComboBox<String> deployType = new JComboBox<>(DEPLOY_TYPES);
    private final JTextField deployName = new JTextField(12);
    private final JTextField deploySource = new JTextField(20);
    private final JTextField deployContext = new JTextField(10);
    private final JTextField deployPort = new JTextField(5);

    private final JTextArea logArea = new JTextArea();
    private final JTabbedPane tabs = new JTabbedPane();

    /**
     * The status line's text, mirrored in a {@code volatile} field so a headless
     * test reads it deterministically: {@link #setStatus} is called on the EDT
     * (from {@link #applySnapshot}), and reading the {@code JLabel} across threads
     * would otherwise be a race. The label is still updated for the real UI.
     */
    private volatile String statusMessage = " ";

    private volatile boolean disposed;

    /** The production console: the default server address and a real supervisor. */
    public WebServerConsolePanel() {
        this(new ManagementClient(ManagementClient.defaultServer()),
                new ServerProcessSupervisor());
    }

    /**
     * A console whose local-server search is rooted at {@code projectRoot}, so a
     * desktop started from a known tree finds the built bootJar there. The server
     * address still defaults to {@link ManagementClient#defaultServer()}.
     */
    public WebServerConsolePanel(File projectRoot) {
        this(new ManagementClient(ManagementClient.defaultServer()),
                new ServerProcessSupervisor(
                        ServerProcessSupervisor.ancestorsOf(
                                projectRoot == null ? null : projectRoot.toPath()),
                        new ServerProcessSupervisor.ProcessSpawner()));
    }

    /** A console over injected collaborators; the test seam. */
    WebServerConsolePanel(ManagementClient client, ServerProcessSupervisor supervisor) {
        this(client, supervisor, defaultExecutor());
    }

    WebServerConsolePanel(ManagementClient client, ServerProcessSupervisor supervisor,
            ExecutorService executor) {
        super(new BorderLayout());
        this.client = client;
        this.supervisor = supervisor;
        this.executor = executor;

        setOpaque(true);
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildHeader(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        tabs.addTab("Overview", buildOverviewTab());
        tabs.addTab("Applications", buildApplicationsTab());
        tabs.addTab("Logs", buildLogsTab());

        refresh();
    }

    private static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "webserver-console");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildHeader() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        toolbar.add(new JLabel("Server:"));
        toolbar.add(addressField);
        toolbar.add(button("Connect", e -> connect()));
        toolbar.add(button("Start local", e -> startLocal()));
        toolbar.add(button("Stop", e -> stopLocal()));
        toolbar.add(button("Refresh", e -> refresh()));
        toolbar.add(new JLabel("Port:"));
        toolbar.add(portField);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));

        JPanel header = new JPanel(new BorderLayout());
        header.add(toolbar, BorderLayout.CENTER);
        header.add(statusLabel, BorderLayout.SOUTH);
        return header;
    }

    private Component buildOverviewTab() {
        overviewArea.setEditable(false);
        overviewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        overviewArea.setText("No server snapshot yet.");
        JScrollPane scroll = new JScrollPane(overviewArea);
        scroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return scroll;
    }

    private Component buildApplicationsTab() {
        JPanel panel = new JPanel(new BorderLayout());

        appTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        appTable.setFillsViewportHeight(true);
        panel.add(new JScrollPane(appTable), BorderLayout.CENTER);

        panel.add(buildDeployForm(), BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        actions.add(button("Start", e -> actOnSelected(this::startApp)));
        actions.add(button("Stop", e -> actOnSelected(this::stopApp)));
        actions.add(button("Undeploy", e -> actOnSelected(this::undeployApp)));
        actions.add(button("Logs", e -> actOnSelected(this::showLogs)));
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildDeployForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Deploy an application"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.gridy = 0;
        int col = 0;

        c.gridx = col++;
        form.add(new JLabel("Type:"), c);
        c.gridx = col++;
        form.add(deployType, c);

        c.gridx = col++;
        form.add(new JLabel("Name:"), c);
        c.gridx = col++;
        form.add(deployName, c);

        c.gridx = col++;
        form.add(new JLabel("Image / jar:"), c);
        c.gridx = col++;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        form.add(deploySource, c);
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0.0;

        c.gridx = col++;
        form.add(new JLabel("Context:"), c);
        c.gridx = col++;
        form.add(deployContext, c);

        c.gridx = col++;
        form.add(new JLabel("Port:"), c);
        c.gridx = col++;
        form.add(deployPort, c);

        c.gridx = col;
        form.add(button("Deploy", e -> deploy()), c);
        return form;
    }

    private Component buildLogsTab() {
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setText("Select an application and press Logs.");
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        return scroll;
    }

    private static JButton button(String label, java.awt.event.ActionListener action) {
        JButton button = new JButton(label);
        button.addActionListener(action);
        return button;
    }

    // ------------------------------------------------------------------
    // Actions (all head I/O off the EDT; none throws)
    // ------------------------------------------------------------------

    /** Re-points the console at the address field and re-reads it. */
    public void connect() {
        if (disposed) {
            return;
        }
        String address = addressField.getText();
        try {
            ManagementClient repointed = new ManagementClient(
                    ManagementClient.normalizeServer(address));
            replaceClient(repointed);
            setStatus("Connecting to " + repointed.server() + " ...");
            refresh();
        } catch (RuntimeException bad) {
            setStatus("Not connected: " + bad.getMessage());
        }
    }

    /** Forks a local server child on the port field, then reads it. */
    public void startLocal() {
        if (disposed) {
            return;
        }
        int port = parsePort(portField.getText(), ServerProcessSupervisor.DEFAULT_PORT);
        setStatus("Starting a local server on port " + port + " ...");
        runAsync(() -> {
            ServerProcessSupervisor.Result result = supervisor.start(port);
            SwingUtilities.invokeLater(() -> {
                setStatus(result.note());
                if (result.started() && result.url() != null) {
                    addressField.setText(result.url());
                    replaceClient(new ManagementClient(result.url()));
                }
            });
            if (result.started()) {
                // Give the forked JVM a moment to bind, then read it.
                refresh();
            }
        });
    }

    /** Stops a server child this console forked, then re-reads. */
    public void stopLocal() {
        if (disposed) {
            return;
        }
        setStatus("Stopping the local server ...");
        runAsync(() -> {
            supervisor.stop();
            SwingUtilities.invokeLater(() -> setStatus("Local server stopped."));
            refresh();
        });
    }

    /** Re-reads health, info and the application list from the server. */
    public void refresh() {
        if (disposed) {
            return;
        }
        runAsync(() -> {
            HealthView health = null;
            ServerInfoView info = null;
            List<AppView> list = List.of();
            String error = null;
            try {
                health = client.health();
                info = client.info();
                list = client.listApps();
            } catch (IOException unreachable) {
                error = unreachable.getMessage();
            } catch (RuntimeException unexpected) {
                error = unexpected.getMessage();
            }
            final HealthView h = health;
            final ServerInfoView i = info;
            final List<AppView> a = list;
            final String err = error;
            SwingUtilities.invokeLater(() -> applySnapshot(h, i, a, err));
        });
    }

    private void deploy() {
        if (disposed) {
            return;
        }
        String type = (String) deployType.getSelectedItem();
        String name = blankToNull(deployName.getText());
        String source = blankToNull(deploySource.getText());
        String context = blankToNull(deployContext.getText());
        Integer port = parsePortOrNull(deployPort.getText());
        if (source == null) {
            setStatus("Deploy needs an image (DOCKER) or a jar path (JAR).");
            return;
        }
        DeployRequest request = DeployRequest.TYPE_JAR.equals(type)
                ? DeployRequest.jar(name, source, context, port)
                : DeployRequest.docker(name, source, context, port);
        setStatus("Deploying " + source + " ...");
        runAsync(() -> {
            try {
                AppView created = client.deploy(request);
                SwingUtilities.invokeLater(() -> setStatus(
                        "Deployed " + created.name() + " -> " + created.url()
                        + " (" + created.state() + ")"));
            } catch (IOException failed) {
                SwingUtilities.invokeLater(() -> setStatus("Deploy failed: " + failed.getMessage()));
            }
            refreshNow();
        });
    }

    private void startApp(String id) {
        lifecycle(id, true);
    }

    private void stopApp(String id) {
        lifecycle(id, false);
    }

    private void lifecycle(String id, boolean start) {
        if (disposed) {
            return;
        }
        setStatus((start ? "Starting " : "Stopping ") + id + " ...");
        runAsync(() -> {
            try {
                AppView updated = start ? client.start(id) : client.stop(id);
                SwingUtilities.invokeLater(() -> setStatus(
                        updated.name() + " is " + updated.state()));
            } catch (IOException failed) {
                SwingUtilities.invokeLater(() -> setStatus(
                        (start ? "Start" : "Stop") + " failed: " + failed.getMessage()));
            }
            refreshNow();
        });
    }

    private void undeployApp(String id) {
        if (disposed) {
            return;
        }
        setStatus("Undeploying " + id + " ...");
        runAsync(() -> {
            try {
                client.undeploy(id);
                SwingUtilities.invokeLater(() -> setStatus("Undeployed " + id));
            } catch (IOException failed) {
                SwingUtilities.invokeLater(() -> setStatus(
                        "Undeploy failed: " + failed.getMessage()));
            }
            refreshNow();
        });
    }

    private void showLogs(String id) {
        if (disposed) {
            return;
        }
        tabs.setSelectedIndex(2);
        logArea.setText("Loading logs for " + id + " ...");
        runAsync(() -> {
            try {
                LogsView logs = client.logs(id, 200);
                SwingUtilities.invokeLater(() -> logArea.setText(logs.logsOrEmpty()));
            } catch (IOException failed) {
                SwingUtilities.invokeLater(() -> logArea.setText(
                        "Could not read logs: " + failed.getMessage()));
            }
        });
    }

    /** Runs an action against the selected application id, if any. */
    private void actOnSelected(java.util.function.Consumer<String> action) {
        int row = appTable.getSelectedRow();
        if (row < 0 || row >= apps.size()) {
            setStatus("Select an application first.");
            return;
        }
        action.accept(apps.get(row).id());
    }

    // ------------------------------------------------------------------
    // EDT updates
    // ------------------------------------------------------------------

    private void applySnapshot(HealthView health, ServerInfoView info, List<AppView> list,
            String error) {
        if (disposed) {
            return;
        }
        if (health == null) {
            setStatus("Server down at " + client.server()
                    + (error == null ? "" : " - " + error));
            overviewArea.setText("The server at " + client.server() + " is not answering.\n\n"
                    + (error == null ? "" : error + "\n\n")
                    + "Start a local server with the toolbar, or point the address\n"
                    + "field at one that is already running.");
            replaceApps(List.of());
            return;
        }
        setStatus("Server " + health.status() + " at " + client.server()
                + " - docker " + (health.dockerAvailable() ? "available" : "unavailable")
                + ", jar " + (health.jarAvailable() ? "available" : "unavailable")
                + ", " + health.running() + "/" + health.deployments() + " running");
        overviewArea.setText(renderOverview(info, health));
        replaceApps(list);
    }

    private void replaceApps(List<AppView> list) {
        apps.clear();
        if (list != null) {
            apps.addAll(list);
        }
        appModel.setRowCount(0);
        for (AppView app : apps) {
            appModel.addRow(new Object[] {
                app.name(), app.type(), app.state(), app.port(), app.contextPath(), app.url()
            });
        }
    }

    private static String renderOverview(ServerInfoView info, HealthView health) {
        StringBuilder sb = new StringBuilder();
        sb.append("Server\n");
        sb.append("  address : ").append("see toolbar\n");
        if (info != null) {
            sb.append("  version : ").append(info.version()).append('\n');
            sb.append("  port    : ").append(info.port()).append('\n');
            sb.append("  java    : ").append(info.javaVersion()).append('\n');
            MetricsView m = info.metrics();
            if (m != null) {
                sb.append("\nMetrics\n");
                sb.append("  heap    : ").append(megabytes(m.heapUsedBytes()))
                        .append(" / ").append(megabytes(m.heapMaxBytes())).append(" MB\n");
                sb.append("  threads : ").append(m.threadCount())
                        .append(" (peak ").append(m.peakThreadCount()).append(")\n");
                sb.append("  cores   : ").append(m.availableProcessors()).append('\n');
                sb.append("  load    : ").append(String.format("%.2f", m.systemLoadAverage())).append('\n');
                sb.append("  uptime  : ").append(m.uptimeMillis() / 1000L).append(" s\n");
            }
        } else {
            sb.append("  (info unavailable)\n");
        }
        sb.append("\nBackends\n");
        sb.append("  docker  : ").append(health.dockerAvailable() ? "available" : "unavailable").append('\n');
        sb.append("  jar     : ").append(health.jarAvailable() ? "available" : "unavailable").append('\n');
        return sb.toString();
    }

    private static long megabytes(long bytes) {
        return bytes / (1024L * 1024L);
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /**
     * Re-points the console at a rebuilt client. The field is {@code volatile} so
     * a Connect / Start-local on the EDT is seen by the next background read.
     */
    private void replaceClient(ManagementClient replacement) {
        this.client = replacement;
    }

    private void setStatus(String text) {
        statusMessage = text;
        if (!disposed) {
            statusLabel.setText(text);
        }
    }

    /** Runs a task on the background executor; never lets an exception escape. */
    private void runAsync(Runnable task) {
        if (disposed || executor.isShutdown()) {
            return;
        }
        try {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (RuntimeException | Error escaped) {
                    SwingUtilities.invokeLater(() -> setStatus("Error: " + escaped.getMessage()));
                }
            });
        } catch (RuntimeException rejected) {
            // The executor was shut down between the guard and the submit.
        }
    }

    /** A synchronous-ish refresh used after a mutation (still off the EDT). */
    private void refreshNow() {
        refresh();
    }

    private static int parsePort(String text, int fallback) {
        Integer parsed = parsePortOrNull(text);
        return parsed == null ? fallback : parsed;
    }

    private static Integer parsePortOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String blankToNull(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }

    // ------------------------------------------------------------------
    // Teardown + test hooks
    // ------------------------------------------------------------------

    /**
     * Releases the panel: stops a server child this console forked and shuts the
     * background executor down. Idempotent and never throws, so the host window can
     * call it unconditionally on close.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            supervisor.stop();
        } catch (RuntimeException ignored) {
            // Tearing down must never escalate.
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether {@link #dispose()} has run. Package-visible for the headless test. */
    boolean isDisposed() {
        return disposed;
    }

    /** The current status-line text. Package-visible for the headless test. */
    String getStatusText() {
        return statusMessage;
    }

    /** How many tabs the console lays out. Package-visible for the headless test. */
    int getTabCount() {
        return tabs.getTabCount();
    }

    /** The server address this console is pointed at. Package-visible for tests. */
    String serverAddress() {
        return client.server();
    }
}
