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
package org.jdesktop.lg3d.apps.passwordmanager;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.List;
import javax.crypto.SecretKey;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The Password Manager's user interface: a <em>lock</em> card (create a master
 * password the first time, or unlock an existing vault) and a <em>vault</em> card
 * with a searchable entry list, a detail editor, a password generator and a
 * settings tab. One panel serves both the 3D desktop (hosted on a SwingNode
 * inside a Frame3D by the {@code PasswordManager} wrapper) and the 2D/Swing
 * desktop (opened as an MDI internal frame via
 * {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>The desktop ships no secret store of its own, so the vault is sealed with
 * the JDK's own crypto through {@link VaultCrypto} (PBKDF2-HMAC-SHA256 +
 * AES-256/GCM) and only the ciphertext is persisted by {@link VaultStore}. The
 * derived key and the decrypted entries live in memory only while unlocked; on
 * Lock - manual or after the auto-lock timeout - the vault, the key and every
 * detail field are cleared. No dialog, no filesystem write and no timer runs
 * until the user acts, so the panel constructs and is asserted on headless.</p>
 */
public class PasswordManagerPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 860;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 560;

    private static final String LOCK_CARD = "lock";
    private static final String MAIN_CARD = "main";

    private static final Color GOOD = new Color(0, 140, 0);
    private static final Color ATTENTION = new Color(200, 80, 0);
    private static final Color MUTED = new Color(90, 90, 90);

    private final VaultStore store;
    private final PasswordManagerSettings settings;
    private final SecureRandom random = new SecureRandom();

    private VaultEnvelope envelope;
    private PasswordVault vault;
    private SecretKey sessionKey;
    private boolean locked = true;

    // Root cards.
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);

    // Lock card.
    private final JLabel lockTitle = new JLabel("Unlock your vault", JLabel.CENTER);
    private final JPasswordField masterField = new JPasswordField(20);
    private final JLabel confirmLabel = new JLabel("Confirm:");
    private final JPasswordField confirmField = new JPasswordField(20);
    private final JButton lockActionButton = new JButton("Unlock");
    private final JLabel lockHint = new JLabel(" ", JLabel.CENTER);

    // Vault tab.
    private final JTextField searchField = new JTextField(16);
    private final JComboBox<String> categoryBox = new JComboBox<>();
    private final DefaultListModel<PasswordEntry> entryModel = new DefaultListModel<>();
    private final JList<PasswordEntry> entryList = new JList<>(entryModel);
    private final JTextField titleField = new JTextField(20);
    private final JTextField userField = new JTextField(20);
    private final JPasswordField passField = new JPasswordField(20);
    private final JButton showPassBtn = new JButton("Show");
    private final JButton genIntoEntryBtn = new JButton("Generate");
    private final JTextField urlField = new JTextField(20);
    private final JTextField categoryField = new JTextField(14);
    private final JTextArea notesArea = new JTextArea(4, 20);
    private final JButton newBtn = new JButton("New");
    private final JButton saveBtn = new JButton("Save");
    private final JButton addBtn = new JButton("Add");
    private final JButton removeBtn = new JButton("Remove");
    private final JLabel entryStrength = new JLabel(" ");

    // Generator tab.
    private final JSpinner lengthSpinner =
            new JSpinner(new SpinnerNumberModel(PasswordManagerSettings.DEFAULT_LENGTH,
                    PasswordManagerSettings.MIN_LENGTH,
                    PasswordManagerSettings.MAX_LENGTH, 1));
    private final JCheckBox upperCheck = new JCheckBox("A-Z", true);
    private final JCheckBox lowerCheck = new JCheckBox("a-z", true);
    private final JCheckBox digitCheck = new JCheckBox("0-9", true);
    private final JCheckBox symbolCheck = new JCheckBox("!@#", true);
    private final JTextField genResult = new JTextField(28);
    private final JLabel genStrength = new JLabel(" ", JLabel.CENTER);

    // Settings tab.
    private final JSpinner autoLockSpinner =
            new JSpinner(new SpinnerNumberModel(PasswordManagerSettings.DEFAULT_AUTOLOCK_MINUTES,
                    0, 120, 1));
    private final JCheckBox maskCheck = new JCheckBox("Mask passwords by default", true);

    // Bottom bar.
    private final JLabel statusLabel = new JLabel("Locked");
    private final JButton lockBtn = new JButton("Lock");
    private volatile String statusMessage = "Locked";
    private Timer autoLockTimer;
    private Runnable onClose;

    /** Builds the panel with the default store. */
    public PasswordManagerPanel() {
        this(new VaultStore());
    }

    /**
     * Builds the panel over an explicit store (package-private for tests).
     *
     * @param store the vault / settings store
     */
    PasswordManagerPanel(VaultStore store) {
        this.store = store;
        this.settings = store.loadSettings();
        this.envelope = store.loadEnvelope();

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        cardPanel.add(buildLockCard(), LOCK_CARD);
        cardPanel.add(buildMainCard(), MAIN_CARD);
        add(cardPanel, BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);

        applySettingsToWidgets();
        wireListeners();
        showLockCard();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildLockCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.EAST;

        lockTitle.setFont(lockTitle.getFont().deriveFont(Font.BOLD, 20f));
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        panel.add(lockTitle, c);

        c.gridwidth = 1;
        c.gridy = 1;
        c.anchor = GridBagConstraints.EAST;
        panel.add(new JLabel("Master password:"), c);
        c.gridx = 1;
        c.anchor = GridBagConstraints.WEST;
        panel.add(masterField, c);

        c.gridx = 0;
        c.gridy = 2;
        c.anchor = GridBagConstraints.EAST;
        panel.add(confirmLabel, c);
        c.gridx = 1;
        c.anchor = GridBagConstraints.WEST;
        panel.add(confirmField, c);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        panel.add(lockActionButton, c);

        lockHint.setForeground(MUTED);
        c.gridy = 4;
        panel.add(lockHint, c);
        return panel;
    }

    private Component buildMainCard() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Vault", buildVaultTab());
        tabs.addTab("Generator", buildGeneratorTab());
        tabs.addTab("Settings", buildSettingsTab());
        return tabs;
    }

    private Component buildVaultTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(buildEntryDock(), BorderLayout.WEST);
        panel.add(buildDetailEditor(), BorderLayout.CENTER);
        return panel;
    }

    private Component buildEntryDock() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Entries"));
        panel.setPreferredSize(new Dimension(250, 100));

        JPanel search = new JPanel(new BorderLayout(4, 4));
        search.add(new JLabel("Search:"), BorderLayout.WEST);
        search.add(searchField, BorderLayout.CENTER);
        panel.add(search);

        JPanel cat = new JPanel(new BorderLayout(4, 4));
        cat.add(new JLabel("Category:"), BorderLayout.WEST);
        categoryBox.setPreferredSize(new Dimension(150, 26));
        cat.add(categoryBox, BorderLayout.CENTER);
        panel.add(cat);

        entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(entryList);
        scroll.setPreferredSize(new Dimension(240, 260));
        panel.add(scroll);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        buttons.add(addBtn);
        buttons.add(removeBtn);
        panel.add(buttons);
        return panel;
    }

    private Component buildDetailEditor() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Details"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addFormRow(panel, c, row++, "Title:", titleField);
        addFormRow(panel, c, row++, "Username:", userField);

        c.gridx = 0;
        c.gridy = row;
        panel.add(new JLabel("Password:"), c);
        JPanel passRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        passRow.add(passField);
        passRow.add(showPassBtn);
        passRow.add(genIntoEntryBtn);
        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(passRow, c);
        c.weightx = 0;
        row++;

        c.gridx = 1;
        c.gridy = row++;
        panel.add(entryStrength, c);

        addFormRow(panel, c, row++, "URL:", urlField);
        addFormRow(panel, c, row++, "Category:", categoryField);

        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        panel.add(new JLabel("Notes:"), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.weighty = 1.0;
        c.fill = GridBagConstraints.BOTH;
        panel.add(new JScrollPane(notesArea), c);
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        row++;

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        actions.add(newBtn);
        actions.add(saveBtn);
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        panel.add(actions, c);
        return panel;
    }

    private void addFormRow(JPanel panel, GridBagConstraints c, int row,
                            String label, JTextField field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, c);
    }

    private Component buildGeneratorTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        opts.setBorder(BorderFactory.createTitledBorder("Policy"));
        opts.add(new JLabel("Length:"));
        lengthSpinner.setPreferredSize(new Dimension(70, 26));
        opts.add(lengthSpinner);
        opts.add(upperCheck);
        opts.add(lowerCheck);
        opts.add(digitCheck);
        opts.add(symbolCheck);
        opts.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        panel.add(opts);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        JButton generate = new JButton("Generate");
        generate.addActionListener(e -> generateStandalone());
        actions.add(generate);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> copyText(genResult.getText()));
        actions.add(copy);
        panel.add(actions);

        genResult.setEditable(false);
        genResult.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        JPanel result = new JPanel(new BorderLayout(6, 6));
        result.setBorder(BorderFactory.createTitledBorder("Result"));
        result.add(genResult, BorderLayout.CENTER);
        result.add(genStrength, BorderLayout.SOUTH);
        result.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        panel.add(result);

        panel.add(Box.createVerticalGlue());
        JLabel note = new JLabel("<html><i>Passwords are generated locally with "
                + "java.security.SecureRandom; nothing leaves this machine.</i></html>");
        note.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        panel.add(note);
        return panel;
    }

    private Component buildSettingsTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        panel.add(new JLabel("Auto-lock after (minutes, 0 = never):"), c);
        c.gridx = 1;
        autoLockSpinner.setPreferredSize(new Dimension(80, 26));
        panel.add(autoLockSpinner, c);

        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 2;
        panel.add(maskCheck, c);

        JButton saveSettings = new JButton("Save settings");
        saveSettings.addActionListener(e -> saveSettings());
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        panel.add(saveSettings, c);

        c.gridy = 3;
        c.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), c);
        return panel;
    }

    private Component buildBottomBar() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        lockBtn.addActionListener(e -> lock());
        lockBtn.setEnabled(false);
        right.add(lockBtn);
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            lock();
            if (onClose != null) {
                onClose.run();
            }
        });
        right.add(close);
        panel.add(right, BorderLayout.NORTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private void applySettingsToWidgets() {
        lengthSpinner.setValue(settings.getGeneratorLength());
        upperCheck.setSelected(settings.isGenUpper());
        lowerCheck.setSelected(settings.isGenLower());
        digitCheck.setSelected(settings.isGenDigits());
        symbolCheck.setSelected(settings.isGenSymbols());
        autoLockSpinner.setValue(settings.getAutoLockMinutes());
        maskCheck.setSelected(settings.isMaskByDefault());
        applyMask();
    }

    private void wireListeners() {
        lockActionButton.addActionListener(e -> doLockAction());
        masterField.addActionListener(e -> doLockAction());
        confirmField.addActionListener(e -> doLockAction());
        addBtn.addActionListener(e -> newEntry());
        removeBtn.addActionListener(e -> removeSelected());
        newBtn.addActionListener(e -> newEntry());
        saveBtn.addActionListener(e -> saveSelected());
        showPassBtn.addActionListener(e -> togglePasswordVisible());
        genIntoEntryBtn.addActionListener(e -> generateIntoEntry());
        searchField.getDocument().addDocumentListener(simpleDoc(e -> refreshEntryList()));
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                refreshEntryList();
            }
        });
        categoryBox.addActionListener(e -> refreshEntryList());
        entryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelectedIntoDetail();
            }
        });
        passField.getDocument().addDocumentListener(simpleDoc(e -> updateEntryStrength()));
    }

    private static javax.swing.event.DocumentListener simpleDoc(
            java.util.function.Consumer<javax.swing.event.DocumentEvent> handler) {
        return new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                handler.accept(e);
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                handler.accept(e);
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                handler.accept(e);
            }
        };
    }

    // ------------------------------------------------------------------
    // Lock / unlock
    // ------------------------------------------------------------------

    private void showLockCard() {
        boolean creating = !envelope.isPresent();
        lockTitle.setText(creating ? "Create a master password" : "Unlock your vault");
        lockActionButton.setText(creating ? "Create Vault" : "Unlock");
        confirmLabel.setVisible(creating);
        confirmField.setVisible(creating);
        lockHint.setText(creating
                ? "Pick a strong master password - it cannot be recovered if lost."
                : "Enter your master password to unlock.");
        masterField.setText("");
        confirmField.setText("");
        cards.show(cardPanel, LOCK_CARD);
        lockBtn.setEnabled(false);
        locked = true;
    }

    private void doLockAction() {
        char[] master = masterField.getPassword();
        if (envelope.isPresent()) {
            boolean ok = unlock(master);
            if (!ok) {
                setStatus("Incorrect master password.");
            }
        } else {
            char[] confirm = confirmField.getPassword();
            boolean ok = createVault(master, confirm);
            if (!ok) {
                masterField.setText("");
                confirmField.setText("");
            }
        }
    }

    /**
     * Creates a new vault sealed under {@code master}. Fails (returning false and
     * setting an explanatory status) when the password is blank, the two entries
     * do not match, or the password is too weak. Package-visible for tests.
     *
     * @param master  the master password
     * @param confirm the confirmation entry
     * @return true when the vault was created and unlocked
     */
    boolean createVault(char[] master, char[] confirm) {
        if (master == null || master.length == 0) {
            setStatus("Choose a master password first.");
            return false;
        }
        if (confirm == null || !java.util.Arrays.equals(master, confirm)) {
            setStatus("The two passwords do not match.");
            return false;
        }
        if (VaultCrypto.strength(new String(master)) == VaultCrypto.Strength.VERY_WEAK) {
            setStatus("That master password is too weak - make it longer or more varied.");
            return false;
        }
        try {
            vault = new PasswordVault();
            envelope = VaultEnvelope.seal(vault.toJsonBytes(), master,
                    VaultCrypto.DEFAULT_ITERATIONS);
            sessionKey = VaultCrypto.deriveKey(master,
                    java.util.Base64.getDecoder().decode(envelope.getSalt()),
                    envelope.getIterations());
            store.saveEnvelope(envelope);
            enterUnlockedState();
            setStatus("Vault created. Add your first entry.");
            return true;
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            vault = null;
            sessionKey = null;
            setStatus("Could not create the vault: " + describe(e));
            return false;
        }
    }

    /**
     * Unlocks an existing vault with {@code master}. Because the cipher is
     * authenticated, a wrong password throws inside {@link VaultEnvelope#open} and
     * is reported as a failure rather than yielding garbage. Package-visible for
     * tests.
     *
     * @param master the master password
     * @return true when the vault unlocked
     */
    boolean unlock(char[] master) {
        if (!envelope.isPresent()) {
            setStatus("No vault to unlock - create one first.");
            return false;
        }
        try {
            byte[] plaintext = envelope.open(master);
            vault = PasswordVault.fromJsonBytes(plaintext);
            sessionKey = VaultCrypto.deriveKey(master,
                    java.util.Base64.getDecoder().decode(envelope.getSalt()),
                    envelope.getIterations());
            enterUnlockedState();
            setStatus("Unlocked - " + vault.size()
                    + (vault.size() == 1 ? " entry." : " entries."));
            return true;
        } catch (GeneralSecurityException e) {
            vault = null;
            sessionKey = null;
            setStatus("Incorrect master password.");
            return false;
        } catch (IOException | RuntimeException e) {
            vault = null;
            sessionKey = null;
            setStatus("The vault could not be read: " + describe(e));
            return false;
        }
    }

    private void enterUnlockedState() {
        locked = false;
        masterField.setText("");
        confirmField.setText("");
        cards.show(cardPanel, MAIN_CARD);
        lockBtn.setEnabled(true);
        refreshCategoryBox();
        refreshEntryList();
        clearDetail();
        restartAutoLock();
    }

    /**
     * Locks the vault: clears the decrypted entries, the derived key and every
     * detail field, stops the auto-lock timer and returns to the lock card. Safe
     * to call when already locked. Package-visible so the 3D wrapper and tests can
     * call it.
     */
    void lock() {
        stopAutoLock();
        vault = null;
        sessionKey = null;
        locked = true;
        clearDetail();
        entryModel.clear();
        showLockCard();
        setStatus("Locked.");
    }

    // ------------------------------------------------------------------
    // Auto-lock
    // ------------------------------------------------------------------

    private void restartAutoLock() {
        stopAutoLock();
        int minutes = settings.getAutoLockMinutes();
        if (minutes <= 0 || locked) {
            return;
        }
        autoLockTimer = new Timer(minutes * 60_000, e -> {
            if (!locked) {
                lock();
                setStatus("Auto-locked after inactivity.");
            }
        });
        autoLockTimer.setRepeats(false);
        autoLockTimer.start();
    }

    private void stopAutoLock() {
        if (autoLockTimer != null) {
            autoLockTimer.stop();
            autoLockTimer = null;
        }
    }

    /** Resets the auto-lock countdown; called after each user action. */
    private void touchActivity() {
        if (!locked && settings.getAutoLockMinutes() > 0) {
            restartAutoLock();
        }
    }

    // ------------------------------------------------------------------
    // Entry list / detail
    // ------------------------------------------------------------------

    private void refreshCategoryBox() {
        String previous = selectedCategory();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("All");
        if (vault != null) {
            for (String category : vault.categories()) {
                model.addElement(category);
            }
        }
        categoryBox.setModel(model);
        if (previous != null && !previous.isBlank() && !previous.equals("All")) {
            categoryBox.setSelectedItem(previous);
        }
    }

    private String selectedCategory() {
        Object sel = categoryBox.getSelectedItem();
        return (sel == null) ? "All" : sel.toString();
    }

    private void refreshEntryList() {
        if (vault == null) {
            entryModel.clear();
            return;
        }
        String query = searchField.getText();
        String category = selectedCategory();
        entryModel.clear();
        for (PasswordEntry entry : vault.entries()) {
            boolean catOk = "All".equals(category)
                    || category.equalsIgnoreCase(entry.getCategory());
            if (catOk && entry.matches(query)) {
                entryModel.addElement(entry);
            }
        }
    }

    private void loadSelectedIntoDetail() {
        PasswordEntry entry = entryList.getSelectedValue();
        if (entry == null) {
            return;
        }
        titleField.setText(entry.getTitle());
        userField.setText(entry.getUsername());
        passField.setText(entry.getPassword());
        urlField.setText(entry.getUrl());
        categoryField.setText(entry.getCategory());
        notesArea.setText(entry.getNotes());
        applyMask();
        updateEntryStrength();
    }

    private void clearDetail() {
        titleField.setText("");
        userField.setText("");
        passField.setText("");
        urlField.setText("");
        categoryField.setText("");
        notesArea.setText("");
        entryStrength.setText(" ");
        entryList.clearSelection();
    }

    private void newEntry() {
        if (locked) {
            return;
        }
        clearDetail();
        titleField.setText("");
        setStatus("Fill in the details, then Save.");
        touchActivity();
    }

    private void saveSelected() {
        if (locked || vault == null) {
            return;
        }
        String title = titleField.getText().trim();
        if (title.isEmpty()) {
            setStatus("Give the entry a title before saving.");
            return;
        }
        PasswordEntry selected = entryList.getSelectedValue();
        PasswordEntry entry = (selected == null) ? new PasswordEntry() : selected;
        entry.setTitle(title);
        entry.setUsername(userField.getText());
        entry.setPassword(new String(passField.getPassword()));
        entry.setUrl(urlField.getText());
        entry.setCategory(categoryField.getText());
        entry.setNotes(notesArea.getText());
        entry.touch();
        if (selected == null) {
            vault.add(entry);
        }
        persistVault();
        refreshCategoryBox();
        refreshEntryList();
        entryList.setSelectedValue(entry, true);
        setStatus((selected == null ? "Added " : "Saved ") + entry.getTitle() + ".");
        touchActivity();
    }

    private void removeSelected() {
        if (locked || vault == null) {
            return;
        }
        PasswordEntry selected = entryList.getSelectedValue();
        if (selected == null) {
            setStatus("Select an entry to remove.");
            return;
        }
        List<PasswordEntry> all = vault.entries();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == selected || all.get(i).getTitle().equals(selected.getTitle())) {
                vault.remove(i);
                break;
            }
        }
        persistVault();
        refreshCategoryBox();
        refreshEntryList();
        clearDetail();
        setStatus("Removed " + selected.getTitle() + ".");
        touchActivity();
    }

    /**
     * Adds an entry directly to the vault and persists it. Package-visible so a
     * test can grow the vault without driving the detail fields.
     *
     * @param entry the entry to add (ignored when null or locked)
     */
    void addEntry(PasswordEntry entry) {
        if (locked || vault == null || entry == null) {
            return;
        }
        vault.add(entry);
        persistVault();
        refreshCategoryBox();
        refreshEntryList();
    }

    // ------------------------------------------------------------------
    // Generator
    // ------------------------------------------------------------------

    private void readGeneratorPolicy() {
        settings.setGeneratorLength((Integer) lengthSpinner.getValue());
        settings.setGenUpper(upperCheck.isSelected());
        settings.setGenLower(lowerCheck.isSelected());
        settings.setGenDigits(digitCheck.isSelected());
        settings.setGenSymbols(symbolCheck.isSelected());
    }

    private String generateFromPolicy() {
        readGeneratorPolicy();
        if (!settings.isGeneratorUsable()) {
            setStatus("Select at least one character class.");
            return "";
        }
        String password = VaultCrypto.generatePassword(settings.getGeneratorLength(),
                settings.isGenUpper(), settings.isGenLower(), settings.isGenDigits(),
                settings.isGenSymbols(), random);
        store.saveSettings(settings);
        return password;
    }

    private void generateStandalone() {
        String password = generateFromPolicy();
        if (password.isEmpty()) {
            return;
        }
        genResult.setText(password);
        renderStrength(genStrength, password);
        setStatus("Generated a " + password.length() + "-character password.");
        touchActivity();
    }

    private void generateIntoEntry() {
        String password = generateFromPolicy();
        if (password.isEmpty()) {
            return;
        }
        passField.setText(password);
        applyMask();
        updateEntryStrength();
        setStatus("Generated a password for this entry - press Save to keep it.");
        touchActivity();
    }

    private void updateEntryStrength() {
        renderStrength(entryStrength, new String(passField.getPassword()));
    }

    private void renderStrength(JLabel label, String password) {
        if (password == null || password.isEmpty()) {
            label.setText(" ");
            return;
        }
        VaultCrypto.Strength strength = VaultCrypto.strength(password);
        int score = VaultCrypto.strengthScore(password);
        label.setText("Strength: " + VaultCrypto.describeStrength(strength)
                + "  (" + score + "/100)");
        label.setForeground(colorForStrength(strength));
    }

    private static Color colorForStrength(VaultCrypto.Strength strength) {
        return switch (strength) {
            case VERY_WEAK, WEAK -> ATTENTION;
            case FAIR -> new Color(180, 140, 0);
            case STRONG, VERY_STRONG -> GOOD;
        };
    }

    // ------------------------------------------------------------------
    // Settings / password visibility / clipboard
    // ------------------------------------------------------------------

    private void saveSettings() {
        settings.setAutoLockMinutes((Integer) autoLockSpinner.getValue());
        settings.setMaskByDefault(maskCheck.isSelected());
        readGeneratorPolicy();
        store.saveSettings(settings);
        applyMask();
        restartAutoLock();
        setStatus("Settings saved.");
    }

    private void togglePasswordVisible() {
        boolean masked = passField.getEchoChar() != '\0';
        if (masked) {
            passField.setEchoChar((char) 0);
            showPassBtn.setText("Hide");
        } else {
            applyMask();
        }
    }

    private void applyMask() {
        if (settings.isMaskByDefault()) {
            passField.setEchoChar('\u2022');
            showPassBtn.setText("Show");
        } else {
            passField.setEchoChar((char) 0);
            showPassBtn.setText("Hide");
        }
    }

    private void copyText(String text) {
        if (text == null || text.isEmpty()) {
            setStatus("Nothing to copy.");
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(text), null);
            setStatus("Copied to the clipboard.");
        } catch (RuntimeException e) {
            setStatus("The clipboard is not available.");
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void persistVault() {
        if (locked || vault == null || sessionKey == null) {
            return;
        }
        try {
            byte[] plaintext = vault.toJsonBytes();
            byte[] iv = VaultCrypto.newIv();
            byte[] cipher = VaultCrypto.encrypt(plaintext, sessionKey, iv);
            envelope.setIv(java.util.Base64.getEncoder().encodeToString(iv));
            envelope.setData(java.util.Base64.getEncoder().encodeToString(cipher));
            store.saveEnvelope(envelope);
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            setStatus("Could not save the vault: " + describe(e));
        }
    }

    // ------------------------------------------------------------------
    // Status / helpers
    // ------------------------------------------------------------------

    private void setStatus(String text) {
        final String message = text;
        // Track the latest status synchronously so it is observable the instant it
        // is set (headless tests never pump the EDT); the label still updates on
        // the EDT.
        statusMessage = message;
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(message);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(message));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }

    /** Wires the panel's Close button (used by both desktop hosts). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------

    /** True while the vault is locked (no decrypted entries in memory). */
    boolean isLocked() {
        return locked;
    }

    /** True when a sealed vault exists (created this session or loaded). */
    boolean hasVault() {
        return envelope != null && envelope.isPresent();
    }

    /** The number of entries in the unlocked vault (0 when locked). */
    int entryCount() {
        return (vault == null) ? 0 : vault.size();
    }

    /** An unmodifiable view of the entries (empty when locked). */
    List<PasswordEntry> entries() {
        return (vault == null) ? List.of() : vault.entries();
    }

    /** The current status text. */
    String statusText() {
        return statusMessage;
    }

    /** The live settings bean (package-visible for tests). */
    PasswordManagerSettings settings() {
        return settings;
    }
}
