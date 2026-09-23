package com.protonmail.landrevillejf.swingide.update.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;

/**
 * Displays a rendered changelog produced by
 * {@code com.protonmail.landrevillejf.swingide.update.changelog.ChangelogManager}.
 */
public class ChangelogDialog extends JDialog {

    private static final String DEFAULT_TITLE = "Changelog";

    /**
     * Creates a modal changelog dialog.
     *
     * @param parent the owning window (frame or dialog), may be {@code null}
     * @param title  the dialog title
     * @param html   the rendered changelog
     */
    public ChangelogDialog(Window parent, String title, String html) {
        super(parent, title != null && !title.isBlank() ? title : DEFAULT_TITLE,
            Dialog.ModalityType.APPLICATION_MODAL);
        initializeUI(html);
    }

    private void initializeUI(String html) {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        mainPanel.add(createChangelogView(html), BorderLayout.CENTER);
        mainPanel.add(createButtonPanel(), BorderLayout.SOUTH);

        add(mainPanel);
        setSize(640, 480);
        setMinimumSize(new Dimension(420, 300));
        setLocationRelativeTo(getParent());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton closeButton = new JButton("Close");
        closeButton.setPreferredSize(new Dimension(100, 30));
        closeButton.addActionListener(this::onCloseClicked);
        panel.add(closeButton);

        return panel;
    }

    private void onCloseClicked(ActionEvent event) {
        dispose();
    }

    /**
     * Builds the scrollable HTML view. Extracted so the component can be
     * embedded in another container (preferences dialog, welcome panel, ...).
     *
     * @param html the rendered changelog
     * @return a scroll pane displaying the changelog
     */
    public static JScrollPane createChangelogView(String html) {
        JEditorPane editorPane = new JEditorPane();
        editorPane.setEditable(false);
        editorPane.setContentType("text/html");
        editorPane.setText(html != null && !html.isBlank()
            ? html
            : "<html><body><p>No changelog available.</p></body></html>");
        editorPane.setCaretPosition(0);

        JScrollPane scrollPane = new JScrollPane(editorPane);
        scrollPane.setPreferredSize(new Dimension(600, 380));
        return scrollPane;
    }

    /**
     * Shows the changelog dialog on the event dispatch thread.
     *
     * @param parent the owning window (frame or dialog), may be {@code null}
     * @param title  the dialog title
     * @param html   the rendered changelog
     */
    public static void show(Window parent, String title, String html) {
        Runnable display = () -> {
            ChangelogDialog dialog = new ChangelogDialog(parent, title, html);
            dialog.setVisible(true);
        };

        if (SwingUtilities.isEventDispatchThread()) {
            display.run();
        } else {
            SwingUtilities.invokeLater(display);
        }
    }
}
