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
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Persists the configured {@link MailAccount}s under the {@code /mail/accounts}
 * {@link Preferences} node - one child node per account - and owns the sealed
 * password of any account in {@link MailAccount.CredentialMode#SAVED} mode
 * (via {@link CredentialVault}).
 *
 * <p>Accounts are kept in a stable, user-visible order (a persisted index) so the
 * picker and folder tree do not reshuffle between launches. Exactly one account
 * may be flagged default; {@link #save(MailAccount)} enforces that invariant.</p>
 */
public class MailAccountStore {

    /** Absolute user-preferences path holding one child node per account. */
    public static final String ROOT = "/mail/accounts";
    private static final String K_SEALED = "passwordSealed";
    private static final String K_ORDER = "order";

    private static final Logger logger =
            Logger.getLogger(MailAccountStore.class.getName());

    private final Preferences root;
    private final CredentialVault vault;

    public MailAccountStore() {
        this(new CredentialVault());
    }

    /** Test seam: lets a caller inject a vault (the key lives in Preferences). */
    public MailAccountStore(CredentialVault vault) {
        this.root = Preferences.userRoot().node(ROOT);
        this.vault = vault;
    }

    /** Loads every persisted account, in the saved order. */
    public List<MailAccount> load() {
        List<MailAccount> list = new ArrayList<MailAccount>();
        try {
            String[] ids = root.childrenNames();
            Arrays.sort(ids);
            List<String> order = readOrder();
            List<MailAccount> unordered = new ArrayList<MailAccount>();
            for (String id : ids) {
                unordered.add(MailAccount.readFrom(id, root.node(id)));
            }
            // Emit in the persisted order first, then any newcomer by id.
            for (String id : order) {
                for (int i = 0; i < unordered.size(); i++) {
                    if (unordered.get(i).getId().equals(id)) {
                        list.add(unordered.remove(i));
                        break;
                    }
                }
            }
            list.addAll(unordered);
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error loading accounts from " + ROOT, e);
        }
        return list;
    }

    private List<String> readOrder() {
        List<String> order = new ArrayList<String>();
        String packed = root.get(K_ORDER, "");
        if (!packed.isEmpty()) {
            for (String id : packed.split("\n")) {
                if (!id.isEmpty()) {
                    order.add(id);
                }
            }
        }
        return order;
    }

    private void writeOrder(List<MailAccount> accounts) {
        StringBuilder sb = new StringBuilder();
        for (MailAccount a : accounts) {
            sb.append(a.getId()).append('\n');
        }
        root.put(K_ORDER, sb.toString());
    }

    /**
     * Writes (or rewrites) one account and flushes. If this account is flagged
     * default, every other account's default flag is cleared so exactly one wins;
     * if it is the first account, it becomes the default automatically.
     */
    public void save(MailAccount account) {
        List<MailAccount> all = load();
        boolean exists = false;
        for (MailAccount a : all) {
            if (a.getId().equals(account.getId())) {
                exists = true;
                break;
            }
        }
        if (!exists && all.isEmpty()) {
            account.setDefaultAccount(true);
        }
        if (account.isDefaultAccount()) {
            for (MailAccount a : all) {
                if (!a.getId().equals(account.getId()) && a.isDefaultAccount()) {
                    a.setDefaultAccount(false);
                    a.writeTo(root.node(a.getId()));
                }
            }
        }
        account.writeTo(root.node(account.getId()));
        if (!exists) {
            all.add(account);
        }
        writeOrder(all);
        flush();
    }

    /** Removes an account node and its sealed password. */
    public void delete(String id) {
        try {
            if (root.nodeExists(id)) {
                root.node(id).removeNode();
            }
            List<MailAccount> remaining = load();
            writeOrder(remaining);
            // Promote a new default if we removed it.
            if (!remaining.isEmpty() && defaultAccount() == null) {
                remaining.get(0).setDefaultAccount(true);
                remaining.get(0).writeTo(root.node(remaining.get(0).getId()));
            }
            flush();
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error deleting account " + id, e);
        }
    }

    /** The account flagged default, or {@code null} when none is. */
    public MailAccount defaultAccount() {
        for (MailAccount a : load()) {
            if (a.isDefaultAccount()) {
                return a;
            }
        }
        return null;
    }

    /** Finds an account by id, or {@code null}. */
    public MailAccount findById(String id) {
        for (MailAccount a : load()) {
            if (a.getId().equals(id)) {
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Sealed passwords (SAVED-mode accounts only)
    // ------------------------------------------------------------------

    /** Seals and persists a password for a SAVED-mode account. */
    public void setPassword(String accountId, String password) {
        String sealed = vault.seal(password);
        Preferences node = root.node(accountId);
        if (sealed == null) {
            node.remove(K_SEALED);
        } else {
            node.put(K_SEALED, sealed);
        }
        flush();
    }

    /** Recovers a previously sealed password, or {@code null} when none/failed. */
    public String getPassword(String accountId) {
        String sealed = root.node(accountId).get(K_SEALED, null);
        return (sealed == null) ? null : vault.open(sealed);
    }

    /** True when a sealed password exists for the account. */
    public boolean hasSavedPassword(String accountId) {
        return root.node(accountId).get(K_SEALED, null) != null;
    }

    private void flush() {
        try {
            root.flush();
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error flushing accounts", e);
        }
    }
}
