package org.jdesktop.lg3d.apps.dockermanager;

import com.protonmail.landrevillejf.IconManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DockerManagementPanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(DockerManagementPanel.class);
    // UI Components
    private JTabbedPane tabbedPane;
    private JTree fileTree;
    private DefaultTreeModel treeModel;
    private JTextArea dockerfileEditor;
    private JTextArea composeEditor;
    private JTable containerTable;
    private ContainerTableModel containerTableModel;
    private JTable imageTable;
    private ImageTableModel imageTableModel;
    private JTextArea terminalOutput;
    private JComboBox<String> dockerCommandCombo;
    private JTextField commandField;
    JButton startDockerBtn;
    JButton stopDockerBtn;
    JButton composeUpBtn;
    JButton composeDownBtn;
    JButton aboutButton;

    // Data
    private File currentDirectory;
    private Map<String, String> dockerTemplates;
    private Map<String, String> composeTemplates;
    private ExecutorService executorService;
    private Process currentProcess;

    private File projectRoot;

    /** No-arg constructor for reflective hosting (2D desktop MDI frame). */
    public DockerManagementPanel() {
        this(null);
    }

    /**
     * @param projectRoot directory to browse for Docker files, or {@code null}
     *                    to fall back to the current working directory.
     */
    public DockerManagementPanel(File projectRoot) {
        super(new BorderLayout());
        this.projectRoot = projectRoot;
        this.currentDirectory = projectRoot != null ? projectRoot : new File(System.getProperty("user.dir"));
        executorService = Executors.newCachedThreadPool();
        initializeTemplates();
        initializeCurrentDirectory();
        initializeUI();
        checkInitialDockerStatus();
    }

    private void checkInitialDockerStatus() {
        // Juste vérifier l'état actuel de Docker sans le démarrer
        executorService.submit(() -> {
            try {
                Process process = Runtime.getRuntime().exec("docker version");
                boolean finished = process.waitFor(3, TimeUnit.SECONDS);

                if (finished && process.exitValue() == 0) {
                    // Docker est déjà en cours d'exécution
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    StringBuilder versionInfo = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("Version:")) {
                            versionInfo.append(line).append("\n");
                        }
                    }

                    final String version = versionInfo.toString().trim();

                    SwingUtilities.invokeLater(() -> {
                        terminalOutput.append(">>> Docker is already running\n");
                        if (!version.isEmpty()) {
                            terminalOutput.append(version + "\n");
                        }
                        terminalOutput.append("Ready to use Docker commands\n\n");

                        updateDockerStatusInStatusBar(true, version);
                        updateAllDockerButtons(true);
                    });
                } else {
                    // Docker n'est pas en cours d'exécution
                    SwingUtilities.invokeLater(() -> {
                        terminalOutput.append(">>> Docker is not running\n");
                        terminalOutput.append("Click 'Start Docker' to start Docker daemon\n\n");

                        updateDockerStatusInStatusBar(false, null);
                        updateAllDockerButtons(false);
                    });
                }
            } catch (Exception e) {
                // Docker n'est pas installé ou inaccessible
                SwingUtilities.invokeLater(() -> {
                    terminalOutput.append(">>> Cannot connect to Docker\n");
                    terminalOutput.append("Make sure Docker is installed and click 'Start Docker'\n\n");

                    updateDockerStatusInStatusBar(false, null);
                    updateAllDockerButtons(false);
                });
            }
        });
    }

    private void initializeCurrentDirectory() {
        if (projectRoot != null) {
            currentDirectory = projectRoot;
            log.info("Initialized current directory to project path: {}", currentDirectory.getAbsolutePath());
        } else {
            log.error("No project provided to DockerManagementPanel");
            // Fallback: utiliser le répertoire de travail courant
            currentDirectory = new File(System.getProperty("user.dir"));
            log.info("Falling back to current working directory: {}", currentDirectory.getAbsolutePath());
        }
    }

    private void initializeTemplates() {
        // Dockerfile Templates
        dockerTemplates = new LinkedHashMap<>();
        dockerTemplates.put("Node.js", """
            # Use official Node.js image
            FROM node:18-alpine
            
            # Set working directory
            WORKDIR /app
            
            # Copy package files
            COPY package*.json ./
            
            # Install dependencies
            RUN npm ci --only=production
            
            # Copy application source
            COPY . .
            
            # Expose port
            EXPOSE 3000
            
            # Start application
            CMD ["node", "index.js"]
            """);

        dockerTemplates.put("Python", """
            # Use official Python image
            FROM python:3.11-slim
            
            # Set working directory
            WORKDIR /app
            
            # Copy requirements file
            COPY requirements.txt .
            
            # Install dependencies
            RUN pip install --no-cache-dir -r requirements.txt
            
            # Copy application source
            COPY . .
            
            # Expose port
            EXPOSE 8000
            
            # Start application
            CMD ["python", "app.py"]
            """);

        dockerTemplates.put("Java Spring Boot", """
            # Use official OpenJDK image
            FROM openjdk:17-jdk-slim
            
            # Set working directory
            WORKDIR /app
            
            # Copy the jar file
            COPY target/*.jar app.jar
            
            # Expose port
            EXPOSE 8080
            
            # Start application
            ENTRYPOINT ["java", "-jar", "app.jar"]
            """);

        dockerTemplates.put("Nginx", """
            # Use official Nginx image
            FROM nginx:alpine
            
            # Copy custom configuration
            COPY nginx.conf /etc/nginx/nginx.conf
            
            # Copy static files
            COPY static/ /usr/share/nginx/html/
            
            # Expose port
            EXPOSE 80 443
            
            # Start Nginx
            CMD ["nginx", "-g", "daemon off;"]
            """);

        // Docker Compose Templates
        composeTemplates = new LinkedHashMap<>();
        composeTemplates.put("Web App + Database", """
            version: '3.8'
            
            services:
              web:
                build: .
                ports:
                  - "3000:3000"
                environment:
                  - NODE_ENV=production
                  - DATABASE_URL=postgres://user:pass@db:5432/mydb
                depends_on:
                  - db
                volumes:
                  - ./app:/app
                  - /app/node_modules
                networks:
                  - app-network
            
              db:
                image: postgres:15-alpine
                environment:
                  - POSTGRES_USER=user
                  - POSTGRES_PASSWORD=pass
                  - POSTGRES_DB=mydb
                volumes:
                  - postgres_data:/var/lib/postgresql/data
                ports:
                  - "5432:5432"
                networks:
                  - app-network
            
            volumes:
              postgres_data:
            
            networks:
              app-network:
                driver: bridge
            """);

        composeTemplates.put("Microservices", """
            version: '3.8'
            
            services:
              api-gateway:
                build: ./api-gateway
                ports:
                  - "8080:8080"
                environment:
                  - SERVICE_REGISTRY_URL=http://service-registry:8761
                depends_on:
                  - service-registry
                  - user-service
                  - product-service
                networks:
                  - microservices-network
            
              service-registry:
                image: eureka-server:latest
                ports:
                  - "8761:8761"
                networks:
                  - microservices-network
            
              user-service:
                build: ./user-service
                environment:
                  - EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://service-registry:8761/eureka/
                networks:
                  - microservices-network
            
              product-service:
                build: ./product-service
                environment:
                  - EUREKA_CLIENT_SERVICEURL_DEFAULTZONE=http://service-registry:8761/eureka/
                networks:
                  - microservices-network
            
            networks:
              microservices-network:
                driver: bridge
            """);
    }

    private void initializeUI() {
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Create toolbar
        JToolBar toolBar = createToolBar();
        add(toolBar, BorderLayout.NORTH);

        // Create main tabbed pane
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Terminal", IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "Host", 16, 16), createTerminalPanel());
        tabbedPane.addTab("Containers", IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "Server", 16, 16), createContainersPanel());
        tabbedPane.addTab("Files", IconManager.loadIcon(IconManager.IconCategory.GENERAL, "Open", 16, 16), createFilePanel());
//        tabbedPane.addTab("Dockerfile", IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "Application", 16, 16), createDockerfilePanel());
//        tabbedPane.addTab("Compose", IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "Applet", 16, 16), createComposePanel());
        tabbedPane.addTab("Images", IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "WebComponent", 16, 16), createImagesPanel());

        add(tabbedPane, BorderLayout.CENTER);

        // Status bar
        add(createStatusBar(), BorderLayout.SOUTH);

        // Dans initializeUI(), après avoir créé les panneaux
        SwingUtilities.invokeLater(() -> {
            loadFileTree();
            setupContainerTableSelection();
            setupImageTableSelection();
            setupImageTableContextMenu();
        });
    }

    private void setupImageTableSelection() {
        imageTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = imageTable.getSelectedRow();
                if (row >= 0) {
                    ImageInfo image = imageTableModel.getImage(row);
                    updateImageButtonsBasedOnImage(image);
                } else {
                    // No selection - disable all image buttons except Refresh
                    resetImageButtons();
                }
            }
        });
    }

    private void updateImageButtonsBasedOnImage(ImageInfo image) {
        // Find the images panel
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Images")) {
                Component comp = tabbedPane.getComponentAt(i);
                if (comp instanceof JPanel panel) {

                    // Look for the action panel in the south
                    for (Component child : panel.getComponents()) {
                        if (child instanceof JPanel actionPanel) {
                            for (Component btn : actionPanel.getComponents()) {
                                if (btn instanceof JButton button) {
                                    String text = button.getText();

                                    // Enable/disable buttons based on selection
                                    // All image action buttons should be enabled when an image is selected
                                    if (text.equals("Remove") || text.equals("Run") ||
                                            text.equals("Inspect") || text.equals("Pull Image")) {
                                        button.setEnabled(true);
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    private void resetImageButtons() {
        // Reset image buttons to default state
        updateImagePanelButtons(isDockerRunning());
    }

    // Also add a right-click context menu for the image table
    private void setupImageTableContextMenu() {
        JPopupMenu contextMenu = new JPopupMenu();

        // Run Image
        JMenuItem runItem = new JMenuItem("Run Image", IconManager.loadIcon(IconManager.IconCategory.MEDIA, "Play", 16, 16));
        runItem.addActionListener(e -> runImage());
        contextMenu.add(runItem);

        // Remove Image
        JMenuItem removeItem = new JMenuItem("Remove Image", IconManager.loadIcon(IconManager.IconCategory.GENERAL, "Remove", 16, 16));
        removeItem.addActionListener(e -> removeSelectedImage());
        contextMenu.add(removeItem);

        // Inspect Image
        JMenuItem inspectItem = new JMenuItem("Inspect Image", IconManager.loadIcon(IconManager.IconCategory.GENERAL, "Zoom", 16, 16));
        inspectItem.addActionListener(e -> inspectImage());
        contextMenu.add(inspectItem);

        contextMenu.addSeparator();

        // Copy Image Name
        JMenuItem copyNameItem = new JMenuItem("Copy Image Name");
        copyNameItem.addActionListener(e -> {
            int row = imageTable.getSelectedRow();
            if (row >= 0) {
                ImageInfo image = imageTableModel.getImage(row);
                StringSelection selection = new StringSelection(image.repository + ":" + image.tag);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
                showMessage("Image name copied to clipboard", "Info", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        contextMenu.add(copyNameItem);

        // Copy Image ID
        JMenuItem copyIdItem = new JMenuItem("Copy Image ID");
        copyIdItem.addActionListener(e -> {
            int row = imageTable.getSelectedRow();
            if (row >= 0) {
                ImageInfo image = imageTableModel.getImage(row);
                StringSelection selection = new StringSelection(image.id);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
                showMessage("Image ID copied to clipboard", "Info", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        contextMenu.add(copyIdItem);

        // Set the context menu to the image table
        imageTable.setComponentPopupMenu(contextMenu);

        // Also add double-click listener to run image on double-click
        imageTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = imageTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        imageTable.setRowSelectionInterval(row, row);
                        runImage();
                    }
                }
            }
        });
    }

    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        // Open Directory - toujours activé
        JButton openDirBtn = new JButton("Open Directory",
                IconManager.loadIcon(IconManager.IconCategory.GENERAL, "Open", 24, 24));
        openDirBtn.addActionListener(e -> openDirectory());
        toolBar.add(openDirBtn);

        toolBar.addSeparator();

        // Start Docker - initialement activé (puisque Docker n'est pas démarré)
        startDockerBtn = new JButton("Start Docker",
                IconManager.loadIcon(IconManager.IconCategory.MEDIA, "Play", 24, 24));
        startDockerBtn.addActionListener(e -> startDockerDaemon());
        toolBar.add(startDockerBtn);

        // Stop Docker - initialement désactivé
        stopDockerBtn = new JButton("Stop Docker",
                IconManager.loadIcon(IconManager.IconCategory.MEDIA, "Stop", 24, 24));
        stopDockerBtn.addActionListener(e -> stopDockerDaemon());
        stopDockerBtn.setEnabled(false);
        toolBar.add(stopDockerBtn);

        toolBar.addSeparator();

        // Docker Operations - initialement désactivés
        JButton buildBtn = new JButton("Build",
                IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "Jar", 24, 24));
        buildBtn.addActionListener(e -> buildDockerfile());
        buildBtn.setEnabled(false);
        toolBar.add(buildBtn);

        JButton runBtn = new JButton("Run",
                IconManager.loadIcon(IconManager.IconCategory.MEDIA, "Play", 24, 24));
        runBtn.addActionListener(e -> runContainer());
        runBtn.setEnabled(false);
        toolBar.add(runBtn);

        toolBar.addSeparator();

        // Compose buttons - initialement désactivés
        composeUpBtn = new JButton("Compose Up",
                IconManager.loadIcon(IconManager.IconCategory.NAVIGATION, "Up", 24, 24));
        composeUpBtn.addActionListener(e -> composeUp());
        composeUpBtn.setEnabled(false);
        toolBar.add(composeUpBtn);

        composeDownBtn = new JButton("Compose Down",
                IconManager.loadIcon(IconManager.IconCategory.NAVIGATION, "Down", 24, 24));
        composeDownBtn.addActionListener(e -> composeDown());
        composeDownBtn.setEnabled(false);
        toolBar.add(composeDownBtn);

        toolBar.addSeparator();

        // Refresh - toujours activé
        JButton refreshBtn = new JButton("Refresh",
                IconManager.loadIcon(IconManager.IconCategory.GENERAL, "Refresh", 24, 24));
        refreshBtn.addActionListener(e -> refreshAll());
        toolBar.add(refreshBtn);

        toolBar.add(Box.createHorizontalGlue());

        // Quick Actions Menu - toujours activé
        JButton quickActionsBtn = new JButton("Quick Actions",
                IconManager.loadIcon(IconManager.IconCategory.GENERAL, "Preferences", 24, 24));
        quickActionsBtn.addActionListener(e -> showQuickActions());
        toolBar.add(quickActionsBtn);
        
        aboutButton = new JButton("About",
                IconManager.loadIcon(IconManager.IconCategory.GENERAL, "About", 24, 24));
        aboutButton.addActionListener(e -> showAboutActions());
        toolBar.add(aboutButton);
        return toolBar;
    }

    private void showAboutActions() {
        // Create a JDialog for the "About" box
        JDialog aboutDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                "About Docker Management", true);
        aboutDialog.setLayout(new BorderLayout());
        aboutDialog.setSize(800, 650);
        aboutDialog.setLocationRelativeTo(this);
        aboutDialog.setResizable(false);

        // Main panel with white background
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        mainPanel.setBackground(Color.WHITE);

        // Header with icon and title
        JPanel headerPanel = new JPanel(new BorderLayout(10, 0));
        headerPanel.setBackground(Color.WHITE);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 15, 0));

        // Docker icon
        JLabel iconLabel = new JLabel();
        iconLabel.setIcon(IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "Server", 24, 24));
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 15));
        headerPanel.add(iconLabel, BorderLayout.WEST);

        // Title
        JPanel titlePanel = new JPanel(new GridLayout(2, 1, 0, 5));
        titlePanel.setBackground(Color.WHITE);

        JLabel titleLabel = new JLabel("Docker Management Plugin");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 20));
        titleLabel.setForeground(new Color(0, 105, 217)); // Docker blue
        titlePanel.add(titleLabel);

        JLabel versionLabel = new JLabel("Version 2.0.0");
        versionLabel.setFont(new Font("SansSerif", Font.PLAIN, 14));
        versionLabel.setForeground(Color.DARK_GRAY);
        titlePanel.add(versionLabel);

        headerPanel.add(titlePanel, BorderLayout.CENTER);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // Separator
        JSeparator separator = new JSeparator();
        separator.setForeground(Color.LIGHT_GRAY);
        mainPanel.add(separator, BorderLayout.CENTER);

        // Information panel
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBackground(Color.WHITE);
        infoPanel.setBorder(BorderFactory.createEmptyBorder(15, 10, 15, 10));

        // Description
        JTextArea descriptionArea = new JTextArea();
        descriptionArea.setEditable(false);
        descriptionArea.setFocusable(false);
        descriptionArea.setBackground(Color.WHITE);
        descriptionArea.setFont(new Font("SansSerif", Font.PLAIN, 12));
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setText("Docker Management Plugin for SwingIDE allows you to easily manage your Docker containers, images, and Docker files directly from the IDE.");
        infoPanel.add(descriptionArea);

        infoPanel.add(Box.createVerticalStrut(15));

        // Features
        JLabel featuresTitle = new JLabel("Main Features:");
        featuresTitle.setFont(new Font("SansSerif", Font.BOLD, 13));
        featuresTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoPanel.add(featuresTitle);

        infoPanel.add(Box.createVerticalStrut(5));

        String[] features = {
                "• Container management (start, stop, restart, remove, logs)",
                "• Image management (pull, run, remove, inspect)",
                "• Dockerfile editor with predefined templates",
                "• Docker Compose editor with templates",
                "• Docker file explorer",
                "• Integrated Docker terminal",
                "• Docker daemon start/stop",
                "• Automatic cleanup (prune, clean up)"
        };

        for (String feature : features) {
            JLabel featureLabel = new JLabel(feature);
            featureLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            featureLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            infoPanel.add(featureLabel);
        }

        infoPanel.add(Box.createVerticalStrut(15));

        // Technical information
        JPanel techPanel = new JPanel(new GridLayout(0, 2, 10, 5));
        techPanel.setBackground(Color.WHITE);
        techPanel.setBorder(BorderFactory.createTitledBorder("Technical Information"));

        addInfoRow(techPanel, "Java Version:", System.getProperty("java.version"));
        addInfoRow(techPanel, "OS:", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        addInfoRow(techPanel, "Docker:", checkDockerVersion());
        addInfoRow(techPanel, "Architecture:", System.getProperty("os.arch"));
        addInfoRow(techPanel, "Plugins Path:", getPluginPath());
        addInfoRow(techPanel, "Author:", "Jean-Francois Landreville");
        addInfoRow(techPanel, "Email:", "landrevillejf@protonmail.com");

        infoPanel.add(techPanel);

        infoPanel.add(Box.createVerticalStrut(10));

        // Copyright
        JLabel copyrightLabel = new JLabel("© 2024 Jean-Francois Landreville. All rights reserved.");
        copyrightLabel.setFont(new Font("SansSerif", Font.PLAIN, 10));
        copyrightLabel.setForeground(Color.GRAY);
        copyrightLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoPanel.add(copyrightLabel);

        JScrollPane infoScroll = new JScrollPane(infoPanel);
        infoScroll.setBorder(null);
        infoScroll.setBackground(Color.WHITE);
        mainPanel.add(infoScroll, BorderLayout.CENTER);

        // Close button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(Color.WHITE);
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JButton closeButton = new JButton("Close");
        closeButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        closeButton.setPreferredSize(new Dimension(100, 30));
        closeButton.addActionListener(e -> aboutDialog.dispose());

        // Check for updates button
        JButton updateButton = new JButton("Check for Updates");
        updateButton.setFont(new Font("SansSerif", Font.PLAIN, 12));
        updateButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(aboutDialog,
                    "You are using the latest version of the Docker Management Plugin.\n" +
                            "Current version: 2.0.0",
                    "Updates",
                    JOptionPane.INFORMATION_MESSAGE);
        });

        buttonPanel.add(updateButton);
        buttonPanel.add(closeButton);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        aboutDialog.add(mainPanel);
        aboutDialog.setVisible(true);
    }

    /**
     * Adds an information row to a GridLayout panel
     */
    private void addInfoRow(JPanel panel, String label, String value) {
        JLabel labelComp = new JLabel(label);
        labelComp.setFont(new Font("SansSerif", Font.BOLD, 11));
        panel.add(labelComp);

        JLabel valueComp = new JLabel(value != null ? value : "Not available");
        valueComp.setFont(new Font("SansSerif", Font.PLAIN, 11));
        panel.add(valueComp);
    }

    /**
     * Checks the installed Docker version
     */
    private String checkDockerVersion() {
        try {
            Process process = Runtime.getRuntime().exec("docker version --format '{{.Server.Version}}'");
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String version = reader.readLine();
                return version != null ? version : "Installed (unknown version)";
            } else {
                return "Not installed or not running";
            }
        } catch (Exception e) {
            return "Not detected";
        }
    }

    /**
     * Gets the plugin path
     */
    private String getPluginPath() {
        try {
            String path = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
            if (path != null && !path.isEmpty()) {
                return new File(path).getParent();
            }
        } catch (Exception e) {
            // Ignore
        }
        return "Unknown";
    }

    public void startDockerDaemon() {
        executorService.submit(() -> {
            try {
                terminalOutput.append(">>> Starting Docker daemon...\n");

                SwingUtilities.invokeLater(() -> {
                    startDockerBtn.setEnabled(false);
                    startDockerBtn.setText("Starting Docker...");
                });

                String os = System.getProperty("os.name").toLowerCase();
                final boolean isWindows = os.contains("win");
                final boolean isMac = os.contains("mac");
                final boolean isLinux = os.contains("nux") || os.contains("nix");

                if (isWindows) {
                    // Windows - démarrer Docker Desktop
                    String command = "Start-Process 'C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe'";
                    ProcessBuilder pb = new ProcessBuilder("powershell", "-Command", command);
                    Process process = pb.start();
                    process.waitFor();

                    SwingUtilities.invokeLater(() -> {
                        terminalOutput.append("Docker Desktop started. Waiting for Docker daemon to be ready...\n");
                        terminalOutput.append("This may take 20-30 seconds...\n\n");

                        // Désactiver le bouton pendant le démarrage
                        Component[] components = getComponents();
                        for (Component comp : components) {
                            if (comp instanceof JToolBar toolbar) {
                                for (Component btn : toolbar.getComponents()) {
                                    if (btn instanceof JButton button) {
                                        if (button.getText().equals("Start Docker")) {
                                            button.setEnabled(false);
                                            button.setText("Starting Docker...");
                                        }
                                    }
                                }
                            }
                        }
                    });

                    // Attendre que Docker soit prêt avec plusieurs tentatives
                    checkDockerStatusWithRetry(30); // 30 tentatives maximum
                    composeUpBtn.setEnabled(true);
                } else if (isMac) {
                    // macOS
                    String command = "open /Applications/Docker.app";
                    Process process = Runtime.getRuntime().exec(command);
                    process.waitFor();

                    SwingUtilities.invokeLater(() -> {
                        terminalOutput.append("Docker Desktop started on macOS.\n");
                        terminalOutput.append("Please wait for Docker to fully start...\n\n");
                    });

                    checkDockerStatusWithRetry(25); // 25 tentatives maximum

                } else if (isLinux) {
                    // Linux
                    SwingUtilities.invokeLater(() -> {
                        int confirm = JOptionPane.showConfirmDialog(this,
                                "This will start Docker daemon using systemctl.\n" +
                                        "You may need to enter your password.\n\nContinue?",
                                "Start Docker Daemon",
                                JOptionPane.YES_NO_OPTION);

                        if (confirm == JOptionPane.YES_OPTION) {
                            String command = "sudo systemctl start docker";
                            executeCommandInTerminal(command);

                            // Vérifier l'état après 3 secondes
                            Timer timer = new Timer(3000, e -> checkDockerStatusWithRetry(10));
                            timer.setRepeats(false);
                            timer.start();
                        }
                    });
                }

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    terminalOutput.append("✗ Error starting Docker: " + e.getMessage() + "\n\n");
                    showMessage("Error starting Docker: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private void checkDockerStatusWithRetry(int maxAttempts) {
        executorService.submit(() -> {
            boolean dockerReady = false;
            int attempts = 0;

            // Notify start of monitoring
            SwingUtilities.invokeLater(() -> {
                terminalOutput.append(">>> Monitoring Docker startup status...\n");
            });

            while (!dockerReady && attempts < maxAttempts) {
                try {
                    attempts++;

                    // Update progress every attempt
                    int finalAttempts = attempts;
                    SwingUtilities.invokeLater(() -> {
                        terminalOutput.append("Waiting for Docker... (" + finalAttempts + "/" + maxAttempts + " attempts)\n");
                    });

                    // Try simple docker command with timeout
                    ProcessBuilder pb = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}");
                    Process process = pb.start();

                    // Add timeout
                    boolean finished = process.waitFor(3, TimeUnit.SECONDS);

                    if (finished && process.exitValue() == 0) {
                        dockerReady = true;

                        // Get Docker version
                        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                        String version = reader.readLine();

                        // Run docker info to ensure full functionality
                        Process infoProcess = new ProcessBuilder("docker", "info").start();
                        int infoExitCode = infoProcess.waitFor();

                        if (infoExitCode == 0) {
                            final String dockerVersion = "Docker version " + (version != null ? version : "Unknown");

                            int finalAttempts1 = attempts;
                            SwingUtilities.invokeLater(() -> {
                                terminalOutput.append(">>> Docker is now ready! (Attempt " + finalAttempts1 + ")\n");
                                terminalOutput.append("Version: " + dockerVersion + "\n");
                                terminalOutput.append("Docker daemon is running and ready to use.\n\n");

                                updateUIForDockerReady(dockerVersion);
                            });
                        }
                        break;
                    }

                    // Clean up process
                    process.destroy();

                    // Wait before next attempt
                    Thread.sleep(2000);

                } catch (Exception e) {
                    // Log error but continue trying
                    log.debug("Docker check attempt {} failed: {}", attempts, e.getMessage());

                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!dockerReady) {
                int finalAttempts2 = attempts;
                SwingUtilities.invokeLater(() -> {
                    terminalOutput.append("✗ Docker failed to start after " + finalAttempts2 + " attempts\n");
                    terminalOutput.append("Please check Docker Desktop manually.\n\n");

                    updateUIForDockerFailed();

                    showMessage(
                            "Docker failed to start. Please:\n" +
                                    "1. Check if Docker Desktop is installed\n" +
                                    "2. Start Docker Desktop manually\n" +
                                    "3. Ensure virtualization is enabled",
                            "Docker Start Failed",
                            JOptionPane.WARNING_MESSAGE
                    );
                });
            }
        });
    }

    private void updateUIForDockerReady(String dockerVersion) {
        // Reactiver le bouton Start Docker
        Component[] components = getComponents();
        for (Component comp : components) {
            if (comp instanceof JToolBar) {
                JToolBar toolbar = (JToolBar) comp;
                for (Component btn : toolbar.getComponents()) {
                    if (btn instanceof JButton) {
                        JButton button = (JButton) btn;
                        String text = button.getText();

                        if (text.equals("Starting Docker...")) {
                            button.setText("Start Docker");
                            button.setEnabled(false); // Docker est déjà démarré
                        } else if (text.equals("Stop Docker")) {
                            button.setEnabled(true);
                        } else if (text.equals("Build") || text.equals("Run") ||
                                text.equals("Compose Up") || text.equals("Compose Down")) {
                            button.setEnabled(true);
                        }
                    }
                }
            }
        }

        // Rafraîchir les conteneurs et images
        refreshContainers();
        refreshImages();

        // Mettre à jour la barre d'état
        updateDockerStatusInStatusBar(true, dockerVersion);
    }

    private void updateUIForDockerFailed() {
        Component[] components = getComponents();
        for (Component comp : components) {
            if (comp instanceof JToolBar) {
                JToolBar toolbar = (JToolBar) comp;
                for (Component btn : toolbar.getComponents()) {
                    if (btn instanceof JButton) {
                        JButton button = (JButton) btn;
                        String text = button.getText();

                        if (text.equals("Starting Docker...")) {
                            button.setText("Start Docker");
                            button.setEnabled(true);
                        } else if (text.equals("Stop Docker")) {
                            button.setEnabled(false);
                        } else if (text.equals("Build") || text.equals("Run") ||
                                text.equals("Compose Up") || text.equals("Compose Down")) {
                            button.setEnabled(false);
                        }
                    }
                }
            }
        }

        updateDockerStatusInStatusBar(false, null);
    }

    public void stopDockerDaemon() {
        executorService.submit(() -> {
            try {
                terminalOutput.append(">>> Stopping Docker daemon gracefully...\n");

                String os = System.getProperty("os.name").toLowerCase();

                if (os.contains("win")) {
                    // Windows - méthode propre
                    stopDockerWindowsGracefully();

                } else if (os.contains("mac")) {
                    // macOS - méthode propre
                    stopDockerMacGracefully();

                } else {
                    // Linux - méthode propre
                    stopDockerLinuxGracefully();
                }
                updateDockerButtonsState(false);
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    terminalOutput.append("✗ Error stopping Docker: " + e.getMessage() + "\n\n");
                    showMessage("Error stopping Docker: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private void stopDockerWindowsGracefully() throws Exception {
        terminalOutput.append("1. Stopping all containers...\n");
        try {
            Process stopProcess = Runtime.getRuntime().exec("docker stop $(docker ps -aq)");
            stopProcess.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignorer si erreur
        }

        terminalOutput.append("2. Closing Docker Desktop gracefully...\n");

        // Méthode 1: Envoyer une commande de fermeture gracieuse
        String closeScript = """
        $dockerProcess = Get-Process "Docker Desktop" -ErrorAction SilentlyContinue
        if ($dockerProcess) {
            $dockerProcess.CloseMainWindow()
            Start-Sleep -Seconds 3
        }
        """;

        try {
            Process psProcess = Runtime.getRuntime().exec(new String[]{"powershell", "-Command", closeScript});
            psProcess.waitFor(3, TimeUnit.SECONDS);
            terminalOutput.append("Close signal sent to Docker Desktop\n");
        } catch (Exception e) {
            terminalOutput.append("PowerShell failed: " + e.getMessage() + "\n");
        }

        // Vérifier après 5 secondes
        Thread.sleep(5000);
        checkDockerStatus();

        SwingUtilities.invokeLater(() -> {
            terminalOutput.append("\nDocker Desktop should be closing...\n");
            terminalOutput.append("Please wait for Docker to fully stop\n\n");
            updateAllDockerButtons(false);
        });
    }

    private void stopDockerMacGracefully() throws Exception {
        terminalOutput.append("1. Stopping all containers...\n");
        try {
            Process stopProcess = Runtime.getRuntime().exec("docker stop $(docker ps -aq)");
            stopProcess.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignorer
        }

        terminalOutput.append("2. Quitting Docker Desktop gracefully...\n");

        // AppleScript pour quitter proprement
        String appleScript = """
        tell application "Docker Desktop"
            quit
        end tell
        """;

        try {
            Process osascript = Runtime.getRuntime().exec(
                    new String[]{"osascript", "-e", appleScript}
            );
            osascript.waitFor(3, TimeUnit.SECONDS);
            terminalOutput.append("Quit signal sent to Docker Desktop\n");
        } catch (Exception e) {
            terminalOutput.append("AppleScript failed: " + e.getMessage() + "\n");
        }

        // Vérifier après 5 secondes
        Thread.sleep(5000);
        checkDockerStatus();

        SwingUtilities.invokeLater(() -> {
            terminalOutput.append("\nDocker Desktop should be closing...\n");
            terminalOutput.append("Please wait for Docker to fully stop\n\n");
            updateAllDockerButtons(false);
        });
    }

    private void stopDockerLinuxGracefully() throws Exception {
        SwingUtilities.invokeLater(() -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "This will stop Docker daemon using systemctl.\n" +
                            "You may need to enter your password.\n\nContinue?",
                    "Stop Docker Daemon",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                terminalOutput.append("1. Stopping Docker daemon...\n");
                String command = "sudo systemctl stop docker";
                executeCommandInTerminal(command);

                // Vérifier après 3 secondes
                Timer timer = new Timer(3000, e -> {
                    checkDockerStatus();
                    updateAllDockerButtons(false);
                });
                timer.setRepeats(false);
                timer.start();
            }
        });
    }

    private void updateDockerStatusInStatusBar(boolean isRunning, String version) {
        // Trouver le JLabel de statut Docker dans la barre d'état
        Component[] components = getComponents();
        for (Component comp : components) {
            if (comp instanceof JPanel) {
                JPanel statusBar = (JPanel) comp;
                Component[] statusComponents = statusBar.getComponents();
                for (Component statusComp : statusComponents) {
                    if (statusComp instanceof JLabel) {
                        JLabel label = (JLabel) statusComp;
                        if (label.getText().startsWith("Docker:")) {
                            if (isRunning) {
                                label.setText("Docker: Running");
                                label.setForeground(new Color(0, 150, 0)); // Vert
                                label.setToolTipText("Version: " + (version != null ? version : "Unknown"));
                            } else {
                                label.setText("Docker: Not Running ✗");
                                label.setForeground(Color.RED);
                                label.setToolTipText("Click 'Start Docker' to start Docker daemon");
                            }
                            break;
                        }
                    }
                }
                break;
            }
        }
    }

    private void checkDockerStatus() {
        executorService.submit(() -> {
            try {
                // Use docker version which is lighter than docker info
                ProcessBuilder pb = new ProcessBuilder("docker", "version");
                Process process = pb.start();

                // Wait with timeout
                boolean finished = process.waitFor(5, TimeUnit.SECONDS);

                if (finished && process.exitValue() == 0) {
                    // Get version info
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    StringBuilder versionInfo = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.contains("Version:")) {
                            versionInfo.append(line).append("\n");
                        }
                    }

                    final String version = versionInfo.toString().trim();

                    SwingUtilities.invokeLater(() -> {
                        updateDockerStatusInStatusBar(true, version);
                        updateAllDockerButtons(true);
                        refreshContainers();
                        refreshImages();
                        terminalOutput.append("\n");
                    });

                } else {
                    // Docker not running or timeout
                    process.destroy();
                    SwingUtilities.invokeLater(() -> {
                        terminalOutput.append(">>> Checking Docker status...\n");
                        terminalOutput.append("Docker is not running\n");
                        terminalOutput.append("Please click 'Start Docker' to start Docker daemon\n\n");
                        updateDockerStatusInStatusBar(false, null);
                        updateAllDockerButtons(false);
                    });
                }

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    terminalOutput.append("✗ Cannot check Docker status\n");
                    terminalOutput.append("Error: " + e.getMessage() + "\n\n");

                    updateDockerStatusInStatusBar(false, null);
                    updateAllDockerButtons(false);
                });
            }
        });
    }

    private void updateDockerButtonsState(boolean dockerRunning) {
        Component[] components = getComponents();
        for (Component comp : components) {
            if (comp instanceof JToolBar toolbar) {
                for (Component btn : toolbar.getComponents()) {
                    if (btn instanceof JButton button) {
                        String text = button.getText();

                        // CORRECTION ICI : La logique était inversée
                        switch (text) {
                            case "Start Docker" -> button.setEnabled(!dockerRunning);
                            case "Stop Docker" -> button.setEnabled(dockerRunning);
                            case "Compose Up" ->
                                    button.setEnabled(dockerRunning);  // CORRIGÉ : activé quand Docker tourne
                            case "Compose Down" -> button.setEnabled(dockerRunning);  // OK
                            case "Build", "Run" -> button.setEnabled(dockerRunning);  // Ces boutons dépendent de Docker
                        }
                        // Les boutons "Refresh" et "Quick Actions" sont toujours activés
                    }
                }
                break;
            }
        }
    }

    private JPanel createFilePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // File tree - Initialiser avec le répertoire du projet
        DefaultMutableTreeNode root;
        if (currentDirectory != null && currentDirectory.exists()) {
            root = new DefaultMutableTreeNode(currentDirectory);
        } else {
            root = new DefaultMutableTreeNode("No directory selected");
        }

        treeModel = new DefaultTreeModel(root);
        fileTree = new JTree(treeModel);
        fileTree.setRootVisible(true);
        fileTree.setShowsRootHandles(true);
        fileTree.setCellRenderer(new FileTreeCellRenderer());

        fileTree.addTreeSelectionListener(e -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof File) {
                File file = (File) node.getUserObject();
                if (file.isFile()) {
                    loadFileContent(file);
                }
            }
        });

        JScrollPane treeScroll = new JScrollPane(fileTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Project Files"));

        // Right panel with file actions
        JPanel rightPanel = new JPanel(new BorderLayout(5, 5));

        // File info panel
        JPanel fileInfoPanel = new JPanel(new GridBagLayout());
        fileInfoPanel.setBorder(BorderFactory.createTitledBorder("File Information"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JLabel nameLabel = new JLabel("Name: ");
        JLabel nameValue = new JLabel("-");
        JLabel sizeLabel = new JLabel("Size: ");
        JLabel sizeValue = new JLabel("-");
        JLabel typeLabel = new JLabel("Type: ");
        JLabel typeValue = new JLabel("-");
        JLabel pathLabel = new JLabel("Path: ");
        JLabel pathValue = new JLabel("-");
        pathValue.setForeground(Color.BLUE);

        gbc.gridx = 0; gbc.gridy = 0; fileInfoPanel.add(nameLabel, gbc);
        gbc.gridx = 1; fileInfoPanel.add(nameValue, gbc);
        gbc.gridx = 0; gbc.gridy = 1; fileInfoPanel.add(sizeLabel, gbc);
        gbc.gridx = 1; fileInfoPanel.add(sizeValue, gbc);
        gbc.gridx = 0; gbc.gridy = 2; fileInfoPanel.add(typeLabel, gbc);
        gbc.gridx = 1; fileInfoPanel.add(typeValue, gbc);
        gbc.gridx = 0; gbc.gridy = 3; fileInfoPanel.add(pathLabel, gbc);
        gbc.gridx = 1; fileInfoPanel.add(pathValue, gbc);

        rightPanel.add(fileInfoPanel, BorderLayout.NORTH);

        // File actions panel
        JPanel actionsPanel = new JPanel(new GridLayout(0, 1, 5, 5));
        actionsPanel.setBorder(BorderFactory.createTitledBorder("Actions"));

        JButton newDockerfileBtn = new JButton("New Dockerfile", IconManager.loadIcon(IconManager.IconCategory.GENERAL,"New",24,24));
        newDockerfileBtn.addActionListener(e -> createNewDockerfile());
        actionsPanel.add(newDockerfileBtn);

        JButton newComposeBtn = new JButton("New Docker Compose", IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT,"Application",24,24));
        newComposeBtn.addActionListener(e -> createNewComposeFile());
        actionsPanel.add(newComposeBtn);

        JButton deleteBtn = new JButton("Delete Selected", IconManager.loadIcon(IconManager.IconCategory.GENERAL,"Remove",24,24));
        deleteBtn.addActionListener(e -> deleteSelectedFile());
        actionsPanel.add(deleteBtn);

        JButton renameBtn = new JButton("Rename", IconManager.loadIcon(IconManager.IconCategory.GENERAL,"Edit",24,24));
        renameBtn.addActionListener(e -> renameSelectedFile());
        actionsPanel.add(renameBtn);

        rightPanel.add(actionsPanel, BorderLayout.CENTER);

        panel.add(treeScroll, BorderLayout.CENTER);
        panel.add(rightPanel, BorderLayout.EAST);

        return panel;
    }

    private JPanel createDockerfilePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Top panel with templates and actions
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));

        // Template selection
        JPanel templatePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        templatePanel.add(new JLabel("Template:"));

        JComboBox<String> templateCombo = new JComboBox<>(dockerTemplates.keySet().toArray(new String[0]));
        templateCombo.addActionListener(e -> {
            String selected = (String) templateCombo.getSelectedItem();
            if (selected != null) {
                dockerfileEditor.setText(dockerTemplates.get(selected));
            }
        });
        templatePanel.add(templateCombo);

        topPanel.add(templatePanel, BorderLayout.WEST);

        // Action buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton validateBtn = new JButton("Validate");
        validateBtn.addActionListener(e -> validateDockerfile());
        buttonPanel.add(validateBtn);

        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> saveDockerfile());
        buttonPanel.add(saveBtn);

        JButton buildBtn = new JButton("Build Image");
        buildBtn.addActionListener(e -> buildDockerfile());
        buttonPanel.add(buildBtn);

        topPanel.add(buttonPanel, BorderLayout.EAST);
        panel.add(topPanel, BorderLayout.NORTH);

        // Editor
        dockerfileEditor = new JTextArea();
        dockerfileEditor.setFont(new Font("Monospaced", Font.PLAIN, 13));
        dockerfileEditor.setText(dockerTemplates.get("Node.js")); // Default template

        JScrollPane editorScroll = new JScrollPane(dockerfileEditor);
        editorScroll.setBorder(BorderFactory.createTitledBorder("Dockerfile Editor"));
        panel.add(editorScroll, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createComposePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Top panel
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));

        JPanel templatePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        templatePanel.add(new JLabel("Template:"));

        JComboBox<String> templateCombo = new JComboBox<>(composeTemplates.keySet().toArray(new String[0]));
        templateCombo.addActionListener(e -> {
            String selected = (String) templateCombo.getSelectedItem();
            if (selected != null) {
                composeEditor.setText(composeTemplates.get(selected));
            }
        });
        templatePanel.add(templateCombo);

        topPanel.add(templatePanel, BorderLayout.WEST);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton validateBtn = new JButton("Validate");
        validateBtn.addActionListener(e -> validateComposeFile());
        buttonPanel.add(validateBtn);

        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> saveComposeFile());
        buttonPanel.add(saveBtn);

        JButton upBtn = new JButton("Compose Up");
        upBtn.addActionListener(e -> composeUp());
        buttonPanel.add(upBtn);

        JButton downBtn = new JButton("Compose Down");
        downBtn.addActionListener(e -> composeDown());
        buttonPanel.add(downBtn);

        topPanel.add(buttonPanel, BorderLayout.EAST);
        panel.add(topPanel, BorderLayout.NORTH);

        // Editor
        composeEditor = new JTextArea();
        composeEditor.setFont(new Font("Monospaced", Font.PLAIN, 13));
        composeEditor.setText(composeTemplates.get("Web App + Database"));

        JScrollPane editorScroll = new JScrollPane(composeEditor);
        editorScroll.setBorder(BorderFactory.createTitledBorder("docker-compose.yml Editor"));
        panel.add(editorScroll, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createContainersPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Table
        containerTableModel = new ContainerTableModel();
        containerTable = new JTable(containerTableModel);
        containerTable.setRowHeight(25);
        containerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Center align for status column
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        containerTable.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);

        // Color status cells
        containerTable.getColumnModel().getColumn(3).setCellRenderer(new StatusCellRenderer());

        JScrollPane tableScroll = new JScrollPane(containerTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Running Containers"));
        panel.add(tableScroll, BorderLayout.CENTER);

        // Action buttons panel
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refreshContainers());
        actionPanel.add(refreshBtn);

        JButton startBtn = new JButton("Start");
        startBtn.addActionListener(e -> startSelectedContainer());
        actionPanel.add(startBtn);

        JButton stopBtn = new JButton("Stop");
        stopBtn.addActionListener(e -> stopSelectedContainer());
        actionPanel.add(stopBtn);

        JButton restartBtn = new JButton("Restart");
        restartBtn.addActionListener(e -> restartSelectedContainer());
        actionPanel.add(restartBtn);

        JButton logsBtn = new JButton("View Logs");
        logsBtn.addActionListener(e -> viewContainerLogs());
        actionPanel.add(logsBtn);

        JButton removeBtn = new JButton("Remove");
        removeBtn.setForeground(Color.RED);
        removeBtn.addActionListener(e -> removeSelectedContainer());
        actionPanel.add(removeBtn);

        JButton inspectBtn = new JButton("Inspect");
        inspectBtn.addActionListener(e -> inspectContainer());
        actionPanel.add(inspectBtn);

        panel.add(actionPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createImagesPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Table
        imageTableModel = new ImageTableModel();
        imageTable = new JTable(imageTableModel);
        imageTable.setRowHeight(25);
        imageTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane tableScroll = new JScrollPane(imageTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Docker Images"));
        panel.add(tableScroll, BorderLayout.CENTER);

        // Action buttons panel
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> refreshImages());
        actionPanel.add(refreshBtn);

        JButton pullBtn = new JButton("Pull Image");
        pullBtn.addActionListener(e -> pullImage());
        actionPanel.add(pullBtn);

        JButton removeBtn = new JButton("Remove");
        removeBtn.setForeground(Color.RED);
        removeBtn.addActionListener(e -> removeSelectedImage());
        actionPanel.add(removeBtn);

        JButton runBtn = new JButton("Run");
        runBtn.addActionListener(e -> runImage());
        actionPanel.add(runBtn);

        JButton inspectBtn = new JButton("Inspect");
        inspectBtn.addActionListener(e -> inspectImage());
        actionPanel.add(inspectBtn);

        panel.add(actionPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createTerminalPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Top panel with command selection
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));

        JPanel commandPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        commandPanel.add(new JLabel("Command:"));

        String[] commands = {
                "docker ps", "docker ps -a", "docker images", "docker system df",
                "docker logs", "docker stats", "docker network ls", "docker volume ls"
        };

        dockerCommandCombo = new JComboBox<>(commands);
        dockerCommandCombo.setEditable(true);
        dockerCommandCombo.addActionListener(e -> {
            commandField.setText((String) dockerCommandCombo.getSelectedItem());
        });
        commandPanel.add(dockerCommandCombo);

        topPanel.add(commandPanel, BorderLayout.WEST);

        // Command field
        JPanel commandFieldPanel = new JPanel(new BorderLayout(5, 5));
        commandFieldPanel.add(new JLabel("Custom Command:"), BorderLayout.WEST);

        commandField = new JTextField();
        commandField.addActionListener(e -> executeCommand());
        commandFieldPanel.add(commandField, BorderLayout.CENTER);

        JButton executeBtn = new JButton("Execute");
        executeBtn.addActionListener(e -> executeCommand());
        commandFieldPanel.add(executeBtn, BorderLayout.EAST);

        topPanel.add(commandFieldPanel, BorderLayout.CENTER);
        panel.add(topPanel, BorderLayout.NORTH);

        // Terminal output - MODIFIER CES LIGNES
        terminalOutput = new JTextArea();
        terminalOutput.setFont(new Font("Monospaced", Font.PLAIN, 12));
        terminalOutput.setEditable(false); // Garder false pour éviter l'édition

        // MAIS permettre la sélection
        terminalOutput.setFocusable(true);
        terminalOutput.setBackground(new Color(30, 30, 30));
        terminalOutput.setForeground(Color.WHITE);
        terminalOutput.setCaretColor(Color.WHITE); // Rendre le curseur visible

        // Ajouter un menu contextuel pour copier
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.addActionListener(e -> terminalOutput.copy());
        popupMenu.add(copyItem);

        JMenuItem selectAllItem = new JMenuItem("Select All");
        selectAllItem.addActionListener(e -> terminalOutput.selectAll());
        popupMenu.add(selectAllItem);

        terminalOutput.setComponentPopupMenu(popupMenu);

        JScrollPane terminalScroll = new JScrollPane(terminalOutput);
        terminalScroll.setBorder(BorderFactory.createTitledBorder("Terminal Output"));
        panel.add(terminalScroll, BorderLayout.CENTER);

        // Bottom panel
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));

        JButton clearBtn = new JButton("Clear Terminal");
        clearBtn.addActionListener(e -> terminalOutput.setText(""));
        bottomPanel.add(clearBtn, BorderLayout.WEST);

        JButton killBtn = new JButton("Kill Process");
        killBtn.setForeground(Color.RED);
        killBtn.addActionListener(e -> {
            killDocker();
        });
        bottomPanel.add(killBtn, BorderLayout.EAST);

        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void killDocker() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "KILL Docker Desktop completely?\n\n" +
                        "This will:\n" +
                        "1. Stop all running containers\n" +
                        "2. Force quit Docker Desktop app\n" +
                        "3. Stop Docker daemon\n\n" +
                        "Continue?",
                "💀 KILL DOCKER DESKTOP 💀",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            terminalOutput.append("\n>>> 🚨 KILLING DOCKER DESKTOP...\n");

            String os = System.getProperty("os.name").toLowerCase();

            // Désactiver le bouton pendant l'opération
            updateAllDockerButtons(false);

            executorService.submit(() -> {
                try {
                    if (os.contains("win")) {
                        // Windows
                        killDockerWindows();
                    } else if (os.contains("mac")) {
                        // macOS
                        killDockerMac();
                    } else {
                        // Linux
                        executeCommandInTerminal("sudo systemctl stop docker docker.socket");
                    }

                    // Attendre 3 secondes pour que Docker soit complètement mort
                    Thread.sleep(3000);

                    // Vérifier le statut
                    checkDockerStatus();

                    SwingUtilities.invokeLater(() -> {
                        terminalOutput.append("\n>>> 💀 DOCKER DESKTOP HAS BEEN KILLED\n");
                        terminalOutput.append(">>> You must restart Docker Desktop manually\n");
                        terminalOutput.append(">>> from Start Menu (Windows) or Applications (macOS)\n\n");
                    });

                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        terminalOutput.append("✗ Error: " + e.getMessage() + "\n");
                    });
                }
            });
        }
    }

    private void killDockerWindows() throws Exception {
        terminalOutput.append("1. Stopping containers...\n");
        try {
            Process stopProcess = Runtime.getRuntime().exec("docker stop $(docker ps -aq)");
            stopProcess.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignorer
        }

        terminalOutput.append("2. Killing Docker Desktop process...\n");

        // Essayer plusieurs méthodes
        String[] commands = {
                "wmic process where \"name='Docker Desktop.exe'\" delete",
                "taskkill /F /IM \"Docker Desktop.exe\" /T",
                "sc stop com.docker.service"
        };

        for (String cmd : commands) {
            try {
                Process process = Runtime.getRuntime().exec(cmd);
                boolean finished = process.waitFor(2, TimeUnit.SECONDS);
                if (finished) {
                    terminalOutput.append("Executed: " + cmd + "\n");
                }
            } catch (Exception e) {
                // Ignorer
            }
        }
    }

    private void killDockerMac() throws Exception {
        terminalOutput.append("1. Stopping containers...\n");
        try {
            Process stopProcess = Runtime.getRuntime().exec("docker stop $(docker ps -aq)");
            stopProcess.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignorer
        }

        terminalOutput.append("2. Quitting Docker Desktop...\n");

        // Méthode 1: AppleScript pour quitter proprement
        try {
            Process osascript = Runtime.getRuntime().exec(
                    new String[]{"osascript", "-e", "tell application \"Docker Desktop\" to quit"}
            );
            osascript.waitFor(2, TimeUnit.SECONDS);
            terminalOutput.append("Quit signal sent\n");
        } catch (Exception e) {
            terminalOutput.append("AppleScript failed: " + e.getMessage() + "\n");
        }

        // Attendre un peu
        Thread.sleep(1000);

        // Méthode 2: Forcer la fermeture
        terminalOutput.append("3. Force killing...\n");
        try {
            Process pkill = Runtime.getRuntime().exec("pkill -f \"Docker Desktop\"");
            pkill.waitFor(2, TimeUnit.SECONDS);
            terminalOutput.append("Process killed\n");
        } catch (Exception e) {
            // Ignorer
        }

        // Méthode 3: Tuer la VM
        try {
            Process hyperkit = Runtime.getRuntime().exec("pkill -f hyperkit");
            hyperkit.waitFor(2, TimeUnit.SECONDS);
            terminalOutput.append("VM killed\n");
        } catch (Exception e) {
            // Ignorer
        }
    }

    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)
        ));

        JLabel statusLabel = new JLabel("Ready");
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusBar.add(statusLabel, BorderLayout.WEST);

        JLabel dockerStatus = new JLabel("Docker: Unknown");
        dockerStatus.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusBar.add(dockerStatus, BorderLayout.EAST);

        return statusBar;
    }

    // Core functionality methods
    private void openDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Project Directory");

        // Définir le répertoire par défaut sur le répertoire du projet
        if (currentDirectory != null && currentDirectory.exists()) {
            chooser.setCurrentDirectory(currentDirectory);
        } else {
            // Fallback: répertoire de travail courant
            chooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentDirectory = chooser.getSelectedFile();
            loadFileTree();
        }
    }

    private void loadFileTree() {
        try {
            // Si currentDirectory est null, réessayer de le récupérer
            if (currentDirectory == null || !currentDirectory.exists()) {
                if (projectRoot != null && projectRoot.exists()) {
                    currentDirectory = projectRoot;
                    log.info("Re-initialized current directory: {}", currentDirectory);
                } else {
                    log.error("Cannot load file tree: no readable project directory");
                    return;
                }
            }

            log.info("Loading file tree from: {}", currentDirectory.getAbsolutePath());
            DefaultMutableTreeNode root = new DefaultMutableTreeNode(currentDirectory);
            buildTree(root, currentDirectory);
            treeModel.setRoot(root);

            // Seulement expand la racine (premier niveau)
            fileTree.expandRow(0);
            fileTree.revalidate();
            fileTree.repaint();
        } catch (Exception e) {
            log.error("Error loading file tree: {}", e.getMessage(), e);
        }
    }

    private void buildTree(DefaultMutableTreeNode parent, File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });

            for (File file : files) {
                if (!file.isHidden()) {
                    // Filtrer pour n'afficher que les fichiers Docker
                    if (file.isDirectory()) {
                        // Pour les dossiers, vérifier s'ils contiennent des fichiers Docker
                        if (containsDockerFiles(file)) {
                            DefaultMutableTreeNode node = new DefaultMutableTreeNode(file);
                            parent.add(node);
                            buildTree(node, file); // Explorer récursivement
                        }
                    } else {
                        // Pour les fichiers, vérifier s'ils sont des fichiers Docker
                        if (isDockerFile(file)) {
                            DefaultMutableTreeNode node = new DefaultMutableTreeNode(file);
                            parent.add(node);
                        }
                    }
                }
            }
        }
    }

    // Méthode pour vérifier si un fichier est un fichier Docker
    private boolean isDockerFile(File file) {
        String name = file.getName().toLowerCase();
        return name.equals("dockerfile") ||
                name.equals("docker-compose.yml") ||
                name.equals("docker-compose.yaml");
    }

    // Méthode pour vérifier si un dossier contient des fichiers Docker
    private boolean containsDockerFiles(File dir) {
        if (!dir.isDirectory()) return false;

        File[] files = dir.listFiles();
        if (files == null) return false;

        for (File file : files) {
            if (!file.isHidden()) {
                if (file.isDirectory()) {
                    if (containsDockerFiles(file)) {
                        return true;
                    }
                } else {
                    if (isDockerFile(file)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private void loadFileContent(File file) {
        try {
            String content = Files.readString(file.toPath());
            if (file.getName().equalsIgnoreCase("Dockerfile")) {
                dockerfileEditor.setText(content);
                tabbedPane.setSelectedIndex(1);
            } else if (file.getName().equalsIgnoreCase("docker-compose.yml") ||
                    file.getName().equalsIgnoreCase("docker-compose.yaml")) {
                composeEditor.setText(content);
                tabbedPane.setSelectedIndex(2);
            }
        } catch (IOException e) {
            log.error("Error loading file: {}", e.getMessage());
        }
    }

    private void createNewDockerfile() {
        String fileName = JOptionPane.showInputDialog(this, "Enter Dockerfile name:", "Dockerfile");
        if (fileName != null && !fileName.trim().isEmpty()) {
            if (currentDirectory == null) {
                JOptionPane.showMessageDialog(this, "Please open a directory first", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            File dockerfile = new File(currentDirectory, fileName);
            try {
                Files.writeString(dockerfile.toPath(), dockerTemplates.get("Node.js"));
                loadFileTree();
                dockerfileEditor.setText(dockerTemplates.get("Node.js"));
                tabbedPane.setSelectedIndex(1);
            } catch (IOException e) {
                log.error("Error creating Dockerfile: {}", e.getMessage());
            }
        }
    }

    private void createNewComposeFile() {
        String fileName = JOptionPane.showInputDialog(this, "Enter Compose file name:", "docker-compose.yml");
        if (fileName != null && !fileName.trim().isEmpty()) {
            if (currentDirectory == null) {
                JOptionPane.showMessageDialog(this, "Please open a directory first", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            File composeFile = new File(currentDirectory, fileName);
            try {
                Files.writeString(composeFile.toPath(), composeTemplates.get("Web App + Database"));
                loadFileTree();
                composeEditor.setText(composeTemplates.get("Web App + Database"));
                tabbedPane.setSelectedIndex(2);
            } catch (IOException e) {
                log.error("Error creating Compose file: {}", e.getMessage());
            }
        }
    }

    private void deleteSelectedFile() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
        if (node != null && node.getUserObject() instanceof File) {
            File file = (File) node.getUserObject();
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Delete '" + file.getName() + "'?",
                    "Confirm Delete",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                try {
                    Files.deleteIfExists(file.toPath());
                    treeModel.removeNodeFromParent(node);
                } catch (IOException e) {
                    log.error("Error deleting file: {}", e.getMessage());
                }
            }
        }
    }

    private void renameSelectedFile() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
        if (node != null && node.getUserObject() instanceof File) {
            File file = (File) node.getUserObject();
            String newName = JOptionPane.showInputDialog(this, "Enter new name:", file.getName());
            if (newName != null && !newName.trim().isEmpty() && !newName.equals(file.getName())) {
                File newFile = new File(file.getParent(), newName);
                if (file.renameTo(newFile)) {
                    node.setUserObject(newFile);
                    treeModel.nodeChanged(node);
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to rename file", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    private void validateDockerfile() {
        String content = dockerfileEditor.getText();
        if (content.trim().isEmpty()) {
            showMessage("Dockerfile is empty", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Basic validation
        List<String> errors = new ArrayList<>();
        String[] lines = content.split("\n");
        boolean hasFrom = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#") || line.isEmpty()) continue;

            if (line.toUpperCase().startsWith("FROM")) {
                hasFrom = true;
            }

            // Check for common errors
            if (line.toUpperCase().startsWith("COPY") || line.toUpperCase().startsWith("ADD")) {
                if (!line.contains(" ")) {
                    errors.add("Line " + (i + 1) + ": Invalid COPY/ADD format");
                }
            }
        }

        if (!hasFrom) {
            errors.add("Dockerfile must start with a FROM instruction");
        }

        if (errors.isEmpty()) {
            showMessage("Dockerfile syntax is valid", "Validation Success", JOptionPane.INFORMATION_MESSAGE);
        } else {
            String errorMsg = String.join("\n", errors);
            showMessage("Validation errors:\n" + errorMsg, "Validation Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveDockerfile() {
        if (currentDirectory == null) {
            showMessage("Please open a directory first", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String fileName = JOptionPane.showInputDialog(this, "Save as:", "Dockerfile");
        if (fileName != null && !fileName.trim().isEmpty()) {
            File dockerfile = new File(currentDirectory, fileName);
            try {
                Files.writeString(dockerfile.toPath(), dockerfileEditor.getText());
                loadFileTree();
                showMessage("Dockerfile saved successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                log.error("Error saving Dockerfile: {}", e.getMessage());
                showMessage("Failed to save Dockerfile: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void buildDockerfile() {
        if (currentDirectory == null) {
            showMessage("Please open a directory first", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String imageName = JOptionPane.showInputDialog(this, "Enter image name (e.g., myapp:latest):", "myapp:latest");
        if (imageName != null && !imageName.trim().isEmpty()) {
            String command = "docker build -t " + imageName + " .";
            executeCommandInTerminal(command);
        }
    }

    private void validateComposeFile() {
        String content = composeEditor.getText();
        if (content.trim().isEmpty()) {
            showMessage("Compose file is empty", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Basic YAML validation
        try {
            // Check for required fields
            if (!content.contains("version:")) {
                showMessage("Compose file should have a version", "Validation Warning", JOptionPane.WARNING_MESSAGE);
            }

            if (!content.contains("services:")) {
                showMessage("Compose file should have services section", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            showMessage("Compose file syntax appears valid", "Validation Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            showMessage("Validation error: " + e.getMessage(), "Validation Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveComposeFile() {
        if (currentDirectory == null) {
            showMessage("Please open a directory first", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String fileName = JOptionPane.showInputDialog(this, "Save as:", "docker-compose.yml");
        if (fileName != null && !fileName.trim().isEmpty()) {
            File composeFile = new File(currentDirectory, fileName);
            try {
                Files.writeString(composeFile.toPath(), composeEditor.getText());
                loadFileTree();
                showMessage("Compose file saved successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                log.error("Error saving Compose file: {}", e.getMessage());
                showMessage("Failed to save Compose file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void composeUp() {
        if (currentDirectory == null) {
            showMessage("Please open a directory first", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String command = "docker-compose up -d";
        executeCommandInTerminal(command);
        composeDownBtn.setEnabled(true);
    }

    private void composeDown() {
        if (currentDirectory == null) {
            showMessage("Please open a directory first", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "This will stop and remove all containers defined in docker-compose.yml\nContinue?",
                "Confirm Compose Down",
                JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            String command = "docker-compose down";
            executeCommandInTerminal(command);
        }
    }

    public void refreshAll() {
        refreshContainers();
        refreshImages();
        if (currentDirectory != null) {
            loadFileTree();
        }
    }

    private void refreshContainers() {
        executorService.submit(() -> {
            try {
                Process process = Runtime.getRuntime().exec("docker ps -a --format \"{{.ID}}|{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}|{{.CreatedAt}}\"");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                List<ContainerInfo> containers = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|", 6);
                    if (parts.length == 6) {
                        containers.add(new ContainerInfo(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
                    }
                }

                process.waitFor();

                SwingUtilities.invokeLater(() -> {
                    containerTableModel.setContainers(containers);
                });

            } catch (Exception e) {
                log.error("Error refreshing containers: {}", e.getMessage());
            }
        });
    }

    private void refreshImages() {
        executorService.submit(() -> {
            try {
                Process process = Runtime.getRuntime().exec("docker images --format \"{{.Repository}}|{{.Tag}}|{{.ID}}|{{.CreatedSince}}|{{.Size}}\"");
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                List<ImageInfo> images = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|", 5);
                    if (parts.length == 5) {
                        images.add(new ImageInfo(parts[0], parts[1], parts[2], parts[3], parts[4]));
                    }
                }

                process.waitFor();

                SwingUtilities.invokeLater(() -> {
                    imageTableModel.setImages(images);
                });

            } catch (Exception e) {
                log.error("Error refreshing images: {}", e.getMessage());
            }
        });
    }

    private void startSelectedContainer() {
        int row = containerTable.getSelectedRow();
        if (row >= 0) {
            ContainerInfo container = containerTableModel.getContainer(row);
            String command = "docker start " + container.id;
            executeCommandInTerminal(command);
        } else {
            showMessage("Please select a container", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void stopSelectedContainer() {
        int row = containerTable.getSelectedRow();
        if (row >= 0) {
            ContainerInfo container = containerTableModel.getContainer(row);
            String command = "docker stop " + container.id;
            executeCommandInTerminal(command);
        } else {
            showMessage("Please select a container", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void restartSelectedContainer() {
        int row = containerTable.getSelectedRow();
        if (row >= 0) {
            ContainerInfo container = containerTableModel.getContainer(row);
            String command = "docker restart " + container.id;
            executeCommandInTerminal(command);
        } else {
            showMessage("Please select a container", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void removeSelectedContainer() {
        int row = containerTable.getSelectedRow();
        if (row >= 0) {
            ContainerInfo container = containerTableModel.getContainer(row);

            int confirm = JOptionPane.showConfirmDialog(this,
                    "Remove container '" + container.name + "'?",
                    "Confirm Remove",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                String command = "docker rm -f " + container.id;
                executeCommandInTerminal(command);
            }
        } else {
            showMessage("Please select a container", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void viewContainerLogs() {
        int row = containerTable.getSelectedRow();
        if (row >= 0) {
            ContainerInfo container = containerTableModel.getContainer(row);
            showContainerLogsDialog(container);
        } else {
            showMessage("Please select a container", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void inspectContainer() {
        int row = containerTable.getSelectedRow();
        if (row >= 0) {
            ContainerInfo container = containerTableModel.getContainer(row);
            String command = "docker inspect " + container.id;
            executeCommandInTerminal(command);
        } else {
            showMessage("Please select a container", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void pullImage() {
        String imageName = JOptionPane.showInputDialog(this, "Enter image name (e.g., nginx:latest):");
        if (imageName != null && !imageName.trim().isEmpty()) {
            String command = "docker pull " + imageName;
            executeCommandInTerminal(command);
        }
    }

    private void removeSelectedImage() {
        int row = imageTable.getSelectedRow();
        if (row >= 0) {
            ImageInfo image = imageTableModel.getImage(row);

            int confirm = JOptionPane.showConfirmDialog(this,
                    "Remove image '" + image.repository + ":" + image.tag + "'?",
                    "Confirm Remove",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                String command = "docker rmi " + image.id;
                executeCommandInTerminal(command);
            }
        } else {
            showMessage("Please select an image", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void runImage() {
        int row = imageTable.getSelectedRow();
        if (row >= 0) {
            ImageInfo image = imageTableModel.getImage(row);
            String containerName = JOptionPane.showInputDialog(this, "Enter container name (optional):");
            String ports = JOptionPane.showInputDialog(this, "Enter port mapping (e.g., 8080:80, optional):");
            String volumes = JOptionPane.showInputDialog(this, "Enter volume mounts (optional):");

            StringBuilder command = new StringBuilder("docker run -d");

            if (containerName != null && !containerName.trim().isEmpty()) {
                command.append(" --name ").append(containerName);
            }

            if (ports != null && !ports.trim().isEmpty()) {
                command.append(" -p ").append(ports);
            }

            if (volumes != null && !volumes.trim().isEmpty()) {
                command.append(" -v ").append(volumes);
            }

            command.append(" ").append(image.repository).append(":").append(image.tag);
            executeCommandInTerminal(command.toString());
        } else {
            showMessage("Please select an image", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void inspectImage() {
        int row = imageTable.getSelectedRow();
        if (row >= 0) {
            ImageInfo image = imageTableModel.getImage(row);
            String command = "docker inspect " + image.id;
            executeCommandInTerminal(command);
        } else {
            showMessage("Please select an image", "Error", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void executeCommand() {
        String command = commandField.getText().trim();
        if (!command.isEmpty()) {
            executeCommandInTerminal(command);
        }
    }

    private void executeCommandInTerminal(String command) {
        terminalOutput.append(">>> " + command + "\n");

        // Vérifier si c'est une commande Docker et si Docker est en cours d'exécution
        if (command.startsWith("docker ") || command.contains("docker-compose")) {
            // Désactiver temporairement les boutons pendant l'exécution
            setDockerButtonsEnabled(false);
        }

        executorService.submit(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder();
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    pb.command("cmd.exe", "/c", command);
                } else {
                    pb.command("sh", "-c", command);
                }

                pb.directory(currentDirectory);
                currentProcess = pb.start();

                // Read output
                BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(currentProcess.getErrorStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    String finalLine = line;
                    SwingUtilities.invokeLater(() -> terminalOutput.append(finalLine + "\n"));
                }

                while ((line = errorReader.readLine()) != null) {
                    String finalLine = line;
                    SwingUtilities.invokeLater(() -> terminalOutput.append("[INFO] " + finalLine + "\n"));
                }

                int exitCode = currentProcess.waitFor();
                SwingUtilities.invokeLater(() -> {
                    terminalOutput.append("[Exit code: " + exitCode + "]\n\n");

                    // Réactiver les boutons après l'exécution
                    if (command.startsWith("docker ") || command.contains("docker-compose")) {
                        checkDockerStatus(); // Vérifier l'état de Docker
                    }
                });

                // Refresh tables if command affects containers or images
                if (command.startsWith("docker") && (
                        command.contains("run") || command.contains("stop") || command.contains("start") ||
                                command.contains("rm") || command.contains("rmi") || command.contains("pull") ||
                                command.contains("build") || command.contains("compose"))) {
                    refreshContainers();
                    refreshImages();
                }

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    terminalOutput.append(e.getMessage() + "\n\n");
                    // Réactiver les boutons en cas d'erreur
                    checkDockerStatus();
                });
                log.error("Error executing command: {}", e.getMessage());
            }
        });
    }

    private void setDockerButtonsEnabled(boolean enabled) {
        // Désactiver tous les boutons qui dépendent de Docker SAUF "Compose Up" et "Compose Down"
        // qui doivent rester activés même pendant l'exécution
        updateAllDockerButtons(enabled);

        // Mettre à jour le bouton "Execute" dans le terminal
        Component[] components = getComponents();
        for (Component comp : components) {
            if (comp instanceof JToolBar toolbar) {
                for (Component btn : toolbar.getComponents()) {
                    if (btn instanceof JButton button) {
                        String text = button.getText();

                        // NE PAS désactiver "Compose Up" et "Compose Down" pendant l'exécution
                        if (text.equals("Execute") || text.equals("Build") || text.equals("Run")) {
                            button.setEnabled(enabled);
                        } else if (text.equals("Compose Up") || text.equals("Compose Down")) {
                            // Laisser ces boutons activés (ils seront gérés par updateAllDockerButtons)
                            continue;
                        }
                    }
                }
            }
        }
    }

    private void setupContainerTableSelection() {
        containerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = containerTable.getSelectedRow();
                if (row >= 0) {
                    ContainerInfo container = containerTableModel.getContainer(row);
                    updateContainerButtonsBasedOnStatus(container.status);
                } else {
                    // Aucune sélection - désactiver tous les boutons sauf Refresh
                    resetContainerButtons();
                }
            }
        });
    }

    private void updateContainerButtonsBasedOnStatus(String status) {
        // Trouver le panneau des conteneurs
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Containers")) {
                Component comp = tabbedPane.getComponentAt(i);
                if (comp instanceof JPanel) {
                    JPanel panel = (JPanel) comp;

                    // Chercher les boutons dans le panel sud
                    for (Component child : panel.getComponents()) {
                        if (child instanceof JPanel) {
                            JPanel actionPanel = (JPanel) child;
                            for (Component btn : actionPanel.getComponents()) {
                                if (btn instanceof JButton) {
                                    JButton button = (JButton) btn;
                                    String text = button.getText();

                                    // Logique pour activer/désactiver les boutons selon l'état
                                    if (status.contains("Up") || status.contains("running")) {
                                        // Container en cours d'exécution
                                        if (text.equals("Stop") || text.equals("Restart") ||
                                                text.equals("View Logs") || text.equals("Inspect")) {
                                            button.setEnabled(true);
                                        } else if (text.equals("Start")) {
                                            button.setEnabled(false);
                                        }
                                    } else {
                                        // Container arrêté
                                        if (text.equals("Start") || text.equals("Remove") ||
                                                text.equals("Inspect")) {
                                            button.setEnabled(true);
                                        } else if (text.equals("Stop") || text.equals("Restart") ||
                                                text.equals("View Logs")) {
                                            button.setEnabled(false);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }
    }


    private void resetContainerButtons() {
        // Réinitialiser tous les boutons à leur état par défaut
        updateContainerPanelButtons(isDockerRunning());
    }

    private boolean isDockerRunning() {
        // Vérifier si Docker est en cours d'exécution
        // Vous pouvez stocker cet état dans une variable de classe
        // ou vérifier dans la barre d'état
        return true; // À adapter selon votre logique
    }

    private void showContainerLogsDialog(ContainerInfo container) {
        System.out.println("Showing logs dialog for container: " + container.name);

        JDialog logsDialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                "Logs: " + container.name, true);
        logsDialog.setLayout(new BorderLayout());
        logsDialog.setSize(800, 600);
        logsDialog.setLocationRelativeTo(this);

        JTextArea logsArea = new JTextArea();
        logsArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logsArea.setEditable(false);

        JScrollPane scrollPane = new JScrollPane(logsArea);
        logsDialog.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> logsDialog.dispose());
        buttonPanel.add(closeBtn);

        JButton followBtn = new JButton("Follow");
        followBtn.addActionListener(e -> {
            String command = "docker logs -f " + container.id;
            executeCommandInTerminal(command);
        });
        buttonPanel.add(followBtn);

        logsDialog.add(buttonPanel, BorderLayout.SOUTH);

        // Afficher un message de chargement
        logsArea.setText("Loading logs for container: " + container.name + "\nPlease wait...");

        // Load logs in background
        executorService.submit(() -> {
            try {
                System.out.println("Executing: docker logs " + container.id);
                Process process = Runtime.getRuntime().exec("docker logs " + container.id);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                StringBuilder logs = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    logs.append(line).append("\n");
                }

                int exitCode = process.waitFor();
                System.out.println("Logs command exit code: " + exitCode);

                SwingUtilities.invokeLater(() -> {
                    if (logs.length() == 0) {
                        logsArea.setText("No logs available for container: " + container.name);
                    } else {
                        logsArea.setText(logs.toString());
                    }
                    logsArea.setCaretPosition(0);
                });

            } catch (Exception e) {
                System.err.println("Error loading logs: " + e.getMessage());
                e.printStackTrace();
                SwingUtilities.invokeLater(() ->
                        logsArea.setText("Error loading logs: " + e.getMessage()));
            }
        });

        logsDialog.setVisible(true);
        System.out.println("Logs dialog displayed");
    }

    private void loadDockerInfo() {
        checkDockerStatusWithRetry(5);
    }

    private void runContainer() {
        String image = JOptionPane.showInputDialog(this, "Enter image name to run:");
        if (image != null && !image.trim().isEmpty()) {
            String name = JOptionPane.showInputDialog(this, "Enter container name (optional):");
            String ports = JOptionPane.showInputDialog(this, "Enter port mapping (e.g., 8080:80, optional):");

            StringBuilder command = new StringBuilder("docker run -d");

            if (name != null && !name.trim().isEmpty()) {
                command.append(" --name ").append(name);
            }

            if (ports != null && !ports.trim().isEmpty()) {
                command.append(" -p ").append(ports);
            }

            command.append(" ").append(image);
            executeCommandInTerminal(command.toString());
        }
    }

    private void showQuickActions() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem cleanContainers = new JMenuItem("Clean All Stopped Containers");
        cleanContainers.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "This will remove all stopped containers. Continue?",
                    "Confirm Clean",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                executeCommandInTerminal("docker container prune -f");
            }
        });
        menu.add(cleanContainers);

        JMenuItem cleanImages = new JMenuItem("Remove Dangling Images");
        cleanImages.addActionListener(e -> {
            executeCommandInTerminal("docker image prune -f");
        });
        menu.add(cleanImages);

        JMenuItem systemPrune = new JMenuItem("System Prune");
        systemPrune.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "This will remove all unused containers, networks, images, and volumes. Continue?",
                    "Confirm System Prune",
                    JOptionPane.YES_NO_OPTION);

            if (confirm == JOptionPane.YES_OPTION) {
                executeCommandInTerminal("docker system prune -af");
            }
        });
        menu.add(systemPrune);

        menu.addSeparator();

        JMenuItem stats = new JMenuItem("Show Live Stats");
        stats.addActionListener(e -> {
            executeCommandInTerminal("docker stats");
        });
        menu.add(stats);

        JMenuItem checkHealth = new JMenuItem("Check Docker Health");
        checkHealth.addActionListener(e -> {
            executeCommandInTerminal("docker info");
        });
        menu.add(checkHealth);

        Component invoker = (Component) SwingUtilities.getWindowAncestor(this);
        menu.show(invoker, invoker.getWidth() - 240, 50);
    }

    private void showMessage(String message, String title, int messageType) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this, message, title, messageType);
        });
    }

    public void cleanup() {
        executorService.shutdown();
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroy();
        }
    }

    private void updateAllDockerButtons(boolean dockerRunning) {
        // Mettre à jour les boutons de la toolbar
        updateDockerButtonsState(dockerRunning);

        // Mettre à jour les boutons dans les panneaux
        updateContainerPanelButtons(dockerRunning);
        updateImagePanelButtons(dockerRunning);
        updateTerminalPanelButtons(dockerRunning);

        // Mettre à jour les boutons dans les onglets Dockerfile et Compose
        updateDockerfilePanelButtons(dockerRunning);
        updateComposePanelButtons(dockerRunning);
    }

    private void updateContainerPanelButtons(boolean dockerRunning) {
        // Trouver le panneau des conteneurs
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Containers")) {
                Component comp = tabbedPane.getComponentAt(i);
                if (comp instanceof JPanel) {
                    JPanel panel = (JPanel) comp;
                    updateButtonsInPanel(panel, dockerRunning);
                    break;
                }
            }
        }
    }

    private void updateImagePanelButtons(boolean dockerRunning) {
        // Trouver le panneau des images
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Images")) {
                Component comp = tabbedPane.getComponentAt(i);
                if (comp instanceof JPanel) {
                    JPanel panel = (JPanel) comp;
                    updateButtonsInPanel(panel, dockerRunning);
                    break;
                }
            }
        }
    }

    private void updateDockerfilePanelButtons(boolean dockerRunning) {
        // Trouver le panneau Dockerfile
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Dockerfile")) {
                Component comp = tabbedPane.getComponentAt(i);
                if (comp instanceof JPanel) {
                    JPanel panel = (JPanel) comp;
                    // Le bouton "Build Image" doit être désactivé si Docker n'est pas en cours d'exécution
                    updateButtonsInPanel(panel, dockerRunning);
                    break;
                }
            }
        }
    }

    private void updateComposePanelButtons(boolean dockerRunning) {
        // Trouver le panneau Compose
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Compose")) {
                Component comp = tabbedPane.getComponentAt(i);
                if (comp instanceof JPanel) {
                    JPanel panel = (JPanel) comp;
                    updateButtonsInPanel(panel, dockerRunning);
                    break;
                }
            }
        }
    }

    private void updateTerminalPanelButtons(boolean dockerRunning) {
        // Trouver le panneau Terminal
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getTitleAt(i).equals("Terminal")) {
                Component comp = tabbedPane.getComponentAt(i);
                if (comp instanceof JPanel) {
                    JPanel panel = (JPanel) comp;
                    // Désactiver le bouton "Execute" si Docker n'est pas en cours d'exécution
                    // (pour les commandes Docker seulement)
                    updateButtonsInPanel(panel, dockerRunning);
                    break;
                }
            }
        }
    }

    private void updateButtonsInPanel(JPanel panel, boolean dockerRunning) {
        // Parcourir tous les composants du panel
        for (Component comp : panel.getComponents()) {
            if (comp instanceof JButton) {
                JButton button = (JButton) comp;
                String text = button.getText();

                // CORRECTION ICI : La logique était trop complexe

                // Boutons qui dépendent de Docker étant en cours d'exécution
                String[] dockerDependentButtons = {
                        "Start", "Stop", "Restart", "Remove", "Inspect", "View Logs",
                        "Pull Image", "Run", "Build Image", "Validate", "Execute", "Build"
                };

                boolean isDockerDependent = false;
                for (String btnText : dockerDependentButtons) {
                    if (text.contains(btnText)) {
                        isDockerDependent = true;
                        break;
                    }
                }

                // Si c'est un bouton qui dépend de Docker, l'activer/désactiver selon l'état
                if (isDockerDependent) {
                    button.setEnabled(dockerRunning);
                }

                // Le bouton "Refresh" est toujours activé
                if (text.equals("Refresh")) {
                    button.setEnabled(true);
                }

                // IMPORTANT: "Compose Up" et "Compose Down" sont gérés par updateDockerButtonsState()
                // Pas besoin de les traiter ici

            } else if (comp instanceof JPanel) {
                updateButtonsInPanel((JPanel) comp, dockerRunning);
            } else if (comp instanceof Container) {
                // Vérifier les composants enfants
                for (Component child : ((Container) comp).getComponents()) {
                    if (child instanceof JPanel) {
                        updateButtonsInPanel((JPanel) child, dockerRunning);
                    }
                }
            }
        }
    }


    public void setProjectRoot(File projectRoot) {
        this.projectRoot = projectRoot;
        this.currentDirectory = projectRoot;
        // Rafraîchir l'UI avec le nouveau projet
        SwingUtilities.invokeLater(() -> {
            loadFileTree();
            refreshContainers();
            refreshImages();
            terminalOutput.append(">>> Project changed to: " + projectRoot.getName() + "\n");
            terminalOutput.append(">>> Directory: " + currentDirectory.getAbsolutePath() + "\n\n");
        });
    }
}