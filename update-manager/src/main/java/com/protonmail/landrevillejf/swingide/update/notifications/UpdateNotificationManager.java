package com.protonmail.landrevillejf.swingide.update.notifications;

import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages system notifications for update events using platform-native APIs.
 * Supports Windows (balloon tips), macOS (Notification Center), and Linux (D-Bus notifications).
 * <p>
 * Every method is safe to call when no system tray is available (headless
 * environments, window managers without a notification area): the notification
 * is then simply logged and dropped.
 * </p>
 */
@Slf4j
public class UpdateNotificationManager {

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_MACOS = OS_NAME.contains("mac");
    private static final boolean IS_LINUX = OS_NAME.contains("linux");

    private static final int TRAY_ICON_SIZE = 16;
    private static final int MAX_TITLE_LENGTH = 50;
    private static final int MAX_MESSAGE_LENGTH = 200;

    private final Map<String, NotificationConfig> notifications;
    private final SystemTray systemTray;
    private final boolean traySupported;
    private TrayIcon trayIcon;
    private boolean trayEnabled;
    private TrayActionListener actionListener;

    public UpdateNotificationManager() {
        this.notifications = new HashMap<>();
        this.systemTray = resolveSystemTray();
        this.traySupported = systemTray != null;
        this.trayEnabled = traySupported;
    }

    private static SystemTray resolveSystemTray() {
        if (GraphicsEnvironment.isHeadless()) {
            log.debug("Headless environment, system tray notifications disabled");
            return null;
        }
        try {
            return SystemTray.isSupported() ? SystemTray.getSystemTray() : null;
        } catch (RuntimeException e) {
            log.warn("System tray unavailable: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Initialize system tray with a generated default icon.
     */
    public void initializeTray() {
        initializeTray(createDefaultTrayImage());
    }

    /**
     * Initialize system tray with update icon.
     * <p>
     * Idempotent: the icon is added once, and can be added again after
     * {@link #removeTray()} so the desktop notifications setting stays reversible.
     * </p>
     */
    public void initializeTray(Image trayImage) {
        if (!traySupported || trayIcon != null || systemTray == null) {
            return;
        }

        try {
            trayIcon = new TrayIcon(trayImage, "Project Looking Glass Update Manager", createTrayMenu());
            trayIcon.setImageAutoSize(true);

            systemTray.add(trayIcon);
            trayEnabled = true;

            log.info("System tray initialized successfully");

        } catch (AWTException | IllegalArgumentException e) {
            log.warn("Failed to initialize system tray", e);
            trayEnabled = false;
            trayIcon = null;
        }
    }

    private PopupMenu createTrayMenu() {
        PopupMenu popup = new PopupMenu();

        MenuItem checkUpdates = new MenuItem("Check for Updates");
        checkUpdates.setActionCommand("check-updates");
        checkUpdates.addActionListener(event -> fireCheckUpdates());

        MenuItem changelog = new MenuItem("View Changelog");
        changelog.setActionCommand("view-changelog");
        changelog.addActionListener(event -> fireShowChangelog());

        MenuItem settings = new MenuItem("Update Settings");
        settings.setActionCommand("update-settings");
        settings.addActionListener(event -> fireShowSettings());

        popup.add(checkUpdates);
        popup.addSeparator();
        popup.add(changelog);
        popup.add(settings);

        return popup;
    }

    /**
     * Simple arrow-free icon drawn at runtime so the tray works without any
     * packaged image resource.
     */
    private Image createDefaultTrayImage() {
        BufferedImage image = new BufferedImage(TRAY_ICON_SIZE, TRAY_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0x3C, 0x82, 0xF6));
            graphics.fillOval(0, 0, TRAY_ICON_SIZE - 1, TRAY_ICON_SIZE - 1);
            graphics.setColor(Color.WHITE);
            graphics.drawLine(TRAY_ICON_SIZE / 2, 3, TRAY_ICON_SIZE / 2, TRAY_ICON_SIZE - 5);
            graphics.drawLine(TRAY_ICON_SIZE / 2 - 3, TRAY_ICON_SIZE - 8, TRAY_ICON_SIZE / 2, TRAY_ICON_SIZE - 4);
            graphics.drawLine(TRAY_ICON_SIZE / 2 + 3, TRAY_ICON_SIZE - 8, TRAY_ICON_SIZE / 2, TRAY_ICON_SIZE - 4);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * Registers the host callbacks invoked from the tray popup menu.
     */
    public void setTrayActionListener(TrayActionListener actionListener) {
        this.actionListener = actionListener;
    }

    private void fireCheckUpdates() {
        TrayActionListener current = this.actionListener;
        if (current != null) {
            current.onCheckForUpdates();
        } else {
            log.debug("No tray action listener registered, ignoring 'check for updates'");
        }
    }

    private void fireShowSettings() {
        TrayActionListener current = this.actionListener;
        if (current != null) {
            current.onShowSettings();
        } else {
            log.debug("No tray action listener registered, ignoring 'update settings'");
        }
    }

    private void fireShowChangelog() {
        TrayActionListener current = this.actionListener;
        if (current != null) {
            current.onShowChangelog();
        } else {
            log.debug("No tray action listener registered, ignoring 'view changelog'");
        }
    }

    /**
     * Show update available notification.
     */
    public void notifyUpdateAvailable(String version, String downloadSize) {
        String message = String.format("Update %s available (%s)", version, downloadSize);
        showNotification("Update Available", message, TrayIcon.MessageType.INFO);
    }

    /**
     * Show download progress notification.
     */
    public void notifyDownloadProgress(String version, int progressPercent) {
        String message = String.format("Downloading %s... %d%%", version, progressPercent);
        showNotification("Downloading Update", message, TrayIcon.MessageType.NONE);
    }

    /**
     * Show download complete notification.
     */
    public void notifyDownloadComplete(String version) {
        String message = String.format("Update %s downloaded successfully", version);
        showNotification("Download Complete", message, TrayIcon.MessageType.INFO);
    }

    /**
     * Show installation in progress notification.
     */
    public void notifyInstallationStarted(String version) {
        String message = String.format("Installing update %s...", version);
        showNotification("Installing Update", message, TrayIcon.MessageType.NONE);
    }

    /**
     * Show installation complete notification with auto-restart prompt.
     */
    public void notifyInstallationComplete(String version, boolean autoRestart) {
        String message = autoRestart
            ? String.format("Update %s installed. Restarting in 60 seconds...", version)
            : String.format("Update %s installed. Please restart the application.", version);
        showNotification("Installation Complete", message, TrayIcon.MessageType.INFO);
    }

    /**
     * Show verification failure notification.
     */
    public void notifyVerificationFailed(String reason) {
        String message = String.format("Update verification failed: %s", reason);
        showNotification("Security Warning", message, TrayIcon.MessageType.WARNING);
    }

    /**
     * Show a neutral informational notification.
     */
    public void notifyInfo(String title, String message) {
        showNotification(title, message, TrayIcon.MessageType.INFO);
    }

    /**
     * Show critical error notification.
     */
    public void notifyError(String title, String message) {
        showNotification(title, message, TrayIcon.MessageType.ERROR);
    }

    /**
     * Register custom notification config.
     */
    public void registerNotification(String notificationId, NotificationConfig config) {
        notifications.put(notificationId, config);
    }

    /**
     * Show custom notification.
     */
    public void showCustomNotification(String notificationId, String title, String message) {
        NotificationConfig config = notifications.get(notificationId);
        if (config != null) {
            showNotification(title, message, config.getMessageType());
        } else {
            log.debug("Unknown notification id '{}', nothing displayed", notificationId);
        }
    }

    /**
     * Internal method to show notification with platform-specific implementation.
     */
    private void showNotification(String title, String message, TrayIcon.MessageType messageType) {
        if (!trayEnabled || trayIcon == null) {
            log.debug("Tray not available, skipping notification: {} - {}", title, message);
            return;
        }

        try {
            String displayTitle = limitLength(title, MAX_TITLE_LENGTH);
            String displayMessage = limitLength(message, MAX_MESSAGE_LENGTH);

            if (IS_WINDOWS) {
                showWindowsNotification(displayTitle, displayMessage, messageType);
            } else if (IS_MACOS) {
                showMacOSNotification(displayTitle, displayMessage, messageType);
            } else if (IS_LINUX) {
                showLinuxNotification(displayTitle, displayMessage, messageType);
            } else {
                trayIcon.displayMessage(displayTitle, displayMessage, messageType);
            }

            log.debug("Notification shown: {} - {}", displayTitle, displayMessage);

        } catch (RuntimeException e) {
            log.warn("Failed to show notification", e);
        }
    }

    private void showWindowsNotification(String title, String message, TrayIcon.MessageType messageType) {
        trayIcon.displayMessage(title, message, messageType);
    }

    private void showMacOSNotification(String title, String message, TrayIcon.MessageType messageType) {
        try {
            String script = String.format(
                "display notification \"%s\" with title \"%s\"",
                message.replace("\"", "\\\""),
                title.replace("\"", "\\\"")
            );

            ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
            pb.start();

        } catch (Exception e) {
            log.debug("Failed to show macOS notification", e);
            trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        }
    }

    private void showLinuxNotification(String title, String message, TrayIcon.MessageType messageType) {
        try {
            String urgency = switch (messageType) {
                case ERROR -> "critical";
                case WARNING -> "normal";
                default -> "low";
            };

            ProcessBuilder pb = new ProcessBuilder(
                "notify-send",
                "-u", urgency,
                "-t", "5000",
                title,
                message
            );
            pb.start();

        } catch (Exception e) {
            log.debug("Failed to show Linux notification", e);
            trayIcon.displayMessage(title, message, messageType);
        }
    }

    /**
     * Remove tray icon and cleanup resources.
     * <p>
     * This method never blocks its caller. {@code SystemTray.remove} disposes an
     * AWT window and, when it is invoked from any thread other than the event
     * dispatch thread, AWT waits for the EDT to process the disposal. A shutdown
     * hook doing that while the EDT is itself inside {@code System.exit} - which
     * joins every hook - deadlocks and the JVM never terminates. The removal is
     * therefore posted to the EDT instead of being awaited, while the local state
     * is cleared immediately so the tray is considered gone from now on.
     * </p>
     */
    public void removeTray() {
        if (!trayEnabled || trayIcon == null || systemTray == null) {
            return;
        }

        TrayIcon icon = trayIcon;
        trayIcon = null;
        trayEnabled = false;

        detachWithoutBlocking(systemTray, icon);
    }

    /**
     * Detaches an icon from the tray without ever blocking the calling thread:
     * the removal runs synchronously on the EDT and is only posted to it from any
     * other thread.
     *
     * @param tray the system tray owning the icon
     * @param icon the icon to remove
     */
    void detachWithoutBlocking(SystemTray tray, TrayIcon icon) {
        if (SwingUtilities.isEventDispatchThread()) {
            detachTray(tray, icon);
        } else {
            SwingUtilities.invokeLater(() -> detachTray(tray, icon));
        }
    }

    /**
     * Detaches an icon from the system tray, on the event dispatch thread.
     *
     * @param tray the system tray owning the icon
     * @param icon the icon to remove
     */
    private void detachTray(SystemTray tray, TrayIcon icon) {
        try {
            tray.remove(icon);
            log.info("System tray removed");
        } catch (RuntimeException e) {
            log.warn("Failed to remove tray icon", e);
        }
    }

    /**
     * Check if tray notifications are available.
     */
    public boolean isTrayAvailable() {
        return trayEnabled && trayIcon != null;
    }

    /**
     * Whether the platform exposes a usable system tray, independently of the
     * current icon state.
     */
    public boolean isTraySupported() {
        return traySupported;
    }

    /**
     * Truncates a notification text so platform limits are respected.
     */
    String limitLength(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength
            ? text.substring(0, maxLength - 3) + "..."
            : text;
    }

    /**
     * Host callbacks triggered from the tray popup menu. All methods have a
     * default no-op implementation.
     */
    public interface TrayActionListener {
        default void onCheckForUpdates() { }
        default void onShowSettings() { }
        default void onShowChangelog() { }
    }

    /**
     * Configuration for custom notifications.
     */
    public static class NotificationConfig {
        private final TrayIcon.MessageType messageType;
        private final int timeout;

        public NotificationConfig(TrayIcon.MessageType messageType, int timeout) {
            this.messageType = messageType;
            this.timeout = timeout;
        }

        public TrayIcon.MessageType getMessageType() { return messageType; }
        public int getTimeout() { return timeout; }
    }
}
