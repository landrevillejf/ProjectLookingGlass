package org.jdesktop.wonderland.common.checksums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the value equality the 2006 {@code Checksum} always intended: the
 * original declared a covariant {@code equals(Checksum)} that never overrode
 * {@code Object.equals}, so collections compared checksums by identity. The
 * proper {@code equals(Object)} + {@code hashCode()} pair compares the encoded
 * checksum string, null-safely.
 */
class ChecksumTest {

    @Test
    void equalChecksumStringsAreEqual() {
        assertThat(new Checksum("deadbeef")).isEqualTo(new Checksum("deadbeef"));
        assertThat(new Checksum("deadbeef")).hasSameHashCodeAs(new Checksum("deadbeef"));
    }

    @Test
    void differentChecksumStringsAreNotEqual() {
        assertThat(new Checksum("deadbeef")).isNotEqualTo(new Checksum("cafebabe"));
    }

    @Test
    void sameInstanceIsEqual() {
        Checksum c = new Checksum("deadbeef");
        assertThat(c).isEqualTo(c);
    }

    @Test
    void nullAndForeignTypesAreNotEqual() {
        Checksum c = new Checksum("deadbeef");
        assertThat(c).isNotEqualTo(null);
        assertThat(c).isNotEqualTo("deadbeef");
    }

    @Test
    void twoDefaultChecksumsAreEqualAndShareAHashCode() {
        // the no-arg constructor leaves the encoded string null; both sides null
        // must compare equal rather than throw, as the old covariant form did.
        Checksum a = new Checksum();
        Checksum b = new Checksum();
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void nullChecksumIsNotEqualToAPopulatedOne() {
        assertThat(new Checksum()).isNotEqualTo(new Checksum("deadbeef"));
        assertThat(new Checksum("deadbeef")).isNotEqualTo(new Checksum());
    }

    @Test
    void settersAndAccessorsRoundTrip() {
        Checksum c = new Checksum();
        c.setChecksum("feedface");
        c.setPathName("models/foo.collada.xml");
        c.setLastModified(1234567890L);
        assertThat(c.getChecksum()).isEqualTo("feedface");
        assertThat(c.getPathName()).isEqualTo("models/foo.collada.xml");
        assertThat(c.getLastModified()).isEqualTo(1234567890L);
    }
}
