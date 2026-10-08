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
package org.jdesktop.lg3d.apps.securitycenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jdesktop.lg3d.utils.system.TorPrivateMode;

/**
 * The pure, weighted security checklist behind the Overview grade. Given a
 * {@link SecuritySnapshot} it scores six defense-in-depth items - SELinux
 * enforcing, the firewall running, an antivirus scanner installed, its
 * definitions known, private (Tor) mode on, and a VPN tunnel up with its kill
 * switch armed - into a 0-100 total and an A-F letter {@link Grade}, plus one
 * {@link Item} per check so the UI can show exactly what passed and what did not.
 *
 * <p>Nothing here reads the host, spawns a process or touches Swing: the panel
 * probes into a snapshot and hands it to {@link #compute}, so the whole scoring
 * contract is unit-testable headless. The two anonymity items (tor, VPN) are
 * deliberately weighted so a host that is fully patched but simply does not run
 * a tunnel still grades well (a B), while a host with everything - including an
 * active, kill-switch-armed tunnel - reaches an A.</p>
 */
public final class SecurityScore {

    /** The letter grade bands over the 0-100 weighted percentage. */
    public enum Grade {
        /** 90-100%: hardened, defense-in-depth active. */
        A,
        /** 75-89%: well protected, optional layers missing. */
        B,
        /** 60-74%: protected but with notable gaps. */
        C,
        /** 40-59%: weak; several core controls missing. */
        D,
        /** Below 40%: exposed. */
        F
    }

    /** Weight of the "SELinux enforcing" check. */
    static final int W_SELINUX = 25;
    /** Weight of the "firewall running" check. */
    static final int W_FIREWALL = 25;
    /** Weight of the "antivirus scanner installed" check. */
    static final int W_SCANNER = 20;
    /** Weight of the "virus definitions known" check. */
    static final int W_DEFINITIONS = 10;
    /** Weight of the "private (Tor) mode on" check. */
    static final int W_TOR = 10;
    /** Weight of the "VPN tunnel up with kill switch armed" check. */
    static final int W_VPN = 10;

    private SecurityScore() {
        // no instances
    }

    /**
     * One weighted checklist line: what was checked, whether it passed, the
     * points it is worth and a short human detail for the findings list.
     *
     * @param check   the checklist item name (never null)
     * @param passed  whether the host satisfies it
     * @param weight  the points this item contributes when passed
     * @param detail  a one-line human explanation, never null
     */
    public record Item(String check, boolean passed, int weight, String detail) {

        /** Normalises nulls and clamps a negative weight so an item is safe to render. */
        public Item {
            check = (check == null) ? "" : check.trim();
            detail = (detail == null) ? "" : detail.trim();
            weight = Math.max(0, weight);
        }

        /** The points this item earned: its weight when passed, else zero. */
        public int earned() {
            return passed ? weight : 0;
        }
    }

    /**
     * The computed score: the earned points, the maximum, the letter grade and
     * the per-item findings, in checklist order.
     *
     * @param score the points earned (clamped to {@code [0, max]})
     * @param max   the total points available
     * @param grade the letter grade for {@code score}/{@code max}
     * @param items the checklist items, never null
     */
    public record Result(int score, int max, Grade grade, List<Item> items) {

        /** Clamps the score into range and copies the items so a result is immutable. */
        public Result {
            items = (items == null) ? List.of() : List.copyOf(items);
            max = Math.max(0, max);
            score = Math.max(0, Math.min(score, max));
            grade = (grade == null) ? Grade.F : grade;
        }

        /** The score as a whole percentage (0-100); zero when nothing was scored. */
        public int percent() {
            return (max <= 0) ? 0 : (int) Math.round(100.0 * score / max);
        }

        /** A short header for the overview, e.g. {@code "Grade B (80/100)"}. */
        public String header() {
            return "Grade " + grade + " (" + score + "/" + max + ")";
        }

        /** The items that did not pass, in checklist order; never null. */
        public List<Item> gaps() {
            List<Item> out = new ArrayList<>();
            for (Item item : items) {
                if (!item.passed()) {
                    out.add(item);
                }
            }
            return out;
        }
    }

    /**
     * Scores a posture snapshot. A null snapshot is treated as the all-unknown
     * {@link SecuritySnapshot#unknown()}, which scores zero (an unprobed host is
     * never assumed safe).
     *
     * @param snapshot the posture to score
     * @return the weighted result, never null
     */
    public static Result compute(SecuritySnapshot snapshot) {
        SecuritySnapshot snap = (snapshot == null) ? SecuritySnapshot.unknown() : snapshot;
        List<Item> items = new ArrayList<>();
        items.add(selinuxItem(snap));
        items.add(firewallItem(snap));
        items.add(scannerItem(snap));
        items.add(definitionsItem(snap));
        items.add(torItem(snap));
        items.add(vpnItem(snap));
        int max = 0;
        int score = 0;
        for (Item item : items) {
            max += item.weight();
            score += item.earned();
        }
        return new Result(score, max, gradeFor(score, max), items);
    }

    /**
     * Maps an earned/available point total onto a letter grade.
     *
     * @param score the points earned
     * @param max   the points available
     * @return the grade band for the resulting percentage
     */
    public static Grade gradeFor(int score, int max) {
        int percent = (max <= 0) ? 0 : (int) Math.round(100.0 * Math.max(0, score) / max);
        if (percent >= 90) {
            return Grade.A;
        }
        if (percent >= 75) {
            return Grade.B;
        }
        if (percent >= 60) {
            return Grade.C;
        }
        if (percent >= 40) {
            return Grade.D;
        }
        return Grade.F;
    }

    private static Item selinuxItem(SecuritySnapshot s) {
        boolean ok = s.selinux() == SecurityProbe.SelinuxMode.ENFORCING;
        return new Item("SELinux enforcing", ok, W_SELINUX,
                "SELinux: " + lower(SecurityProbe.describeSelinux(s.selinux())) + ".");
    }

    private static Item firewallItem(SecuritySnapshot s) {
        boolean ok = s.firewall() == SecurityProbe.FirewallState.RUNNING;
        return new Item("Firewall running", ok, W_FIREWALL,
                "Firewall: " + lower(SecurityProbe.describeFirewall(s.firewall())) + ".");
    }

    private static Item scannerItem(SecuritySnapshot s) {
        boolean ok = s.scannerAvailable();
        return new Item("Antivirus installed", ok, W_SCANNER,
                ok ? "Antivirus: " + s.scanner() + " is installed."
                   : "Antivirus: no scanner found.");
    }

    private static Item definitionsItem(SecuritySnapshot s) {
        boolean ok = s.scannerAvailable() && s.version().isPresent();
        return new Item("Virus definitions known", ok, W_DEFINITIONS,
                ok ? "Definitions: " + s.version() + "."
                   : "Definitions: unknown - update ClamAV (freshclam).");
    }

    private static Item torItem(SecuritySnapshot s) {
        boolean ok = s.tor() == TorPrivateMode.State.ON;
        return new Item("Private (Tor) mode on", ok, W_TOR,
                "Private (Tor) mode: " + lower(TorPrivateMode.describe(s.tor())) + ".");
    }

    private static Item vpnItem(SecuritySnapshot s) {
        boolean ok = s.vpnConnected() && s.vpnKillSwitch();
        String detail;
        if (!s.vpnConnected()) {
            detail = "VPN: no tunnel is connected.";
        } else if (s.vpnKillSwitch()) {
            detail = "VPN: tunnel up with the kill switch armed.";
        } else {
            detail = "VPN: tunnel up but the kill switch is off.";
        }
        return new Item("VPN tunnel with kill switch", ok, W_VPN, detail);
    }

    private static String lower(String text) {
        return (text == null) ? "" : text.toLowerCase(Locale.ROOT);
    }
}
