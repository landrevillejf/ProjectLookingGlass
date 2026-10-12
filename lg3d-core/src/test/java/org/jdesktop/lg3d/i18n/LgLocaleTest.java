package org.jdesktop.lg3d.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure precedence rule of {@link LgLocale}: sysprop over stored setting
 * over JVM default, unknown values pinned to English, never a guess.
 */
class LgLocaleTest {

    @Test
    @DisplayName("sysprop beats the stored setting and the host locale")
    void sysPropWins() {
        assertEquals(Locale.forLanguageTag("fr"),
                LgLocale.resolve("fr", "en", Locale.ENGLISH));
        assertEquals(Locale.ENGLISH,
                LgLocale.resolve("en", "fr", Locale.FRANCE));
    }

    @Test
    @DisplayName("a sysprop of 'system' defers to the stored setting")
    void systemDefersToStored() {
        assertEquals(Locale.forLanguageTag("fr"),
                LgLocale.resolve("system", "fr", Locale.ENGLISH));
        assertEquals(Locale.forLanguageTag("fr"),
                LgLocale.resolve(null, "fr", Locale.ENGLISH));
    }

    @Test
    @DisplayName("both 'system' falls through to the host locale")
    void hostLocaleIsTheThirdSource() {
        assertEquals(Locale.FRANCE,
                LgLocale.resolve("system", "system", Locale.FRANCE));
        assertEquals(Locale.JAPAN,
                LgLocale.resolve(null, null, Locale.JAPAN));
    }

    @Test
    @DisplayName("an unknown pin is English - never the host, never a guess")
    void unknownValueFallsBackToEnglish() {
        // "de" has no bundle here; silently running in French or crashing
        // would both be worse than visible-but-English labels.
        assertEquals(Locale.ENGLISH, LgLocale.resolve("de", "fr", Locale.FRANCE));
        assertEquals(Locale.ENGLISH, LgLocale.resolve("", "zz", Locale.FRANCE));
    }

    @Test
    @DisplayName("values are trimmed and case-insensitive")
    void valuesAreNormalized() {
        assertEquals(Locale.forLanguageTag("fr"), LgLocale.resolve("  FR ", null, Locale.ENGLISH));
        assertEquals(Locale.ENGLISH, LgLocale.resolve("En", null, Locale.FRANCE));
    }

    @Test
    @DisplayName("a null host locale cannot blank the decision")
    void nullDefaultIsSafe() {
        assertEquals(Locale.ENGLISH, LgLocale.resolve(null, null, null));
    }

    @Test
    @DisplayName("normalize() maps every junk value to 'system'")
    void normalizeMapsJunkToSystem() {
        assertEquals(LgLocale.SYSTEM, LgLocale.normalize(null));
        assertEquals(LgLocale.SYSTEM, LgLocale.normalize("   "));
        assertEquals(LgLocale.FR, LgLocale.normalize("FR"));
    }

    @Test
    @DisplayName("the resolved language is never blank for a pinned value")
    void pinnedLanguagesCarryTheirCode() {
        // Deliberately only the pure resolve(): the live get() would read the
        // developer's real preferences node, which tests must not depend on.
        assertEquals("en", LgLocale.resolve("en", null, Locale.FRANCE).getLanguage());
        assertEquals("fr", LgLocale.resolve(null, "fr", Locale.ENGLISH).getLanguage());
        assertEquals("", Locale.ROOT.getLanguage());
    }
}
