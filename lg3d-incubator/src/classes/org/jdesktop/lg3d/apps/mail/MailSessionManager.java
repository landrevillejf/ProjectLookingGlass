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
package org.jdesktop.lg3d.apps.mail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the live {@link MailService} sessions for every configured account and the
 * cross-cutting behaviour both desktops share: credential resolution, connection
 * caching / reconnect, applying the user's {@link MailRule}s to freshly fetched
 * mail, an optional periodic auto-check, and a "test connection" probe for the
 * account dialog.
 *
 * <p>The manager is deliberately UI-agnostic: it never touches Swing, so it is
 * unit-testable with an injected fake {@link ServiceFactory}. The 2D
 * {@link MailPanel} calls it from a background worker and marshals results back to
 * the EDT itself; the native-3D {@link Mail3D} calls it from its own thread.</p>
 *
 * <p><b>Credentials.</b> A {@link MailAccount.CredentialMode#SAVED} account's
 * password is recovered from the {@link MailAccountStore} (sealed by
 * {@link CredentialVault}); an {@code ASK} account's is obtained from the
 * installed {@link PasswordPrompt} and lives only in memory for the connected
 * session. No password is ever logged.</p>
 */
public class MailSessionManager implements AutoCloseable {

    /** Supplies a password interactively for ASK-mode accounts. */
    public interface PasswordPrompt {
        /** Returns the password, or {@code null} if the user cancelled. */
        String prompt(MailAccount account);
    }

    /** Creates the backend for an account; injectable so tests use a fake. */
    public interface ServiceFactory {
        MailService create(MailAccount account);
    }

    private static final Logger logger =
            Logger.getLogger(MailSessionManager.class.getName());

    private final MailAccountStore accountStore;
    private final MailRuleStore ruleStore;
    private final ServiceFactory factory;
    private final Map<String, MailService> sessions = new HashMap<String, MailService>();

    private PasswordPrompt prompt;
    private ScheduledExecutorService scheduler;

    /** Production manager: real accounts/rules and the Jakarta Mail backend. */
    public MailSessionManager() {
        this(new MailAccountStore(), new MailRuleStore(),
                account -> new ImapSmtpMailService());
    }

    /** Test seam: inject the stores and a fake backend factory. */
    public MailSessionManager(MailAccountStore accountStore,
            MailRuleStore ruleStore, ServiceFactory factory) {
        this.accountStore = accountStore;
        this.ruleStore = ruleStore;
        this.factory = factory;
    }

    public MailAccountStore accounts() {
        return accountStore;
    }

    public MailRuleStore rules() {
        return ruleStore;
    }

    public void setPasswordPrompt(PasswordPrompt prompt) {
        this.prompt = prompt;
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    /**
     * Returns a connected session for the account, connecting (and prompting for a
     * password if needed) on first use or after a dropped connection.
     */
    public synchronized MailService session(String accountId)
            throws MailBackendException {
        MailService existing = sessions.get(accountId);
        if (existing != null && existing.isConnected()) {
            return existing;
        }
        MailAccount account = accountStore.findById(accountId);
        if (account == null) {
            throw new MailBackendException("Unknown account: " + accountId);
        }
        String password = resolvePassword(account);
        if (password == null) {
            throw new MailBackendException("No password available for "
                    + account.getDisplayLabel());
        }
        MailService service = factory.create(account);
        service.connect(account, password);
        sessions.put(accountId, service);
        return service;
    }

    /** Convenience overload taking the account directly. */
    public MailService session(MailAccount account) throws MailBackendException {
        return session(account.getId());
    }

    /**
     * Resolves the password for an account: the sealed value for a SAVED account
     * (falling through to a prompt when none is stored yet), otherwise the
     * interactive prompt. Returns {@code null} when unavailable / cancelled.
     */
    public String resolvePassword(MailAccount account) {
        if (account.getCredentialMode() == MailAccount.CredentialMode.SAVED) {
            String saved = accountStore.getPassword(account.getId());
            if (saved != null) {
                return saved;
            }
        }
        return (prompt == null) ? null : prompt.prompt(account);
    }

    /**
     * Probe used by the account dialog's "Test connection": connects a throwaway
     * session, lists folders, and disconnects. Throws with a user-safe message on
     * failure so the dialog can show it.
     */
    public int testConnection(MailAccount account, String password)
            throws MailBackendException {
        MailService probe = factory.create(account);
        try {
            probe.connect(account, password);
            return probe.listFolders().size();
        } finally {
            probe.disconnect();
        }
    }

    /** Drops a cached session (e.g. after the account's settings change). */
    public synchronized void invalidate(String accountId) {
        MailService s = sessions.remove(accountId);
        if (s != null) {
            s.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Fetch + rules
    // ------------------------------------------------------------------

    /** Fetches a folder's envelopes and applies the user's triage rules. */
    public List<MailMessage> fetch(String accountId, String folder)
            throws MailBackendException {
        MailService service = session(accountId);
        List<MailMessage> messages = new ArrayList<MailMessage>(service.list(folder));
        applyRules(service, messages);
        return messages;
    }

    /**
     * Runs the ordered rule set over freshly fetched messages, mutating flags and
     * removing the ones a MOVE/DELETE rule carried out of the folder. A per-message
     * rule failure is logged and skipped so one bad rule cannot abort the fetch.
     */
    void applyRules(MailService service, List<MailMessage> messages) {
        List<MailRule> rules = ruleStore.load();
        if (rules.isEmpty()) {
            return;
        }
        Iterator<MailMessage> it = messages.iterator();
        while (it.hasNext()) {
            MailMessage m = it.next();
            for (MailRule r : rules) {
                if (!r.matches(m)) {
                    continue;
                }
                try {
                    switch (r.getAction()) {
                        case MARK_READ:
                            service.setRead(m, true);
                            m.setRead(true);
                            break;
                        case FLAG:
                            service.setFlagged(m, true);
                            m.setFlagged(true);
                            break;
                        case MOVE:
                            if (!r.getTargetFolder().isEmpty()) {
                                service.move(m, r.getTargetFolder());
                                it.remove();
                            }
                            break;
                        case DELETE:
                            service.delete(m);
                            it.remove();
                            break;
                        default:
                            break;
                    }
                } catch (MailBackendException e) {
                    logger.log(Level.WARNING,
                            "Rule " + r.getId() + " failed on a message", e);
                }
                // A message moved/deleted left this folder; stop matching it.
                if (r.getAction() == MailRule.Action.MOVE
                        || r.getAction() == MailRule.Action.DELETE) {
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Auto-check
    // ------------------------------------------------------------------

    /**
     * Starts (or restarts) the periodic auto-check. A non-positive interval or a
     * null tick disables it. The tick runs on a daemon thread; the caller is
     * responsible for marshalling any UI update back to the EDT.
     */
    public synchronized void startAutoCheck(int minutes, Runnable tick) {
        stopAutoCheck();
        if (minutes <= 0 || tick == null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mail-autocheck");
            t.setDaemon(true);
            return t;
        });
        Runnable safe = () -> {
            try {
                tick.run();
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Mail auto-check failed", e);
            }
        };
        scheduler.scheduleWithFixedDelay(safe, minutes, minutes, TimeUnit.MINUTES);
    }

    /** Stops the periodic auto-check if it is running. */
    public synchronized void stopAutoCheck() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public synchronized boolean isAutoCheckRunning() {
        return scheduler != null;
    }

    // ------------------------------------------------------------------
    // Teardown
    // ------------------------------------------------------------------

    /** Disconnects every cached session and stops the auto-check. */
    public synchronized void disconnectAll() {
        for (MailService s : sessions.values()) {
            s.disconnect();
        }
        sessions.clear();
    }

    @Override
    public synchronized void close() {
        stopAutoCheck();
        disconnectAll();
    }
}
