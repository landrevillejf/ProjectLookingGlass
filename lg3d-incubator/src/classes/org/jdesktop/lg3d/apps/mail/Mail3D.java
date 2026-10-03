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
import java.util.List;
import org.jdesktop.lg3d.apps.orgchart.ui.agenda.AgendaButton;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * The native 3D e-mail client, sitting beside {@code Agenda3D} / {@code Contact3D}
 * in the same JVM. It is a plain {@link Frame3D} using the standard glassy window
 * decoration; its body is a single live-texture {@link MailView} above a strip of
 * {@link AgendaButton} controls reused from the agenda app.
 *
 * <p>It now drives off the <em>same</em> {@link MailSessionManager} /
 * {@link MailService} model as the 2D {@link MailPanel}: real IMAP folders and
 * messages from the configured accounts. The 3D client is the browse-and-triage
 * surface - it lists and reads the default account's mail and can mark read/unread,
 * flag, delete, move and send a preset <em>quick reply</em> (the 3D desktop routes
 * no keyboard focus, so there is no free-text compose). Full compose, attachments
 * and account/credential entry stay a 2D-panel capability: with no saved credential
 * for an account the 3D view simply reports that it needs one, deferring to the
 * Swing client.</p>
 *
 * <p>Backend calls run on a short-lived worker thread so the 3D universe thread is
 * never blocked on the network; results are pushed into the {@link MailView}, which
 * repaints its fixed texture in place.</p>
 */
public class Mail3D extends Frame3D {

    private static final float DEPTH = 0.01f;

    /** Quick-reply body presets the 3D client cycles through (no keyboard in 3D). */
    private static final String[] REPLIES = {
        "Thanks - got it.",
        "Sounds good, let's proceed.",
        "Can you send more detail?",
        "I'll follow up shortly.",
    };

    private MailSessionManager manager;
    private MailView view;

    private final List<MailFolder> folders = new ArrayList<MailFolder>();
    private final List<MailMessage> messages = new ArrayList<MailMessage>();
    private MailAccount account;
    private String folder = MailMessage.FOLDER_INBOX;
    private MailMessage selected;
    private MailMessage draft;          // non-null while composing a quick reply
    private int replyCursor;

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
            reload();
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
        manager = new MailSessionManager();
        // The 3D desktop routes no keyboard focus, so it cannot prompt for a
        // password: ASK-mode accounts resolve to null and the view tells the user
        // to enter the credential in the 2D Mail panel. SAVED accounts work.
        manager.setPasswordPrompt(a -> null);
        MailAccount def = manager.accounts().defaultAccount();
        if (def == null) {
            List<MailAccount> all = manager.accounts().load();
            account = all.isEmpty() ? null : all.get(0);
        } else {
            account = def;
        }
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

        // Top row: folder switching plus the read-mode triage actions.
        addButton("Inbox", 0, 0, () -> openFolder(MailMessage.FOLDER_INBOX));
        addButton("Sent", 1, 0, () -> openFolder(MailMessage.FOLDER_SENT));
        addButton("Read", 2, 0, () -> toggleRead());
        addButton("Flag", 3, 0, () -> toggleFlag());
        addButton("Del", 4, 0, () -> deleteSelected());
        addButton("Next", 5, 0, () -> selectNext());

        // Bottom row: quick-reply compose plus move / refresh.
        addButton("Reply", 0, 1, () -> startReply());
        addButton("Body", 1, 1, () -> cycleReplyBody());
        addButton("Send", 2, 1, () -> sendDraft());
        addButton("Back", 3, 1, () -> cancelDraft());
        addButton("Move", 4, 1, () -> moveSelected());
        addButton("Get", 5, 1, () -> reload());

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
    // Backend plumbing (off the 3D thread)
    // ------------------------------------------------------------------

    /** A backend task that may fail with a {@link MailBackendException}. */
    private interface Task {
        void run() throws MailBackendException;
    }

    /** Runs a backend task on a worker thread, then repaints the view. */
    private void async(Task task) {
        Thread t = new Thread(() -> {
            try {
                task.run();
            } catch (MailBackendException e) {
                view.setStatus(e.getMessage());
            } catch (RuntimeException e) {
                view.setStatus("Mail error: " + e.getMessage());
            }
            refreshView();
        }, "mail3d-worker");
        t.setDaemon(true);
        t.start();
    }

    private void requireAccount() throws MailBackendException {
        if (account == null) {
            throw new MailBackendException(
                    "No account configured - add one in the 2D Mail panel.");
        }
    }

    /** Reconnects, lists folders and loads the current folder. */
    private void reload() {
        async(() -> {
            requireAccount();
            MailService s = manager.session(account.getId());
            folders.clear();
            folders.addAll(s.listFolders());
            if (!hasFolder(folder)) {
                folder = MailMessage.FOLDER_INBOX;
            }
            loadCurrentFolder(s);
        });
    }

    private boolean hasFolder(String name) {
        for (MailFolder f : folders) {
            if (f.getName().equals(name)) {
                return true;
            }
        }
        return folders.isEmpty();
    }

    private void loadCurrentFolder(MailService s) throws MailBackendException {
        List<MailMessage> fetched = manager.fetch(account.getId(), folder);
        messages.clear();
        messages.addAll(fetched);
        selected = null;
        view.setStatus("");
    }

    // ------------------------------------------------------------------
    // Read / browse actions
    // ------------------------------------------------------------------

    private void openFolder(String target) {
        if (draft != null || folder.equals(target)) {
            return;
        }
        folder = target;
        async(() -> {
            requireAccount();
            loadCurrentFolder(manager.session(account.getId()));
        });
    }

    /** Opens a clicked message, fetching its body and marking it read. */
    private void openMessage(MailMessage m) {
        if (draft != null || m == null) {
            return;
        }
        async(() -> {
            requireAccount();
            MailService s = manager.session(account.getId());
            MailMessage full = s.open(m);
            if (!full.isRead()) {
                s.setRead(full, true);
                full.setRead(true);
            }
            int idx = messages.indexOf(m);
            if (idx >= 0) {
                messages.set(idx, full);
            }
            selected = full;
        });
    }

    /** Moves the selection to the next message in the current folder. */
    private void selectNext() {
        if (draft != null || messages.isEmpty()) {
            return;
        }
        int i = messages.indexOf(selected);
        openMessage(messages.get((i + 1) % messages.size()));
    }

    private void toggleRead() {
        if (draft != null || selected == null) {
            return;
        }
        final MailMessage m = selected;
        final boolean to = !m.isRead();
        async(() -> {
            requireAccount();
            manager.session(account.getId()).setRead(m, to);
            m.setRead(to);
        });
    }

    private void toggleFlag() {
        if (draft != null || selected == null) {
            return;
        }
        final MailMessage m = selected;
        final boolean to = !m.isFlagged();
        async(() -> {
            requireAccount();
            manager.session(account.getId()).setFlagged(m, to);
            m.setFlagged(to);
        });
    }

    private void deleteSelected() {
        if (draft != null || selected == null) {
            return;
        }
        final MailMessage m = selected;
        async(() -> {
            requireAccount();
            manager.session(account.getId()).delete(m);
            messages.remove(m);
            selected = null;
        });
    }

    /** Moves the selection to the next folder that is not the current one. */
    private void moveSelected() {
        if (draft != null || selected == null) {
            return;
        }
        final MailMessage m = selected;
        final String target = nextFolderAfter(folder);
        if (target == null) {
            view.setStatus("No other folder to move to.");
            return;
        }
        async(() -> {
            requireAccount();
            manager.session(account.getId()).move(m, target);
            messages.remove(m);
            selected = null;
            view.setStatus("Moved to " + target + ".");
        });
    }

    private String nextFolderAfter(String current) {
        if (folders.size() < 2) {
            return null;
        }
        int idx = -1;
        for (int i = 0; i < folders.size(); i++) {
            if (folders.get(i).getName().equals(current)) {
                idx = i;
                break;
            }
        }
        MailFolder f = folders.get((idx + 1) % folders.size());
        return f.getName();
    }

    // ------------------------------------------------------------------
    // Quick-reply compose
    // ------------------------------------------------------------------

    private void startReply() {
        if (draft != null || selected == null) {
            return;
        }
        replyCursor = 0;
        draft = new MailMessage();
        draft.setAccountId(account == null ? "" : account.getId());
        draft.setFrom(account == null ? MailAddress.of("") : account.fromAddress());
        draft.setTo(Collections.singletonList(selected.getFrom()));
        String subject = selected.getSubject();
        draft.setSubject(subject.toLowerCase().startsWith("re:")
                ? subject : "Re: " + subject);
        draft.setTextBody(REPLIES[0]);
        draft.setFolder(MailMessage.FOLDER_SENT);
        refreshView();
    }

    private void cycleReplyBody() {
        if (draft == null) {
            return;
        }
        replyCursor = (replyCursor + 1) % REPLIES.length;
        draft.setTextBody(REPLIES[replyCursor]);
        refreshView();
    }

    private void sendDraft() {
        if (draft == null) {
            return;
        }
        final MailMessage d = draft;
        async(() -> {
            requireAccount();
            manager.session(account.getId()).send(d, new ArrayList<MailAttachment>());
            draft = null;
            view.setStatus("Message sent.");
        });
    }

    private void cancelDraft() {
        if (draft == null) {
            return;
        }
        draft = null;
        refreshView();
    }

    // ------------------------------------------------------------------
    // View
    // ------------------------------------------------------------------

    /** Pushes the current folder / selection / draft state into the view. */
    private void refreshView() {
        int unread = 0;
        for (MailMessage m : messages) {
            if (!m.isRead()) {
                unread++;
            }
        }
        view.setAccount(account == null ? "" : account.getDisplayLabel());
        view.setFolder(folder);
        view.setList(messages, unread);
        view.setSelected(selected);
        view.setDraft(draft);
    }
}
