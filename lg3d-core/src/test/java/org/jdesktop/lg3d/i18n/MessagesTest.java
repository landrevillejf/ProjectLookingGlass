package org.jdesktop.lg3d.i18n;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bundle contract: both languages parse and carry the same keys, lookups
 * hit in the run's language, and a miss degrades to the key or the caller's
 * fallback - never to an exception on the painting path.
 */
class MessagesTest {

    /** Same base the class under test uses; duplicated on purpose, the
     *  bundle location is part of the shipped-jar contract. */
    private static final String BASE = "org.jdesktop.lg3d.i18n.messages";

    private static final ResourceBundle.Control NO_FALLBACK =
            new ResourceBundle.Control() {
                @Override
                public Locale getFallbackLocale(String baseName, Locale locale) {
                    return null;
                }
            };

    @AfterEach
    void restoreTheRun() {
        System.clearProperty(LgLocale.SYSPROP);
        LgLocale.resetForTests();
    }

    @Test
    @DisplayName("both bundles load and every base key has a French twin")
    void bundlesAreAtParity() {
        ResourceBundle en = ResourceBundle.getBundle(BASE, Locale.ROOT, NO_FALLBACK);
        ResourceBundle fr = ResourceBundle.getBundle(BASE, Locale.FRENCH, NO_FALLBACK);
        Set<String> enKeys = new HashSet<>(en.keySet());
        Set<String> frKeys = new HashSet<>(fr.keySet());
        assertTrue(enKeys.size() > 0, "the base bundle must not be empty");
        assertEquals(enKeys, frKeys, "messages_fr must carry exactly the base keys");
        for (String key : enKeys) {
            assertFalse(en.getString(key).isBlank(), key + " has no English text");
            assertFalse(fr.getString(key).isBlank(), key + " has no French text");
            // Patterns that carry placeholders must parse, in both bundles:
            // an unpaired apostrophe in French silently swallows text.
            if (en.getString(key).indexOf('{') >= 0) {
                assertDoesNotThrow(() -> new MessageFormat(en.getString(key)),
                        key + " is not a valid English pattern");
            }
            if (fr.getString(key).indexOf('{') >= 0) {
                assertDoesNotThrow(() -> new MessageFormat(fr.getString(key)),
                        key + " is not a valid French pattern");
            }
        }
    }

    @Test
    @DisplayName("the run's language decides which text tr() returns")
    void trFollowsThePinnedLanguage() {
        System.setProperty(LgLocale.SYSPROP, LgLocale.EN);
        LgLocale.resetForTests();
        assertEquals("Language", Messages.tr("lg2d.cc.language"));

        System.setProperty(LgLocale.SYSPROP, LgLocale.FR);
        LgLocale.resetForTests();
        assertEquals("Langue", Messages.tr("lg2d.cc.language"));
    }

    @Test
    @DisplayName("an unknown pin falls to the base bundle, not to the host locale")
    void controlOverridesTheJvmDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            // A French host asking for an unsupported pin must still read
            // English labels - the Control kills the default-locale fallback.
            Locale.setDefault(Locale.FRANCE);
            System.setProperty(LgLocale.SYSPROP, "zz");
            LgLocale.resetForTests();
            assertEquals("Language", Messages.tr("lg2d.cc.language"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    @DisplayName("a missing key shows the key, and trOrDefault shows the fallback")
    void missesAreSafe() {
        System.setProperty(LgLocale.SYSPROP, LgLocale.EN);
        LgLocale.resetForTests();
        assertEquals("lg2d.nope.missing", Messages.tr("lg2d.nope.missing"));
        assertEquals("XML literal",
                Messages.trOrDefault("lg2d.nope.missing", "XML literal"));
        assertEquals("Language",
                Messages.trOrDefault("lg2d.cc.language", "XML literal"));
        // A null key is a programming error, not an exception: it echoes back.
        assertNull(Messages.tr(null));
    }

    @Test
    @DisplayName("hasKey answers for the run's chain only")
    void hasKeyTracksTheBundles() {
        System.setProperty(LgLocale.SYSPROP, LgLocale.FR);
        LgLocale.resetForTests();
        assertTrue(Messages.hasKey("lg2d.cc.language"));
        assertFalse(Messages.hasKey("lg2d.cc.language.nonexistent"));
    }
}
