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
package org.jdesktop.lg3d.apps.messenger;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JToolBar;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * The Instant Messenger's Swing face: a multi-protocol chat client.
 *
 * <p>The WEST rail lists saved {@link AccountConfig accounts} (each backed by a
 * {@link MessengerProtocol} from the {@link ProtocolRegistry}) and the open
 * {@link Conversation conversations}; the CENTER is a styled transcript that
 * colours nicknames and honours the {@link MessengerSettings} display filters;
 * the SOUTH input bar sends plain text or interprets {@code /slash} commands
 * ({@code /join /part /msg /me /nick /topic /quit /raw /connect /disconnect
 * /clear /help}). All protocol events arrive on the backend I/O thread and are
 * marshalled onto the EDT before touching a widget.</p>
 *
 * <p>The panel is plain Swing and touches no Java&nbsp;3D, so the one class
 * serves both desktops: in 3D the {@link Messenger} wrapper hosts it on a
 * {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow}; in
 * the 2D/Swing desktop {@code Desktop2DAppRegistry.PANEL_APPS} opens the very
 * same panel as an MDI internal frame. The main surface uses only layout
 * managers, buttons, lists and text widgets (no Synth combo boxes on the
 * rendered surface), so it paints correctly into the SwingNode offscreen buffer;
 * the modal dialogs use combo boxes freely because they are separate top-level
 * windows.</p>
 *
 * <p><b>Headless-safe:</b> every browser, clipboard, modal-dialog and
 * notification path is guarded on {@link GraphicsEnvironment#isHeadless()}, and
 * the constructor performs no network I/O, so the panel can be built and its
 * pure logic exercised in the headless test JVM.</p>
 */
public class MessengerPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 980;
    public static final int HEIGHT_PX = 640;

    private static final Color BACKDROP = new Color(0x1E, 0x24, 0x2C);
    private static final Color CARD = new Color(0x27, 0x2F, 0x39);
    private static final Color TEXT = new Color(0xEC, 0xF1, 0xF7);
    private static final Color TEXT_DIM = new Color(0x9A, 0xA7, 0xB4);
    private static final Color ACCENT = new Color(0x3D, 0x8B, 0xFF);
    private static final Color CONNECTED = new Color(0x50, 0xFA, 0x7B);
    private static final Color ERROR_C = new Color(0xFF, 0x6E, 0x6E);
    private static final Color ACTION_C = new Color(0xFF, 0x79, 0xC6);

    /** Deterministic nickname palette (readable on the dark transcript). */
    private static final Color[] NICK_PALETTE = {
        new Color(0x7F, 0xB3, 0xFF), new Color(0xFF, 0xB8, 0x6C),
        new Color(0x50, 0xFA, 0x7B), new Color(0xFF, 0x79, 0xC6),
        new Color(0xF1, 0xFA, 0x8C), new Color(0x8B, 0xE9, 0xFD),
        new Color(0xBD, 0x93, 0xF9), new Color(0xFF, 0xA0, 0x7A),
    };

    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss");

    private final MessengerStore store;
    private final ProtocolRegistry registry;
    private final PanelListener listener = new PanelListener();
    private MessengerSettings settings;

    private final List<AccountConfig> accounts = new ArrayList<>();
    private final List<Conversation> conversations = new ArrayList<>();
    private final List<StoredMessage> transcript = new ArrayList<>();
    /** accountId -> live backend, present only while connecting/connected. */
    private final Map<String, MessengerProtocol> protocols = new HashMap<>();
    /** conversationKey -> last-known channel members (for the header count). */
    private final Map<String, List<String>> rosters = new HashMap<>();

    private final DefaultListModel<AccountConfig> accountModel = new DefaultListModel<>();
    private final DefaultListModel<Conversation> conversationModel = new DefaultListModel<>();
    private final JList<AccountConfig> accountList = new JList<>(accountModel);
    private final JList<Conversation> conversationList = new JList<>(conversationModel);

    private final JTextPane transcriptPane = new JTextPane();
    private final StyledDocument doc = transcriptPane.getStyledDocument();
    private final JTextField inputField = new JTextField();
    private final JButton sendButton = new JButton("Send");
    private final JLabel headerLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel connectionLabel = new JLabel(" ");

    private final SimpleAttributeSet baseStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet tsStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet systemStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet errorStyle = new SimpleAttributeSet();
    private final SimpleAttributeSet actionStyle = new SimpleAttributeSet();

    private Runnable onClose;
    private Conversation current;

    public MessengerPanel() {
        this(new MessengerStore());
    }

    /**
     * Creates the panel backed by an explicit store (tests point this at a temp
     * directory).
     *
     * @param store the persistence backend
     */
    public MessengerPanel(MessengerStore store) {
        this(store, ProtocolRegistry.standard());
    }

    /**
     * Creates the panel with an explicit store and protocol registry.
     *
     * @param store    the persistence backend
     * @param registry the backend catalogue/factory
     */
    public MessengerPanel(MessengerStore store, ProtocolRegistry registry) {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBackground(BACKDROP);
        setOpaque(true);

        this.store = store;
        this.registry = (registry == null) ? ProtocolRegistry.standard() : registry;
        this.settings = store.loadSettings();

        accounts.addAll(store.loadAccounts());
        transcript.addAll(store.loadMessages());

        initStyles();
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildSidebar(), BorderLayout.WEST);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        refreshAccounts();
        refreshConversations();
        renderTranscript();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private void initStyles() {
        StyleConstants.setFontFamily(baseStyle, "SansSerif");
        StyleConstants.setFontSize(baseStyle, settings.getFontSize());
        StyleConstants.setForeground(baseStyle, TEXT);

        tsStyle.addAttributes(baseStyle);
        StyleConstants.setForeground(tsStyle, TEXT_DIM);
        StyleConstants.setFontSize(tsStyle, Math.max(8, settings.getFontSize() - 2));

        systemStyle.addAttributes(baseStyle);
        StyleConstants.setForeground(systemStyle, TEXT_DIM);
        StyleConstants.setItalic(systemStyle, true);

        errorStyle.addAttributes(baseStyle);
        StyleConstants.setForeground(errorStyle, ERROR_C);
        StyleConstants.setBold(errorStyle, true);

        actionStyle.addAttributes(baseStyle);
        StyleConstants.setForeground(actionStyle, ACTION_C);
        StyleConstants.setItalic(actionStyle, true);
    }

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JButton connect = new JButton("Connect");
        connect.addActionListener(e -> connectSelected());
        JButton disconnect = new JButton("Disconnect");
        disconnect.addActionListener(e -> disconnectSelected());
        JButton addAccount = new JButton("Add Account");
        addAccount.addActionListener(e -> showAccountDialog(null));
        JButton settingsBtn = new JButton("Settings");
        settingsBtn.addActionListener(e -> showSettingsDialog());

        bar.add(connect);
        bar.add(disconnect);
        bar.addSeparator();
        bar.add(addAccount);
        bar.addSeparator();
        bar.add(settingsBtn);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(bar, BorderLayout.CENTER);
        connectionLabel.setForeground(TEXT_DIM);
        connectionLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        connectionLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 10));
        wrap.add(connectionLabel, BorderLayout.EAST);
        return wrap;
    }

    private JComponent buildSidebar() {
        accountList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accountList.setCellRenderer(new AccountRenderer());
        accountList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateConnectionLabel();
            }
        });

        JPanel accountsPanel = new JPanel(new BorderLayout());
        accountsPanel.setOpaque(false);
        accountsPanel.setBorder(BorderFactory.createTitledBorder("Accounts"));
        accountsPanel.add(new JScrollPane(accountList), BorderLayout.CENTER);
        JPanel acctButtons = new JPanel(new GridLayout(2, 2, 4, 4));
        acctButtons.setOpaque(false);
        acctButtons.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JButton addBtn = new JButton("Add");
        addBtn.addActionListener(e -> showAccountDialog(null));
        JButton editBtn = new JButton("Edit");
        editBtn.addActionListener(e -> showAccountDialog(accountList.getSelectedValue()));
        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> removeSelectedAccount());
        JButton connectBtn = new JButton("Connect");
        connectBtn.addActionListener(e -> connectSelected());
        acctButtons.add(addBtn);
        acctButtons.add(editBtn);
        acctButtons.add(removeBtn);
        acctButtons.add(connectBtn);
        accountsPanel.add(acctButtons, BorderLayout.SOUTH);

        conversationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conversationList.setCellRenderer(new ConversationRenderer());
        conversationList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectConversation(conversationList.getSelectedValue());
            }
        });
        JPanel convPanel = new JPanel(new BorderLayout());
        convPanel.setOpaque(false);
        convPanel.setBorder(BorderFactory.createTitledBorder("Conversations"));
        convPanel.add(new JScrollPane(conversationList), BorderLayout.CENTER);
        JPanel convButtons = new JPanel(new GridLayout(1, 2, 4, 0));
        convButtons.setOpaque(false);
        convButtons.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JButton closeConv = new JButton("Close");
        closeConv.addActionListener(e -> closeSelectedConversation());
        JButton clearConv = new JButton("Clear");
        clearConv.addActionListener(e -> clearTranscript());
        convButtons.add(closeConv);
        convButtons.add(clearConv);
        convPanel.add(convButtons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, accountsPanel, convPanel);
        split.setResizeWeight(0.4);
        split.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        split.setContinuousLayout(true);

        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.setPreferredSize(new Dimension(220, 0));
        west.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        west.add(split, BorderLayout.CENTER);
        return west;
    }

    private JComponent buildCenter() {
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 12));

        headerLabel.setForeground(TEXT);
        headerLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        center.add(headerLabel, BorderLayout.NORTH);

        transcriptPane.setEditable(false);
        transcriptPane.setBackground(CARD);
        transcriptPane.setForeground(TEXT);
        transcriptPane.setCaretColor(TEXT);
        transcriptPane.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(transcriptPane);
        scroll.setBorder(BorderFactory.createLineBorder(CARD.darker()));
        center.add(scroll, BorderLayout.CENTER);

        JPanel inputBar = new JPanel(new BorderLayout(6, 0));
        inputBar.setBackground(CARD);
        inputBar.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        inputField.addActionListener(e -> sendCurrentInput());
        sendButton.setBackground(ACCENT);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.addActionListener(e -> sendCurrentInput());
        inputBar.add(inputField, BorderLayout.CENTER);
        inputBar.add(sendButton, BorderLayout.EAST);
        center.add(inputBar, BorderLayout.SOUTH);
        return center;
    }

    private JComponent buildStatusBar() {
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        statusLabel.setForeground(TEXT_DIM);
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(BACKDROP);
        south.add(statusLabel, BorderLayout.CENTER);
        return south;
    }

    // ------------------------------------------------------------------
    // Model refresh
    // ------------------------------------------------------------------

    private void refreshAccounts() {
        AccountConfig sel = accountList.getSelectedValue();
        accountModel.clear();
        for (AccountConfig a : accounts) {
            accountModel.addElement(a);
        }
        if (sel != null && accounts.contains(sel)) {
            accountList.setSelectedValue(sel, false);
        }
        updateConnectionLabel();
    }

    private void refreshConversations() {
        Conversation sel = current;
        conversationModel.clear();
        for (Conversation c : conversations) {
            conversationModel.addElement(c);
        }
        if (sel != null && conversations.contains(sel)) {
            conversationList.setSelectedValue(sel, false);
        }
        updateHeader();
    }

    private void updateConnectionLabel() {
        AccountConfig a = accountList.getSelectedValue();
        if (a == null) {
            connectionLabel.setText(" ");
            return;
        }
        boolean up = isConnected(a.getId());
        connectionLabel.setForeground(up ? CONNECTED : TEXT_DIM);
        connectionLabel.setText(up ? "\u25cf connected" : "\u25cb offline");
    }

    private void updateHeader() {
        if (current == null) {
            headerLabel.setText(" ");
            return;
        }
        List<String> members = rosters.get(current.key());
        String suffix = (members == null || members.isEmpty())
                ? "" : "   \u2014   " + members.size() + " here";
        headerLabel.setText(current.displayName() + suffix);
    }

    private boolean isConnected(String accountId) {
        MessengerProtocol p = protocols.get(accountId);
        return p != null && p.isConnected();
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    private void connectSelected() {
        connectAccount(accountList.getSelectedValue());
    }

    private void disconnectSelected() {
        disconnectAccount(accountList.getSelectedValue());
    }

    /** Connects an account, creating its backend from the registry. */
    void connectAccount(AccountConfig account) {
        if (account == null) {
            setStatus("Select an account to connect");
            return;
        }
        if (isConnected(account.getId())) {
            setStatus(account + " is already connected");
            return;
        }
        MessengerProtocol p = registry.create(account.getProtocolId());
        if (p == null) {
            setStatus("Unknown protocol: " + account.getProtocolId());
            return;
        }
        if (p instanceof IrcProtocol irc) {
            irc.setAutoReconnect(settings.isAutoReconnect());
            irc.setReconnectDelaySeconds(settings.getReconnectDelaySeconds());
        }
        protocols.put(account.getId(), p);
        ensureConsole(account);
        if (account.isPasswordPrompt()) {
            promptPassword(account);
        }
        setStatus("Connecting to " + account + " ...");
        refreshAccounts();
        p.connect(account, listener);
    }

    /** Disconnects an account and flushes its transcript to disk. */
    void disconnectAccount(AccountConfig account) {
        if (account == null) {
            setStatus("Select an account to disconnect");
            return;
        }
        MessengerProtocol p = protocols.remove(account.getId());
        if (p != null) {
            p.disconnect();
        }
        refreshAccounts();
        saveTranscript();
        setStatus("Disconnected " + account);
    }

    private void promptPassword(AccountConfig account) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JPasswordField server = new JPasswordField();
        JPasswordField nickserv = new JPasswordField();
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        addFormRow(form, c, 0, "Server password", server);
        addFormRow(form, c, 1, "NickServ password", nickserv);
        int opt = JOptionPane.showConfirmDialog(this, form, "Password for " + account,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return;
        }
        account.setServerPassword(new String(server.getPassword()));
        account.setNickServPassword(new String(nickserv.getPassword()));
    }

    private void removeSelectedAccount() {
        AccountConfig a = accountList.getSelectedValue();
        if (a == null) {
            return;
        }
        disconnectAccount(a);
        accounts.remove(a);
        conversations.removeIf(cv -> cv.accountId.equals(a.getId()));
        transcript.removeIf(m -> m.getAccountId().equals(a.getId()));
        if (current != null && current.accountId.equals(a.getId())) {
            current = null;
        }
        saveAccounts();
        saveTranscript();
        refreshAccounts();
        refreshConversations();
        renderTranscript();
        setStatus("Removed " + a);
    }

    private void showAccountDialog(AccountConfig existing) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        boolean isNew = (existing == null);
        AccountConfig a = isNew ? new AccountConfig() : existing.copy();

        JTextField name = new JTextField(a.getName());
        List<ProtocolRegistry.ProtocolInfo> infos = registry.protocols();
        JComboBox<String> protocol = new JComboBox<>();
        for (ProtocolRegistry.ProtocolInfo info : infos) {
            protocol.addItem(info.id() + " \u2014 " + info.displayName());
        }
        protocol.setSelectedIndex(Math.max(0, registry.ids().indexOf(a.getProtocolId())));
        JTextField host = new JTextField(a.getHost());
        JTextField port = new JTextField(String.valueOf(a.getPort()));
        JTextField nick = new JTextField(a.getNickname());
        JTextField realName = new JTextField(a.getRealName());
        JTextField channels = new JTextField(String.join(" ", a.getAutoJoinChannels()));
        JCheckBox tls = new JCheckBox("Use TLS", a.isUseTls());
        JCheckBox autoConnect = new JCheckBox("Connect at startup", a.isAutoConnect());
        JCheckBox prompt = new JCheckBox("Ask for password", a.isPasswordPrompt());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addFormRow(form, c, row++, "Account name", name);
        addFormRow(form, c, row++, "Protocol", protocol);
        addFormRow(form, c, row++, "Server host", host);
        addFormRow(form, c, row++, "Port", port);
        addFormRow(form, c, row++, "Nickname", nick);
        addFormRow(form, c, row++, "Real name", realName);
        addFormRow(form, c, row++, "Auto-join channels", channels);
        c.gridx = 1; c.gridy = row++; form.add(tls, c);
        c.gridx = 1; c.gridy = row++; form.add(autoConnect, c);
        c.gridx = 1; c.gridy = row; form.add(prompt, c);

        int opt = JOptionPane.showConfirmDialog(this, form,
                isNew ? "Add Account" : "Edit Account",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return;
        }
        a.setName(name.getText().trim());
        String chosen = (String) protocol.getSelectedItem();
        if (chosen != null) {
            int dash = chosen.indexOf(' ');
            a.setProtocolId(dash > 0 ? chosen.substring(0, dash) : chosen);
        }
        a.setHost(host.getText());
        try {
            a.setPort(Integer.parseInt(port.getText().trim()));
        } catch (NumberFormatException ex) {
            a.setPort(0);
        }
        a.setNickname(nick.getText().trim());
        a.setRealName(realName.getText());
        List<String> chList = new ArrayList<>();
        for (String tok : channels.getText().split("\\s+")) {
            if (!tok.isBlank()) {
                chList.add(tok.trim());
            }
        }
        a.setAutoJoinChannels(chList);
        a.setUseTls(tls.isSelected());
        a.setAutoConnect(autoConnect.isSelected());
        a.setPasswordPrompt(prompt.isSelected());

        if (isNew) {
            if (a.getName().isEmpty()) {
                a.setName(a.getProtocolId() + "://" + a.getHost());
            }
            accounts.add(a);
        } else {
            int idx = accounts.indexOf(existing);
            if (idx >= 0) {
                accounts.set(idx, a);
            }
        }
        saveAccounts();
        refreshAccounts();
        accountList.setSelectedValue(a, true);
        setStatus((isNew ? "Added " : "Updated ") + a);
    }

    // ------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------

    private Conversation ensureConsole(AccountConfig account) {
        Conversation console = findConversation(account.getId(), "");
        if (console == null) {
            console = new Conversation(account.getId(), "", accountLabel(account) + " \u00b7 console");
            conversations.add(console);
            refreshConversations();
        }
        return console;
    }

    private Conversation findConversation(String accountId, String target) {
        String t = (target == null) ? "" : target;
        for (Conversation c : conversations) {
            if (c.accountId.equals(accountId) && c.target.equals(t)) {
                return c;
            }
        }
        return null;
    }

    private Conversation conversationFor(AccountConfig account, String target) {
        Conversation c = findConversation(account.getId(), target);
        if (c == null) {
            c = new Conversation(account.getId(), target, target);
            conversations.add(c);
            refreshConversations();
        }
        return c;
    }

    private void selectConversation(Conversation c) {
        current = c;
        updateHeader();
        renderTranscript();
        inputField.requestFocusInWindow();
    }

    private void closeSelectedConversation() {
        Conversation c = conversationList.getSelectedValue();
        if (c == null || c.isConsole()) {
            setStatus("Select a channel or peer to close");
            return;
        }
        MessengerProtocol p = protocols.get(c.accountId);
        if (p != null && c.channel) {
            p.partChannel(c.target, "Closing");
        }
        conversations.remove(c);
        rosters.remove(c.key());
        if (c.equals(current)) {
            current = null;
        }
        refreshConversations();
        renderTranscript();
        setStatus("Closed " + c.target);
    }

    private void clearTranscript() {
        if (current == null) {
            transcript.clear();
        } else {
            transcript.removeIf(m -> m.getAccountId().equals(current.accountId)
                    && m.getTarget().equals(current.target));
        }
        saveTranscript();
        renderTranscript();
        setStatus("Cleared transcript");
    }

    private String accountLabel(AccountConfig account) {
        String n = account.getName();
        return (n == null || n.isBlank()) ? account.getNickname() : n;
    }

    private AccountConfig accountById(String id) {
        for (AccountConfig a : accounts) {
            if (a.getId().equals(id)) {
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Sending + command interpretation
    // ------------------------------------------------------------------

    private void sendCurrentInput() {
        String text = inputField.getText();
        inputField.setText("");
        submitInput(text);
    }

    /**
     * Sends a line as either a {@code /slash} command or a plain chat message to
     * the current conversation. Package-private for tests.
     *
     * @param text the raw input line
     */
    void submitInput(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ParsedCommand cmd = parseCommand(text);
        if (cmd == null) {
            sendPlain(text);
        } else {
            dispatchCommand(cmd);
        }
    }

    private void sendPlain(String text) {
        if (current == null || current.isConsole()) {
            setStatus("Select a channel or contact first (or use /msg nick hi)");
            return;
        }
        MessengerProtocol p = protocols.get(current.accountId);
        AccountConfig account = accountById(current.accountId);
        if (p == null || !p.isConnected() || account == null) {
            setStatus("Not connected to " + (account == null ? current.accountId : account));
            return;
        }
        p.sendMessage(current.target, text);
        ingest(account, new ChatMessage(ourNick(account), current.target, text,
                ChatMessage.Kind.PRIVMSG));
    }

    private void dispatchCommand(ParsedCommand cmd) {
        String verb = cmd.verb();
        switch (verb) {
            case "help" -> showHelp();
            case "clear" -> clearTranscript();
            case "connect" -> connectAccount(resolveAccount(cmd.rest()));
            case "disconnect" -> disconnectAccount(resolveAccount(cmd.rest()));
            case "join" -> cmdJoin(cmd);
            case "part", "leave" -> cmdPart(cmd);
            case "msg", "query", "m" -> cmdMsg(cmd);
            case "me", "action" -> cmdMe(cmd);
            case "nick" -> cmdNick(cmd);
            case "topic" -> cmdTopic(cmd);
            case "quit", "server" -> cmdQuit(cmd);
            case "raw" -> cmdRaw(cmd);
            default -> setStatus("Unknown command: /" + verb + "  (try /help)");
        }
    }

    private void cmdJoin(ParsedCommand cmd) {
        AccountConfig account = currentAccount();
        if (notConnected(account)) {
            return;
        }
        if (cmd.args().isEmpty()) {
            setStatus("Usage: /join #channel");
            return;
        }
        MessengerProtocol p = protocols.get(account.getId());
        for (String ch : cmd.args()) {
            p.joinChannel(ch);
            Conversation conv = conversationFor(account,
                    ch.startsWith("#") || ch.startsWith("&") ? ch : "#" + ch);
            selectConversation(conv);
        }
    }

    private void cmdPart(ParsedCommand cmd) {
        AccountConfig account = currentAccount();
        if (notConnected(account)) {
            return;
        }
        String target = cmd.rest().isBlank()
                ? (current == null ? "" : current.target)
                : firstToken(cmd.rest());
        if (target.isEmpty()) {
            setStatus("Usage: /part #channel");
            return;
        }
        protocols.get(account.getId()).partChannel(target, afterFirstToken(cmd.rest()));
    }

    private void cmdMsg(ParsedCommand cmd) {
        AccountConfig account = currentAccount();
        if (notConnected(account)) {
            return;
        }
        if (cmd.args().size() < 2) {
            setStatus("Usage: /msg nick message");
            return;
        }
        String target = cmd.args().get(0);
        String body = afterFirstToken(cmd.rest());
        Conversation conv = conversationFor(account, target);
        selectConversation(conv);
        protocols.get(account.getId()).sendMessage(target, body);
        ingest(account, new ChatMessage(ourNick(account), target, body, ChatMessage.Kind.PRIVMSG));
    }

    private void cmdMe(ParsedCommand cmd) {
        if (current == null || current.isConsole()) {
            setStatus("Select a channel or contact first");
            return;
        }
        AccountConfig account = currentAccount();
        if (notConnected(account)) {
            return;
        }
        protocols.get(account.getId()).sendAction(current.target, cmd.rest());
        ingest(account, new ChatMessage(ourNick(account), current.target, cmd.rest(),
                ChatMessage.Kind.ACTION));
    }

    private void cmdNick(ParsedCommand cmd) {
        AccountConfig account = currentAccount();
        if (notConnected(account)) {
            return;
        }
        if (cmd.args().isEmpty()) {
            setStatus("Usage: /nick newnick");
            return;
        }
        MessengerProtocol p = protocols.get(account.getId());
        if (p instanceof IrcProtocol irc) {
            irc.changeNick(firstToken(cmd.rest()));
            account.setNickname(firstToken(cmd.rest()));
            saveAccounts();
            refreshAccounts();
            setStatus("Nickname change requested");
        } else {
            setStatus("This protocol does not support /nick");
        }
    }

    private void cmdTopic(ParsedCommand cmd) {
        AccountConfig account = currentAccount();
        if (notConnected(account)) {
            return;
        }
        String target = (current == null || current.isConsole()) ? firstToken(cmd.rest()) : current.target;
        MessengerProtocol p = protocols.get(account.getId());
        if (p instanceof IrcProtocol irc && !target.isEmpty()) {
            irc.sendRaw("TOPIC " + target + " :" + afterFirstToken(cmd.rest()));
        } else {
            setStatus("This protocol does not support /topic");
        }
    }

    private void cmdQuit(ParsedCommand cmd) {
        AccountConfig account = currentAccount();
        if (notConnected(account)) {
            return;
        }
        MessengerProtocol p = protocols.get(account.getId());
        if (p instanceof IrcProtocol irc) {
            irc.sendRaw("QUIT :" + (cmd.rest().isBlank() ? "LG3D Messenger" : cmd.rest()));
        }
        disconnectAccount(account);
    }

    private void cmdRaw(ParsedCommand cmd) {
        AccountConfig account = currentAccount();
        if (notConnected(account)) {
            return;
        }
        MessengerProtocol p = protocols.get(account.getId());
        if (p instanceof IrcProtocol irc) {
            irc.sendRaw(cmd.rest());
        } else {
            setStatus("Raw commands are only supported on IRC");
        }
    }

    private void showHelp() {
        String help = "Commands: /connect [acct] /disconnect [acct] /join #chan "
                + "/part [#chan] /msg nick text /me text /nick new /topic text "
                + "/quit [msg] /raw line /clear /help";
        systemLine(currentAccount(), help);
        setStatus("/help printed to the transcript");
    }

    private AccountConfig currentAccount() {
        if (current != null) {
            AccountConfig a = accountById(current.accountId);
            if (a != null) {
                return a;
            }
        }
        return accountList.getSelectedValue();
    }

    private AccountConfig resolveAccount(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return currentAccount();
        }
        String key = nameOrId.trim();
        for (AccountConfig a : accounts) {
            if (a.getId().equals(key) || a.getName().equalsIgnoreCase(key)
                    || a.getNickname().equalsIgnoreCase(key)) {
                return a;
            }
        }
        return currentAccount();
    }

    private boolean notConnected(AccountConfig account) {
        if (account == null) {
            setStatus("Select an account first");
            return true;
        }
        if (!isConnected(account.getId())) {
            setStatus("Not connected to " + account);
            return true;
        }
        return false;
    }

    private String ourNick(AccountConfig account) {
        MessengerProtocol p = protocols.get(account.getId());
        if (p instanceof IrcProtocol irc) {
            String n = irc.getCurrentNick();
            if (n != null && !n.isBlank()) {
                return n;
            }
        }
        return account.getNickname();
    }

    // ------------------------------------------------------------------
    // Ingest + rendering
    // ------------------------------------------------------------------

    /** Routes a protocol event into the transcript (EDT only). */
    private void ingest(AccountConfig account, ChatMessage msg) {
        Conversation conv = routeConversation(account, msg);
        String text = normalizeText(msg);
        StoredMessage sm = new StoredMessage(account.getId(), conv.target, conv.channel,
                msg.getFrom(), text, msg.getKind(), msg.getEpochMs());
        transcript.add(sm);
        trimTranscript();
        if (conv.equals(current)) {
            renderMessage(sm);
            scrollToEnd();
        }
        if (msg.isChat() && settings.isNotifyOnMessage()
                && !conv.equals(current) && !msg.getFrom().equals(ourNick(account))) {
            setStatus("New message in " + conv.displayName() + " from " + msg.getFrom());
        }
    }

    private Conversation routeConversation(AccountConfig account, ChatMessage msg) {
        String target = msg.getTarget();
        ChatMessage.Kind k = msg.getKind();
        if (target == null || target.isEmpty() || k == ChatMessage.Kind.NICK
                || k == ChatMessage.Kind.QUIT) {
            return ensureConsole(account);
        }
        return conversationFor(account, target);
    }

    private String normalizeText(ChatMessage msg) {
        if (msg.getKind() == ChatMessage.Kind.NICK) {
            return msg.getTarget();
        }
        return msg.getText();
    }

    private void trimTranscript() {
        int limit = settings.getHistoryLimit();
        if (limit > 0) {
            while (transcript.size() > limit) {
                transcript.remove(0);
            }
        }
    }

    private void renderTranscript() {
        try {
            doc.remove(0, doc.getLength());
        } catch (Exception ex) {
            // Nothing to clear.
        }
        if (current == null) {
            appendSegment("Select or open a conversation to begin. "
                    + "Add an account, then Connect.", systemStyle);
            return;
        }
        for (StoredMessage m : transcript) {
            if (m.getAccountId().equals(current.accountId)
                    && m.getTarget().equals(current.target)) {
                renderMessage(m);
            }
        }
        scrollToEnd();
    }

    private void renderMessage(StoredMessage m) {
        ChatMessage.Kind k = m.kindEnum();
        switch (k) {
            case JOIN, PART, QUIT, NICK -> {
                if (settings.isShowJoinsParts()) {
                    renderPresence(m, k);
                }
            }
            case SYSTEM, NOTICE -> {
                if (settings.isShowSystemMessages()) {
                    appendTimestamp();
                    appendSegment("*** " + m.getText() + "\n", systemStyle);
                }
            }
            case ERROR -> {
                appendTimestamp();
                appendSegment("!! " + m.getText() + "\n", errorStyle);
            }
            case ACTION -> {
                appendTimestamp();
                appendSegment("* " + m.getSender() + " " + m.getText() + "\n", actionStyle);
            }
            case TOPIC -> {
                appendTimestamp();
                appendSegment("Topic: " + m.getText() + "\n", systemStyle);
            }
            default -> renderPrivmsg(m);
        }
    }

    private void renderPresence(StoredMessage m, ChatMessage.Kind k) {
        appendTimestamp();
        String line = switch (k) {
            case JOIN -> "--> " + m.getSender() + " joined " + m.getTarget();
            case PART -> "<-- " + m.getSender() + " left " + m.getTarget()
                    + (m.getText().isEmpty() ? "" : " (" + m.getText() + ")");
            case QUIT -> "<-- " + m.getSender() + " quit"
                    + (m.getText().isEmpty() ? "" : " (" + m.getText() + ")");
            case NICK -> "--- " + m.getSender() + " is now known as " + m.getText();
            default -> m.getText();
        };
        appendSegment(line + "\n", systemStyle);
    }

    private void renderPrivmsg(StoredMessage m) {
        appendTimestamp();
        SimpleAttributeSet nickStyle = new SimpleAttributeSet(baseStyle);
        Color nc = settings.isColorNicknames() ? nickColor(m.getSender()) : TEXT;
        StyleConstants.setForeground(nickStyle, nc);
        StyleConstants.setBold(nickStyle, true);
        appendSegment("<", baseStyle);
        appendSegment(m.getSender(), nickStyle);
        appendSegment("> ", baseStyle);
        appendSegment(m.getText() + "\n", baseStyle);
    }

    private void appendTimestamp() {
        if (settings.isShowTimestamps()) {
            appendSegment("[" + formatTimestamp(System.currentTimeMillis()) + "] ", tsStyle);
        }
    }

    private void appendSegment(String text, SimpleAttributeSet style) {
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (Exception ex) {
            // A rendering hiccup must never break the client.
        }
    }

    private void scrollToEnd() {
        transcriptPane.setCaretPosition(doc.getLength());
    }

    private void systemLine(AccountConfig account, String text) {
        ChatMessage m = ChatMessage.system(text);
        if (account == null) {
            appendTimestamp();
            appendSegment("*** " + text + "\n", systemStyle);
            return;
        }
        ingest(account, m);
    }

    /** A stable, readable colour for a nickname (deterministic for tests). */
    static Color nickColor(String nick) {
        if (nick == null || nick.isEmpty()) {
            return TEXT;
        }
        return NICK_PALETTE[Math.floorMod(nick.hashCode(), NICK_PALETTE.length)];
    }

    // ------------------------------------------------------------------
    // Protocol listener (I/O thread -> EDT)
    // ------------------------------------------------------------------

    private void edt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private final class PanelListener implements ProtocolListener {
        @Override
        public void onConnected(AccountConfig account) {
            edt(() -> {
                setStatus("Connected to " + account);
                refreshAccounts();
                systemLine(account, "Connected to " + account.getHost());
            });
        }

        @Override
        public void onDisconnected(AccountConfig account, String reason) {
            edt(() -> {
                protocols.remove(account.getId());
                refreshAccounts();
                saveTranscript();
                systemLine(account, "Disconnected" + (reason == null ? "" : ": " + reason));
                setStatus("Disconnected from " + account);
            });
        }

        @Override
        public void onMessage(AccountConfig account, ChatMessage message) {
            edt(() -> ingest(account, message));
        }

        @Override
        public void onStatus(AccountConfig account, String status) {
            edt(() -> systemLine(account, status));
        }

        @Override
        public void onError(AccountConfig account, String error) {
            edt(() -> {
                Conversation conv = ensureConsole(account);
                StoredMessage sm = new StoredMessage(account.getId(), conv.target, false,
                        "", error, ChatMessage.Kind.ERROR, System.currentTimeMillis());
                transcript.add(sm);
                trimTranscript();
                if (conv.equals(current)) {
                    renderMessage(sm);
                    scrollToEnd();
                }
                setStatus(error);
            });
        }

        @Override
        public void onRosterUpdate(AccountConfig account, String channel, List<String> members) {
            edt(() -> {
                Conversation conv = conversationFor(account, channel);
                rosters.put(conv.key(), new ArrayList<>(members));
                updateHeader();
            });
        }
    }

    // ------------------------------------------------------------------
    // Command parsing (pure, testable)
    // ------------------------------------------------------------------

    /** A parsed {@code /slash} command: lower-cased verb, tokens, raw remainder. */
    record ParsedCommand(String verb, List<String> args, String rest) {
    }

    /**
     * Parses an input line. Returns {@code null} when the line is not a command
     * (does not start with {@code /}), so the caller sends it as chat.
     *
     * @param input the raw line
     * @return the parsed command, or null for plain text
     */
    static ParsedCommand parseCommand(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (!s.startsWith("/")) {
            return null;
        }
        String body = s.substring(1);
        String verb;
        String rest;
        int sp = -1;
        for (int i = 0; i < body.length(); i++) {
            if (Character.isWhitespace(body.charAt(i))) {
                sp = i;
                break;
            }
        }
        if (sp < 0) {
            verb = body;
            rest = "";
        } else {
            verb = body.substring(0, sp);
            rest = body.substring(sp + 1).trim();
        }
        List<String> args = new ArrayList<>();
        for (String tok : rest.split("\\s+")) {
            if (!tok.isEmpty()) {
                args.add(tok);
            }
        }
        return new ParsedCommand(verb.toLowerCase(Locale.ROOT), args, rest);
    }

    static String firstToken(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        int sp = t.indexOf(' ');
        return (sp < 0) ? t : t.substring(0, sp);
    }

    static String afterFirstToken(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        int sp = t.indexOf(' ');
        return (sp < 0) ? "" : t.substring(sp + 1).trim();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void saveAccounts() { store.saveAccounts(accounts); }
    private void saveTranscript() { store.saveMessages(transcript, settings.getHistoryLimit()); }
    private void saveSettings() { store.saveSettings(settings); }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    // ------------------------------------------------------------------
    // Dialogs: settings
    // ------------------------------------------------------------------

    private void showSettingsDialog() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JCheckBox timestamps = new JCheckBox("Show timestamps", settings.isShowTimestamps());
        JCheckBox joinsParts = new JCheckBox("Show joins/parts", settings.isShowJoinsParts());
        JCheckBox systemMsgs = new JCheckBox("Show system messages", settings.isShowSystemMessages());
        JCheckBox notify = new JCheckBox("Notify on new message", settings.isNotifyOnMessage());
        JCheckBox colorNicks = new JCheckBox("Colour nicknames", settings.isColorNicknames());
        JCheckBox reconnect = new JCheckBox("Auto-reconnect", settings.isAutoReconnect());
        JTextField fontSize = new JTextField(String.valueOf(settings.getFontSize()));
        JTextField historyLimit = new JTextField(String.valueOf(settings.getHistoryLimit()));
        JTextField reconnectDelay = new JTextField(String.valueOf(settings.getReconnectDelaySeconds()));
        JComboBox<String> defProto = new JComboBox<>(registry.ids().toArray(new String[0]));
        defProto.setSelectedItem(settings.getDefaultProtocolId());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        c.gridx = 1; c.gridy = row++; form.add(timestamps, c);
        c.gridx = 1; c.gridy = row++; form.add(joinsParts, c);
        c.gridx = 1; c.gridy = row++; form.add(systemMsgs, c);
        c.gridx = 1; c.gridy = row++; form.add(notify, c);
        c.gridx = 1; c.gridy = row++; form.add(colorNicks, c);
        c.gridx = 1; c.gridy = row++; form.add(reconnect, c);
        addFormRow(form, c, row++, "Font size", fontSize);
        addFormRow(form, c, row++, "History limit", historyLimit);
        addFormRow(form, c, row++, "Reconnect delay (s)", reconnectDelay);
        addFormRow(form, c, row, "Default protocol", defProto);

        int opt = JOptionPane.showConfirmDialog(this, form, "Messenger Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return;
        }
        settings.setShowTimestamps(timestamps.isSelected());
        settings.setShowJoinsParts(joinsParts.isSelected());
        settings.setShowSystemMessages(systemMsgs.isSelected());
        settings.setNotifyOnMessage(notify.isSelected());
        settings.setColorNicknames(colorNicks.isSelected());
        settings.setAutoReconnect(reconnect.isSelected());
        settings.setFontSize(parseIntOr(fontSize.getText(), settings.getFontSize()));
        settings.setHistoryLimit(parseIntOr(historyLimit.getText(), settings.getHistoryLimit()));
        settings.setReconnectDelaySeconds(
                parseIntOr(reconnectDelay.getText(), settings.getReconnectDelaySeconds()));
        settings.setDefaultProtocolId((String) defProto.getSelectedItem());
        saveSettings();
        initStyles();
        transcriptPane.setFont(new Font("SansSerif", Font.PLAIN, settings.getFontSize()));
        renderTranscript();
        setStatus("Settings saved");
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static void addFormRow(JPanel form, GridBagConstraints c, int row,
                                   String labelText, JComponent field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        form.add(new JLabel(labelText), c);
        c.gridx = 1; c.gridy = row; c.weightx = 1.0;
        form.add(field, c);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Connects every account flagged auto-connect (called by the host window). */
    public void autoConnect() {
        for (AccountConfig a : accounts) {
            if (a.isAutoConnect()) {
                connectAccount(a);
            }
        }
    }

    /** Sets the callback invoked when the user closes the window (Frame3D host). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Disconnects every backend, persists state and runs the close hook. */
    public void shutdown() {
        for (MessengerProtocol p : new ArrayList<>(protocols.values())) {
            try {
                p.disconnect();
            } catch (RuntimeException ex) {
                // Best effort on the way out.
            }
        }
        protocols.clear();
        saveTranscript();
        saveAccounts();
        saveSettings();
        if (onClose != null) {
            onClose.run();
        }
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    JLabel statusLbl() { return statusLabel; }
    JTextField inputField() { return inputField; }
    JTextPane transcriptPane() { return transcriptPane; }
    List<AccountConfig> accounts() { return accounts; }
    List<Conversation> conversations() { return conversations; }
    List<StoredMessage> transcript() { return transcript; }
    MessengerSettings settings() { return settings; }
    ProtocolRegistry registry() { return registry; }
    DefaultListModel<AccountConfig> accountModel() { return accountModel; }
    DefaultListModel<Conversation> conversationModel() { return conversationModel; }

    void selectConversationForTest(Conversation c) { selectConversation(c); }

    /** Adds an account programmatically (used by tests; bypasses the dialog). */
    void addAccountForTest(AccountConfig a) {
        accounts.add(a);
        saveAccounts();
        refreshAccounts();
    }

    /** The transcript text currently rendered, for assertions. */
    String transcriptText() {
        try {
            return doc.getText(0, doc.getLength());
        } catch (Exception ex) {
            return "";
        }
    }

    /** Package-private accessor for the timestamp formatter. */
    static String formatTimestamp(long epochMs) {
        return (epochMs <= 0) ? "" : TS.format(new Date(epochMs));
    }

    /** All conversation keys currently holding a roster (for tests). */
    Set<String> rosterKeys() {
        return new LinkedHashSet<>(rosters.keySet());
    }

    // ------------------------------------------------------------------
    // Inner types
    // ------------------------------------------------------------------

    /**
     * A chat target within one account: the account console (empty target), a
     * channel, or a private peer. Identity is {@code (accountId, target)}.
     */
    static final class Conversation {
        final String accountId;
        final String target;
        final boolean channel;
        private final String label;

        Conversation(String accountId, String target, String label) {
            this.accountId = (accountId == null) ? "" : accountId;
            this.target = (target == null) ? "" : target;
            this.channel = this.target.startsWith("#") || this.target.startsWith("&");
            this.label = (label == null || label.isBlank()) ? this.target : label;
        }

        boolean isConsole() { return target.isEmpty(); }
        boolean isChannel() { return channel; }
        String displayName() { return label; }
        String key() { return accountId + '\u0000' + target; }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Conversation other)) {
                return false;
            }
            return accountId.equals(other.accountId) && target.equals(other.target);
        }

        @Override
        public int hashCode() {
            return accountId.hashCode() * 31 + target.hashCode();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class AccountRenderer extends JLabel
            implements ListCellRenderer<AccountConfig> {
        @Override
        public Component getListCellRendererComponent(JList<? extends AccountConfig> list,
                AccountConfig value, int index, boolean isSelected, boolean cellHasFocus) {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            if (value == null) {
                setText("");
                return this;
            }
            setText(value.toString());
            setToolTipText(value.getProtocolId() + "://" + value.getHost());
            setBackground(isSelected ? ACCENT : list.getBackground());
            setForeground(isSelected ? Color.WHITE : TEXT);
            return this;
        }
    }

    private static final class ConversationRenderer extends JLabel
            implements ListCellRenderer<Conversation> {
        @Override
        public Component getListCellRendererComponent(JList<? extends Conversation> list,
                Conversation value, int index, boolean isSelected, boolean cellHasFocus) {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            if (value == null) {
                setText("");
                return this;
            }
            String prefix = value.isConsole() ? "\u2699 " : (value.isChannel() ? "# " : "@ ");
            setText(value.isConsole() ? value.displayName() : prefix + value.displayName());
            setBackground(isSelected ? ACCENT : list.getBackground());
            setForeground(isSelected ? Color.WHITE
                    : (value.isConsole() ? TEXT_DIM : TEXT));
            return this;
        }
    }
}
