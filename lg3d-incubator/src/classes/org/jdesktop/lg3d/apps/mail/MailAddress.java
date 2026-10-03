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

/**
 * A single RFC-822 mailbox: an optional display name plus an e-mail address.
 *
 * <p>A plain, immutable value object shared by both the 2D {@link MailPanel} and
 * the native-3D {@link Mail3D} through {@link MailMessage}. It parses and formats
 * the {@code "Display Name <user@host>"} form so the recipient fields of a
 * message (from / to / cc / bcc) can round-trip between the wire and the UI
 * without every caller re-implementing the same splitting logic.</p>
 */
public final class MailAddress {

    private final String name;
    private final String email;

    public MailAddress(String name, String email) {
        this.name = (name == null) ? "" : name.trim();
        this.email = (email == null) ? "" : email.trim();
    }

    /** Convenience for an address with no display name. */
    public static MailAddress of(String email) {
        return new MailAddress("", email);
    }

    /**
     * Parses one {@code "Name <email>"} (or bare {@code email}) token. Returns
     * {@code null} for a blank input so callers can filter empty recipient rows.
     */
    public static MailAddress parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        int lt = s.indexOf('<');
        int gt = s.indexOf('>');
        if (lt >= 0 && gt > lt) {
            String name = s.substring(0, lt).trim();
            String email = s.substring(lt + 1, gt).trim();
            // Strip surrounding quotes the wire form sometimes carries.
            if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1).trim();
            }
            return new MailAddress(name, email);
        }
        return new MailAddress("", s);
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    /** True when there is a usable address (a name alone is not enough). */
    public boolean hasEmail() {
        return !email.isEmpty();
    }

    /**
     * Formats back to {@code "Name <email>"} when a name is present, otherwise
     * the bare address. This is the form the compose fields display and the form
     * {@link #parse(String)} reads back.
     */
    public String format() {
        if (name.isEmpty()) {
            return email;
        }
        if (email.isEmpty()) {
            return name;
        }
        return name + " <" + email + ">";
    }

    /** The display label: the name when present, otherwise the address. */
    public String display() {
        return name.isEmpty() ? email : name;
    }

    @Override
    public String toString() {
        return format();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MailAddress)) {
            return false;
        }
        MailAddress a = (MailAddress) o;
        return name.equals(a.name) && email.equalsIgnoreCase(a.email);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + email.toLowerCase().hashCode();
    }
}
