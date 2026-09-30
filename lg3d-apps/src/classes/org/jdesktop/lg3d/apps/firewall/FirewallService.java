package org.jdesktop.lg3d.apps.firewall;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for interacting with Linux firewall systems (firewalld and iptables).
 *
 * <p>This service provides methods to query firewall status, list rules, and
 * enable/disable the firewall. It attempts to use firewalld first (via
 * firewall-cmd), falling back to iptables if firewalld is not available.</p>
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 * Licensed under GNU GPLv2.
 */
public class FirewallService {

    private static final boolean USE_FIREWALLD = isFirewalldAvailable();

    /**
     * Checks if firewalld is available on the system.
     */
    private static boolean isFirewalldAvailable() {
        try {
            Process proc = new ProcessBuilder("firewall-cmd", "--state").start();
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets the current firewall status and rules.
     */
    public static FirewallStatus getStatus() {
        boolean enabled = false;
        List<FirewallRule> rules = new ArrayList<>();

        try {
            if (USE_FIREWALLD) {
                rules = parseFirewalldListAll();
                enabled = !rules.isEmpty();
            } else {
                enabled = isIptablesActive();
                if (enabled) {
                    rules = parseIptablesRules();
                }
            }
        } catch (Exception e) {
            enabled = false;
        }

        return new FirewallStatus(enabled, rules);
    }

    /**
     * Enables the firewall system.
     * @return true if the command executed successfully.
     */
    public static boolean enableFirewall() {
        try {
            Process proc;
            if (USE_FIREWALLD) {
                // Active firewalld via systemctl
                proc = new ProcessBuilder("sudo", "systemctl", "start", "firewalld").start();
            } else {
                // Remet la politique par défaut d'iptables sur ACCEPT ou applique un script de règles
                proc = new ProcessBuilder("sudo", "iptables", "-P", "INPUT", "ACCEPT").start();
            }
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Disables the firewall system.
     * @return true if the command executed successfully.
     */
    public static boolean disableFirewall() {
        try {
            Process proc;
            if (USE_FIREWALLD) {
                // Arrête firewalld via systemctl
                proc = new ProcessBuilder("sudo", "systemctl", "stop", "firewalld").start();
            } else {
                // Vide toutes les règles iptables (Flush)
                proc = new ProcessBuilder("sudo", "iptables", "-F").start();
            }
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Executes 'firewall-cmd --list-all' once and parses structural zones lines
     * directly into FirewallRule data objects.
     */
    private static List<FirewallRule> parseFirewalldListAll() {
        List<FirewallRule> rules = new ArrayList<>();
        try {
            Process proc = new ProcessBuilder("firewall-cmd", "--list-all").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();

                    // Parse ports line (e.g., "ports: 80/tcp 443/tcp")
                    if (line.startsWith("ports:") && line.length() > 6) {
                        String portsRaw = line.replace("ports:", "").trim();
                        for (String token : portsRaw.split("\\s+")) {
                            if (token.contains("/")) {
                                String[] parts = token.split("/");
                                rules.add(new FirewallRule(parts[1], "any", "any", parts[0], "ACCEPT", "IN_public_allow"));
                            }
                        }
                    }

                    // Parse services line (e.g., "services: ssh dhcpv6-client")
                    if (line.startsWith("services:") && line.length() > 9) {
                        String servicesRaw = line.replace("services:", "").trim();
                        for (String service : servicesRaw.split("\\s+")) {
                            rules.add(new FirewallRule("any", "any", "any", service, "ACCEPT", "IN_public_allow"));
                        }
                    }
                }
            }
            proc.waitFor();
            if (proc.exitValue() != 0) {
                return new ArrayList<>();
            }
        } catch (Exception e) {
            // Safe fallback container
        }
        return rules;
    }

    /**
     * Fallback check for basic iptables status.
     */
    private static boolean isIptablesActive() {
        try {
            Process proc = new ProcessBuilder("iptables", "-L", "-n").start();
            proc.waitFor();
            return proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parses 'iptables -S' tokens straight into matching FirewallRule parameters.
     */
    private static List<FirewallRule> parseIptablesRules() {
        List<FirewallRule> rules = new ArrayList<>();
        try {
            Process proc = new ProcessBuilder("iptables", "-S").start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("-A")) {
                        rules.add(convertIptablesLineToRule(line));
                    }
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            // Safe fallback container
        }
        return rules;
    }

    /**
     * Helper to tokenize a standard iptables rule line.
     */
    private static FirewallRule convertIptablesLineToRule(String line) {
        String[] tokens = line.split("\\s+");
        String target = "UNKNOWN";
        String protocol = "any";
        String source = "any";
        String destination = "any";
        String port = "any";
        String action = "ACCEPT";

        if (tokens.length > 1) {
            target = tokens[1]; // Chain name follows -A
        }

        for (int i = 0; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "-p":
                    if (i + 1 < tokens.length) protocol = tokens[i + 1];
                    break;
                case "-s":
                    if (i + 1 < tokens.length) source = tokens[i + 1];
                    break;
                case "-d":
                    if (i + 1 < tokens.length) destination = tokens[i + 1];
                    break;
                case "--dport":
                    if (i + 1 < tokens.length) port = tokens[i + 1];
                    break;
                case "-j":
                    if (i + 1 < tokens.length) action = tokens[i + 1];
                    break;
            }
        }
        return new FirewallRule(protocol, source, destination, port, action, target);
    }
}
