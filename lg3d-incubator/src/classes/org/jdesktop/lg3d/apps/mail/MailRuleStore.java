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
 * Persists the ordered list of {@link MailRule}s under the {@code /mail/rules}
 * {@link Preferences} node - one child node per rule.
 *
 * <p>Order matters: rules are applied top-to-bottom and a {@code MOVE}/{@code
 * DELETE} rule can change what later rules see, so the sequence the user arranges
 * in the settings dialog is the sequence {@link MailSessionManager} runs.</p>
 */
public class MailRuleStore {

    /** Absolute user-preferences path holding one child node per rule. */
    public static final String ROOT = "/mail/rules";
    private static final String K_ORDER = "order";

    private static final Logger logger =
            Logger.getLogger(MailRuleStore.class.getName());

    private final Preferences root;

    public MailRuleStore() {
        this.root = Preferences.userRoot().node(ROOT);
    }

    /** Loads every rule in the persisted order. */
    public List<MailRule> load() {
        List<MailRule> list = new ArrayList<MailRule>();
        try {
            String[] ids = root.childrenNames();
            Arrays.sort(ids);
            List<MailRule> unordered = new ArrayList<MailRule>();
            for (String id : ids) {
                unordered.add(MailRule.readFrom(id, root.node(id)));
            }
            for (String id : readOrder()) {
                for (int i = 0; i < unordered.size(); i++) {
                    if (unordered.get(i).getId().equals(id)) {
                        list.add(unordered.remove(i));
                        break;
                    }
                }
            }
            list.addAll(unordered);
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error loading rules from " + ROOT, e);
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

    /**
     * Replaces the whole ordered rule set: rewrites each rule node, drops the
     * nodes no longer present, and persists the new order. The settings dialog
     * edits the list as a unit, so a bulk save keeps ordering trivially correct.
     */
    public void saveAll(List<MailRule> rules) {
        try {
            List<MailRule> keep = (rules == null) ? new ArrayList<MailRule>() : rules;
            // Remove nodes that are no longer in the list.
            for (String id : root.childrenNames()) {
                boolean stillThere = false;
                for (MailRule r : keep) {
                    if (r.getId().equals(id)) {
                        stillThere = true;
                        break;
                    }
                }
                if (!stillThere && root.nodeExists(id)) {
                    root.node(id).removeNode();
                }
            }
            StringBuilder order = new StringBuilder();
            for (MailRule r : keep) {
                r.writeTo(root.node(r.getId()));
                order.append(r.getId()).append('\n');
            }
            root.put(K_ORDER, order.toString());
            root.flush();
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error saving rules", e);
        }
    }

    /** Removes every rule (used by tests and a "reset" action). */
    public void clear() {
        saveAll(new ArrayList<MailRule>());
    }
}
