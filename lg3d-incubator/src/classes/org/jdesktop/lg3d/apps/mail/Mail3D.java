/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.mail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaButton;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.ContactDirectory;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * A native 3D e-mail client that sits beside {@code Agenda3D} / {@code Contact3D}
 * in the same JVM. Like those, it is a plain {@link Frame3D} using the standard
 * glassy window decoration (title-bar buttons plus the CTRL + right-click
 * flip-to-sticky gesture); its body is a single live-texture {@link MailView}
 * (a message list over a reading / compose pane) above a strip of
 * {@link AgendaButton} controls reused from the agenda app.
 *
 * <p>The mailbox is local-only and persisted to the user
 * {@link java.util.prefs.Preferences} tree under {@link MailStore#ROOT}, so it
 * survives across launches; "sending" a message files it in the Sent folder
 * rather than talking SMTP, which keeps the compose / reply / send loop fully
 * exercisable offline. Recipients are drawn from the same shared
 * {@code /contacts} directory {@code Contact3D} populates, read through
 * {@link ContactDirectory}, so the mail client shares one address book with the
 * rest of the suite.</p>
 *
 * <p>Interaction is button-driven (dev mode has no keyboard focus routing):
 * click a message in the list to open and mark it read; {@code Inbox}/{@code
 * Sent} switch folders and {@code Next} walks the selection; {@code New}/{@code
 * Reply} open a draft; {@code To}/{@code Subj}/{@code Body} cycle the draft's
 * fields; {@code Send} files it and jumps to Sent while {@code Back} discards
 * it; {@code Read} toggles the unread flag and {@code Del} removes the
 * selection. Compose-only actions are inert while reading and vice versa, so a
 * draft is never clobbered by accident. Every accepted change is saved
 * immediately.</p>
 */
public class Mail3D extends Frame3D {

    private static final float DEPTH = 0.01f;

    /** The local identity outgoing messages are sent from. */
    private static final String ME = "You";
    private static final String ME_EMAIL = "you@example.com";

    /** Recipient fallback when the shared contact directory is empty. */
    private static final String FALLBACK_TO = "Friend";
    private static final String FALLBACK_EMAIL = "friend@example.com";

    /** Subject / body presets the compose buttons cycle through. */
    private static final String[] SUBJECTS = {
        "Quick note", "Following up", "Status update", "A question", "Thank you"
    };
    private static final String[] BODIES = {
        "Hi,\n\nJust a quick note from Mail 3D. Everything here is stored\n"
            + "locally in your preferences, so it survives a restart.\n\n"
            + "Cheers,\nYou",
        "Hi,\n\nFollowing up on my earlier message. Let me know your thoughts\n"
            + "whenever you get a chance.\n\nBest,\nYou",
        "Hi,\n\nShort status update: the Gradle build is green on JDK 21 and\n"
            + "the desktop boots clean in dev mode.\n\nRegards,\nYou",
    };

    private MailStore store;
    private ContactDirectory directory;
    private final List<MailMessage> all = new ArrayList<MailMessage>();

    private MailView view;
    private String folder = MailMessage.FOLDER_INBOX;
    private MailMessage selected;
    private MailMessage draft;          // non-null while composing
    private int contactCursor;
    private int subjectCursor;
    private int bodyCursor;

    // Computed layout (physical world units).
    private float width;
    private float height;
    private float mailW;
    private float mailH;
    private float mailCenterY;
    private float btnW;
    private float btnH;
    private float startX;
    private float bottomY;
    private float colGap;
    private float rowGap;

    public static void main(String[] args) {
        new Mail3D();
    }

    public Mail3D() {
        super();
        try {
            setName("Mail 3D");
            computeLayout();
            setPreferredSize(new Vector3f(width, height, DEPTH));
            initData();
            createUI();
            setVisible(true);
            changeEnabled(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start Mail3D", e);
        }
    }

    private void computeLayout() {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        height = tk.getScreenHeight() * 0.5f;
        width = height * 1.6f;

        float topMargin = height * 0.10f;    // clears the corner window buttons
        float bottomMargin = height * 0.03f;
        float controlH = height * 0.17f;
        float gap = height * 0.025f;

        mailH = height - topMargin - bottomMargin - controlH - gap;
        mailW = mailH * 2.0f;                // match the 2:1 mail texture
        float maxW = width * 0.96f;
        if (mailW > maxW) {
            mailW = maxW;
            mailH = mailW / 2.0f;
        }
        mailCenterY = height * 0.5f - topMargin - mailH * 0.5f;

        int cols = 6;
        colGap = mailW * 0.012f;
        btnW = (mailW - (cols - 1) * colGap) / cols;
        btnH = controlH * 0.40f;
        rowGap = controlH * 0.14f;
        startX = -mailW * 0.5f + btnW * 0.5f;
        bottomY = -height * 0.5f + bottomMargin + btnH * 0.5f;
    }

    private void initData() {
        store = new MailStore();
        store.seedIfEmpty();
        all.addAll(store.load());
        directory = new ContactDirectory();
    }

    private void createUI() {
        view = new MailView(mailW, mailH);
        view.setTranslation(0.0f, mailCenterY, 0.001f);
        view.setMailListener(new MailView.MailListener() {
            public void messageSelected(MailMessage message) {
                openMessage(message);
            }
        });
        addChild(view);

        // Top row: folder switching plus the read-mode message actions.
        addButton("Inbox", 0, 0, new Runnable() {
            public void run() { openFolder(MailMessage.FOLDER_INBOX); } });
        addButton("Sent", 1, 0, new Runnable() {
            public void run() { openFolder(MailMessage.FOLDER_SENT); } });
        addButton("New", 2, 0, new Runnable() {
            public void run() { newMessage(); } });
        addButton("Reply", 3, 0, new Runnable() {
            public void run() { reply(); } });
        addButton("Read", 4, 0, new Runnable() {
            public void run() { toggleRead(); } });
        addButton("Del", 5, 0, new Runnable() {
            public void run() { deleteSelected(); } });

        // Bottom row: the compose actions (plus Next to walk the selection).
        addButton("To", 0, 1, new Runnable() {
            public void run() { cycleTo(); } });
        addButton("Subj", 1, 1, new Runnable() {
            public void run() { cycleSubject(); } });
        addButton("Body", 2, 1, new Runnable() {
            public void run() { cycleBody(); } });
        addButton("Send", 3, 1, new Runnable() {
            public void run() { send(); } });
        addButton("Back", 4, 1, new Runnable() {
            public void run() { cancel(); } });
        addButton("Next", 5, 1, new Runnable() {
            public void run() { selectNext(); } });

        refreshView();
    }

    private void addButton(String label, int col, int row, Runnable action) {
        AgendaButton button = new AgendaButton(label, btnW, btnH,
                new ActionNoArg() {
                    public void performAction(LgEventSource source) {
                        action.run();
                    }
                });
        button.setTranslation(colX(col), rowY(row), 0.002f);
        addChild(button);
    }

    private float colX(int col) {
        return startX + col * (btnW + colGap);
    }

    private float rowY(int row) {
        return bottomY + (1 - row) * (btnH + rowGap);
    }

    // ------------------------------------------------------------------
    // Read / browse actions
    // ------------------------------------------------------------------

    private void openFolder(String target) {
        if (draft != null || folder.equals(target)) {
            return;
        }
        folder = target;
        selected = null;
        refreshView();
    }

    /** Opens a clicked message, marking it read (ignored while composing). */
    private void openMessage(MailMessage m) {
        if (draft != null) {
            return;
        }
        selected = m;
        if (!m.isRead()) {
            m.setRead(true);
            store.save(m);
        }
        refreshView();
    }

    /** Moves the selection to the next message in the current folder. */
    private void selectNext() {
        if (draft != null) {
            return;
        }
        List<MailMessage> list = currentFolderMessages();
        if (list.isEmpty()) {
            return;
        }
        int i = list.indexOf(selected);
        openMessage(list.get((i + 1) % list.size()));
    }

    private void toggleRead() {
        if (draft != null || selected == null) {
            return;
        }
        selected.setRead(!selected.isRead());
        store.save(selected);
        refreshView();
    }

    private void deleteSelected() {
        if (draft != null || selected == null) {
            return;
        }
        all.remove(selected);
        store.delete(selected.getId());
        selected = null;
        refreshView();
    }

    // ------------------------------------------------------------------
    // Compose actions
    // ------------------------------------------------------------------

    private void newMessage() {
        if (draft != null) {
            return;
        }
        subjectCursor = 0;
        bodyCursor = 0;
        ContactDirectory.ContactInfo c = nextContact();
        String toName = (c != null) ? c.displayName : FALLBACK_TO;
        String toEmail = (c != null && c.email != null) ? c.email : FALLBACK_EMAIL;
        draft = new MailMessage(store.newId(), ME, ME_EMAIL, toName, toEmail,
                SUBJECTS[0], BODIES[0], MailMessage.FOLDER_SENT);
        refreshView();
    }

    private void reply() {
        if (draft != null || selected == null) {
            return;
        }
        MailMessage src = selected;
        String subject = src.getSubject();
        if (!subject.startsWith("Re: ")) {
            subject = "Re: " + subject;
        }
        String quoted = src.getBody().replace("\n", "\n> ");
        String body = "Hi " + src.getFrom() + ",\n\nThanks for the note.\n\n> "
                + quoted;
        subjectCursor = 0;
        bodyCursor = 0;
        draft = new MailMessage(store.newId(), ME, ME_EMAIL,
                src.getFrom(), src.getFromEmail(), subject, body,
                MailMessage.FOLDER_SENT);
        refreshView();
    }

    private void cycleTo() {
        if (draft == null) {
            return;
        }
        ContactDirectory.ContactInfo c = nextContact();
        if (c != null) {
            draft.setTo(c.displayName, (c.email != null) ? c.email : "");
        }
        view.refresh();
    }

    private void cycleSubject() {
        if (draft == null) {
            return;
        }
        subjectCursor = (subjectCursor + 1) % SUBJECTS.length;
        draft.setSubject(SUBJECTS[subjectCursor]);
        view.refresh();
    }

    private void cycleBody() {
        if (draft == null) {
            return;
        }
        bodyCursor = (bodyCursor + 1) % BODIES.length;
        draft.setBody(BODIES[bodyCursor]);
        view.refresh();
    }

    /** Files the draft into the Sent folder and jumps the view there. */
    private void send() {
        if (draft == null) {
            return;
        }
        store.save(draft);
        all.add(draft);
        selected = draft;
        draft = null;
        folder = MailMessage.FOLDER_SENT;
        refreshView();
    }

    /** Discards the draft without saving it. */
    private void cancel() {
        if (draft == null) {
            return;
        }
        draft = null;
        refreshView();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Returns the next shared contact (cycling), or null if none exist. */
    private ContactDirectory.ContactInfo nextContact() {
        List<ContactDirectory.ContactInfo> contacts = directory.getContacts();
        if (contacts.isEmpty()) {
            return null;
        }
        ContactDirectory.ContactInfo c = contacts.get(contactCursor % contacts.size());
        contactCursor = (contactCursor + 1) % contacts.size();
        return c;
    }

    /** The current folder's messages, newest first. */
    private List<MailMessage> currentFolderMessages() {
        List<MailMessage> out = new ArrayList<MailMessage>();
        for (MailMessage m : all) {
            if (folder.equals(m.getFolder())) {
                out.add(m);
            }
        }
        Collections.sort(out, new Comparator<MailMessage>() {
            public int compare(MailMessage a, MailMessage b) {
                return Long.compare(b.getWhen(), a.getWhen());
            }
        });
        return out;
    }

    /** Pushes the current folder / selection / draft state into the view. */
    private void refreshView() {
        List<MailMessage> list = currentFolderMessages();
        int unread = 0;
        for (MailMessage m : list) {
            if (!m.isRead()) {
                unread++;
            }
        }
        view.setFolder(folder);
        view.setList(list, unread);
        view.setSelected(selected);
        view.setDraft(draft);
    }
}
