package org.lpmconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link LpmDatabase}, the read-only parser for LPM's state files. Each
 * test points the reader at a {@code @TempDir} through the {@code lpm.dbdir}
 * system property (the same override the class documents for testing), writes
 * fixture files in the contract's formats and asserts the parsed result. The
 * property is always cleared afterwards so the default-directory test and other
 * suites are unaffected.
 */
class LpmDatabaseTest {

    private static final String DB_DIR_PROPERTY = "lpm.dbdir";

    @AfterEach
    void clearOverride() {
        System.clearProperty(DB_DIR_PROPERTY);
    }

    private static LpmDatabase dbAt(Path dir) {
        System.setProperty(DB_DIR_PROPERTY, dir.toString());
        return new LpmDatabase();
    }

    private static void write(Path dir, String name, String content)
            throws IOException {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the default constructor resolves /var/lib/lpm")
    void defaultDirectory() {
        System.clearProperty(DB_DIR_PROPERTY);
        assertEquals(Paths.get("/var/lib/lpm"), new LpmDatabase().getDirectory());
    }

    @Test
    @DisplayName("a missing directory is not available and reads empty")
    void unavailableWhenMissing(@TempDir Path parent) {
        Path missing = parent.resolve("nope");
        LpmDatabase db = dbAt(missing);
        assertEquals(missing, db.getDirectory());
        assertFalse(db.isAvailable());
        assertTrue(db.readPackages().isEmpty());
        assertTrue(db.readInstalled().isEmpty());
        assertTrue(db.readHeld().isEmpty());
        assertTrue(db.readHistory(10).isEmpty());
    }

    @Test
    @DisplayName("a directory with an index file is available")
    void availableWithIndex(@TempDir Path dir) throws IOException {
        write(dir, "packages.list", "a|1|A||\n");
        assertTrue(dbAt(dir).isAvailable());
    }

    @Test
    @DisplayName("readPackages parses fields and flags installed / held")
    void parsesPackages(@TempDir Path dir) throws IOException {
        write(dir, "packages.list", String.join("\n",
                "# a comment line",
                "",
                "firefox|1.2.3|A browser|gtk,dbus|abc123",
                "vim|9.1|Editor|ncurses|deadbeef",
                "leafpad|0.8|Leaf editor||") + "\n");
        write(dir, "installed.list", "firefox 1.2.3\n");
        write(dir, "holds.list", "vim\n");

        List<LpmPackage> packages = dbAt(dir).readPackages();
        assertEquals(3, packages.size());

        LpmPackage firefox = packages.get(0);
        assertEquals("firefox", firefox.getName());
        assertEquals("1.2.3", firefox.getVersion());
        assertEquals("A browser", firefox.getDescription());
        assertEquals("gtk,dbus", firefox.getDeps());
        assertEquals("abc123", firefox.getChecksum());
        assertTrue(firefox.isInstalled());
        assertFalse(firefox.isHeld());

        LpmPackage vim = packages.get(1);
        assertFalse(vim.isInstalled());
        assertTrue(vim.isHeld());
    }

    @Test
    @DisplayName("an installed package missing from packages.list is appended")
    void appendsUnlistedInstalled(@TempDir Path dir) throws IOException {
        write(dir, "packages.list", "firefox|1.0|Browser||\n");
        write(dir, "installed.list", "firefox 1.0\norphan 2.0\n");

        List<LpmPackage> packages = dbAt(dir).readPackages();
        assertEquals(2, packages.size());
        LpmPackage orphan = packages.get(1);
        assertEquals("orphan", orphan.getName());
        assertEquals("", orphan.getVersion());
        assertTrue(orphan.isInstalled());
    }

    @Test
    @DisplayName("readInstalled and readHeld filter the package list")
    void filtersInstalledAndHeld(@TempDir Path dir) throws IOException {
        write(dir, "packages.list", String.join("\n",
                "a|1|A||",
                "b|1|B||",
                "c|1|C||") + "\n");
        write(dir, "installed.list", "a 1\nb 1\n");
        write(dir, "holds.list", "b\n");

        LpmDatabase db = dbAt(dir);
        assertEquals(List.of("a", "b"),
                db.readInstalled().stream().map(LpmPackage::getName).toList());
        assertEquals(List.of("b"),
                db.readHeld().stream().map(LpmPackage::getName).toList());
    }

    @Test
    @DisplayName("readHistory returns newest-first, honouring the max and blanks")
    void historyIsNewestFirst(@TempDir Path dir) throws IOException {
        write(dir, "history.log", String.join("\n",
                "1000|install|a|1",
                "",
                "1001|remove|b|2",
                "1002|upgrade|c|3") + "\n");

        LpmDatabase db = dbAt(dir);
        List<String[]> all = db.readHistory(10);
        assertEquals(3, all.size());
        assertEquals("1002", all.get(0)[0]);
        assertEquals("upgrade", all.get(0)[1]);
        assertEquals("c", all.get(0)[2]);
        assertEquals("3", all.get(0)[3]);
        assertEquals("1000", all.get(2)[0]);

        assertEquals(2, db.readHistory(2).size());
    }

    @Test
    @DisplayName("a malformed line without a name is skipped")
    void skipsNamelessLine(@TempDir Path dir) throws IOException {
        write(dir, "packages.list", "|1|noname||\ngood|2|G||\n");
        List<LpmPackage> packages = dbAt(dir).readPackages();
        assertEquals(1, packages.size());
        assertEquals("good", packages.get(0).getName());
    }
}
