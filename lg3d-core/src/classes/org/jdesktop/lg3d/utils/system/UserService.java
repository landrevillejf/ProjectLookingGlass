/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.system;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads local users and groups and performs user administration.
 *
 * <p>Reading is done directly from {@code /etc/passwd} and {@code /etc/group}
 * (no privileges needed). All mutations (add/remove user, set password, change
 * groups) go through {@link PrivilegedRunner} ({@code pkexec}), which returns a
 * structured result so the control center can prompt for authorization, report
 * a dismissal, or switch to read-only when polkit is unavailable.</p>
 *
 * <p>No JNI/JNA; pure JDK plus the standard {@code useradd}/{@code usermod}/
 * {@code userdel}/{@code groupadd}/{@code gpasswd}/{@code chpasswd} tools.</p>
 */
public final class UserService {

    /** Shells that mark an account as non-login (system/service). */
    private static final List<String> NOLOGIN_SHELLS = List.of(
            "/usr/sbin/nologin", "/sbin/nologin", "/bin/false", "/usr/bin/false",
            "/bin/true", "/usr/bin/nologin");

    private UserService() {
        // no instances
    }

    /** A local user account. */
    public static final class User {
        private final String name;
        private final String fullName;
        private final long uid;
        private final long gid;
        private final String home;
        private final String shell;
        private final boolean system;
        private final String avatarPath;

        User(String name, String fullName, long uid, long gid, String home,
             String shell, boolean system, String avatarPath) {
            this.name = name;
            this.fullName = fullName;
            this.uid = uid;
            this.gid = gid;
            this.home = home;
            this.shell = shell;
            this.system = system;
            this.avatarPath = avatarPath;
        }

        public String getName() { return name; }
        public String getFullName() { return (fullName == null || fullName.isEmpty()) ? name : fullName; }
        public long getUid() { return uid; }
        public long getGid() { return gid; }
        public String getHome() { return home; }
        public String getShell() { return shell; }
        public boolean isSystem() { return system; }
        /** Absolute path to an avatar image, or null if none was found. */
        public String getAvatarPath() { return avatarPath; }

        @Override
        public String toString() { return name + " (" + uid + ")"; }
    }

    /** A local group. */
    public static final class Group {
        private final String name;
        private final long gid;
        private final List<String> members;

        Group(String name, long gid, List<String> members) {
            this.name = name;
            this.gid = gid;
            this.members = members;
        }

        public String getName() { return name; }
        public long getGid() { return gid; }
        public List<String> getMembers() { return members; }

        @Override
        public String toString() { return name + " (" + gid + ")"; }
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Lists user accounts.
     *
     * @param includeSystem if false, service/nologin accounts and uid &lt; 1000
     *                      (except root) are omitted
     */
    public static List<User> listUsers(boolean includeSystem) {
        List<User> users = new ArrayList<>();
        for (String line : Proc.readLines("/etc/passwd")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(":", -1);
            if (f.length < 7) {
                continue;
            }
            String name = f[0];
            long uid = Proc.parseLong(f[2], -1);
            long gid = Proc.parseLong(f[3], -1);
            String gecos = f[4];
            String home = f[5];
            String shell = f[6];
            boolean system = isSystemAccount(uid, shell);
            if (!includeSystem && system) {
                continue;
            }
            users.add(new User(name, parseFullName(gecos), uid, gid, home, shell,
                    system, findAvatar(name, home)));
        }
        return users;
    }

    /** Convenience: only human (non-system) accounts. */
    public static List<User> listHumanUsers() {
        return listUsers(false);
    }

    /** Lists all local groups. */
    public static List<Group> listGroups() {
        List<Group> groups = new ArrayList<>();
        for (String line : Proc.readLines("/etc/group")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(":", -1);
            if (f.length < 4) {
                continue;
            }
            List<String> members = new ArrayList<>();
            if (!f[3].isBlank()) {
                for (String m : f[3].split(",")) {
                    if (!m.isBlank()) {
                        members.add(m.trim());
                    }
                }
            }
            groups.add(new Group(f[0], Proc.parseLong(f[2], -1), members));
        }
        return groups;
    }

    /** The group names a user belongs to (primary + supplementary). */
    public static List<String> groupsFor(String username) {
        List<String> result = new ArrayList<>();
        for (Group g : listGroups()) {
            if (g.getMembers().contains(username)) {
                result.add(g.getName());
            }
        }
        return result;
    }

    /** Resolves a uid to a user name, or null if unknown. */
    public static String userName(long uid) {
        for (User u : listUsers(true)) {
            if (u.getUid() == uid) {
                return u.getName();
            }
        }
        return null;
    }

    /** True if an account with the given name already exists. */
    public static boolean userExists(String username) {
        for (User u : listUsers(true)) {
            if (u.getName().equals(username)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Mutations (all via pkexec)
    // ------------------------------------------------------------------

    /**
     * Creates a user account.
     *
     * @param username   the login name
     * @param fullName   the GECOS display name (may be null)
     * @param shell      the login shell (null = system default)
     * @param groups     supplementary groups (may be null/empty)
     * @param createHome whether to create and skeleton the home directory
     */
    public static PrivilegedRunner.PrivilegedResult addUser(
            String username, String fullName, String shell, List<String> groups, boolean createHome) {
        List<String> cmd = new ArrayList<>();
        cmd.add("useradd");
        if (createHome) {
            cmd.add("-m");
        }
        if (fullName != null && !fullName.isBlank()) {
            cmd.add("-c");
            cmd.add(fullName);
        }
        if (shell != null && !shell.isBlank()) {
            cmd.add("-s");
            cmd.add(shell);
        }
        if (groups != null && !groups.isEmpty()) {
            cmd.add("-G");
            cmd.add(String.join(",", groups));
        }
        cmd.add(username);
        return PrivilegedRunner.run(cmd);
    }

    /** Deletes a user account (optionally removing its home directory). */
    public static PrivilegedRunner.PrivilegedResult deleteUser(String username, boolean removeHome) {
        List<String> cmd = new ArrayList<>();
        cmd.add("userdel");
        if (removeHome) {
            cmd.add("-r");
        }
        cmd.add(username);
        return PrivilegedRunner.run(cmd);
    }

    /** Updates a user's full name, shell and/or home via {@code usermod}. */
    public static PrivilegedRunner.PrivilegedResult modifyUser(
            String username, String fullName, String shell, String home) {
        List<String> cmd = new ArrayList<>();
        cmd.add("usermod");
        boolean any = false;
        if (fullName != null && !fullName.isBlank()) {
            cmd.add("-c");
            cmd.add(fullName);
            any = true;
        }
        if (shell != null && !shell.isBlank()) {
            cmd.add("-s");
            cmd.add(shell);
            any = true;
        }
        if (home != null && !home.isBlank()) {
            cmd.add("-d");
            cmd.add(home);
            any = true;
        }
        if (!any) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "Nothing to change.", -1, "");
        }
        cmd.add(username);
        return PrivilegedRunner.run(cmd);
    }

    /**
     * Sets a user's password via {@code chpasswd} (fed on stdin so the password
     * never appears in the process argument list).
     */
    public static PrivilegedRunner.PrivilegedResult setPassword(String username, String password) {
        if (password == null) {
            password = "";
        }
        List<String> cmd = List.of("chpasswd");
        return PrivilegedRunner.run(cmd, username + ":" + password + "\n");
    }

    /** Replaces a user's supplementary groups ({@code usermod -G}). */
    public static PrivilegedRunner.PrivilegedResult setGroups(String username, List<String> groups) {
        return PrivilegedRunner.run("usermod", "-G",
                (groups == null) ? "" : String.join(",", groups), username);
    }

    /** Adds a user to a group ({@code gpasswd -a}). */
    public static PrivilegedRunner.PrivilegedResult addUserToGroup(String username, String group) {
        return PrivilegedRunner.run("gpasswd", "-a", username, group);
    }

    /** Removes a user from a group ({@code gpasswd -d}). */
    public static PrivilegedRunner.PrivilegedResult removeUserFromGroup(String username, String group) {
        return PrivilegedRunner.run("gpasswd", "-d", username, group);
    }

    /** Creates a group ({@code groupadd}). */
    public static PrivilegedRunner.PrivilegedResult addGroup(String group) {
        return PrivilegedRunner.run("groupadd", group);
    }

    /** Deletes a group ({@code groupdel}). */
    public static PrivilegedRunner.PrivilegedResult deleteGroup(String group) {
        return PrivilegedRunner.run("groupdel", group);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isSystemAccount(long uid, String shell) {
        if (shell != null && NOLOGIN_SHELLS.contains(shell)) {
            return true;
        }
        // Conventional human-user uid range starts at 1000 (500 on old distros);
        // root (0) and everything below 1000 is treated as system.
        return uid >= 0 && uid < 1000 || uid >= 65534;
    }

    private static String parseFullName(String gecos) {
        if (gecos == null || gecos.isEmpty()) {
            return "";
        }
        int comma = gecos.indexOf(',');
        return (comma >= 0) ? gecos.substring(0, comma) : gecos;
    }

    /** Best-effort avatar lookup: AccountsService icon, then ~/.face. */
    private static String findAvatar(String username, String home) {
        Path acc = Paths.get("/var/lib/AccountsService/icons", username);
        if (Files.isReadable(acc)) {
            return acc.toString();
        }
        if (home != null && !home.isBlank()) {
            Path face = Paths.get(home, ".face");
            if (Files.isReadable(face)) {
                return face.toString();
            }
        }
        return null;
    }
}
