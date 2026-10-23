/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.scenemanager.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Decides which executables a start-menu / taskbar launch command depends on.
 *
 * <p>A descriptor's {@code command} (or {@code exec}) is a whole command line,
 * not a bare program name: it may carry arguments, and it may be launched
 * through a privilege-escalation wrapper. Whether the item can actually run --
 * and therefore whether the desktop shows it or silently self-hides it -- is a
 * question about which of those tokens name a program that must be installed.
 * This class answers exactly that question and nothing else: it is pure string
 * logic with no process, no scene graph and no display dependency, so it is
 * unit-testable headless. {@link ApplicationDescription#isApplicationAvailable}
 * then probes each token this returns against the system.
 *
 * <p>The rule is deliberately narrow. Only the leading program is required, so a
 * command such as {@code firefox https://...} gates on {@code firefox} alone and
 * its URL argument is never mistaken for a missing executable. The one exception
 * is a privilege-escalation wrapper ({@code pkexec}, {@code sudo}, ...): there
 * the wrapper is not the point of the item, the elevated program is, so the
 * first following non-option token is required too. That is what lets
 * {@code pkexec calamares} hide on a host that has {@code pkexec} but no
 * {@code calamares}, instead of showing an item that dies when it is clicked.
 */
public final class LaunchCommand {

    /**
     * Privilege-escalation launchers. A command that begins with one of these
     * runs the <em>next</em> program with elevated privileges, so the item can
     * only launch when that program is installed too.
     */
    private static final Set<String> PRIVILEGE_WRAPPERS = Set.of(
            "pkexec", "sudo", "doas", "gksu", "gksudo", "kdesu", "kdesudo");

    /** A utility class: never instantiated. */
    private LaunchCommand() {
    }

    /**
     * True when the command runs in this JVM rather than forking an external
     * executable, so there is nothing on the system to look for.
     *
     * <p>{@code java ...} runs a class's main; {@code swingapp ...} does the
     * same but also opts the app into 3D window capture (see AppLaunchAction).
     * Both are always available. The {@code java} prefix test is intentionally
     * the historical {@code startsWith("java")}, which also treats {@code javaws}
     * as an in-JVM launch and preserves the behaviour every descriptor relies on.
     *
     * @param exec the command line from the descriptor
     * @return true if the command needs no external executable
     */
    public static boolean isInJvmLaunch(String exec) {
        return exec.startsWith("java") || exec.startsWith("swingapp ");
    }

    /**
     * The executables a command depends on, in the order they must be probed:
     * always the leading program, plus -- when that program is a
     * privilege-escalation wrapper -- the first following token that is not an
     * option (the real program the wrapper elevates).
     *
     * <p>A blank command, which names nothing runnable, yields an empty array;
     * so does an in-JVM launch, whose availability never depends on the system.
     *
     * @param exec the command line from the descriptor
     * @return the tokens that must each be present for the command to run
     */
    public static String[] requiredExecutables(String exec) {
        if (isInJvmLaunch(exec)) {
            return new String[0];
        }
        String trimmed = exec.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        String[] tokens = trimmed.split("\\s+");
        List<String> required = new ArrayList<>();
        required.add(tokens[0]);
        if (isPrivilegeWrapper(tokens[0])) {
            for (int i = 1; i < tokens.length; i++) {
                if (!tokens[i].startsWith("-")) {
                    required.add(tokens[i]);
                    break;
                }
            }
        }
        return required.toArray(new String[0]);
    }

    /**
     * True when the leading token is a privilege-escalation launcher, matched on
     * its base name so an absolute path such as {@code /usr/bin/pkexec} counts.
     *
     * @param program the leading token of a command
     * @return true if the token elevates the program that follows it
     */
    public static boolean isPrivilegeWrapper(String program) {
        int slash = program.lastIndexOf(File.separatorChar);
        String base = slash < 0 ? program : program.substring(slash + 1);
        return PRIVILEGE_WRAPPERS.contains(base);
    }
}
