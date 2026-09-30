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
package org.jdesktop.lg3d.apps.ssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SshPanel extends JPanel {

    private final JTextField hostField = new JTextField("localhost", 15);
    private final JTextField userField = new JTextField(System.getProperty("user.name"), 10);
    private final JPasswordField passField = new JPasswordField(10);
    private final JTextField portField = new JTextField("22", 4);

    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");

    private final JTextArea terminalArea = new JTextArea();
    private final JTextField commandField = new JTextField();

    // Java 21 Virtual Thread Executor for non-blocking I/O
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private Session session;
    private ChannelShell channel;
    private OutputStream commandStream;

    private Runnable onClose;

    public SshPanel() {
        setupUI();
        setupListeners();
    }

    private void setupUI() {
        setLayout(new BorderLayout());

        // Top Configuration Panel
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        topPanel.add(new JLabel("Host:"));
        topPanel.add(hostField);
        topPanel.add(new JLabel("Port:"));
        topPanel.add(portField);
        topPanel.add(new JLabel("Username:"));
        topPanel.add(userField);
        topPanel.add(new JLabel("Password:"));
        topPanel.add(passField);
        topPanel.add(connectButton);
        topPanel.add(disconnectButton);
        disconnectButton.setEnabled(false);
        add(topPanel, BorderLayout.NORTH);

        // Center Terminal View
        terminalArea.setEditable(false);
        terminalArea.setBackground(Color.BLACK);
        terminalArea.setForeground(Color.GREEN);
        terminalArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        JScrollPane scrollPane = new JScrollPane(terminalArea);
        add(scrollPane, BorderLayout.CENTER);

        // Bottom Input Panel
        JPanel bottomPanel = new JPanel(new BorderLayout());
        commandField.setFont(new Font("Monospaced", Font.PLAIN, 14));
        commandField.setEnabled(false);
        bottomPanel.add(new JLabel(" Command: "), BorderLayout.WEST);
        bottomPanel.add(commandField, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void setupListeners() {
        connectButton.addActionListener(e -> connectSsh());
        disconnectButton.addActionListener(e -> disconnectSsh());

        // Execute commands on Enter key press
        commandField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    sendTerminalCommand();
                }
            }
        });
    }

    private void connectSsh() {
        String host = hostField.getText().trim();
        int port = Integer.parseInt(portField.getText().trim());
        String username = userField.getText().trim();
        String password = new String(passField.getPassword());

        connectButton.setEnabled(false);
        terminalArea.append("Connecting to " + host + "...\n");

        // Offload network connection to a JDK 21 Virtual Thread
        executor.submit(() -> {
            try {
                JSch jsch = new JSch();
                session = jsch.getSession(username, host, port);
                session.setPassword(password);

                Properties config = new Properties();
                config.put("StrictHostKeyChecking", "no"); // For test/local envs
                session.setConfig(config);

                session.connect(15000); // 15-second timeout

                // Open interactive shell channel
                channel = (ChannelShell) session.openChannel("shell");

                // Get command stream hook
                commandStream = channel.getOutputStream();

                // Fire stream reader task on another virtual thread
                InputStream inputStream = channel.getInputStream();
                executor.submit(() -> readChannelOutput(inputStream));

                channel.connect();

                // Update UI on Swing Event Dispatch Thread (EDT)
                SwingUtilities.invokeLater(() -> {
                    disconnectButton.setEnabled(true);
                    commandField.setEnabled(true);
                    commandField.requestFocusInWindow();
                    terminalArea.append("--- Connected Successfully ---\n");
                });

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    JOptionPane.showMessageDialog(this, "Connection Failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    terminalArea.append("Connection failed.\n");
                });
            }
        });
    }

    private void readChannelOutput(InputStream in) {
        byte[] buffer = new byte[1024];
        try {
            while (channel != null && channel.isConnected()) {
                int read = in.read(buffer);
                if (read == -1) break;

                String output = new String(buffer, 0, read, StandardCharsets.UTF_8);
                // Dynamically update terminal UI
                SwingUtilities.invokeLater(() -> {
                    terminalArea.append(output);
                    terminalArea.setCaretPosition(terminalArea.getDocument().getLength());
                });
            }
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> terminalArea.append("\n[Disconnected from Stream]\n"));
        }
    }

    private void sendTerminalCommand() {
        String cmd = commandField.getText();
        if (commandStream != null) {
            executor.submit(() -> {
                try {
                    // Send command followed by system carriage return
                    commandStream.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
                    commandStream.flush();
                    SwingUtilities.invokeLater(() -> commandField.setText(""));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> terminalArea.append("\nFailed to send command: " + ex.getMessage() + "\n"));
                }
            });
        }
    }

    private void disconnectSsh() {
        executor.submit(() -> {
            try {
                if (channel != null && channel.isConnected()) channel.disconnect();
                if (session != null && session.isConnected()) session.disconnect();
            } finally {
                SwingUtilities.invokeLater(() -> {
                    connectButton.setEnabled(true);
                    disconnectButton.setEnabled(false);
                    commandField.setEnabled(false);
                    terminalArea.append("\n--- Connection Closed ---\n");
                });
            }
        });
    }

    /**
     * Clean up resources when the panel is removed or application closed.
     */
    public void shutdown() {
        disconnectSsh();
        executor.shutdown();
    }

    /** Sets the callback invoked when the user presses Close. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }
}
