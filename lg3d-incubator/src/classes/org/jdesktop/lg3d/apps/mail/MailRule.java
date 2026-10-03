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

import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A user-defined filter: when a fetched message's {@code from}, {@code subject}
 * or {@code to} matches a condition, an action (move / mark-read / flag / delete)
 * is applied.
 *
 * <p>Rules are evaluated by {@link MailSessionManager} right after a folder is
 * fetched, so triage happens the moment new mail lands. Matching is
 * case-insensitive for {@code CONTAINS}/{@code EQUALS}; {@code REGEX} uses a
 * compiled {@link Pattern} and a malformed expression simply never matches rather
 * than throwing into the fetch loop.</p>
 */
public final class MailRule {

    /** Which header the condition inspects. */
    public enum Field {
        FROM, SUBJECT, TO
    }

    /** How the field is compared against the value. */
    public enum Match {
        CONTAINS, EQUALS, REGEX
    }

    /** What happens to a message that matches. */
    public enum Action {
        MOVE, MARK_READ, FLAG, DELETE
    }

    private final String id;
    private boolean enabled = true;
    private Field field = Field.FROM;
    private Match match = Match.CONTAINS;
    private String value = "";
    private Action action = Action.MARK_READ;
    private String targetFolder = "";      // only for MOVE

    public MailRule(String id) {
        this.id = (id == null || id.isEmpty()) ? newId() : id;
    }

    public static String newId() {
        return "rule-" + Long.toString(System.nanoTime(), 36)
                + "-" + Integer.toString((int) (Math.random() * 0xFFFFFF), 36);
    }

    // ------------------------------------------------------------------
    // Preferences (de)serialisation
    // ------------------------------------------------------------------

    static MailRule readFrom(String id, Preferences node) {
        MailRule r = new MailRule(id);
        r.enabled = node.getBoolean("enabled", true);
        r.field = enumOr(Field.class, node.get("field", null), Field.FROM);
        r.match = enumOr(Match.class, node.get("match", null), Match.CONTAINS);
        r.value = node.get("value", "");
        r.action = enumOr(Action.class, node.get("action", null), Action.MARK_READ);
        r.targetFolder = node.get("targetFolder", "");
        return r;
    }

    void writeTo(Preferences node) {
        node.putBoolean("enabled", enabled);
        node.put("field", field.name());
        node.put("match", match.name());
        node.put("value", value);
        node.put("action", action.name());
        node.put("targetFolder", targetFolder);
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String value,
            E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /** True when the rule is on and {@code m}'s field satisfies the condition. */
    public boolean matches(MailMessage m) {
        if (!enabled || m == null || value == null || value.isEmpty()) {
            return false;
        }
        String haystack = subjectOf(m);
        if (haystack == null) {
            return false;
        }
        switch (match) {
            case EQUALS:
                return haystack.equalsIgnoreCase(value);
            case REGEX:
                return regexMatches(haystack);
            case CONTAINS:
            default:
                return haystack.toLowerCase().contains(value.toLowerCase());
        }
    }

    private String subjectOf(MailMessage m) {
        switch (field) {
            case SUBJECT:
                return m.getSubject();
            case TO:
                return MailMessage.join(m.allRecipients());
            case FROM:
            default:
                return m.getFrom().format();
        }
    }

    private boolean regexMatches(String haystack) {
        try {
            return Pattern.compile(value, Pattern.CASE_INSENSITIVE)
                    .matcher(haystack).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Field getField() {
        return field;
    }

    public void setField(Field field) {
        this.field = (field == null) ? Field.FROM : field;
    }

    public Match getMatch() {
        return match;
    }

    public void setMatch(Match match) {
        this.match = (match == null) ? Match.CONTAINS : match;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = (value == null) ? "" : value;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = (action == null) ? Action.MARK_READ : action;
    }

    public String getTargetFolder() {
        return targetFolder;
    }

    public void setTargetFolder(String targetFolder) {
        this.targetFolder = (targetFolder == null) ? "" : targetFolder;
    }

    /** A one-line human description for the rules list. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(enabled ? "" : "(off) ");
        sb.append(field).append(' ').append(match).append(" \"").append(value)
                .append("\" -> ").append(action);
        if (action == Action.MOVE && !targetFolder.isEmpty()) {
            sb.append(" ").append(targetFolder);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
