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
package org.jdesktop.lg3d.apps.orgchart.ui.agenda;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jdesktop.lg3d.apps.mail.MailAccount;
import org.jdesktop.lg3d.apps.mail.MailAccountStore;
import org.jdesktop.lg3d.apps.mail.MailBackendException;
import org.jdesktop.lg3d.apps.mail.MailMessage;
import org.jdesktop.lg3d.apps.mail.MailService;
import org.jdesktop.lg3d.apps.mail.MailSessionManager;

/**
 * Sends real meeting invitations for an {@link Appointment}: it resolves the
 * desktop's default mail account, composes the RFC 5545
 * {@code METHOD:REQUEST} payload with {@link InvitationBuilder}, and hands the
 * draft plus its {@code invite.ics} attachment to the mail app's existing
 * Jakarta Mail backend through {@link MailSessionManager}.
 *
 * <p>The sender is deliberately UI-agnostic (it never touches Swing or Java 3D)
 * so both the 2D {@link AgendaPanel} and the native-3D {@link Agenda3D} can
 * share it and the whole send path is unit-testable headlessly with an injected
 * fake {@link MailSessionManager.ServiceFactory}. Callers run {@link #send}
 * off the event thread themselves — SMTP is a blocking network round trip.</p>
 *
 * <p><b>Credentials.</b> Password handling is exactly the mail app's: a
 * SAVED-mode account's sealed password is recovered silently; an ASK-mode
 * account needs a {@link MailSessionManager.PasswordPrompt} installed by the
 * UI (the 2D panel installs one; the 3D app, like {@code Mail3D}, does not and
 * reports the manager's user-safe "no password" error instead). No password is
 * ever logged.</p>
 */
public class InvitationSender {

    private final MailSessionManager manager;

    /** Production sender over the real accounts and the Jakarta Mail backend. */
    public InvitationSender() {
        this(new MailSessionManager());
    }

    /** Test seam: inject a manager wired to a fake {@link MailService}. */
    public InvitationSender(final MailSessionManager manager) {
        this.manager = manager;
    }

    /** The mail accounts this sender resolves its organizer from. */
    public MailAccountStore accounts() {
        return manager.accounts();
    }

    /** Installs the interactive password prompt for ASK-mode accounts. */
    public void setPasswordPrompt(final MailSessionManager.PasswordPrompt prompt) {
        manager.setPasswordPrompt(prompt);
    }

    /**
     * Invites the given contacts to one occurrence of the appointment and
     * returns how many invitations actually went out.
     *
     * @param a        the appointment to invite to
     * @param date     the concrete calendar date of the occurrence
     * @param invitees the resolved contacts (entries without an e-mail are skipped)
     * @return the number of recipients the invitation was sent to
     * @throws MailBackendException with a user-safe message when no invitee has
     *         an address, no mail account is configured, the password is
     *         unavailable or the SMTP send fails
     */
    public int send(final Appointment a, final LocalDate date,
            final List<ContactDirectory.ContactInfo> invitees)
            throws MailBackendException {
        List<ContactDirectory.ContactInfo> withMail = withEmail(invitees);
        if (withMail.isEmpty()) {
            throw new MailBackendException("None of the invited contacts has an "
                    + "e-mail address; add one in the Contacts app first.");
        }
        MailAccount organizer = manager.accounts().defaultAccount();
        if (organizer == null || !organizer.isComplete()) {
            throw new MailBackendException("No mail account is configured yet. "
                    + "Add one in the Mail app (Settings \u2192 Accounts) before "
                    + "sending invitations.");
        }
        ZoneId zone = ZoneId.systemDefault();
        String ics = InvitationBuilder.buildICalendar(
                a, date, zone, Instant.now(), organizer, withMail);
        MailMessage draft = InvitationBuilder.buildDraft(a, date, zone, organizer, withMail);
        MailService service = manager.session(organizer);
        service.send(draft,
                Collections.singletonList(InvitationBuilder.buildAttachment(ics)));
        return withMail.size();
    }

    /** The subset of invitees that actually carry an e-mail address. */
    static List<ContactDirectory.ContactInfo> withEmail(
            final List<ContactDirectory.ContactInfo> invitees) {
        List<ContactDirectory.ContactInfo> out =
                new ArrayList<ContactDirectory.ContactInfo>();
        if (invitees != null) {
            for (ContactDirectory.ContactInfo c : invitees) {
                if (c != null && c.email != null && !c.email.isBlank()) {
                    out.add(c);
                }
            }
        }
        return out;
    }
}
