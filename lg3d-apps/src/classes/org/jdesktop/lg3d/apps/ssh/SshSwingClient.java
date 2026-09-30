package org.jdesktop.lg3d.apps.ssh;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class SshSwingClient extends JFrame {

    public SshSwingClient() {
        super("Java 21 Swing SSH Client (JSch)");
        setupUI();
    }

    private void setupUI() {
        setSize(900, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Instanciation et ajout du Panel SSH
        SshPanel sshPanel = new SshPanel();
        add(sshPanel, BorderLayout.CENTER);

        // Optionnel : s'assurer de couper proprement les connexions lors de la fermeture de la fenêtre
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                sshPanel.shutdown();
            }
        });
    }

    public static void main(String[] args) {
        // Enforce modern native system Look and Feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new SshSwingClient().setVisible(true));
    }
}
