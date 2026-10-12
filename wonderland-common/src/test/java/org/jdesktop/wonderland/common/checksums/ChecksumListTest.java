/*
 * End-to-end JAXB round-trip through the jakarta.xml.bind-api 2.3.3 +
 * glassfish jaxb-runtime 2.3.9 pair pulled in by wonderland-common/build.gradle.
 * The 2006-era ChecksumList was written against the JAXB that shipped inside
 * JDK 8; verifying the same marshal/unmarshal path on JDK 21 with the external
 * runtime is what proves M0's dependency wiring is right.
 */
package org.jdesktop.wonderland.common.checksums;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChecksumListTest {

    @Test
    void jaxbRoundTripPreservesChecksums() throws Exception {
        ChecksumList list = new ChecksumList();
        Map<String, Checksum> map = new HashMap<>();
        Checksum a = new Checksum("deadbeef");
        a.setPathName("models/foo.collada.xml");
        a.setLastModified(1234567890L);
        Checksum b = new Checksum("cafebabe");
        b.setPathName("textures/bar.png");
        b.setLastModified(987654321L);
        map.put("models/foo.collada.xml", a);
        map.put("textures/bar.png", b);
        list.setChecksums(map);

        StringWriter sw = new StringWriter();
        list.encode(sw);
        String xml = sw.toString();
        assertThat(xml).contains("checksum-list")
                .contains("deadbeef").contains("cafebabe")
                .contains("models/foo.collada.xml").contains("textures/bar.png");

        ChecksumList decoded = ChecksumList.decode(new StringReader(xml));
        // decode() repopulates internalChecksums from the wire list; both paths
        // must round-trip intact.
        assertThat(decoded.getChecksum("models/foo.collada.xml")).isNotNull();
        assertThat(decoded.getChecksum("textures/bar.png")).isNotNull();
        assertThat(decoded.getChecksum("models/foo.collada.xml").getChecksum()).isEqualTo("deadbeef");
        assertThat(decoded.getChecksum("textures/bar.png").getChecksum()).isEqualTo("cafebabe");
    }

    @Test
    void putChecksumAddsToBothListAndMap() {
        ChecksumList list = new ChecksumList();
        Checksum c = new Checksum("abc");
        c.setPathName("x");
        list.putChecksum(c);
        assertThat(list.checksumList).hasSize(1);
        // putChecksum (unlike setChecksums / decode) keys internalChecksums by
        // the checksum value itself -- pinning the 2006-era behaviour, not
        // correcting it.
        assertThat(list.getChecksum("abc")).isSameAs(c);
    }

    @Test
    void getChecksumMapReturnsCopy() {
        ChecksumList list = new ChecksumList();
        Checksum c = new Checksum("zzz");
        c.setPathName("path");
        list.putChecksum(c);
        Map<String, Checksum> snapshot = list.getChecksumMap();
        snapshot.clear();
        assertThat(list.getChecksum("zzz")).isSameAs(c);
    }

    @Test
    void toHexStringPadsLeadingZeroPerByte() {
        // 0x0A -> "0a", 0xFF -> "ff"; matches Checksum.toHexString's
        // Integer.toHexString(256 + (b & 255)).substring(1) trick.
        assertThat(Checksum.toHexString(new byte[]{0x0A, (byte) 0xFF, 0x00})).isEqualTo("0aff00");
    }
}
