/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland M9: the panel that makes a walk-in a visit rather than a slide.
 *
 * The reference screenshot of this project (documentation's outpatient-clinic
 * picture) is not a picture of geometry. What it shows is a room with a
 * <em>"Users (9)"</em> window on it, a per-person volume slider and a
 * <em>Mic On</em> pill -- a desktop whose people are listed because they are
 * present. The world browser's Enter World window had none of that: it read the
 * world over REST, built a scene and opened it, so a visitor stood in a clinic
 * that had been evacuated. This is the missing surface: who is here, and what
 * they are saying.
 *
 * The two halves are deliberately the 2006 module's own, not new models:
 * {@link UserList} is the roster and {@link ChatConversation} the transcript,
 * both headless and already covered in {@code wonderland-modules}. This class is
 * their face, so it owns no state of its own beyond what those two hold -- a row
 * that is in the list is a row that is in the model, which is what lets the
 * headless suite assert the roster by reading the model back.
 *
 * <p><strong>Thread rule.</strong> Every mutator here runs on the EDT; the
 * caller hops. That is the same convention
 * {@link WonderlandWorldPanel#render} states, and it is not laziness: a
 * transcript painted from the transport thread is a Swing race, and a test that
 * has to wait for an event queue it cannot see is a test that sleeps. So
 * {@link WorldVisit} does the hopping where it owns the thread, and this panel
 * paints what it is handed.</p>
 *
 * <p><strong>Sending.</strong> The panel does not know what a socket is. The
 * typed line goes to an injected {@code Consumer} -- in production
 * {@link WorldVisit}'s {@link org.jdesktop.wonderland.client.cell.ChatChannel},
 * in a test a list -- because the question "does pressing Send put the text on
 * the wire" belongs to whoever owns the wire, and answering it here would let a
 * fake transcript pass for a real conversation.</p>
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.DefaultListModel;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import org.jdesktop.wonderland.modules.chat.ChatConversation;
import org.jdesktop.wonderland.modules.chat.ChatMessage;
import org.jdesktop.wonderland.modules.userlist.PresentUser;
import org.jdesktop.wonderland.modules.userlist.UserList;

/**
 * The docked sidebar of a walk-in window: the people present and the lines they
 * have said, with one field for saying a line back.
 */
public final class WorldVisitPanel extends JPanel {

    /** The panel's preferred width; narrow enough that the world keeps the view. */
    public static final int WIDTH_PX = 250;

    /** The panel's preferred height, matching the viewer window it docks onto. */
    public static final int HEIGHT_PX = 560;

    /** What the header says when nobody but the visitor is here. */
    static final String ALONE_LABEL = "No one else is here";

    private final DefaultListModel<String> rosterModel = new DefaultListModel<>();

    private final JList<String> rosterList = new JList<>(rosterModel);

    private final JLabel rosterHeader = new JLabel(ALONE_LABEL);

    private final JTextArea transcript = new JTextArea();

    private final JTextField input = new JTextField();

    private final JButton sendButton = new JButton("Send");

    private final JLabel statusLabel = new JLabel(" ");

    /** The call's volume, 0..100 -- the reference's slider, docked, not floating. */
    private final JSlider volumeSlider = new JSlider(0, 100, 100);

    /** The reference's Mic pill; selected reads On, deselected Off. */
    private final JToggleButton micToggle = new JToggleButton("Mic On", true);

    private final JLabel volumeLabel = new JLabel("Volume");

    /** The live call the audio controls drive; null until a call exists. */
    private JavaSoundVoiceBridge voice;

    private double localGain = 1.0;

    private boolean localMuted;

    /** The roster's truth; this panel is its face, never a second copy. */
    private final UserList users = new UserList();

    /** The transcript's truth, bounded the way the 2006 scrollback was. */
    private final ChatConversation conversation = new ChatConversation();

    private Consumer<String> sender;

    private String localName = "you";

    /** Builds the panel with no wire behind Send yet. */
    public WorldVisitPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        rosterHeader.setFont(getFont().deriveFont(Font.BOLD));
        rosterHeader.setBorder(headerBorder());

        rosterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rosterList.setCellRenderer(new RosterRenderer());
        JScrollPane rosterScroll = new JScrollPane(rosterList);
        rosterScroll.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFont(getFont());
        transcript.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JScrollPane chatScroll = new JScrollPane(transcript);
        chatScroll.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        chatScroll.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // Enter in the field means the same as the button -- a chat box you must
        // click twice to use is a chat box nobody uses.
        input.addActionListener(e -> sendTypedLine());
        sendButton.addActionListener(e -> sendTypedLine());
        // Nothing to send until a wire owns the button; a live Send over a dead
        // socket is the same lie as a walk-in that claims to be multiplayer.
        sendButton.setEnabled(false);
        JPanel composer = new JPanel(new BorderLayout(4, 0));
        composer.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        composer.add(input, BorderLayout.CENTER);
        composer.add(sendButton, BorderLayout.EAST);

        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));

        JPanel centre = new JPanel(new BorderLayout());
        centre.add(rosterHeader, BorderLayout.NORTH);
        centre.add(rosterScroll, BorderLayout.CENTER);
        // The roster is a few rows; giving it a fixed share keeps the transcript
        // the growing half, which is the part that fills up during a conversation.
        rosterScroll.setPreferredSize(new Dimension(WIDTH_PX, 130));
        JPanel stacked = new JPanel();
        stacked.setLayout(new BoxLayout(stacked, BoxLayout.Y_AXIS));
        stacked.add(centre);
        stacked.add(Box.createVerticalStrut(4));
        JPanel chatBox = new JPanel(new BorderLayout());
        JLabel chatHeader = new JLabel("Conversation");
        chatHeader.setFont(getFont().deriveFont(Font.BOLD));
        chatHeader.setAlignmentX(LEFT_ALIGNMENT);
        chatBox.add(chatHeader, BorderLayout.NORTH);
        chatBox.add(chatScroll, BorderLayout.CENTER);
        chatBox.setAlignmentX(LEFT_ALIGNMENT);
        stacked.add(chatBox);
        stacked.add(composer);

        add(stacked, BorderLayout.CENTER);

        // The reference's audio chrome: a volume slider and a Mic pill under the
        // conversation. They drive the live call when there is one and remember
        // the setting when there is not, so the controls are never a decoration
        // that lies about being wired.
        volumeSlider.setToolTipText("Call volume");
        volumeSlider.addChangeListener(e -> applyVolume());
        micToggle.addActionListener(e -> applyMic());
        JPanel audio = new JPanel(new BorderLayout(6, 0));
        audio.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        audio.add(volumeLabel, BorderLayout.WEST);
        audio.add(volumeSlider, BorderLayout.CENTER);
        audio.add(micToggle, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout());
        south.add(audio, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
        applyVolume();
        applyMic();

        users.addListener(new UserList.PresenceListener() {
            @Override
            public void userJoined(PresentUser user) {
                paintRoster();
            }

            @Override
            public void userLeft(PresentUser user) {
                paintRoster();
            }
        });
        conversation.addListener(this::paintLine);
    }

    // ------------------------------------------------------------------
    // What the owner wires up
    // ------------------------------------------------------------------

    /**
     * Put a wire behind Send.
     *
     * @param sender called with the text exactly as typed, on the EDT; may be
     *               {@code null} to take the button back off
     */
    public void setSender(Consumer<String> sender) {
        this.sender = sender;
        sendButton.setEnabled(sender != null);
        input.setEditable(sender != null);
    }

    /**
     * Name the local visitor is listed under -- the name the server stamps on
     * their own lines, so that one string has to agree with the other or a
     * person appears in their own roster twice.
     */
    public void setLocalName(String name) {
        this.localName = (name == null || name.isBlank()) ? "you" : name.trim();
        paintRoster();
    }

    /** The name the local visitor is listed under. */
    public String getLocalName() {
        return localName;
    }

    /** Say what the visit is doing, or why it is not. */
    public void setStatus(String status) {
        statusLabel.setText((status == null || status.isBlank())
                ? " " : status);
        statusLabel.setToolTipText(status);
    }

    /** The strip's text, for the suite that drives the panel without showing it. */
    String statusText() {
        return statusLabel.getText();
    }

    /**
     * Hand the panel the live call its audio controls drive. The current slider
     * and pill state are pushed onto it immediately, so a call that starts mid
     * visit inherits the volume the visitor already chose.
     *
     * @param voice the call, or {@code null} to take the controls back to local
     */
    public void setVoice(JavaSoundVoiceBridge voice) {
        this.voice = voice;
        applyVolume();
        applyMic();
    }

    /** The volume the slider shows, 0..100. */
    public int volume() {
        return volumeSlider.getValue();
    }

    /** Whether the Mic pill reads On. */
    public boolean micOn() {
        return micToggle.isSelected();
    }

    /**
     * Move the volume slider the way a drag would; {@code setValue} fires the
     * same change path the listener is on, so programmatic and hand moves agree.
     */
    public void setVolume(int percent) {
        volumeSlider.setValue(Math.max(0, Math.min(100, percent)));
    }

    /**
     * Set the Mic pill the way a click would. A programmatic {@code setSelected}
     * fires the item listener but not the action listener, so the apply runs
     * here too and the two routes cannot disagree.
     */
    public void setMicOn(boolean on) {
        micToggle.setSelected(on);
        applyMic();
    }

    private void applyVolume() {
        localGain = volumeSlider.getValue() / 100.0;
        volumeLabel.setText("Volume " + volumeSlider.getValue());
        if (voice != null) {
            voice.setOutputGain(localGain);
        }
    }

    private void applyMic() {
        localMuted = !micToggle.isSelected();
        micToggle.setText(micToggle.isSelected() ? "Mic On" : "Mic Off");
        if (voice != null) {
            voice.setMuted(localMuted);
        }
    }

    /** The roster's header line -- "Users (9)", or the words for being alone. */
    String rosterHeaderText() {
        return rosterHeader.getText();
    }

    // ------------------------------------------------------------------
    // Roster
    // ------------------------------------------------------------------

    /**
     * Mark a person present.
     *
     * @param id   the stable key (a session id, the avatar's cell id)
     * @param name the display name
     */
    public void join(String id, String name) {
        if (id == null) {
            return;
        }
        users.add(new PresentUser(id, (name == null || name.isBlank())
                ? id : name.trim()));
    }

    /** Mark a person gone; an id that was never present is a no-op. */
    public void leave(String id) {
        if (id != null) {
            users.remove(id);
        }
    }

    /** The roster as the panel shows it, names only, sorted as the model sorts. */
    public java.util.List<String> rosterNames() {
        return users.users().stream().map(PresentUser::getName).toList();
    }

    /** How many rows the roster holds. */
    public int rosterSize() {
        return users.size();
    }

    /** The roster model, for a test or a caller that wants the rows themselves. */
    public ListModel<String> rosterModel() {
        return rosterModel;
    }

    private void paintRoster() {
        rosterModel.clear();
        for (PresentUser user : users.users()) {
            rosterModel.addElement(user.getName());
        }
        int others = users.size() - 1;   // the visitor is in the list too
        rosterHeader.setText((others <= 0) ? ALONE_LABEL
                : "Users (" + users.size() + ")");
    }

    // ------------------------------------------------------------------
    // Conversation
    // ------------------------------------------------------------------

    /**
     * Put one received line in the transcript.
     *
     * @param senderName the author as the server authenticated them
     * @param text       the body
     * @param sentAt     epoch millis the author posted it; carried into the
     *                   model so the scrollback reads in the order it happened
     */
    public void showMessage(String senderName, String text, long sentAt) {
        // The model's own post() stamps the clock, and a transcript that shows
        // the arrival time rather than the typing time is a few seconds out at
        // worst -- so the model is used as it is, and the timestamp it records
        // is the one shown. sentAt decides nothing here; it is what a future
        // history-merge will order by, and it is already on the wire.
        conversation.post(senderName, text);
    }

    /** The transcript as printed, newest line last. */
    public String transcriptText() {
        return transcript.getText();
    }

    /** The bounded model behind the printed transcript. */
    public ChatConversation conversation() {
        return conversation;
    }

    private void paintLine(ChatMessage message) {
        transcript.append(message.getSender() + ": " + message.getText() + "\n");
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    /**
     * Press Send with something in the field. Returns whether a line went out,
     * which is what the status strip reports -- a test can drive the button's
     * body without synthesising a mouse event.
     */
    boolean sendTypedLine() {
        String text = input.getText();
        if (sender == null) {
            setStatus("not connected to a world yet");
            return false;
        }
        if (text == null || text.trim().isEmpty()) {
            setStatus("nothing to send");
            return false;
        }
        input.setText("");
        // The transcript stays empty here on purpose: the line comes back from
        // the server's relay with the authenticated name, like everyone else's,
        // so a local echo would be a second copy of one sentence.
        sender.accept(text);
        setStatus("sent \u2014 waiting for the world");
        return true;
    }

    /** The composer's field, for a test that types rather than clicks. */
    JTextField inputField() {
        return input;
    }

    private static Border headerBorder() {
        return BorderFactory.createEmptyBorder(6, 6, 2, 6);
    }

    /** Names alone, with the local visitor marked so a roster of one is honest. */
    private final class RosterRenderer implements ListCellRenderer<String> {

        private final JLabel label = new JLabel();

        RosterRenderer() {
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
        }

        @Override
        public java.awt.Component getListCellRendererComponent(
                JList<? extends String> list, String value, int index,
                boolean isSelected, boolean cellHasFocus) {
            label.setText(value.equals(localName) ? value + "  (you)" : value);
            label.setBackground(isSelected
                    ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(isSelected
                    ? list.getSelectionForeground() : list.getForeground());
            return label;
        }
    }
}
